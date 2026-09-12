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
import android.widget.CheckBox;
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

        EditText apiKey = v.findViewById(R.id.config_api_key);
        EditText port = v.findViewById(R.id.config_port);
        CheckBox checkUpdate = v.findViewById(R.id.config_check_update);
        CheckBox desktop = v.findViewById(R.id.config_desktop_mode);
        CheckBox proroot = v.findViewById(R.id.config_proroot);
        CheckBox lan = v.findViewById(R.id.config_lan_mode);
        CheckBox overlay = v.findViewById(R.id.config_overlay_stream);
        Button save = v.findViewById(R.id.config_save);

        // 高级项折叠
        View advBody = v.findViewById(R.id.config_adv_body);
        v.findViewById(R.id.config_adv_header).setOnClickListener(x ->
                advBody.setVisibility(advBody.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        // 回填当前值
        apiKey.setText(c.getApiKey());
        port.setText(c.getPort());
        checkUpdate.setChecked(c.isCheckUpdate());
        desktop.setChecked(c.isDesktopMode());
        CheckBox gecko = v.findViewById(R.id.config_gecko_core);
        gecko.setVisibility(com.deepseekharness.app.BuildConfig.LOW_ANDROID ? View.VISIBLE : View.GONE);
        v.findViewById(R.id.config_gecko_hint).setVisibility(com.deepseekharness.app.BuildConfig.LOW_ANDROID ? View.VISIBLE : View.GONE);
        gecko.setChecked(c.isGeckoCore());
        proroot.setChecked(c.isProroot());
        lan.setChecked(c.isLanMode());
        overlay.setChecked(pref(ctx, "overlay_stream", false));
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
            if (!c.saveApiKey(key)) { apiKey.setError(com.deepseekharness.app.util.UiText.text("密钥加密保存失败，原配置已保留，请重试")); return null; }
            c.setPort(String.valueOf(chosenPort));
            c.setCheckUpdate(checkUpdate.isChecked());
            c.setDesktopMode(desktop.isChecked());
            if (com.deepseekharness.app.BuildConfig.LOW_ANDROID) c.setGeckoCore(gecko.isChecked());
            c.setProroot(proroot.isChecked());
            c.setDnsMode(dns.getCheckedRadioButtonId()==R.id.dns_ipv4?"ipv4":dns.getCheckedRadioButtonId()==R.id.dns_native?"native":"auto");
            c.setLanMode(lan.isChecked());
            setPref(ctx, "overlay_stream", overlay.isChecked());
            applyLanMode(c, lan.isChecked());
            if (lan.isChecked() && getActivity() instanceof MainActivity)
                ((MainActivity) getActivity()).requestLocalNetwork();
            Toast.makeText(ctx, com.deepseekharness.app.util.UiText.text("已保存；网页显示选项重新进入对话生效，端口与运行时需重启 Web"), Toast.LENGTH_LONG).show();
            if (overlay.isChecked() && !OverlayController.permitted(ctx)) openOverlayPermission();
            return null;
            });
            } catch (Exception e) {
                toast(com.deepseekharness.app.util.UiText.text("配置保存未完成：") + com.deepseekharness.app.util.SensitiveData.redact(String.valueOf(e)));
            } finally { saving.close(); }
        });

        return v;
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

    private void showOverlayStyleDialog() { OverlayStyleDialog.show(requireContext()); }

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
}
