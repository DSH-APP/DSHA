package com.deepseekharness.app.ui;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.PluginRepository;
import com.deepseekharness.app.plugins.PluginManagementEngine;

/** 显式设备审计入口：真实插件页面检测与强刷；不提交安装或启停意图。 */
public final class PluginListAudit extends Instrumentation {
  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private PluginRepository.State awaitCatalog(PluginManagementEngine engine, long afterVersion)
      throws Exception {
    long deadline = android.os.SystemClock.elapsedRealtime() + 90000;
    while (android.os.SystemClock.elapsedRealtime() < deadline) {
      PluginRepository.State state = engine.state().getValue();
      if (state != null && state.catalog != null && state.catalog.version > afterVersion) {
        if (!state.catalog.loading && !state.catalog.error.isEmpty())
          throw new AssertionError(state.catalog.error);
        if (state.catalog.known && !state.catalog.loading && !state.catalog.stale) return state;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("插件目录未在期限内完成核验");
  }

  @Override
  public void onStart() {
    Bundle result = new Bundle();
    FragmentSessionTestActivity page = null;
    try {
      ActivityMonitor monitor =
          addMonitor(FragmentSessionTestActivity.class.getName(), null, false);
      runOnMainSync(
          () ->
              getTargetContext()
                  .startActivity(
                      new Intent(getTargetContext(), FragmentSessionTestActivity.class)
                          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
      page = (FragmentSessionTestActivity) waitForMonitorWithTimeout(monitor, 15000);
      removeMonitor(monitor);
      if (page == null) throw new AssertionError("插件测试宿主未打开");
      FragmentSessionTestActivity host = page;
      PluginFragment fragment = new PluginFragment();
      Bundle installed = new Bundle();
      installed.putBoolean("show_installed", true);
      fragment.setArguments(installed);
      PluginManagementEngine[] engine = {null};
      runOnMainSync(
          () -> {
            engine[0] = PluginManagementEngine.get(host);
            host.getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commitNow();
            if (fragment.installedState() != engine[0].state())
              throw new AssertionError("页面未订阅生产应用级目录");
          });
      PluginRepository.State first = awaitCatalog(engine[0], -1);
      runOnMainSync(() -> fragment.find(R.id.btnRefresh).performClick());
      PluginRepository.State state = awaitCatalog(engine[0], first.catalog.version);
      waitForIdleSync();
      java.util.Set<String> expected = new java.util.LinkedHashSet<>();
      for (String name : com.deepseekharness.app.util.BuiltinPlugins.DEFAULT_BUILTINS)
        if (!com.deepseekharness.app.util.BuiltinPlugins.internal(name)) expected.add(name);
      java.util.Set<String> actual = new java.util.LinkedHashSet<>();
      for (PluginRepository.Item item : state.items)
        if (item.builtin) {
          actual.add(item.name);
          if (!item.available) throw new AssertionError("内置组件不可用：" + item.name);
        }
      if (!actual.equals(expected))
        throw new AssertionError("内置组件集合错误：expected=" + expected + ", actual=" + actual);
      String[] shown = {null};
      runOnMainSync(
          () -> shown[0] = ((TextView) fragment.find(R.id.statusText)).getText().toString());
      if (!shown[0].equals(com.deepseekharness.app.util.UiStateText.render(state.message)))
        throw new AssertionError("页面未显示实际检测结果");
      result.putString("result", "PASS：生产应用级目录、真实页面确保载入与显式强刷、可见内置组件核验");
    } catch (Throwable error) {
      result.putString("failure", android.util.Log.getStackTraceString(error));
    } finally {
      if (page != null) {
        FragmentSessionTestActivity host = page;
        runOnMainSync(host::finish);
      }
      finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
  }
}
