package org.telegram.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.LaunchActivity;

/**
 * 见山客户端 · 常驻保活（移植自芒果的 MangoResidentService）
 *
 * 关键点：光挂前台服务没用，Telegram 在后台会把 MTProto 长连接 pause 掉。
 * 所以这里做三件事：
 *   1) 前台服务 + specialUse 类型（dataSync 在 Android 15 有每日时长预算，到点会被掐）；
 *   2) 周期性 ConnectionsManager.resumeNetworkMaybe()（亮屏 10s / 熄屏 30s 一次）；
 *   3) 连接看门狗：连续 4 跳没连上就 checkConnection() + resumeNetworkMaybe() 硬重连。
 * 唤醒锁只在"干活那一下"抓 3 秒，避免常驻持锁发烫。
 */
public final class JianshanResidentService extends Service {

    private static final int NOTIFICATION_ID = 0x4a5253; // "JRS"
    private static final int BATTERY_NOTIFICATION_ID = 0x4a5254;
    private static final String CHANNEL = "jianshan_resident";
    private static final String BATTERY_CHANNEL = "jianshan_battery";
    private static final String ACTION_STOP = "jianshan.resident.stop";

    private static final long TICK_SCREEN_ON = 5_000L;
    private static final long TICK_SCREEN_OFF = 30_000L;
    private static final long NET_WAKE_GAP_ON = 10_000L;
    private static final long NET_WAKE_GAP_OFF = 30_000L;
    private static final long WAKE_LOCK_MS = 3_000L;

    private Handler handler;
    private PowerManager.WakeLock wakeLock;
    private final long[] nextNetWake = new long[UserConfig.MAX_ACCOUNT_COUNT];
    private final int[] deadTicks = new int[UserConfig.MAX_ACCOUNT_COUNT];
    private volatile boolean killed;
    private volatile boolean notificationShown;
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
                    if (cm.getConnectionState() == ConnectionsManager.ConnectionStateConnected) {
                        deadTicks[a] = 0;
                    } else if (++deadTicks[a] >= 4) {
                        deadTicks[a] = 0;
                        cm.checkConnection();
                        cm.resumeNetworkMaybe();
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("[jianshan-keepalive] dead connection, force reconnect account=" + a);
                        }
                    }
                } catch (Throwable ignore) {
                }
                if (now >= nextNetWake[a]) {
                    if (!woke && wakeLock != null) {
                        wakeLock.acquire(WAKE_LOCK_MS);
                        woke = true;
                    }
                    try {
                        ConnectionsManager.getInstance(a).resumeNetworkMaybe();
                    } catch (Throwable ignore) {
                    }
                    nextNetWake[a] = now + netGap;
                }
            }
            if (!notificationShown) {
                showNotification();
                notificationShown = true;
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

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        ApplicationLoader.postInitApplication();
        handler = new Handler(getMainLooper());
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        if (power != null) {
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jianshan:resident");
            wakeLock.setReferenceCounted(false);
        }
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL, "后台常驻",
                    NotificationManager.IMPORTANCE_MIN);
            channel.setSound(null, null);
            channel.enableVibration(false);
            channel.setShowBadge(false);
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
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
        maybePromptBattery();
        handler.removeCallbacks(tick);
        handler.post(tick);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        killed = true;
        if (handler != null) {
            handler.removeCallbacks(tick);
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
                    .setContentText("后台常驻中，收消息更及时")
                    .setPriority(NotificationCompat.PRIORITY_MIN)
                    .setContentIntent(content)
                    .setOngoing(true)
                    .setSilent(true)
                    .setShowWhen(false)
                    .setOnlyAlertOnce(true)
                    .addAction(0, "取消", cancel);
            startForeground(NOTIFICATION_ID, builder.build());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
