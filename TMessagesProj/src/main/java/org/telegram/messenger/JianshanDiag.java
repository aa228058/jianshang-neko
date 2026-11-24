package org.telegram.messenger;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import androidx.core.app.NotificationManagerCompat;

import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 见山客户端 · 后台诊断
 *
 * 目的：不猜。“进后台没消息”只有三种可能，这份报告能直接区分：
 *   A) 进程被冻/被杀  → tick 日志在熄屏后就断了，且 ApplicationExitInfo 里有记录
 *   B) 进程活着但 socket 断/被限网 → tick 日志连续，但连接状态不是 Connected
 *   C) 消息到了但通知没出 → tick 正常、连接 Connected，但设备上没有通知（看通知权限/通道/Doze）
 *
 * 日志落在 filesDir/jianshan-keepalive.log（自动截断到 ~256KB），不进相册、不出网。
 */
public final class JianshanDiag {

    private static final String LOG_NAME = "jianshan-keepalive.log";
    private static final long MAX_LOG_BYTES = 256 * 1024L;
    private static final Object LOCK = new Object();

    private JianshanDiag() {
    }

    private static File logFile(Context context) {
        return new File(context.getFilesDir(), LOG_NAME);
    }

    /** 追加一行诊断日志（线程安全，失败静默） */
    public static void log(Context context, String line) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            FileOutputStream out = null;
            try {
                File f = logFile(context);
                if (f.exists() && f.length() > MAX_LOG_BYTES) {
                    // 简单轮转：留后一半
                    byte[] keep = readTail(f, (int) (MAX_LOG_BYTES / 2));
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    FileOutputStream o2 = new FileOutputStream(f);
                    o2.write(keep);
                    o2.close();
                }
                out = new FileOutputStream(f, true);
                String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
                out.write((ts + " " + line + "\n").getBytes());
            } catch (Throwable ignore) {
            } finally {
                if (out != null) {
                    try {
                        out.close();
                    } catch (IOException ignore) {
                    }
                }
            }
        }
    }

    private static byte[] readTail(File f, int max) {
        try {
            long len = f.length();
            long skip = Math.max(0, len - max);
            FileInputStream in = new FileInputStream(f);
            long skipped = 0;
            while (skipped < skip) {
                long s = in.skip(skip - skipped);
                if (s <= 0) {
                    break;
                }
                skipped += s;
            }
            byte[] buf = new byte[(int) Math.min(max, len - skipped)];
            int read = in.read(buf);
            in.close();
            if (read <= 0) {
                return new byte[0];
            }
            byte[] out = new byte[read];
            System.arraycopy(buf, 0, out, 0, read);
            return out;
        } catch (Throwable t) {
            return new byte[0];
        }
    }

    private static String tail(Context context, int lines) {
        try {
            File f = logFile(context);
            if (!f.exists()) {
                return "(暂无日志)";
            }
            byte[] data = readTail(f, 64 * 1024);
            String[] all = new String(data).split("\n");
            int from = Math.max(0, all.length - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = from; i < all.length; i++) {
                sb.append(all[i]).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(日志读取失败)";
        }
    }

    private static String exitReasons(Context context) {
        if (Build.VERSION.SDK_INT < 30) {
            return "(Android 10 及以下没有 ApplicationExitInfo)";
        }
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                return "(取不到 ActivityManager)";
            }
            java.util.List<ActivityManager.ApplicationExitInfo> list =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8);
            if (list == null || list.isEmpty()) {
                return "(没有进程死亡记录 = 进程一直是活的)";
            }
            StringBuilder sb = new StringBuilder();
            SimpleDateFormat fmt = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
            for (ActivityManager.ApplicationExitInfo info : list) {
                sb.append("  ").append(fmt.format(new Date(info.getTimestamp())))
                        .append(" reason=").append(reasonText(info.getReason()))
                        .append(" status=").append(info.getStatus())
                        .append(" desc=").append(info.getDescription() == null ? "-" : info.getDescription())
                        .append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(读取失败: " + t + ")";
        }
    }

    private static String reasonText(int reason) {
        switch (reason) {
            case ActivityManager.ApplicationExitInfo.REASON_EXIT_SELF:
                return "正常退出(EXIT_SELF)";
            case ActivityManager.ApplicationExitInfo.REASON_SIGNALED:
                return "被信号杀(SIGNALED)";
            case ActivityManager.ApplicationExitInfo.REASON_LOW_MEMORY:
                return "内存不足(LOW_MEMORY)";
            case ActivityManager.ApplicationExitInfo.REASON_CRASH:
                return "崩溃(CRASH)";
            case ActivityManager.ApplicationExitInfo.REASON_CRASH_NATIVE:
                return "原生崩溃(CRASH_NATIVE)";
            case ActivityManager.ApplicationExitInfo.REASON_ANR:
                return "无响应(ANR)";
            case ActivityManager.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
                return "初始化失败";
            case ActivityManager.ApplicationExitInfo.REASON_PERMISSION_CHANGE:
                return "权限变更";
            case ActivityManager.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE:
                return "资源占用过高被系统杀(EXCESSIVE_RESOURCE_USAGE)";
            case ActivityManager.ApplicationExitInfo.REASON_USER_REQUESTED:
                return "用户/清理器主动结束(USER_REQUESTED)";
            case ActivityManager.ApplicationExitInfo.REASON_OTHER:
                return "OTHER";
            default:
                return String.valueOf(reason);
        }
    }

    /** 生成完整报告文本 */
    public static String buildReport(Context context) {
        StringBuilder sb = new StringBuilder();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sb.append("===== 见山客户端 · 后台诊断 =====\n");
        sb.append("时间: ").append(fmt.format(new Date())).append('\n');
        sb.append("包名: ").append(context.getPackageName()).append('\n');
        try {
            android.content.pm.PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            sb.append("版本: ").append(pi.versionName).append(" (").append(pi.versionCode).append(")\n");
        } catch (Throwable ignore) {
        }
        sb.append("机型: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("进程已存活: ").append((SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000).append(" 秒\n");

        sb.append("\n--- 保活状态 ---\n");
        sb.append("常驻开关: ").append(JianshanResidentService.isNeeded()).append('\n');
        sb.append("服务 running: ").append(JianshanResidentService.isRunning()).append('\n');
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                sb.append("亮屏: ").append(pm.isInteractive()).append('\n');
                if (Build.VERSION.SDK_INT >= 23) {
                    sb.append("Doze(deviceIdle): ").append(pm.isDeviceIdleMode()).append('\n');
                    sb.append("省电模式: ").append(pm.isPowerSaveMode()).append('\n');
                    sb.append("电池优化白名单: ").append(pm.isIgnoringBatteryOptimizations(context.getPackageName())).append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("电源状态读取失败: ").append(t).append('\n');
        }

        sb.append("\n--- 通知状态 ---\n");
        try {
            sb.append("通知总开关: ").append(NotificationManagerCompat.from(context).areNotificationsEnabled()).append('\n');
        } catch (Throwable t) {
            sb.append("通知开关读取失败: ").append(t).append('\n');
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                android.app.NotificationManager nm =
                        (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) {
                    android.app.NotificationChannel ch = nm.getNotificationChannel("jianshan_resident2");
                    sb.append("常驻通道 jianshan_resident2: ")
                            .append(ch == null ? "不存在(!!)" : ("importance=" + ch.getImportance())).append('\n');
                    android.app.NotificationChannel old = nm.getNotificationChannel("jianshan_resident");
                    sb.append("旧通道 jianshan_resident: ").append(old == null ? "已删除" : ("importance=" + old.getImportance())).append('\n');
                }
            } catch (Throwable t) {
                sb.append("通道读取失败: ").append(t).append('\n');
            }
        }

        sb.append("\n--- 各账号连接 ---\n");
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            try {
                UserConfig uc = UserConfig.getInstance(a);
                if (!uc.isClientActivated()) {
                    continue;
                }
                ConnectionsManager cm = ConnectionsManager.getInstance(a);
                sb.append("  #").append(a)
                        .append(" uid=").append(uc.getClientUserId())
                        .append(" state=").append(connectionText(cm.getConnectionState()))
                        .append(" paused=").append(cm.getPauseTime() != 0)
                        .append('\n');
            } catch (Throwable ignore) {
            }
        }

        sb.append("\n--- 最近进程死亡记录（决定是不是被系统杀的）---\n");
        sb.append(exitReasons(context));

        sb.append("\n--- 保活日志（最后 220 行）---\n");
        sb.append(tail(context, 220));
        sb.append("===== 报告结束 =====\n");
        return sb.toString();
    }

    private static String connectionText(int state) {
        switch (state) {
            case ConnectionsManager.ConnectionStateWaitingForNetwork:
                return "WaitingForNetwork";
            case ConnectionsManager.ConnectionStateConnecting:
                return "Connecting";
            case ConnectionsManager.ConnectionStateConnectingToProxy:
                return "ConnectingToProxy";
            case ConnectionsManager.ConnectionStateUpdating:
                return "Updating";
            case ConnectionsManager.ConnectionStateConnected:
                return "Connected";
            default:
                return String.valueOf(state);
        }
    }

    /** 生成报告并复制到剪贴板，返回报告全文 */
    public static String copyReportToClipboard(Context context) {
        String report = buildReport(context);
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("jianshan-diag", report));
            }
        } catch (Throwable ignore) {
        }
        log(context, "[diag] report copied, len=" + report.length());
        return report;
    }
}
