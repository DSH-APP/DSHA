package com.deepseekharness.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.plugins.PluginOperationQueue;
import com.deepseekharness.app.util.PluginInstallLink;
import com.deepseekharness.app.util.PluginSource;

/** 网站唤起的安装页只跟踪自己的操作身份；关闭或重建页面不停止后台任务。 */
public final class PluginInstallActivity extends AppCompatActivity {
  private com.deepseekharness.app.plugins.PluginManagementEngine repository;
  private PluginInstallLink request;
  private String operationId;
  private Button install;
  private TextView status, details;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    setContentView(R.layout.activity_plugin_install);
    PressFeedback.attachButtons(findViewById(android.R.id.content));
    status = findViewById(R.id.link_install_status);
    details = findViewById(R.id.link_install_details);
    install = findViewById(R.id.link_install_confirm);
    operationId = saved == null ? null : saved.getString("pluginOperationId");
    findViewById(R.id.link_install_close).setOnClickListener(v -> finish());
    findViewById(R.id.link_install_manage).setOnClickListener(v -> openMain(true));
    try {
      request = PluginInstallLink.parse(getIntent().getDataString());
    } catch (IllegalArgumentException error) {
      status.setText(com.deepseekharness.app.util.UiText.text(error.getMessage()));
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
    repository = com.deepseekharness.app.plugins.PluginManagementEngine.get(this);
    repository.state().observe(this, ignored -> renderOperation());
    install.setOnClickListener(
        v -> {
          if (!HarnessController.get(this).isEnvironmentReady()) {
            getSharedPreferences("dsha-install-link", 0)
                .edit()
                .putString("pending", getIntent().getDataString())
                .apply();
            openMain(false);
            finish();
          } else inspect();
        });
    if (!HarnessController.get(this).isEnvironmentReady()) {
      status.setText(
          com.deepseekharness.app.util.UiText.choose(
              "请先完成首次初始化", "Complete DSHA setup first; plugin installation will resume afterward"));
      install.setText(com.deepseekharness.app.util.UiText.text("初始化 DSHA"));
    } else if (saved == null) inspect();
    else renderOperation();
  }

  @Override
  protected void onSaveInstanceState(Bundle saved) {
    super.onSaveInstanceState(saved);
    if (operationId != null) saved.putString("pluginOperationId", operationId);
  }

  private void inspect() {
    PluginOperationQueue.Operation previous = repository.operation(operationId);
    if (previous != null && previous.pending()) return;
    operationId =
        repository.inspect(
            PluginSource.parse(request.url), request.sha256, request.name, request.version);
    renderOperation();
  }

  private void renderOperation() {
    PluginOperationQueue.Operation operation = repository.operation(operationId);
    PluginRepository.Preview preview = repository.operationPreview(operationId);
    if (preview != null) details.setText(preview.description());
    else
      details.setText(
          com.deepseekharness.app.util.UiText.format(
              "下载并解析插件包后自动安装、启用。\n\n%s",
              com.deepseekharness.app.util.SensitiveData.redact(request.url)));
    if (operation != null) {
      // 状态与按钮标签变化走 150ms 淡入（审计 F-08）。
      UiMotion.crossFadeText(
          status, com.deepseekharness.app.util.UiStateText.render(operation.message));
      boolean installed =
          operation.phase == PluginOperationQueue.Phase.SUCCEEDED
              && operation.transactionId != null
              && !operation.transactionId.isEmpty();
      install.setEnabled(!operation.pending() && !installed);
      UiMotion.crossFadeText(
          install,
          com.deepseekharness.app.util.UiText.text(
              installed ? "安装完成" : operation.pending() ? "正在安装" : "解析链接 / 重试"));
    } else {
      if (operationId != null)
        UiMotion.crossFadeText(
            status,
            com.deepseekharness.app.util.UiText.choose(
                "应用进程已重建；原任务结果未知，请先到插件管理核对。不会自动重放安装。",
                "The app process was recreated. Verify the previous result in plugin management; installation will not replay automatically."));
      install.setText(com.deepseekharness.app.util.UiText.text("解析链接 / 重试"));
      install.setEnabled(true);
    }
  }

  private void openMain(boolean plugins) {
    startActivity(MainActivity.navigationIntent(this).putExtra("open_plugins", plugins));
  }
}
