package org.telegram.messenger;

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
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 启动诊断日志 v2。
 *
 * 关键改动（v1 的教训）：
 *  1) 主线程只写「应用私有目录」的文件流，写公共下载目录走**独立后台线程**，
 *     这样即使 MediaStore 卡住也不会把主线程拖死（避免我们自己制造卡顿）。
 *  2) 增加心跳线程：每 2 秒记一次主线程状态，并在第 12/24 秒转储堆栈。
 *     进程被杀 → 心跳停止；主线程卡死 → 心跳继续但状态是 RUNNABLE/BLOCKED。
 */
public final class JianshanBootLog {

    private static final String FILE_NAME = "jianshan_boot.txt";
    private static final long T0 = android.os.SystemClock.elapsedRealtime();

    private static volatile boolean installed;
    private static volatile boolean heartbeatStarted;
    private static volatile FileOutputStream privateStream;
    private static final LinkedBlockingQueue<String> PUBLIC_QUEUE = new LinkedBlockingQueue<>();

    private JianshanBootLog() {
    }

    public static void install(final Context context) {
        if (installed || context == null) {
            return;
        }
        // 小部件引擎是独立进程，它一启动就会把日志冲掉，只在主进程记
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
        try {
            File f = new File(context.getFilesDir(), FILE_NAME);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            privateStream = new FileOutputStream(f, true);
        } catch (Throwable ignore) {
        }
        // 公共目录镜像：完全在后台线程做，主线程永不被它阻塞
        try {
            final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            Thread t = new Thread(() -> {
                OutputStream os = null;
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        app.getContentResolver().delete(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                                new String[]{FILE_NAME});
                        ContentValues v = new ContentValues();
                        v.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
                        v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                        Uri uri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                        if (uri != null) {
                            os = app.getContentResolver().openOutputStream(uri, "w");
                        }
                    } else {
                        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        os = new FileOutputStream(new File(dir, FILE_NAME), false);
                    }
                } catch (Throwable ignore) {
                }
                final OutputStream out = os;
                while (true) {
                    try {
                        String line = PUBLIC_QUEUE.take();
                        if (out != null) {
                            out.write(line.getBytes());
                            out.flush();
                        }
                    } catch (Throwable ignore) {
                    }
                }
            }, "js-log-mirror");
            t.setDaemon(true);
            t.start();
        } catch (Throwable ignore) {
        }
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            log("!!! CRASH in thread " + t.getName() + " : " + Log.getStackTraceString(e));
            if (prev != null) {
                prev.uncaughtException(t, e);
            }
        });
        log("======== 见山诊断版 v2 启动 ========");
        log("app=" + context.getPackageName() + " | Android " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")"
                + " | " + Build.MANUFACTURER + " " + Build.MODEL + " | abi=" + Build.SUPPORTED_ABIS[0]);
    }

    public static void step(final String what) {
        log("[+" + (android.os.SystemClock.elapsedRealtime() - T0) + "ms] " + what);
    }

    public static void log(final String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String text = stamp + "  " + line + "\n";
        synchronized (JianshanBootLog.class) {
            FileOutputStream fos = privateStream;
            if (fos != null) {
                try {
                    fos.write(text.getBytes());
                    fos.flush();
                } catch (Throwable ignore) {
                }
            }
        }
        try {
            PUBLIC_QUEUE.offer(text);
        } catch (Throwable ignore) {
        }
        Log.e("JianshanBoot", line);
    }

    /** 心跳：区分「进程被杀」和「主线程卡死」 */
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
                for (int i = 0; i < Math.min(st.length, 10); i++) {
                    log("      at " + st[i]);
                }
            }
        } catch (Throwable t) {
            log("dumpThreads failed: " + t);
        }
    }
}
