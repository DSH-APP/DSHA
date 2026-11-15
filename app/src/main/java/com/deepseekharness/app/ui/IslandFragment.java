package com.deepseekharness.app.ui;

import android.content.res.ColorStateList;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.AgentTaskNotifications;
import com.deepseekharness.app.IslandSupport;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.AgentTaskState;
import com.deepseekharness.app.util.IslandPage;
import com.deepseekharness.app.util.IslandText;
import com.deepseekharness.app.util.UiText;

import java.util.List;

/**
 * 「任务状态上岛」设置页（方案 B，见 docs/design/island-status-20261102）。
 *
 * <p>页面顶部示意“回到桌面后会看到什么”：代表任务、胶囊短文本与保留期全部沿用通知侧的
 * {@link IslandText} 选举，页面只读不写。两个开关仍只落到 {@link ConfigStore}；能力状态每次
 * 恢复页面时直接读系统（{@link IslandSupport#state}），不缓存。
 */
public class IslandFragment extends Fragment {
  /** 预览刷新间隔：只在页面可见时运行，内容只读快照，不触发任何通知。 */
  private static final long TICK_MS = 1_000;

  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable tick =
      new Runnable() {
        @Override
        public void run() {
          renderLive();
          main.postDelayed(this, TICK_MS);
        }
      };

  private View root;
  private ConfigStore config;
  private IslandPage.Status status = IslandPage.Status.UNDETERMINED;
  private final TextView[][] levelViews = new TextView[IslandPage.LEVELS.length][];
  private final View[] levelRows = new View[IslandPage.LEVELS.length];

  @Nullable
  @Override
  public View onCreateView(
      @NonNull LayoutInflater inflater,
      @Nullable ViewGroup container,
      @Nullable Bundle savedInstanceState) {
    root = inflater.inflate(R.layout.fragment_island, container, false);
    PressFeedback.attachButtons(root);
    config = HarnessController.get(requireContext()).config();

    InfoHint.bind(
        root.findViewById(R.id.island_intro), UiText.text("任务状态上岛"), UiText.text("任务阶段显示在状态栏胶囊"));
    setText(R.id.island_label_state, "当前状态");
    setText(R.id.island_label_behavior, "行为");
    setText(R.id.island_label_order, "上岛顺序");
    setText(R.id.island_order_hint, "同级先开始的优先 · 结束后保留 8 秒");
    setText(R.id.island_preview_caption, "回到桌面后（示意）");
    setText(R.id.island_enabled_title, "任务状态上岛");
    setText(R.id.island_enabled_sub, "多个任务同时运行时，只显示优先级最高的一个");
    setText(R.id.island_chronometer_title, "用系统计时器显示耗时");
    setText(R.id.island_devices, "支持的设备");

    CompoundButton enabled = root.findViewById(R.id.island_enabled);
    CompoundButton chronometer = root.findViewById(R.id.island_chronometer);
    enabled.setChecked(config.isTaskIslandEnabled());
    chronometer.setChecked(config.isTaskIslandChronometer());
    enabled.setOnCheckedChangeListener(
        (button, checked) -> {
          config.setTaskIslandEnabled(checked);
          renderSwitches();
          renderLive();
        });
    chronometer.setOnCheckedChangeListener(
        (button, checked) -> {
          config.setTaskIslandChronometer(checked);
          renderSwitches();
        });
    bindRowToggle(R.id.island_enabled_text, enabled);
    bindRowToggle(R.id.island_chronometer_text, chronometer);

    root.findViewById(R.id.island_open_settings).setOnClickListener(v -> openSettings());
    root.findViewById(R.id.island_devices).setOnClickListener(v -> showDevices());

    buildLevels(inflater);
    renderSwitches();
    root.findViewById(R.id.island_columns)
        .addOnLayoutChangeListener(
            (v, l, t, r, b, ol, ot, or, ob) -> {
              if (r - l != or - ol) v.post(this::applyColumns);
            });
    return root;
  }

  @Override
  public void onResume() {
    super.onResume();
    // 用户可能刚从系统设置回来：每次可见都重新读系统状态。
    renderStatus();
    main.removeCallbacks(tick);
    main.post(tick);
  }

