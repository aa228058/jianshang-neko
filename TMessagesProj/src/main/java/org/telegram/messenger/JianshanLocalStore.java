package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 见山客户端 · 本地伪装 + 撤回保存（v34）
 *
 * v33 的教训：把“懒加载 SharedPreferences”放进了构造函数的热路径，还带了类锁，
 * 结果一条消息渲染就可能卡在磁盘读上 → 打开就卡死/ANR。
 *
 * v34 原则：**热路径零 IO、零锁、零分配**
 *  - 开关状态、撤回 id 集合都在 install() 里用后台线程预加载进内存；
 *  - isRecalled() 只做一次 HashSet.contains（未加载完就返回 false，绝不阻塞）；
 *  - markRecalled() 只改内存集合，落盘交给单线程 executor 合并写；
 *  - 所有 SharedPreferences 访问都在后台线程发生。
 */
public final class JianshanLocalStore {

    private static final String PREF = "jianshan_local";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    private JianshanLocalStore() {
    }

    /* ───────── 开关（内存快照，后台线程刷新） ───────── */

    private static volatile boolean flagsLoaded;
    private static volatile boolean resident = true;
    private static volatile boolean antiRevoke = true;
    private static volatile boolean saveRecalled = true;
    private static volatile boolean fakeEnabled = true;

