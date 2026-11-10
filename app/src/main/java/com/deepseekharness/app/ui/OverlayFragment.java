package com.deepseekharness.app.ui;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.OverlayController;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;

/**
 * 独立悬浮条编辑页（方案 A 紧凑版）：顶部开关 + 实时预览卡；外观用紧凑行（滑条带数值、分段胶囊、色块）；
 * 显示内容三项开关。改动即时写入原有偏好键（短暂防抖），不再需要「保存样式」。
 */
public final class OverlayFragment extends Fragment {
  private static final long PERSIST_DELAY_MS = 250L;
  private final int[] durations = {2, 5, 6, 10, 15, 30, 60};
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable persistTask = this::persist;

  private CardPage ui;
  private MaterialSwitch enabled, reasoning, commands, confirmation;
  private SeekBar opacity, font;
  private TextView opacityValue, fontValue, backgroundValue;
  private ChipGroup lines, hold;
  private View[] swatches;
  private TextView sample, permission;
  private Button previewAction;
  private int backgroundIndex;
  private int holdSeconds;
  private boolean livePreview, building;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    var prefs = requireContext().getSharedPreferences(Constants.PREFS, 0);
    building = true;
    ui =
        new CardPage(
            requireContext(),
            UiText.choose("悬浮条", "Floating status"),
            UiText.choose("在其他应用中，轻量查看任务进展。", "Follow progress while using other apps."));

    // 1. 总开关 + 权限状态（未授权时可点去开启）。
    LinearLayout top = ui.card();
    enabled =
        toggle(
            top,
            UiText.choose("显示悬浮条", "Show floating status"),
            prefs.getBoolean(OverlayController.K_ENABLED, false));
    permission = ui.text("", 12, R.color.shell_text_muted);
    permission.setPadding(0, 0, 0, ui.dp(4));
    permission.setOnClickListener(
        v -> {
          if (!OverlayController.permitted(requireContext())) openOverlayPermission();
        });
    top.addView(permission);

    // 2. 预览卡：标题行右侧是文字按钮，下面是实时样式预览。
    LinearLayout previewCard = ui.card();
    LinearLayout previewHead = new LinearLayout(requireContext());
    previewHead.setGravity(Gravity.CENTER_VERTICAL);
    TextView previewTitle =
        ui.text(UiText.choose("样式预览", "Style preview"), 13, R.color.shell_text_secondary);
    previewHead.addView(previewTitle, new LinearLayout.LayoutParams(0, -2, 1));
    previewAction = new androidx.appcompat.widget.AppCompatButton(requireContext());
    previewAction.setId(R.id.overlay_preview_button);
    previewAction.setText(UiText.choose("在屏幕上预览", "Preview on screen"));
    previewAction.setAllCaps(false);
    previewAction.setTextSize(13);
    previewAction.setTextColor(requireContext().getColor(R.color.shell_accent));
    previewAction.setBackgroundResource(R.drawable.bg_action_plain);
    previewAction.setMinHeight(ui.dp(44));
    previewAction.setMinimumHeight(ui.dp(44));
    previewAction.setPadding(ui.dp(12), 0, ui.dp(12), 0);
    previewAction.setContentDescription(UiText.choose("显示系统悬浮条预览", "Show system overlay preview"));
    previewAction.setOnClickListener(v -> startLivePreview());
    previewHead.addView(previewAction);
    previewCard.addView(previewHead);
    sample = ui.text("", 13, R.color.text);
    sample.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
    LinearLayout.LayoutParams sampleParams = new LinearLayout.LayoutParams(-1, -2);
    sampleParams.topMargin = ui.dp(4);
    sampleParams.bottomMargin = ui.dp(4);
    previewCard.addView(sample, sampleParams);

