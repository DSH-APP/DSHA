package com.deepseekharness.app.ui;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.EntryTarget;

/**
 * 常驻通知的应用入口中转页：透明、无动画，在自己的独立任务里决定去向后立即结束。
 *
 * <ul>
 *   <li>启动目标为主界面（默认）、尚未完成首次设置或已登记启动恢复：原样转交通知原本的 Main 入口，行为不变；
 *   <li>启动目标为 Web（含原本带 open_web 的 AI 任务通知）：先回到仍存活的原网页；Web 已就绪但没有页面时直接打开 Web；否则交给 Main
 *       的直启流程（加载页 → 等鉴权 → Web），环境门禁照常优先。
 * </ul>
 *
 * <p>中转页不和 Main 共用任务：若用 Main 的任务承载，点通知时会先把 Main 带到前台，再切到 Web，
 * 正是要避免的闪屏。
 */
public final class AppEntryRouterActivity extends Activity {
  /** 通知原本的 Main 入口（homeIntent / navigationIntent + open_web）。 */
  static final String EXTRA_LEGACY = "dsha-entry-legacy";

  /** 交给 Main 的应用入口：按启动目标处理，而不是“回到原生启动页”。 */
  static final String EXTRA_APP_ENTRY = "dsha-app-entry";

  /**
   * 通知正文用的 PendingIntent：请求码与旗标沿用调用方原值，FLAG_UPDATE_CURRENT 时仍替换旧通知的入口。
   *
   * @param legacy 原本直接交给 Main 的入口；偏好为主界面时原样使用
   */
  public static PendingIntent pending(Context context, int requestCode, Intent legacy, int flags) {
    Intent route =
        new Intent(context, AppEntryRouterActivity.class)
            .putExtra(EXTRA_LEGACY, legacy)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    return PendingIntent.getActivity(context, requestCode, route, flags);
  }

  @Override
  protected void onCreate(Bundle saved) {
    super.onCreate(saved);
    overridePendingTransition(0, 0);
    try {
      if (saved == null) route();
    } catch (RuntimeException unavailable) {
      android.util.Log.w("DSHA", "APP_ENTRY_ROUTE: " + unavailable.getClass().getSimpleName());
      // 任何异常都回到原生启动页，不留下空白中转页。
      startActivity(MainActivity.homeIntent(this));
    }
    finishAndRemoveTask();
    overridePendingTransition(0, 0);
  }

  private void route() {
    ConfigStore config = new ConfigStore(this);
    Intent legacy = legacy();
    if (!EntryTarget.notificationWeb(
        config.getEntryTarget(), config.isWelcomed(), config.isStartupRecoveryRequested())) {
      android.util.Log.i("DSHA", "APP_ENTRY_ROUTE MAIN");
      startActivity(legacy);
      return;
    }
    // 1. 仍存活的原网页（含正停在 Web 时拉下通知）：整屏带回，不经过 Main。
    if (BrowserTasks.returnToLiveBrowser(this)) {
      android.util.Log.i("DSHA", "APP_ENTRY_ROUTE RETURN_WEB");
      return;
    }
    // 2. Web 已就绪但没有可返回的页面（例如 Main 与网页已被系统回收、服务仍在）：直接打开 Web。
    HarnessController controller = HarnessController.get(this);
    String url = controller.getWebAuthUrl();
    if (!BrowserTasks.returnPending()
        && url != null
        && !url.isEmpty()
        && !controller.isStopping()
        && !controller.isUserStopped()) {
      android.util.Log.i("DSHA", "APP_ENTRY_ROUTE OPEN_WEB");
      startActivity(WebPreviewActivity.intent(this, url, null));
      return;
    }
    // 3. 其余情况交给 Main：未就绪时走直启加载页，已就绪时按既有返回路由或停在启动页。
    android.util.Log.i("DSHA", "APP_ENTRY_ROUTE MAIN_ENTRY");
    // 不带 NATIVE_HOME：那是“我要回原生页面”，会撤销待返回的原网页。
    Intent entry =
        MainActivity.navigationIntent(this)
            .putExtra(EXTRA_APP_ENTRY, true)
            .putExtra("open_launch", true);
    entry.removeExtra(BrowserTasks.NATIVE_HOME);
    startActivity(entry);
  }

  @SuppressWarnings("deprecation") // API 33 前没有类型化重载。
  private Intent legacy() {
    Intent legacy = getIntent().getParcelableExtra(EXTRA_LEGACY);
    // 只转交到本应用的 Main；缺失或被替换的入口一律回到原生启动页。
    if (legacy == null
        || legacy.getComponent() == null
        || !getPackageName().equals(legacy.getComponent().getPackageName())
        || !MainActivity.class.getName().equals(legacy.getComponent().getClassName()))
      return MainActivity.homeIntent(this);
    return legacy.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
  }
}
