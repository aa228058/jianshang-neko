package org.telegram.messenger;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * 启动诊断日志 v3。
 *
 * v1 教训：主线程同步写公共目录，可能把主线程拖死。
 * v2 教训：先删后建，如果建失败就一个文件都没有（比没有日志更糟）。
 *
 * v3 策略：
 *   1) 主线程只往「应用私有文件」写，微秒级，绝不会卡；
 *   2) 内存里同时留一份完整文本 BUFFER；
 *   3) 一个后台线程每 3 秒把 BUFFER **整份**写到一个固定名字的文件，
 *      连写多条通道，任何一条成功，用户就一定能拿到日志：
 *        a. 应用外部文件目录 /sdcard/Android/data/<pkg>/files/jianshan_boot.txt（最稳，不需权限）
 *        b. 公共「下载」/Download/jianshan_boot.txt（MediaStore）
 *        c. 上面 a/b 都失败时，用带时间戳的文件名再试
 *      **从不删除**任何已有文件，只覆盖写自己那个。
 *   4) 心跳线程：每 2 秒一行主线程状态，第 12/24 秒转储全部线程堆栈。
 */
public final class JianshanBootLog {

    private static final String FILE_NAME = "jianshan_boot.txt";
    private static final long T0 = android.os.SystemClock.elapsedRealtime();
    private static final StringBuilder BUFFER = new StringBuilder();

    private static volatile boolean installed;
    private static volatile boolean heartbeatStarted;
    private static volatile FileOutputStream privateStream;
    private static volatile File externalFile;
    private static volatile Context appContext;
    private static volatile String channelReport = "尚未尝试";

    private JianshanBootLog() {
    }

