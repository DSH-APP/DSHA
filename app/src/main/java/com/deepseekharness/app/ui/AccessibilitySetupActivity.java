package com.deepseekharness.app.ui;
import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.*;
import com.deepseekharness.app.util.UiText;
/** 两步屏幕操作设置；权限仍由系统页面和用户决定。 */
public final class AccessibilitySetupActivity extends AppCompatActivity {
    private TextView status;private Button enable;
    @Override protected void onCreate(Bundle state){super.onCreate(state);
        CardPage page=new CardPage(this,t("让助手操作屏幕","Let the assistant use your screen"),t("开启一次，之后直接在对话里说出要做的事。","Enable once, then describe what you want in a conversation."));UiNavigation.addHeader(this,page.root,t("屏幕操作","Screen control"),this::finish);
        LinearLayout first=page.card();page.kv(first,t("第 1 步","Step 1"),t("开启系统服务","Enable the system service"));
        first.addView(page.text(t("点下方按钮 → 已下载的应用 / 服务 → DSHA 配对助手 → 开启「使用服务」，确认系统提示。","Tap below → Downloaded apps / services → DSHA 配对助手 → Enable the service, then confirm the system prompt."),13,R.color.text_secondary));enable=page.button(first,t("去开启屏幕操作","Enable screen control"),true,()->openSettings(this));
        LinearLayout second=page.card();page.kv(second,t("第 2 步","Step 2"),t("返回这里检查","Return here to check"));status=page.text("",13,R.color.text);second.addView(status);page.button(second,t("检查连接","Check connection"),false,this::refresh);
        LinearLayout help=page.card();help.addView(page.text(t("怎么使用","How to use"),16,R.color.text));help.addView(page.text(t("在 DSH 对话中发送：\n“请打开系统设置，查看当前的显示设置。”\n\n首次操作按应用提示确认。你可以随时在系统无障碍设置中关闭服务。读屏与点按无需 Root、Shizuku 或 ADB。截屏需要 Android 11+。","In a DSH conversation, ask:\n“Open system settings and inspect the display settings.”\n\nConfirm the app prompt when requested. You can disable the service in Accessibility settings at any time. Observation and taps require no Root, Shizuku or ADB. Screenshots require Android 11+."),13,R.color.text_secondary));
        page.entry(help,t("找不到开关或开关是灰色","Missing or disabled switch"),t("查看系统设置提示","View system setup tips"),R.drawable.ic_recovery_document,()->CardSheet.show(this,t("开启提示","Setup tips"),t("部分手机路径是：设置 → 更多设置 → 无障碍 → 已下载的服务。\n\nAndroid 13+ 若提示“受限设置”，在系统应用详情右上角菜单允许受限设置，再返回无障碍页面。只有你确认信任已安装的 DSHA 时才启用。\n\n开启后仍未连接，可关闭再开启服务，并允许 DSHA 后台运行。","Some devices use Settings → Additional settings → Accessibility → Downloaded services.\n\nOn Android 13+, if the system reports restricted settings, use the app-info menu to allow restricted settings, then return to Accessibility. Enable only for your trusted DSHA installation.\n\nIf disconnected after enabling, toggle the service off and on and allow DSHA to run in the background.")));
        page.footer.setVisibility(android.view.View.GONE);setContentView(page.root);
    }
    static void openSettings(android.app.Activity activity){try{Intent intent=new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");intent.putExtra(Intent.EXTRA_COMPONENT_NAME,new ComponentName(activity,DshaAccessibilityService.class));activity.startActivity(intent);}catch(RuntimeException missing){try{activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}catch(RuntimeException error){Toast.makeText(activity,t("请打开系统设置 → 无障碍 → DSHA 配对助手。","Open Settings → Accessibility → DSHA 配对助手."),Toast.LENGTH_LONG).show();}}}
    private void refresh(){if(status==null)return;boolean connected=DshaAccessibilityService.connected();boolean allowed=DshaAccessibilityService.enabled(this);status.setText(connected?t("已连接，可以在对话中使用 Computer Use。","Connected. Computer Use is available in conversations."):allowed?t("开关已开启，正在等待系统连接；可重新开关服务。","Enabled, waiting for the system to connect. Try toggling the service."):t("尚未开启，请完成第 1 步。","Not enabled. Complete step 1."));enable.setText(connected?t("查看或关闭系统授权","Review or disable access"):t("去开启屏幕操作","Enable screen control"));}
    @Override protected void onResume(){super.onResume();refresh();}
    private static String t(String zh,String en){return UiText.choose(zh,en);}
}
