package com.deepseekharness.app.nethealth;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.device.NetworkDiagnostics;
import com.deepseekharness.app.device.NetworkStateMonitor;
import com.deepseekharness.app.util.NetworkHealthPolicy;
import com.deepseekharness.app.util.NetworkSnapshot;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 网络健康一期：只检测、只记录，不重启 Web、不发通知、不改系统设置。
 *
 * <p>信号：系统拦截（默认网络回调的 blocked）、Web 运行但前台服务缺失、容器 resolv.conf 过期、 宿主侧出网探测、Web
 * 运行期间 60 秒心跳的调度断档。所有工作在单个后台线程串行执行；记录写在私有目录，最多 200 条，不含地址与凭据。
 *
 * <p>不修改运行时描述符钉住的类，只读调用它们的公开方法。
 */
public final class NetworkHealthMonitor {
  private static final String TAG = "DSHA-NETHEALTH";
  private static final long NETWORK_SETTLE_MS = 5_000;
  private static final long BACKGROUND_SETTLE_MS = 3_000;
  private static final String HARNESS_SERVICE = "com.deepseekharness.app.HarnessService";

  /** 一次体检的结果；text 已脱敏，可直接复制。 */
  public static final class Report {
    public final NetworkHealthPolicy.Verdict verdict;
    public final String evidence;
    public final String text;

    Report(NetworkHealthPolicy.Verdict verdict, String evidence, String text) {
      this.verdict = verdict;
      this.evidence = evidence;
      this.text = text;
    }
  }

  private static final Object START = new Object();
  private static volatile NetworkHealthMonitor instance;

  private final Context app;
  private final ScheduledExecutorService worker;
  private final File store;
  // 以下只在 worker 线程读写
  private List<NetworkHealthPolicy.Event> events;
  private long lastBeat;
  private long pendingGap;
  private ScheduledFuture<?> settle;
  private volatile List<NetworkHealthPolicy.Event> published = List.of();
  private volatile NetworkHealthPolicy.Verdict lastVerdict = NetworkHealthPolicy.Verdict.OK;

