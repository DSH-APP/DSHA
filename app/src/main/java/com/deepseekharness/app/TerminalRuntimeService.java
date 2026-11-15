package com.deepseekharness.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import com.deepseekharness.app.core.TerminalRuntimeProtection;
import com.deepseekharness.app.core.TerminalSessionOwner;
import com.deepseekharness.app.ui.TerminalActivity;
import com.deepseekharness.app.util.NotificationIds;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;

/** 仅保护用户可见操作开启的终端；不依赖 Web 服务，不从服务恢复回放 shell。 */
public final class TerminalRuntimeService extends Service {
  private static final String ACTION_PREPARE = "com.deepseekharness.app.PREPARE_TERMINAL";
  private static final String EXTRA_TICKET = "terminal_preparation";
  private static final String CHANNEL = "dsha_terminal_runtime";
  private static final Handler MAIN = new Handler(Looper.getMainLooper());
  private final TerminalRuntimeProtection protection = TerminalRuntimeProtection.shared();
  private final Runnable changed = () -> MAIN.post(this::retireIfIdle);
  private volatile boolean promoted, destroyed;
  private boolean retiring;

  /** 必须在可见 Activity 的主线程调用；系统拒绝时不创建会话。 */
  public static TerminalRuntimeProtection.Ticket requestFromVisible(
      FragmentActivity activity,
      TerminalRuntimeProtection.Kind kind,
      TerminalRuntimeProtection.Admission admission)
      throws IOException {
    if (Looper.myLooper() != Looper.getMainLooper() || !ForegroundActivity.isResumed(activity))
      throw new IOException("TERMINAL_START_REQUIRES_VISIBLE_ACTIVITY");
    if (admission == null) throw new IllegalArgumentException("TERMINAL_ADMISSION_REQUIRED");
    TerminalRuntimeProtection protection = TerminalRuntimeProtection.shared();
    // 只包回调，不捕获 Activity；未开始票由页面 onPause 显式取消。
    TerminalRuntimeProtection.Ticket ticket =
        protection.prepare(
            TerminalSessionOwner.shared().ownerEpoch(),
            kind,
            new TerminalRuntimeProtection.Admission() {
              @Override
              public void onReady(TerminalRuntimeProtection.Ticket ready) {
                MAIN.post(
                    () -> {
                      if (ready.state() == TerminalRuntimeProtection.State.ACTIVE)
                        admission.onReady(ready);
                      else
                        admission.onRejected(
                            ready,
                            ready.error().isEmpty()
                                ? "TERMINAL_FOREGROUND_SERVICE_LOST"
                                : ready.error());
                    });
              }

              @Override
              public void onRejected(TerminalRuntimeProtection.Ticket rejected, String reason) {
                MAIN.post(() -> admission.onRejected(rejected, reason));
              }
            });
    try {
      android.content.Context app = activity.getApplicationContext();
      ContextCompat.startForegroundService(
          app,
          new Intent(app, TerminalRuntimeService.class)
              .setAction(ACTION_PREPARE)
              .putExtra(EXTRA_TICKET, ticket.id()));
    } catch (RuntimeException unavailable) {
      protection.reject(ticket.id(), "TERMINAL_FOREGROUND_SERVICE_UNAVAILABLE");
      throw new IOException("TERMINAL_FOREGROUND_SERVICE_UNAVAILABLE", unavailable);
    }
    MAIN.postDelayed(
        () -> protection.expire(ticket), TerminalRuntimeProtection.PROMOTION_TIMEOUT_MS);
    MAIN.postDelayed(
        () -> protection.expire(ticket), TerminalRuntimeProtection.PREPARATION_TIMEOUT_MS);
    return ticket;
  }

  @Override
  public void onCreate() {
    super.onCreate();
    protection.addObserver(changed);
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    String id =
        intent != null && ACTION_PREPARE.equals(intent.getAction())
            ? intent.getStringExtra(EXTRA_TICKET)
            : null;
    // 即使请求已取消，也先满足 startForegroundService 的平台限时合同，再退休。
    try {
      showNotification();
      promoted = true;
    } catch (RuntimeException unavailable) {
      promoted = false;
      protection.reject(id, "TERMINAL_FOREGROUND_SERVICE_UNAVAILABLE");
      protection.serviceDestroyed(this);
      retiring = true;
      stopSelf();
      return START_NOT_STICKY;
    }
    if (id != null && !retiring && protection.matches(id))
      protection.promoted(id, this, () -> promoted && !destroyed && !retiring);
    else if (id != null) protection.reject(id, "TERMINAL_FOREGROUND_SERVICE_UNAVAILABLE");
    retireIfIdle();
    return START_NOT_STICKY;
  }

  private void showNotification() {
    NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26 && manager != null) {
      NotificationChannel channel =
          new NotificationChannel(
              CHANNEL,
              UiText.choose("终端后台保护", "Terminal background protection"),
              NotificationManager.IMPORTANCE_LOW);
      channel.setDescription(
          UiText.choose(
              "保护用户开启的终端及尚待核验的清理",
              "Protects user-started terminals and cleanup awaiting verification"));
      manager.createNotificationChannel(channel);
    }
    Intent open =
        new Intent(this, TerminalActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent content =
        PendingIntent.getActivity(
            this,
            NotificationIds.TERMINAL_RUNTIME,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    Notification notification =
        new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(UiText.choose("终端正在运行或等待清理", "Terminal running or awaiting cleanup"))
            .setContentText(
                UiText.choose(
                    "返回页面不会结束会话；点按管理终端",
                    "Leaving the page does not close sessions. Tap to manage terminals."))
            .setContentIntent(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    if (Build.VERSION.SDK_INT >= 34)
      startForeground(
          NotificationIds.TERMINAL_RUNTIME,
          notification,
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    else startForeground(NotificationIds.TERMINAL_RUNTIME, notification);
  }

  private void retireIfIdle() {
    if (destroyed || retiring || protection.needsService(this)) return;
    retiring = true;
    promoted = false;
    if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
    else stopForeground(true);
    stopSelf();
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    promoted = false;
    protection.removeObserver(changed);
    protection.serviceDestroyed(this);
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
