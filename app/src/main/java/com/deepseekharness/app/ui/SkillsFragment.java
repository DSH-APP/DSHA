package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.core.MaintenanceCoordinator;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.skills.SkillImports;
import com.deepseekharness.app.skills.SkillLibrary;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.SheetGeometry;
import com.deepseekharness.app.util.SkillDocument;
import com.deepseekharness.app.util.SkillLifecycle;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 用户全局技能（设置子页）。使用官方全局技能目录，不执行导入正文。
 *
 * <p>布局按本页实际内容宽度分三档，不按设备类型：
 *
 * <ul>
 *   <li>&lt; {@link #MEDIUM_DP}dp（手机竖屏、分屏小窗）：单列，两行描述，点行从底部弹出详情；
 *   <li>{@link #MEDIUM_DP}–{@link #SPLIT_DP}dp（平板竖屏、手机横屏）：单列宽行，名称与标签同一行，详情居中弹出；
 *   <li>≥ {@link #SPLIT_DP}dp（平板横屏）：左列表、右详情。
 * </ul>
 *
 * <p>每行带启用开关（原地改名 SKILL.md ⇄ SKILL.md.disabled）。删除默认进入「最近删除」，保留天数可配置，到期在下次打开本页时清理；
 * 也可直接彻底删除。所有写操作与导入共用环境门禁、维护屏障与 RuntimeTasks。
 */
public final class SkillsFragment extends Fragment {
  /** 单列宽行所需的最小内容宽度。 */
  static final int MEDIUM_DP = 600;

  /** 列表 + 详情并排所需的最小内容宽度；平板竖屏（去掉导航栏后约 700dp）仍走单列，避免两栏都过窄。 */
  static final int SPLIT_DP = 780;

  static final String PREFS = "dsha_skills";
  static final String KEY_RETENTION = "trash_retention_days";

  private static final String STATE_SELECTED = "skills.selected";
  private static final int SOURCE_PREVIEW_LINES = 8;

  enum Filter {
    ALL,
    ENABLED,
    DISABLED
  }

  enum Tier {
    NARROW,
    MEDIUM,
    WIDE
  }

  static final class Result {
    final String code, name, path;
    final int count;

    Result(String code, String name, String path) {
      this(code, name, path, 0);
    }

    Result(String code, String name, String path, int count) {
      this.code = code;
      this.name = name;
      this.path = path;
      this.count = count;
    }
  }

  public static final class Model extends ViewModel {
    final MutableLiveData<SkillImports.Catalogue> catalogue = new MutableLiveData<>();
    final MutableLiveData<Result> result = new MutableLiveData<>(new Result("READY", "", ""));
    final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
    final AtomicBoolean working = new AtomicBoolean();
    BackupControl control;
    boolean committing;
    Filter filter = Filter.ALL;
    String query = "";
    boolean trashExpanded;

    synchronized void cancel() {
      if (control != null && !committing) control.cancel();
    }

    @Override
    protected void onCleared() {
      cancel();
    }
  }

  /** 后台写操作；返回结果条内容。 */
  private interface Work {
    Result run(Context app) throws Exception;
  }

  private Model model;
  private CardPage ui;
  private LinearLayout split,
      listColumn,
      detailColumn,
      progressRow,
      toolsRow,
      segmentRow,
      statusRow;
  private TextView status, progressText;
  private Button importButton, refreshButton, cancelButton, undoButton;
  private final Button[] segments = new Button[3];
  private EditText search;
  private ShellActivityTrack progressTrack;
  @Nullable private String selected;
  private Tier tier = Tier.NARROW;
  private boolean autoLoad = true;
  @Nullable private androidx.appcompat.app.AlertDialog sheet;

  private final ActivityResultLauncher<String[]> picker =
      registerForActivityResult(
          new ActivityResultContracts.OpenDocument(),
          uri -> {
            if (uri != null) start(uri);
          });

  private static String t(String chinese, String english) {
    return UiText.choose(chinese, english);
  }

  private static String tf(String chinese, String english, Object... values) {
    return String.format(Locale.ROOT, UiText.choose(chinese, english), values);
  }

  /** 仅测试用：跳过自动读取目录，由测试直接渲染固定目录。 */
  void disableAutoLoadForTest() {
    autoLoad = false;
  }

  /** 仅测试用：直接提供目录。 */
  void showCatalogueForTest(SkillImports.Catalogue catalogue) {
    model.catalogue.setValue(catalogue);
  }

  static Tier tierFor(int widthDp) {
    return widthDp >= SPLIT_DP ? Tier.WIDE : widthDp >= MEDIUM_DP ? Tier.MEDIUM : Tier.NARROW;
  }

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle saved) {
    model = new ViewModelProvider(this).get(Model.class);
    if (saved != null) selected = saved.getString(STATE_SELECTED);
    Context ctx = requireContext();
    ui = new CardPage(ctx, t("技能", "Skills"), "");
    // 页首一句话，导入规则与存放位置收进 ⓘ。
    TextView hint = ui.text("", 13, R.color.shell_text_secondary);
    hint.setPadding(0, 0, 0, ui.dp(14));
    ui.content.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    InfoHint.bind(
        hint,
        t("技能", "Skills"),
        UiText.format(
            "导入 SKILL.md，让 DSH 按需读取可复用的任务说明。\n\n这里管理用户全局技能，无需 Root。导入仅复制 SKILL.md，正文中的命令不会自动执行；引用的脚本和资料需用文件入口另行放入同一技能目录。\n\n存放位置：%s",
            SkillImports.GUEST_ROOT));

    // 工具行：搜索 + 导入。
    toolsRow = new LinearLayout(ctx);
    toolsRow.setGravity(Gravity.CENTER_VERTICAL);
    search = new androidx.appcompat.widget.AppCompatEditText(ctx);
    search.setTag("skills.search");
    search.setSingleLine(true);
    search.setTextSize(14);
    search.setHint(t("搜索名称或描述", "Search name or description"));
    search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
    search.setBackgroundResource(R.drawable.bg_shell_input);
    search.setTextColor(ctx.getColor(R.color.shell_text));
    search.setHintTextColor(ctx.getColor(R.color.shell_text_muted));
    search.setPadding(ui.dp(14), 0, ui.dp(14), 0);
    search.setMinHeight(ui.dp(46));
    search.setText(model.query);
    search.addTextChangedListener(
        new TextWatcher() {
          @Override
          public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

          @Override
          public void onTextChanged(CharSequence s, int a, int b, int c) {}

          @Override
          public void afterTextChanged(Editable s) {
            String next = s.toString().trim();
            if (next.equals(model.query)) return;
            model.query = next;
            render(model.catalogue.getValue());
          }
        });
    toolsRow.addView(search, new LinearLayout.LayoutParams(0, ui.dp(46), 1f));
    importButton = new androidx.appcompat.widget.AppCompatButton(ctx);
    importButton.setText(t("导入技能", "Import SKILL.md"));
    styleButton(importButton, true);
    importButton.setMinHeight(ui.dp(46));
    importButton.setMinimumHeight(ui.dp(46));
    importButton.setPadding(ui.dp(16), 0, ui.dp(16), 0);
    importButton.setOnClickListener(v -> RecentsPrivacyGuard.launch(picker, new String[] {"*/*"}));
    LinearLayout.LayoutParams importParams = new LinearLayout.LayoutParams(-2, ui.dp(46));
    importParams.setMarginStart(ui.dp(10));
    toolsRow.addView(importButton, importParams);
    ui.content.addView(toolsRow, new LinearLayout.LayoutParams(-1, -2));

    // 分段筛选 + 刷新。
    segmentRow = new LinearLayout(ctx);
    segmentRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout track = new LinearLayout(ctx);
    track.setBackgroundResource(R.drawable.bg_plugin_segment_track);
    track.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
    Filter[] filters = Filter.values();
    for (int i = 0; i < filters.length; i++) {
      Filter filter = filters[i];
      Button segment = new androidx.appcompat.widget.AppCompatButton(ctx);
      segment.setTag("skills.segment." + filter.name().toLowerCase(Locale.ROOT));
      segment.setAllCaps(false);
      segment.setTextSize(13);
      segment.setSingleLine(true);
      segment.setIncludeFontPadding(false);
      segment.setBackgroundResource(R.drawable.bg_shell_segment);
      segment.setMinHeight(ui.dp(38));
      segment.setMinimumHeight(ui.dp(38));
      segment.setMinWidth(0);
      segment.setMinimumWidth(0);
      segment.setPadding(ui.dp(10), 0, ui.dp(10), 0);
      segment.setOnClickListener(
          v -> {
            model.filter = filter;
            render(model.catalogue.getValue());
          });
      segments[i] = segment;
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ui.dp(38), 1f);
      if (i > 0) p.setMarginStart(ui.dp(2));
      track.addView(segment, p);
    }
    segmentRow.addView(track, new LinearLayout.LayoutParams(0, -2, 1f));
    refreshButton = textButton(ctx, t("刷新", "Refresh"));
    refreshButton.setOnClickListener(v -> start(null));
    LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(-2, -2);
    refreshParams.setMarginStart(ui.dp(8));
    segmentRow.addView(refreshButton, refreshParams);
    LinearLayout.LayoutParams segmentParams = new LinearLayout.LayoutParams(-1, -2);
    segmentParams.topMargin = ui.dp(12);
    ui.content.addView(segmentRow, segmentParams);

    // 进度行：只在读取 / 写入时出现，带取消。
    progressRow = new LinearLayout(ctx);
    progressRow.setOrientation(LinearLayout.VERTICAL);
    progressRow.setVisibility(View.GONE);
    LinearLayout progressHead = new LinearLayout(ctx);
    progressHead.setGravity(Gravity.CENTER_VERTICAL);
    progressText = ui.text("", 13, R.color.shell_text_secondary);
    progressText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    progressHead.addView(progressText, new LinearLayout.LayoutParams(0, -2, 1));
    cancelButton = textButton(ctx, t("取消", "Cancel"));
    cancelButton.setOnClickListener(v -> model.cancel());
    progressHead.addView(cancelButton);
    progressRow.addView(progressHead);
    progressTrack = new ShellActivityTrack(ctx);
    progressTrack.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    progressRow.addView(progressTrack, new LinearLayout.LayoutParams(-1, ui.dp(16)));
    LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, -2);
    progressParams.topMargin = ui.dp(10);
    ui.content.addView(progressRow, progressParams);

    // 结果条：成功 / 出错时出现；禁用与删除带「撤销」。
    statusRow = new LinearLayout(ctx);
    statusRow.setGravity(Gravity.CENTER_VERTICAL);
    statusRow.setVisibility(View.GONE);
    statusRow.setPadding(ui.dp(12), ui.dp(4), ui.dp(4), ui.dp(4));
    status = ui.text("", 13, R.color.shell_text);
    status.setLineSpacing(ui.dp(2), 1f);
    status.setPadding(0, ui.dp(6), 0, ui.dp(6));
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1f));
    undoButton = textButton(ctx, t("撤销", "Undo"));
    undoButton.setOnClickListener(v -> undo());
    statusRow.addView(undoButton);
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
    statusParams.topMargin = ui.dp(10);
    ui.content.addView(statusRow, statusParams);

    split = new LinearLayout(ctx);
    split.setOrientation(LinearLayout.HORIZONTAL);
    listColumn = ui.column();
    detailColumn = ui.column();
    split.addView(listColumn, new LinearLayout.LayoutParams(0, -2, 1f));
    LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(0, -2, 1f);
    detailParams.setMarginStart(ui.dp(14));
    split.addView(detailColumn, detailParams);
    LinearLayout.LayoutParams splitParams = new LinearLayout.LayoutParams(-1, -2);
    splitParams.topMargin = ui.dp(6);
    ui.content.addView(split, splitParams);
    ui.footer.setVisibility(View.GONE);

    // 按本页实际宽度切换三档；只在跨越阈值时重排，避免布局循环。
    ui.root.addOnLayoutChangeListener(
        (v, l, top, r, b, ol, ot, oR, ob) -> {
          float density = getResources().getDisplayMetrics().density;
          Tier next = tierFor(Math.round((r - l) / density));
          if (next != tier) {
            tier = next;
            v.post(() -> render(model.catalogue.getValue()));
          }
        });

    model.catalogue.observe(getViewLifecycleOwner(), this::render);
    model.result.observe(getViewLifecycleOwner(), this::renderResult);
    model.busy.observe(getViewLifecycleOwner(), busy -> renderBusy(Boolean.TRUE.equals(busy)));
    if (autoLoad && model.catalogue.getValue() == null && !model.working.get()) start(null);
    return ui.root;
  }

  @Override
  public void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    if (selected != null) out.putString(STATE_SELECTED, selected);
  }

  @Override
  public void onDestroyView() {
    if (sheet != null) sheet.dismiss();
    sheet = null;
    super.onDestroyView();
  }

  // ---- 控件 ----

  private Button textButton(Context ctx, String label) {
    Button button = new androidx.appcompat.widget.AppCompatButton(ctx);
    button.setText(label);
    button.setAllCaps(false);
    button.setTextSize(13);
    button.setTextColor(ctx.getColor(R.color.shell_accent));
    button.setBackgroundResource(R.drawable.bg_action_plain);
    button.setMinHeight(ui.dp(44));
    button.setMinimumHeight(ui.dp(44));
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(ui.dp(12), 0, ui.dp(12), 0);
    return button;
  }

  private void styleButton(Button button, boolean primary) {
    Context ctx = requireContext();
    button.setAllCaps(false);
    button.setTextSize(14);
    button.setTypeface(null, android.graphics.Typeface.BOLD);
    button.setIncludeFontPadding(false);
    button.setSingleLine(true);
    button.setMinHeight(ui.dp(primary ? 50 : 44));
    button.setMinimumHeight(ui.dp(primary ? 50 : 44));
    button.setMinWidth(0);
    button.setMinimumWidth(0);
    button.setPadding(ui.dp(22), 0, ui.dp(22), 0);
    button.setBackgroundResource(primary ? R.drawable.bg_btn_primary : R.drawable.bg_btn);
    button.setTextColor(
        ctx.getColorStateList(primary ? R.color.button_primary_text : R.color.button_text));
  }

  /** 危险操作按钮：软红底 + 状态色描边，与主按钮语义分开。 */
  private Button dangerButton(Context ctx, String label) {
    Button button = new androidx.appcompat.widget.AppCompatButton(ctx);
    button.setText(label);
    styleButton(button, false);
    button.setBackgroundResource(R.drawable.bg_shell_danger);
    button.setTextColor(ctx.getColor(R.color.shell_bad));
    return button;
  }

  private Button plainButton(Context ctx, String label, boolean primary) {
    Button button = new androidx.appcompat.widget.AppCompatButton(ctx);
    button.setText(label);
    styleButton(button, primary);
    return button;
  }

  private void renderBusy(boolean active) {
    if (importButton == null) return;
    importButton.setEnabled(!active);
    refreshButton.setEnabled(!active);
    cancelButton.setEnabled(active && !model.committing);
    undoButton.setEnabled(!active);
    progressRow.setVisibility(active ? View.VISIBLE : View.GONE);
    progressTrack.setState(active ? ShellActivityTrack.RUNNING : ShellActivityTrack.IDLE);
    // 开关与行内操作随忙碌状态一起禁用。
    render(model.catalogue.getValue());
  }

  private boolean busy() {
    return Boolean.TRUE.equals(model.busy.getValue());
  }

  // ---- 列表 ----

  /** 渲染目录：分段计数、启用 / 禁用分组、最近删除；并排时右侧显示选中技能详情。 */
  void render(@Nullable SkillImports.Catalogue catalogue) {
    if (listColumn == null || getContext() == null) return;
    Context ctx = requireContext();
    listColumn.removeAllViews();
    detailColumn.removeAllViews();
    boolean wide = tier == Tier.WIDE;
    LinearLayout.LayoutParams listParams = (LinearLayout.LayoutParams) listColumn.getLayoutParams();
    listParams.weight = wide ? 0.95f : 1f;
    listColumn.setLayoutParams(listParams);
    LinearLayout.LayoutParams detailParams =
        (LinearLayout.LayoutParams) detailColumn.getLayoutParams();
    detailParams.weight = wide ? 1.05f : 0f;
    detailColumn.setLayoutParams(detailParams);
    if (catalogue == null) {
      segmentRow.setVisibility(View.GONE);
      detailColumn.setVisibility(View.GONE);
      return;
    }
    boolean empty = catalogue.entries.isEmpty() && catalogue.trash.isEmpty();
    toolsRow.setVisibility(empty ? View.GONE : View.VISIBLE);
    segmentRow.setVisibility(empty ? View.GONE : View.VISIBLE);
    int total = catalogue.entries.size(), on = catalogue.enabledCount();
    int[] counts = {total, on, total - on};
    String[][] labels = {{"全部 %d", "All %d"}, {"已启用 %d", "On %d"}, {"已禁用 %d", "Off %d"}};
    for (int i = 0; i < segments.length; i++) {
      Button segment = segments[i];
      boolean chosen = model.filter.ordinal() == i;
      String label = tf(labels[i][0], labels[i][1], counts[i]);
      segment.setText(label);
      segment.setSelected(chosen);
      segment.setTypeface(
          null, chosen ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
      segment.setTextColor(
          ctx.getColor(chosen ? R.color.shell_text : R.color.shell_text_secondary));
    }
    if (catalogue.unreadable > 0) {
      TextView notice = ui.text("", 12, R.color.shell_warn);
      notice.setBackgroundResource(R.drawable.bg_shell_status);
      notice.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
      notice.setText(UiText.format("另有 %s 个格式不符", String.valueOf(catalogue.unreadable)));
      LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(-1, -2);
      noticeParams.topMargin = ui.dp(10);
      listColumn.addView(notice, noticeParams);
    }
    if (empty) {
      selected = null;
      detailColumn.setVisibility(View.GONE);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.topMargin = ui.dp(4);
      listColumn.addView(emptyState(ctx), p);
      return;
    }
    String query = model.query.toLowerCase(Locale.ROOT);
    List<SkillImports.Entry> enabled = new ArrayList<>(), disabled = new ArrayList<>();
    for (SkillImports.Entry entry : catalogue.entries) {
      if (model.filter == Filter.ENABLED && !entry.enabled) continue;
      if (model.filter == Filter.DISABLED && entry.enabled) continue;
      if (!query.isEmpty()
          && !entry.document.name.toLowerCase(Locale.ROOT).contains(query)
          && !entry.document.description.toLowerCase(Locale.ROOT).contains(query)) continue;
      (entry.enabled ? enabled : disabled).add(entry);
    }
    SkillImports.Entry current = null;
    for (SkillImports.Entry entry : catalogue.entries)
      if (entry.document.name.equals(selected)
          && (enabled.contains(entry) || disabled.contains(entry))) current = entry;
    if (wide && current == null) {
      current = !enabled.isEmpty() ? enabled.get(0) : !disabled.isEmpty() ? disabled.get(0) : null;
      selected = current == null ? null : current.document.name;
    }
    if (!enabled.isEmpty()) group(ctx, t("已启用", "On") + " · " + enabled.size(), enabled, current);
    if (!disabled.isEmpty())
      group(ctx, t("已禁用", "Off") + " · " + disabled.size(), disabled, current);
    if (enabled.isEmpty() && disabled.isEmpty()) {
      TextView none =
          ui.text(
              catalogue.entries.isEmpty()
                  ? t("没有已安装的技能。", "No installed skills.")
                  : t("没有符合条件的技能。", "No matching skills."),
              13,
              R.color.shell_text_secondary);
      none.setGravity(Gravity.CENTER);
      none.setPadding(ui.dp(12), ui.dp(24), ui.dp(12), ui.dp(24));
      none.setBackgroundResource(R.drawable.bg_shell_card);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.topMargin = ui.dp(12);
      listColumn.addView(none, p);
    }
    if (model.filter == Filter.ALL && query.isEmpty() && !catalogue.trash.isEmpty())
      trashSection(ctx, catalogue.trash);
    detailColumn.setVisibility(wide && current != null ? View.VISIBLE : View.GONE);
    if (wide && current != null) {
      LinearLayout card = ui.column();
      card.setTag("skills.detail");
      card.setBackgroundResource(R.drawable.bg_shell_card);
      card.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), ui.dp(16));
      detail(ctx, card, current, null);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.topMargin = ui.dp(34);
      detailColumn.addView(card, p);
    }
  }

  private TextView sectionHead(String label) {
    TextView head = ui.text(label, 12, R.color.shell_text_secondary);
    head.setTypeface(null, android.graphics.Typeface.BOLD);
    head.setPadding(ui.dp(2), ui.dp(16), 0, ui.dp(8));
    return head;
  }

  private void group(
      Context ctx, String label, List<SkillImports.Entry> entries, SkillImports.Entry current) {
    TextView head = sectionHead(label);
    head.setTag("skills.count");
    head.setAccessibilityHeading(true);
    listColumn.addView(head);
    LinearLayout group = ui.column();
    group.setBackgroundResource(R.drawable.bg_shell_card);
    group.setPadding(0, ui.dp(4), 0, ui.dp(4));
    for (int i = 0; i < entries.size(); i++) {
      SkillImports.Entry entry = entries.get(i);
      if (i > 0) group.addView(divider(ctx));
      group.addView(
          row(ctx, entry, tier == Tier.WIDE && entry == current),
          new LinearLayout.LayoutParams(-1, -2));
    }
    listColumn.addView(group, new LinearLayout.LayoutParams(-1, -2));
  }

  private View divider(Context ctx) {
    View line = new View(ctx);
    line.setBackgroundColor(ctx.getColor(R.color.shell_line));
    LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(-1, 1);
    lineParams.setMarginStart(ui.dp(66));
    line.setLayoutParams(lineParams);
    return line;
  }

  private View emptyState(Context ctx) {
    LinearLayout empty = ui.column();
    empty.setGravity(Gravity.CENTER_HORIZONTAL);
    empty.setBackgroundResource(R.drawable.bg_shell_card);
    empty.setPadding(ui.dp(20), ui.dp(28), ui.dp(20), ui.dp(20));
    empty.addView(iconTile(ctx, 26, true), new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));
    TextView title = ui.text(t("还没有技能", "No skills yet"), 16, R.color.shell_text);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    title.setGravity(Gravity.CENTER);
    title.setPadding(0, ui.dp(14), 0, ui.dp(6));
    empty.addView(title);
    TextView sub =
        ui.text(
            t(
                "导入 SKILL.md 后在此显示",
                "Imported SKILL.md files appear here; DSH loads them as needed."),
            13,
            R.color.shell_text_secondary);
    sub.setGravity(Gravity.CENTER);
    sub.setLineSpacing(ui.dp(3), 1f);
    empty.addView(sub);
    Button add = plainButton(ctx, t("导入技能", "Import SKILL.md"), true);
    add.setEnabled(!busy());
    add.setOnClickListener(v -> RecentsPrivacyGuard.launch(picker, new String[] {"*/*"}));
    LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(-2, -2);
    addParams.topMargin = ui.dp(16);
    empty.addView(add, addParams);
    Button format = textButton(ctx, t("格式要求", "Format requirements"));
    format.setOnClickListener(
        v ->
            InfoHint.show(
                ctx,
                t("格式要求", "Format requirements"),
                t(
                    "· 文件名必须是 SKILL.md，UTF-8 文本，不超过 1 MiB。\n· 以 --- 开头的 YAML 头，包含小写短横线 name 和非空 description。\n· 可选 disable-model-invocation / user-invocable 控制调用方式。\n· 只复制 SKILL.md，不执行其中命令；同名技能不会被覆盖。",
                    "· The file must be named SKILL.md, UTF-8 text, at most 1 MiB.\n· Start with --- YAML frontmatter containing a lowercase kebab-case name and a non-empty description.\n· Optional disable-model-invocation / user-invocable control how it is invoked.\n· Only SKILL.md is copied and its commands never run; a skill with the same name is not overwritten.")));
    LinearLayout.LayoutParams formatParams = new LinearLayout.LayoutParams(-2, -2);
    formatParams.topMargin = ui.dp(6);
    empty.addView(format, formatParams);
    return empty;
  }

  /** 图标块：启用为强调色，禁用 / 删除为次级色，不只靠颜色区分（另有文字标签）。 */
  private View iconTile(Context ctx, int iconDp, boolean active) {
    FrameLayout tile = new FrameLayout(ctx);
    tile.setBackgroundResource(R.drawable.bg_launch_tile);
    if (!active) tile.setAlpha(0.55f);
    tile.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    ImageView icon = new ImageView(ctx);
    icon.setImageResource(R.drawable.ic_ui_tasks);
    icon.setColorFilter(ctx.getColor(active ? R.color.shell_accent : R.color.shell_text_muted));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ui.dp(iconDp), ui.dp(iconDp));
    params.gravity = Gravity.CENTER;
    tile.addView(icon, params);
    return tile;
  }

  /** 列表行：图标 + 名称 / 描述 / 标签 + 启用开关；单列带箭头，并排时选中项高亮。 */
  private View row(Context ctx, SkillImports.Entry entry, boolean isSelected) {
    LinearLayout row = new LinearLayout(ctx);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(ui.dp(tier == Tier.MEDIUM ? 64 : 68));
    row.setPadding(ui.dp(12), ui.dp(10), ui.dp(8), ui.dp(10));
    row.setClickable(true);
    row.setFocusable(true);
    row.setTag("skill:" + entry.document.name);
    row.setBackgroundResource(isSelected ? R.drawable.bg_settings_row : 0);
    row.setSelected(isSelected);
    row.setForeground(ctx.getDrawable(R.drawable.bg_action_plain));
    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
    iconParams.setMarginEnd(ui.dp(14));
    row.addView(iconTile(ctx, 20, entry.enabled), iconParams);
    LinearLayout copy = ui.column();
    TextView name =
        ui.text(
            entry.document.name, 15, entry.enabled ? R.color.shell_text : R.color.shell_text_muted);
    name.setTypeface(null, android.graphics.Typeface.BOLD);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    TextView description =
        ui.text(
            entry.document.description,
            12,
            entry.enabled ? R.color.shell_text_secondary : R.color.shell_text_muted);
    description.setEllipsize(android.text.TextUtils.TruncateAt.END);
    description.setLineSpacing(ui.dp(2), 1f);
    description.setPadding(0, ui.dp(4), 0, 0);
    View badges = badges(ctx, entry);
    if (tier == Tier.MEDIUM) {
      // 宽行：名称与标签同一行，描述只占一行，一屏放下更多技能。
      LinearLayout line = new LinearLayout(ctx);
      line.setGravity(Gravity.CENTER_VERTICAL);
      line.addView(name, new LinearLayout.LayoutParams(-2, -2, 0f));
      if (badges != null) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
        p.setMarginStart(ui.dp(8));
        line.addView(badges, p);
      }
      copy.addView(line);
      description.setMaxLines(1);
      copy.addView(description);
    } else {
      copy.addView(name);
      description.setMaxLines(tier == Tier.WIDE ? 1 : 2);
      copy.addView(description);
      if (badges != null) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.topMargin = ui.dp(6);
        copy.addView(badges, p);
      }
    }
    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
    MaterialSwitch toggle = new MaterialSwitch(ctx);
    toggle.setTag("skill.switch:" + entry.document.name);
    toggle.setChecked(entry.enabled);
    toggle.setEnabled(!busy());
    toggle.setContentDescription(tf("启用 %s", "Enable %s", entry.document.name));
    toggle.setOnCheckedChangeListener((button, checked) -> toggle(entry, checked));
    LinearLayout.LayoutParams switchParams = new LinearLayout.LayoutParams(-2, -2);
    switchParams.setMarginStart(ui.dp(6));
    row.addView(toggle, switchParams);
    if (tier != Tier.WIDE) {
      ImageView chevron = new ImageView(ctx);
      chevron.setImageResource(R.drawable.ic_ui2_chevron);
      chevron.setColorFilter(ctx.getColor(R.color.shell_text_muted));
      chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      // RTL 下箭头随阅读方向。
      if (chevron.getDrawable() != null) chevron.getDrawable().setAutoMirrored(true);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18));
      p.setMarginStart(ui.dp(4));
      row.addView(chevron, p);
    }
    row.setOnClickListener(
        v -> {
          if (tier == Tier.WIDE) {
            selected = entry.document.name;
            render(model.catalogue.getValue());
          } else showSheet(entry);
        });
    row.setContentDescription(
        UiText.raw(
            entry.document.name
                + ", "
                + (entry.enabled ? t("已启用", "On") : t("已禁用", "Off"))
                + ", "
                + entry.document.description));
    return row;
  }

  @Nullable
  private View badges(Context ctx, SkillImports.Entry entry) {
    LinearLayout line = new LinearLayout(ctx);
    line.setGravity(Gravity.CENTER_VERTICAL);
    if (!entry.enabled) line.addView(pill(ctx, t("已禁用", "Off"), Pill.OFF));
    if (entry.document.userInvocable)
      addPill(line, pill(ctx, "/" + entry.document.name, entry.enabled ? Pill.ACCENT : Pill.PLAIN));
    if (entry.document.modelInvocable && entry.enabled)
      addPill(line, pill(ctx, t("模型可调用", "Model can invoke"), Pill.PLAIN));
    return line.getChildCount() == 0 ? null : line;
  }

  private void addPill(LinearLayout line, View pill) {
    if (line.getChildCount() > 0) {
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
      p.setMarginStart(ui.dp(6));
      line.addView(pill, p);
    } else line.addView(pill);
  }

  private enum Pill {
    ACCENT,
    PLAIN,
    OFF,
    DELETED
  }

  private TextView pill(Context ctx, String label, Pill kind) {
    int text =
        switch (kind) {
          case ACCENT -> R.color.shell_accent;
          case DELETED -> R.color.shell_bad;
          case OFF -> R.color.shell_text;
          default -> R.color.shell_text_secondary;
        };
    TextView pill = ui.text(label, 11, text);
    pill.setSingleLine(true);
    pill.setEllipsize(android.text.TextUtils.TruncateAt.END);
    pill.setMaxWidth(ui.dp(180));
    pill.setPadding(ui.dp(8), ui.dp(3), ui.dp(8), ui.dp(3));
    if (kind == Pill.OFF) pill.setTypeface(null, android.graphics.Typeface.BOLD);
    GradientDrawable shape = new GradientDrawable();
    shape.setCornerRadius(ui.dp(999));
    shape.setColor(
        ctx.getColor(
            switch (kind) {
              case ACCENT -> R.color.shell_accent_soft;
              case DELETED -> R.color.shell_bad_soft;
              default -> R.color.shell_line;
            }));
    if (kind == Pill.OFF) shape.setStroke(ui.dp(1), ctx.getColor(R.color.shell_line_strong));
    pill.setBackground(shape);
    return pill;
  }

  // ---- 最近删除 ----

  private void trashSection(Context ctx, List<SkillLibrary.Item> items) {
    int days = retentionDays(ctx);
    LinearLayout head = new LinearLayout(ctx);
    head.setGravity(Gravity.CENTER_VERTICAL);
    head.setTag("skills.trash");
    TextView label = sectionHead(t("最近删除", "Recently deleted") + " · " + items.size());
    label.setAccessibilityHeading(true);
    head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
    Button retention = textButton(ctx, tf("保留 %d 天", "Keep %d days", days));
    retention.setTag("skills.retention");
    retention.setContentDescription(
        tf("最近删除保留 %d 天，点按修改", "Recently deleted kept for %d days. Tap to change", days));
    retention.setOnClickListener(v -> showRetention());
    head.addView(retention);
    Button expand = textButton(ctx, model.trashExpanded ? t("收起", "Collapse") : t("展开", "Expand"));
    expand.setOnClickListener(
        v -> {
          model.trashExpanded = !model.trashExpanded;
          render(model.catalogue.getValue());
        });
    head.addView(expand);
    LinearLayout.LayoutParams headParams = new LinearLayout.LayoutParams(-1, -2);
    headParams.topMargin = ui.dp(6);
    listColumn.addView(head, headParams);
    LinearLayout card = ui.column();
    card.setBackgroundResource(R.drawable.bg_shell_card);
    card.setPadding(0, ui.dp(4), 0, ui.dp(4));
    long now = System.currentTimeMillis();
    if (!model.trashExpanded) {
      // 收起时只给一行摘要，最早到期的一项决定提示。
      int soonest = Integer.MAX_VALUE;
      for (SkillLibrary.Item item : items)
        soonest = Math.min(soonest, SkillLifecycle.remainingDays(item.meta.deletedAt, now, days));
      TextView summary =
          ui.text(
              tf(
                  "%d 个技能，最早一个 %s。删除后 %d 天彻底清除。",
                  "%d skills; the oldest is removed %s. Items are removed %d days after deletion.",
                  items.size(),
                  remaining(soonest),
                  days),
              12,
              R.color.shell_text_secondary);
      summary.setLineSpacing(ui.dp(2), 1f);
      summary.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
      card.addView(summary);
      listColumn.addView(card, new LinearLayout.LayoutParams(-1, -2));
      return;
    }
    for (int i = 0; i < items.size(); i++) {
      if (i > 0) card.addView(divider(ctx));
      card.addView(trashRow(ctx, items.get(i), now, days), new LinearLayout.LayoutParams(-1, -2));
    }
    listColumn.addView(card, new LinearLayout.LayoutParams(-1, -2));
    Button empty = textButton(ctx, t("清空最近删除", "Empty recently deleted"));
    empty.setTag("skills.trash.empty");
    empty.setTextColor(ctx.getColor(R.color.shell_bad));
    empty.setEnabled(!busy());
    empty.setOnClickListener(v -> confirmEmptyTrash(items.size()));
    LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(-2, -2);
    emptyParams.gravity = Gravity.END;
    emptyParams.topMargin = ui.dp(4);
    listColumn.addView(empty, emptyParams);
  }

  private String remaining(int days) {
    return days <= 0 ? t("今天彻底删除", "removed today") : tf("%d 天后彻底删除", "removed in %d days", days);
  }

  private View trashRow(Context ctx, SkillLibrary.Item item, long now, int days) {
    LinearLayout row = new LinearLayout(ctx);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setTag("skill.trash:" + item.meta.name);
    row.setPadding(ui.dp(12), ui.dp(10), ui.dp(8), ui.dp(6));
    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40));
    iconParams.setMarginEnd(ui.dp(14));
    iconParams.gravity = Gravity.TOP;
    row.addView(iconTile(ctx, 20, false), iconParams);
    LinearLayout copy = ui.column();
    LinearLayout line = new LinearLayout(ctx);
    line.setGravity(Gravity.CENTER_VERTICAL);
    TextView name = ui.text(item.meta.name, 15, R.color.shell_text_muted);
    name.setTypeface(null, android.graphics.Typeface.BOLD);
    name.setSingleLine(true);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    line.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
    copy.addView(line);
    int left = SkillLifecycle.remainingDays(item.meta.deletedAt, now, days);
    TextView when = ui.text(remaining(left), 12, R.color.shell_bad);
    when.setPadding(0, ui.dp(4), 0, 0);
    copy.addView(when);
    if (!item.description.isEmpty()) {
      TextView description = ui.text(item.description, 12, R.color.shell_text_muted);
      description.setMaxLines(1);
      description.setEllipsize(android.text.TextUtils.TruncateAt.END);
      description.setPadding(0, ui.dp(2), 0, 0);
      copy.addView(description);
    }
    LinearLayout actions = new LinearLayout(ctx);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    Button restore = textButton(ctx, t("恢复", "Restore"));
    restore.setTag("skill.restore:" + item.meta.name);
    restore.setEnabled(!busy());
    restore.setContentDescription(tf("恢复 %s", "Restore %s", item.meta.name));
    restore.setOnClickListener(v -> restore(item));
    restore.setPadding(0, 0, ui.dp(12), 0);
    actions.addView(restore);
    Button purge = textButton(ctx, t("彻底删除", "Delete permanently"));
    purge.setTag("skill.purge:" + item.meta.name);
    purge.setTextColor(ctx.getColor(R.color.shell_bad));
    purge.setEnabled(!busy());
    purge.setContentDescription(tf("彻底删除 %s", "Permanently delete %s", item.meta.name));
    purge.setOnClickListener(v -> confirmPurge(item));
    actions.addView(purge);
    copy.addView(actions);
    row.addView(copy, new LinearLayout.LayoutParams(0, -2, 1f));
    return row;
  }

  // ---- 详情 ----

  /** 详情内容（并排时右侧卡片与单列弹层共用）。dismiss 非空时表示在弹层内，操作前先关闭弹层。 */
  private void detail(
      Context ctx, LinearLayout body, SkillImports.Entry entry, @Nullable Runnable dismiss) {
    LinearLayout header = new LinearLayout(ctx);
    header.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(ui.dp(46), ui.dp(46));
    tileParams.setMarginEnd(ui.dp(12));
    header.addView(iconTile(ctx, 22, entry.enabled), tileParams);
    LinearLayout titles = ui.column();
    TextView name = ui.text(entry.document.name, 18, R.color.shell_text);
    name.setTypeface(null, android.graphics.Typeface.BOLD);
    name.setAccessibilityHeading(true);
    titles.addView(name);
    TextView description = ui.text(entry.document.description, 13, R.color.shell_text_secondary);
    description.setLineSpacing(ui.dp(3), 1f);
    description.setPadding(0, ui.dp(4), 0, 0);
    titles.addView(description);
    header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
    body.addView(header);

    // 状态卡：放在最上面，写明禁用的影响。
    LinearLayout state = new LinearLayout(ctx);
    state.setTag("skills.state");
    state.setGravity(Gravity.CENTER_VERTICAL);
    state.setBackgroundResource(R.drawable.bg_shell_status);
    state.setPadding(ui.dp(14), ui.dp(12), ui.dp(10), ui.dp(12));
    LinearLayout stateCopy = ui.column();
    TextView stateTitle =
        ui.text(entry.enabled ? t("已启用", "On") : t("已禁用", "Off"), 14, R.color.shell_text);
    stateTitle.setTypeface(null, android.graphics.Typeface.BOLD);
    stateCopy.addView(stateTitle);
    TextView stateText =
        ui.text(
            entry.enabled
                ? t("DSH 会在后续请求中发现并按需读取此技能。", "DSH discovers this skill and loads it as needed.")
                : t(
                    "DSH 不会发现或调用此技能；文件和同目录资料原样保留，随时可重新启用。",
                    "DSH will not discover or invoke this skill. Its files stay in place and it can be turned back on at any time."),
            12,
            R.color.shell_text_secondary);
    stateText.setLineSpacing(ui.dp(2), 1f);
    stateText.setPadding(0, ui.dp(3), 0, 0);
    stateCopy.addView(stateText);
    state.addView(stateCopy, new LinearLayout.LayoutParams(0, -2, 1f));
    MaterialSwitch toggle = new MaterialSwitch(ctx);
    toggle.setChecked(entry.enabled);
    toggle.setEnabled(!busy());
    toggle.setContentDescription(tf("启用 %s", "Enable %s", entry.document.name));
    toggle.setOnCheckedChangeListener(
        (button, checked) -> {
          if (dismiss != null) dismiss.run();
          toggle(entry, checked);
        });
    state.addView(toggle);
    LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-1, -2);
    stateParams.topMargin = ui.dp(14);
    body.addView(state, stateParams);

    // 调用方式与位置。
    body.addView(detailLabel(t("调用方式", "Invocation")));
    LinearLayout facts = ui.column();
    facts.setBackgroundResource(R.drawable.bg_shell_card);
    facts.setPadding(ui.dp(12), ui.dp(2), ui.dp(12), ui.dp(2));
    fact(
        ctx,
        facts,
        t("用户命令", "User command"),
        entry.document.userInvocable ? "/" + entry.document.name : t("不允许", "Not allowed"),
        entry.document.userInvocable);
    fact(
        ctx,
        facts,
        t("模型自主调用", "Model invocation"),
        entry.document.modelInvocable ? t("允许", "Allowed") : t("不允许", "Not allowed"),
        false);
    fact(ctx, facts, t("位置", "Location"), entry.guestPath(), true);
    body.addView(facts, new LinearLayout.LayoutParams(-1, -2));

    // SKILL.md 原文：默认折叠到前几行。
    LinearLayout sourceHead = new LinearLayout(ctx);
    sourceHead.setGravity(Gravity.CENTER_VERTICAL);
    sourceHead.addView(
        detailLabel(
            entry.relative.endsWith(SkillLifecycle.DISABLED_SUFFIX)
                ? SkillLifecycle.DISABLED_DOCUMENT
                : SkillLifecycle.DOCUMENT),
        new LinearLayout.LayoutParams(0, -2, 1f));
    String[] lines = entry.document.text.split("\n", -1);
    boolean long_ = lines.length > SOURCE_PREVIEW_LINES;
    TextView source = ui.text(entry.document.text, 12, R.color.shell_text);
    source.setTypeface(android.graphics.Typeface.MONOSPACE);
    source.setTextIsSelectable(true);
    source.setLineSpacing(ui.dp(3), 1f);
    source.setBackgroundResource(R.drawable.bg_shell_status);
    source.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
    source.setTextDirection(View.TEXT_DIRECTION_LTR);
    if (long_) {
      source.setMaxLines(SOURCE_PREVIEW_LINES);
      source.setEllipsize(android.text.TextUtils.TruncateAt.END);
      Button more = textButton(ctx, t("展开全文", "Show all"));
      more.setOnClickListener(
          v -> {
            boolean collapsed = source.getMaxLines() == SOURCE_PREVIEW_LINES;
            source.setMaxLines(collapsed ? Integer.MAX_VALUE : SOURCE_PREVIEW_LINES);
            more.setText(collapsed ? t("收起", "Collapse") : t("展开全文", "Show all"));
          });
      sourceHead.addView(more);
    }
    body.addView(sourceHead, new LinearLayout.LayoutParams(-1, -2));
    body.addView(source, new LinearLayout.LayoutParams(-1, -2));

    // 操作：删除（危险，最轻）· 复制命令 · 启用 / 禁用（主）。
    LinearLayout actions = new LinearLayout(ctx);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    Button delete = dangerButton(ctx, t("删除", "Delete"));
    delete.setTag("skills.delete");
    delete.setEnabled(!busy());
    delete.setPadding(ui.dp(16), 0, ui.dp(16), 0);
    delete.setOnClickListener(
        v -> {
          if (dismiss != null) dismiss.run();
          confirmDelete(entry);
        });
    actions.addView(delete, new LinearLayout.LayoutParams(-2, ui.dp(46)));
    if (entry.document.userInvocable) {
      Button copy = plainButton(ctx, t("复制命令", "Copy command"), false);
      copy.setPadding(ui.dp(10), 0, ui.dp(10), 0);
      copy.setEllipsize(android.text.TextUtils.TruncateAt.END);
      copy.setOnClickListener(
          v -> {
            ClipboardManager clipboard =
                (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
              clipboard.setPrimaryClip(ClipData.newPlainText("skill", "/" + entry.document.name));
              Toast.makeText(ctx, UiText.text("已复制"), Toast.LENGTH_SHORT).show();
            }
          });
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ui.dp(46), 1f);
      p.setMarginStart(ui.dp(10));
      actions.addView(copy, p);
    }
    Button flip =
        plainButton(ctx, entry.enabled ? t("禁用", "Turn off") : t("启用", "Turn on"), !entry.enabled);
    flip.setTag("skills.flip");
    flip.setEnabled(!busy());
    flip.setPadding(ui.dp(10), 0, ui.dp(10), 0);
    flip.setOnClickListener(
        v -> {
          if (dismiss != null) dismiss.run();
          toggle(entry, !entry.enabled);
        });
    LinearLayout.LayoutParams flipParams = new LinearLayout.LayoutParams(0, ui.dp(46), 1f);
    flipParams.setMarginStart(ui.dp(10));
    actions.addView(flip, flipParams);
    LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
    actionParams.topMargin = ui.dp(16);
    body.addView(actions, actionParams);
  }

  private TextView detailLabel(String label) {
    TextView view = ui.text(label, 12, R.color.shell_text_secondary);
    view.setTypeface(null, android.graphics.Typeface.BOLD);
    view.setPadding(ui.dp(2), ui.dp(16), 0, ui.dp(6));
    return view;
  }

  private void fact(Context ctx, LinearLayout card, String label, String value, boolean mono) {
    if (card.getChildCount() > 0) {
      View line = new View(ctx);
      line.setBackgroundColor(ctx.getColor(R.color.shell_line));
      card.addView(line, new LinearLayout.LayoutParams(-1, 1));
    }
    LinearLayout row = new LinearLayout(ctx);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setMinimumHeight(ui.dp(42));
    row.addView(
        ui.text(label, 13, R.color.shell_text_secondary), new LinearLayout.LayoutParams(-2, -2));
    TextView content = ui.text(value, mono ? 12 : 13, R.color.shell_text);
    if (mono) {
      content.setTypeface(android.graphics.Typeface.MONOSPACE);
      content.setTextDirection(View.TEXT_DIRECTION_LTR);
    }
    content.setGravity(Gravity.END);
    content.setTextIsSelectable(mono);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
    p.setMarginStart(ui.dp(12));
    row.addView(content, p);
    card.addView(row);
  }

  private void showSheet(SkillImports.Entry entry) {
    Context ctx = requireContext();
    if (sheet != null) sheet.dismiss();
    CardPage page = new CardPage(ctx, "", "", true);
    androidx.appcompat.app.AlertDialog[] holder = new androidx.appcompat.app.AlertDialog[1];
    detail(
        ctx,
        page.content,
        entry,
        () -> {
          if (holder[0] != null) holder[0].dismiss();
        });
    var dialog = CardSheet.create(ctx, page, SheetGeometry.Size.M);
    holder[0] = dialog;
    page.footer.setVisibility(View.GONE);
    dialog.setOnDismissListener(
        d -> {
          if (sheet == dialog) sheet = null;
        });
    sheet = dialog;
    CardSheet.show(dialog, ctx);
  }

  // ---- 弹窗 ----

  /** 删除确认：先在后台列出将移走的文件，再弹出；读不出清单时仍可删除，只是不列文件。 */
  private void confirmDelete(SkillImports.Entry entry) {
    if (busy()) return;
    Context app = requireContext().getApplicationContext();
    View root = getView();
    new Thread(
            () -> {
              SkillLibrary.Preview preview = null;
              try (RuntimeTasks task = RuntimeTasks.begin("技能读取")) {
                preview = library(app).preview(entry, new BackupControl(null));
              } catch (Exception unavailable) {
                // 清单只用于说明；删除本身仍会重新核对条目。
              }
              SkillLibrary.Preview shown = preview;
              if (root != null)
                root.post(
                    () -> {
                      if (isAdded() && getView() == root) showDeleteDialog(entry, shown);
                    });
            },
            "dsha-skills-preview")
        .start();
  }

  private void showDeleteDialog(SkillImports.Entry entry, @Nullable SkillLibrary.Preview preview) {
    Context ctx = requireContext();
    int days = retentionDays(ctx);
    CardPage page =
        new CardPage(
            ctx,
            tf("删除 %s？", "Delete %s?", entry.document.name),
            tf(
                "整个技能移到「最近删除」，DSH 不再读取；%d 天内可恢复，之后自动彻底删除。",
                "The whole skill moves to Recently deleted and DSH stops reading it. You can restore it for %d days; after that it is removed permanently.",
                days),
            true);
    if (preview != null && !preview.paths.isEmpty()) {
      LinearLayout list = ui.column();
      list.setTag("skills.delete.files");
      list.setBackgroundResource(R.drawable.bg_shell_status);
      list.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
      int shown = Math.min(8, preview.paths.size());
      for (int i = 0; i < shown; i++) {
        TextView path = ui.text(preview.paths.get(i), 12, R.color.shell_text);
        path.setTypeface(android.graphics.Typeface.MONOSPACE);
        path.setTextDirection(View.TEXT_DIRECTION_LTR);
        path.setSingleLine(true);
        path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        path.setPadding(0, ui.dp(3), 0, ui.dp(3));
        list.addView(path);
      }
      int more = preview.paths.size() - shown;
      TextView summary =
          ui.text(
              (more > 0 || preview.truncated
                      ? tf(
                          "另有 %s 项 · ",
                          "%s more · ", preview.truncated ? more + "+" : String.valueOf(more))
                      : "")
                  + tf("共 %d 个文件，%s", "%d files, %s", preview.paths.size(), size(preview.bytes)),
              11,
              R.color.shell_text_muted);
      summary.setPadding(0, ui.dp(6), 0, ui.dp(2));
      list.addView(summary);
      page.content.addView(list, new LinearLayout.LayoutParams(-1, -2));
    }
    var dialog = CardSheet.create(ctx, page, SheetGeometry.Size.M);
    if (entry.enabled) {
      Button off = textButton(ctx, t("只想暂时不用？改为禁用", "Only pausing it? Turn it off instead"));
      off.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      off.setPadding(0, 0, 0, 0);
      off.setOnClickListener(
          v -> {
            dialog.dismiss();
            toggle(entry, false);
          });
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
      p.topMargin = ui.dp(6);
      page.content.addView(off, p);
    }
    Button trash =
        page.button(
            page.footer,
            t("移到最近删除", "Move to Recently deleted"),
            true,
            () -> {
              dialog.dismiss();
              trash(entry);
            });
    trash.setTag("skills.delete.trash");
    Button purge = dangerButton(ctx, t("彻底删除（不可恢复）", "Delete permanently (cannot undo)"));
    purge.setTag("skills.delete.purge");
    purge.setOnClickListener(
        v -> {
          dialog.dismiss();
          deletePermanently(entry);
        });
    LinearLayout.LayoutParams purgeParams = new LinearLayout.LayoutParams(-1, -2);
    purgeParams.topMargin = ui.dp(10);
    page.footer.addView(purge, purgeParams);
    page.button(page.footer, t("取消", "Cancel"), false, dialog::dismiss);
    CardSheet.show(dialog, ctx);
  }

  private void confirmPurge(SkillLibrary.Item item) {
    if (busy()) return;
    Context ctx = requireContext();
    CardPage page =
        new CardPage(
            ctx,
            tf("彻底删除 %s？", "Permanently delete %s?", item.meta.name),
            t(
                "技能目录及其中的脚本、资料会被立即删除，无法恢复。删除后可以重新导入同名技能。",
                "The skill folder, including its scripts and resources, is removed now and cannot be restored. A skill with the same name can be imported afterwards."),
            true);
    var dialog = CardSheet.create(ctx, page, SheetGeometry.Size.S);
    page.button(page.footer, t("取消", "Cancel"), false, dialog::dismiss);
    Button purge = dangerButton(ctx, t("彻底删除", "Delete permanently"));
    purge.setOnClickListener(
        v -> {
          dialog.dismiss();
          purge(item);
        });
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = ui.dp(10);
    page.footer.addView(purge, p);
    CardSheet.show(dialog, ctx);
  }

  private void confirmEmptyTrash(int count) {
    if (busy()) return;
    Context ctx = requireContext();
    CardPage page =
        new CardPage(
            ctx,
            t("清空最近删除？", "Empty Recently deleted?"),
            tf(
                "%d 个技能会被立即彻底删除，无法恢复。",
                "%d skills will be permanently deleted now. This cannot be undone.", count),
            true);
    var dialog = CardSheet.create(ctx, page, SheetGeometry.Size.S);
    page.button(page.footer, t("取消", "Cancel"), false, dialog::dismiss);
    Button purge = dangerButton(ctx, t("全部彻底删除", "Delete all permanently"));
    purge.setOnClickListener(
        v -> {
          dialog.dismiss();
          emptyTrash();
        });
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = ui.dp(10);
    page.footer.addView(purge, p);
    CardSheet.show(dialog, ctx);
  }

  /** 保留天数：7 / 30 / 90 预设或自定义 1–365。缩短后若有项立即到期，先提示数量，再点一次才保存。 */
  private void showRetention() {
    Context ctx = requireContext();
    int current = retentionDays(ctx);
    CardPage page =
        new CardPage(
            ctx,
            t("最近删除保留时长", "Keep recently deleted"),
            t(
                "删除的技能在「最近删除」中保留的天数，到期后下次打开本页时彻底删除。",
                "How long deleted skills stay in Recently deleted. Expired items are removed the next time this page opens."),
            true);
    RadioGroup group = new RadioGroup(ctx);
    group.setOrientation(RadioGroup.VERTICAL);
    int[] presets = {7, SkillLifecycle.DEFAULT_RETENTION_DAYS, 90};
    RadioButton custom = new RadioButton(ctx);
    boolean matched = false;
    for (int days : presets) {
      RadioButton option = new RadioButton(ctx);
      option.setId(View.generateViewId());
      option.setTag(days);
      option.setTextSize(14);
      option.setTextColor(ctx.getColor(R.color.shell_text));
      option.setMinHeight(ui.dp(46));
      option.setText(
          days == SkillLifecycle.DEFAULT_RETENTION_DAYS
              ? tf("%d 天（默认）", "%d days (default)", days)
              : tf("%d 天", "%d days", days));
      group.addView(option);
      if (days == current) {
        option.setChecked(true);
        matched = true;
      }
    }
    custom.setId(View.generateViewId());
    custom.setTextSize(14);
    custom.setTextColor(ctx.getColor(R.color.shell_text));
    custom.setMinHeight(ui.dp(46));
    custom.setText(t("自定义", "Custom"));
    group.addView(custom);
    page.content.addView(group);
    EditText input = new androidx.appcompat.widget.AppCompatEditText(ctx);
    input.setTag("skills.retention.input");
    input.setInputType(InputType.TYPE_CLASS_NUMBER);
    input.setSingleLine(true);
    input.setHint(
        tf(
            "%d–%d 天",
            "%d–%d days", SkillLifecycle.MIN_RETENTION_DAYS, SkillLifecycle.MAX_RETENTION_DAYS));
    input.setBackgroundResource(R.drawable.bg_shell_input);
    input.setTextColor(ctx.getColor(R.color.shell_text));
    input.setHintTextColor(ctx.getColor(R.color.shell_text_muted));
    input.setPadding(ui.dp(14), 0, ui.dp(14), 0);
    input.setMinHeight(ui.dp(46));
    if (!matched) {
      custom.setChecked(true);
      input.setText(String.valueOf(current));
    }
    input.setEnabled(custom.isChecked());
    LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(-1, ui.dp(46));
    inputParams.setMarginStart(ui.dp(32));
    page.content.addView(input, inputParams);
    TextView error = ui.text("", 12, R.color.shell_warn);
    error.setLineSpacing(ui.dp(2), 1f);
    error.setPadding(0, ui.dp(8), 0, 0);
    error.setVisibility(View.GONE);
    error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    page.content.addView(error);
    group.setOnCheckedChangeListener(
        (g, id) -> {
          input.setEnabled(id == custom.getId());
          if (id == custom.getId()) input.requestFocus();
          error.setVisibility(View.GONE);
        });
    var dialog = CardSheet.create(ctx, page, SheetGeometry.Size.M);
    page.button(page.footer, t("取消", "Cancel"), false, dialog::dismiss);
    int[] confirmed = {-1};
    Button save = page.button(page.footer, t("保存", "Save"), true, () -> {});
    save.setOnClickListener(
        v -> {
          int days;
          RadioButton chosen = group.findViewById(group.getCheckedRadioButtonId());
          if (chosen == null) return;
          if (chosen == custom) {
            days = SkillLifecycle.parseRetention(String.valueOf(input.getText()));
            if (days < 0) {
              error.setText(
                  tf(
                      "请输入 %d 到 %d 之间的整数天数。",
                      "Enter a whole number of days from %d to %d.",
                      SkillLifecycle.MIN_RETENTION_DAYS,
                      SkillLifecycle.MAX_RETENTION_DAYS));
              error.setVisibility(View.VISIBLE);
              return;
            }
          } else days = (Integer) chosen.getTag();
          int due = dueAfter(days);
          if (due > 0 && confirmed[0] != days) {
            confirmed[0] = days;
            error.setText(
                tf(
                    "改为 %d 天后，最近删除中有 %d 个技能已过期，保存后会立即彻底删除。再点一次「保存」确认。",
                    "With %d days, %d skills in Recently deleted have already expired and will be permanently deleted right after saving. Tap Save again to confirm.",
                    days,
                    due));
            error.setVisibility(View.VISIBLE);
            return;
          }
          dialog.dismiss();
          setRetention(ctx, days);
        });
    CardSheet.show(dialog, ctx);
  }

  private int dueAfter(int days) {
    SkillImports.Catalogue catalogue = model.catalogue.getValue();
    if (catalogue == null) return 0;
    long now = System.currentTimeMillis();
    int due = 0;
    for (SkillLibrary.Item item : catalogue.trash)
      if (SkillLifecycle.expired(item.meta.deletedAt, now, days)) due++;
    return due;
  }

  private static String size(long bytes) {
    if (bytes < 1024) return bytes + " B";
    if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
    return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024));
  }

  // ---- 结果条 ----

  private void renderResult(Result result) {
    if (status == null) return;
    String code = result.code;
    if ("READY".equals(code)) {
      statusRow.setVisibility(View.GONE);
      progressText.setText("");
      return;
    }
    String progress = progress(code);
    if (progress != null) {
      progressText.setText(progress);
      statusRow.setVisibility(View.GONE);
      return;
    }
    boolean ok =
        switch (code) {
          case "IMPORTED",
                  "ENABLED",
                  "DISABLED",
                  "TRASHED",
                  "RESTORED",
                  "PURGED",
                  "DELETED",
                  "EMPTIED",
                  "RETENTION" ->
              true;
          default -> false;
        };
    boolean cancelled = "CANCELLED".equals(code);
    int tone =
        ok ? R.color.shell_good : cancelled ? R.color.shell_text_secondary : R.color.shell_warn;
    GradientDrawable shape = new GradientDrawable();
    shape.setCornerRadius(ui.dp(12));
    int color = requireContext().getColor(tone);
    shape.setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x1F));
    shape.setStroke(ui.dp(1), androidx.core.graphics.ColorUtils.setAlphaComponent(color, 0x66));
    statusRow.setBackground(shape);
    status.setTextColor(
        requireContext().getColor(cancelled ? R.color.shell_text_secondary : R.color.shell_text));
    boolean wasHidden = statusRow.getVisibility() != View.VISIBLE;
    statusRow.setVisibility(View.VISIBLE);
    if (ok || cancelled) {
      UiMotion.crossFadeText(status, resultText(result));
    } else {
      // 失败结果：结果条先给一句结论，完整原因/影响/下一步点开 ⓘ（审计 F-07 + 基准 ⑬）。
      InfoHint.bindOnSurface(status, null, "", resultText(result));
    }
    undoButton.setVisibility(
        "DISABLED".equals(code) || "TRASHED".equals(code) ? View.VISIBLE : View.GONE);
    if (wasHidden) UiMotion.reveal(statusRow);
  }

  @Nullable
  private String progress(String code) {
    return switch (code) {
      case "READING" -> t("正在读取用户技能…", "Reading user skills…");
      case "IMPORTING" -> t("正在读取并检查 SKILL.md…", "Reading and checking SKILL.md…");
      case "WORKING" -> t("正在更新技能…", "Updating skills…");
      default -> null;
    };
  }

  /** 撤销最近一次禁用（重新启用）或删除（从最近删除恢复）。 */
  private void undo() {
    Result last = model.result.getValue();
    SkillImports.Catalogue catalogue = model.catalogue.getValue();
    if (last == null || catalogue == null || busy()) return;
    if ("DISABLED".equals(last.code)) {
      for (SkillImports.Entry entry : catalogue.entries)
        if (entry.document.name.equals(last.name) && !entry.enabled) toggle(entry, true);
    } else if ("TRASHED".equals(last.code)) {
      for (SkillLibrary.Item item : catalogue.trash) if (item.slot.equals(last.path)) restore(item);
    }
  }

  // ---- 写操作 ----

  private void toggle(SkillImports.Entry entry, boolean enabled) {
    if (entry.enabled == enabled) return;
    mutate(
        app -> {
          library(app).setEnabled(entry, enabled, new BackupControl(null));
          return new Result(enabled ? "ENABLED" : "DISABLED", entry.document.name, "");
        });
  }

  private void trash(SkillImports.Entry entry) {
    mutate(
        app -> {
          String slot =
              library(app).trash(entry, System.currentTimeMillis(), new BackupControl(null));
          return new Result("TRASHED", entry.document.name, slot);
        });
  }

  private void deletePermanently(SkillImports.Entry entry) {
    mutate(
        app -> {
          library(app)
              .deletePermanently(entry, System.currentTimeMillis(), new BackupControl(null));
          return new Result("DELETED", entry.document.name, "");
        });
  }

  private void restore(SkillLibrary.Item item) {
    mutate(
        app -> {
          var restored = library(app).restore(item.slot, new BackupControl(null));
          return new Result("RESTORED", restored.document.name, "", restored.enabled ? 1 : 0);
        });
  }

  private void purge(SkillLibrary.Item item) {
    mutate(
        app -> {
          library(app).purge(item.slot, new BackupControl(null));
          return new Result("PURGED", item.meta.name, "");
        });
  }

  private void emptyTrash() {
    SkillImports.Catalogue catalogue = model.catalogue.getValue();
    if (catalogue == null) return;
    List<String> slots = new ArrayList<>();
    for (SkillLibrary.Item item : catalogue.trash) slots.add(item.slot);
    mutate(
        app -> {
          SkillLibrary library = library(app);
          int done = 0;
          for (String slot : slots) {
            library.purge(slot, new BackupControl(null));
            done++;
          }
          return new Result("EMPTIED", "", "", done);
        });
  }

  private void setRetention(Context ctx, int days) {
    prefs(ctx).edit().putInt(KEY_RETENTION, SkillLifecycle.retention(days)).apply();
    // 立即按新天数清理一次并刷新列表。
    model.result.setValue(new Result("RETENTION", "", "", days));
    reload(true);
  }

  /** 写操作：与导入同一套门禁与屏障，完成后刷新目录；失败只报告，不重放。 */
  private void mutate(Work work) {
    if (!model.working.compareAndSet(false, true)) return;
    Context app = requireContext().getApplicationContext();
    Model owner = model;
    synchronized (owner) {
      owner.control = null;
      owner.committing = true;
    }
    owner.busy.setValue(true);
    owner.result.setValue(new Result("WORKING", "", ""));
    new Thread(
            () -> {
              Result outcome;
              try {
                EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("技能管理");
                if (lease == null) throw new IOException("SKILL_BUSY");
                try (lease) {
                  outcome =
                      lease.run(
                          () -> {
                            if (MaintenanceCoordinator.pending(app.getFilesDir()))
                              throw new IOException("SKILL_BUSY");
                            synchronized (MaintenanceCoordinator.archiveLock()) {
                              try (RuntimeTasks task = RuntimeTasks.begin("技能管理")) {
                                return work.run(app);
                              }
                            }
                          });
                }
              } catch (Exception failure) {
                String code = failure.getMessage();
                outcome = new Result(code == null ? "SKILL_IO" : code, "", "");
              }
              try {
                owner.catalogue.postValue(catalogue(app, new BackupControl(null)));
              } catch (Exception refreshFailed) {
                // 写操作已有结论；只读刷新失败不改写结论，也不重做操作。
              }
              owner.result.postValue(outcome);
              synchronized (owner) {
                owner.committing = false;
              }
              owner.working.set(false);
              owner.busy.postValue(false);
            },
            "dsha-skills")
        .start();
  }

  private void reload(boolean keepResult) {
    if (!model.working.compareAndSet(false, true)) return;
    Context app = requireContext().getApplicationContext();
    Model owner = model;
    BackupControl control = new BackupControl(null);
    synchronized (owner) {
      owner.control = control;
      owner.committing = false;
    }
    owner.busy.setValue(true);
    Result previous = owner.result.getValue();
    if (!keepResult) owner.result.setValue(new Result("READING", "", ""));
    new Thread(
            () -> {
              try {
                purgeExpired(app);
                owner.catalogue.postValue(catalogue(app, control));
                owner.result.postValue(
                    keepResult && previous != null ? previous : new Result("READY", "", ""));
              } catch (Exception failure) {
                String code =
                    failure instanceof java.io.InterruptedIOException || control.isCancelled()
                        ? "CANCELLED"
                        : failure.getMessage();
                owner.result.postValue(new Result(code == null ? "SKILL_IO" : code, "", ""));
              } finally {
                synchronized (owner) {
                  owner.control = null;
                  owner.committing = false;
                }
                owner.working.set(false);
                owner.busy.postValue(false);
              }
            },
            "dsha-skills")
        .start();
  }

  /** 到期清理：拿不到门禁（安装 / 备份 / 维护进行中）时跳过，下次打开再清。 */
  private static void purgeExpired(Context app) {
    EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("技能清理");
    if (lease == null) return;
    try (lease) {
      lease.run(
          () -> {
            if (MaintenanceCoordinator.pending(app.getFilesDir())) return 0;
            synchronized (MaintenanceCoordinator.archiveLock()) {
              try (RuntimeTasks task = RuntimeTasks.begin("技能清理")) {
                return library(app)
                    .purgeExpired(
                        System.currentTimeMillis(), retentionDays(app), new BackupControl(null));
              }
            }
          });
    } catch (Exception skipped) {
      // 清理失败不影响列表；条目保留到下次。
    }
  }

  /** 已安装 + 最近删除；最近删除读不出时只显示已安装。 */
  private static SkillImports.Catalogue catalogue(Context app, BackupControl control)
      throws IOException {
    SkillImports.Catalogue installed;
    try (RuntimeTasks task = RuntimeTasks.begin("技能读取")) {
      installed = repository(app).list(control);
      try {
        return installed.withTrash(library(app).list(control));
      } catch (java.io.InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException trashUnreadable) {
        return installed;
      }
    }
  }

  static SharedPreferences prefs(Context context) {
    return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  static int retentionDays(Context context) {
    return SkillLifecycle.retention(
        prefs(context).getInt(KEY_RETENTION, SkillLifecycle.DEFAULT_RETENTION_DAYS));
  }

  // ---- 导入 ----

  private void start(@Nullable Uri uri) {
    if (uri == null) {
      reload(false);
      return;
    }
    if (!model.working.compareAndSet(false, true)) return;
    if (!"content".equals(uri.getScheme())) {
      model.working.set(false);
      model.result.setValue(new Result("SKILL_URI", "", ""));
      return;
    }
    Context app = requireContext().getApplicationContext();
    Model owner = model;
    BackupControl control = new BackupControl(null);
    synchronized (owner) {
      owner.control = control;
      owner.committing = false;
    }
    owner.busy.setValue(true);
    owner.result.setValue(new Result("IMPORTING", "", ""));
    new Thread(
            () -> {
              try {
                byte[] bytes = read(app, uri, control);
                SkillDocument.parse(bytes);
                synchronized (owner) {
                  control.check();
                  owner.committing = true;
                }
                owner.busy.postValue(true);
                EnvironmentTaskGate.Lease lease = EnvironmentTaskGate.tryAcquire("技能导入");
                if (lease == null) throw new IOException("SKILL_BUSY");
                SkillImports.Entry installed;
                try (lease) {
                  installed =
                      lease.run(
                          () -> {
                            if (MaintenanceCoordinator.pending(app.getFilesDir()))
                              throw new IOException("SKILL_BUSY");
                            synchronized (MaintenanceCoordinator.archiveLock()) {
                              try (RuntimeTasks task = RuntimeTasks.begin("技能导入")) {
                                return repository(app)
                                    .install("SKILL.md", bytes, new BackupControl(null));
                              }
                            }
                          });
                }
                owner.result.postValue(
                    new Result("IMPORTED", installed.document.name, installed.guestPath()));
                try {
                  owner.catalogue.postValue(catalogue(app, new BackupControl(null)));
                } catch (IOException | RuntimeException refreshFailed) {
                  // 发布已完成；只读刷新失败不能把成功导入报告成未完成或重复执行导入。
                }
              } catch (Exception failure) {
                String code =
                    failure instanceof java.io.InterruptedIOException || control.isCancelled()
                        ? "CANCELLED"
                        : failure.getMessage();
                owner.result.postValue(new Result(code == null ? "SKILL_IO" : code, "", ""));
              } finally {
                synchronized (owner) {
                  owner.control = null;
                  owner.committing = false;
                }
                owner.working.set(false);
                owner.busy.postValue(false);
              }
            },
            "dsha-skills")
        .start();
  }

  private static SkillImports repository(Context context) throws IOException {
    return new SkillImports(
        new AndroidBackupFileSystem(), context.getFilesDir().getCanonicalFile());
  }

  private static SkillLibrary library(Context context) throws IOException {
    return new SkillLibrary(
        new AndroidBackupFileSystem(), context.getFilesDir().getCanonicalFile());
  }

  private static byte[] read(Context context, Uri uri, BackupControl control) throws IOException {
    String filename;
    try (Cursor cursor =
        DocumentStreams.query(
            context.getContentResolver(),
            uri,
            new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
            control)) {
      if (!cursor.moveToFirst()) throw new IOException("SKILL_FILENAME");
      int name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
      if (name < 0 || cursor.isNull(name)) throw new IOException("SKILL_FILENAME");
      filename = cursor.getString(name);
      SkillDocument.filename(filename);
      int size = cursor.getColumnIndex(OpenableColumns.SIZE);
      if (size >= 0 && !cursor.isNull(size) && cursor.getLong(size) > SkillDocument.MAX_BYTES)
        throw new IOException("SKILL_SIZE");
    }
    try (InputStream input = DocumentStreams.input(context.getContentResolver(), uri, control);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        control.check();
        if (output.size() + count > SkillDocument.MAX_BYTES) throw new IOException("SKILL_SIZE");
        output.write(buffer, 0, count);
      }
      control.check();
      return output.toByteArray();
    }
  }

  private String resultText(Result result) {
    return switch (result.code) {
      case "IMPORTED" ->
          UiText.format(
              "已导入技能：%s\n%s\nDSH 会在后续请求中发现此技能；若目录尚未刷新，可重启 Web。", result.name, result.path);
      case "ENABLED" ->
          tf("已启用 %s，下次请求生效。", "%s is on. It takes effect from the next request.", result.name);
      case "DISABLED" ->
          tf("已禁用 %s，下次请求生效。", "%s is off. It takes effect from the next request.", result.name);
      case "TRASHED" ->
          tf(
              "已将 %s 移到最近删除，%d 天后彻底删除。",
              "Moved %s to Recently deleted. It is removed permanently in %d days.",
              result.name,
              retentionDays(requireContext()));
      case "RESTORED" ->
          result.count == 1
              ? tf("已恢复 %s。", "Restored %s.", result.name)
              : tf("已恢复 %s，保持禁用状态。", "Restored %s. It stays off.", result.name);
      case "PURGED", "DELETED" ->
          tf(
              "已彻底删除 %s，可重新导入同名技能。",
              "Permanently deleted %s. The name can be used again.", result.name);
      case "EMPTIED" -> tf("已彻底删除 %d 个技能。", "Permanently deleted %d skills.", result.count);
      case "RETENTION" ->
          tf("最近删除改为保留 %d 天。", "Recently deleted now keeps items for %d days.", result.count);
      case "CANCELLED" -> t("已取消读取，未导入技能。", "Reading cancelled. No skill was imported.");
      case "SKILL_FILENAME", "SKILL_URI" ->
          t(
              "请选择名为 SKILL.md 的文件，不能导入目录或压缩包。",
              "Choose a file named SKILL.md. Folders and archives cannot be imported.");
      case "SKILL_SIZE" ->
          t("SKILL.md 必须为非空文件，且不超过 1 MiB。", "SKILL.md must be non-empty and at most 1 MiB.");
      case "SKILL_ENCODING" ->
          t("SKILL.md 必须是有效 UTF-8 文本。", "SKILL.md must contain valid UTF-8 text.");
      case "SKILL_FRONTMATTER", "SKILL_REQUIRED_FIELDS", "SKILL_NAME", "SKILL_INVOCATION" ->
          t(
              "技能格式无效。文件需以 --- YAML 开头，包含小写短横线 name 和非空 description；调用开关使用 disable-model-invocation / user-invocable。未导入文件。",
              "Invalid skill format. Start with --- YAML frontmatter, include a lowercase kebab-case name and non-empty description, and use disable-model-invocation / user-invocable for invocation controls. No file was imported.");
      case "SKILL_EXISTS" ->
          t(
              "已有同名技能或目录，未覆盖原件。请先在源文件中使用不同的 name。",
              "A skill or folder with this name already exists. The original was retained. Use a different name in the source file first.");
      case "SKILL_IN_TRASH" ->
          t(
              "最近删除中有同名技能。请先恢复或彻底删除它，再导入。",
              "A skill with this name is in Recently deleted. Restore or permanently delete it before importing.");
      case "SKILL_CHANGED" ->
          t(
              "技能文件已在别处被修改，本次操作未执行。列表已刷新，请确认后重试。",
              "The skill files changed elsewhere, so nothing was done. The list has been refreshed; check it and try again.");
      case "SKILL_STATE_CONFLICT" ->
          t(
              "技能目录里同时有 SKILL.md 和 SKILL.md.disabled，未覆盖任何一份。请用文件入口保留其中一份后再切换。",
              "The skill folder has both SKILL.md and SKILL.md.disabled. Neither was overwritten; keep only one through Files, then switch again.");
      case "SKILL_RESTORE_EXISTS" ->
          t(
              "原位置已有同名技能或文件，未覆盖。请先处理同名项再恢复。",
              "Something with the same name already exists at the original location and was not overwritten. Deal with it first, then restore.");
      case "SKILL_PURGE_PARTIAL" ->
          t(
              "技能已移出技能目录，但未能全部清除，剩余内容留在最近删除中，可再次彻底删除。",
              "The skill was moved out of the skills folder but could not be fully removed. What remains is in Recently deleted; delete it permanently again.");
      case "SKILL_TRASH_DIRECTORY",
              "SKILL_TRASH_ITEM",
              "SKILL_TRASH_META",
              "RETIRED_DIRECTORY_NOT_OWNED" ->
          t(
              "最近删除中的内容状态异常，未做改动。请刷新后重试。",
              "Recently deleted is in an unexpected state, so nothing was changed. Refresh and try again.");
      case "SKILL_BUSY" ->
          t(
              "有安装、备份、恢复或维护任务正在进行，请完成后重试。",
              "An install, backup, restore or maintenance operation is active. Retry after it finishes.");
      case "SKILL_HOME_UNAVAILABLE", "PARENT_LINK_OR_MISSING", "PARENT_LINK" ->
          t(
              "当前 DSH 用户目录不可安全读取，请先完成环境安装或修复。未创建替代目录。",
              "The current DSH user folder cannot be read safely. Complete environment setup or repair first. No replacement folder was created.");
      case "SKILL_DIRECTORY", "SKILL_ENTRY_LIMIT" ->
          t(
              "技能目录不是可安全读取的普通目录，或条目超过 512 项。原件已保留。",
              "The skills folder is not a safely readable regular directory, or it has more than 512 entries. Originals were retained.");
      default ->
          t(
              "技能操作未完成。请检查文件授权、可用空间和目录状态；已有技能未覆盖。",
              "The skill operation did not finish. Check file access, free space and the folder state. Existing skills were retained.");
    };
  }
}