  @Override
  public void onPause() {
    main.removeCallbacks(tick);
    super.onPause();
  }

  @Override
  public void onDestroyView() {
    main.removeCallbacks(tick);
    root = null;
    super.onDestroyView();
  }

  // ---------- 系统状态 ----------

  private void renderStatus() {
    if (root == null) return;
    int raw;
    try {
      raw = IslandSupport.state(requireContext());
    } catch (Throwable unavailable) {
      raw = IslandSupport.STATE_UNKNOWN;
    }
    status = IslandPage.status(raw);
    String title;
    String sub;
    int dot;
    switch (status) {
      case ALLOWED -> {
        title = UiText.text("已允许");
        sub = UiText.text("回桌面后显示任务胶囊");
        dot = R.color.shell_good;
      }
      case DENIED -> {
        title = UiText.text("实时活动已关闭");
        sub = UiText.text("未获系统允许，仅显示通知");
        dot = R.color.shell_warn;
      }
      case UNSUPPORTED -> {
        title = UiText.text("当前系统不支持");
        sub = UiText.text("需要 Android 16 及以上");
        dot = R.color.shell_text_muted;
      }
      default -> {
        title = UiText.text("系统支持，开关状态查不到");
        sub = UiText.text("系统未提供查询接口，仍会尝试显示");
        dot = R.color.shell_warn;
      }
    }
    String api = "API " + Build.VERSION.SDK_INT;
    if (raw == IslandSupport.STATE_UNKNOWN && IslandSupport.supported())
      api = api + " · " + UiText.text("旧版接口");
    ((TextView) root.findViewById(R.id.island_state_title)).setText(title);
    ((TextView) root.findViewById(R.id.island_state_sub)).setText(sub);
    ((TextView) root.findViewById(R.id.island_state_api)).setText(api);
    root.findViewById(R.id.island_state_dot)
        .setBackgroundTintList(ColorStateList.valueOf(requireContext().getColor(dot)));
    root.findViewById(R.id.island_state_text).setContentDescription(join(title, sub, api));

    TextView open = root.findViewById(R.id.island_open_settings);
    boolean offers = IslandPage.offersSettings(status);
    open.setVisibility(offers ? View.VISIBLE : View.GONE);
    open.setText(UiText.text(status == IslandPage.Status.DENIED ? "去打开" : "系统设置"));
    open.setContentDescription(UiText.text("打开系统实时活动设置"));
    renderLive();
  }

  private void openSettings() {
    try {
      startActivity(IslandSupport.settingsIntent(requireContext()));
    } catch (Exception unavailable) {
      // 系统没有对应设置页时不阻塞用户。
      Toast.makeText(requireContext(), UiText.text("无法打开系统实时活动设置"), Toast.LENGTH_SHORT).show();
    }
  }

  // ---------- 开关 ----------

  /** 标题列点按时切换同一行的开关（与运行配置页一致）；禁用时不响应。 */
  private void bindRowToggle(int textColumn, CompoundButton target) {
    root.findViewById(textColumn)
        .setOnClickListener(
            v -> {
              if (target.isEnabled()) target.toggle();
            });
  }

  private void renderSwitches() {
    if (root == null) return;
    boolean on = ((CompoundButton) root.findViewById(R.id.island_enabled)).isChecked();
    CompoundButton chronometer = root.findViewById(R.id.island_chronometer);
    // 计时器只在上岛开启时生效（AgentTaskNotifications: islandEnabled && chronometer）；
    // 置灰但保留已保存的值，重新开启上岛后原样生效。
    chronometer.setEnabled(on);
    root.findViewById(R.id.island_chronometer_text).setAlpha(on ? 1f : 0.5f);
    String chronometerSub =
        !on
            ? UiText.text("需先开启任务状态上岛")
            : UiText.text(chronometer.isChecked() ? "通知显示已用时间，由系统计秒" : "通知显示开始时间");
    show(R.id.island_chronometer_sub, chronometerSub);
    describe(R.id.island_enabled_title, R.id.island_enabled_sub, R.id.island_enabled);
    describe(R.id.island_chronometer_title, R.id.island_chronometer_sub, R.id.island_chronometer);
  }