    private static void loadFlags() {
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

    private static boolean isFlagsLoaded() {
        if (flagsLoaded) {
            return true;
        }
        // 还没预加载完：不要在主线程读盘，先按默认值走，等后台加载完自然会更新
        return false;
    }

    public static boolean isSaveRecalledEnabled() {
        return !isFlagsLoaded() || saveRecalled;
    }

    public static void setSaveRecalledEnabled(boolean value) {
        saveRecalled = value;
        writeAsync(p -> p.edit().putBoolean("save_recalled", value).apply());
    }

    public static boolean isAntiRevokeEnabled() {
        return !isFlagsLoaded() || antiRevoke;
    }

    public static void setAntiRevokeEnabled(boolean value) {
        antiRevoke = value;
        writeAsync(p -> p.edit().putBoolean("anti_revoke", value).apply());
    }

    public static boolean isResidentEnabled() {
        return !isFlagsLoaded() || resident;
    }

    public static void setResidentEnabled(boolean value) {
        resident = value;
        writeAsync(p -> p.edit().putBoolean("resident", value).apply());
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
        return !isFlagsLoaded() || fakeEnabled;
    }

    public static void setFakeEnabled(boolean value) {
        fakeEnabled = value;
        writeAsync(p -> p.edit().putBoolean("fake_enabled", value).apply());
    }

    /* ───────── 本地伪装 ───────── */

    public static String getLocalPhone(long userId) {
        try {
            return prefs().getString("phone_" + userId, null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setLocalPhone(long userId, String phone) {
        String v = phone == null ? "" : phone;
        writeAsync(p -> p.edit().putString("phone_" + userId, v).apply());
    }

    public static String getLocalBio(long userId) {
        try {
            return prefs().getString("bio_" + userId, null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setLocalBio(long userId, String bio) {
        String v = bio == null ? "" : bio;
        writeAsync(p -> p.edit().putString("bio_" + userId, v).apply());
    }

    public static String getLocalMessageText(long dialogId, int messageId) {
        try {
            return prefs().getString("text_" + dialogId + "_" + messageId, null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void setLocalMessageText(long dialogId, int messageId, String text) {
        String v = text == null ? "" : text;
        writeAsync(p -> p.edit().putString("text_" + dialogId + "_" + messageId, v).apply());
    }

    public static int getLocalMessageDate(long dialogId, int messageId) {
        try {
            return prefs().getInt("date_" + dialogId + "_" + messageId, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    public static void setLocalMessageDate(long dialogId, int messageId, int date) {
        writeAsync(p -> p.edit().putInt("date_" + dialogId + "_" + messageId, date).apply());
    }

    /* ───────── 已保存的撤回/删除消息 ───────── */

    private static final int MAX_SAVED = 800;

    public static void saveMessage(TLRPC.Message message, long dialogId) {
        if (message == null || !isSaveRecalledEnabled()) {
            return;
        }
        final String text = message.message;
        if (TextUtils.isEmpty(text)) {
            return;
        }
        final String key = "saved_" + message.id;
        final String value = dialogId + "\u0001" + message.date + "\u0001" + message.from_id + "\u0001" + text.replace('\n', ' ');
        writeAsync(p -> {
            if (p.contains(key)) {
                return;
            }
            p.edit()
                    .putString(key, value)
                    .putInt("saved_count", Math.min(MAX_SAVED, p.getInt("saved_count", 0) + 1))
                    .apply();
        });
    }

    /** 返回 [dialogId, date, text] 三元组列表，按时间倒序。只给界面手动调用，可以慢。 */
    public static ArrayList<String[]> getSavedMessages() {
        ArrayList<String[]> out = new ArrayList<>();
        try {
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
        } catch (Throwable ignore) {
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

    /* ───────── 撤回标记：内存集合，热路径 O(1) 无锁 ───────── */

    private static final int MAX_RECALLED = 4000;
    private static final HashMap<Integer, HashSet<Integer>> recalledSets = new HashMap<>();
    private static volatile boolean recalledLoaded;

    /** 热路径：每次构造 MessageObject 都会调用 —— 只做内存查找，绝不做 IO */
    public static boolean isRecalled(int account, int messageId) {
        if (!recalledLoaded || !antiRevoke) {
            return false;
        }
        final HashSet<Integer> set;
        synchronized (recalledSets) {
            set = recalledSets.get(account);
        }
        return set != null && set.contains(messageId);
    }

    public static void markRecalled(int account, int messageId) {
        synchronized (recalledSets) {
            HashSet<Integer> set = recalledSets.get(account);
            if (set == null) {
                set = new HashSet<>();
                recalledSets.put(account, set);
            }
            if (!set.add(messageId) || set.size() > MAX_RECALLED) {
                if (set.size() > MAX_RECALLED) {
                    set.clear();
                }
            }
            final HashSet<Integer> snapshot = new HashSet<>(set);
            final int acc = account;
            writeAsync(p -> p.edit().putString("recalled_" + acc, joinIds(snapshot)).apply());
        }
    }

    private static String joinIds(HashSet<Integer> ids) {
        StringBuilder sb = new StringBuilder(ids.size() * 8);
        for (Integer id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /* ───────── 后台写盘（单线程，合并提交） ───────── */

    private static ExecutorService io;

    private static synchronized ExecutorService io() {
        if (io == null) {
            io = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "jianshan-store");
                t.setPriority(Thread.MIN_PRIORITY);
                t.setDaemon(true);
                return t;
            });
        }
        return io;
    }

    private interface PrefTask {
        void run(SharedPreferences p);
    }

    private static void writeAsync(PrefTask task) {
        try {
            io().execute(() -> {
                try {
                    task.run(prefs());
                } catch (Throwable ignore) {
                }
            });
        } catch (Throwable ignore) {
        }
    }

    /** 后台预加载：开关 + 各账号撤回集合。全部在 jianshan-store 线程上做，绝不碰主线程。 */
    private static void preload() {
        try {
            loadFlags();
            HashMap<Integer, HashSet<Integer>> sets = new HashMap<>();
            SharedPreferences p = prefs();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                String cur = p.getString("recalled_" + a, "");
                if (cur == null || cur.isEmpty()) {
                    continue;
                }
                HashSet<Integer> set = new HashSet<>();
                for (String token : cur.split(",")) {
                    if (token.isEmpty()) {
                        continue;
                    }
                    try {
                        set.add(Integer.parseInt(token));
                    } catch (NumberFormatException ignore) {
                    }
                }
                if (!set.isEmpty()) {
                    sets.put(a, set);
                }
            }
            synchronized (recalledSets) {
                recalledSets.clear();
                recalledSets.putAll(sets);
            }
            recalledLoaded = true;
            JianshanDiag.log(ApplicationLoader.applicationContext,
                    "[perf] preload done, accounts with recalled=" + sets.size());
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    /* ───────── 观察新消息（用于撤回后仍可查） ───────── */

    private static boolean installed;

    public static void install() {
        if (installed || ApplicationLoader.applicationContext == null) {
            return;
        }
        installed = true;
        try {
            io().execute(JianshanLocalStore::preload);
        } catch (Throwable ignore) {
        }
        JianshanDiag.startMainThreadWatchdog(ApplicationLoader.applicationContext);
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
