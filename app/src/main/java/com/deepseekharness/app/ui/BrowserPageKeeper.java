package com.deepseekharness.app.ui;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import android.os.SystemClock;

import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.PreviewPageSession;

/**
 * 实验性页面保活（B-min / K1+K2）：应用作用域、单 owner 的 WebView 页面保管器。
 *
 * <p>Activity 被系统回收时只把 WebView 从视图树摘下并交给本对象，不 {@code destroy()}；新 Activity 以同一
 * generation/instance 接管同一 WebView，不重新 loadUrl。显式结束、服务换代、renderer 退出、内存回收、
 * 任务被移除与 origin-restore 都会 retire 并销毁页面。进程死亡/强杀/APK 更新时本对象随进程消失，
 * 仍由文档恢复兜底。
 *
 * <p>本类不是安全边界：页面在后台仍可能执行脚本。页面摘挂期间监听 Web 运行时身份变化，在停止/维护/
 * 备份/换代时销毁页面（K2）。
 */
public final class BrowserPageKeeper implements ComponentCallbacks2 {
  public static final String REASON_CONFIG = "config";
  public static final String REASON_SYSTEM = "system";
  public static final String REASON_FINISH = "finish";
  public static final String REASON_RENDERER = "renderer";
  public static final String REASON_IDENTITY = "identity";
  public static final String REASON_SESSION_CHANGED = "session-changed";
  public static final String REASON_WEB_SESSION = "web-session";
  public static final String REASON_ORIGIN_RESTORE = "origin-restore";
  public static final String REASON_RELOAD = "reload";
  public static final String REASON_INIT_FAILED = "init-failed";
  public static final String REASON_FALLBACK = "fallback";
  public static final String REASON_TRIM = "trim";
  public static final String REASON_TASK_REMOVED = "task-removed";
  public static final String REASON_EMPTY = "empty";

  interface Clock {
    long now();
  }

  private static BrowserPageKeeper instance;

  /**
   * 不依赖 DshaApp 的字段：DshaApp/HarnessService 属于运行时描述符钉住的 launcher 源，
   * 页面保活不应为两个钩子改变受管运行时身份。进程只保留最近一个 Application 的 keeper。
   */
  public static BrowserPageKeeper forApplication(Context source) {
    Context context = source;
    try {
      Context application = source.getApplicationContext();
      if (application != null) context = application;
    } catch (RuntimeException unavailable) {
      // 保持传入的 context；诊断仍可用。
    }
    synchronized (BrowserPageKeeper.class) {
      if (instance == null || instance.context != context) {
        instance = new BrowserPageKeeper(context, SystemClock::elapsedRealtime, true);
      }
      return instance;
    }
  }

  private final Context context;
  private final Clock clock;
  private WebPreviewActivity.Retained page;
  private java.lang.ref.WeakReference<WebPreviewActivity> owner =
      new java.lang.ref.WeakReference<>(null);
  private long detachedAt;
  private String detachedReason = "";
  private String retiredReason = "";
  private int attaches;
  private int detaches;
  private int retires;
  private final Runnable runtimeReadyChanged = this::onRuntimeReadyChanged;
  private boolean observingRuntime;

  BrowserPageKeeper(Context context, Clock clock, boolean registerCallbacks) {
    this.context = context;
    this.clock = clock;
    if (registerCallbacks) {
      try {
        context.registerComponentCallbacks(this);
      } catch (RuntimeException unavailable) {
        // 页面保活是尽力适配：拿不到应用回调时只是失去 trim 兜底。
      }
    }
  }

  /** Activity 借阅应用级唯一页面；没有页面时新建一个空壳，页面创建仍由 Activity 完成。 */
  public synchronized WebPreviewActivity.Retained acquire(WebPreviewActivity activity) {
    boolean adopted = page != null;
    boolean wasDetached = adopted && detachedAt > 0;
    String resumedFrom = detachedReason;
    if (page == null) page = new WebPreviewActivity.Retained();
    if (page.view != null && page.view.getParent() instanceof android.view.ViewGroup parent)
      parent.removeView(page.view);
    page.keeper = this;
    page.owner = new java.lang.ref.WeakReference<>(activity);
    owner = new java.lang.ref.WeakReference<>(activity);
    detachedAt = 0;
    detachedReason = "";
    if (wasDetached) {
      attaches++;
      WebPageDiagnostics.keepAlive(context, "ATTACH", resumedFrom, generationOf(page), page.ready);
    }
    observeRuntime();
    return page;
  }

  /**
   * Activity 销毁但页面要保留（配置变更或系统回收）。只清 owner；视图的摘除与客户端重挂由 Activity 完成。
   * 空壳页面没有可保留的内容，直接 retire。
   */
  public synchronized boolean detach(WebPreviewActivity activity, String reason) {
    if (page == null) return false;
    if (owner.get() != activity) return false;
    owner.clear();
    if (page.view == null) {
      retireLocked(REASON_EMPTY);
      return false;
    }
    detachedAt = clock.now();
    detachedReason = com.deepseekharness.app.util.WebPageDiagnosticText.reasonCode(reason);
    WebPageDiagnostics.keepAlive(context, "DETACH", reason, generationOf(page), page.ready);
    detaches++;
    return true;
  }

  /** 当前 Activity 显式结束页面；owner 已换成别的 Activity 时拒绝，避免旧实例销毁新页面。 */
  public synchronized boolean retireFrom(WebPreviewActivity activity, String reason) {
    if (page == null) return false;
    if (owner.get() != activity) return false;
    retireLocked(reason);
    return true;
  }

