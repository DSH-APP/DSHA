package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.AppCompatRadioButton;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.TerminalExtraKeys;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** 终端快捷按键编辑：手机/平板两套草稿同存，只有保存才写入偏好；不新增 Activity 或布局资源。 */
final class ExtraKeysDialog {
  private static final int ROWS = 2;

  private ExtraKeysDialog() {}

  static void show(Context context, Runnable onSaved) {
    if (context == null) return;
    new Editor(context, onSaved).show();
  }

  /** 一次编辑会话：切换布局保留草稿，取消不写盘。 */
  private static final class Editor {
    private final Context context;
    private final Runnable onSaved;
    private final SharedPreferences prefs;
    private final Map<TerminalExtraKeys.Profile, List<List<String>>> drafts =
        new EnumMap<>(TerminalExtraKeys.Profile.class);
    private final CardPage page;
    private final AlertDialog dialog;
    private final RadioGroup profiles;
    private final AppCompatRadioButton phone;
    private final AppCompatRadioButton tablet;
    private final LinearLayout rowsBox;
    private TerminalExtraKeys.Profile selected;
    private boolean updating;

    Editor(Context context, Runnable onSaved) {
      this.context = context;
      this.onSaved = onSaved;
      prefs = context.getSharedPreferences(TerminalExtraKeys.PREFS, Context.MODE_PRIVATE);
      for (TerminalExtraKeys.Profile profile : TerminalExtraKeys.Profile.values()) {
        drafts.put(
            profile,
            normalize(
                TerminalExtraKeys.decode(
                    prefs.getString(TerminalExtraKeys.prefKey(profile), ""), profile)));
      }
      selected =
          TerminalExtraKeys.profileFor(
              context.getResources().getConfiguration().smallestScreenWidthDp);
      page =
          new CardPage(
              context,
              UiText.choose("快捷按键", "Extra keys"),
              UiText.choose(
                  "应用于 PTY 会话；手机与平板布局分别保存。长按或点按按键可操作。",
                  "Applies to PTY sessions; phone and tablet layouts are saved separately. Tap or long-press a key to change it."),
              true);
      // 多段表单：短屏走分栏形态，平板加宽到 640 / 720。
      dialog = CardSheet.create(context, page, com.deepseekharness.app.util.SheetGeometry.Size.L);
      LinearLayout layoutCard = page.card();
      layoutCard.addView(page.text(UiText.choose("布局", "Layout"), 11, R.color.text_muted));
      profiles = new RadioGroup(context);
      profiles.setOrientation(RadioGroup.HORIZONTAL);
      phone = radio("手机布局", "Phone layout");
      tablet = radio("平板布局", "Tablet layout");
      profiles.addView(phone, new LinearLayout.LayoutParams(0, -2, 1f));
      profiles.addView(tablet, new LinearLayout.LayoutParams(0, -2, 1f));
      layoutCard.addView(profiles, new LinearLayout.LayoutParams(-1, -2));
      rowsBox = page.column();
      page.content.addView(rowsBox, new LinearLayout.LayoutParams(-1, -2));
      page.button(
          page.content, UiText.choose("恢复当前布局默认", "Reset current layout"), false, this::reset);
      page.button(page.footer, UiText.choose("取消", "Cancel"), false, dialog::dismiss);
      page.button(page.footer, UiText.choose("保存", "Save"), true, this::save);
      profiles.setOnCheckedChangeListener(
          (group, checked) -> {
            if (updating || checked == View.NO_ID) return;
            selected =
                checked == tablet.getId()
                    ? TerminalExtraKeys.Profile.TABLET
                    : TerminalExtraKeys.Profile.PHONE;
            renderRows();
          });
      updating = true;
      profiles.check(selected == TerminalExtraKeys.Profile.TABLET ? tablet.getId() : phone.getId());
      updating = false;
      renderRows();
    }

    void show() {
      CardSheet.show(dialog, context);
    }

    private AppCompatRadioButton radio(String chinese, String english) {
      var button = new AppCompatRadioButton(context);
      button.setId(View.generateViewId());
      button.setText(UiText.choose(chinese, english));
      return button;
    }

