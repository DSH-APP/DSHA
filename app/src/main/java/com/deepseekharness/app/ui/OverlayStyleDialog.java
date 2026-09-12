package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import com.deepseekharness.app.OverlayController;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.Constants;

/** 悬浮条样式编辑器；预览只使用当前草稿，取消不会写入设置。 */
final class OverlayStyleDialog {
    private final Context context;
    private final SharedPreferences prefs;
    private final TextView[] swatches = new TextView[OverlayController.BG_PRESETS.length];
    private final LinearLayout root;
    private final AlertDialog dialog;
    private final TextView preview, previewHint;
    private final SeekBar alpha, lines, font, hold;
    private final CheckBox reasoning, command, confirm;
    private int selected;
    private OverlayStyleDialog(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences(Constants.PREFS, 0);
        selected = Math.max(0, Math.min(swatches.length - 1, prefs.getInt(OverlayController.K_BG, 0)));
        root = new LinearLayout(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int available = dp(context.getResources().getConfiguration().screenHeightDp) * 9 / 10;
                if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
                    available = Math.min(available, View.MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST));
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20), dp(16), dp(20), dp(14));
        LinearLayout heading = new LinearLayout(context); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(com.deepseekharness.app.util.UiText.text("悬浮条外观与行为"), 19, R.color.text); title.setTypeface(null, android.graphics.Typeface.BOLD);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button("×", false); close.setTextSize(24); close.setContentDescription(com.deepseekharness.app.util.UiText.text("关闭，不保存"));
        close.setPadding(0, 0, 0, 0); heading.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48))); root.addView(heading);
        LinearLayout content = column();
        TextView caption = text(com.deepseekharness.app.util.UiText.text("底色"), 14, R.color.text_secondary); content.addView(caption);
        LinearLayout colors = new LinearLayout(context); colors.setPadding(0, dp(10), 0, dp(14));
        for (int i = 0; i < swatches.length; i++) {
            final int index = i; LinearLayout item = column(); item.setGravity(Gravity.CENTER_HORIZONTAL);
            LinearLayout.LayoutParams column = new LinearLayout.LayoutParams(0, -2, 1); if (i > 0) column.leftMargin = dp(6);
            colors.addView(item, column);
            TextView chip = text("", 22, R.color.accent_on); chip.setTextColor(0xffffffff); chip.setGravity(Gravity.CENTER);
            chip.setFocusable(true); chip.setClickable(true); chip.setMinHeight(dp(48));
            chip.setOnClickListener(v -> { selected = index; paint(); updatePreview(); });
            swatches[i] = chip; item.addView(chip, new LinearLayout.LayoutParams(-1, dp(48)));
            TextView label = text(com.deepseekharness.app.util.UiText.text(OverlayController.BG_NAMES[i]), 11, R.color.text_secondary); label.setGravity(Gravity.CENTER);
            label.setMinHeight(dp(30));label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            label.setPadding(0, dp(6), 0, 0); item.addView(label);
        }
        content.addView(colors); paint();
        alpha = slider(content, com.deepseekharness.app.util.UiText.text("不透明度"), 20, 100, prefs.getInt(OverlayController.K_ALPHA, OverlayController.DEF_ALPHA), "%");
        lines = slider(content, com.deepseekharness.app.util.UiText.text("显示行数"), 1, 6, prefs.getInt(OverlayController.K_LINES, OverlayController.DEF_LINES), com.deepseekharness.app.util.UiText.text(" 行"));
        font = slider(content, com.deepseekharness.app.util.UiText.text("文字大小"), 6, 20, prefs.getInt(OverlayController.K_TEXT_SP, OverlayController.DEF_TEXT_SP), " sp");
        hold = slider(content, com.deepseekharness.app.util.UiText.text("停留时间"), 2, 60, prefs.getInt(OverlayController.K_HOLD, OverlayController.DEF_HOLD), com.deepseekharness.app.util.UiText.text(" 秒"));
        reasoning = check(content, com.deepseekharness.app.util.UiText.text("显示思考过程"), OverlayController.K_REASONING, false);
        command = check(content, com.deepseekharness.app.util.UiText.text("工具调用显示命令原文"), OverlayController.K_COMMAND, true);
        confirm = check(content, com.deepseekharness.app.util.UiText.text("在悬浮条上确认命令"), OverlayController.K_CONFIRM, true);
        previewHint = text("", 12, R.color.text_secondary); previewHint.setPadding(0, dp(10), 0, dp(8));
        preview = text("", 12, R.color.text); preview.setTextColor(0xffffffff); preview.setPadding(dp(12), dp(10), dp(12), dp(10));
        content.addView(previewHint); content.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        preview.setVisibility(View.GONE); previewHint.setVisibility(View.GONE);
        // 小屏/横屏仍能滚动全部控件，标题和操作按钮保持可见。
        ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false); scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout footer = new LinearLayout(context); footer.setGravity(Gravity.CENTER_VERTICAL); footer.setPadding(0, dp(12), 0, 0);
        Button show = button(com.deepseekharness.app.util.UiText.text("预览"), false), cancel = button(com.deepseekharness.app.util.UiText.text("取消"), false), save = button(com.deepseekharness.app.util.UiText.text("保存"), true);
        for (Button action : new Button[]{show, cancel, save}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (action != show) lp.leftMargin = dp(8); footer.addView(action, lp);
        }
        root.addView(footer);
        dialog = new com.deepseekharness.app.ui.DshaDialogBuilder(context).setView(root).create();
        close.setOnClickListener(v -> dialog.dismiss()); cancel.setOnClickListener(v -> dialog.dismiss());
        show.setOnClickListener(v -> {
            preview.setVisibility(View.VISIBLE); previewHint.setVisibility(View.VISIBLE); updatePreview();
            scroll.post(() -> scroll.smoothScrollTo(0, content.getHeight()));
        });
        save.setOnClickListener(v -> {
            prefs.edit().putInt(OverlayController.K_BG, selected).putInt(OverlayController.K_ALPHA, value(alpha, 20))
                    .putInt(OverlayController.K_LINES, value(lines, 1)).putInt(OverlayController.K_TEXT_SP, value(font, 6))
                    .putInt(OverlayController.K_HOLD, value(hold, 2)).putBoolean(OverlayController.K_REASONING, reasoning.isChecked())
                    .putBoolean(OverlayController.K_COMMAND, command.isChecked()).putBoolean(OverlayController.K_CONFIRM, confirm.isChecked()).apply();
            OverlayController.applyStyleNow(context.getApplicationContext());
            Toast.makeText(context, com.deepseekharness.app.util.UiText.text("悬浮条设置已保存"), Toast.LENGTH_SHORT).show(); dialog.dismiss();
        });
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            root.setBackgroundResource(R.drawable.bg_device_card);
            int screenWidth = Math.min(dp(context.getResources().getConfiguration().screenWidthDp), context.getResources().getDisplayMetrics().widthPixels);
            dialog.getWindow().setLayout(Math.min(dp(480), screenWidth - dp(32)), -2);
        }
    }
    static AlertDialog show(Context context) { return new OverlayStyleDialog(context).dialog; }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(context); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private TextView text(String value, int size, int color) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size);
        text.setTextColor(ContextCompat.getColor(context, color)); return text;
    }
    private Button button(String value, boolean primary) {
        Button button = new Button(context); button.setText(value); button.setAllCaps(false); button.setMinHeight(dp(48));
        button.setTextSize(14);button.setPadding(dp(4), dp(10), dp(4), dp(10));
        button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_action_plain);
        button.setTextColor(ContextCompat.getColor(context, primary ? R.color.button_primary_text : R.color.primary)); return button;
    }
    private CheckBox check(LinearLayout parent, String value, String key, boolean fallback) {
        CheckBox checkbox = new CheckBox(context); checkbox.setText(value); checkbox.setTextSize(14); checkbox.setMinHeight(dp(48));
        checkbox.setTextColor(ContextCompat.getColor(context, R.color.text)); checkbox.setChecked(prefs.getBoolean(key, fallback));
        checkbox.setOnCheckedChangeListener((button, checked) -> updatePreview()); parent.addView(checkbox); return checkbox;
    }
    private SeekBar slider(LinearLayout parent, String title, int min, int max, int current, String unit) {
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text(title, 14, R.color.text), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text("", 13, R.color.primary); value.setPadding(dp(10), dp(5), dp(10), dp(5));
        value.setBackgroundResource(R.drawable.bg_device_tint); row.addView(value); parent.addView(row);
        SeekBar bar = new SeekBar(context); bar.setMax(max - min); bar.setProgress(Math.max(0, Math.min(max - min, current - min)));
        value.setText(com.deepseekharness.app.util.UiText.text((bar.getProgress() + min) + unit)); bar.setContentDescription(title);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seek, int progress, boolean fromUser) { value.setText(com.deepseekharness.app.util.UiText.text((progress + min) + unit)); updatePreview(); }
            @Override public void onStartTrackingTouch(SeekBar seek) { }
            @Override public void onStopTrackingTouch(SeekBar seek) { }
        });
        parent.addView(bar, new LinearLayout.LayoutParams(-1, dp(48))); return bar;
    }
    private static int value(SeekBar bar, int min) { return min + bar.getProgress(); }
    private void paint() {
        for (int i = 0; i < swatches.length; i++) {
            TextView chip = swatches[i]; if (chip == null) continue;
            GradientDrawable bg = new GradientDrawable(); bg.setColor(0xff000000 | OverlayController.BG_PRESETS[i]); bg.setCornerRadius(dp(12));
            if (i == selected) bg.setStroke(dp(3), ContextCompat.getColor(context, R.color.primary));
            chip.setBackground(bg); chip.setText(com.deepseekharness.app.util.UiText.text(i == selected ? "✓" : "")); chip.setSelected(i == selected);
            chip.setContentDescription(com.deepseekharness.app.util.UiText.text(OverlayController.BG_NAMES[i]) + (i == selected ? com.deepseekharness.app.util.UiText.text("，已选中") : com.deepseekharness.app.util.UiText.text("，选择底色")));
        }
    }
    private void updatePreview() {
        if (preview == null || preview.getVisibility() != View.VISIBLE) return;
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(12));
        bg.setColor((Math.round(value(alpha, 20) * 255f / 100f) << 24) | OverlayController.BG_PRESETS[selected]);
        preview.setBackground(bg); preview.setTextSize(value(font, 6)); preview.setMaxLines(value(lines, 1));
        preview.setText((reasoning.isChecked() ? com.deepseekharness.app.util.UiText.text("正在思考：先检查文件内容。\n") : "")
                + (command.isChecked() ? com.deepseekharness.app.util.UiText.text("正在执行命令：ls -la\n") : com.deepseekharness.app.util.UiText.text("正在执行命令\n")) + com.deepseekharness.app.util.UiText.text("文件已整理完成，可以继续下一步。"));
        previewHint.setText(com.deepseekharness.app.util.UiText.text("预览 · 最多 ") + value(lines, 1) + com.deepseekharness.app.util.UiText.text(" 行 · 停止输出后保留 ") + value(hold, 2) + com.deepseekharness.app.util.UiText.text(" 秒"));
    }
}
