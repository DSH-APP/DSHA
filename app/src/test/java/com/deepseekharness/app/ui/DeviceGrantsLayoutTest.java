package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.GrantSummary;
import com.deepseekharness.app.util.UiText;
import com.google.android.material.materialswitch.MaterialSwitch;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/**
 * 设备能力授权页：首页只保留全局 / 入口 / 系统 / 敏感四段，顶部不再有概览胶囊；
 * 设备执行通道与屏幕操作各自独立成页，通道与屏幕的紧凑行、档位与确认方式仍在子页验证。
 * 内容宽 ≥640dp 时敏感能力移到右栏。Root 未启用时点放宽档不会保存。
 *
 * <p>mdpi 下 1dp = 1px。只验证布局与本地状态，不验证真机渲染。
 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    application = DeviceGrantsLayoutTest.App.class,
    shadows = DeviceGrantsLayoutTest.NoStorageManager.class,
    qualifiers = "zh-rCN-w360dp-h740dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public final class DeviceGrantsLayoutTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  /** Robolectric 没有存储卷，原生 isExternalStorageManager 会越界；这里按「未授予」处理。 */
  @Implements(android.os.Environment.class)
  public static final class NoStorageManager {
    @Implementation
    protected static boolean isExternalStorageManager() {
      return false;
    }
  }

  public static final class Host extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout content = new FrameLayout(this);
      content.setId(R.id.fragment_container);
      setContentView(content);
    }
  }

  private ActivityController<Host> host;
  private String previousLanguage;

  @Before
  public void language() {
    previousLanguage = UiText.language();
    UiText.setLanguage("zh");
  }

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
    UiText.setLanguage(previousLanguage);
  }

  private View start(int width) {
    return startPage(width, new DeviceGrantsFragment());
  }

  private View startChannels(int width) {
    return startPage(width, new DeviceChannelsFragment());
  }

  private View startScreen(int width) {
    return startPage(width, new ScreenOperationFragment());
  }

  private View startPage(int width, Fragment fragment) {
    host = Robolectric.buildActivity(Host.class).setup().visible();
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, fragment)
        .commitNow();
    ShadowLooper.shadowMainLooper().idle();
    View page =
        host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container).getView();
    layout(page, width);
    return page;
  }

  private static void layout(View root, int width) {
    for (int pass = 0; pass < 4; pass++) {
      root.measure(
          View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, width, root.getMeasuredHeight());
      ShadowLooper.shadowMainLooper().idle();
    }
  }

  private static void collectOverflow(View view, int left, int width, List<String> out) {
    if (view.getVisibility() != View.VISIBLE) return;
    int start = left + view.getLeft();
    int end = start + view.getWidth();
    if (start < -1 || end > width + 1) {
      String label =
          view instanceof TextView ? String.valueOf(((TextView) view).getText()) : view.toString();
      out.add(label + " [" + start + "," + end + "]");
      return;
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        collectOverflow(group.getChildAt(i), start - view.getScrollX(), width, out);
  }

  private static int idOf(View page, String name) {
    return page.getResources().getIdentifier(name, "id", page.getContext().getPackageName());
  }

  private static List<Integer> sectionOrder(ViewGroup column) {
    List<Integer> ids = new ArrayList<>();
    for (int i = 0; i < column.getChildCount(); i++) ids.add(column.getChildAt(i).getId());
    return ids;
  }

  @Test
  public void phoneShowsEntryRowsWithoutSummaryHeader() {
    View page = start(360);
    List<String> overflow = new ArrayList<>();
    collectOverflow(page, 0, 360, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());

    // 顶部四个概览胶囊与固定头部已移除，页面直接从滚动内容开始（编译期资源 id 已不存在）。
    assertEquals("概览头部必须移除", 0, idOf(page, "grants_header"));
    assertEquals("概览胶囊必须移除", 0, idOf(page, "grants_pills"));

    assertEquals(
        List.of(
            R.id.grants_section_global,
            R.id.grants_section_entries,
            R.id.grants_section_system,
            R.id.grants_section_sensitive),
        sectionOrder(page.findViewById(R.id.grants_col_start)));
    assertEquals(View.GONE, page.findViewById(R.id.grants_col_end).getVisibility());

    // 两个入口行：可点、带状态摘要；卡片内容已迁到独立页面。
    View channels = page.findViewById(R.id.channels_entry);
    View screen = page.findViewById(R.id.screen_entry);
    assertTrue(channels.isClickable());
    assertTrue(screen.isClickable());
    TextView channelsStatus = page.findViewById(R.id.channels_entry_status);
    TextView screenStatus = page.findViewById(R.id.screen_entry_status);
    assertFalse(channelsStatus.getText().toString().isEmpty());
    assertFalse(screenStatus.getText().toString().isEmpty());
    assertEquals(0, ellipsisCount(channelsStatus));
    assertEquals(0, ellipsisCount(screenStatus));
    assertNull("通道卡必须移除", page.findViewById(R.id.channels_card));
    assertNull("屏幕卡必须移除", page.findViewById(R.id.screen_card));
    assertNull("Root 档位已移到通道页", page.findViewById(R.id.device_root_modes));
    assertNull("确认方式已移到屏幕页", page.findViewById(R.id.screen_policy_status));

    // 全局权限卡追加到槽位里，不再按页面下标插入。
    assertEquals(1, ((ViewGroup) page.findViewById(R.id.grants_global_slot)).getChildCount());
  }

  @Test
  @Config(qualifiers = "zh-rCN-w900dp-h1200dp-mdpi")
  public void wideContentMovesSensitiveToSecondColumn() {
    View page = start(900);
    ViewGroup end = page.findViewById(R.id.grants_col_end);
    assertEquals(View.VISIBLE, end.getVisibility());
    assertEquals(List.of(R.id.grants_section_sensitive), sectionOrder(end));
    assertEquals(
        List.of(
            R.id.grants_section_global, R.id.grants_section_entries, R.id.grants_section_system),
        sectionOrder(page.findViewById(R.id.grants_col_start)));
    List<String> overflow = new ArrayList<>();
    collectOverflow(page, 0, 900, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());
  }

  @Test
  public void entriesOpenTheirOwnPages() {
    View page = start(360);
    page.findViewById(R.id.channels_entry).performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertTrue(
        "通道入口必须打开独立页面",
        host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container)
            instanceof DeviceChannelsFragment);
    host.get().getSupportFragmentManager().popBackStackImmediate();
    ShadowLooper.shadowMainLooper().idle();

    page =
        host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container).getView();
    page.findViewById(R.id.screen_entry).performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertTrue(
        "屏幕入口必须打开独立页面",
        host.get().getSupportFragmentManager().findFragmentById(R.id.fragment_container)
            instanceof ScreenOperationFragment);
  }

  /**
   * 通道页紧凑行式：Root / ADB 的开关与标题同一行；状态在标题左列按权重换行。
   */
  @Test
  public void channelPageUsesCompactRows() {
    View page = startChannels(360);
    ViewGroup rootText = (ViewGroup) page.findViewById(R.id.device_root_text);
    View rootSwitch = page.findViewById(R.id.config_root_shell);
    assertSame(rootText, page.findViewById(R.id.device_root_title).getParent());
    assertSame(rootText, page.findViewById(R.id.device_root_status).getParent());
    assertSame(rootText.getParent(), rootSwitch.getParent());
    assertSame(rootText.getParent(), page.findViewById(R.id.device_root_verify).getParent());
    ViewGroup shizukuText = (ViewGroup) page.findViewById(R.id.device_shizuku_text);
    assertSame(shizukuText, page.findViewById(R.id.device_shizuku_title).getParent());
    assertSame(shizukuText, page.findViewById(R.id.workspace_shizuku_status).getParent());
    assertSame(shizukuText.getParent(), page.findViewById(R.id.workspace_shizuku_auth).getParent());
    ViewGroup adbText = (ViewGroup) page.findViewById(R.id.config_adb_text);
    assertSame(adbText, page.findViewById(R.id.config_adb_title).getParent());
    assertSame(adbText, page.findViewById(R.id.config_adb_status).getParent());
    assertSame(adbText.getParent(), page.findViewById(R.id.config_adb_pair).getParent());
    assertSame(adbText.getParent(), page.findViewById(R.id.config_adb_enable).getParent());
    int channels = page.findViewById(R.id.grants_section_channels).getHeight();
    // 四行通道（含外接设备）含状态换行与外接设备说明，预算与原页一致。
    assertTrue("channels too tall: " + channels, channels <= 560);
    List<String> overflow = new ArrayList<>();
    collectOverflow(page, 0, 360, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());
  }

  /** 屏幕页紧凑行式：状态行 + 设置按钮同一行，虚拟识屏并入屏幕卡。 */
  @Test
  public void screenPageUsesCompactRows() {
    View page = startScreen(360);
    assertSame(
        page.findViewById(R.id.config_a11y_status).getParent(),
        page.findViewById(R.id.computer_use_accessibility).getParent());
    assertSame(
        page.findViewById(R.id.screen_card),
        page.findViewById(R.id.screen_virtual_group).getParent());
    int screen = page.findViewById(R.id.grants_section_screen).getHeight();
    // 虚拟屏预览档位行与确认方式说明都在同一页；收起态多保留一行安全后果。
    assertTrue("screen too tall: " + screen, screen <= 560);
    List<String> overflow = new ArrayList<>();
    collectOverflow(page, 0, 360, overflow);
    assertTrue("horizontal overflow: " + overflow, overflow.isEmpty());
  }

  /**
   * T125：ADB 通道状态是整句（开启后可能带端口或原因）。状态变长时只在标题左列里换行，
   * 「配对」按钮与开关始终保留宽度、留在页内，且不靠省略号截断。
   */
  @Test
  @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
  public void longAdbStatusWrapsWithoutSqueezingChannelActions() {
    String[][] samples = {
      {"zh", "尚未配对：点「配对」，配对后还会验证连接。"},
      {"en", "Not paired: tap Pair; the connection is verified after pairing."},
      {"zh", "连接已验证 · 端口 5555\\nkey=YES port=5555"},
      {"zh", LONG_ADB_STATUS}
    };
    for (String[] sample : samples) {
      UiText.setLanguage(sample[0]);
      View page = startChannels(320);
      TextView pill = page.findViewById(R.id.config_adb_status);
      pill.setText(sample[1]);
      relayout(page, 320);
      List<String> overflow = new ArrayList<>();
      collectOverflow(page, 0, 320, overflow);
      assertTrue(sample[0] + " horizontal overflow: " + overflow, overflow.isEmpty());
      assertStatusBoundedInTextColumn(pill);
      assertAdbActionsInside(page, 320);
      assertEquals(sample[0] + " 状态不能省略", 0, ellipsisCount(pill));
      host.pause().stop().destroy();
      host = null;
    }
  }

  /**
   * T125：通道状态必须允许按可用宽度换行——多行上限、不省略，状态列按权重让出动作宽度。
   *
   * <p>Robolectric 默认（LEGACY）字体度量不可靠（宽字符可能按 0 宽计算），离屏测不出真实换行；
   * 这里锁定结构契约（可换行、无省略号、状态列受权重约束、动作保持宽度），像素级换行由
   * LayoutAuditInstrumentation 与真机核对。
   */
  @Test
  @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
  public void channelStatusAllowsWrappingWithoutEllipsis() {
    UiText.setLanguage("zh");
    View page = startChannels(320);
    TextView pill = page.findViewById(R.id.config_adb_status);
    pill.setText(LONG_ADB_STATUS);
    relayout(page, 320);
    assertStatusBoundedInTextColumn(pill);
    assertAdbActionsInside(page, 320);
    assertEquals("通道状态必须允许多行", 6, pill.getMaxLines());
    assertNull("通道状态不能用省略号截断", pill.getEllipsize());
    assertEquals("通道状态不能出现省略号", 0, ellipsisCount(pill));
  }

  /** 1.3 倍字体 + 320dp：长状态同样只占左列，配对按钮与开关不丢宽度。 */
  @Test
  @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
  public void longAdbStatusWrapsAtLargeFontScale() {
    org.robolectric.RuntimeEnvironment.setFontScale(1.3f);
    try {
      UiText.setLanguage("en");
      View page = startChannels(320);
      TextView pill = page.findViewById(R.id.config_adb_status);
      pill.setText(
          "Not paired: tap Pair; the connection is verified after pairing. "
              + "If pairing fails, go back to wireless debugging and pair again.");
      relayout(page, 320);
      assertStatusBoundedInTextColumn(pill);
      assertAdbActionsInside(page, 320);
      assertEquals("1.3x 通道状态必须允许多行", 6, pill.getMaxLines());
      assertNull("1.3x 通道状态不能用省略号截断", pill.getEllipsize());
      assertEquals("1.3x 状态不能省略", 0, ellipsisCount(pill));
    } finally {
      org.robolectric.RuntimeEnvironment.setFontScale(1f);
    }
  }

  /**
   * 状态胶囊必须被限制在标题左列里：左列存在且窄于通道行（行内动作要有位置），
   * 胶囊不宽于左列。旧布局把胶囊与按钮放在同一行，胶囊量完就没有按钮的宽度了。
   */
  private static void assertStatusBoundedInTextColumn(TextView pill) {
    View column = (View) pill.getParent();
    View row = (View) column.getParent();
    // 旧布局把胶囊和按钮放进同一行：胶囊按自身宽度先量，按钮只剩 0 宽。状态必须回到按权重让位的左列。
    android.widget.LinearLayout.LayoutParams lp =
        (android.widget.LinearLayout.LayoutParams) column.getLayoutParams();
    assertEquals("状态左列必须按权重让出动作宽度", 1f, lp.weight, 0.001f);
    assertEquals("状态左列宽度必须由权重分配", 0, lp.width);
    assertTrue("左列宽度为 0", column.getWidth() > 0);
    assertTrue("左列占满通道行，动作按钮没有位置", column.getWidth() < row.getWidth());
    assertTrue(
        "状态胶囊宽于左列: " + pill.getWidth() + " > " + column.getWidth(),
        pill.getWidth() <= column.getWidth());
  }

  private static final String LONG_ADB_STATUS =
      "尚未配对：点「配对」，配对后还会验证连接。连接失败时请回到无线调试页重新配对；" + "环境任务进行中时 ADB 暂停检查，完成后自动重试。";

  private static void assertAdbActionsInside(View page, int pageWidth) {
    View pair = page.findViewById(R.id.config_adb_pair);
    View toggle = page.findViewById(R.id.config_adb_enable);
    TextView pill = page.findViewById(R.id.config_adb_status);
    assertTrue("配对按钮被状态文案挤掉宽度", pair.getWidth() > 0);
    assertTrue("开关被状态文案挤掉宽度", toggle.getWidth() > 0);
    int pillLeft = absoluteLeft(pill);
    int pillRight = pillLeft + pill.getWidth();
    int pairLeft = absoluteLeft(pair);
    int pairRight = pairLeft + pair.getWidth();
    int toggleLeft = absoluteLeft(toggle);
    int toggleRight = toggleLeft + toggle.getWidth();
    assertTrue(
        "状态胶囊越界: [" + pillLeft + "," + pillRight + "]", pillLeft >= 0 && pillRight <= pageWidth);
    assertTrue(
        "配对按钮越界: [" + pairLeft + "," + pairRight + "]", pairLeft >= 0 && pairRight <= pageWidth);
    assertTrue(
        "开关越界: [" + toggleLeft + "," + toggleRight + "]",
        toggleLeft >= 0 && toggleRight <= pageWidth);
    // 与布局方向无关：三者横向互不重叠（RTL 时顺序镜像，但不会叠在一起）。
    assertTrue("配对按钮与状态胶囊重叠", pillRight <= pairLeft || pairRight <= pillLeft);
    assertTrue("开关与配对按钮重叠", pairRight <= toggleLeft || toggleRight <= pairLeft);
  }

  /** 重复测量/布局但不跑主线程 idle：避免后台状态回调覆盖测试里设定的长文案。 */
  private static void relayout(View root, int width) {
    for (int pass = 0; pass < 2; pass++) {
      root.measure(
          View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(1600, View.MeasureSpec.EXACTLY));
      root.layout(0, 0, width, root.getMeasuredHeight());
    }
  }

  private static int absoluteLeft(View view) {
    int x = 0;
    View candidate = view;
    while (candidate != null) {
      x += candidate.getLeft() - candidate.getScrollX();
      android.view.ViewParent parent = candidate.getParent();
      candidate = parent instanceof View ? (View) parent : null;
    }
    return x;
  }

  @Test
  public void relaxedSegmentDoesNotSaveWhileRootIsDisabled() {
    Context context = org.robolectric.RuntimeEnvironment.getApplication();
    context
        .getSharedPreferences(Constants.PREFS, 0)
        .edit()
        .putBoolean(Constants.KEY_ALLOW_ROOT_SHELL, false)
        .commit();
    new ConfigStore(context).setRootPolicyMode(DeviceShellPolicy.Mode.STRICT);
    View page = startChannels(360);
    RadioGroup root = page.findViewById(R.id.device_root_modes);
    root.getChildAt(1).performClick();
    ShadowLooper.shadowMainLooper().idle();
    assertEquals(DeviceShellPolicy.Mode.STRICT, new ConfigStore(context).rootPolicyMode());
    assertTrue(((RadioButton) root.getChildAt(0)).isChecked());
    assertFalse(((RadioButton) root.getChildAt(1)).isChecked());
  }

  /**
   * 十语种状态词：中文保留「关闭 / 關閉」，其余语种用「已关闭」译条（x1970619），
   * 不能复用「关闭按钮」词条，否则会得到 Close / Fermer / Закрыть 这类动词译法。
   */
  @Test
  public void offStateWordUsesOffTranslationsInEveryLanguage() {
    java.util.Map<String, String> expected =
        java.util.Map.of(
            "zh", "关闭",
            "zh-Hant", "關閉",
            "en", "Off",
            "ru", "Выключено",
            "ja", "無効",
            "ko", "꺼짐",
            "ar", "معطّل",
            "pt", "Desativado",
            "la", "Inactivum",
            "fr", "Désactivé");
    for (var entry : expected.entrySet()) {
      UiText.setLanguage(entry.getKey());
      assertEquals(
          entry.getKey(),
          entry.getValue(),
          DeviceGrantsFragment.summaryText(GrantSummary.Label.GLOBAL_OFF));
    }
  }

  /** 长译文语种（fr / ru / pt）在 320dp 窄屏下入口状态不溢出、不省略。 */
  @Test
  @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
  public void entryStatusFitsLongTranslationsAtNarrowWidth() {
    for (String language : new String[] {"fr", "ru", "pt"}) {
      UiText.setLanguage(language);
      View page = start(320);
      List<String> overflow = new ArrayList<>();
      collectOverflow(page, 0, 320, overflow);
      assertTrue(language + " horizontal overflow: " + overflow, overflow.isEmpty());
      assertEntryStatusNotClipped(page, language);
      host.pause().stop().destroy();
      host = null;
    }
  }

  /** 1.3 倍字体 + 320dp + 最长译文（ru）也不截断。 */
  @Test
  @Config(qualifiers = "zh-rCN-w320dp-h740dp-mdpi")
  public void entryStatusFitsAtLargeFontScale() {
    org.robolectric.RuntimeEnvironment.setFontScale(1.3f);
    try {
      UiText.setLanguage("ru");
      View page = start(320);
      assertEntryStatusNotClipped(page, "ru@1.3");
    } finally {
      org.robolectric.RuntimeEnvironment.setFontScale(1f);
    }
  }

  /** 入口状态摘要允许换行、不使用省略号；整行仍在页宽内。 */
  private static void assertEntryStatusNotClipped(View page, String language) {
    for (int id : new int[] {R.id.channels_entry_status, R.id.screen_entry_status}) {
      TextView status = page.findViewById(id);
      assertNull(language + " 入口状态不能用省略号截断", status.getEllipsize());
      assertEquals(language + " 入口状态不能出现省略号", 0, ellipsisCount(status));
      assertTrue(language + " 入口状态被截断", status.getText().length() > 0);
      assertTrue(
          language + " 入口状态越界",
          absoluteLeft(status) >= 0 && absoluteLeft(status) + status.getWidth() <= page.getWidth());
    }
  }

  /** 开关关闭态轨道用近白底（不能与浅灰卡片同色），开启态仍是主题主色。 */
  @Test
  public void switchesUseContrastingTrackTint() {
    View page = startScreen(360);
    MaterialSwitch pause = page.findViewById(R.id.screen_pause);
    ColorStateList tint = pause.getTrackTintList();
    assertNotNull("开关必须带显式轨道填充", tint);
    Context context = page.getContext();
    assertEquals(
        "关闭态轨道应为近白底", themeColor(context, R.attr.dsha_pal_shell_surface), tint.getDefaultColor());
    assertEquals(
        "开启态轨道仍为主题主色",
        themeColor(context, R.attr.dsha_pal_primary),
        tint.getColorForState(new int[] {android.R.attr.state_checked}, 0));
  }

  private static int themeColor(Context context, int attribute) {
    android.util.TypedValue value = new android.util.TypedValue();
    assertTrue(context.getTheme().resolveAttribute(attribute, value, true));
    return value.data;
  }

  /** 全部文件访问：整行与状态词都直接打开系统授权页，不再折叠成说明。 */
  @Test
  public void allFilesRowOpensSystemSettings() {
    View page = start(360);
    TextView status = page.findViewById(R.id.config_all_files_status);
    assertEquals("未开启", status.getText().toString());
    View row = page.findViewById(R.id.config_all_files);
    assertTrue("整行可点，直接进系统授权页", row.isClickable());
    assertEquals(
        "行尾箭头提示会跳转到系统页",
        View.VISIBLE,
        page.findViewById(R.id.config_all_files_chevron).getVisibility());
    row.performClick();
    ShadowActivity shadowActivity = Shadow.extract(host.get());
    Intent started = shadowActivity.getNextStartedActivity();
    assertNotNull("点击后应拉起系统「所有文件访问」设置", started);
    assertEquals(
        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        started.getAction());
  }

  /** 屏幕确认方式：收起态保留一行安全后果；「全部允许」放宽档位默认展开完整规则。 */
  @Test
  public void screenPolicyDisclosureKeepsConsequenceAndExpandsWhenRelaxed() {
    View page = startScreen(360);
    TextView status = page.findViewById(R.id.screen_policy_status);
    assertTrue(status.isClickable());
    assertFalse(DisclosureCard.expanded(status));
    // 收起态不再常显安全说明；完整规则收进展开区。
    assertNull(findText(page, "仅控制 DSHA 确认，不授予系统权限"));
    status.performClick();
    assertTrue(DisclosureCard.expanded(status));
    assertNotNull(
        findText(
            page,
            "仅设置 DSHA 的屏幕确认；不会授予系统权限。全部允许只省普通确认，敏感界面仍按「敏感界面交接」询问或交接，密码文本读写始终阻断。自定义中未配置的应用仍询问。规则保存在本机，不随备份迁移。"));
  }

  private static String findText(View view, String target) {
    if (view instanceof TextView text && target.contentEquals(text.getText())) return target;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        String found = findText(group.getChildAt(i), target);
        if (found != null) return found;
      }
    return null;
  }

  private static int ellipsisCount(TextView view) {
    android.text.Layout layout = view.getLayout();
    assertNotNull(String.valueOf(view.getText()), layout);
    int total = 0;
    for (int line = 0; line < layout.getLineCount(); line++) total += layout.getEllipsisCount(line);
    return total;
  }
}
