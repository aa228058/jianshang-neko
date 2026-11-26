package tw.nekomimi.nekogram;

import android.content.Context;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;

/**
 * 见山客户端 · 汇总
 *
 * 把所有已登录账号里「私聊的未读」和「群里 @我 / 回复我的未读」聚到一个列表。
 * 只读本地会话缓存，不复制消息、不跨账号搬运数据。
 */
public class JianshanInboxActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private RecyclerListView listView;
    private InboxAdapter adapter;

    private static class Row {
        int account;
        long dialogId;
        String title;
        String subtitle;
        String time;
        int unread;
        boolean isPrivate;
        Object avatarObject;
    }

    private class InboxAdapter extends RecyclerListView.SelectionAdapter {
        private final ArrayList<Row> rows = new ArrayList<>();
        private final Context context;

        InboxAdapter(Context context) {
            this.context = context;
        }

        void setRows(ArrayList<Row> newRows) {
            rows.clear();
            rows.addAll(newRows);
            notifyDataSetChanged();
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout cell = new FrameLayout(context);
            cell.setBackground(Theme.getSelectorDrawable(false));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));

            BackupImageView avatar = new BackupImageView(context);
            avatar.setRoundRadius(AndroidUtilities.dp(24));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(AndroidUtilities.dp(48), AndroidUtilities.dp(48));
            alp.rightMargin = AndroidUtilities.dp(12);
            row.addView(avatar, alp);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(LinearLayout.VERTICAL);
            TextView title = new TextView(context);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            TextView subtitle = new TextView(context);
            subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            subLp.topMargin = AndroidUtilities.dp(2);
            texts.addView(subtitle, subLp);
            row.addView(texts, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView time = new TextView(context);
            time.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            time.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.leftMargin = AndroidUtilities.dp(8);
            row.addView(time, tlp);

            cell.addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            cell.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            cell.setTag(new Holder(avatar, title, subtitle, time));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            Holder h = (Holder) holder.itemView.getTag();
            h.title.setText(row.title);
            h.subtitle.setText(row.subtitle);
            h.time.setText(row.time);
            AvatarDrawable drawable = new AvatarDrawable();
            if (row.avatarObject instanceof TLRPC.User) {
                TLRPC.User user = (TLRPC.User) row.avatarObject;
                drawable.setInfo(user);
                h.avatar.setForUserOrChat(user, drawable);
            } else if (row.avatarObject instanceof TLRPC.Chat) {
                TLRPC.Chat chat = (TLRPC.Chat) row.avatarObject;
                drawable.setInfo(chat);
                h.avatar.setForUserOrChat(chat, drawable);
            } else {
                h.avatar.setImageDrawable(null);
            }
            final Row clickRow = row;
            holder.itemView.setOnClickListener(v -> {
                Bundle args = new Bundle();
                if (DialogObject.isUserDialog(clickRow.dialogId)) {
                    args.putLong("user_id", clickRow.dialogId);
                } else {
                    args.putLong("chat_id", -clickRow.dialogId);
                }
                presentFragment(new ChatActivity(args));
            });
        }
    }

    private static class Holder {
        final BackupImageView avatar;
        final TextView title;
        final TextView subtitle;
        final TextView time;

        Holder(BackupImageView avatar, TextView title, TextView subtitle, TextView time) {
            this.avatar = avatar;
            this.title = title;
            this.subtitle = subtitle;
            this.time = time;
        }
    }

    @Override
    public View createView(Context context) {
        if (actionBar != null) {
            actionBar.setVisibility(View.GONE);
        }

        FrameLayout content = new FrameLayout(context);
        final int statusBar = AndroidUtilities.statusBarHeight;

        TextView title = new TextView(context);
        title.setText("汇总");
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        title.setPadding(AndroidUtilities.dp(20), statusBar + AndroidUtilities.dp(12), AndroidUtilities.dp(20), AndroidUtilities.dp(10));
        content.addView(title, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setPadding(0, statusBar + AndroidUtilities.dp(56), 0, AndroidUtilities.dp(96));
        listView.setClipToPadding(false);
        adapter = new InboxAdapter(context);
        listView.setAdapter(adapter);
        content.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = content;
        loadRows();
        return fragmentView;
    }

    /** 见山：跨账号预热很贵（每个账号一次 loadDialogs + 一份会话列表常驻内存），
     *  所以：① 60 秒节流 ② 每 300ms 只处理一个账号 ③ 不再强制从服务器拉（force=false） */
    private long lastWarmAt;
    private int warmIndex;

    private void warmUpAccounts() {
        final long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastWarmAt < 60_000L) {
            return;
        }
        lastWarmAt = now;
        warmIndex = 0;
        warmNext();
    }

    private void warmNext() {
        if (isPaused || warmIndex >= UserConfig.MAX_ACCOUNT_COUNT || getParentActivity() == null) {
            return;
        }
        final int a = warmIndex++;
        try {
            if (UserConfig.getInstance(a).isClientActivated()) {
                MessagesController mc = MessagesController.getInstance(a);
                if (mc.getAllDialogs().isEmpty()) {
                    // force=false：先用本地库，缺了再让 Telegram 自己按需拉
                    mc.loadDialogs(0, 0, 100, false);
                }
            }
        } catch (Throwable ignore) {
        }
        AndroidUtilities.runOnUIThread(this::warmNext, 300);
    }

    private boolean isPaused = true;

    @Override
    public void onResume() {
        super.onResume();
        isPaused = false;
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload);
        warmUpAccounts();
        loadRows();
    }

    @Override
    public void onPause() {
        super.onPause();
        isPaused = true;
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogsNeedReload);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsNeedReload) {
            final long now = android.os.SystemClock.elapsedRealtime();
            if (now - lastRowsAt < 800L) {
                return;   // 见山：会话列表频繁刷新时别每次都重建全部行
            }
            lastRowsAt = now;
            loadRows();
        }
    }

    private long lastRowsAt;

    private void loadRows() {
        ArrayList<Row> rows = new ArrayList<>();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (!UserConfig.getInstance(a).isClientActivated()) {
                continue;
            }
            MessagesController mc = MessagesController.getInstance(a);
            ArrayList<TLRPC.Dialog> dialogs = mc.getAllDialogs();
            if (dialogs == null) {
                continue;
            }
            for (int i = 0; i < dialogs.size(); i++) {
                TLRPC.Dialog dialog = dialogs.get(i);
                if (dialog == null || dialog.id == 0) {
                    continue;
                }
                boolean isPrivate = DialogObject.isUserDialog(dialog.id);
                boolean relevant = dialog.unread_count > 0 || dialog.unread_mentions_count > 0;
                if (!isPrivate && dialog.unread_mentions_count <= 0) {
                    continue;
                }
                if (!relevant) {
                    continue;
                }
                Row row = new Row();
                row.account = a;
                row.dialogId = dialog.id;
                row.unread = dialog.unread_count + dialog.unread_mentions_count;
                row.isPrivate = isPrivate;
                row.time = android.text.format.DateFormat.getTimeFormat(
                                org.telegram.messenger.ApplicationLoader.applicationContext)
                        .format(new Date((long) dialog.last_message_date * 1000L));
                TLRPC.User user = isPrivate ? mc.getUser(dialog.id) : null;
                TLRPC.Chat chat = isPrivate ? null : mc.getChat(-dialog.id);
                if (isPrivate && user != null) {
                    row.title = UserObject.getUserName(user);
                    row.avatarObject = user;
                } else if (chat != null) {
                    row.title = chat.title;
                    row.avatarObject = chat;
                } else {
                    row.title = "会话";
                }
                row.subtitle = (isPrivate ? "私聊" : "群组") + (row.unread > 0 ? " · " + row.unread + " 条未读" : "");
                rows.add(row);
            }
        }
        Collections.sort(rows, (r1, r2) -> Integer.compare(r2.unread, r1.unread));
        if (adapter != null) {
            adapter.setRows(rows);
        }
    }
}
