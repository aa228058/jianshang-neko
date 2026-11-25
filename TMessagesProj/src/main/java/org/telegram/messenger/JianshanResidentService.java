package org.telegram.messenger;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

/**
 * 见山客户端 · 常驻保活（移植自芒果的 MangoResidentService，v-final29 修正版）
 *
 * 三件事：
 *   1) specialUse 前台服务 + onCreate 立刻 startForeground（抢 Android 8+ 的 5 秒预算）；
 *   2) 独立 HandlerThread 上跑 tick：连接看门狗 + resumeNetworkMaybe，绝不占主线程；
 *   3) AlarmManager.setAndAllowWhileIdle 十分钟一次兜底：Doze 里 CPU 被睡死时，
 *      系统按闹钟把进程唤醒一次 → 抢 10 秒锁 + 全账号 resumeNetworkMaybe，把 socket 拉回来。
 *
 * 注意：v-final28 那两个改动（只在未连通时抢锁、每个 tick 构造 20 个 NotificationsController）
 * 会让主线程被拖垮、并且 Doze 里 tick 直接停摆，表现为“进后台一会儿就没消息”，已全部回退。
 */
public final class JianshanResidentService extends Service {

    private static final int NOTIFICATION_ID = 0x4a5253; // "JRS"
    private static final int BATTERY_NOTIFICATION_ID = 0x4a5254;
    private static final int ALARM_REQUEST_CODE = 0x4a5256;
    private static final String CHANNEL = "jianshan_resident2";
    private static final String BATTERY_CHANNEL = "jianshan_battery";
    /** 见山：v-final27 用过的旧通道（IMPORTANCE_MIN，创建后无法提升，只能删掉重建） */
    private static final String CHANNEL_LEGACY = "jianshan_resident";
    private static final String ACTION_STOP = "jianshan.resident.stop";

    private static final long TICK_SCREEN_ON = 15_000L;
    private static final long TICK_SCREEN_OFF = 60_000L;
    private static final long NET_WAKE_GAP_ON = 15_000L;
    private static final long NET_WAKE_GAP_OFF = 30_000L;
    private static final long WAKE_LOCK_MS_ON = 3_000L;
    private static final long WAKE_LOCK_MS_OFF = 10_000L;
    /** Doze 兜底唤醒间隔（setAndAllowWhileIdle 在 Doze 下允许的最小粒度约 9 分钟） */
    private static final long ALARM_INTERVAL_ON_MS = 3 * 60 * 1000L;
    /** 熄屏/后台被冻结时靠这个把进程叫醒；60 秒一次是"能收到消息"和"别太耗电"的折中 */
    private static final long ALARM_INTERVAL_OFF_MS = 60 * 1000L;
    private static final long ALARM_INTERVAL_MS = ALARM_INTERVAL_OFF_MS;
    /** 每 N 个 tick 复位一次前台服务通知（Android 13+ 可被用户划掉） */
    private static final int FGS_REPOST_TICKS = 10;

