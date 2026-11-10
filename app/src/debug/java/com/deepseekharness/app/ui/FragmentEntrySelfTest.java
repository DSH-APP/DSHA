package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.PluginRepository;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/**
 * 调试入口：在 instrumentation 工作线程调用 run(driver, 空测试 Activity, 容器 ID)。 替换打开页面/刷新边界；鉴权由 PreviewAuth
 * 定向测试覆盖。 不启动 Web、不更改实际插件，调用方提供独立测试 Activity。
 */
public final class FragmentEntrySelfTest {
  private FragmentEntrySelfTest() {}

  public static String run(Instrumentation driver, FragmentActivity host, int container)
      throws Exception {
    return run(driver, host, container, ignored -> {});
  }

  static String run(
      Instrumentation driver,
      FragmentActivity host,
      int container,
      java.util.function.Consumer<String> result)
      throws Exception {
    EntryProbe entry = new EntryProbe();
    PluginProbe plugin = new PluginProbe();
    try {
      show(driver, host, container, entry);
      main(
          driver,
          () -> {
            for (int i = 0; i < 30; i++)
              entry.requireView().findViewById(R.id.launch_start).performClick();
            require(!entry.requireView().findViewById(R.id.launch_start).isEnabled(), "进入中按钮未禁用");
            require(
                ((TextView) entry.requireView().findViewById(R.id.launch_status))
                    .getText()
                    .toString()
                    .contains("打开"),
                "缺少打开页面状态");
          });
      require(entry.opened.get() == 1, "连点未保持单次导航");
      main(
          driver,
          () -> {
            for (int i = 0; i < 30; i++)
              entry.requireView().findViewById(R.id.launch_start).performClick();
          });
      require(entry.opened.get() == 1, "已打开后重复进入");

      // 页面返回/重建允许显式重入；Launch 不再拥有鉴权工作线程或迟到的 HTTP 回调。
      rebuild(driver, host, entry);
      click(driver, entry);
      require(entry.opened.get() == 2, "重建后不能显式重新进入");
      rebuild(driver, host, entry);
      entry.throwOpen = true;
      click(driver, entry);
      untilUi(driver, () -> entry.requireView().findViewById(R.id.launch_start).isEnabled());
      main(
          driver,
          () ->
              require(
                  ((TextView) entry.requireView().findViewById(R.id.launch_status))
                      .getText()
                      .toString()
                      .contains("无法打开"),
                  "Activity 异常未返回可重试状态"));
      require(entry.opened.get() == 2, "Activity 导航异常仍计入打开");
      entry.throwOpen = false;
      click(driver, entry);
      require(entry.opened.get() == 3, "Activity 导航异常后不能重试");

      rebuild(driver, host, entry);
      main(
          driver,
          () -> {
            entry.generationReads = 0;
            entry.changeGeneration = true;
            enter(entry);
          });
      untilUi(driver, () -> entry.requireView().findViewById(R.id.launch_start).isEnabled());
      require(entry.opened.get() == 3, "Web 代次变化后仍打开旧页面");
      main(
          driver,
          () -> {
            entry.changeGeneration = false;
            entry.urlReads = 0;
            entry.changeUrl = true;
            enter(entry);
          });
      untilUi(driver, () -> entry.requireView().findViewById(R.id.launch_start).isEnabled());
      require(entry.opened.get() == 3, "Web 地址变化后仍打开旧页面");
      result.accept("launch=PASS：连点单次导航、重建重入、Activity异常可重试、派发前代次/地址围栏；鉴权由PreviewAuth单独验证");

      Bundle installed = new Bundle();
      installed.putBoolean("show_installed", true);
      plugin.setArguments(installed);
      show(driver, host, container, plugin);
      until(() -> plugin.refreshes.get() == 1);
      untilUi(driver, () -> plugin.showsSafeMode(false));
      rebuild(driver, host, plugin);
      require(plugin.refreshes.get() == 1, "普通返回触发多余全量刷新");
      main(
          driver,
          () -> {
            host.getSupportFragmentManager().beginTransaction().detach(plugin).commitNow();
            plugin.fixtureDirty = true;
            plugin.fixtureSafeMode = true;
            plugin.blocked = true;
            host.getSupportFragmentManager().beginTransaction().attach(plugin).commitNow();
          });
      Thread.sleep(650);
      require(plugin.refreshes.get() == 1, "安全启动未完成就刷新了旧状态");
      main(driver, () -> require(plugin.showsSafeMode(false), "未刷新前测试缓存不正确"));
      main(
          driver,
          () -> {
            plugin.blocked = false;
            plugin.publishFixture(); // 模拟应用级引擎主动发布结果；页面没有 500ms 门禁轮询。
          });
      until(() -> plugin.refreshes.get() == 2);
      untilUi(driver, () -> plugin.showsSafeMode(true));
      rebuild(driver, host, plugin);
      require(plugin.refreshes.get() == 2, "同一失效被重复刷新");
      main(
          driver,
          () -> {
            host.getSupportFragmentManager().beginTransaction().detach(plugin).commitNow();
            plugin.fixtureSafeMode = false;
            plugin.fixtureDirty = true;
            host.getSupportFragmentManager().beginTransaction().attach(plugin).commitNow();
          });
      until(() -> plugin.refreshes.get() == 3);
      untilUi(driver, () -> plugin.showsSafeMode(false));
      rebuild(driver, host, plugin);
      require(plugin.refreshes.get() == 3, "恢复后的普通返回重复刷新");
      result.accept("plugin=PASS：内存夹具的安全状态、启用开关、恢复按钮同步，刷新次数 1→2→3");
      return "PASS：Launch 单次导航/重建重入/Activity异常重试/代次与地址围栏；Plugin 内存夹具安全状态、开关和恢复按钮同步，刷新次数 1→2→3";
    } finally {
      main(
          driver,
          () -> {
            Fragment current = host.getSupportFragmentManager().findFragmentById(container);
            if (current != null)
              host.getSupportFragmentManager().beginTransaction().remove(current).commitNow();
          });
    }
  }

