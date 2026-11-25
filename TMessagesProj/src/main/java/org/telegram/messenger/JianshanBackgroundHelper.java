package org.telegram.messenger;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 见山客户端 · 后台存活引导（系统设置跳转）
 *
 * 三个必须手动放行的开关：电池优化白名单、自启动、通知（+后台弹窗）。
 * 每个都做多 ROM 厂商 Activity 探测 + 兜底跳“应用详情”，跳不过去也不崩。
 */
public final class JianshanBackgroundHelper {

    private JianshanBackgroundHelper() {
    }

    /** 国产 ROM 自启动页（按优先级探测） */
    private static final String[][] AUTOSTART_PAGES = {
            {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
            {"com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
            {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
            {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
            {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
            {"com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity"},
            {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
    };

    /* ───────── 状态查询 ───────── */

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            if (Build.VERSION.SDK_INT < 23) {
                return true;
            }
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable t) {
            FileLog.e(t);
            return false;
        }
    }

    public static boolean notificationsEnabled(Context context) {
        try {
            return androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean canDrawOverlays(Context context) {
        try {
            return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
        } catch (Throwable t) {
            return false;
        }
    }

    /* ───────── 跳转 ───────── */

    /** ① 电池优化白名单 */
    public static void requestIgnoreBatteryOptimizations(Activity activity) {
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                return;
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable t) {
            FileLog.e(t);
            openAppDetails(activity);
        }
    }

    /** ② 自启动 / 后台运行。返回 false 表示没找到厂商页面，已兜底跳应用详情 */
    public static boolean openAutoStart(Activity activity) {
        if (activity == null) {
            return false;
        }
        for (String[] page : AUTOSTART_PAGES) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(page[0], page[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                activity.startActivity(intent);
                return true;
            } catch (Throwable ignore) {
                // 换下一家
            }
        }
        openAppDetails(activity);
        return false;
    }

    /** ③ 通知设置（含通道） */
    public static void openNotificationSettings(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Intent intent;
            if (Build.VERSION.SDK_INT >= 26) {
                intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName());
            } else {
                intent = new Intent("android.settings.APP_NOTIFICATION_SETTINGS")
                        .putExtra("app_package", activity.getPackageName())
                        .putExtra("app_uid", activity.getApplicationInfo().uid);
            }
            activity.startActivity(intent);
            return;
        } catch (Throwable t) {
            FileLog.e(t);
        }
        openAppDetails(activity);
    }

    /** ④ 后台弹出界面 / 悬浮窗 */
    public static void openOverlaySettings(Activity activity) {
        if (activity == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(intent);
                return;
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        openAppDetails(activity);
    }

    /** 见山：跳到系统「无障碍」设置页，让用户手动启用保活服务 */
    public static void openAccessibilitySettings(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Throwable t) {
            FileLog.e(t);
            openAppDetails(activity);
        }
    }

    public static void openAppDetails(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
