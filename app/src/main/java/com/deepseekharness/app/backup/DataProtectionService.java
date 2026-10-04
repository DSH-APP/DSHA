package com.deepseekharness.app.backup;

import android.app.*;
import android.content.*;
import android.os.*;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.util.UiText;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 数据导出、恢复与受控运行时验证共享 dataSync 前台通知；中断后不自动重放任务。 */
public final class DataProtectionService extends Service {
  private static final String CHANNEL = "dsha_data_protection";
  private static final String START_ID = "dsha_protection_start_id";
  private static final AtomicLong NEXT_START = new AtomicLong();
  private static final ConcurrentHashMap<Long, StartTicket> PENDING_STARTS =
      new ConcurrentHashMap<>();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (show()) main.postDelayed(this, 600);
        }
      };
  private boolean promoted;
  private IOException promotionFailure;

  /** Workers wait for the service's actual promotion before touching protected data. */
  public static final class StartTicket {
    private final long id;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile IOException failure;

    StartTicket(long id) {
      this.id = id;
    }

    void complete(IOException error) {
      failure = error;
      ready.countDown();
    }

    public void await() throws IOException {
      try {
        if (!ready.await(8, TimeUnit.SECONDS)) {
          PENDING_STARTS.remove(id, this);
          throw new IOException("FOREGROUND_SERVICE_START_TIMEOUT");
        }
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new IOException("FOREGROUND_SERVICE_START_INTERRUPTED", error);
      }
      if (failure != null) throw failure;
    }
  }

  public static StartTicket start(Context context) throws IOException {
    long id = NEXT_START.incrementAndGet();
    StartTicket ticket = new StartTicket(id);
    PENDING_STARTS.put(id, ticket);
    Intent intent = new Intent(context, DataProtectionService.class).putExtra(START_ID, id);
    try {
      if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
      else context.startService(intent);
    } catch (RuntimeException error) {
      PENDING_STARTS.remove(id, ticket);
      IOException failure = new IOException("FOREGROUND_SERVICE_UNAVAILABLE", error);
      ticket.complete(failure);
      throw failure;
    }
    return ticket;
  }

  private static void failPending(IOException failure) {
    for (var entry : PENDING_STARTS.entrySet())
      if (PENDING_STARTS.remove(entry.getKey(), entry.getValue()))
        entry.getValue().complete(failure);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    if (Build.VERSION.SDK_INT >= 26)
      getSystemService(NotificationManager.class)
          .createNotificationChannel(
              new NotificationChannel(
                  CHANNEL,
                  UiText.choose("数据保护", "Data protection"),
                  NotificationManager.IMPORTANCE_LOW));
    // 即使任务在 Service 创建之前已结束，也先履行 startForegroundService 的时间契约。
    if (publish(UiText.choose("正在确认任务状态…", "Checking task state…"), null, null)) main.post(refresh);
  }

  private boolean show() {
    var nativeJob = NativeBackupJobs.get(this).state();
    BackupTask tasks = BackupTask.get(this);
    var maintenance = tasks.snapshot();
    if (!nativeJob.busy && !tasks.maintenanceBusy()) {
      promoted = false;
      stopForeground(true);
      stopSelf();
      return false;
    }
    String id = nativeJob.busy ? "native:" + nativeJob.id : "maintenance:" + maintenance.id;
    Intent cancel =
        new Intent(this, DataProtectionService.class)
            .setAction("cancel")
            .setData(android.net.Uri.parse("dsha://data-operation/" + id))
            .putExtra("operation", id);
    PendingIntent action =
        nativeJob.busy || tasks.cancellable()
            ? PendingIntent.getService(
                this, 0, cancel, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)
            : null;
    Intent view =
        MaintenanceUiPorts.fromOwner(getApplicationContext())
            .dataProtectionPage(nativeJob.busy, maintenance.id);
    view.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent open =
        PendingIntent.getActivity(
            this, 9031, view, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    String text =
        nativeJob.busy
            ? UiText.format(
                "%s 项 · %s",
                nativeJob.entries, com.deepseekharness.app.util.Fmt.bytes(nativeJob.bytes))
            : UiText.choose("正在保护数据并验证运行环境", "Protecting data and verifying the runtime");
    return publish(text, action, open);
  }

  private boolean publish(String text, PendingIntent cancel, PendingIntent open) {
    Notification.Builder notification =
        Build.VERSION.SDK_INT >= 26
            ? new Notification.Builder(this, CHANNEL)
            : new Notification.Builder(this);
    notification
        .setSmallIcon(R.mipmap.ic_launcher)
        .setContentTitle(UiText.choose("DSHA 数据保护", "DSHA data protection"))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true);
    if (cancel != null)
      notification.addAction(
          new Notification.Action.Builder(null, UiText.choose("取消", "Cancel"), cancel).build());
    // startForegroundService 的第一帧可能还没有拿到任务快照；即使这时用户立刻
    // 点击通知，也必须能回到 DSHA，而不是出现一个没有响应的通知。任务快照就绪后
    // show() 会用 NativeData/Extract 的更具体页面替换这个兜底入口。
    if (open == null) {
      Intent app = MaintenanceUiPorts.fromOwner(getApplicationContext()).runtimePage();
      open =
          PendingIntent.getActivity(
              this, 9032, app, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    notification.setContentIntent(open);
    Notification built = notification.build();
    if (!promoted) {
      try {
        if (Build.VERSION.SDK_INT >= 29)
          startForeground(
              com.deepseekharness.app.util.NotificationIds.DATA_PROTECTION,
              built,
              android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(com.deepseekharness.app.util.NotificationIds.DATA_PROTECTION, built);
        promoted = true;
      } catch (RuntimeException error) {
        promotionFailure = new IOException("FOREGROUND_SERVICE_UNAVAILABLE", error);
        failPending(promotionFailure);
        stopSelf();
        return false;
      }
    } else {
      // A running service only updates its existing notification. Calling
      // startForeground again after the app backgrounds can be denied.
      try {
        getSystemService(NotificationManager.class)
            .notify(com.deepseekharness.app.util.NotificationIds.DATA_PROTECTION, built);
      } catch (RuntimeException ignored) {
        /* The already-posted foreground notification remains. */
      }
    }
    return true;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (!promoted && promotionFailure == null)
      publish(UiText.choose("正在确认任务状态…", "Checking task state…"), null, null);
    long request = intent == null ? 0 : intent.getLongExtra(START_ID, 0);
    StartTicket ticket = PENDING_STARTS.remove(request);
    if (ticket != null)
      ticket.complete(
          promoted
              ? null
              : promotionFailure == null
                  ? new IOException("FOREGROUND_SERVICE_UNAVAILABLE")
                  : promotionFailure);
    if (!promoted) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (intent != null && "cancel".equals(intent.getAction())) {
      String id = intent.getStringExtra("operation");
      var jobs = NativeBackupJobs.get(this);
      var tasks = BackupTask.get(this);
      if (("native:" + jobs.state().id).equals(id)) jobs.cancel();
      if (("maintenance:" + tasks.snapshot().id).equals(id)) tasks.cancel();
    }
    main.removeCallbacks(refresh);
    main.post(refresh);
    return START_NOT_STICKY;
  }

  @Override
  public void onTimeout(int startId, int fgsType) {
    NativeBackupJobs.get(this).cancel();
    BackupTask.get(this).cancel();
    stopSelf();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  @Override
  public void onDestroy() {
    main.removeCallbacks(refresh);
    promoted = false;
    failPending(new IOException("FOREGROUND_SERVICE_STOPPED"));
    super.onDestroy();
  }
}
