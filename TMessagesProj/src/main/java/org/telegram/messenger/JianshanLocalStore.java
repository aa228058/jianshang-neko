package org.telegram.messenger;

import android.content.SharedPreferences;
import android.text.TextUtils;

import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

/**
 * 见山客户端 · 本地伪装 + 撤回保存
 *
 * 设计原则：所有改动只影响本机显示，绝不改变任何发往服务器的数据。
 *  1) 本地保存消息：观察新消息，把文本留在本地，对方撤回后仍可在「已保存消息」里看到；
 *  2) 本地显示手机号 / 简介：按 userId 覆盖渲染值；
 *  3) 本地修改消息文本 / 时间：按 会话+消息id 覆盖渲染值。
 */
public final class JianshanLocalStore {

    private static final String PREF = "jianshan_local";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF, android.content.Context.MODE_PRIVATE);
    }

    private JianshanLocalStore() {
    }

    /* ───────── 开关 ───────── */

    public static boolean isSaveRecalledEnabled() {
        return prefs().getBoolean("save_recalled", true);   // 默认开启
    }

    public static void setSaveRecalledEnabled(boolean value) {
        prefs().edit().putBoolean("save_recalled", value).apply();
    }

    public static boolean isAntiRevokeEnabled() {
        return prefs().getBoolean("anti_revoke", true);   // 默认开启
    }

    public static void setAntiRevokeEnabled(boolean value) {
        prefs().edit().putBoolean("anti_revoke", value).apply();
    }

    public static boolean isResidentEnabled() {
        return prefs().getBoolean("resident", true);   // 默认开启常驻保活
    }

    public static void setResidentEnabled(boolean value) {
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
        return prefs().getBoolean("fake_enabled", true);
    }

    public static void setFakeEnabled(boolean value) {
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

    /* ───────── 撤回标记（用于在消息上提示"对方已撤回"） ───────── */

    public static void markRecalled(int account, int messageId) {
        SharedPreferences p = prefs();
        String key = "recalled_" + account;
        String cur = p.getString(key, "");
        String token = String.valueOf(messageId);
        if (("," + cur + ",").contains("," + token + ",")) {
            return;
        }
        String next = cur.isEmpty() ? token : cur + "," + token;
        if (next.length() > 20000) {
            int cut = next.indexOf(',', next.length() - 15000);
            next = cut > 0 ? next.substring(cut + 1) : next;
        }
        p.edit().putString(key, next).apply();
    }

    public static boolean isRecalled(int account, int messageId) {
        String cur = prefs().getString("recalled_" + account, "");
        return !cur.isEmpty() && ("," + cur + ",").contains("," + messageId + ",");
    }

    /* ───────── 观察新消息（用于撤回后仍可查） ───────── */

    private static boolean installed;

    public static void install() {
        if (installed || ApplicationLoader.applicationContext == null) {
            return;
        }
        installed = true;
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