    /** 按当前草稿重绘两行按键；空行只留说明，保存后由模型去掉该行。 */
    private void renderRows() {
      rowsBox.removeAllViews();
      List<List<String>> draft = drafts.get(selected);
      for (int index = 0; index < ROWS; index++) {
        final int row = index;
        List<String> actions = draft.get(index);
        LinearLayout card = section();
        card.addView(
            page.text(
                rowTitle(index) + " · " + actions.size() + "/" + TerminalExtraKeys.MAX_KEYS_PER_ROW,
                11,
                R.color.text_muted));
        if (actions.isEmpty()) {
          TextView hint =
              page.text(
                  UiText.choose(
                      "此行无按键；保存后隐藏",
                      "No keys in this row yet; it stays hidden in the terminal after saving."),
                  11,
                  R.color.text_muted);
          hint.setPadding(0, page.dp(6), 0, 0);
          card.addView(hint);
        } else {
          ChipGroup group = new ChipGroup(context);
          // 与终端按键行一致固定 LTR，左移/右移在 RTL 语言下也对应实际显示顺序。
          group.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
          group.setChipSpacing(page.dp(6));
          for (int position = 0; position < actions.size(); position++)
            group.addView(chip(actions.get(position), row, position));
          card.addView(group, new LinearLayout.LayoutParams(-1, -2));
        }
        page.button(card, UiText.choose("+ 添加按键", "+ Add key"), false, () -> addKey(row));
      }
    }

    private LinearLayout section() {
      LinearLayout card = page.column();
      card.setBackgroundResource(R.drawable.bg_card);
      card.setPadding(page.dp(16), page.dp(14), page.dp(16), page.dp(14));
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
      params.bottomMargin = page.dp(14);
      rowsBox.addView(card, params);
      return card;
    }

    private String rowTitle(int index) {
      return index == 0 ? UiText.choose("第一行", "Row 1") : UiText.choose("第二行", "Row 2");
    }

    /** 关闭图标直接移除；点按与长按都打开同一份操作菜单。 */
    private Chip chip(String action, int row, int index) {
      Chip chip = new Chip(context);
      chip.setText(TerminalExtraKeys.label(action));
      chip.setCheckable(false);
      chip.setCloseIconVisible(true);
      chip.setContentDescription(TerminalExtraKeys.description(action));
      chip.setOnClickListener(v -> showKeyMenu(row, index));
      chip.setOnLongClickListener(
          v -> {
            showKeyMenu(row, index);
            return true;
          });
      chip.setOnCloseIconClickListener(v -> removeKey(row, index));
      return chip;
    }

    private void showKeyMenu(int row, int index) {
      List<String> actions = drafts.get(selected).get(row);
      if (index < 0 || index >= actions.size()) return;
      String action = actions.get(index);
      String[] items = {
        UiText.choose("替换", "Replace"),
        UiText.choose("左移", "Move left"),
        UiText.choose("右移", "Move right"),
        UiText.choose("移除", "Remove")
      };
      new DshaDialogBuilder(context)
          .setTitle(TerminalExtraKeys.label(action))
          .setItems(
              items,
              (menu, which) -> {
                if (which == 0) chooseReplacement(row, index);
                else if (which == 1) moveKey(row, index, -1);
                else if (which == 2) moveKey(row, index, 1);
                else removeKey(row, index);
              })
          .setNegativeButton(UiText.choose("取消", "Cancel"), null)
          .show();
    }

    private void chooseReplacement(int row, int index) {
      List<String> actions = drafts.get(selected).get(row);
      if (index < 0 || index >= actions.size()) return;
      List<String> candidates = candidates(actions.get(index));
      if (candidates.isEmpty()) {
        toast(UiText.choose("没有可替换的按键", "No other keys available"));
        return;
      }
      chooseAction(
          candidates,
          action -> {
            List<String> target = drafts.get(selected).get(row);
            if (index < target.size()) {
              target.set(index, action);
              renderRows();
            }
          });
    }

    private void addKey(int row) {
      List<String> actions = drafts.get(selected).get(row);
      if (actions.size() >= TerminalExtraKeys.MAX_KEYS_PER_ROW) {
        toast(UiText.choose("每行已满，请先移除其他按键", "This row is full; remove a key first"));
        return;
      }
      List<String> candidates = candidates(null);
      if (candidates.isEmpty()) {
        toast(UiText.choose("没有更多可添加的按键", "No more keys available"));
        return;
      }
      chooseAction(
          candidates,
          action -> {
            List<String> target = drafts.get(selected).get(row);
            if (target.size() < TerminalExtraKeys.MAX_KEYS_PER_ROW && !target.contains(action)) {
              target.add(action);
              renderRows();
            }
          });
    }