    // 3. 外观：紧凑行，行间分隔线。
    ui.label(UiText.choose("外观", "Appearance"));
    LinearLayout appearance = ui.card();
    opacity = new SeekBar(requireContext());
    opacity.setMax(80);
    opacity.setProgress(prefs.getInt(OverlayController.K_ALPHA, OverlayController.DEF_ALPHA) - 20);
    opacityValue = sliderRow(appearance, UiText.text("不透明度"), opacity);
    divider(appearance);
    font = new SeekBar(requireContext());
    font.setMax(14);
    font.setProgress(prefs.getInt(OverlayController.K_TEXT_SP, OverlayController.DEF_TEXT_SP) - 6);
    fontValue = sliderRow(appearance, UiText.text("文字大小"), font);
    divider(appearance);
    String[] lineLabels = new String[6];
    String[] lineSpoken = new String[6];
    for (int i = 0; i < 6; i++) {
      lineLabels[i] = String.valueOf(i + 1);
      lineSpoken[i] = i == 0 ? UiText.text("1 行") : UiText.format("%s 行", i + 1);
    }
    lines =
        chipRow(
            appearance,
            UiText.choose("最多显示行数", "Maximum lines"),
            lineLabels,
            lineSpoken,
            prefs.getInt(OverlayController.K_LINES, OverlayController.DEF_LINES) - 1);
    equalizeChips(lines);
    divider(appearance);
    holdSeconds = prefs.getInt(OverlayController.K_HOLD, OverlayController.DEF_HOLD);
    int selected = -1;
    for (int i = 0; i < durations.length; i++) if (holdSeconds == durations[i]) selected = i;
    String[] times = new String[durations.length + (selected < 0 ? 1 : 0)];
    for (int i = 0; i < durations.length; i++) times[i] = UiText.format("%s 秒", durations[i]);
    if (selected < 0) {
      // 旧版本可能存了不在列表里的秒数：保留为一个额外选项，不擅自改写。
      selected = durations.length;
      times[selected] = UiText.format("%s 秒（当前）", holdSeconds);
    }
    hold = chipRow(appearance, UiText.choose("停留时间", "Display duration"), times, times, selected);
    divider(appearance);
    backgroundIndex =
        Math.max(
            0,
            Math.min(
                OverlayController.BG_PRESETS.length - 1, prefs.getInt(OverlayController.K_BG, 0)));
    backgroundValue = swatchRow(appearance, UiText.choose("背景样式", "Background style"));

    // 4. 显示内容。
    ui.label(UiText.choose("显示内容", "Content"));
    LinearLayout behavior = ui.card();
    reasoning =
        toggle(
            behavior,
            UiText.choose("显示思考过程", "Show reasoning"),
            prefs.getBoolean(OverlayController.K_REASONING, false));
    divider(behavior);
    commands =
        toggle(
            behavior,
            UiText.choose("显示执行命令", "Show executed commands"),
            prefs.getBoolean(OverlayController.K_COMMAND, true));
    divider(behavior);
    confirmation =
        toggle(
            behavior,
            UiText.choose("在悬浮条上确认命令", "Confirm commands in the overlay"),
            prefs.getBoolean(OverlayController.K_CONFIRM, true));
    TextView confirmHint = ui.text("", 12, R.color.shell_text_muted);
    confirmHint.setPadding(0, 0, 0, ui.dp(6));
    InfoHint.bind(
        confirmHint,
        UiText.choose("在悬浮条上确认命令", "Confirm commands in the overlay"),
        UiText.choose(
            "需要确认的命令可直接在悬浮条上批准或拒绝。\n\n这是与通知、DSHA 弹窗并行的确认渠道，谁先点谁生效。关闭后悬浮条不再显示批准按钮，通知和弹窗照常可用；命令拦截规则不变。",
            "Commands that need confirmation can be approved or rejected right on the overlay.\n\nThis runs alongside the notification and the DSHA dialog; whichever you tap first applies. When off, the overlay shows no approval buttons, while the notification and dialog still work. Command blocking rules do not change."));
    behavior.addView(confirmHint);

