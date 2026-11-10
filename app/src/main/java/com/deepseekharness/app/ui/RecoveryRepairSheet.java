package com.deepseekharness.app.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;
import java.util.List;

/**
 * 应急修复提案的单一确认容器：选择、预览与执行按钮都在同一个对话框里切换，不再叠加第二层卡片。
 *
 * <p>返回只退一层——预览回到选择，选择回到应急页；从「修复提案」列表直接打开的预览，返回直接关闭。
 * 选择后等待预览生成期间按钮立即禁用，避免重复生成提案；执行与拒绝放在滚动内容里，短屏与大字体下
 * 仍能滚动到。这里不改原生确认边界，也不缓存任何提案数据。
 */
final class RecoveryRepairSheet {
  /** 回调都在主线程发出；实现方负责把读取、提案与写入放到后台线程。 */
  interface Listener {
    void onChoice(int index);

    void onConfirm(String proposalId);

    void onReject(String proposalId);
  }

  private final Context context;
  private final Listener listener;
  private final ScrollView scroll;
  private final LinearLayout body;
  private final List<View> choiceButtons = new java.util.ArrayList<>();
  private AlertDialog dialog;
  private String[] choices = new String[0];
  private String proposalId = "";
  private boolean choosing;
  private boolean previewing;
  private boolean previewFromChoices;

  RecoveryRepairSheet(Context context, Listener listener) {
    this.context = context;
    this.listener = listener;
    scroll = new ScrollView(context);
    body = column();
    body.setPadding(dp(18), dp(12), dp(18), dp(12));
    scroll.addView(body);
  }

  /** 打开或回到选择层；已经显示时只替换内容，不新建对话框。 */
  void showChoices(String[] labels) {
    choices = labels == null ? new String[0] : labels;
    choosing = false;
    previewing = false;
    previewFromChoices = false;
    ensureDialog();
    dialog.setTitle(UiText.choose("选择要预览的修复", "Choose a repair to preview"));
    renderChoices();
    if (!dialog.isShowing()) dialog.show();
  }

  /**
   * 打开或切换到预览层。
   *
   * @param fromChoices 返回键回到选择层；false 表示从提案列表直接打开，返回直接关闭对话框
   */
  void showPreview(
      String id, List<String[]> rows, String blockedReason, boolean pending, boolean fromChoices) {
    proposalId = id == null ? "" : id;
    choosing = false;
    previewing = true;
    previewFromChoices = fromChoices;
    ensureDialog();
    dialog.setTitle(UiText.choose("确认这一次修复", "Confirm this repair"));
    renderPreview(rows, blockedReason, pending && !proposalId.isEmpty());
    if (!dialog.isShowing()) dialog.show();
  }

  void dismiss() {
    if (dialog != null && dialog.isShowing()) dialog.dismiss();
  }

  boolean isShowing() {
    return dialog != null && dialog.isShowing();
  }

  /** 返回一层：预览（来自选择层）回到选择层，其余情况关闭对话框；返回 true 表示已处理。 */
  boolean back() {
    if (!isShowing()) return false;
    if (previewing && previewFromChoices) {
      choosing = false;
      previewing = false;
      previewFromChoices = false;
      dialog.setTitle(UiText.choose("选择要预览的修复", "Choose a repair to preview"));
      renderChoices();
      return true;
    }
    dismiss();
    return true;
  }

  private void ensureDialog() {
    if (dialog != null) return;
    dialog = new DshaDialogBuilder(context).setView(scroll).create();
    dialog.setOnKeyListener(
        (current, keyCode, event) -> {
          if (keyCode != android.view.KeyEvent.KEYCODE_BACK) return false;
          if (event.getAction() == android.view.KeyEvent.ACTION_UP) back();
          return true;
        });
  }

  private void choose(int index) {
    if (choosing) return;
    choosing = true;
    for (View choice : choiceButtons) choice.setEnabled(false);
    listener.onChoice(index);
  }

  private void renderChoices() {
    body.removeAllViews();
    choiceButtons.clear();
    for (int index = 0; index < choices.length; index++) {
      final int position = index;
      choiceButtons.add(button(body, choices[index], false, () -> choose(position)));
    }
    // 选择层等待预览时仍可返回，避免慢读取把用户锁在对话框里。
    button(body, UiText.choose("返回", "Back"), false, this::back);
  }

  private void renderPreview(List<String[]> rows, String blockedReason, boolean pending) {
    body.removeAllViews();
    if (rows != null) {
      for (String[] row : rows) {
        if (row == null || row.length == 0) continue;
        TextView heading = text(row[0], 15, R.color.text);
        heading.setTypeface(null, Typeface.BOLD);
        body.addView(heading);
        TextView value = text(row.length > 1 ? row[1] : "", 14, R.color.text_secondary);
        value.setTextIsSelectable(true);
        value.setPadding(0, dp(6), 0, dp(16));
        body.addView(value);
      }
    }
    if (blockedReason != null && !blockedReason.isEmpty()) {
      body.addView(text(blockedReason, 14, R.color.err));
    }
    if (!pending) {
      button(body, UiText.choose("返回", "Back"), false, this::back);
      return;
    }
    // 执行与拒绝放进同一个滚动内容，避免短屏/大字体下对话框按钮行挤出屏幕。
    button(
        body,
        UiText.choose("保存原件并执行", "Save original and apply"),
        true,
        () -> {
          if (proposalId.isEmpty()) return;
          dismiss();
          listener.onConfirm(proposalId);
        });
    button(
        body,
        UiText.choose("拒绝提案", "Reject"),
        false,
        () -> {
          if (proposalId.isEmpty()) return;
          dismiss();
          listener.onReject(proposalId);
        });
    button(body, UiText.choose("返回", "Back"), false, this::back);
  }

  private Button button(LinearLayout parent, String title, boolean primary, Runnable action) {
    return CardPage.button(context, parent, title, primary, action);
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(context);
    view.setText(value);
    view.setTextSize(size);
    view.setIncludeFontPadding(false);
    view.setTextColor(context.getColor(color));
    return view;
  }

  private LinearLayout column() {
    LinearLayout view = new LinearLayout(context);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  private int dp(int value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }
}
