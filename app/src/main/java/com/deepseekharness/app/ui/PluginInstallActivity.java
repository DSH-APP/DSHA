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

/** 网站唤起后解析、安装并启用插件；结果保留真实包信息。 */
public final class PluginInstallActivity extends AppCompatActivity {
  private PluginRepository repository;
  private PluginInstallLink request;
  private Button install;
  private TextView status, details;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_plugin_install);
    status = findViewById(R.id.link_install_status);
    details = findViewById(R.id.link_install_details);
    install = findViewById(R.id.link_install_confirm);
    findViewById(R.id.link_install_close)
        .setOnClickListener(
            v -> {
              if (repository != null) repository.discardPreview();
              finish();
            });
    findViewById(R.id.link_install_manage).setOnClickListener(v -> openMain(true));
    try {
      request = PluginInstallLink.parse(getIntent().getDataString());
    } catch (IllegalArgumentException e) {
      status.setText(com.deepseekharness.app.util.UiText.text(e.getMessage()));
      install.setEnabled(false);
      return;
    }
    if (!request.builtin.isEmpty()) {
      status.setText(
          com.deepseekharness.app.util.UiText.format("请在插件管理中查看 %s 的安装和启用状态", request.builtin));
      install.setText(com.deepseekharness.app.util.UiText.text("打开插件管理"));
      install.setOnClickListener(v -> openMain(true));
      return;
    }
    repository = PluginRepository.get(this);
    repository
        .state()
        .observe(
            this,
            state -> {
              status.setText(com.deepseekharness.app.util.UiStateText.render(state.message));
              install.setEnabled(!state.busy);
              renderPreview();
            });
    repository.preview().observe(this, ignored -> renderPreview());
    install.setOnClickListener(
        v -> {
          if (!HarnessController.get(this).isEnvironmentReady()) {
            getSharedPreferences("dsha-install-link", 0)
                .edit()
                .putString("pending", getIntent().getDataString())
                .apply();
            openMain(false);
            finish();
          } else if (repository.preview().getValue() != null) repository.confirmPreview();
          else inspect();
        });
    if (!HarnessController.get(this).isEnvironmentReady()) {
      status.setText(
          com.deepseekharness.app.util.UiText.choose(
              "请先完成 DSHA 首次初始化，完成后会继续安装插件",
              "Complete DSHA setup first; plugin installation will resume afterward"));
      install.setText(com.deepseekharness.app.util.UiText.text("初始化 DSHA"));
    } else if (saved == null) inspect();
  }

  private void inspect() {
    repository.inspect(
        PluginSource.parse(request.url), request.sha256, request.name, request.version);
  }

  private void renderPreview() {
    if (repository.installationSucceeded()) {
      details.setText(repository.installedDescription());
      install.setText(com.deepseekharness.app.util.UiText.text("安装完成"));
      install.setEnabled(false);
      return;
    }
    PluginRepository.Preview preview = repository.preview().getValue();
    if (preview != null) {
      details.setText(preview.description());
      install.setText(
          repository.isBusy()
              ? com.deepseekharness.app.util.UiText.choose("正在安装", "Installing")
              : com.deepseekharness.app.util.UiText.choose("重试安装", "Retry installation"));
      install.setEnabled(!repository.isBusy());
    } else {
      details.setText(
          com.deepseekharness.app.util.UiText.format(
              "下载并解析插件包后自动安装、启用。\n\n%s",
              com.deepseekharness.app.util.SensitiveData.redact(request.url)));
      install.setText(
          HarnessController.get(this).isEnvironmentReady()
              ? com.deepseekharness.app.util.UiText.text("解析链接 / 重试")
              : com.deepseekharness.app.util.UiText.text("初始化 DSHA"));
    }
  }

  private void openMain(boolean plugins) {
    startActivity(
        new Intent(this, MainActivity.class)
            .putExtra("open_plugins", plugins)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
  }
}
