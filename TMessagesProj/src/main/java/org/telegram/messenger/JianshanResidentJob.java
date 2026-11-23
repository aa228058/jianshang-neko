package org.telegram.messenger;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

/**
 * 见山客户端 · 常驻保活兜底（JobScheduler）
 *
 * 前台服务能保证“活着”，但 ROM 冻结/清理仍会把进程砍掉。
 * 这里挂一个 15 分钟周期、持久化的 Job：进程没了由系统把我们拉回来，
 * 再把 JianshanResidentService 重新 startForeground。
 */
public final class JianshanResidentJob extends JobService {

    private static final int JOB_ID = 0x4a5255; // "JRU"
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    public static void schedule(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 21) {
            return;
        }
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler == null) {
                return;
            }
            JobInfo.Builder builder = new JobInfo.Builder(JOB_ID,
                    new ComponentName(context, JianshanResidentJob.class))
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPersisted(true)
                    .setPeriodic(PERIOD_MS);
            int result = scheduler.schedule(builder.build());
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("[jianshan-keepalive] schedule resident job result=" + result);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public static void cancel(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 21) {
            return;
        }
        try {
            JobScheduler scheduler = (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (scheduler != null) {
                scheduler.cancel(JOB_ID);
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        try {
            if (JianshanResidentService.isNeeded() && !JianshanResidentService.isRunning()) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("[jianshan-keepalive] job wakes resident service");
                }
                JianshanResidentService.start(getApplicationContext());
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