  private static void show(
      Instrumentation driver, FragmentActivity host, int container, Fragment fragment) {
    main(
        driver,
        () ->
            host.getSupportFragmentManager()
                .beginTransaction()
                .replace(container, fragment)
                .commitNow());
    driver.waitForIdleSync();
  }

  private static void rebuild(Instrumentation driver, FragmentActivity host, Fragment fragment) {
    main(
        driver,
        () -> {
          host.getSupportFragmentManager().beginTransaction().detach(fragment).commitNow();
          host.getSupportFragmentManager().beginTransaction().attach(fragment).commitNow();
        });
    driver.waitForIdleSync();
  }

  private static void enter(EntryProbe entry) {
    try {
      java.lang.reflect.Method enter = LaunchFragment.class.getDeclaredMethod("enterWeb");
      enter.setAccessible(true);
      enter.invoke(entry);
    } catch (Exception error) {
      throw new AssertionError("调用进入边界失败", error);
    }
  }

  private static void click(Instrumentation driver, EntryProbe entry) {
    main(driver, () -> entry.requireView().findViewById(R.id.launch_start).performClick());
  }

  private static void until(BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
    require(condition.getAsBoolean(), "等待异步结果超时");
  }

  private static void untilUi(Instrumentation driver, BooleanSupplier condition) throws Exception {
    until(
        () -> {
          boolean[] result = {false};
          main(driver, () -> result[0] = condition.getAsBoolean());
          return result[0];
        });
  }

  private static void require(boolean okay, String message) {
    if (!okay) throw new AssertionError(message);
  }

  /** 把断言传回 instrumentation 线程，避免主线程异常直接杀掉目标进程。 */
  private static void main(Instrumentation driver, Runnable work) {
    Throwable[] failure = {null};
    driver.runOnMainSync(
        () -> {
          try {
            work.run();
          } catch (Throwable error) {
            failure[0] = error;
          }
        });
    if (failure[0] != null) throw new AssertionError("主线程自测失败", failure[0]);
  }

  public static class EntryProbe extends LaunchFragment {
    final AtomicInteger opened = new AtomicInteger();
    boolean throwOpen, changeGeneration, changeUrl;
    int generationReads, urlReads;

    @Override
    String webEntryUrl() {
      return changeUrl && urlReads++ > 0
          ? "http://127.0.0.1:3081/?token=changed-fixture"
          : "http://127.0.0.1:3080/?token=debug-fixture";
    }

