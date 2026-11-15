package com.deepseekharness.app.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import com.deepseekharness.app.device.NetworkStateMonitor;
import com.deepseekharness.app.util.NetworkEpochPolicy;
import com.deepseekharness.app.util.NetworkSnapshot;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 把「之后应当新建外网连接」的网络时刻发布到容器（T37）。
 *
 * <p>订阅全 App 唯一的 {@link NetworkStateMonitor}；{@link NetworkEpochPolicy} 判定需要时，向
 * {@link ResolverRefresher} 已登记的每个 rootfs 写入 {@value NetworkEpochPolicy#RELATIVE_PATH}。
 * 容器内 net-epoch.cjs 看到代次变化后回收空闲与切网后无数据的外网连接，不重放请求。写入失败只
 * 记日志：缺少代次时 Node 退回原来的超时行为。
 */
final class NetworkEpochPublisher {
  private static final String TAG = "DSHA-NET";
  private static final long DEBOUNCE_MS = 300;
  private static final Object START = new Object();
  private static volatile NetworkEpochPublisher instance;

  private final Context context;
  private final Handler handler;
  private final Runnable publish = this::publish;
  // 仅在 handler 线程读写
  private NetworkSnapshot last;
  private long sequence;

  private NetworkEpochPublisher(Context context) {
    this.context = context;
    HandlerThread thread = new HandlerThread("dsha-net-epoch");
    thread.setDaemon(true);
    thread.start();
    handler = new Handler(thread.getLooper());
  }

  /** 在任何锁之外调用：首次会注册系统网络回调（跨 Binder）。 */
  static void start(Context context) {
    if (context == null || instance != null) return;
    try {
      synchronized (START) {
        if (instance != null) return;
        Context app = context.getApplicationContext();
        NetworkEpochPublisher created = new NetworkEpochPublisher(app == null ? context : app);
        NetworkStateMonitor monitor = NetworkStateMonitor.ensureStarted(created.context);
        NetworkSnapshot initial = monitor.current();
        created.handler.post(() -> created.last = initial);
        monitor.addListener(
            (event, snapshot) -> created.handler.post(() -> created.onNetwork(snapshot)));
        instance = created;
      }
    } catch (RuntimeException error) {
      Log.w(TAG, "network epoch publisher unavailable: " + error.getClass().getSimpleName());
    }
  }

  private void onNetwork(NetworkSnapshot snapshot) {
    boolean invalidates = NetworkEpochPolicy.invalidates(last, snapshot);
    last = snapshot;
    if (!invalidates) return;
    // 同一次切网会连续来几条回调；合并成一次写入。
    handler.removeCallbacks(publish);
    handler.postDelayed(publish, DEBOUNCE_MS);
  }

  private void publish() {
    byte[] token =
        NetworkEpochPolicy.token(SystemClock.elapsedRealtime(), ++sequence)
            .getBytes(StandardCharsets.US_ASCII);
    for (String root : ResolverRefresher.roots()) {
      File rootfs = new File(root);
      if (!new File(rootfs, "run").isDirectory()) continue;
      try {
        RuntimeAssetFiles.write(
            context, new File(rootfs, NetworkEpochPolicy.RELATIVE_PATH), token, false);
      } catch (IOException | RuntimeException error) {
        Log.w(TAG, "network epoch write failed: " + error.getClass().getSimpleName());
      }
    }
  }
}