    TextView autosave =
        ui.text(
            UiText.choose("改动会立即保存。", "Changes are saved automatically."),
            12,
            R.color.shell_text_muted);
    autosave.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(8));
    ui.content.addView(autosave);
    ui.footer.setVisibility(View.GONE);

    // 监听：任何改动都刷新预览，并防抖写入偏好；总开关立即生效。
    enabled.setOnCheckedChangeListener(
        (button, on) -> {
          if (building) return;
          paint();
          persist();
          if (!on) OverlayController.teardown(requireContext().getApplicationContext());
          else if (!OverlayController.permitted(requireContext())) openOverlayPermission();
        });
    CompoundButton.OnCheckedChangeListener repaint =
        (button, checked) -> {
          if (!building) changed();
        };
    reasoning.setOnCheckedChangeListener(repaint);
    commands.setOnCheckedChangeListener(repaint);
    confirmation.setOnCheckedChangeListener(repaint);
    SeekBar.OnSeekBarChangeListener slide =
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int value, boolean user) {
            if (!building) changed();
          }

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {}
        };
    opacity.setOnSeekBarChangeListener(slide);
    font.setOnSeekBarChangeListener(slide);
    ChipGroup.OnCheckedStateChangeListener chips =
        (group, ids) -> {
          if (!building) changed();
        };
    lines.setOnCheckedStateChangeListener(chips);
    hold.setOnCheckedStateChangeListener(chips);
    building = false;
    renderPermission();
    paint();
    return ui.root;
  }

  private void changed() {
    paint();
    main.removeCallbacks(persistTask);
    main.postDelayed(persistTask, PERSIST_DELAY_MS);
  }

  private void startLivePreview() {
    livePreview = true;
    paint();
    if (!OverlayController.permitted(requireContext())) {
      permission.setText(
          UiText.choose(
              "需要悬浮窗权限，正在打开系统设置…", "Overlay permission is required; opening system settings…"));
      openOverlayPermission();
    } else {
      permission.setText(
          UiText.choose(
              "预览已显示，改动即时生效",
              "System overlay preview is shown; changes below update it immediately."));
    }
  }

  private MaterialSwitch toggle(LinearLayout parent, String label, boolean value) {
    MaterialSwitch toggle = new MaterialSwitch(requireContext());
    toggle.setText(label);
    toggle.setTextSize(14);
    toggle.setTextColor(requireContext().getColor(R.color.shell_text));
    toggle.setMinHeight(ui.dp(48));
    toggle.setChecked(value);
    parent.addView(toggle, new LinearLayout.LayoutParams(-1, -2));
    return toggle;
  }

  private void divider(LinearLayout parent) {
    View line = new View(requireContext());
    line.setBackgroundColor(requireContext().getColor(R.color.shell_line));
    parent.addView(line, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1) / 2 + 1)));
  }

  /** 名称 + 滑条 + 右侧数值，一行完成。返回数值文本。 */
  private TextView sliderRow(LinearLayout parent, String label, SeekBar bar) {
    LinearLayout row = new LinearLayout(requireContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(ui.dp(52));
    TextView name = ui.text(label, 14, R.color.shell_text);
    name.setMinWidth(ui.dp(72));
    row.addView(name, new LinearLayout.LayoutParams(-2, -2));
    bar.setId(View.generateViewId());
    name.setLabelFor(bar.getId());
    bar.setContentDescription(label);
    row.addView(bar, new LinearLayout.LayoutParams(0, ui.dp(48), 1f));
    TextView value = ui.text("", 13, R.color.shell_text_secondary);
    value.setMinWidth(ui.dp(48));
    value.setGravity(Gravity.END);
    value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    row.addView(value, new LinearLayout.LayoutParams(-2, -2));
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
    return value;
  }

  /** 标题 + 单选胶囊（可折行）。 */
  private ChipGroup chipRow(
      LinearLayout parent, String title, String[] labels, String[] spoken, int selected) {
    TextView name = ui.text(title, 14, R.color.shell_text);
    name.setPadding(0, ui.dp(12), 0, ui.dp(6));
    parent.addView(name);
    ChipGroup group = new ChipGroup(requireContext());
    group.setSingleSelection(true);
    group.setSelectionRequired(true);
    group.setChipSpacingHorizontal(ui.dp(6));
    group.setChipSpacingVertical(ui.dp(2));
    int pick = Math.max(0, Math.min(labels.length - 1, selected));
    for (int i = 0; i < labels.length; i++) {
      Chip chip = new Chip(requireContext());
      chip.setId(View.generateViewId());
      chip.setText(UiText.raw(labels[i]));
      chip.setContentDescription(spoken[i]);
      chip.setCheckable(true);
      chip.setCheckedIconVisible(false);
      chip.setTag(i);
      group.addView(chip);
      if (i == pick) chip.setChecked(true);
    }
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = ui.dp(8);
    parent.addView(group, params);
    return group;
  }

  /**
   * 1–6 行这类短数字选项：在一行里等分宽度，窄屏不再剩一个「6」单独换行。
   * 按 ChipGroup 实际宽度计算，放不下最小触控宽度时保持默认换行。
   */
  private void equalizeChips(ChipGroup group) {
    group.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          int count = group.getChildCount();
          int width = right - left - group.getPaddingStart() - group.getPaddingEnd();
          if (count == 0 || width <= 0 || width == oldRight - oldLeft) return;
          int each = (width - group.getChipSpacingHorizontal() * (count - 1)) / count;
          if (each < ui.dp(40)) return;
          group.post(
              () -> {
                for (int i = 0; i < count; i++) {
                  Chip chip = (Chip) group.getChildAt(i);
                  chip.setEnsureMinTouchTargetSize(false);
                  chip.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                  chip.setChipStartPadding(0);
                  chip.setChipEndPadding(0);
                  chip.setTextStartPadding(0);
                  chip.setTextEndPadding(0);
                  chip.setMinHeight(ui.dp(40));
                  ViewGroup.LayoutParams params = chip.getLayoutParams();
                  params.width = each;
                  chip.setLayoutParams(params);
                }
              });
        });
  }

  private static int checkedIndex(ChipGroup group) {
    View chip = group.findViewById(group.getCheckedChipId());
    return chip != null && chip.getTag() instanceof Integer ? (Integer) chip.getTag() : 0;
  }

  /** 标题 + 当前颜色名，下方一排圆形色块；选中的加强调色外圈。返回颜色名文本。 */
  private TextView swatchRow(LinearLayout parent, String title) {
    LinearLayout head = new LinearLayout(requireContext());
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.setPadding(0, ui.dp(12), 0, ui.dp(6));
    head.addView(ui.text(title, 14, R.color.shell_text), new LinearLayout.LayoutParams(0, -2, 1));
    TextView value = ui.text("", 13, R.color.shell_text_secondary);
    head.addView(value);
    parent.addView(head);
    LinearLayout row = new LinearLayout(requireContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    swatches = new View[OverlayController.BG_PRESETS.length];
    for (int i = 0; i < swatches.length; i++) {
      final int index = i;
      FrameLayout ring = new FrameLayout(requireContext());
      ring.setClickable(true);
      ring.setFocusable(true);
      ring.setContentDescription(UiText.text(OverlayController.BG_NAMES[i]));
      View dot = new View(requireContext());
      GradientDrawable fill = new GradientDrawable();
      fill.setShape(GradientDrawable.OVAL);
      fill.setColor(0xFF000000 | OverlayController.BG_PRESETS[i]);
      fill.setStroke(ui.dp(1), requireContext().getColor(R.color.shell_line_strong));
      dot.setBackground(fill);
      FrameLayout.LayoutParams dotParams = new FrameLayout.LayoutParams(ui.dp(30), ui.dp(30));
      dotParams.gravity = Gravity.CENTER;
      ring.addView(dot, dotParams);
      ring.setOnClickListener(
          v -> {
            if (backgroundIndex == index) return;
            backgroundIndex = index;
            changed();
          });
      LinearLayout.LayoutParams ringParams = new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44));
      ringParams.setMarginEnd(ui.dp(8));
      row.addView(ring, ringParams);
      swatches[i] = ring;
    }
    LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
    rowParams.bottomMargin = ui.dp(8);
    parent.addView(row, rowParams);
    return value;
  }

  private void styleSwatches() {
    for (int i = 0; i < swatches.length; i++) {
      boolean selected = i == backgroundIndex;
      GradientDrawable ring = new GradientDrawable();
      ring.setShape(GradientDrawable.OVAL);
      ring.setColor(android.graphics.Color.TRANSPARENT);
      if (selected) ring.setStroke(ui.dp(2), requireContext().getColor(R.color.shell_accent));
      swatches[i].setBackground(ring);
      swatches[i].setAccessibilityDelegate(
          new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                View host, android.view.accessibility.AccessibilityNodeInfo info) {
              super.onInitializeAccessibilityNodeInfo(host, info);
              info.setClassName(RadioButton.class.getName());
              info.setCheckable(true);
              info.setChecked(selected);
            }
          });
    }
  }

  private int lineCount() {
    return checkedIndex(lines) + 1;
  }

  private int seconds() {
    int index = checkedIndex(hold);
    return index < durations.length ? durations[index] : holdSeconds;
  }

  private void paint() {
    if (sample == null || swatches == null || font == null || lines == null) return;
    int count = Math.max(1, lineCount());
    int sp = Math.max(6, font.getProgress() + 6);
    int alphaValue = Math.max(20, Math.min(100, opacity.getProgress() + 20));
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(
        ((alphaValue * 255 / 100) << 24) | OverlayController.BG_PRESETS[backgroundIndex]);
    shape.setCornerRadius(ui.dp(12));
    sample.setBackground(shape);
    sample.setTextColor(android.graphics.Color.WHITE);
    sample.setTextSize(sp);
    sample.setMinLines(1);
    sample.setMaxLines(count);
    StringBuilder text =
        new StringBuilder(UiText.choose("DSHA · 样式预览\n", "DSHA · Style preview\n"));
    if (reasoning != null && reasoning.isChecked())
      text.append(UiText.choose("正在思考：先检查文件内容。\n", "Thinking: checking the files first.\n"));
    if (commands != null && commands.isChecked())
      text.append(UiText.choose("正在执行命令：ls -la\n", "Running command: ls -la\n"));
    text.append(UiText.choose("文件已整理完成，可以继续下一步。", "Files are ready; continue to the next step."));
    sample.setText(text.toString());
    opacityValue.setText(UiText.raw(alphaValue + "%"));
    fontValue.setText(UiText.raw(sp + "sp"));
    backgroundValue.setText(UiText.text(OverlayController.BG_NAMES[backgroundIndex]));
    styleSwatches();
    androidx.core.view.ViewCompat.setStateDescription(
        opacity, UiText.format("不透明度：%s", alphaValue + "%"));
    androidx.core.view.ViewCompat.setStateDescription(font, UiText.format("文字大小：%s", sp + "sp"));
    if (livePreview)
      OverlayController.showStylePreview(
          requireContext(),
          backgroundIndex,
          alphaValue,
          count,
          sp,
          reasoning != null && reasoning.isChecked(),
          commands != null && commands.isChecked());
  }

  private void renderPermission() {
    if (permission == null || getContext() == null) return;
    boolean permitted = OverlayController.permitted(requireContext());
    permission.setText(
        permitted
            ? UiText.choose("悬浮窗权限已开启", "Overlay permission is enabled")
            : UiText.choose("需要悬浮窗权限 · 去开启 ›", "Overlay permission needed · Grant ›"));
    permission.setTextColor(
        requireContext().getColor(permitted ? R.color.shell_text_muted : R.color.shell_accent));
    permission.setMinHeight(permitted ? 0 : ui.dp(44));
    permission.setGravity(Gravity.CENTER_VERTICAL);
    permission.setClickable(!permitted);
  }

  private void openOverlayPermission() {
    try {
      startActivity(
          new Intent(
              Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
              Uri.parse("package:" + requireContext().getPackageName())));
    } catch (RuntimeException unavailable) {
      try {
        startActivity(
            new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + requireContext().getPackageName())));
      } catch (RuntimeException ignored) {
        Toast.makeText(
                requireContext(),
                UiText.choose(
                    "请在系统设置中允许 DSHA 显示在其他应用上层。",
                    "Allow DSHA to display over other apps in system settings."),
                Toast.LENGTH_LONG)
            .show();
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    renderPermission();
    if (livePreview && OverlayController.permitted(requireContext())) paint();
  }

  @Override
  public void onPause() {
    // 离开页面前把防抖中的改动写掉，避免拖动滑条后立刻返回丢失。
    if (main.hasCallbacks(persistTask)) {
      main.removeCallbacks(persistTask);
      persist();
    }
    super.onPause();
  }

  /** 写入原有偏好键，并让已显示的悬浮条立即套用新样式。 */
  private void persist() {
    if (enabled == null || getContext() == null) return;
    requireContext()
        .getSharedPreferences(Constants.PREFS, 0)
        .edit()
        .putBoolean(OverlayController.K_ENABLED, enabled.isChecked())
        .putInt(OverlayController.K_ALPHA, opacity.getProgress() + 20)
        .putInt(OverlayController.K_LINES, lineCount())
        .putInt(OverlayController.K_HOLD, seconds())
        .putInt(OverlayController.K_BG, backgroundIndex)
        .putInt(OverlayController.K_TEXT_SP, font.getProgress() + 6)
        .putBoolean(OverlayController.K_REASONING, reasoning.isChecked())
        .putBoolean(OverlayController.K_COMMAND, commands.isChecked())
        .putBoolean(OverlayController.K_CONFIRM, confirmation.isChecked())
        .apply();
    if (enabled.isChecked())
      OverlayController.applyStyleNow(requireContext().getApplicationContext());
  }

  @Override
  public void onDestroyView() {
    main.removeCallbacks(persistTask);
    livePreview = false;
    if (getContext() != null)
      OverlayController.hideStylePreview(requireContext().getApplicationContext());
    super.onDestroyView();
  }
}
