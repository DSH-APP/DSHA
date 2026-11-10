package com.deepseekharness.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShizukuChannelStatus;
import com.deepseekharness.app.util.UiText;
import rikka.shizuku.Shizuku;

/** Provider 接收服务 Binder，Application 建立监听；不依赖 ADB 开关或 Ubuntu 环境。 */
public final class ShizukuShell {
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static final Object LOCK = new Object();
  private static volatile Context context;
  private static volatile IShellService service;
  private static volatile ServiceConnection connection;
  private static volatile String failure = "";
  // 最近一次用户点击授权的结果，只影响文案；Binder 断开或重新请求时清空。
  private static volatile ShizukuChannelStatus.Outcome outcome = ShizukuChannelStatus.Outcome.NONE;
  private static Shizuku.UserServiceArgs arguments;
  private static boolean listening;
  private static int requestCode = 9527;

  private ShizukuShell() {}

  public static void init(Context ctx) {
    if (ctx != null) context = ctx.getApplicationContext();
    synchronized (LOCK) {
      if (listening || context == null) return;
      listening = true;
    }
    try {
      Shizuku.addBinderDeadListener(
          () ->
              MAIN.post(
                  () -> {
                    outcome = ShizukuChannelStatus.Outcome.NONE;
                    disconnect(UiText.text("Shizuku 服务已停止"), false);
                  }));
      Shizuku.addBinderReceivedListenerSticky(() -> MAIN.post(() -> ensureBound(context)));
    } catch (Throwable e) {
      synchronized (LOCK) {
        listening = false;
      }
      fail(e);
    }
  }

  public static boolean isInstalled(Context ctx) {
    return isAvailable() || ShizukuManagerCompat.manager(ctx) != null;
  }

  public static boolean isAvailable() {
    try {
      return Shizuku.pingBinder();
    } catch (Throwable e) {
      return false;
    }
  }

  public static boolean hasPermission() {
    try {
      return isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    } catch (Throwable e) {
      return false;
    }
  }

  public static boolean isReady() {
    IShellService current = service;
    return hasPermission() && current != null && current.asBinder().pingBinder();
  }

  public static String status() {
    return "binder="
        + isAvailable()
        + ",permission="
        + hasPermission()
        + ",bound="
        + isReady()
        + ",binding="
        + (connection != null && service == null)
        + (outcome == ShizukuChannelStatus.Outcome.NONE ? "" : ",request=" + outcome)
        + (failure.isEmpty() ? "" : ",error=" + failure);
  }

  /**
   * 当前授权步骤。权限只认服务端 checkSelfPermission 的真实结果；管理器可见性按本进程所在用户解析， 分身 / 工作资料里看不到机主空间的管理器时不能就地弹授权页。
   */
  public static ShizukuChannelStatus.Step step(Context ctx) {
    boolean alive = isAvailable();
    boolean granted = alive && hasPermission();
    return ShizukuChannelStatus.step(
        alive,
        granted,
        granted && isReady(),
        !failure.isEmpty(),
        ctx != null && ShizukuManagerCompat.manager(ctx) != null,
        ShizukuChannelStatus.isProfileUser(android.os.Process.myUid()));
  }

  public static String userStatus(Context ctx) {
    ShizukuChannelStatus.Step step = step(ctx);
    switch (step) {
      case INSTALL:
        return UiText.text("尚未安装 Shizuku。安装并启动后可在这里授权。");
      case PROFILE_NO_SERVICE:
        return UiText.choose(
            "DSHA 正运行在应用分身或工作资料中，本空间没有 Shizuku / Stellar，服务也尚未连接。请在机主空间启动管理器服务后回到这里刷新。",
            "DSHA is running in an app clone or work profile that has no Shizuku or Stellar, and the service is not connected. Start the manager service in the main profile, then come back and refresh.");
      case CONNECT:
        if (ShizukuManagerCompat.isStellar(ctx))
          return UiText.choose(
              "已安装 Stellar，Shizuku 兼容层尚未连接。请在 Stellar 中启动服务并确认已开启「Shizuku 兼容层」，回到 DSHA 后点授权。",
              "Stellar is installed, but the Shizuku compatibility layer is not connected. Start the service in Stellar and make sure the Shizuku compatibility layer is enabled, then return to DSHA and tap Authorize.");
        return UiText.text("管理器已安装，服务尚未连接。点击授权重新连接；若仍未连接，请启动管理器服务，并检查隐藏模式是否允许 DSHA。");
      case AUTHORIZE_IN_OWNER:
        return UiText.choose(
            "服务已连接，但 DSHA 运行在应用分身或工作资料中，本空间没有管理器，授权窗口无法弹出。请在机主空间打开 Stellar（或 Shizuku）的应用授权列表，允许分身里的 DSHA，然后回到这里点「刷新状态」。",
            "The service is connected, but DSHA is running in an app clone or work profile with no manager, so the authorization prompt cannot appear. In the main profile, open the app list in Stellar (or Shizuku), allow the cloned DSHA, then come back and tap Refresh.");
      case REQUEST:
        if (outcome == ShizukuChannelStatus.Outcome.DENIED)
          return ShizukuManagerCompat.isStellar(ctx)
              ? UiText.choose(
                  "Stellar 拒绝了 DSHA 的授权。若曾选择「始终拒绝」，Stellar 不会再弹窗；请在 Stellar 的应用授权列表中把 DSHA 改为允许，再回到这里刷新。",
                  "Stellar denied DSHA. If you chose to always deny, Stellar will not ask again; change DSHA to allowed in Stellar's app list, then come back and refresh.")
              : UiText.choose(
                  "管理器拒绝了 DSHA 的授权。可再次点击授权，或在管理器的应用列表中允许 DSHA。",
                  "The manager denied DSHA. Tap Authorize again, or allow DSHA in the manager's app list.");
        if (outcome == ShizukuChannelStatus.Outcome.TIMEOUT)
          return UiText.choose(
              "授权窗口没有返回结果。请确认管理器弹出了授权页；若没有弹出，可在管理器的应用授权列表中直接允许 DSHA，再回到这里刷新。",
              "The authorization prompt returned no result. Make sure the manager showed its prompt; if not, allow DSHA directly in the manager's app list, then come back and refresh.");
        return UiText.text("服务已连接，尚未授权 DSHA。点击下方授权。");
      case READY:
        return UiText.text("已授权 · 设备 Shell 已连接，可直接使用，无需 ADB 配对");
      case BIND_FAILED:
        return UiText.format("已授权，连接失败：%s。点击下方重试。", failure);
      case BINDING:
      default:
        return UiText.text("已授权，正在连接设备 Shell…");
    }
  }