  /** renderer 在 Activity 摘挂空窗内退出：只有 keeper 还持有该页面时才允许 retire。 */
  synchronized void retireDetached(WebPreviewActivity.Retained candidate, String reason) {
    if (page == null || page != candidate || owner.get() != null) return;
    retireLocked(reason);
  }

  /** 应用级销毁（停止 Web、维护、origin 恢复等 K2 接入点）。 */
  public synchronized boolean retire(String reason) {
    if (page == null) return false;
    retireLocked(reason);
    return true;
  }

  /** 仅摘挂状态响应系统内存压力；可见页面交给系统/错误面板，不在这里静默销毁。 */
  public synchronized boolean trimMemory(int level) {
    boolean pressure =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE;
    if (!pressure || page == null || owner.get() != null) return false;
    retireLocked(REASON_TRIM);
    return true;
  }

  /** 任务被系统移除：进程通常也会结束，但不能依赖进程死亡来释放页面。 */
  public synchronized boolean onTaskRemoved() {
    if (page == null) return false;
    retireLocked(REASON_TASK_REMOVED);
    return true;
  }

  /** 只退役已摘挂的页面；仍附着在 Activity 的页面交给该 Activity 的 finish/sync 流程收尾。 */
  public synchronized boolean retireDetachedPage(String reason) {
    if (page == null || owner.get() != null) return false;
    retireLocked(reason);
    return true;
  }

  /** 进程内是否还持有任何页面（含 attached）；origin-restore 等迁移等待用它确认页面真正销毁。 */
  public static boolean hasKeptPage() {
    BrowserPageKeeper current;
    synchronized (BrowserPageKeeper.class) {
      current = instance;
    }
    return current != null && current.hasPage();
  }

  private synchronized boolean hasPage() {
    return page != null;
  }

  public synchronized WebPreviewActivity.Retained page() {
    return page;
  }

  public synchronized boolean isAttached() {
    return page != null && owner.get() != null;
  }

  /** 当前页面是否仍归这个 Activity；新实例先接管后，旧实例的 onDestroy 只能放手。 */
  public synchronized boolean owns(WebPreviewActivity activity) {
    return page != null && owner.get() == activity;
  }

  public synchronized boolean hasDetachedPage() {
    return page != null && page.view != null && owner.get() == null;
  }

  public synchronized long detachedAt() {
    return detachedAt;
  }

  public synchronized String lastDetachedReason() {
    return detachedReason;
  }

  public synchronized String lastRetiredReason() {
    return retiredReason;
  }

  public synchronized int attaches() {
    return attaches;
  }

  public synchronized int detaches() {
    return detaches;
  }

  public synchronized int retires() {
    return retires;
  }

  @Override
  public void onTrimMemory(int level) {
    trimMemory(level);
  }

  @Override
  public void onLowMemory() {
    trimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
  }

  @Override
  public void onConfigurationChanged(Configuration newConfig) {}

  /**
   * 摘挂页面期间监听 Web 运行时身份：停止/维护/备份/换代时 ready 身份消失或变化，旧页面必须销毁，
   * 不能等下一次 attach 才由 Activity 发现。附着中的页面由 Activity 自己的同步流程处理。
   */
  private void observeRuntime() {
    if (observingRuntime) return;
    try {
      HarnessController.get(context).addReadyWebPageListener(runtimeReadyChanged);
      observingRuntime = true;
    } catch (RuntimeException | LinkageError unavailable) {
      // 页面保活是尽力适配；失去运行时监听时仍有 Activity 生命周期与 trim 兜底。
    }
  }

  private void onRuntimeReadyChanged() {
    WebPreviewActivity.Retained snapshot;
    synchronized (this) {
      snapshot = page;
      if (snapshot == null || owner.get() != null) return;
    }
    PreviewPageSession.Identity identity = snapshot.pageSession.identity();
    HarnessController controller;
    try {
      controller = HarnessController.get(context);
      PreviewPageSession.Identity current = controller.getReadyWebPageIdentity();
      boolean keep =
          current != null ? current.equals(identity) : controller.isWebSessionRetained(identity);
      if (keep) return;
    } catch (RuntimeException | LinkageError unavailable) {
      return;
    }
    synchronized (this) {
      // 回调与 attach 都在主线程；仍复核一次，避免迟到的运行时事件销毁新 owner 的页面。
      if (page != snapshot || owner.get() != null) return;
      retireLocked(REASON_WEB_SESSION);
    }
  }

  private void retireLocked(String reason) {
    WebPreviewActivity.Retained retiring = page;
    long generation = generationOf(retiring);
    boolean ready = retiring != null && retiring.ready;
    page = null;
    detachedAt = 0;
    detachedReason = "";
    retiredReason = com.deepseekharness.app.util.WebPageDiagnosticText.reasonCode(reason);
    retires++;
    owner.clear();
    if (retiring == null) return;
    WebPageDiagnostics.keepAlive(context, "RETIRE", retiredReason, generation, ready);
    retiring.keeper = null;
    retiring.owner.clear();
    if (retiring.view != null && retiring.view.getParent() instanceof android.view.ViewGroup parent)
      parent.removeView(retiring.view);
    retiring.dispose();
  }

  static long generationOf(WebPreviewActivity.Retained candidate) {
    if (candidate == null) return -1;
    var identity = candidate.pageSession.identity();
    return identity == null ? -1 : identity.generation();
  }
}
