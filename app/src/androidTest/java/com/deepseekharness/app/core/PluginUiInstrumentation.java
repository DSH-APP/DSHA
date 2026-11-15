package com.deepseekharness.app.core;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import com.deepseekharness.app.R;
import com.deepseekharness.app.plugins.PluginManagementEngine;
import com.deepseekharness.app.ui.MainActivity;
import com.deepseekharness.app.ui.PluginFragment;
import java.lang.reflect.Field;
import java.util.function.BooleanSupplier;

/** 显式设备审计入口：生产应用级订阅、页面重建与过滤状态保存。 不启动下载/inspect（其成功路径会自动安装），不取消、清理或提交任何用户插件任务。 */
public final class PluginUiInstrumentation extends Instrumentation {
  private volatile MainActivity page;
  private final Application.ActivityLifecycleCallbacks lifecycle =
      new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity a, Bundle b) {}

        public void onActivityStarted(Activity a) {}

        public void onActivityResumed(Activity a) {
          if (a instanceof MainActivity) page = (MainActivity) a;
        }

        public void onActivityPaused(Activity a) {}

        public void onActivityStopped(Activity a) {}

        public void onActivitySaveInstanceState(Activity a, Bundle b) {}

        public void onActivityDestroyed(Activity a) {}
      };

  @Override
  public void onCreate(Bundle args) {
    super.onCreate(args);
    start();
  }

  private void require(boolean ok, String message) {
    if (!ok) throw new AssertionError(message);
  }

  private void ui(Runnable action) {
    Throwable[] failure = {null};
    runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure[0] = error;
          }
        });
    if (failure[0] != null) throw new AssertionError(failure[0]);
    waitForIdleSync();
  }

  private void until(BooleanSupplier ready, String reason) throws Exception {
    long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
    boolean[] complete = {false};
    while (!complete[0] && android.os.SystemClock.elapsedRealtime() < deadline) {
      ui(() -> complete[0] = ready.getAsBoolean());
      if (!complete[0]) Thread.sleep(100);
    }
    require(complete[0], reason);
  }

  private PluginFragment fragment() {
    var fragment = page.getSupportFragmentManager().findFragmentById(R.id.fragment_container);
    return fragment instanceof PluginFragment ? (PluginFragment) fragment : null;
  }

  private <T extends View> T control(int id) {
    PluginFragment fragment = fragment();
    if (fragment == null || fragment.getView() == null) return null;
    T result = fragment.requireView().findViewById(id);
    if (result != null) return result;
    try {
      for (String name : new String[] {"header", "footer"}) {
        Field field = PluginFragment.class.getDeclaredField(name);
        field.setAccessible(true);
        View part = (View) field.get(fragment);
        if (part != null && (result = part.findViewById(id)) != null) return result;
      }
      return null;
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  private Object pageEngine() {
    try {
      Field field = PluginFragment.class.getDeclaredField("repository");
      field.setAccessible(true);
      return field.get(fragment());
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }

  @Override
  public void onStart() {
    Bundle output = new Bundle();
    Application app = (Application) getTargetContext().getApplicationContext();
    app.registerActivityLifecycleCallbacks(lifecycle);
    try {
      startActivitySync(
          new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      until(() -> page != null, "主界面未就绪");
      ui(
          () ->
              ((com.google.android.material.bottomnavigation.BottomNavigationView)
                      page.findViewById(R.id.bottom_nav))
                  .setSelectedItemId(R.id.nav_plugins));
      until(() -> control(R.id.btnInstalled) != null, "插件页没有加载");
      PluginManagementEngine[] engine = {null};
      ui(
          () -> {
            engine[0] = PluginManagementEngine.get(page);
            require(pageEngine() == engine[0], "页面未使用生产应用级引擎");
            control(R.id.btnInstalled).performClick();
            ((EditText) control(R.id.pluginSearch)).setText("ui-audit-filter");
            ((CheckBox) control(R.id.chkHideBuiltin)).setChecked(true);
          });
      MainActivity old = page;
      ui(old::recreate);
      until(() -> page != old && control(R.id.pluginSearch) != null, "页面重建失败");
      ui(
          () -> {
            require(PluginManagementEngine.get(page) == engine[0], "重建后应用级引擎身份变化");
            require(pageEngine() == engine[0], "重建后的页面订阅了不同引擎");
            require(
                ((EditText) control(R.id.pluginSearch))
                    .getText()
                    .toString()
                    .equals("ui-audit-filter"),
                "重建后搜索条件丢失");
            require(((CheckBox) control(R.id.chkHideBuiltin)).isChecked(), "重建后隐藏条件丢失");
            require(control(R.id.btnInstalled).isSelected(), "重建后管理标签丢失");
          });
      output.putString("result", "PASS");
      output.putString("coverage", "生产应用级引擎身份与页面订阅、Activity重建保留搜索/隐藏/标签；未执行下载、安装、启停或取消任务");
    } catch (Throwable error) {
      output.putString("result", "FAIL: " + android.util.Log.getStackTraceString(error));
    } finally {
      if (page != null) {
        Activity current = page;
        ui(current::finish);
      }
      app.unregisterActivityLifecycleCallbacks(lifecycle);
    }
    finish(
        "PASS".equals(output.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED,
        output);
  }
}