  public static void requestPermission(Shizuku.OnRequestPermissionResultListener callback) {
    MAIN.post(
        () -> {
          if (hasPermission()) {
            ensureBound(context);
            if (callback != null)
              callback.onRequestPermissionResult(0, PackageManager.PERMISSION_GRANTED);
            return;
          }
          // 当前用户没有管理器（如应用分身）时服务端的授权页弹不出来，也不会回调：不发起注定超时的请求。
          if (!ShizukuChannelStatus.canRequestPermission(step(context))) {
            if (callback != null)
              callback.onRequestPermissionResult(0, PackageManager.PERMISSION_DENIED);
            return;
          }
          outcome = ShizukuChannelStatus.Outcome.NONE;
          final int code = ++requestCode;
          final Shizuku.OnRequestPermissionResultListener[] holder =
              new Shizuku.OnRequestPermissionResultListener[1];
          final boolean[] completed = {false};
          final boolean[] timedOut = {false};
          holder[0] =
              (received, result) -> {
                if (received != code || completed[0]) return;
                completed[0] = true;
                Shizuku.removeRequestPermissionResultListener(holder[0]);
                outcome =
                    result == PackageManager.PERMISSION_GRANTED
                        ? ShizukuChannelStatus.Outcome.NONE
                        : timedOut[0]
                            ? ShizukuChannelStatus.Outcome.TIMEOUT
                            : ShizukuChannelStatus.Outcome.DENIED;
                if (result == PackageManager.PERMISSION_GRANTED) ensureBound(context);
                if (callback != null) callback.onRequestPermissionResult(code, result);
              };
          try {
            Shizuku.addRequestPermissionResultListener(holder[0]);
            Shizuku.requestPermission(code);
            MAIN.postDelayed(
                () -> {
                  if (!completed[0]) {
                    timedOut[0] = true;
                    holder[0].onRequestPermissionResult(code, PackageManager.PERMISSION_DENIED);
                  }
                },
                60_000);
          } catch (Throwable e) {
            fail(e);
            // 请求没有送达服务端，不能显示成「管理器拒绝」。
            timedOut[0] = true;
            holder[0].onRequestPermissionResult(code, PackageManager.PERMISSION_DENIED);
          }
        });
  }