    public static void install(final Context context) {
        if (installed || context == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                String pn = android.app.Application.getProcessName();
                if (pn != null && pn.contains("widget")) {
                    return;
                }
            }
        } catch (Throwable ignore) {
        }
        installed = true;
        appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        try {
            File f = new File(appContext.getFilesDir(), FILE_NAME);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            privateStream = new FileOutputStream(f, true);
        } catch (Throwable ignore) {
        }
        // 应用外部文件目录：普通文件写，不需要任何权限
        try {
            File dir = appContext.getExternalFilesDir(null);
            if (dir != null) {
                dir.mkdirs();
                externalFile = new File(dir, FILE_NAME);
                if (externalFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    externalFile.delete();
                }
            }
        } catch (Throwable ignore) {
        }
        startMirror();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            log("!!! CRASH in thread " + t.getName() + " : " + Log.getStackTraceString(e));
            flushNow();
            if (prev != null) {
                prev.uncaughtException(t, e);
            }
        });
        log("======== 见山诊断版 v3 启动 ========");
        log("app=" + appContext.getPackageName() + " | Android " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")"
                + " | " + Build.MANUFACTURER + " " + Build.MODEL + " | abi=" + Build.SUPPORTED_ABIS[0]);
        log("日志落点: /sdcard/Android/data/" + appContext.getPackageName() + "/files/" + FILE_NAME + "  或  /sdcard/Download/" + FILE_NAME);
        dumpExitReasons(appContext);
    }

    /**
     * 上一次进程是怎么死的——Android 自己记着（API 30+）。
     * 原生崩溃时 getTraceInputStream() 给的就是 tombstone 文本，这是唯一不需要 root/adb 的官方通道。
     */
    public static void dumpExitReasons(final Context context) {
        if (Build.VERSION.SDK_INT < 30) {
            log("退出原因: 系统版本 < 30，接口不可用");
            return;
        }
        try {
            android.app.ActivityManager am =
                    (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) {
                log("退出原因: 拿不到 ActivityManager");
                return;
            }
            java.util.List<android.app.ApplicationExitInfo> list =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 8);
            if (list == null || list.isEmpty()) {
                log("退出原因: 没有任何历史记录");
                return;
            }
            int i = 0;
            for (android.app.ApplicationExitInfo info : list) {
                i++;
                log("---- 历史退出 #" + i
                        + " reason=" + info.getReason() + " (" + reasonName(info.getReason()) + ")"
                        + " status=" + info.getStatus()
                        + " importance=" + info.getImportance()
                        + " pss=" + info.getPss() + "KB"
                        + " time=" + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date(info.getTimestamp()))
                        + " desc=" + info.getDescription());
                try {
                    java.io.InputStream is = info.getTraceInputStream();
                    if (is != null) {
                        byte[] buf = new byte[8192];
                        int n;
                        int total = 0;
                        StringBuilder sb = new StringBuilder();
                        while ((n = is.read(buf)) > 0 && total < 262144) {
                            sb.append(new String(buf, 0, n));
                            total += n;
                        }
                        is.close();
                        log("---- 崩溃/ANR 追踪 #" + i + " 开始 ----");
                        for (String line : sb.toString().split("\n")) {
                            log("  | " + line);
                        }
                        log("---- 追踪 #" + i + " 结束 ----");
                    } else {
                        log("  (没有可读的 trace)");
                    }
                } catch (Throwable t) {
                    log("  读 trace 失败: " + t);
                }
            }
        } catch (Throwable t) {
            log("退出原因读取失败: " + t);
        }
    }

    private static String reasonName(int r) {
        switch (r) {
            case 1: return "EXIT_SELF";
            case 2: return "SIGNALED";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH";
            case 5: return "CRASH_NATIVE";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            case 15: return "PACKAGE_STATE_CHANGE";
            case 16: return "PACKAGE_UPDATED";
            default: return "未知";
        }
    }

    public static void step(final String what) {
        log("[+" + (android.os.SystemClock.elapsedRealtime() - T0) + "ms] " + what);
    }

    public static void log(final String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String text = stamp + "  " + line + "\n";
        synchronized (BUFFER) {
            BUFFER.append(text);
        }
        FileOutputStream fos = privateStream;
        if (fos != null) {
            try {
                fos.write(text.getBytes());
                fos.flush();
            } catch (Throwable ignore) {
            }
        }
        Log.e("JianshanBoot", line);
        // 应用外部目录逐行写，保证即使进程马上被杀也有内容
        File ext = externalFile;
        if (ext != null) {
            try {
                FileOutputStream e = new FileOutputStream(ext, true);
                e.write(text.getBytes());
                e.close();
            } catch (Throwable ignore) {
            }
        }
    }

    private static String snapshot() {
        synchronized (BUFFER) {
            return BUFFER.toString();
        }
    }

    /** 把整份日志写进公共「下载」目录，多条通道依次尝试 */
    private static void mirrorOnce(final String text) {
        if (appContext == null) {
            return;
        }
        StringBuilder report = new StringBuilder();
        // 通道 B：公共下载（MediaStore）
        try {
            ContentResolver cr = appContext.getContentResolver();
            Uri uri = null;
            if (Build.VERSION.SDK_INT >= 29) {
                cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        MediaStore.MediaColumns.DISPLAY_NAME + "=?", new String[]{FILE_NAME});
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            } else {
                File legacy = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME);
                FileOutputStream lf = new FileOutputStream(legacy, false);
                lf.write(text.getBytes());
                lf.close();
                report.append("下载(legacy)=OK ");
            }
            if (uri != null) {
                OutputStream os = cr.openOutputStream(uri, "w");
                if (os != null) {
                    os.write(text.getBytes());
                    os.close();
                    report.append("下载=OK ");
                } else {
                    report.append("下载流=空 ");
                }
            } else if (Build.VERSION.SDK_INT >= 29) {
                report.append("下载insert=空 ");
            }
        } catch (Throwable t) {
            report.append("下载失败=").append(t.getClass().getSimpleName()).append(' ');
        }
        // 通道 C：带时间戳的名字兜底
        if (!report.toString().contains("=OK")) {
            try {
                ContentValues v = new ContentValues();
                String name = "jianshan_boot_" + new SimpleDateFormat("MMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                if (Build.VERSION.SDK_INT >= 29) {
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                }
                Uri uri = appContext.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    OutputStream os = appContext.getContentResolver().openOutputStream(uri, "w");
                    if (os != null) {
                        os.write(text.getBytes());
                        os.close();
                        report.append("时间戳文件=OK");
                    }
                }
            } catch (Throwable t) {
                report.append("时间戳失败=").append(t.getClass().getSimpleName());
            }
        }
        report.append(externalFile != null ? " | 外部文件目录=OK" : " | 外部文件目录=无");
        channelReport = report.toString();
    }

    private static void startMirror() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 60; i++) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
                String text = snapshot();
                try {
                    mirrorOnce(text);
                } catch (Throwable ignore) {
                }
            }
        }, "js-mirror");
        t.setDaemon(true);
        t.start();
    }

    /** 崩溃前尽量把整份日志再刷一次 */
    public static void flushNow() {
        try {
            mirrorOnce(snapshot());
            log("[通道状态] " + channelReport);
        } catch (Throwable ignore) {
        }
    }

    public static void startHeartbeat() {
        if (heartbeatStarted) {
            return;
        }
        heartbeatStarted = true;
        Thread t = new Thread(() -> {
            Thread main = Looper.getMainLooper().getThread();
            for (int i = 1; i <= 40; i++) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                log("[hb " + (i * 2) + "s] 主线程 state=" + main.getState());
                if (i == 6 || i == 12) {
                    log("==== 第 " + (i == 6 ? 1 : 2) + " 次转储 ====");
                    dumpThreads();
                }
            }
        }, "js-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    public static void dumpThreads() {
        try {
            Thread main = Looper.getMainLooper().getThread();
            log("---- 主线程堆栈 (" + main.getName() + ", state=" + main.getState() + ") ----");
            for (StackTraceElement e : main.getStackTrace()) {
                log("    at " + e);
            }
            log("---- 其它线程 ----");
            for (Map.Entry<Thread, StackTraceElement[]> en : Thread.getAllStackTraces().entrySet()) {
                Thread th = en.getKey();
                if (th == main) {
                    continue;
                }
                log("  thread " + th.getName() + " state=" + th.getState());
                StackTraceElement[] st = en.getValue();
                for (int i = 0; i < Math.min(st.length, 8); i++) {
                    log("      at " + st[i]);
                }
            }
        } catch (Throwable t) {
            log("dumpThreads failed: " + t);
        }
    }
}
