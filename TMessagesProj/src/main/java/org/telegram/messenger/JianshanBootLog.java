package org.telegram.messenger;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

/**
 * 启动诊断日志（诊断版专用）。
 *
 * 目的：App 卡开屏时界面根本起不来，设置里的"发送日志"用不了，logcat 又需要电脑。
 * 所以把每一步启动进度 + 卡住时的线程堆栈，直接写到公共「下载」目录的 jianshan_boot.txt，
 * 用户用文件管理器就能取出来。
 */
public final class JianshanBootLog {

    private static final String FILE_NAME = "jianshan_boot.txt";
    private static final long T0 = android.os.SystemClock.elapsedRealtime();

    private static volatile boolean installed;
    private static OutputStream publicStream;
    private static java.io.File privateFile;

    private JianshanBootLog() {
    }

    public static void install(final Context context) {
        if (installed || context == null) {
            return;
        }
        installed = true;
        // 老文件先删掉，保证用户拿到的是这一次启动的
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                context.getContentResolver().delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                        new String[]{FILE_NAME});
            }
        } catch (Throwable ignore) {
        }
        try {
            privateFile = new java.io.File(context.getFilesDir(), FILE_NAME);
            if (privateFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                privateFile.delete();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri != null) {
                    publicStream = context.getContentResolver().openOutputStream(uri, "w");
                }
            } else {
                java.io.File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                publicStream = new java.io.FileOutputStream(new java.io.File(dir, FILE_NAME), false);
            }
        } catch (Throwable ignore) {
        }
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            log("!!! CRASH in thread " + t.getName() + " : " + Log.getStackTraceString(e));
            if (prev != null) {
                prev.uncaughtException(t, e);
            }
        });
        log("======== 见山诊断版启动 ========");
        log("app=" + context.getPackageName() + " | Android " + Build.VERSION.RELEASE + " (sdk " + Build.VERSION.SDK_INT + ")"
                + " | " + Build.MANUFACTURER + " " + Build.MODEL + " | abi=" + Build.SUPPORTED_ABIS[0]);
    }

    public static void step(final String what) {
        log("[+" + (android.os.SystemClock.elapsedRealtime() - T0) + "ms] " + what);
    }

    public static void log(final String line) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        write(stamp + "  " + line + "\n");
        Log.e("JianshanBoot", line);
    }

    private static synchronized void write(final String text) {
        if (privateFile != null) {
            try {
                java.io.FileOutputStream fos = new java.io.FileOutputStream(privateFile, true);
                fos.write(text.getBytes());
                fos.close();
            } catch (Throwable ignore) {
            }
        }
        if (publicStream != null) {
            try {
                publicStream.write(text.getBytes());
                publicStream.flush();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * 卡住时把主线程和所有线程的堆栈打下来——这比任何猜测都直接。
     */
    public static void watchdog(final long delayMs) {
        final Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            log("---- 已过 " + (android.os.SystemClock.elapsedRealtime() - T0) + "ms：主线程仍然没走完，转储堆栈 ----");
            dumpThreads();
            // 再等一会儿看是彻底卡死还是慢
            handler.postDelayed(() -> {
                log("---- 已过 " + (android.os.SystemClock.elapsedRealtime() - T0) + "ms：第二次转储 ----");
                dumpThreads();
            }, 10000);
        }, delayMs);
    }

    public static void dumpThreads() {
        try {
            Thread main = Looper.getMainLooper().getThread();
            log("==== 主线程堆栈 (" + main.getName() + ", state=" + main.getState() + ") ====");
            for (StackTraceElement e : main.getStackTrace()) {
                write("    at " + e + "\n");
            }
            log("==== 全部线程 ====");
            for (Map.Entry<Thread, StackTraceElement[]> en : Thread.getAllStackTraces().entrySet()) {
                Thread t = en.getKey();
                if (t == main) {
                    continue;
                }
                write("  thread " + t.getName() + " state=" + t.getState() + "\n");
                StackTraceElement[] st = en.getValue();
                for (int i = 0; i < Math.min(st.length, 12); i++) {
                    write("      at " + st[i] + "\n");
                }
            }
        } catch (Throwable t) {
            log("dumpThreads failed: " + t);
        }
    }
}