    /** catalog 减去当前 profile 已用动作；allow 用于替换时保留当前项本身。 */
    private List<String> candidates(String allow) {
      Set<String> used = new HashSet<>();
      for (List<String> row : drafts.get(selected)) used.addAll(row);
      List<String> result = new ArrayList<>();
      for (String action : TerminalExtraKeys.catalog())
        if (!used.contains(action) || action.equals(allow)) result.add(action);
      return result;
    }

    private void chooseAction(List<String> candidates, Consumer<String> onPicked) {
      String[] labels = new String[candidates.size()];
      for (int index = 0; index < labels.length; index++)
        labels[index] = TerminalExtraKeys.label(candidates.get(index));
      new DshaDialogBuilder(context)
          .setTitle(UiText.choose("选择按键", "Choose key"))
          .setItems(
              labels,
              (menu, which) -> {
                if (which >= 0 && which < candidates.size()) onPicked.accept(candidates.get(which));
              })
          .setNegativeButton(UiText.choose("取消", "Cancel"), null)
          .show();
    }

    private void moveKey(int row, int index, int delta) {
      List<String> actions = drafts.get(selected).get(row);
      int target = index + delta;
      if (index < 0 || index >= actions.size()) return;
      if (target < 0 || target >= actions.size()) {
        toast(
            delta < 0
                ? UiText.choose("已是最左", "Already leftmost")
                : UiText.choose("已是最右", "Already rightmost"));
        return;
      }
      actions.add(target, actions.remove(index));
      renderRows();
    }

    private void removeKey(int row, int index) {
      List<String> actions = drafts.get(selected).get(row);
      if (index < 0 || index >= actions.size()) return;
      actions.remove(index);
      renderRows();
    }

    private void reset() {
      drafts.put(selected, normalize(TerminalExtraKeys.defaults(selected)));
      renderRows();
    }

    private void save() {
      TerminalExtraKeys.Profile empty = firstEmpty();
      if (empty != null) {
        select(empty);
        toast(
            empty == TerminalExtraKeys.Profile.TABLET
                ? UiText.choose("平板布局至少保留一个按键", "Keep at least one key in the tablet layout")
                : UiText.choose("手机布局至少保留一个按键", "Keep at least one key in the phone layout"));
        return;
      }
      SharedPreferences.Editor editor = prefs.edit();
      for (TerminalExtraKeys.Profile profile : TerminalExtraKeys.Profile.values()) {
        editor.putString(
            TerminalExtraKeys.prefKey(profile),
            TerminalExtraKeys.encode(TerminalExtraKeys.sanitize(drafts.get(profile))));
      }
      editor.apply();
      toast(UiText.choose("已保存", "Saved"));
      try {
        if (onSaved != null) onSaved.run();
      } finally {
        dialog.dismiss();
      }
    }

    private void select(TerminalExtraKeys.Profile profile) {
      selected = profile;
      updating = true;
      profiles.check(profile == TerminalExtraKeys.Profile.TABLET ? tablet.getId() : phone.getId());
      updating = false;
      renderRows();
    }

    private TerminalExtraKeys.Profile firstEmpty() {
      for (TerminalExtraKeys.Profile profile : TerminalExtraKeys.Profile.values()) {
        int count = 0;
        for (List<String> row : drafts.get(profile)) count += row.size();
        if (count == 0) return profile;
      }
      return null;
    }

    private void toast(String text) {
      Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
    }
  }

  /** 固定两行、可编辑副本；decode 允许只返回一行，编辑界面始终补齐第二行。 */
  private static List<List<String>> normalize(List<List<String>> source) {
    List<List<String>> result = new ArrayList<>();
    if (source != null) {
      for (List<String> row : source) {
        if (result.size() >= ROWS) break;
        result.add(row == null ? new ArrayList<>() : new ArrayList<>(row));
      }
    }
    while (result.size() < ROWS) result.add(new ArrayList<>());
    return result;
  }
}
