package tw.nekomimi.nekogram.settings;

import android.app.Activity;
import android.content.Context;
import android.view.View;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.JianshanBackgroundHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

/**
 * 见山客户端 · 后台存活引导
 *
 * 国产 ROM 上光有前台服务不够，还要手动放行三件事：
 *   ① 电池优化白名单  ② 自启动/后台运行  ③ 通知 + 后台弹出界面
 * 这三步没做完，保活代码写得再好也会被冻。
 */
public class JianshanBackgroundGuideActivity extends BaseNekoSettingsActivity {

    private final int batteryRow = rowId++;
    private final int autostartRow = rowId++;
    private final int notifyRow = rowId++;
    private final int overlayRow = rowId++;
    private final int retestRow = rowId++;

    @Override
    protected String getActionBarTitle() {
        return "后台存活引导";
    }

    private Context ctx() {
        Activity activity = getParentActivity();
        return activity != null ? activity : ApplicationLoader.applicationContext;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean batteryOk = JianshanBackgroundHelper.isIgnoringBatteryOptimizations(ctx());
        boolean notifyOk = JianshanBackgroundHelper.notificationsEnabled(ctx());
        boolean overlayOk = JianshanBackgroundHelper.canDrawOverlays(ctx());

        items.add(UItem.asHeader("三步，缺一不可"));
        items.add(UItem.asButton(batteryRow, "① 电池优化白名单：允许后台", batteryOk ? "已放行" : "去打开"));
        items.add(UItem.asShadow("没进白名单，熄屏十几秒后系统就把进程冻住，长连接跟着断，消息和通知都会晚到。"));
        items.add(UItem.asButton(autostartRow, "② 自启动 / 后台运行", "去打开"));
        items.add(UItem.asShadow("自启没开，进程被清掉后只能等你自己再点开 App，期间收不到任何消息。"));
        items.add(UItem.asButton(notifyRow, "③ 通知权限（含「消息常驻」通道）", notifyOk ? "已开启" : "去打开"));
        items.add(UItem.asShadow("通知被关掉时，保活和连接都正常，但消息不会上屏——看起来就像“通知跟不上”。"));
        items.add(UItem.asButton(overlayRow, "④ 后台弹出界面 / 悬浮窗", overlayOk ? "已允许" : "去打开"));
        items.add(UItem.asShadow("仅部分 ROM 需要；不开的话弹窗提醒会被系统拦掉。"));
        items.add(UItem.asButton(retestRow, "重新检测", "刷新"));
        items.add(UItem.asShadow("做完上面几条回到这里点「重新检测」，状态变成已放行就说明生效了。国产 ROM 升级后可能被重置，隔段时间回来复查一次。"));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        final int id = item.id;
        Activity activity = getParentActivity();
        if (activity == null) {
            return;
        }
        if (id == batteryRow) {
            JianshanBackgroundHelper.requestIgnoreBatteryOptimizations(activity);
            return;
        }
        if (id == autostartRow) {
            if (!JianshanBackgroundHelper.openAutoStart(activity)) {
                android.widget.Toast.makeText(activity,
                        "这台机器没找到自启动页，已打开应用详情，请在「权限 / 后台」里手动放行",
                        android.widget.Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (id == notifyRow) {
            JianshanBackgroundHelper.openNotificationSettings(activity);
            return;
        }
        if (id == overlayRow) {
            JianshanBackgroundHelper.openOverlaySettings(activity);
            return;
        }
        if (id == retestRow) {
            if (listView != null && listView.adapter != null) {
                listView.adapter.update(true);
            }
            return;
        }
    }
}
