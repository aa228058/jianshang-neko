package org.telegram.messenger;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * 见山客户端 · 无障碍保活（v48）
 *
 * 原理：Android 会把"启用了无障碍服务"的应用视为系统级辅助工具，
 * OOM adj 会被抬高到接近前台服务的水平，MIUI 的一键清理/安全中心清理通常也会跳过它。
 * 这里不读屏、不监听内容（canRetrieveWindowContent=false），只作为一个"锚"。
 */
public final class JianshanAccessibilityService extends AccessibilityService {

    private static volatile boolean connected;

    public static boolean isConnected() {
        return connected;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        JianshanDiag.log(getApplicationContext(), "[ax] accessibility connected（进程优先级已抬高）");
        try {
            JianshanResidentService.startIfNeeded();
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不读屏，什么都不做
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        JianshanDiag.log(getApplicationContext(), "[ax] accessibility unbound");
        return super.onUnbind(intent);
    }
}