    @Override
    long webEntryGeneration() {
      return changeGeneration && generationReads++ > 0 ? 8 : 7;
    }

    @Override
    void openWebEntry(Activity activity, String url, String cookie) {
      require(cookie == null, "Launch 不应传递预鉴权 cookie");
      if (throwOpen) throw new IllegalStateException("调试 Activity 异常");
      opened.incrementAndGet();
    }
  }

  /** 构造纯内存目录快照，不创建生产/Activity Repository，不触发后台执行。 */
  static PluginRepository.State fixtureState(
      List<PluginRepository.Item> items, boolean busy, String message, boolean safeMode)
      throws Exception {
    var registry = new com.deepseekharness.app.plugins.PluginRegistry();
    var source =
        new com.deepseekharness.app.plugins.PluginSourceIdentity(
            new java.io.File("/fixture/plugin-ui/data"),
            "memory-data",
            0,
            new java.io.File("/fixture/plugin-ui/rootfs"),
            "memory-rootfs",
            0,
            "fixture",
            0);
    registry.completeQuery(registry.beginQuery(source), items, Collections.emptyList(), safeMode);
    Constructor<PluginRepository.State> state =
        PluginRepository.State.class.getDeclaredConstructor(
            com.deepseekharness.app.plugins.PluginRegistry.Snapshot.class,
            List.class,
            boolean.class,
            String.class,
            boolean.class,
            int.class);
    state.setAccessible(true);
    return state.newInstance(
        registry.snapshot(), Collections.emptyList(), busy, message, false, -1);
  }

  public static class PluginProbe extends PluginFragment {
    final AtomicInteger refreshes = new AtomicInteger();
    final MutableLiveData<PluginRepository.State> fixture = new MutableLiveData<>();
    volatile boolean blocked;
    boolean fixtureSafeMode, fixtureDirty = true;
    volatile Throwable fixtureFailure;

    @Override
    com.deepseekharness.app.plugins.PluginManagementEngine managementEngine() {
      return null;
    }

    @Override
    androidx.lifecycle.LiveData<PluginRepository.State> installedState() {
      return fixture;
    }

    @Override
    boolean safeModeActive() {
      return fixture.getValue() != null && fixture.getValue().catalog.safeMode;
    }

    @Override
    void syncInstalledState() {
      if (!blocked && fixtureDirty) publishFixture();
    }

    void publishFixture() {
      try {
        Constructor<PluginRepository.Item> item =
            PluginRepository.Item.class.getDeclaredConstructor(JSONObject.class);
        item.setAccessible(true);
        List<PluginRepository.Item> items =
            Collections.singletonList(
                item.newInstance(
                    new JSONObject()
                        .put("name", "dsha-debug-fixture")
                        .put("description", "只存在于测试页面内存")
                        .put("version", "0.0.0")
                        .put("available", true)
                        .put("enabled", !fixtureSafeMode)));
        fixture.setValue(
            fixtureState(
                items, false, fixtureSafeMode ? "测试夹具：安全模式" : "测试夹具：普通模式", fixtureSafeMode));
        fixtureDirty = false;
        refreshes.incrementAndGet();
      } catch (Exception error) {
        fixtureFailure = error;
        refreshes.incrementAndGet();
      }
    }

    boolean showsSafeMode(boolean safe) {
      if (fixtureFailure != null) throw new AssertionError("发布插件夹具失败", fixtureFailure);
      View root = getView();
      if (root == null || fixture.getValue() == null || fixture.getValue().catalog.safeMode != safe)
        return false;
      RecyclerView list = root.findViewById(R.id.pluginList);
      // 恢复按钮在 header 的安全模式警示条里；插件从位置 1 开始，位置 0 是 header。
      if ((find(R.id.btnPluginRestore).getVisibility() == View.VISIBLE) != safe) return false;
      RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(1);
      if (holder == null) {
        list.scrollToPosition(1);
        return false;
      }
      android.widget.CompoundButton toggle = holder.itemView.findViewById(R.id.pluginSwitch);
      TextView status = holder.itemView.findViewById(R.id.pluginStatus);
      return toggle != null
          && toggle.isChecked() != safe
          && status.getText().toString().contains(safe ? "已禁用" : "已启用");
    }
  }
}