  private void describe(int titleId, int subId, int switchId) {
    CharSequence title = ((TextView) root.findViewById(titleId)).getText();
    CharSequence sub = ((TextView) root.findViewById(subId)).getText();
    root.findViewById(switchId).setContentDescription(join(title, sub));
  }

  // ---------- 预览与上岛顺序 ----------

  private void buildLevels(LayoutInflater inflater) {
    LinearLayout host = root.findViewById(R.id.island_levels);
    host.removeAllViews();
    boolean chinese = chinese();
    for (int i = 0; i < IslandPage.LEVELS.length; i++) {
      int rank = IslandPage.LEVELS[i];
      if (i > 0) {
        View divider = new View(requireContext());
        divider.setBackgroundResource(R.color.shell_line);
        host.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
      }
      View row = inflater.inflate(R.layout.item_island_level, host, false);
      TextView no = row.findViewById(R.id.island_level_no);
      TextView title = row.findViewById(R.id.island_level_title);
      TextView sample = row.findViewById(R.id.island_level_sample);
      TextView why = row.findViewById(R.id.island_level_why);
      TextView count = row.findViewById(R.id.island_level_count);
      no.setText(String.valueOf(rank));
      title.setText(levelName(rank));
      sample.setText(IslandPage.sample(rank, chinese));
      String reason = levelReason(rank);
      why.setText(reason);
      why.setVisibility(reason.isEmpty() ? View.GONE : View.VISIBLE);
      levelViews[i] = new TextView[] {no, title, count};
      levelRows[i] = row;
      host.addView(row);
    }
  }

  private static String levelName(int rank) {
    return switch (rank) {
      case 5 -> UiText.text("等你确认");
      case 4 -> UiText.text("正在停止");
      case 3 -> UiText.text("执行工具");
      case 2 -> UiText.text("生成回复");
      default -> UiText.text("其他运行中");
    };
  }

  private static String levelReason(int rank) {
    return switch (rank) {
      case 5 -> UiText.text("需要你回答，排最前");
      case 4 -> UiText.text("你已点停止，先显示结果");
      case 3 -> UiText.text("胶囊带工具名");
      default -> "";
    };
  }

  /** 每秒：读任务快照，刷新顶部预览与五级高亮。只读，不发通知。 */
  private void renderLive() {
    if (root == null || !isAdded()) return;
    List<AgentTaskState.View> rows;
    try {
      rows = AgentTaskNotifications.get(requireContext()).snapshot();
    } catch (Throwable unavailable) {
      rows = List.of();
    }
    long now = SystemClock.elapsedRealtime();
    renderPreview(IslandPage.preview(rows, now, IslandText.TERMINAL_GRACE_MS), now);
    renderLevels(rows);
  }

