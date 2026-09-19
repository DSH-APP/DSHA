package com.deepseekharness.app.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.OverlayController;
import com.deepseekharness.app.R;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;

/**
 * 配置子页：接口、显示与运行行为；设备授权由独立页面管理。
 * 所有开关都落到 ConfigStore / SharedPreferences，并真正影响启动与预览。
 */
public class ConfigFragment extends Fragment {
    // Settings 没有在所有 compileSdk stub 中暴露该常量，使用公开的 action 字符串保持 API 26+ 兼容。
    private static final String ACTION_PICTURE_IN_PICTURE_SETTINGS = "android.settings.PICTURE_IN_PICTURE_SETTINGS";
    private View pictureSettings;
    private TextView pictureStatus;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_config, container, false);
        ConfigStore c = new ConfigStore(requireContext());
        Context ctx = requireContext();
        android.widget.RadioGroup dns=v.findViewById(R.id.config_dns_mode);
        dns.check("ipv4".equals(c.getDnsMode())?R.id.dns_ipv4:"native".equals(c.getDnsMode())?R.id.dns_native:R.id.dns_auto);


        v.findViewById(R.id.config_workspace_entry).setOnClickListener(x -> open(new WorkspaceFragment()));

        v.findViewById(R.id.config_models).setOnClickListener(x->startActivity(new Intent(requireContext(),ModelSetupActivity.class)));
        EditText apiKey = v.findViewById(R.id.config_api_key);
        EditText port = v.findViewById(R.id.config_port);
        CompoundButton checkUpdate = v.findViewById(R.id.config_check_update);
        CompoundButton desktop = v.findViewById(R.id.config_desktop_mode);
        pictureSettings = v.findViewById(R.id.config_picture_in_picture_settings);
        pictureStatus = v.findViewById(R.id.config_picture_in_picture_status);
        pictureSettings.setOnClickListener(x -> openPictureInPictureSettings());
        renderPictureInPictureStatus();
        CompoundButton staticLoader=v.findViewById(R.id.config_static_loader), noSeccomp=v.findViewById(R.id.config_no_seccomp);
        staticLoader.setChecked(c.isProrootStaticLoader());noSeccomp.setChecked(c.isProotSeccompDisabled());
        CompoundButton proroot = v.findViewById(R.id.config_proroot);
        CompoundButton lan = v.findViewById(R.id.config_lan_mode);
        CompoundButton overlay = v.findViewById(R.id.config_overlay_stream);
        Button save = v.findViewById(R.id.config_save);

        // 高级项折叠
        View advBody = v.findViewById(R.id.config_adv_body);
        v.findViewById(R.id.config_adv_header).setOnClickListener(x ->
                advBody.setVisibility(advBody.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        // 回填当前值
        java.util.concurrent.atomic.AtomicReference<com.deepseekharness.app.util.CredentialRead> credential=
                new java.util.concurrent.atomic.AtomicReference<>(c.readApiKey());
        apiKey.setText(credential.get().usable()?credential.get().requireValue():"");
        TextView credentialState=v.findViewById(R.id.config_credential_state);
        Button retryCredential=v.findViewById(R.id.config_credential_retry),removeCredential=v.findViewById(R.id.config_credential_remove);
        retryCredential.setText(com.deepseekharness.app.util.UiText.text("重试读取 API Key"));
        removeCredential.setText(com.deepseekharness.app.util.UiText.text("移除不可用的凭据记录"));
        Runnable renderCredential=()->{
            boolean failed=!credential.get().usable();credentialState.setVisibility(failed?View.VISIBLE:View.GONE);
            credentialState.setText(failed?ConfigStore.credentialMessage(credential.get()):"");
            retryCredential.setVisibility(failed?View.VISIBLE:View.GONE);removeCredential.setVisibility(failed?View.VISIBLE:View.GONE);
        };
        renderCredential.run();
        retryCredential.setOnClickListener(x->{credential.set(c.readApiKey());if(credential.get().usable()&&apiKey.getText().length()==0)apiKey.setText(credential.get().requireValue());renderCredential.run();});
        removeCredential.setOnClickListener(x->new DshaDialogBuilder(ctx)
                .setTitle(com.deepseekharness.app.util.UiText.text("移除不可用的凭据记录？"))
                .setMessage(com.deepseekharness.app.util.UiText.text("仅移除本机保存的 API Key 记录，不删除对话或配置文件。继续使用相关接口时需要重新提供凭据。"))
                .setNegativeButton(com.deepseekharness.app.util.UiText.text("取消"),null)
                .setPositiveButton(com.deepseekharness.app.util.UiText.text("移除"),(dialog,which)->{
                    var lease=com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(com.deepseekharness.app.util.UiText.text("保存配置"));
                    if(lease==null){toast(com.deepseekharness.app.util.UiText.text("有其他操作正在进行，请稍后重试。"));return;}
                    try{lease.run(()->{var current=c.readApiKey();if(current.usable()){credential.set(current);if(apiKey.getText().length()==0)apiKey.setText(current.requireValue());renderCredential.run();return null;}
                        if(c.saveApiKey("")){credential.set(c.readApiKey());apiKey.setText("");renderCredential.run();}
                        else toast(com.deepseekharness.app.util.UiText.text("凭据记录未能保存，原记录已保留。"));return null;});}
                    catch(Exception failure){toast(com.deepseekharness.app.util.UiText.text("凭据记录未能保存，原记录已保留。"));}finally{lease.close();}
                }).show());
        port.setText(c.getPort());
        checkUpdate.setChecked(c.isCheckUpdate());
        desktop.setChecked(c.isDesktopMode());
        CompoundButton gecko = v.findViewById(R.id.config_gecko_core);
        gecko.setVisibility(View.GONE);
        v.findViewById(R.id.config_gecko_hint).setVisibility(com.deepseekharness.app.BuildConfig.LOW_ANDROID ? View.VISIBLE : View.GONE);
        gecko.setChecked(c.isGeckoCore());
        proroot.setChecked(c.isProroot());
        lan.setChecked(c.isLanMode());
        overlay.setChecked(pref(ctx, "overlay_stream", false));
        bindChoice(v,R.id.config_runtime_choice,com.deepseekharness.app.util.UiText.choose("运行方式","Runtime"),new String[]{"proroot","proot"},c.isProroot()?0:1,index->proroot.setChecked(index==0));
        bindChoice(v,R.id.config_dns_choice,com.deepseekharness.app.util.UiText.choose("DNS 解析策略","DNS policy"),new String[]{com.deepseekharness.app.util.UiText.choose("自动（推荐）","Automatic (recommended)"),"IPv4",com.deepseekharness.app.util.UiText.choose("原生","Native")},"ipv4".equals(c.getDnsMode())?1:"native".equals(c.getDnsMode())?2:0,index->dns.check(index==1?R.id.dns_ipv4:index==2?R.id.dns_native:R.id.dns_auto));
        bindChoice(v,R.id.config_core_choice,com.deepseekharness.app.util.UiText.choose("网页内核","Browser engine"),com.deepseekharness.app.BuildConfig.LOW_ANDROID?new String[]{com.deepseekharness.app.util.UiText.choose("自动选择内核","Automatic engine"),"Gecko"}:new String[]{"System WebView"},com.deepseekharness.app.BuildConfig.LOW_ANDROID&&c.isGeckoCore()?1:0,index->gecko.setChecked(index==1));

        // 悬浮条外观与行为（照 1.1.9.1：底色预设 + 不透明度/行数/字号/停留 + 行为开关）
        v.findViewById(R.id.config_overlay_style).setOnClickListener(x -> showOverlayStyleDialog());


        v.findViewById(R.id.config_repo_link).setOnClickListener(x -> openRepo(ctx));

        save.setOnClickListener(x -> {
            final int chosenPort;
            try { chosenPort = com.deepseekharness.app.util.ConfigInput.port(port.getText().toString()); }
            catch (IllegalArgumentException e) { advBody.setVisibility(View.VISIBLE); port.setError(e.getMessage()); port.requestFocus(); return; }
            port.setError(null); apiKey.setError(null);
            com.deepseekharness.app.util.EnvironmentTaskGate.Lease saving =
                    com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire(com.deepseekharness.app.util.UiText.text("保存配置"));
            if (saving == null) { toast(com.deepseekharness.app.util.UiText.text("正在") + com.deepseekharness.app.util.EnvironmentTaskGate.activeKind() + com.deepseekharness.app.util.UiText.text("，完成后再保存配置")); return; }
            try {
            saving.run(() -> {
            String key = apiKey.getText().toString().trim();
            boolean keepUnreadable=!credential.get().usable()&&key.isEmpty();
            if (!keepUnreadable&&!c.saveApiKey(key)) { apiKey.setError(com.deepseekharness.app.util.UiText.text("密钥加密保存失败，原配置已保留，请重试")); return null; }
            if(!keepUnreadable){credential.set(c.readApiKey());renderCredential.run();}
            c.setPort(String.valueOf(chosenPort));
            c.setCheckUpdate(checkUpdate.isChecked());
            c.setDesktopMode(desktop.isChecked());
            if (com.deepseekharness.app.BuildConfig.LOW_ANDROID) c.setGeckoCore(gecko.isChecked());
            c.setProroot(proroot.isChecked());
            c.setProrootStaticLoader(staticLoader.isChecked());c.setProotSeccompDisabled(noSeccomp.isChecked());
            c.setDnsMode(dns.getCheckedRadioButtonId()==R.id.dns_ipv4?"ipv4":dns.getCheckedRadioButtonId()==R.id.dns_native?"native":"auto");
            c.setLanMode(lan.isChecked());
             boolean overlayEnabled = overlay.isChecked();
             setPref(ctx, "overlay_stream", overlayEnabled);
             // The settings switch is also the lifecycle switch for the
             // WindowManager overlay.  Previously only the preference changed;
             // an already visible bar stayed alive after disabling and a newly
             // enabled bar did not receive the refreshed style until a later
             // process event, which looked like the control did nothing.
             if (!overlayEnabled) OverlayController.teardown(ctx.getApplicationContext());
             else if (OverlayController.permitted(ctx)) OverlayController.applyStyleNow(ctx.getApplicationContext());
            applyLanMode(c, lan.isChecked());
            if (lan.isChecked() && getActivity() instanceof MainActivity)
                ((MainActivity) getActivity()).requestLocalNetwork();
            Toast.makeText(ctx, com.deepseekharness.app.util.UiText.text(keepUnreadable?"其他设置已保存；无法读取的 API Key 原记录保持不变。":"已保存；网页显示选项重新进入对话生效，端口与运行时需重启 Web"), Toast.LENGTH_LONG).show();
            if (overlay.isChecked() && !OverlayController.permitted(ctx)) openOverlayPermission();
            return null;
            });
            } catch (Exception e) {
                toast(com.deepseekharness.app.util.UiText.text("配置保存未完成：") + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e)));
            } finally { saving.close(); }
        });

        return v;
    }

    @Override public void onResume() {
        super.onResume();
        renderPictureInPictureStatus();
    }

    @Override public void onDestroyView() {
        pictureSettings = null;
        pictureStatus = null;
        super.onDestroyView();
    }

    private void openPictureInPictureSettings() {
        Context ctx = getContext();
        if (ctx == null) return;
        if (!PictureInPictureActivity.supported(ctx)) {
            Toast.makeText(ctx, R.string.picture_in_picture_unsupported, Toast.LENGTH_LONG).show();
            return;
        }
        Uri app = Uri.parse("package:" + ctx.getPackageName());
        try {
            startActivity(new Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS, app));
        } catch (RuntimeException unavailable) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app)); }
            catch (RuntimeException ignored) { Toast.makeText(ctx, R.string.picture_in_picture_settings_unavailable, Toast.LENGTH_LONG).show(); }
        }
    }

    private void renderPictureInPictureStatus() {
        Context ctx = getContext();
        if (ctx == null || pictureSettings == null || pictureStatus == null) return;
        boolean supported = PictureInPictureActivity.supported(ctx);
        pictureSettings.setEnabled(supported);
        pictureSettings.setAlpha(supported ? 1f : 0.55f);
        pictureStatus.setText(!supported ? R.string.picture_in_picture_unsupported
                : PictureInPictureActivity.allowed(ctx) ? R.string.picture_in_picture_allowed
                : R.string.picture_in_picture_disabled);
    }

    private void openOverlayPermission() {
        try { startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + requireContext().getPackageName()))); }
        catch (Exception e) { toast(com.deepseekharness.app.util.UiText.text("未取得悬浮窗权限，请到系统设置 → 应用 → DSHA → 悬浮窗中允许")); }
    }

    /** LAN 开关真正生效：开启时若 dsh 已鉴权则启动 3081 代理，关闭时停掉监听。 */
    private void applyLanMode(ConfigStore c, boolean on) {
        try {
            if (!on) {
                com.deepseekharness.app.LanProxyService.stopLanListener();
                return;
            }
            HarnessController hc = new HarnessController(requireContext());
            long gen = hc.getWebGeneration();
            if (gen <= 0 || !com.deepseekharness.app.LanProxyService.hasDshAuth(gen)) {
                // dsh 还没起来/还没交换 cookie：等下次进入对话时 HarnessController 自动启动
                return;
            }
            com.deepseekharness.app.LanProxyService.start(
                    hc.proot().getRootfsDir().getAbsolutePath(),
                    requireContext(), hc.getWebPort(), gen);
        } catch (Throwable t) {
            android.util.Log.w("DSHA", com.deepseekharness.app.util.UiText.text("LAN 开关生效失败: ") + t.getMessage());
        }
    }

    private void open(Fragment f) {
        UiMotion.page(requireContext(),getParentFragmentManager().beginTransaction())
                .addToBackStack(null)
                .replace(R.id.fragment_container, f)
                .commit();
    }

    private void showOverlayStyleDialog() { UiMotion.page(requireContext(),getParentFragmentManager().beginTransaction()).replace(R.id.fragment_container,new OverlayFragment()).addToBackStack("config").commit(); }

    private void openRepo(Context ctx) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/qiannianhuanxiang/DSHA")));
        } catch (Exception e) {
            toast(com.deepseekharness.app.util.UiText.text("无法打开浏览器"));
        }
    }

    private boolean pref(Context ctx, String k, boolean def) {
        return ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).getBoolean(k, def);
    }

    private void setPref(Context ctx, String k, boolean v) {
        ctx.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE).edit().putBoolean(k, v).apply();
    }

    private void toast(String s) {
        Context context = getContext();
        if (context != null) Toast.makeText(context, s, Toast.LENGTH_SHORT).show();
    }
    private void bindChoice(View root,int id,String prompt,String[] labels,int selected,java.util.function.IntConsumer action){
        DshaSelectView choice=root.findViewById(id);choice.setPrompt(prompt);choice.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),R.layout.item_data_choice,labels));choice.setSelection(selected);
        choice.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(android.widget.AdapterView<?> parent){}public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long item){action.accept(position);}});
    }

}
