package org.telegram.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 见山客户端 · Doze 兜底闹钟接收器
 *
 * 屏灭 + Doze 时 Android 会限制后台网络，socket 只能靠周期唤醒维持。
 * 这个 receiver 由 AlarmManager.setAndAllowWhileIdle 每 10 分钟唤醒一次：
 * 抢 10 秒锁 → 全账号 checkConnection + resumeNetworkMaybe → 服务不在就拉起来。
 */
public class JianshanResidentAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_ALARM = "jianshan.resident.alarm";

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            if (!JianshanResidentService.isNeeded()) {
                return;
            }
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("[jianshan-keepalive] alarm fired");
            }
            JianshanResidentService.startIfNeeded();
            JianshanResidentService.pokeNetwork();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }
}
