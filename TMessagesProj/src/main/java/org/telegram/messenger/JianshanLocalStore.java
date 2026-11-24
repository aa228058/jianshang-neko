package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * 见山客户端 · 本地伪装 + 撤回保存
 *
 * 设计原则：所有改动只影响本机显示，绝不改变任何发往服务器的数据。
 *  1) 本地保存消息：观察新消息，把文本留在本地，对方撤回后仍可在「已保存消息」里看到；
 *  2) 本地显示手机号 / 简介：按 userId 覆盖渲染值；
 *  3) 本地修改消息文本 / 时间：按 会话+消息id 覆盖渲染值。
 *
 * v33 性能修正：原来每次构造 MessageObject 都会走 isRecalled()，
 * 而它每次都要 getString 一个最长 2 万字符的 id 串再做字符串拼接查找，
 * 冷启动要构造上万个 MessageObject → 几十 MB 字符串churn → 打开就卡。
 * 现在：开关状态与 id 集合都常驻内存，isRecalled 退化成一次 HashSet 查找。
 */
public final class JianshanLocalStore {

    private static final String PREF = "jianshan_local";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE);
    }

    private JianshanLocalStore() {
    }

    /* ───────── 开关（内存缓存，避免每次 getBoolean） ───────── */

    private static volatile boolean flagsLoaded;
    private static volatile boolean resident = true;
    private static volatile boolean antiRevoke = true;
    private static volatile boolean saveRecalled = true;
    private static volatile boolean fakeEnabled = true;

    private static void ensureFlags() {
        if (flagsLoaded) {
            return;
        }
        synchronized (JianshanLocalStore.class) {
            if (flagsLoaded) {
                return;
            }
            try {
                SharedPreferences p = prefs();
                resident = p.getBoolean("resident", true);
                antiRevoke = p.getBoolean("anti_revoke", true);
                saveRecalled = p.getBoolean("save_recalled", true);
                fakeEnabled = p.getBoolean("fake_enabled", true);
            } catch (Throwable ignore) {
            }
            flagsLoaded = true;
        }
    }

    public static boolean isSaveRecalledEnabled() {
        ensureFlags();
        return saveRecalled;
    }

    public static void setSaveRecalledEnabled(boolean value) {
        saveRecalled = value;
        prefs().edit().putBoolean("save_recalled", value).apply();
    }

    public static boolean isAntiRevokeEnabled() {
        ensureFlags();
        return antiRevoke;
    }

    public static void setAntiRevokeEnabled(boolean value) {
        antiRevoke = value;
        prefs().edit().putBoolean("anti_revoke", value).apply();
    }

    public static boolean isResidentEnabled() {
        ensureFlags();
        return resident;
    }

    public static void setResidentEnabled(boolean value) {
        resident = value;
        prefs().edit().putBoolean("resident", value).apply();
        try {
            if (value) {
                JianshanResidentService.start(ApplicationLoader.applicationContext);
                JianshanResidentJob.schedule(ApplicationLoader.applicationContext);
            } else {
                JianshanResidentService.stop(ApplicationLoader.applicationContext);
                JianshanResidentJob.cancel(ApplicationLoader.applicationContext);
            }
        } catch (Throwable ignore) {
        }
    }

    public static boolean isFakeEnabled() {
        ensureFlags();
        return fakeEnabled;
    }

    public static void setFakeEnabled(boolean value) {
        fakeEnabled = value;
        prefs().edit().putBoolean("fake_enabled", value).apply();
    }

    /* ───────── 本地伪装 ───────── */

    public static String getLocalPhone(long userId) {
        return prefs().getString("phone_" + userId, null);
    }

    public static void setLocalPhone(long userId, String phone) {
        prefs().edit().putString("phone_" + userId, phone == null ? "" : phone).apply();
    }

    public static String getLocalBio(long userId) {
        return prefs().getString("bio_" + userId, null);
    }

    public static void setLocalBio(long userId, String bio) {
        prefs().edit().putString("bio_" + userId, bio == null ? "" : bio).apply();
    }

    public static String getLocalMessageText(long dialogId, int messageId) {
        return prefs().getString("text_" + dialogId + "_" + messageId, null);
    }

    public static void setLocalMessageText(long dialogId, int messageId, String text) {
        prefs().edit().putString("text_" + dialogId + "_" + messageId, text == null ? "" : text).apply();
    }

    public static int getLocalMessageDate(long dialogId, int messageId) {
        return prefs().getInt("date_" + dialogId + "_" + messageId, 0);
    }

    public static void setLocalMessageDate(long dialogId, int messageId, int date) {
        prefs().edit().putInt("date_" + dialogId + "_" + messageId, date).apply();
    }

    /* ───────── 已保存的撤回/删除消息 ───────── */

    private static final int MAX_SAVED = 800;

    public static void saveMessage(TLRPC.Message message, long dialogId) {
        if (message == null || !isSaveRecalledEnabled()) {
            return;
        }
        String text = message.message;
        if (TextUtils.isEmpty(text)) {
            return;
        }
        String key = "saved_" + message.id;
        SharedPreferences p = prefs();
        if (p.contains(key)) {
            return;
        }
        SharedPreferences.Editor e = p.edit();
        e.putString(key, dialogId + "\u0001" + message.date + "\u0001" + message.from_id + "\u0001" + text.replace('\n', ' '));
        e.putInt("saved_count", Math.min(MAX_SAVED, p.getInt("saved_count", 0) + 1));
        e.apply();
    }

    /** 返回 [dialogId, date, text] 三元组列表，按时间倒序。 */
    public static ArrayList<String[]> getSavedMessages() {
        ArrayList<String[]> out = new ArrayList<>();
        for (java.util.Map.Entry<String, ?> entry : prefs().getAll().entrySet()) {
            String k = entry.getKey();
            if (!k.startsWith("saved_") || !(entry.getValue() instanceof String)) {
                continue;
            }
            String[] parts = ((String) entry.getValue()).split("\u0001", 4);
            if (parts.length < 4) {
                continue;
            }
            out.add(new String[]{parts[0], parts[1], parts[3]});
        }
        out.sort((a, b) -> {
            try {
                return Integer.compare(Integer.parseInt(b[1]), Integer.parseInt(a[1]));
            } catch (Exception e) {
                return 0;
            }
        });
        return out;
    }

    /* ───────── 撤回标记（内存集合 + 懒加载，性能关键路径） ───────── */

    private static final int MAX_RECALLED = 4000;
    /* 诊断：热路径调用量统计（每 2 万次打一行，看它到底被调了多少次） */
    private static final java.util.concurrent.atomic.AtomicLong recalledCalls =
            new java.util.concurrent.atomic.AtomicLong();
    private static volatile long recalledFirstAt;
    private static volatile long recalledFirstElapsed;
    private static final HashMap<Integer, HashSet<Integer>> recalledSets = new HashMap<>();

    private static HashSet<Integer> recalledSet(int account) {
        synchronized (recalledSets) {
            HashSet<Integer> set = recalledSets.get(account);
            if (set != null) {
                return set;
            }
            set = new HashSet<>();
            try {
                String cur = prefs().getString("recalled_" + account, "");
                if (!cur.isEmpty()) {
                    for (String token : cur.split(",")) {
                        if (token.isEmpty()) {
                            continue;
                        }
                        try {
                            set.add(Integer.parseInt(token));
                        } catch (NumberFormatException ignore) {
                        }
                    }
                }
            } catch (Throwable ignore) {
            }
            recalledSets.put(account, set);
            return set;
        }
    }

    public static void markRecalled(int account, int messageId) {
        HashSet<Integer> set = recalledSet(account);
        synchronized (set) {
            if (!set.add(messageId)) {
                return;
            }
            if (set.size() > MAX_RECALLED) {
                // 超量就整体重建，丢掉最旧的一半（HashSet 无序，按数值截断够用）
                HashSet<Integer> fresh = new HashSet<>();
                int keepFrom = 0;
                for (Integer id : set) {
                    if (keepFrom++ >= set.size() - MAX_RECALLED / 2) {
                        fresh.add(id);
                    }
                }
                set.clear();
                set.addAll(fresh);
            }
            StringBuilder sb = new StringBuilder();
            for (Integer id : set) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(id);
            }
            try {
                prefs().edit().putString("recalled_" + account, sb.toString()).apply();
            } catch (Throwable ignore) {
            }
        }
    }

    /** 热路径：每次构造 MessageObject 都会调用，必须是 O(1) */
    public static boolean isRecalled(int account, int messageId) {
        if (!isAntiRevokeEnabled()) {
            return false;
        }
        try {
            long n = recalledCalls.incrementAndGet();
            if (n == 1) {
                recalledFirstAt = System.currentTimeMillis();
                recalledFirstElapsed = android.os.SystemClock.elapsedRealtime();
            } else if (n % 20000 == 0) {
                JianshanDiag.log(ApplicationLoader.applicationContext,
                        "[perf] isRecalled calls=" + n + " 窗口=" +
                                (android.os.SystemClock.elapsedRealtime() - recalledFirstElapsed) / 1000 + "s");
            }
            return recalledSet(account).contains(messageId);
        } catch (Throwable ignore) {
            return false;
        }
    }

    /* ───────── 观察新消息（用于撤回后仍可查） ───────── */

    private static boolean installed;

    public static void install() {
        if (installed || ApplicationLoader.applicationContext == null) {
            return;
        }
        installed = true;
        ensureFlags();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            final int account = a;
            NotificationCenter.getInstance(account).addObserver((id, acc, args) -> {
                if (id != NotificationCenter.didReceiveNewMessages || args.length < 2) {
                    return;
                }
                if (!isSaveRecalledEnabled()) {
                    return;
                }
                try {
                    Object second = args[1];
                    if (!(second instanceof ArrayList)) {
                        return;
                    }
                    @SuppressWarnings("unchecked")
                    ArrayList<MessageObject> arr = (ArrayList<MessageObject>) second;
                    for (int i = 0; i < arr.size(); i++) {
                        MessageObject mo = arr.get(i);
                        if (mo != null && mo.messageOwner != null) {
                            saveMessage(mo.messageOwner, mo.getDialogId());
                        }
                    }
                } catch (Throwable ignore) {
                }
            }, NotificationCenter.didReceiveNewMessages);
        }
    }
}
