package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticRepository;
import com.deepseekharness.app.util.DiagnosticReport;
import com.deepseekharness.app.util.DiagnosticReport.Health;
import com.deepseekharness.app.util.UiText;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/** 真实自检布局（手机 / sw600dp）与结果分组渲染；只向 ViewModel 注入结果，不运行环境探针。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = DiagnosticActivityRenderingTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class DiagnosticActivityRenderingTest {
  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  private ActivityController<DiagnosticActivity> controller;
  private String previousLanguage;

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage("en");
  }

  @After
  public void tearDown() {
    if (controller != null) controller.pause().stop().destroy();
    RuntimeEnvironment.setFontScale(1f);
    UiText.setLanguage(previousLanguage);
  }

  @Test
  @Config(qualifiers = "en-rUS-w360dp-h740dp-mdpi")
  public void phoneStartsIdleWithEmptyStateAndSingleTitle() {
    DiagnosticActivity page = open();
    assertEquals("Not checked", text(page, R.id.diagnostic_headline));
    assertEquals("Inspect", ((Button) page.findViewById(R.id.diagnostic_refresh)).getText());
    LinearLayout results = page.findViewById(R.id.diagnostic_results);
    assertEquals(1, results.getChildCount());
    assertSame(page.findViewById(R.id.diagnostic_empty), results.getChildAt(0));
    // 复制需要结果；未检查时禁用。旧页面的页内大标题已移除，只剩顶栏一处。
    assertFalse(page.findViewById(R.id.diagnostic_copy).isEnabled());
    assertEquals(1, countText(page.getWindow().getDecorView(), "Diagnostics"));
    assertEquals(View.GONE, page.findViewById(R.id.diagnostic_logs_status).getVisibility());
  }

  @Test
  @Config(qualifiers = "en-rUS-w360dp-h740dp-mdpi")
  public void problemsComeFirstAndHealthyChecksFoldIntoOneRow() throws Exception {
    DiagnosticActivity page = open();
    publish(
        page,
        check("a", Health.PASS, "Device", "Sampled"),
        check("b", Health.UNVERIFIED, "Web auth", "DSH not running"),
        check("c", Health.INFO, "Storage", "Available"),
        check("d", Health.NEEDS_ATTENTION, "Network tools", "curl failed"),
        check("e", Health.PASS, "Node", "Responded"));

    assertEquals("1 need attention", text(page, R.id.diagnostic_headline));
    assertEquals("Check again", ((Button) page.findViewById(R.id.diagnostic_refresh)).getText());
    assertTrue(page.findViewById(R.id.diagnostic_copy).isEnabled());
    List<String> rows = rowDescriptions(page.findViewById(R.id.diagnostic_results));
    assertEquals(List.of("Network tools, curl failed", "Web auth, DSH not running", "3 OK"), rows);
    assertNull(findByDescription(page, "Device, Sampled"));

    findByDescription(page, "3 OK").performClick();
    settle();
    assertNotNull(findByDescription(page, "Device, Sampled"));
    assertNotNull(findByDescription(page, "Storage, Available"));
    assertEquals(1, okColumns(page));
  }

  @Test
  @Config(qualifiers = "en-rUS-w360dp-h740dp-mdpi")
  public void allPassingShowsGoodHeadlineAndFailurePhaseWins() throws Exception {
    DiagnosticActivity page = open();
    publish(
        page,
        check("a", Health.PASS, "Device", "Sampled"),
        check("b", Health.INFO, "Storage", "Ok"));
    assertEquals("Everything looks fine", text(page, R.id.diagnostic_headline));
    assertEquals("All 2 checks passed", text(page, R.id.diagnostic_status));

    repository(page).phase.setValue(DiagnosticRepository.Phase.FAILED);
    settle();
    assertEquals("Check incomplete", text(page, R.id.diagnostic_headline));
  }

  @Test
  @Config(qualifiers = "en-rUS-sw800dp-w1280dp-h800dp-land-mdpi")
  public void tabletLandscapeUsesSideBySideColumnsAndTwoColumnHealthyList() throws Exception {
    DiagnosticActivity page = open();
    publish(page, healthy(10));
    // 状态卡主按钮横跨整张卡片，其右缘即左栏右缘；结果卡必须完全位于其右侧且顶部同排。
    View hero = page.findViewById(R.id.diagnostic_refresh);
    View results = page.findViewById(R.id.diagnostic_results);
    int[] heroAt = new int[2], resultsAt = new int[2];
    hero.getLocationInWindow(heroAt);
    results.getLocationInWindow(resultsAt);
    assertTrue(
        "results right of status card: heroRight="
            + (heroAt[0] + hero.getWidth())
            + " results="
            + resultsAt[0],
        resultsAt[0] > heroAt[0] + hero.getWidth());
    assertTrue("columns share the top row", resultsAt[1] < heroAt[1]);

    findByDescription(page, "10 OK").performClick();
    settle();
    assertEquals(2, okColumns(page));
  }

  @Test
  @Config(qualifiers = "en-rUS-sw800dp-w1280dp-h800dp-land-mdpi")
  public void largeFontKeepsHealthyListInOneColumnOnTablet() throws Exception {
    RuntimeEnvironment.setFontScale(1.3f);
    DiagnosticActivity page = open();
    publish(page, healthy(6));
    findByDescription(page, "6 OK").performClick();
    settle();
    assertEquals(1, okColumns(page));
  }

  @Test
  @Config(qualifiers = "en-rUS-w360dp-h740dp-mdpi")
  public void exportSheetOffersTextReportOnlyAfterACheck() throws Exception {
    DiagnosticActivity page = open();
    page.findViewById(R.id.diagnostic_export).performClick();
    settle();
    View sheet = ShadowDialog.getLatestDialog().getWindow().getDecorView();
    View text = findByText(sheet, "Text report .txt");
    assertNotNull(text);
    assertFalse(((View) text.getParent()).isEnabled());
    assertNotNull(findByText(sheet, "Run a check first"));
    assertNotNull(findByText(sheet, "Save to Downloads"));
    ShadowDialog.getLatestDialog().dismiss();

    publish(page, check("a", Health.PASS, "Device", "Sampled"));
    page.findViewById(R.id.diagnostic_export).performClick();
    settle();
    sheet = ShadowDialog.getLatestDialog().getWindow().getDecorView();
    View option = (View) findByText(sheet, "Text report .txt").getParent();
    assertTrue(option.isEnabled());
    option.performClick();
    settle();
    // 文本报告只走系统文件选择器，「下载」按钮隐藏。
    assertEquals(View.GONE, findByText(sheet, "Save to Downloads").getVisibility());
  }

  @Test
  @Config(qualifiers = "en-rUS-w360dp-h740dp-mdpi")
  public void feedbackExportEntryFollowsTestPackageBuildFlag() {
    DiagnosticActivity page = open();
    // 入口只由构建标志决定：CI 全量单测用 -Pdsha.sunsetTest=true（TEST_PACKAGE=true），
    // 本机普通构建为 false；不能按包名或运行时状态猜测。
    int expected = com.deepseekharness.app.BuildConfig.TEST_PACKAGE ? View.VISIBLE : View.GONE;
    assertEquals(expected, page.findViewById(R.id.diagnostic_feedback_export).getVisibility());
    assertEquals(
        expected, page.findViewById(R.id.diagnostic_feedback_export_divider).getVisibility());
    assertEquals(View.GONE, page.findViewById(R.id.diagnostic_feedback_status).getVisibility());
    if (expected == View.VISIBLE) {
      android.widget.TextView title = page.findViewById(R.id.diagnostic_feedback_title);
      assertNotNull(title);
      assertTrue(title.getText().length() > 0);
    }
  }

  // ---------- helpers ----------

  private DiagnosticActivity open() {
    controller = Robolectric.buildActivity(DiagnosticActivity.class).setup().visible();
    settle();
    return controller.get();
  }

  private static DiagnosticRepository repository(DiagnosticActivity page) {
    return new ViewModelProvider(page).get(DiagnosticRepository.class);
  }

  private void publish(DiagnosticActivity page, DiagnosticReport.Check... checks) throws Exception {
    Constructor<DiagnosticRepository.Result> make =
        DiagnosticRepository.Result.class.getDeclaredConstructor(DiagnosticReport.Check.class);
    make.setAccessible(true);
    List<DiagnosticRepository.Result> results = new ArrayList<>();
    for (DiagnosticReport.Check check : checks) results.add(make.newInstance(check));
    DiagnosticRepository model = repository(page);
    model.results.setValue(results);
    model.phase.setValue(DiagnosticRepository.Phase.SUCCEEDED);
    model.report.setValue("report");
    settle();
  }

  private static DiagnosticReport.Check check(
      String key, Health health, String title, String status) {
    return new DiagnosticReport.Check(key, health, title, status, "detail " + key);
  }

  private static DiagnosticReport.Check[] healthy(int n) {
    DiagnosticReport.Check[] all = new DiagnosticReport.Check[n];
    for (int i = 0; i < n; i++) all[i] = check("k" + i, Health.PASS, "Check " + i, "Ok " + i);
    return all;
  }

  private void settle() {
    for (int i = 0; i < 3; i++) {
      ShadowLooper.idleMainLooper();
      if (controller != null) {
        View root = controller.get().getWindow().getDecorView();
        root.measure(
            View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
        root.layout(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
      }
    }
  }

  private static String text(DiagnosticActivity page, int id) {
    return ((TextView) page.findViewById(id)).getText().toString();
  }

  /** 结果卡中带 contentDescription 的直接行（问题行与折叠头）。 */
  private static List<String> rowDescriptions(LinearLayout card) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < card.getChildCount(); i++) {
      CharSequence d = card.getChildAt(i).getContentDescription();
      if (d != null) out.add(d.toString());
    }
    return out;
  }

  /** 展开后网格每行的单元格数（不含占位空 View）。 */
  private static int okColumns(DiagnosticActivity page) {
    LinearLayout card = page.findViewById(R.id.diagnostic_results);
    ViewGroup grid = (ViewGroup) card.getChildAt(card.getChildCount() - 1);
    for (int i = 0; i < grid.getChildCount(); i++)
      if (grid.getChildAt(i) instanceof LinearLayout line
          && line.getOrientation() == LinearLayout.HORIZONTAL) return line.getChildCount();
    return 0;
  }

  private static View findByDescription(DiagnosticActivity page, String description) {
    return find(
        page.getWindow().getDecorView(),
        v ->
            description.contentEquals(
                v.getContentDescription() == null ? "" : v.getContentDescription()));
  }

  private static View findByText(View root, String value) {
    return find(root, v -> v instanceof TextView t && value.contentEquals(t.getText()));
  }

  private static int countText(View root, String value) {
    int[] n = {0};
    find(
        root,
        v -> {
          if (v instanceof TextView t
              && v.getVisibility() == View.VISIBLE
              && value.contentEquals(t.getText())) n[0]++;
          return false;
        });
    return n[0];
  }

  private static View find(View root, java.util.function.Predicate<View> match) {
    if (match.test(root)) return root;
    if (root instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View hit = find(group.getChildAt(i), match);
        if (hit != null) return hit;
      }
    return null;
  }
}
