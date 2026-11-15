package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
    // 2026-11-01 压缩：长说明拆成三条要点，结果单独成卡（原型见 docs/design/install-env-20261101）。
    CardPage page = new CardPage(this, UiText.choose("首次安装测速", "First-install timing"), null);
    setContentView(page.root);
    LinearLayout facts = page.card();
    fact(page, facts, R.drawable.ic_ui2_box, UiText.text("在独立目录执行首次安装，现有环境不变"));
    fact(page, facts, R.drawable.ic_onb_info, UiText.text("开始时会正常停止 DSH 和终端"));
    fact(page, facts, R.drawable.ic_recovery_clock, UiText.text("测速期间请保持应用在前台"));
    page.label(UiText.text("最近一次结果"));
    LinearLayout result = page.card();
    String previous = com.deepseekharness.app.core.ColdSetupTiming.report(this, false).trim();
    status = page.text(previous.isEmpty() ? "—" : previous, 13, R.color.shell_text);
    status.setTypeface(android.graphics.Typeface.MONOSPACE);
    status.setLineSpacing(page.dp(3), 1);
    status.setTextIsSelectable(true);
    status.setPadding(0, page.dp(4), 0, page.dp(4));
    status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
    result.addView(status);
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

  /** 要点行：小图标 + 一句说明，不可点按。 */
  private static void fact(CardPage page, LinearLayout card, int icon, String value) {
    LinearLayout row = new LinearLayout(page.context);
    row.setGravity(android.view.Gravity.TOP);
    row.setPadding(0, page.dp(7), 0, page.dp(7));
    ImageView mark = new ImageView(page.context);
    mark.setImageResource(icon);
    mark.setImageTintList(
        android.content.res.ColorStateList.valueOf(page.context.getColor(R.color.shell_accent)));
    mark.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(page.dp(16), page.dp(16));
    mp.topMargin = page.dp(1);
    row.addView(mark, mp);
    TextView words = page.text(value, 13, R.color.shell_text_secondary);
    words.setLineSpacing(page.dp(3), 1);
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, -2, 1);
    wp.leftMargin = page.dp(10);
    row.addView(words, wp);
    card.addView(row);
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
                    com.deepseekharness.app.core.ColdSetupTiming.run(
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
                                + com.deepseekharness.app.core.ColdSetupTiming.report(this, true)));
              } catch (Exception error) {
                runOnUiThread(
                    () ->
                        status.setText(
                            UiText.format(
                                    "测速未完成：%s",
                                    com.deepseekharness.app.util.SensitiveData.redact(
                                        String.valueOf(error)))
                                + com.deepseekharness.app.core.ColdSetupTiming.report(
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