  private NetworkHealthMonitor(Context context) {
    app = context.getApplicationContext();
    worker =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "dsha-nethealth");
              thread.setDaemon(true);
              return thread;
            });
    store = new File(new File(app.getFilesDir(), "nethealth"), "events.txt");
  }

  /** 惰性启动一次；随进程存活。失败不影响 App 其它功能。 */
  public static NetworkHealthMonitor ensureStarted(Context context) {
    NetworkHealthMonitor local = instance;
    if (local != null) return local;
    synchronized (START) {
      if (instance == null) {
        NetworkHealthMonitor created = new NetworkHealthMonitor(context);
        created.start();
        instance = created;
      }
      return instance;
    }
  }

  private void start() {
    worker.execute(
        () -> {
          events = load();
          published = List.copyOf(events);
        });
    try {
      NetworkStateMonitor.ensureStarted(app)
          .addListener((event, snapshot) -> schedule(NETWORK_SETTLE_MS));
    } catch (RuntimeException unavailable) {
      Log.w(TAG, "network monitor unavailable: " + unavailable.getClass().getSimpleName());
    }
    worker.scheduleWithFixedDelay(
        this::heartbeat,
        NetworkHealthPolicy.HEARTBEAT_MS,
        NetworkHealthPolicy.HEARTBEAT_MS,
        TimeUnit.MILLISECONDS);
  }

  /** App 全部界面离开前台：稍后复核一次（主要用于发现前台服务缺失）。 */
  public void onAppBackground() {
    schedule(BACKGROUND_SETTLE_MS);
  }

  /** 最近事件快照（旧在前），可在任意线程读取。 */
  public List<NetworkHealthPolicy.Event> events() {
    return published;
  }

  public NetworkHealthPolicy.Verdict lastVerdict() {
    return lastVerdict;
  }

  /** 手动体检：总是探测出网，并附脱敏的宿主网络与容器 DNS 状态。回调在后台线程。 */
  public void checkNow(Consumer<Report> done) {
    worker.execute(
        () -> {
          Report report;
          try {
            NetworkHealthPolicy.Signals signals = collect();
            if (signals.connected) signals.probe = NetworkHealthProbe.run();
            NetworkHealthPolicy.Verdict verdict = NetworkHealthPolicy.classify(signals);
            String evidence = NetworkHealthPolicy.evidence(signals);
            record(verdict, evidence);
            report =
                new Report(
                    verdict,
                    evidence,
                    "DSHA_NET_HEALTH_CHECK_V1\nverdict="
                        + verdict.name()
                        + "\nevidence="
                        + evidence
                        + "\nprobe_host="
                        + NetworkHealthProbe.HOST
                        + "\n"
                        + NetworkHealthPolicy.redactNetReport(netReport()));
          } catch (RuntimeException failure) {
            report = null;
            Log.w(TAG, "check failed: " + failure.getClass().getSimpleName());
          }
          if (done != null) done.accept(report);
        });
  }

  // ---------- 内部：全部在 worker 线程 ----------

  private void schedule(long delayMs) {
    worker.execute(
        () -> {
          if (settle != null) settle.cancel(false);
          settle = worker.schedule(this::evaluate, delayMs, TimeUnit.MILLISECONDS);
        });
  }

  private void heartbeat() {
    try {
      long now = SystemClock.elapsedRealtime();
      // 省电模式空闲时本来就允许 CPU 休眠，此时的心跳断档不是故障，不计入疑似冻结。
      if (!webActive() || new com.deepseekharness.app.core.ConfigStore(app).isEcoMode()) {
        lastBeat = 0;
        return;
      }
      long gap = NetworkHealthPolicy.frozenGap(lastBeat, now);
      lastBeat = now;
      if (gap > 0) {
        pendingGap = gap;
        evaluate();
      }
    } catch (RuntimeException failure) {
      Log.w(TAG, "heartbeat failed: " + failure.getClass().getSimpleName());
    }
  }

  private void evaluate() {
    try {
      NetworkHealthPolicy.Signals signals = collect();
      signals.frozenGapMs = pendingGap;
      pendingGap = 0;
      if (NetworkHealthPolicy.shouldProbe(signals)) signals.probe = NetworkHealthProbe.run();
      record(NetworkHealthPolicy.classify(signals), NetworkHealthPolicy.evidence(signals));
    } catch (RuntimeException failure) {
      Log.w(TAG, "evaluate failed: " + failure.getClass().getSimpleName());
    }
  }

  private NetworkHealthPolicy.Signals collect() {
    NetworkHealthPolicy.Signals s = new NetworkHealthPolicy.Signals();
    HarnessController controller = HarnessController.get(app);
    // 启动中由 WebStartProtection 先拿前台服务；此窗口不判缺失，避免误报。
    s.webActive = controller.requiresRuntimeProtection() && !controller.isStarting();
    s.foregroundService = harnessForeground();
    NetworkSnapshot snapshot = NetworkStateMonitor.ensureStarted(app).current();
    if (!snapshot.connected()) snapshot = NetworkStateMonitor.query(app);
    s.connected = snapshot.connected();
    s.blocked = snapshot.blocked;
    s.validated = snapshot.validated;
    s.vpn = snapshot.vpn;
    s.transport = snapshot.transport;
    s.resolvState = NetworkHealthPolicy.resolvState(netReport());
    return s;
  }

  private boolean webActive() {
    HarnessController controller = HarnessController.get(app);
    return controller.requiresRuntimeProtection();
  }

  private String netReport() {
    return NetworkDiagnostics.render(
        app,
        new com.deepseekharness.app.core.ConfigStore(app).getDnsMode(),
        List.of(new File(app.getFilesDir(), "linux/ubuntu").getAbsolutePath()));
  }

  /** 本应用 HarnessService 是否处于前台；getRunningServices 对调用方自身服务仍返回结果。 */
  @SuppressWarnings("deprecation")
  private boolean harnessForeground() {
    try {
      ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
      if (manager == null) return false;
      for (ActivityManager.RunningServiceInfo info : manager.getRunningServices(64)) {
        if (info.service == null) continue;
        if (!app.getPackageName().equals(info.service.getPackageName())) continue;
        if (HARNESS_SERVICE.equals(info.service.getClassName())) return info.foreground;
      }
    } catch (RuntimeException unavailable) {
      // 查询失败按「未知」处理：视为存在，避免误报缺失。
      return true;
    }
    return false;
  }

  private void record(NetworkHealthPolicy.Verdict verdict, String evidence) {
    lastVerdict = verdict;
    if (verdict == NetworkHealthPolicy.Verdict.OK) return;
    if (events == null) events = load();
    events = NetworkHealthPolicy.append(events, System.currentTimeMillis(), verdict, evidence);
    published = List.copyOf(events);
    save(events);
  }

  private List<NetworkHealthPolicy.Event> load() {
    try {
      if (!store.isFile() || store.length() > 256 * 1024) return new java.util.ArrayList<>();
      return NetworkHealthPolicy.parse(
          new String(Files.readAllBytes(store.toPath()), StandardCharsets.UTF_8));
    } catch (Exception unreadable) {
      return new java.util.ArrayList<>();
    }
  }

  /** 先写临时文件再改名，进程中途被杀不留半截记录。 */
  private void save(List<NetworkHealthPolicy.Event> value) {
    File dir = store.getParentFile();
    File temp = new File(dir, "events.txt.tmp");
    try {
      if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
      try (FileOutputStream out = new FileOutputStream(temp)) {
        out.write(NetworkHealthPolicy.serialize(value).getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      if (!temp.renameTo(store)) temp.delete();
    } catch (Exception unwritable) {
      temp.delete();
      Log.w(TAG, "save failed: " + unwritable.getClass().getSimpleName());
    }
  }
}