    private HandlerThread thread;
    private Handler handler;
    private Handler mainHandler;
    private PowerManager.WakeLock wakeLock;
    private final long[] nextNetWake = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private final int[] deadTicks = new int[UserConfig.MAX_ACCOUNT_COUNT];
    private volatile boolean killed;
    private volatile boolean notificationShown;
    private int fgsRepostCounter;
    private long lastTickAt;
    private long lastMemCleanAt;
    private int memLogCounter;
    private int tickCounter;
    private String lastStateSummary;
    /** 见山：检测到长时间冻结后，进入 30 分钟"激进模式"，用 setAlarmClock 抢唤醒 */
    private static volatile long aggressiveUntil;
    private static volatile boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (killed) {
                return;
            }
            if (!isNeeded()) {
                stopSelf();
                return;
            }
            final long now = SystemClock.elapsedRealtime();
            final boolean screenOn = isScreenOn();
            final long netGap = screenOn ? NET_WAKE_GAP_ON : NET_WAKE_GAP_OFF;
            boolean woke = false;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (!UserConfig.getInstance(a).isClientActivated()) {
                    continue;
                }
                try {
                    ConnectionsManager cm = ConnectionsManager.getInstance(a);
                    if (cm.getConnectionState() == ConnectionsManager.ConnectionStateConnectingToProxy
                            && deadTicks[a] >= 1) {
                        // 见山：卡在代理握手上就主动体检并切线路（换节点/代理挂了能自己恢复）
                        ProxyRotationController.pokeProxyCheck();
                    }
                    if (cm.getConnectionState() != ConnectionsManager.ConnectionStateConnected
                            || cm.getPauseTime() != 0) {
                        if (++deadTicks[a] >= 2) {
                            deadTicks[a] = 0;
                            // 硬重置：pause + resume(false) 会重建到所有 DC（含代理）的连接，
                            // 比只调 resumeNetworkMaybe 更能从 ConnectingToProxy(4) 里挣脱
                            ConnectionsManager.native_pauseNetwork(a);
                            ConnectionsManager.native_resumeNetwork(a, false);
                            cm.checkConnection();
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.d("[jianshan-keepalive] dead connection, force reconnect account=" + a);
                            }
                        }
                    } else {
                        deadTicks[a] = 0;
                    }
                } catch (Throwable ignore) {
                }
                if (now >= nextNetWake[a]) {
                    // 每次唤醒网络都抢一下锁：没有锁，熄屏/Doze 里这轮 tick 跑完 CPU 立刻睡，socket 就断粮
                    if (!woke && wakeLock != null) {
                        wakeLock.acquire(screenOn ? WAKE_LOCK_MS_ON : WAKE_LOCK_MS_OFF);
                        woke = true;
                    }
                    try {
                        ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                    } catch (Throwable ignore) {
                    }
                    nextNetWake[a] = now + netGap;
                }
            }
            {
                final long gap = lastTickAt == 0 ? 0 : (now - lastTickAt);
                lastTickAt = now;
                if (gap > 180_000L) {
                    aggressiveUntil = now + 30 * 60 * 1000L;
                    JianshanDiag.log(JianshanResidentService.this, "[freeze] 进程被冻结 " + (gap / 1000) + "s，进入激进唤醒模式 30min");
                }
                // 只在“状态变了 / 间隔异常 / 每 8 跳”时落盘，避免 15 秒一条把 IO 写满
                StringBuilder state = new StringBuilder("#").append(screenOn ? 1 : 0);
                for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                    try {
                        if (!UserConfig.getInstance(a).isClientActivated()) {
                            continue;
                        }
                        final ConnectionsManager cmA = ConnectionsManager.getInstance(a);
                        state.append(' ').append(a).append('=')
                                .append(cmA.getConnectionState())
                                .append("/rx").append(JianshanLinkProbe.ageSeconds(a)).append('s');
                        JianshanLinkProbe.checkSilent(a,
                                cmA.getConnectionState() == ConnectionsManager.ConnectionStateConnected);
                    } catch (Throwable ignore) {
                    }
                }
                final String summary = state.toString();
                tickCounter++;
                if (!summary.equals(lastStateSummary) || gap > 60_000L || tickCounter % 8 == 0) {
                    lastStateSummary = summary;
                    JianshanDiag.log(JianshanResidentService.this,
                            "[tick] " + summary + " gap=" + (gap / 1000) + "s");
                }
            }
            // 见山：这台机器把 MIUI 电量与性能服务禁用了，系统不再帮我们回收图片/表情缓存，
            // 堆会一路涨到 512MB 上限然后 OOM 卡死。所以这里自己做：后台每 5 分钟清一次图片内存缓存，
            // 堆占用超过 85% 立刻清；每 8 个 tick 打一行 [mem] 方便看增长曲线。
            try {
                long used = JianshanDiag.heapUsed();
                long max = JianshanDiag.heapMax();
                if (++memLogCounter >= 8) {
                    memLogCounter = 0;
                    JianshanDiag.log(JianshanResidentService.this, "[mem] " + JianshanDiag.heapStats());
                }
                boolean lowHeap = max > 0 && used * 100 / max >= 85;
                final long cleanGap = JianshanLocalStore.isLowMemMode() ? 60_000L : 5 * 60 * 1000L;
                boolean timeToClean = !screenOn && now - lastMemCleanAt > cleanGap;
                if (lowHeap || timeToClean) {
                    lastMemCleanAt = now;
                    ImageLoader.getInstance().clearMemory();
                    if (lowHeap && !screenOn) {
                        // 只在熄屏时催 GC：前台 GC 会掉帧
                        System.gc();
                    }
                    JianshanDiag.log(JianshanResidentService.this,
                            "[mem] cleared image cache (" + (lowHeap ? "low heap" : "screen off") + ") " + JianshanDiag.heapStats());
                }
            } catch (Throwable ignore) {
            }
            if (!notificationShown || ++fgsRepostCounter >= FGS_REPOST_TICKS) {
                fgsRepostCounter = 0;
                notificationShown = true;
                mainHandler.post(JianshanResidentService.this::showNotification);
            }
            handler.postDelayed(this, screenOn ? TICK_SCREEN_ON : TICK_SCREEN_OFF);
        }
    };

    public static boolean isKeepAliveEnabled() {
        return JianshanLocalStore.isResidentEnabled();
    }

    public static boolean isNeeded() {
        return JianshanLocalStore.isResidentEnabled();
    }

    public static boolean isRunning() {
        return running;
    }

    public static void startIfNeeded() {
        if (running) {
            return;
        }
        if (isNeeded()) {
            start(ApplicationLoader.applicationContext);
        }
    }

    public static void start(Context context) {
        if (context == null) {
            return;
        }
        try {
            Intent intent = new Intent(context, JianshanResidentService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.stopService(new Intent(context, JianshanResidentService.class));
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** 见山：Doze 闹钟/开机广播用。抢 10 秒锁 + 全账号硬连一次，把睡死的 socket 拉回来 */
    public static void pokeNetwork() {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            return;
        }
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jianshan:poke");
                lock.setReferenceCounted(false);
                lock.acquire(10_000);
            }
        } catch (Throwable ignore) {
        }
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            try {
                ConnectionsManager cm = ConnectionsManager.getInstance(a);
                cm.checkConnection();
                cm.resumeNetworkMaybe();
            } catch (Throwable ignore) {
            }
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("[jianshan-keepalive] poke network");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        mainHandler = new Handler(getMainLooper());
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL, "消息常驻",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.setShowBadge(false);
                channel.setDescription("保持后台连接，及时收到新消息通知");
                manager.createNotificationChannel(channel);
                try {
                    manager.deleteNotificationChannel(CHANNEL_LEGACY);
                } catch (Throwable ignore) {
                }
            }
        }
        // 见山：startForegroundService 之后 5 秒内必须 startForeground。
        // 闹钟冷启动进程时，postInitApplication 很慢，先抢前台再去做重活，
        // 否则会被系统判超时杀进程（死亡记录里的 reason=CRASH 就是它）。
        showNotification();
        notificationShown = true;
        JianshanDiag.log(this, "[svc] onCreate (foreground ok)");
        JianshanDiag.installCrashHandler(getApplicationContext());
        thread = new HandlerThread("jianshan-resident");
        thread.start();
        handler = new Handler(thread.getLooper());
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jianshan:resident");
            wakeLock.setReferenceCounted(false);
        }
        final Thread initThread = new Thread(() -> {
            try {
                ApplicationLoader.postInitApplication();
            } catch (Throwable t) {
                FileLog.e(t);
            }
            JianshanDiag.log(JianshanResidentService.this, "[svc] init done");
        }, "jianshan-init");
        initThread.setPriority(Thread.MIN_PRIORITY);
        initThread.start();
        armAlarm();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showNotification();
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            killed = true;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!isNeeded()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        killed = false;
        JianshanDiag.log(this, "[svc] onStartCommand");
        maybePromptBattery();
        armAlarm();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 见山：从最近任务划掉时别让闹钟跟着丢，进程真被杀了也能靠它回来
        try {
            JianshanDiag.log(this, "[svc] onTaskRemoved (被从最近任务划掉)");
            // 见山：被划掉就把图片缓存清掉，减少下次切回来的页错误/重载
            ImageLoader.getInstance().clearMemory();
            armAlarm();
            pokeNetwork();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        JianshanDiag.log(this, "[svc] onDestroy");
        running = false;
        killed = true;
        if (handler != null) {
            handler.removeCallbacks(tick);
        }
        if (thread != null) {
            try {
                thread.quitSafely();
            } catch (Throwable ignore) {
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 见山：只排下一次闹钟（tick 里绝不调用，否则会无限推后） */
    public static void armNext(Context context) {
        if (context == null) {
            return;
        }
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            Intent intent = new Intent(context, JianshanResidentAlarmReceiver.class)
                    .setAction(JianshanResidentAlarmReceiver.ACTION_ALARM);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getBroadcast(context, ALARM_REQUEST_CODE, intent, flags);
            // 见山：熄屏时一律走 setAlarmClock —— 这台机器上 MIUI 连 setExactAndAllowWhileIdle
            // 都会推迟到 288 秒，只有闹钟类（AlarmClock）不敢推。
            final boolean screen = isScreenOn();
            final boolean aggressive = !screen || SystemClock.elapsedRealtime() < aggressiveUntil;
            final long interval = aggressive ? 60_000L
                    : ALARM_INTERVAL_ON_MS;
            long at = System.currentTimeMillis() + interval;
            if (aggressive) {
                // setAlarmClock 的优先级高于普通精确闹钟，厂商基本不敢推迟（代价：状态栏出现闹钟图标）
                PendingIntent show = PendingIntent.getActivity(context, ALARM_REQUEST_CODE + 1,
                        new Intent(context, LaunchActivity.class), flags);
                am.setAlarmClock(new AlarmManager.AlarmClockInfo(at, show), pi);
                JianshanDiag.log(context, "[alarm] armed ALARM_CLOCK in " + (interval / 1000) + "s");
            } else if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                // 精确唤醒：HyperOS 冻结进程时，系统闹钟仍会被派发，这是唯一能穿透冻结的通道
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                JianshanDiag.log(context, "[alarm] armed EXACT in " + (interval / 1000) + "s");
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                JianshanDiag.log(context, "[alarm] armed idle in " + (interval / 1000) + "s");
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /** 见山：Doze 兜底闹钟（setAndAllowWhileIdle 不需要任何特殊权限） */
    private void armAlarm() {
        armNext(this);
    }

    private static boolean isScreenOn() {
        try {
            PowerManager power = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
            return power != null && power.isInteractive();
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                return true;
            }
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 第二层：没进电池优化白名单就弹一条引导通知 */
    private void maybePromptBattery() {
        try {
            if (isIgnoringBatteryOptimizations()) {
                return;
            }
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(BATTERY_CHANNEL,
                        "后台运行", NotificationManager.IMPORTANCE_DEFAULT);
                channel.setSound(null, null);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            Intent batteryIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            PendingIntent allow = PendingIntent.getActivity(this, BATTERY_NOTIFICATION_ID, batteryIntent, flags);
            Intent autoStart = new Intent();
            autoStart.setComponent(new android.content.ComponentName("com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"));
            PendingIntent autoStartPi = null;
            try {
                autoStartPi = PendingIntent.getActivity(this, BATTERY_NOTIFICATION_ID + 1, autoStart, flags);
            } catch (Throwable ignore) {
            }
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, BATTERY_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("让见山客户端在后台活下来")
                    .setContentText("点「允许后台」关闭电池优化，否则后台收消息会被系统掐断")
                    .setStyle(new NotificationCompat.BigTextStyle().bigText(
                            "点「允许后台」关闭电池优化；国产 ROM 还要在「自启动设置」里手动放行。"))
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(allow)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .addAction(0, "允许后台", allow);
            if (autoStartPi != null) {
                builder.addAction(0, "自启动设置", autoStartPi);
            }
            manager.notify(BATTERY_NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private void showNotification() {
        try {
            Intent open = new Intent(this, LaunchActivity.class).setAction("jianshan.resident");
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent content = PendingIntent.getActivity(this, NOTIFICATION_ID, open, flags);
            PendingIntent cancel = PendingIntent.getService(this, NOTIFICATION_ID,
                    new Intent(this, JianshanResidentService.class).setAction(ACTION_STOP), flags);
            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_sync)
                    .setContentTitle("见山客户端")
                    .setContentText("消息常驻中，收消息更及时")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setContentIntent(content)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .addAction(0, "取消", cancel);
            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
