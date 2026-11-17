package tw.nekomimi.nekogram.helpers;

import android.app.Application;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.google.firebase.analytics.FirebaseAnalytics;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.HashMap;

import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryLevel;
import io.sentry.android.core.SentryAndroid;
import io.sentry.protocol.User;
import tw.nekomimi.nekogram.Extra;

public class AnalyticsHelper {
    private static SharedPreferences preferences;

    private static FirebaseAnalytics firebaseAnalytics;

    public static boolean sendBugReport = true;
    public static boolean analyticsDisabled = false;
    public static String userId = null;

    public static void start(Application application) {
        // 见山实验：完全不做 Firebase / Sentry 初始化，验证它们是不是卡死/崩溃的原因
        org.telegram.messenger.JianshanBootLog.step("AnalyticsHelper.start 已被移除（实验组）");
        if (true) {
            analyticsDisabled = true;
            return;
        }
        preferences = application.getSharedPreferences("nekoanalytics", Application.MODE_PRIVATE);
        analyticsDisabled = !Extra.FORCE_ANALYTICS && preferences.getBoolean("analyticsDisabled", false);
        sendBugReport = Extra.FORCE_ANALYTICS || preferences.getBoolean("sendBugReport", true);
        if (analyticsDisabled) {
            FileLog.d("Analytics: userId = disabled");
            return;
        }
        userId = preferences.getString("userId", null);
        if (userId == null || userId.length() < 32) {
            preferences.edit().putString("userId", userId = generateUserID()).apply();
        }
        org.telegram.messenger.JianshanBootLog.step("Analytics: FirebaseAnalytics.getInstance 之前");
        firebaseAnalytics = FirebaseAnalytics.getInstance(application);
        org.telegram.messenger.JianshanBootLog.step("Analytics: FirebaseAnalytics.getInstance 完成");
        firebaseAnalytics.setAnalyticsCollectionEnabled(true);
        firebaseAnalytics.setUserId(userId);
        org.telegram.messenger.JianshanBootLog.step("Analytics: SentryAndroid.init 之前 (dsn=" + (Extra.SENTRY_DSN == null ? "null" : Extra.SENTRY_DSN.length() + "字符") + ")");
        SentryAndroid.init(application, options -> {
            options.setDsn(Extra.SENTRY_DSN);
            options.setEnvironment(BuildConfig.BUILD_TYPE);
            options.setPrintUncaughtStackTrace(true);
            options.getDataCollection().forceDataCollection();
            options.setEnableUserInteractionTracing(true);
            options.setAttachViewHierarchy(true);
            options.setEnableSystemEventBreadcrumbsExtras(true);
            options.setTracesSampleRate(0.01);
        });
        org.telegram.messenger.JianshanBootLog.step("Analytics: SentryAndroid.init 完成");
        var user = new User();
        user.setId(userId);
        Sentry.setUser(user);
        org.telegram.messenger.JianshanBootLog.step("Analytics: 全部完成");

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("Analytics: userId = " + userId);
        }
    }

    private static String generateUserID() {
        return Utilities.generateRandomString(32);
    }

    public static void captureException(Exception e) {
        if (analyticsDisabled || e == null) return;
        Sentry.captureException(e);
    }

    public static void trackFragmentLifecycle(String lifecycle, BaseFragment fragment) {
        if (analyticsDisabled || fragment == null) return;
        var breadcrumb = new Breadcrumb();
        breadcrumb.setType("navigation");
        breadcrumb.setCategory("ui.fragment.lifecycle");
        breadcrumb.setLevel(SentryLevel.INFO);
        breadcrumb.setData("state", lifecycle);
        breadcrumb.setData("screen", getFragmentName(fragment));
        Sentry.addBreadcrumb(breadcrumb);
        if ("created".equals(lifecycle)) {
            firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, null);
        }
    }

    private static String getFragmentName(BaseFragment fragment) {
        var canonicalName = fragment.getClass().getCanonicalName();
        return canonicalName != null ? canonicalName : fragment.getClass().getSimpleName();
    }

    public static void trackEvent(String event, HashMap<String, String> map) {
        if (analyticsDisabled) return;
        Bundle bundle = new Bundle();
        for (String key : map.keySet()) {
            bundle.putString(key, map.get(key));
        }
        firebaseAnalytics.logEvent(event, bundle);
    }

    public static boolean isSettingsAvailable() {
        return !Extra.FORCE_ANALYTICS;
    }

    public static void setAnalyticsDisabled() {
        AnalyticsHelper.analyticsDisabled = true;
        if (BuildConfig.DEBUG) return;
        FirebaseAnalytics.getInstance(ApplicationLoader.applicationContext).setAnalyticsCollectionEnabled(false);
        preferences.edit().putBoolean("analyticsDisabled", true).apply();
    }

    public static void toggleSendBugReport() {
        AnalyticsHelper.sendBugReport = !AnalyticsHelper.sendBugReport;
        if (BuildConfig.DEBUG) return;
        preferences.edit().putBoolean("sendBugReport", sendBugReport).apply();
    }
}
