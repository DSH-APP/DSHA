package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.plugins.PluginManagementEngine;
import com.deepseekharness.app.plugins.PluginOperationQueue;
import com.deepseekharness.app.plugins.PluginRegistry;
import com.deepseekharness.app.plugins.PluginSourceIdentity;
import com.deepseekharness.app.util.PluginSort;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
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

/** Standard 的真实 XML、Fragment、RecyclerView 和 DiffUtil 回归；只隔离数据与执行入口。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    qualifiers = "en-rUS-w400dp-h800dp-mdpi",
    application = PluginFragmentRenderingTest.App.class)
@LooperMode(LooperMode.Mode.PAUSED)
public final class PluginFragmentRenderingTest {
  private static final int WIDTH = 400, HEIGHT = 800;
  private static final String PAGE_TAG = "plugin-rendering";
  private static final PluginSourceIdentity SOURCE =
      new PluginSourceIdentity(
          new File("/fixture/plugin-rendering/data"),
          "memory-data",
          0,
          new File("/fixture/plugin-rendering/rootfs"),
          "memory-rootfs",
          0,
          "fixture",
          1);

  public static final class App extends DshaApp {
    @Override
    public void onCreate() {}
  }

  public static final class FixtureHost extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle saved) {
      setTheme(R.style.Theme_DeepseekHarness);
      super.onCreate(saved);
      FrameLayout content = new FrameLayout(this);
      content.setId(R.id.fragment_container);
      setContentView(content);
    }
  }

  public static final class Fixture extends ViewModel {
    final MutableLiveData<PluginRepository.State> states = new MutableLiveData<>();
  }

  /** 与调试 Page 使用相同四个边界，生产布局、render、Adapter 和页面状态均不覆写。 */
  public static final class FixtureFragment extends PluginFragment {
    @Override
    PluginManagementEngine managementEngine() {
      return null;
    }

    @Override
    LiveData<PluginRepository.State> installedState() {
      return new ViewModelProvider(requireActivity()).get(Fixture.class).states;
    }

    @Override
    boolean safeModeActive() {
      return false;
    }

    @Override
    void syncInstalledState() {}
  }

  public static final class OtherFragment extends Fragment {
    public OtherFragment() {
      super(android.R.layout.simple_list_item_1);
    }
  }

  private ActivityController<FixtureHost> host;
  private FixtureFragment page;
  private String previousLanguage;

  @Before
  public void setUp() {
    previousLanguage = UiText.language();
    UiText.setLanguage("en");
    new ConfigStore(RuntimeEnvironment.getApplication()).setPluginSort(PluginSort.Mode.NAME_ASC);
    android.content.pm.ActivityInfo activity = new android.content.pm.ActivityInfo();
    activity.name = FixtureHost.class.getName();
    activity.packageName = RuntimeEnvironment.getApplication().getPackageName();
    activity.applicationInfo = RuntimeEnvironment.getApplication().getApplicationInfo();
    activity.theme = R.style.Theme_DeepseekHarness;
    org.robolectric.shadows.ShadowPackageManager packageManager =
        org.robolectric.shadow.api.Shadow.extract(
            RuntimeEnvironment.getApplication().getPackageManager());
    packageManager.addOrUpdateActivity(activity);
    host = Robolectric.buildActivity(FixtureHost.class).setup().visible();
  }

  @After
  public void tearDown() {
    if (host != null) host.pause().stop().destroy();
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    UiText.setLanguage(previousLanguage);
  }

  @Test
  public void unknownLoadingFailedAndConfirmedEmptyRenderDifferentStates() throws Exception {
    PluginRegistry registry = new PluginRegistry();
    mount(state(registry.snapshot(), List.of(), false, "", -1));
    assertEmptyText("The plugin list is unconfirmed. Refresh to check.");
    assertEquals("Plugin list unconfirmed", text(page.find(R.id.pluginCount)));
    assertEquals(2, adapter().getItemCount());

    PluginRegistry.Query query = registry.beginQuery(SOURCE);
    publish(state(registry.snapshot(), List.of(), false, "", -1));
    assertEmptyText("Reading plugins…");
    assertEquals("Checking plugins…", text(page.find(R.id.pluginCount)));

    assertTrue(registry.failQuery(query, "fixture read failure"));
    publish(state(registry.snapshot(), List.of(), false, "fixture read failure", -1));
    assertEmptyText("Could not read plugins. Check the status and refresh to retry.");
    assertEquals("Plugin list unconfirmed", text(page.find(R.id.pluginCount)));
    assertEquals("fixture read failure", text(page.find(R.id.statusText)));

    assertTrue(registry.completeQuery(registry.beginQuery(SOURCE), List.of(), List.of(), false));
    publish(state(registry.snapshot(), List.of(), false, "", -1));
    assertEmptyText("No plugins installed");
    assertEquals(
        host.get().getResources().getQuantityString(R.plurals.plugin_count, 0, 0),
        text(page.find(R.id.pluginCount)));
    assertEquals(2, adapter().getItemCount());
    assertEquals(0, adapter().getItemViewType(0));
    assertEquals(2, adapter().getItemViewType(1));
  }

  @Test
  public void damagedPluginShowsRecoveryGuidanceAndKeepsDeleteAvailable() throws Exception {
    var damaged =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "damaged-plugin")
                .put("available", true)
                .put("deletable", true)
                .put("metadataError", "PLUGIN_METADATA_UNREADABLE"));
    mount(state(catalog(List.of(damaged)), List.of(), false, "", -1));
    assertTrue(text(rowAt(1).findViewById(R.id.pluginDesc)).contains("Metadata read failed"));
    assertTrue(
        String.valueOf(rowAt(1).findViewById(R.id.pluginStatus).getContentDescription())
            .contains("Plugin metadata unavailable"));
    assertEquals(View.VISIBLE, rowAt(1).findViewById(R.id.pluginDelete).getVisibility());
  }

  @Test
  public void temporaryPluginPointsToItsWebSessionAndNeverShowsPackageDeletion() throws Exception {
    var temporary =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "temporary-plugin")
                .put("dynamic", true)
                .put("deletable", true)
                .put("sessionId", "fixture-session")
                .put("pluginId", "fixture-plugin"));
    mount(state(catalog(List.of(temporary)), List.of(), false, "", -1));
    assertTrue(text(rowAt(1).findViewById(R.id.pluginDesc)).contains("Cordis plugin management"));
    assertEquals(View.GONE, rowAt(1).findViewById(R.id.pluginDelete).getVisibility());
  }

  @Test
  public void runtimeEvidenceRefreshKeepsRowsAndDistinguishesHistoryFromCurrent() throws Exception {
    var alpha = item("alpha").withRuntime("loaded", 1);
    mount(state(catalog(List.of(alpha, item("beta"))), List.of(), false, "", -1));
    assertEquals("Loaded", text(rowAt(1).findViewById(R.id.pluginRuntime)));
    publish(
        state(
            catalog(List.of(alpha.withRuntime("loaded", 1, true), item("beta"))),
            List.of(),
            false,
            "",
            -1));
    assertEquals("Unverified", text(rowAt(1).findViewById(R.id.pluginRuntime)));
    String staleDetail =
        String.valueOf(rowAt(1).findViewById(R.id.pluginRuntime).getContentDescription());
    assertTrue(staleDetail.contains("awaiting verification; last state: loaded"));
    for (String runtime : List.of("pending", "failed", "loaded")) {
      publish(
          state(
              catalog(List.of(alpha.withRuntime(runtime, 1), item("beta"))),
              List.of(),
              false,
              "",
              -1));
      assertEquals(
          runtime.equals("pending") ? "Waiting" : runtime.equals("failed") ? "Failed" : "Loaded",
          text(rowAt(1).findViewById(R.id.pluginRuntime)));
      assertEquals(List.of("alpha", "beta"), renderedNames());
    }
    var historical =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "alpha")
                .put("available", true)
                .put("enabled", true)
                .put("version", "1.0")
                .put("loadState", "loaded"));
    publish(state(catalog(List.of(historical)), List.of(), false, "", -1));
    assertEquals("Unverified", text(rowAt(1).findViewById(R.id.pluginRuntime)));
    String history =
        String.valueOf(rowAt(1).findViewById(R.id.pluginRuntime).getContentDescription());
    assertTrue(history.contains("Current Web: load unconfirmed"));
    assertTrue(history.contains("Last activation record: loaded"));
  }

  @Test
  public void badgesSeparateSourceVersionConfigAndRuntimeTones() throws Exception {
    var loaded =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "a-loaded")
                .put("available", true)
                .put("enabled", true)
                .put("runtimeProvided", true)
                .put("version", "0.2.2-rc.1")
                .put("runtimeState", "loaded")
                .put("runtimeStale", false));
    var disabled =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "b-disabled")
                .put("available", true)
                .put("version", "1.0")
                .put("runtimeState", "stopped")
                .put("runtimeStale", false));
    var unconfirmed =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "c-unconfirmed")
                .put("available", true)
                .put("enabled", true)
                .put("version", "1.0")
                .put("runtimeState", "unknown"));
    mount(state(catalog(List.of(loaded, disabled, unconfirmed)), List.of(), false, "", -1));

    View loadedRow = rowAt(1);
    assertBadge(
        loadedRow,
        R.id.pluginBadge,
        "Provided by DSH",
        R.color.shell_text_secondary,
        page.requireContext().getColor(R.color.shell_raised));
    assertBadge(
        loadedRow,
        R.id.pluginVersion,
        "0.2.2-rc.1",
        R.color.shell_text_secondary,
        page.requireContext().getColor(R.color.shell_raised));
    assertBadge(
        loadedRow, R.id.pluginStatus, "Enabled", R.color.shell_good, badgeFill(R.color.shell_good));
    String loadedRuntime = text(loadedRow.findViewById(R.id.pluginRuntime));
    assertEquals("Loaded", loadedRuntime);
    assertBadge(
        loadedRow,
        R.id.pluginRuntime,
        loadedRuntime,
        R.color.shell_good,
        badgeFill(R.color.shell_good));

    View disabledRow = rowAt(2);
    assertBadge(
        disabledRow,
        R.id.pluginBadge,
        "Third-party",
        R.color.shell_text_secondary,
        page.requireContext().getColor(R.color.shell_raised));
    assertBadge(
        disabledRow,
        R.id.pluginStatus,
        "Disabled",
        R.color.shell_text_secondary,
        page.requireContext().getColor(R.color.shell_raised));
    String unloadedRuntime = text(disabledRow.findViewById(R.id.pluginRuntime));
    assertEquals("Not loaded", unloadedRuntime);
    assertBadge(
        disabledRow,
        R.id.pluginRuntime,
        unloadedRuntime,
        R.color.shell_text_secondary,
        page.requireContext().getColor(R.color.shell_raised));

    View unconfirmedRow = rowAt(3);
    assertBadge(
        unconfirmedRow,
        R.id.pluginStatus,
        "Enabled",
        R.color.shell_good,
        badgeFill(R.color.shell_good));
    String unconfirmedRuntime = text(unconfirmedRow.findViewById(R.id.pluginRuntime));
    assertEquals("Unverified", unconfirmedRuntime);
    assertBadge(
        unconfirmedRow,
        R.id.pluginRuntime,
        unconfirmedRuntime,
        R.color.shell_warn,
        badgeFill(R.color.shell_warn));
  }

  @Test
  public void restartRequirementRendersWarningChipBesidesTheFourBadges() throws Exception {
    var pending =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "restart-plugin")
                .put("available", true)
                .put("enabled", true)
                .put("version", "1.0")
                .put("runtimeState", "loaded")
                .put("runtimeStale", false)
                .put("restartRequired", true));
    mount(state(catalog(List.of(pending)), List.of(), false, "", -1));
    View row = rowAt(1);
    for (int id :
        new int[] {R.id.pluginBadge, R.id.pluginVersion, R.id.pluginStatus, R.id.pluginRuntime})
      assertEquals(View.VISIBLE, row.findViewById(id).getVisibility());

    TextView chipText = row.findViewById(R.id.pluginChipText);
    assertNotNull("重启待办必须渲染为警示胶囊", chipText);
    assertEquals("Restart Web to apply.", text(chipText));
    assertEquals(View.VISIBLE, row.findViewById(R.id.pluginNotes).getVisibility());
    assertEquals(
        page.requireContext().getColor(R.color.shell_warn), chipText.getCurrentTextColor());
    View chip = (View) chipText.getParent();
    assertNotNull(chip.getBackgroundTintList());
    assertEquals(badgeFill(R.color.shell_warn), chip.getBackgroundTintList().getDefaultColor());
  }

  @Test
  public void headerOnlyStateChangesRenderWithoutAnyAdapterNotification() throws Exception {
    PluginRegistry.Snapshot catalog = catalog(List.of(item("alpha"), item("beta")));
    mount(state(catalog, List.of(), false, "", -1));
    Notifications observer = observe();

    View strip = page.find(R.id.pluginStatusStrip);
    int idleHeight = strip.getHeight();
    assertEquals(View.VISIBLE, page.find(R.id.btnPluginUpdates).getVisibility());
    assertEquals(View.VISIBLE, page.find(R.id.pluginCount).getVisibility());

    publish(state(catalog, List.of(), true, "fixture header progress", 37));
    assertEquals(View.VISIBLE, page.find(R.id.pluginBusy).getVisibility());
    PluginWaveProgress progress = page.find(R.id.pluginBusy);
    assertEquals(37, progress.percent());
    assertFalse(progress.indeterminate());
    assertEquals("fixture header progress", text(page.find(R.id.statusText)));
    // 同一条状态条里只有一个动作：忙碌时取消代替检查更新，计数让位。
    assertEquals(View.VISIBLE, page.find(R.id.btnCancelPluginTask).getVisibility());
    assertEquals(View.GONE, page.find(R.id.btnPluginUpdates).getVisibility());
    assertEquals(View.GONE, page.find(R.id.pluginCount).getVisibility());
    assertEquals(View.VISIBLE, page.find(R.id.pluginStatusSpinner).getVisibility());
    assertEquals("忙碌与空闲状态条等高，不顶动列表", idleHeight, strip.getHeight());
    observer.assertNone();

    publish(state(catalog, List.of(), false, "fixture header finished", -1));
    // 没有明确结果时直接回到空闲；车道保留（INVISIBLE），状态条高度不变。
    assertEquals(View.INVISIBLE, page.find(R.id.pluginBusy).getVisibility());
    assertEquals("fixture header finished", text(page.find(R.id.statusText)));
    assertEquals(View.GONE, page.find(R.id.btnCancelPluginTask).getVisibility());
    assertEquals(View.VISIBLE, page.find(R.id.btnPluginUpdates).getVisibility());
    assertEquals(idleHeight, strip.getHeight());
    observer.assertNone();
    assertEquals(List.of("alpha", "beta"), renderedNames());
  }

  @Test
  public void compactHeaderMovesSourceChipAndCollapsesIdleMarketStrip() throws Exception {
    mount(state(catalog(List.of(item("alpha"), item("beta"))), List.of(), false, "", -1));
    TextView source = page.find(R.id.btnPluginDownloadSource);
    View manageSlot = page.find(R.id.pluginManageSourceSlot);
    assertSame("管理页：下载源在筛选行", manageSlot, source.getParent());
    assertEquals("Source: auto", text(source));
    assertTrue(source.getContentDescription().toString().contains("Automatic"));
    TextView installed = page.find(R.id.btnInstalled);
    assertTrue("分段带计数徽标", text(installed).startsWith("Plugin manager"));
    assertTrue(installed.getContentDescription().toString().contains("2"));
    assertEquals(View.GONE, page.find(R.id.pluginSafeBanner).getVisibility());

    assertTrue(page.find(R.id.btnMarket).performClick());
    layout();
    assertSame("市场页：下载源与解析安装同行", page.find(R.id.pluginMarketSourceSlot), source.getParent());
    assertTrue(text(source).startsWith("Download source"));
    assertEquals("市场页空闲且无消息时状态条收起", View.GONE, page.find(R.id.pluginStatusStrip).getVisibility());

    publish(
        state(
            catalog(List.of(item("alpha"), item("beta"))),
            List.of(),
            false,
            "fixture market note",
            -1));
    assertEquals(View.VISIBLE, page.find(R.id.pluginStatusStrip).getVisibility());
    assertEquals(View.GONE, page.find(R.id.btnPluginUpdates).getVisibility());
    assertEquals(View.GONE, page.find(R.id.pluginCount).getVisibility());

    EditText link = page.find(R.id.appbar_github_input);
    android.widget.ImageButton paste = page.find(R.id.btnPluginPaste);
    assertEquals(host.get().getString(R.string.ui_m0179), paste.getContentDescription());
    link.setText("https://github.com/example/plugin");
    layout();
    assertEquals("Clear link", paste.getContentDescription());
    assertTrue(page.find(R.id.btnPluginInstall).isEnabled());
    assertTrue(paste.performClick());
    assertEquals("", text(link));
    assertFalse(page.find(R.id.btnPluginInstall).isEnabled());
  }

  @Test
  public void unrelatedOperationProgressDoesNotNotifyOrChangePluginRows() throws Exception {
    PluginRegistry.Snapshot catalog = catalog(List.of(item("alpha"), item("beta")));
    mount(state(catalog, List.of(), false, "", -1));
    String alpha = rowStatus(1);
    String beta = rowStatus(2);
    Notifications observer = observe();
    PluginOperationQueue.Queue<String> queue = new PluginOperationQueue.Queue<>();
    var operation =
        queue.enqueue(PluginOperationQueue.Type.CHECK_UPDATES, "*", null, "", SOURCE, "fixture");
    assertSame(operation, queue.poll());

    for (int percent : new int[] {5, 40, 85}) {
      assertTrue(
          queue.progress(
              operation.operationId,
              PluginOperationQueue.Phase.RUNNING,
              "global progress",
              percent,
              null));
      publish(state(catalog, queue.snapshot(), true, "global progress", percent));
      assertEquals(percent, ((PluginWaveProgress) page.find(R.id.pluginBusy)).percent());
      observer.assertNone();
      assertEquals(alpha, rowStatus(1));
      assertEquals(beta, rowStatus(2));
    }
  }

  @Test
  public void targetProgressUsesOnlyItsPayloadAndSkipsHeaderAtEveryPosition() throws Exception {
    List<PluginRepository.Item> items = List.of(item("alpha"), item("beta"), item("gamma"));
    PluginRegistry.Snapshot catalog = catalog(items);
    mount(state(catalog, List.of(), false, "", -1));
    Notifications observer = observe();

    // 首插件和非首插件均验证；position 0 始终是真实页头。
    for (int targetPosition : new int[] {1, 2}) {
      PluginOperationQueue.Queue<String> queue = new PluginOperationQueue.Queue<>();
      var operation =
          queue.enqueue(
              PluginOperationQueue.Type.SET_ENABLED,
              items.get(targetPosition - 1).stableKey,
              false,
              "1.0",
              SOURCE,
              "fixture");
      assertSame(operation, queue.poll());
      assertTrue(
          queue.progress(
              operation.operationId,
              PluginOperationQueue.Phase.RUNNING,
              "target progress",
              10,
              null));
      publish(state(catalog, queue.snapshot(), true, "target progress", 10));
      View target = rowAt(targetPosition);
      assertTrue(rowStatus(targetPosition).contains("10%"));
      int otherPosition = targetPosition == 1 ? 2 : 1;
      String otherStatus = rowStatus(otherPosition);
      long targetId = adapter().getItemId(targetPosition);
      observer.clear();

      assertTrue(
          queue.progress(
              operation.operationId,
              PluginOperationQueue.Phase.RUNNING,
              "target progress",
              70,
              null));
      publish(state(catalog, queue.snapshot(), true, "target progress", 70));
      observer.assertSingle("change", targetPosition, 1);
      assertEquals(Integer.valueOf(PluginListPresenter.OPERATION), observer.events.get(0).payload);
      assertTrue(observer.events.get(0).position >= 1);
      assertEquals(targetId, adapter().getItemId(targetPosition));
      assertEquals(
          items.get(targetPosition - 1).name,
          text(rowAt(targetPosition).findViewById(R.id.pluginName)));
      assertTrue(rowStatus(targetPosition).contains("70%"));
      assertEquals(otherStatus, rowStatus(otherPosition));
      assertEquals(0, adapter().getItemViewType(0));
    }
  }

  @Test
  public void insertRemoveAndSortNotificationsAlsoKeepTheHeaderOffset() throws Exception {
    PluginRepository.Item alpha = item("alpha"), beta = item("beta"), first = item("aardvark");
    mount(state(catalog(List.of(alpha, beta)), List.of(), false, "", -1));
    long alphaId = adapter().getItemId(1), betaId = adapter().getItemId(2);
    Notifications observer = observe();

    publish(state(catalog(List.of(alpha, beta, first)), List.of(), false, "", -1));
    observer.assertSingle("insert", 1, 1);
    assertEquals(List.of("aardvark", "alpha", "beta"), renderedNames());
    assertEquals(alphaId, adapter().getItemId(2));
    assertEquals(betaId, adapter().getItemId(3));
    observer.clear();

    publish(state(catalog(List.of(alpha, beta)), List.of(), false, "", -1));
    observer.assertSingle("remove", 1, 1);
    assertEquals(List.of("alpha", "beta"), renderedNames());
    observer.clear();

    chooseSort(PluginSort.Mode.NAME_DESC);
    assertEquals(0, observer.fullChanges);
    assertFalse(observer.events.isEmpty());
    for (Event event : observer.events) {
      assertEquals("move", event.kind);
      assertTrue(event.position >= 1);
      assertTrue(event.destination >= 1);
    }
    assertEquals(List.of("beta", "alpha"), renderedNames());
    assertEquals(betaId, adapter().getItemId(1));
    assertEquals(alphaId, adapter().getItemId(2));
  }

  @Test
  public void newFragmentAfterAnotherPageRestoresFiltersExpansionAndStableKeyAnchor()
      throws Exception {
    List<PluginRepository.Item> items = recoveryItems();
    mount(state(catalog(items), List.of(), false, "", -1));
    configureRecoveryPage();
    FixtureFragment original = page;
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, new OtherFragment())
        .commitNow();
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    assertNull(original.getView());

    // 同一动态身份改变展示名称，并在其前面插入一行；恢复不能按旧名称或旧 position 猜测。
    List<PluginRepository.Item> refreshed = new ArrayList<>(items);
    refreshed.set(7, anchorItem("plugin8-renamed"));
    refreshed.add(item("plugin13"));
    fixture().states.setValue(state(catalog(refreshed), List.of(), false, "", -1));
    page = new FixtureFragment();
    replacePage();
    assertNotSame(original, page);
    assertRecoveryControls();
    assertAnchor(6, "plugin8-renamed", -13);
    assertEquals(View.VISIBLE, rowAt(6).findViewById(R.id.pluginDetails).getVisibility());
    assertEquals(
        List.of(
            "plugin13",
            "plugin12",
            "plugin11",
            "plugin10",
            "plugin9",
            "plugin8-renamed",
            "plugin7",
            "plugin6",
            "plugin5",
            "plugin4",
            "plugin3",
            "plugin2",
            "plugin1"),
        renderedNames());
  }

  @Test
  public void savedActivityRestoresPageWithAFreshViewModelAndRealViews() throws Exception {
    PluginRepository.State ready = state(catalog(recoveryItems()), List.of(), false, "", -1);
    mount(ready);
    configureRecoveryPage();
    Fixture oldFixture = fixture();
    FixtureFragment oldPage = page;
    Bundle saved = new Bundle();
    host.pause().saveInstanceState(saved).stop().destroy();
    host = null;
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();

    host = Robolectric.buildActivity(FixtureHost.class).create(saved);
    assertNotSame(oldFixture, fixture());
    fixture().states.setValue(ready);
    host.start().resume().visible();
    page = (FixtureFragment) host.get().getSupportFragmentManager().findFragmentByTag(PAGE_TAG);
    assertNotNull(page);
    assertNotSame(oldPage, page);
    layout();
    assertRecoveryControls();
    assertAnchor(5, "plugin8", -13);
    assertEquals(View.VISIBLE, rowAt(5).findViewById(R.id.pluginDetails).getVisibility());
    assertEquals(14, adapter().getItemCount());
  }

  private void configureRecoveryPage() {
    ((EditText) page.find(R.id.pluginSearch)).setText("keep");
    ((CheckBox) page.find(R.id.chkHideBuiltin)).setChecked(true);
    chooseSort(PluginSort.Mode.NAME_DESC);
    assertEquals(14, adapter().getItemCount());
    assertEquals("plugin12", text(rowAt(1).findViewById(R.id.pluginName)));
    assertEquals("plugin8", text(rowAt(5).findViewById(R.id.pluginName)));
    assertTrue(rowAt(5).findViewById(R.id.pluginExpand).performClick());
    layout();
    assertEquals(View.VISIBLE, rowAt(5).findViewById(R.id.pluginDetails).getVisibility());
    layoutManager().scrollToPositionWithOffset(5, -13);
    layout();
    assertAnchor(5, "plugin8", -13);
  }

  private void assertRecoveryControls() {
    assertEquals("keep", text(page.find(R.id.pluginSearch)));
    assertTrue(((CheckBox) page.find(R.id.chkHideBuiltin)).isChecked());
    assertEquals(host.get().getString(R.string.plugin_sort_za), text(page.find(R.id.btnSort)));
    assertTrue(page.find(R.id.btnInstalled).isSelected());
    assertEquals(View.VISIBLE, page.find(R.id.installedControls).getVisibility());
  }

  private void assertAnchor(int position, String name, int offset) {
    assertEquals(position, layoutManager().findFirstVisibleItemPosition());
    View anchor = layoutManager().findViewByPosition(position);
    assertNotNull(anchor);
    assertEquals(name, text(anchor.findViewById(R.id.pluginName)));
    assertEquals(offset, layoutManager().getDecoratedTop(anchor) - list().getPaddingTop());
  }

  private List<PluginRepository.Item> recoveryItems() throws Exception {
    List<PluginRepository.Item> items = new ArrayList<>();
    for (int index = 1; index <= 12; index++)
      items.add(index == 8 ? anchorItem("plugin8") : item("plugin" + index));
    items.add(
        new PluginRepository.Item(
            new JSONObject().put("name", "unrelated").put("available", true)));
    PluginRepository.Item builtin =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "dsh-task-notifier")
                .put("description", "keep builtin")
                .put("available", true)
                .put("enabled", true)
                .put("builtin", true));
    assertTrue(builtin.builtin);
    items.add(builtin);
    return items;
  }

  private static PluginRepository.Item item(String name) throws Exception {
    return new PluginRepository.Item(
        new JSONObject()
            .put("name", name)
            .put("description", "keep fixture " + name)
            .put("available", true)
            .put("enabled", true)
            .put("version", "1.0"));
  }

  private static PluginRepository.Item anchorItem(String name) throws Exception {
    return new PluginRepository.Item(
        new JSONObject()
            .put("name", name)
            .put("description", "keep stable anchor")
            .put("available", true)
            .put("enabled", true)
            .put("dynamic", true)
            .put("runtimeGeneration", 1)
            .put("sessionId", "fixture-session")
            .put("pluginId", "anchor-plugin"));
  }

  private static PluginRegistry.Snapshot catalog(List<PluginRepository.Item> items) {
    PluginRegistry registry = new PluginRegistry();
    assertTrue(registry.completeQuery(registry.beginQuery(SOURCE), items, List.of(), false));
    return registry.snapshot();
  }

  /** 仅反射 State 的封装构造器；目录、条目和操作快照均走生产公开 API。 */
  private static PluginRepository.State state(
      PluginRegistry.Snapshot catalog,
      List<PluginOperationQueue.Operation> operations,
      boolean busy,
      String message,
      int percent)
      throws Exception {
    Constructor<PluginRepository.State> constructor =
        PluginRepository.State.class.getDeclaredConstructor(
            PluginRegistry.Snapshot.class,
            List.class,
            boolean.class,
            String.class,
            boolean.class,
            int.class);
    constructor.setAccessible(true);
    return constructor.newInstance(catalog, operations, busy, message, busy, percent);
  }

  private Fixture fixture() {
    return new ViewModelProvider(host.get()).get(Fixture.class);
  }

  private void mount(PluginRepository.State state) {
    fixture().states.setValue(state);
    page = new FixtureFragment();
    Bundle arguments = new Bundle();
    arguments.putBoolean("show_installed", true);
    page.setArguments(arguments);
    replacePage();
  }

  private void replacePage() {
    host.get()
        .getSupportFragmentManager()
        .beginTransaction()
        .replace(R.id.fragment_container, page, PAGE_TAG)
        .commitNow();
    layout();
    assertSame(page.requireView(), list());
    assertTrue(adapter().hasStableIds());
  }

  private void publish(PluginRepository.State state) {
    fixture().states.setValue(state);
    layout();
  }

  private RecyclerView list() {
    return page.find(R.id.pluginList);
  }

  private RecyclerView.Adapter<?> adapter() {
    return list().getAdapter();
  }

  private LinearLayoutManager layoutManager() {
    return (LinearLayoutManager) list().getLayoutManager();
  }

  private void layout() {
    // PAUSED 主线程只处理已到期任务，避免 EditText 光标/动画的无限定时回调。
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    View decor = host.get().getWindow().getDecorView();
    measureAndLayout(decor);
    measureAndLayout(list());
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    measureAndLayout(list());
    assertFalse("布局必须消费真实 Adapter 更新", list().hasPendingAdapterUpdates());
  }

  private static void measureAndLayout(View view) {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, WIDTH, HEIGHT);
  }

  private View rowAt(int position) {
    assertTrue("position 0 是页头，末项是页脚", position >= 1 && position < adapter().getItemCount() - 1);
    layoutManager().scrollToPositionWithOffset(position, 0);
    layout();
    RecyclerView.ViewHolder holder = list().findViewHolderForAdapterPosition(position);
    assertNotNull("插件行必须通过 RecyclerView 真实创建和绑定", holder);
    assertEquals(position, holder.getAdapterPosition());
    assertNotNull(holder.itemView.findViewById(R.id.pluginName));
    return holder.itemView;
  }

  private List<String> renderedNames() {
    List<String> names = new ArrayList<>();
    for (int position = 1; position < adapter().getItemCount() - 1; position++)
      names.add(text(rowAt(position).findViewById(R.id.pluginName)));
    return names;
  }

  private void assertEmptyText(String expected) {
    TextView empty = page.find(R.id.pluginEmpty);
    assertEquals(View.VISIBLE, empty.getVisibility());
    assertEquals(expected, text(empty));
  }

  private void chooseSort(PluginSort.Mode mode) {
    assertTrue(page.find(R.id.btnSort).performClick());
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
    assertNotNull(dialog);
    assertTrue(dialog.isShowing());
    // G1：排序入口改为底部抽屉，按文案点对应行（不再是单选 ListView）。
    int[] labels = {
      R.string.plugin_sort_az,
      R.string.plugin_sort_za,
      R.string.plugin_sort_enabled,
      R.string.plugin_sort_updates
    };
    View title =
        findText(
            dialog.getWindow().getDecorView(),
            page.requireContext().getString(labels[mode.ordinal()]));
    assertNotNull("排序抽屉必须包含选项：" + mode, title);
    // 文案在行内层叠容器里；点为整行（可点祖先）才是抽屉的选择动作。
    View row = title;
    while (row != null && !row.isClickable()) row = (View) row.getParent();
    assertNotNull(row);
    assertTrue(row.performClick());
    layout();
    assertFalse(dialog.isShowing());
  }

  private static View findText(View view, String expected) {
    if (view instanceof TextView text && expected.contentEquals(text.getText())) return view;
    if (view instanceof android.view.ViewGroup group) {
      for (int index = 0; index < group.getChildCount(); index++) {
        View found = findText(group.getChildAt(index), expected);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static String text(View view) {
    assertNotNull(view);
    return ((TextView) view).getText().toString();
  }

  private String rowStatus(int position) {
    View row = rowAt(position);
    return text(row.findViewById(R.id.pluginStatus))
        + "\n"
        + text(row.findViewById(R.id.pluginRuntime));
  }

  /** 与生产一致的胶囊底色：同一语义色 18% 填充（中性用 raised）。 */
  private int badgeFill(int toneColor) {
    return (page.requireContext().getColor(toneColor) & 0x00FFFFFF) | (0x2E << 24);
  }

  /** 徽章必须可见、文字完整、文字色与底色都取自声明的 shell_* 语义 token。 */
  private void assertBadge(View row, int id, String expectedText, int textColor, int background) {
    TextView badge = row.findViewById(id);
    assertEquals(expectedText, text(badge));
    assertEquals(View.VISIBLE, badge.getVisibility());
    assertEquals(page.requireContext().getColor(textColor), badge.getCurrentTextColor());
    assertNotNull("徽章必须有胶囊背景", badge.getBackground());
    assertNotNull("徽章底色必须由语义 token tint", badge.getBackgroundTintList());
    assertEquals(background, badge.getBackgroundTintList().getDefaultColor());
  }

  private Notifications observe() {
    Notifications observer = new Notifications();
    adapter().registerAdapterDataObserver(observer);
    return observer;
  }

  private static final class Event {
    final String kind;
    final int position, count, destination;
    final Object payload;

    Event(String kind, int position, int count, int destination, Object payload) {
      this.kind = kind;
      this.position = position;
      this.count = count;
      this.destination = destination;
      this.payload = payload;
    }
  }

  private static final class Notifications extends RecyclerView.AdapterDataObserver {
    int fullChanges;
    final List<Event> events = new ArrayList<>();

    @Override
    public void onChanged() {
      fullChanges++;
    }

    @Override
    public void onItemRangeChanged(int positionStart, int itemCount) {
      events.add(new Event("change", positionStart, itemCount, -1, null));
    }

    @Override
    public void onItemRangeChanged(int positionStart, int itemCount, Object payload) {
      events.add(new Event("change", positionStart, itemCount, -1, payload));
    }

    @Override
    public void onItemRangeInserted(int positionStart, int itemCount) {
      events.add(new Event("insert", positionStart, itemCount, -1, null));
    }

    @Override
    public void onItemRangeRemoved(int positionStart, int itemCount) {
      events.add(new Event("remove", positionStart, itemCount, -1, null));
    }

    @Override
    public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
      events.add(new Event("move", fromPosition, itemCount, toPosition, null));
    }

    void clear() {
      fullChanges = 0;
      events.clear();
    }

    void assertNone() {
      assertEquals("禁止 notifyDataSetChanged", 0, fullChanges);
      assertTrue("页头或无关操作不应通知插件条目", events.isEmpty());
    }

    void assertSingle(String kind, int position, int count) {
      assertEquals("禁止 notifyDataSetChanged", 0, fullChanges);
      assertEquals(1, events.size());
      Event event = events.get(0);
      assertEquals(kind, event.kind);
      assertEquals(position, event.position);
      assertEquals(count, event.count);
    }
  }
}
