package com.deepseekharness.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.util.PluginInstallLink;
import com.deepseekharness.app.util.PluginSource;

/** 网站唤起后显示真实包信息，用户确认后才写入插件配置。 */
public final class PluginInstallActivity extends AppCompatActivity {
    private PluginRepository repository;
    private PluginInstallLink request;
    private Button install;
    private TextView status, details;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); setContentView(R.layout.activity_plugin_install);
        status = findViewById(R.id.link_install_status); details = findViewById(R.id.link_install_details);
        install = findViewById(R.id.link_install_confirm);
        findViewById(R.id.link_install_close).setOnClickListener(v -> { if (repository != null) repository.discardPreview(); finish(); });
        findViewById(R.id.link_install_manage).setOnClickListener(v -> openMain(true));
        try { request = PluginInstallLink.parse(getIntent().getDataString()); }
        catch (IllegalArgumentException e) { status.setText(com.deepseekharness.app.util.UiText.text(e.getMessage())); install.setEnabled(false); return; }
        if (!request.builtin.isEmpty()) {
            status.setText(com.deepseekharness.app.util.UiText.text("请在插件管理中查看 ") + request.builtin + com.deepseekharness.app.util.UiText.text(" 的安装和启用状态"));
            install.setText(com.deepseekharness.app.util.UiText.text("打开插件管理")); install.setOnClickListener(v -> openMain(true)); return;
        }
        repository = new ViewModelProvider(this).get(PluginRepository.class);
        repository.state().observe(this, state -> {
            status.setText(com.deepseekharness.app.util.UiStateText.render(state.message)); install.setEnabled(!state.busy);
            renderPreview();
        });
        repository.preview().observe(this, ignored -> renderPreview());
        install.setOnClickListener(v -> {
            if (!HarnessController.get(this).isEnvironmentReady()) {
                getSharedPreferences("dsha-install-link", 0).edit().putString("pending", getIntent().getDataString()).apply();
                openMain(false); finish();
            } else if (repository.preview().getValue() != null) repository.confirmPreview();
            else inspect();
        });
        if (!HarnessController.get(this).isEnvironmentReady()) {
            status.setText(com.deepseekharness.app.util.UiText.text("请先完成 DSHA 首次初始化，完成后会返回插件安装确认页")); install.setText(com.deepseekharness.app.util.UiText.text("初始化 DSHA"));
        } else if (saved == null) inspect();
    }
    private void inspect() { repository.inspect(PluginSource.parse(request.url), request.sha256, request.name, request.version); }
    private void renderPreview() {
        if (repository.installationSucceeded()) {
            details.setText(com.deepseekharness.app.util.UiText.text(repository.installedDescription()));
            install.setText(com.deepseekharness.app.util.UiText.text("安装完成")); install.setEnabled(false); return;
        }
        PluginRepository.Preview preview = repository.preview().getValue();
        if (preview != null) {
            details.setText(preview.description()); install.setText(com.deepseekharness.app.util.UiText.text("确认安装"));
        } else {
            details.setText(com.deepseekharness.app.util.UiText.text("下载并解析插件包后，会显示真实作者、版本和兼容范围。确认安装前不会启用插件。\n\n") + com.deepseekharness.app.util.SensitiveData.redact(request.url));
            install.setText(HarnessController.get(this).isEnvironmentReady() ? com.deepseekharness.app.util.UiText.text("解析链接 / 重试") : com.deepseekharness.app.util.UiText.text("初始化 DSHA"));
        }
    }
    private void openMain(boolean plugins) {
        startActivity(new Intent(this, MainActivity.class).putExtra("open_plugins", plugins)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
    }
}
