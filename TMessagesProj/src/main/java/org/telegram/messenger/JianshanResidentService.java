package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/**
 * 见山客户端 · 常驻保活
 *
 * 用一个最低优先级的前台通知把进程吊住，配合 Telegram 自己的 KeepAliveJob，
 * 在国产 ROM（HyperOS 等）上尽量减少被清理的概率。
 */
public class JianshanResidentService extends Service {

    private static final String CHANNEL_ID = "jianshan_resident";
    private static final int NOTIFICATION_ID = 0x4a53;   // JS

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
        } catch (Throwable ignore) {
        }
    }

    public static void stop(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.stopService(new Intent(context, JianshanResidentService.class));
        } catch (Throwable ignore) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return START_STICKY;
    }

    private Notification buildNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "见山常驻",
                        NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.enableLights(false);
                nm.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent launch = new Intent(this, org.telegram.ui.LaunchActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch, flags);
        builder.setSmallIcon(getApplicationInfo().icon)
                .setContentTitle("见山客户端")
                .setContentText("保持在线，收消息更及时")
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(Notification.PRIORITY_MIN)
                .setContentIntent(pending);
        return builder.build();
    }
}