  public static void ensureBound(Context ctx) {
    init(ctx);
    if (Looper.myLooper() != Looper.getMainLooper()) {
      MAIN.post(() -> ensureBound(context));
      return;
    }
    if (context == null) return;
    if (!hasPermission()) {
      if (connection != null) disconnect("", true);
      return;
    }
    if (isReady()) return;
    if (service != null) disconnect("", true);
    if (connection != null) return;
    failure = "";
    arguments =
        new Shizuku.UserServiceArgs(new ComponentName(context, ShellService.class))
            .processNameSuffix("shizuku")
            .tag("dsha-device-shell")
            .daemon(false)
            .version(BuildConfig.VERSION_CODE);
    ServiceConnection pending =
        new ServiceConnection() {
          @Override
          public void onServiceConnected(ComponentName name, IBinder binder) {
            if (connection != this) return;
            try {
              if (binder == null || !binder.pingBinder())
                throw new IllegalStateException(
                    com.deepseekharness.app.util.UiText.text("设备 Shell 返回了失效的 Binder"));
              binder.linkToDeath(
                  () ->
                      MAIN.post(
                          () ->
                              lost(this, com.deepseekharness.app.util.UiText.text("设备 Shell 已退出"))),
                  0);
              synchronized (LOCK) {
                service = IShellService.Stub.asInterface(binder);
                failure = "";
                LOCK.notifyAll();
              }
            } catch (Throwable e) {
              fail(e);
              lost(this, failure);
            }
          }

          @Override
          public void onServiceDisconnected(ComponentName name) {
            lost(this, com.deepseekharness.app.util.UiText.text("设备 Shell 连接断开"));
          }

          @Override
          public void onBindingDied(ComponentName name) {
            lost(this, com.deepseekharness.app.util.UiText.text("设备 Shell 绑定已失效"));
          }

          @Override
          public void onNullBinding(ComponentName name) {
            lost(this, com.deepseekharness.app.util.UiText.text("设备 Shell 未返回 Binder"));
          }
        };
    connection = pending;
    try {
      Shizuku.bindUserService(arguments, pending);
      MAIN.postDelayed(
          () -> {
            if (connection == pending && service == null)
              disconnect(com.deepseekharness.app.util.UiText.text("设备 Shell 连接超时"), true);
          },
          15_000);
    } catch (Throwable e) {
      fail(e);
      disconnect(failure, true);
    }
  }

  private static void lost(ServiceConnection expected, String message) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      MAIN.post(() -> lost(expected, message));
      return;
    }
    if (connection != expected) return;
    disconnect(message, true);
    MAIN.postDelayed(() -> ensureBound(context), 4000);
  }

  private static void disconnect(String message, boolean unbind) {
    ServiceConnection old = connection;
    Shizuku.UserServiceArgs oldArguments = arguments;
    synchronized (LOCK) {
      connection = null;
      service = null;
      failure = message;
      LOCK.notifyAll();
    }
    if (unbind && old != null && oldArguments != null) {
      try {
        Shizuku.unbindUserService(oldArguments, old, false);
      } catch (Throwable ignored) {
      }
    }
  }

  private static void fail(Throwable error) {
    failure = SensitiveData.redact(error.getClass().getSimpleName() + ": " + error.getMessage());
    android.util.Log.w("ShizukuShell", failure);
  }

  /** 只在发送前等待绑定；已经发送的命令失败后不自动重放。 */
  public static boolean awaitReady(Context ctx, long timeoutMs) {
    ensureBound(ctx);
    if (Looper.myLooper() == Looper.getMainLooper()) return isReady();
    long until = SystemClock.elapsedRealtime() + timeoutMs;
    synchronized (LOCK) {
      while (hasPermission() && !isReady()) {
        long remaining = until - SystemClock.elapsedRealtime();
        if (remaining <= 0) break;
        try {
          LOCK.wait(Math.min(remaining, 250));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return isReady();
  }

  public static String exec(String command) {
    if (!awaitReady(context, 8000))
      return "[SHIZUKU_SERVICE_NOT_READY] " + status() + "\n[EXIT=124]";
    IShellService current = service;
    try {
      return current.exec(command);
    } catch (Throwable e) {
      fail(e);
      return com.deepseekharness.app.util.UiText.format(
          "[EXECUTION_UNKNOWN] 设备命令结果未确认，不会自动重试：%s\n[EXIT=125]", failure);
    }
  }

  /** No Binder call is attempted when readiness is unavailable. */
  public static com.deepseekharness.app.device.DeviceTransportResult execVirtualScreenTyped(
      String command) {
    if (!awaitReady(context, 8000))
      return com.deepseekharness.app.device.DeviceTransportResult.notSent(
          "shizuku",
          "SHIZUKU_SERVICE_NOT_READY",
          "[SHIZUKU_SERVICE_NOT_READY] " + status() + "\n[EXIT=124]");
    IShellService current = service;
    try {
      return com.deepseekharness.app.device.DeviceTransportResult.fromLegacy(
          "shizuku",
          current.execVirtualScreen(command),
          com.deepseekharness.app.device.DeviceTransportIdentity.unknown());
    } catch (Throwable error) {
      fail(error);
      return com.deepseekharness.app.device.DeviceTransportResult.unknown(
          "shizuku",
          "SHIZUKU_EXECUTION_UNKNOWN",
          "[EXECUTION_UNKNOWN] VSCREEN_RESULT_UNKNOWN\n[EXIT=125]",
          false,
          com.deepseekharness.app.device.DeviceTransportIdentity.unknown());
    }
  }

  /** Dedicated native-only operation; the generic Binder exec still rejects app_process. */
  public static String execVirtualScreen(String command) {
    if (!awaitReady(context, 8000))
      return "[SHIZUKU_SERVICE_NOT_READY] " + status() + "\n[EXIT=124]";
    IShellService current = service;
    try {
      return current.execVirtualScreen(command);
    } catch (Throwable e) {
      fail(e);
      return com.deepseekharness.app.util.UiText.format(
          "[EXECUTION_UNKNOWN] 虚拟屏启动结果未确认，不会自动重试：%s\n[EXIT=125]", failure);
    }
  }
}
