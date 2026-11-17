package org.telegram.messenger;

import android.os.Build;

/**
 * 见山诊断：把原生崩溃（SIGSEGV/SIGBUS/SIGABRT…）的信号、地址、调用栈写到普通文件里。
 * 卡开屏这类症状如果是 dlopen 阶段挂掉，Java 层什么都记不到，只能靠这个。
 */
public final class JianshanCrashGuard {

    private static boolean loaded;
    private static boolean installed;

    private JianshanCrashGuard() {
    }

    public static synchronized boolean install(String logPath) {
        if (installed) {
            return true;
        }
        if (!loaded) {
            try {
                System.loadLibrary("jsdiag");
                loaded = true;
            } catch (Throwable t) {
                JianshanBootLog.step("崩溃捕获库加载失败: " + t);
                return false;
            }
        }
        try {
            nativeInstall(logPath);
            installed = true;
            JianshanBootLog.step("崩溃捕获已安装 -> " + logPath);
            return true;
        } catch (Throwable t) {
            JianshanBootLog.step("崩溃捕获安装失败: " + t);
            return false;
        }
    }

    private static native void nativeInstall(String path);
}
