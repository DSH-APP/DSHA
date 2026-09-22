package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.UpdateRepository;
import com.deepseekharness.app.util.UpdatePolicy;

/** 仅调试包：用独立示例状态渲染实际布局，不执行安装、恢复或设备操作。 */
public final class LayoutPreviewActivity extends AppCompatActivity {
    View canvas;
    private FrameLayout frame;
    @Override protected void attachBaseContext(Context base) {
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.fontScale = LayoutAuditInstrumentation.scale;
        config.screenWidthDp = LayoutAuditInstrumentation.width;
        config.screenHeightDp = LayoutAuditInstrumentation.height;
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp,config.screenHeightDp);
        super.attachBaseContext(base.createConfigurationContext(config));
    }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        frame = new FrameLayout(this);
        setContentView(frame);
        showScene(getIntent().getStringExtra("scene"));
    }
    void showScene(String scene) {
        androidx.fragment.app.Fragment old = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
        if (old != null) getSupportFragmentManager().beginTransaction().remove(old).commitNow();
        frame.removeAllViews();
        if(scene.startsWith("recovery")) {
            StartupRecoveryLayout page=new StartupRecoveryLayout(this,com.deepseekharness.app.util.UiText.choose("启动配置或插件加载失败","Startup configuration or plugin loading failed"),()->{});
            String[] zh={"重试启动","安全启动基础界面","新建配置文件","恢复中断的配置修复","环境安装与修复","查看并下载日志","刷新恢复记录"};
            String[] en={"Retry startup","Start the basic interface in safe mode","Create configuration file","Recover interrupted configuration repair","Environment installation and repair","View and download logs","Refresh recovery records"};
            int[] icons={R.drawable.ic_recovery_refresh,R.drawable.ic_recovery_shield,R.drawable.ic_recovery_new_file,R.drawable.ic_recovery_wrench,R.drawable.ic_recovery_gear,R.drawable.ic_recovery_document,R.drawable.ic_recovery_refresh};
            for(int i=0;i<zh.length;i++)page.action(i<4?page.recovery:page.tools,com.deepseekharness.app.util.UiText.choose(zh[i],en[i]),icons[i],()->{},true);
            if(scene.equals("recovery_empty"))page.label(page.attempts,com.deepseekharness.app.util.UiText.choose("还没有启动记录。下一次启动会自动记录。","No startup records yet. The next start will be recorded automatically."),13,R.color.text_muted);
            else {
                page.action(page.attempts,"2026-09-11 22:03 · "+com.deepseekharness.app.util.UiText.choose("失败","Failed")+" · 4.9s",0,()->{},true);
                page.action(page.snapshots,com.deepseekharness.app.util.UiText.choose("健康启动","Healthy start")+" · 2026-09-11 21:40",0,()->{},true);
                page.pluginCard.setVisibility(View.VISIBLE);page.action(page.plugins,"example-plugin · "+com.deepseekharness.app.util.UiText.choose("卸载","Remove"),0,()->{},true);
            }
            canvas=page.root;frame.addView(canvas,new FrameLayout.LayoutParams(dp(LayoutAuditInstrumentation.width),dp(LayoutAuditInstrumentation.height),Gravity.TOP|Gravity.CENTER_HORIZONTAL));return;
        }
        int layout = getResources().getIdentifier(scene.startsWith("update")?"activity_update":scene.startsWith("plugins")?"fragment_plugins":scene.startsWith("launch")?"fragment_launch":scene,"layout",getPackageName());
        View body = getLayoutInflater().inflate(layout,null,false);
        boolean fragment = scene.startsWith("fragment_") || scene.startsWith("plugins") || scene.startsWith("launch");
        canvas = fragment ? getLayoutInflater().inflate(R.layout.activity_main,null,false) : body;
        if (fragment) {
            ((FrameLayout)canvas.findViewById(R.id.fragment_container)).addView(body,new FrameLayout.LayoutParams(-1,-1));
            String title = scene.contains("device_grants")?"设备能力授权":scene.contains("config")?"配置":scene.contains("workspace")?"数据与备份":scene.contains("install")?"安装与修复":scene.contains("settings")?"设置":scene.startsWith("plugins")?"插件":scene.contains("terminal")?"终端":"启动";
            ((TextView)canvas.findViewById(R.id.app_title)).setText(com.deepseekharness.app.util.UiText.text(title));
            boolean nested = scene.contains("config") || scene.contains("workspace") || scene.equals("fragment_install") || scene.contains("device_grants");
            visible(R.id.sub_back,nested);visible(R.id.app_logo,!nested);
            ((TextView)canvas.findViewById(R.id.btn_theme)).setText(com.deepseekharness.app.util.UiText.text(ThemeController.isDark(this)?"黑夜":"白天"));
            com.google.android.material.bottomnavigation.BottomNavigationView nav = canvas.findViewById(R.id.bottom_nav);
            nav.setSelectedItemId(title.equals("插件")?R.id.nav_plugins:title.equals("终端")?R.id.nav_terminal:title.equals("启动")?R.id.nav_launch:R.id.nav_settings);
        }
        frame.addView(canvas,new FrameLayout.LayoutParams(dp(LayoutAuditInstrumentation.width),dp(LayoutAuditInstrumentation.height),Gravity.TOP|Gravity.CENTER_HORIZONTAL));
        TextView notice = new TextView(this);
        notice.setText("正在检查排版，请暂勿关闭"); notice.setTextSize(12);notice.setGravity(Gravity.CENTER);
        notice.setTextColor(getColor(R.color.text_secondary));notice.setBackgroundColor(getColor(R.color.surface));
        frame.addView(notice,new FrameLayout.LayoutParams(-1,dp(28),Gravity.BOTTOM));
        bind(scene);
        if (scene.equals("fragment_settings")) {
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container,new SettingsFragment()).commitNow();
        }
    }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private void text(int id,String text) { TextView view=canvas.findViewById(id);if(view!=null)view.setText(com.deepseekharness.app.util.UiText.text(text)); }
    private void visible(int id,boolean visible) { View view=canvas.findViewById(id);if(view!=null)view.setVisibility(visible?View.VISIBLE:View.GONE); }
    private void bind(String scene) {
        if (scene.startsWith("update")) {
            ((android.widget.RadioGroup)canvas.findViewById(R.id.update_channels)).check(R.id.update_preview);
            text(R.id.update_current,BuildConfig.VERSION_NAME+" · 版本码 113");
            boolean available=!scene.equals("update_idle");
            UpdatePolicy.Release release=available?new UpdatePolicy.Release(114,"布局样例",UpdatePolicy.PREVIEW,BuildConfig.LOW_ANDROID?"low":"standard",23,"arm64-v8a","https://dsha.cc/example.apk","0".repeat(64),33554432,"本条仅用于测试长版本说明的排版。不会下载或安装样例。","https://dsha.cc/download/"):null;
            boolean busy=scene.equals("update_busy"), ready=scene.equals("update_ready");
            try {
                java.lang.reflect.Constructor<UpdateRepository.State> ctor = UpdateRepository.State.class.getDeclaredConstructor(String.class,boolean.class,long.class,long.class,UpdatePolicy.Release.class,java.io.File.class);
                ctor.setAccessible(true);
                UpdateRepository.State state = ctor.newInstance(available?busy?"正在下载，离开页面后继续":ready?"下载及校验完成，可以安装更新":"下载暂停：网络连接中断，可继续下载":"此通道暂无适合当前设备的更新。",busy,busy?12582912L:0L,available?33554432L:0L,release,ready?new java.io.File(getCacheDir(),"layout-only.apk"):null);
                UpdateUi.render(canvas,state,false);
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        } else if (scene.startsWith("launch")) {
            text(R.id.launch_status,scene.equals("launch_error")?"连续启动失败，自动重启已暂停。可检查插件后再试。":"准备好后点击启动，进入本机对话。");
            text(R.id.launch_log,"[布局样例] 检查运行环境\n[布局样例] 等待启动\n\n本页面不启动 dsh，不执行模型请求。");
            if (scene.equals("launch_error")) { visible(R.id.launch_recovery,true);text(R.id.launch_recovery,"恢复选项");text(R.id.launch_run_state,"自动重启已暂停"); }
        } else if (scene.startsWith("plugins")) {
            boolean management=scene.equals("plugins_installed");
            visible(R.id.pluginMarketCard,!management);visible(R.id.pluginWebsiteSection,!management);visible(R.id.pluginLinkSection,!management);visible(R.id.pluginLocalTitle,!management);visible(R.id.pluginLocalCard,!management);visible(R.id.installedControls,management);
            text(R.id.pluginLinkHint,"支持 npm、GitHub 和已构建压缩包");text(R.id.statusText,"请选择插件来源，解析后核对实际信息。");text(R.id.pluginCount,"共 12 个插件");
            if(management) { visible(R.id.pluginEmpty,true);text(R.id.pluginEmpty,"布局检查样例：插件列表会显示在这里"); }
        } else if (scene.equals("fragment_workspace")) {
            text(R.id.workspace_backup_status,"最近成功备份：今天 14:26\n全量备份 · 2.1 MiB");
        } else if (scene.equals("fragment_config")) {
            text(R.id.config_port,"3080");
        } else if (scene.equals("activity_diagnostics")) {
            text(R.id.diagnostic_status,"检查完成，报告保留在本机。");
            text(R.id.diagnostic_report,"版本：rc1.4（布局样例）\nPython：可用\n证书：可用\nNode：可用\n\n此处只使用独立的显示样例。");
        } else if (scene.equals("activity_plugin_install")) {
            text(R.id.link_install_status,"请解析插件来源并核对实际信息。");
            text(R.id.link_install_details,"来源：仅用于排版检查的示例\n包名：example-plugin\n版本：1.0.0\n\n不会下载或安装测试插件。");
        }
    }
}
