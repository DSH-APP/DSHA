package com.deepseekharness.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import com.deepseekharness.app.util.ShizukuManagers;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

/** 兼容标准协议的管理器；重新索取 Binder 只在用户点击时发生，不代替管理器授权。 */
public final class ShizukuManagerCompat {
  private static final String PERMISSION = "moe.shizuku.manager.permission.API_V23";
  private static final String REQUEST_BINDER = "rikka.shizuku.intent.action.REQUEST_BINDER";
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private static boolean requesting;

  private ShizukuManagerCompat() {}

  public static ApplicationInfo manager(Context context) {
    ShizukuManagers.Selection selected = select(context);
    if (selected.kind() == ShizukuManagers.Kind.NONE) return null;
    return applicationInfo(context.getPackageManager(), selected.packageName());
  }

  /** 识别到的管理器种类；未安装任何管理器时为 NONE。 */
  public static ShizukuManagers.Kind kind(Context context) {
    return select(context).kind();
  }

  /** Stellar 不声明 API_V23，也没有导出的 rikka REQUEST_BINDER；文案与入口据此区分。 */
  public static boolean isStellar(Context context) {
    return kind(context) == ShizukuManagers.Kind.STELLAR;
  }

  /** 权限声明者优先，其次按固定白名单顺序取第一个已安装的兼容管理器。 */
  private static ShizukuManagers.Selection select(Context context) {
    PackageManager pm = context.getPackageManager();
    Set<String> installed = new LinkedHashSet<>();
    for (String packageName : ShizukuManagers.knownPackages())
      if (applicationInfo(pm, packageName) != null) installed.add(packageName);
    return ShizukuManagers.choose(protocolOwner(pm), installed);
  }

  /** 权限声明者必须在包可见性内可解析；查不到时交给兼容白名单兜底，不把协议管理器误判成未安装。 */
  private static String protocolOwner(PackageManager pm) {
    try {
      String owner = pm.getPermissionInfo(PERMISSION, 0).packageName;
      return applicationInfo(pm, owner) == null ? null : owner;
    } catch (PackageManager.NameNotFoundException | RuntimeException error) {
      return null;
    }
  }

  private static ApplicationInfo applicationInfo(PackageManager pm, String packageName) {
    if (packageName == null || packageName.isEmpty()) return null;
    try {
      return pm.getApplicationInfo(packageName, 0);
    } catch (PackageManager.NameNotFoundException | RuntimeException error) {
      return null;
    }
  }

  public static Intent launchIntent(Context context) {
    ApplicationInfo info = manager(context);
    return info == null
        ? null
        : context.getPackageManager().getLaunchIntentForPackage(info.packageName);
  }

  /** 官方与 thedjchi 的公开 REQUEST_BINDER 入口；仅接受所选管理器 UID 的单次回调。 */
  public static void reconnect(Activity activity, Runnable completed) {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      MAIN.post(() -> reconnect(activity, completed));
      return;
    }
    if (ShizukuShell.isAvailable()) {
      completed.run();
      return;
    }
    if (requesting) {
      completed.run();
      return;
    }
    ApplicationInfo info = manager(activity);
    if (info == null) {
      completed.run();
      return;
    }
    // Stellar 的 REQUEST_BINDER 是 receiver（roro.stellar.intent.action.REQUEST_BINDER），协议未核实：
    // 安全上不向它发送任何 broadcast / 自定义协议，只打开 Stellar 让用户检查服务与「Shizuku 兼容层」。
    // Stellar 服务端会在进程进入前台时经 <包名>.shizuku Provider 主动推送兼容 Binder，这里不需要索取。
    if (ShizukuManagers.isStellar(info.packageName)) {
      openManager(activity);
      completed.run();
      return;
    }
    Intent intent = new Intent(REQUEST_BINDER).setPackage(info.packageName);
    android.content.pm.ResolveInfo handler =
        activity.getPackageManager().resolveActivity(intent, 0);
    if (handler == null
        || handler.activityInfo == null
        || !handler.activityInfo.exported
        || handler.activityInfo.applicationInfo.uid != info.uid) {
      // 找不到导出的标准入口时只能打开管理器本身，不猜测未知协议。
      openManager(activity);
      completed.run();
      return;
    }
    intent.setComponent(
        new android.content.ComponentName(
            handler.activityInfo.packageName, handler.activityInfo.name));
    requesting = true;
    AtomicBoolean finished = new AtomicBoolean();
    AtomicBoolean delivered = new AtomicBoolean();
    Runnable finish =
        () -> {
          if (finished.compareAndSet(false, true)) {
            requesting = false;
            completed.run();
          }
        };
    Binder callback =
        new Binder() {
          @Override
          protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != 1
                || Binder.getCallingUid() != info.uid
                || finished.get()
                || !delivered.compareAndSet(false, true)) return false;
            try {
              IBinder remote = data.readStrongBinder();
              // 管理器另附的 APK 路径无需读取，绝不执行或加载外部 APK。
              if (remote != null
                  && remote.pingBinder()
                  && "moe.shizuku.server.IShizukuService".equals(remote.getInterfaceDescriptor())) {
                MAIN.post(
                    () -> {
                      if (finished.get()) return;
                      Shizuku.OnBinderReceivedListener[] listener =
                          new Shizuku.OnBinderReceivedListener[1];
                      listener[0] =
                          () ->
                              MAIN.post(
                                  () -> {
                                    Shizuku.removeBinderReceivedListener(listener[0]);
                                    finish.run();
                                  });
                      try {
                        Shizuku.addBinderReceivedListener(listener[0]);
                        // 交给本应用受保护的标准 Provider 完成 API 版本握手，避免调用库内部接口。
                        receiveThroughProvider(activity, remote);
                        MAIN.postDelayed(
                            () -> Shizuku.removeBinderReceivedListener(listener[0]), 15000);
                      } catch (Throwable error) {
                        Shizuku.removeBinderReceivedListener(listener[0]);
                        finish.run();
                      }
                    });
              } else MAIN.post(finish);
            } catch (Throwable error) {
              MAIN.post(finish);
            }
            return true;
          }
        };
    Bundle data = new Bundle();
    data.putBinder("binder", callback);
    intent.putExtra("data", data);
    try {
      activity.startActivity(intent);
    } catch (RuntimeException error) {
      finish.run();
    }
    MAIN.postDelayed(finish, 15000);
  }

  /** 打开管理器启动页；失败时保持静默，由状态行说明原因，不猜测未知协议。 */
  private static void openManager(Activity activity) {
    Intent launch = launchIntent(activity);
    if (launch != null)
      try {
        activity.startActivity(launch);
      } catch (RuntimeException ignored) {
      }
  }

  // 锁定的 API 12.2.0 / 13.1.5 均使用这一 Parcelable 线协议；仅此适配点接触库内部类型。
  // 入口此前已核对管理器 UID、Binder 描述符及单次请求，本应用 Provider 的系统权限不变。
  @android.annotation.SuppressLint("RestrictedApi")
  private static void receiveThroughProvider(Context context, IBinder binder) {
    Bundle received = new Bundle();
    received.putParcelable(
        "moe.shizuku.privileged.api.intent.extra.BINDER",
        new moe.shizuku.api.BinderContainer(binder));
    context
        .getContentResolver()
        .call(
            android.net.Uri.parse("content://" + context.getPackageName() + ".shizuku"),
            "sendBinder",
            null,
            received);
  }
}