  private void renderPreview(IslandPage.Preview preview, long now) {
    boolean island = config.isTaskIslandEnabled();
    TextView clock = root.findViewById(R.id.island_strip_clock);
    clock.setText(
        java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
    TextView capsule = root.findViewById(R.id.island_capsule);
    View notice = root.findViewById(R.id.island_notice);
    TextView note = root.findViewById(R.id.island_preview_note);
    View container = root.findViewById(R.id.island_preview);
    String caption = UiText.text("回到桌面后（示意）");

    if (preview.empty()) {
      capsule.setVisibility(View.GONE);
      notice.setVisibility(View.GONE);
      String idle = UiText.text("暂无运行任务");
      note.setText(idle);
      container.setContentDescription(join(caption, idle));
      return;
    }

    AgentTaskState.View row = preview.shown();
    boolean capsuleShown = island && IslandPage.mayPromote(status);
    String shortText = AgentTaskNotifications.islandShortText(row);
    capsule.setVisibility(capsuleShown ? View.VISIBLE : View.GONE);
    if (capsuleShown) {
      boolean maybe = status == IslandPage.Status.UNDETERMINED;
      capsule.setText(shortText);
      capsule.setBackgroundResource(
          maybe ? R.drawable.bg_island_capsule_maybe : R.drawable.bg_island_capsule);
      capsule.setTextColor(
          requireContext().getColor(maybe ? R.color.shell_accent : R.color.shell_on_accent));
    }

    String title = row.title().isEmpty() ? UiText.text("DSHA 任务") : row.title();
    String phase = AgentTaskNotifications.phase(row);
    String body = row.detail().isEmpty() ? phase : phase + " · " + row.detail();
    if (!row.active()) {
      long elapsed = Math.max(0, row.seen() - row.started());
      body = body + " · " + IslandText.duration(elapsed, chinese());
    }
    notice.setVisibility(View.VISIBLE);
    ((TextView) root.findViewById(R.id.island_notice_title)).setText(title);
    ((TextView) root.findViewById(R.id.island_notice_text)).setText(body);

    String reason;
    if (!island) reason = UiText.text("任务状态上岛已关闭，只显示普通通知。");
    else if (!capsuleShown) reason = UiText.text("系统未允许实时活动，只显示普通通知。");
    else if (status == IslandPage.Status.UNDETERMINED) reason = UiText.text("是否显示胶囊由系统决定。");
    else reason = "";
    String others = preview.others() > 0 ? UiText.format("另有 %d 个任务只显示普通通知", preview.others()) : "";
    String noteText = join(reason, others);
    note.setText(noteText);
    note.setVisibility(noteText.isEmpty() ? View.GONE : View.VISIBLE);
    container.setContentDescription(
        join(caption, capsuleShown ? shortText : "", title, body, noteText));
  }

  private void renderLevels(List<AgentTaskState.View> rows) {
    int[] counts = IslandPage.levelCounts(rows);
    int live = config.isTaskIslandEnabled() ? IslandPage.liveRank(rows) : 0;
    for (int i = 0; i < IslandPage.LEVELS.length; i++) {
      if (levelViews[i] == null) continue;
      int rank = IslandPage.LEVELS[i];
      boolean on = rank == live;
      TextView no = levelViews[i][0];
      TextView title = levelViews[i][1];
      TextView count = levelViews[i][2];
      no.setBackgroundResource(on ? R.drawable.bg_island_badge_on : R.drawable.bg_island_badge);
      no.setTextColor(
          requireContext().getColor(on ? R.color.shell_on_accent : R.color.shell_text_secondary));
      levelRows[i].setBackgroundResource(
          on ? R.color.shell_accent_soft : android.R.color.transparent);
      String countText =
          on ? UiText.text("在岛上") : counts[i] > 0 ? UiText.format("%d 个", counts[i]) : "";
      count.setText(countText);
      count.setTextColor(
          requireContext().getColor(on ? R.color.shell_accent : R.color.shell_text_muted));
      levelRows[i].setContentDescription(
          join(String.valueOf(rank), title.getText(), levelReason(rank), countText));
    }
  }

  /**
   * 「支持的设备」：沿用 CardSheet（手机贴底并让出悬浮胶囊，宽屏居中限宽卡片）。
   * 三条完整句各占一行，前面的状态点与页面系统状态行同色：可上岛 / 仅普通通知 / 补充说明。
   */
  private void showDevices() {
    android.content.Context context = requireContext();
    CardPage page = new CardPage(context, UiText.text("支持的设备"), "", true);
    LinearLayout card = page.card();
    card.setPadding(page.dp(16), page.dp(4), page.dp(16), page.dp(4));
    deviceRow(
        page,
        card,
        R.color.shell_good,
        R.color.shell_text,
        UiText.text("Android 16 及以上的原生系统、小米澎湃 OS 3.0.300+/4、ColorOS 16、OriginOS 6，以及后续跟进同一接口的厂商。"));
    deviceRow(
        page,
        card,
        R.color.shell_warn,
        R.color.shell_text_secondary,
        UiText.text("更老的系统和鸿蒙只显示普通通知，这是系统能力差异，不是开关失效。"));
    deviceRow(
        page,
        card,
        R.color.shell_text_muted,
        R.color.shell_text_secondary,
        UiText.text("小米超级岛的大岛样式需要向小米申请白名单；DSHA 不依赖它，未申请时使用系统生成的胶囊。"));
    var dialog = CardSheet.create(context, page);
    page.button(page.footer, UiText.text("知道了"), false, dialog::dismiss);
    CardSheet.show(dialog, context);
  }

  private void deviceRow(CardPage page, LinearLayout card, int dot, int color, String text) {
    if (card.getChildCount() > 0) {
      View divider = new View(page.context);
      divider.setBackgroundResource(R.color.shell_line);
      LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, page.dp(1));
      line.setMarginStart(page.dp(22));
      card.addView(divider, line);
    }
    LinearLayout row = new LinearLayout(page.context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setPadding(0, page.dp(12), 0, page.dp(12));
    View mark = new View(page.context);
    mark.setBackgroundResource(R.drawable.bg_island_dot);
    mark.setBackgroundTintList(ColorStateList.valueOf(page.context.getColor(dot)));
    mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    // 点与首行文字垂直居中：首行行高约 20dp，点 8dp。
    LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(page.dp(8), page.dp(8));
    markParams.topMargin = page.dp(6);
    markParams.setMarginEnd(page.dp(14));
    row.addView(mark, markParams);
    TextView body = page.text(text, 14, color);
    body.setLineSpacing(page.dp(4), 1);
    body.setTextIsSelectable(true);
    row.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
    card.addView(row);
  }

  // ---------- 布局 ----------

  /** 内容宽度 ≥ island_two_column_dp 时左右两列；超过最大内容宽度时两侧留白居中。 */
  private void applyColumns() {
    if (root == null) return;
    LinearLayout columns = root.findViewById(R.id.island_columns);
    View start = root.findViewById(R.id.island_column_start);
    View end = root.findViewById(R.id.island_column_end);
    float density = getResources().getDisplayMetrics().density;
    int width = columns.getWidth();
    if (width == 0) return;
    boolean two =
        Math.round(width / density) >= getResources().getInteger(R.integer.island_two_column_dp);
    int basePad = getResources().getDimensionPixelSize(R.dimen.config_page_pad_h);
    int maxContent =
        Math.round(getResources().getInteger(R.integer.island_max_content_dp) * density);
    int side = two ? Math.max(Math.round(28 * density), (width - maxContent) / 2) : basePad;
    if (columns.getPaddingStart() != side || columns.getPaddingEnd() != side) {
      columns.setPaddingRelative(side, columns.getPaddingTop(), side, columns.getPaddingBottom());
      View intro = root.findViewById(R.id.island_intro);
      ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) intro.getLayoutParams();
      p.setMarginStart(side);
      p.setMarginEnd(side);
      intro.setLayoutParams(p);
    }
    int orientation = two ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL;
    if (columns.getOrientation() == orientation) return;
    columns.setOrientation(orientation);
    int gap = getResources().getDimensionPixelSize(R.dimen.config_column_gap);
    LinearLayout.LayoutParams a = (LinearLayout.LayoutParams) start.getLayoutParams();
    LinearLayout.LayoutParams b = (LinearLayout.LayoutParams) end.getLayoutParams();
    a.width = two ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
    b.width = two ? 0 : ViewGroup.LayoutParams.MATCH_PARENT;
    a.weight = two ? 1f : 0f;
    b.weight = two ? 1f : 0f;
    b.setMarginStart(two ? gap : 0);
    start.setLayoutParams(a);
    end.setLayoutParams(b);
  }

  // ---------- 工具 ----------

  /** 设置已登记的中文源文案（经 UiText 翻译）。 */
  private void setText(int id, String chinese) {
    show(id, UiText.text(chinese));
  }

  /** 设置已翻译好的文字。 */
  private void show(int id, String text) {
    ((TextView) root.findViewById(id)).setText(text);
  }

  /** 与通知侧一致：胶囊短文本只有中英两套，按界面语言是否中文选择。 */
  private static boolean chinese() {
    return UiText.language().startsWith("zh");
  }

  /** 无障碍描述用的分句连接；分隔符走现有已翻译的「，」。 */
  private static String join(CharSequence... parts) {
    StringBuilder text = new StringBuilder();
    for (CharSequence part : parts) {
      if (part == null || part.length() == 0) continue;
      if (text.length() > 0) text.append(UiText.text("，"));
      text.append(part);
    }
    return text.toString();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
