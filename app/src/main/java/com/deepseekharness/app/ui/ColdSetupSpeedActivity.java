package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** 用户可复现的全流程计时；只在独立空目录安装，保留当前环境与数据。 */
public final class ColdSetupSpeedActivity extends androidx.appcompat.app.AppCompatActivity {
  private TextView status;
  private Button start;
  private Button back;
  private Thread worker;
  private boolean running;

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    getOnBackPressedDispatcher()
        .addCallback(
            this,
            new androidx.activity.OnBackPressedCallback(true) {
              @Override
              public void handleOnBackPressed() {
                if (!running) finish();
                else cancel();
              }
            });
    CardPage page =
        new CardPage(
            this,
            UiText.choose("首次安装测速", "First-install timing"),
            UiText.choose(
                "在独立目录完整执行首次安装，保留当前环境、数据和设置。开始时会正常停止 DSH 和终端；测速期间请保持应用在前台。",
                "Run a complete first install in an isolated directory while keeping the existing environment, data and settings. DSH and terminals stop normally before timing. Keep the app in the foreground."));
    setContentView(page.root);
    status =
        page.text(
            com.deepseekharness.app.runtime.ColdSetupTiming.report(this, false), 14, R.color.text);
    page.content.addView(status);
    start = page.button(page.footer, UiText.choose("开始测速", "Start timing"), true, this::run);
    back =
        page.button(
            page.footer,
            UiText.text("返回"),
            false,
            () -> {
              if (!running) finish();
              else cancel();
            });
  }

  private void run() {
    if (running) return;
    running = true;
    start.setEnabled(false);
    back.setText(UiText.text("取消"));
    status.setText(UiText.choose("正在准备独立安装目录…", "Preparing an isolated install directory…"));
    worker =
        new Thread(
            () -> {
              try {
                double seconds =
                    com.deepseekharness.app.runtime.ColdSetupTiming.run(
                        this,
                        value ->
                            runOnUiThread(
                                () ->
                                    status.setText(
                                        com.deepseekharness.app.util.UiStateText.render(value))));
                runOnUiThread(
                    () ->
                        status.setText(
                            UiText.format("完整首次安装完成：%.2f 秒；临时环境已清理，原数据保留。", seconds)
                                + com.deepseekharness.app.runtime.ColdSetupTiming.report(
                                    this, true)));
              } catch (Exception error) {
                runOnUiThread(
                    () ->
                        status.setText(
                            UiText.format(
                                    "测速未完成：%s",
                                    com.deepseekharness.app.util.SensitiveData.redact(
                                        String.valueOf(error)))
                                + com.deepseekharness.app.runtime.ColdSetupTiming.report(
                                    this, false)));
              } finally {
                runOnUiThread(
                    () -> {
                      running = false;
                      start.setEnabled(true);
                      back.setText(UiText.text("返回"));
                    });
              }
            },
            "dsha-cold-timing");
    worker.start();
  }

  private void cancel() {
    Thread active = worker;
    if (active != null && running) active.interrupt();
  }
}
