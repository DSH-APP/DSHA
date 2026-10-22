package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.util.Ids;
import com.deepseekharness.app.util.OneShotLaunchAuthority;
import com.deepseekharness.app.util.ScreenActionResult;
import com.deepseekharness.app.util.VirtualScreenRoutes;
import com.deepseekharness.app.util.VirtualScreenRequestFence;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import com.deepseekharness.app.util.VirtualScreenLifecycleState;
import com.deepseekharness.app.capabilities.CapabilityBroker;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.json.JSONObject;

/** State snapshots use LOCK; remote/platform operations never run while holding it. */
public final class VirtualScreenManager {
  private final Object LOCK = new Object();
  // Ordered mutation channel, separate from the state lock: stop/revoke never wait for it.
  private final ReentrantLock ACTIONS = new ReentrantLock(true);
  private final SecureRandom RANDOM = new SecureRandom();
  private final Object OBSERVATION_RELEASES = new Object();
  private final java.util.LinkedHashMap<String, Runnable> observationReleases =
      new java.util.LinkedHashMap<>();
  private boolean observationReleaseScheduled;
  private final ScheduledExecutorService WORKER;

  private static ScheduledExecutorService lifecycleWorker() {
    return Executors.newSingleThreadScheduledExecutor(
        r -> {
          Thread t = new Thread(r, "vscreen-lifecycle");
          t.setDaemon(true);
          return t;
        });
  }

  private ScheduledFuture<?> cleanupProbe;
  private static final int CLEANUP_PROBE_ATTEMPTS = 3;

  private static long cleanupProbeDelayMillis(int attempt) {
    return switch (attempt) {
      case 0 -> 250;
      case 1 -> 1000;
      case 2 -> 3000;
      default -> throw new IllegalArgumentException("VSCREEN_CLEANUP_ATTEMPT_INVALID");
    };
  }

  private final Context context;
  private volatile String token = "", channel = "", generation = "", lastError = "";

  /** 最近一次创建失败摘要：连续重试同样的失败不再重复写诊断（写放大）。 */
  private volatile String lastCreateFailure = "";

  private volatile String lastStartOutcomeJson = "";

  private volatile int port;
  private final AtomicLong LIFECYCLE_EPOCH = new AtomicLong();
  private volatile long activeEpoch = -1;
  private long stateRevision, frameSequence = -1;
  private final LongSupplier clock;
  private volatile boolean starting;
  private volatile boolean launchDispatched;
  private final ThreadLocal<String> BRIDGE_TARGET = new ThreadLocal<>();
  private final ThreadLocal<BooleanSupplier> BRIDGE_AUTHORITY = new ThreadLocal<>();
  private ScheduledFuture<?> heartbeat;
  private static final long ADB_LAUNCH_TTL_MS = 30_000;
  private final OneShotLaunchAuthority ADB_LAUNCH = new OneShotLaunchAuthority();

  private final com.deepseekharness.app.util.ApplicationOwner<VirtualScreenForeground>
      foregroundOwner = new com.deepseekharness.app.util.ApplicationOwner<>();
  private final com.deepseekharness.app.util.ApplicationOwner<VirtualScreenOverlayController>
      overlayOwner = new com.deepseekharness.app.util.ApplicationOwner<>();
  private final com.deepseekharness.app.util.ApplicationOwner<VirtualScreenPreviews> previewsOwner =
      new com.deepseekharness.app.util.ApplicationOwner<>();
  private final VirtualScreenSemanticAdapter semantics = new VirtualScreenSemanticAdapter();
  private final VirtualScreenAccessibility accessibility = semantics.session;
  private final VirtualScreenTransport transport = new VirtualScreenTransport();
  private final VirtualScreenLauncher launcher = new VirtualScreenLauncher();
  private final OneShotLaunchAuthority ADB_PROBE = new OneShotLaunchAuthority();
  private final VirtualScreenJournal journal;

  @androidx.annotation.RequiresApi(30)
  private static final class Api30JournalFactory {
    static VirtualScreenJournal open(java.io.File files) throws java.io.IOException {
      return new VirtualScreenSessionJournal(files);
    }
  }

  private final Channels channels;
  private final java.util.function.Supplier<String> bootSource;
  private volatile VirtualScreenLifecycleState lifecycleState = VirtualScreenLifecycleState.STOPPED;
  private volatile VirtualScreenHandshake core;
  private volatile ClosedSession pendingCleanup;
  private volatile String attemptBoot = "";
  private final Visuals visuals;

  interface Visuals {
    void present();

    void stopped(long fence);
  }

  interface Channels {
    VirtualScreenChannel choose(Context application);

    VirtualScreenChannel find(String id);
  }

  public VirtualScreenManager(Context application) {
    this(DshaApp.from(application), android.os.SystemClock::elapsedRealtime, null);
  }

  /** Isolated host fixtures inject clock/UI effects; production is constructed by DshaApp only. */
  VirtualScreenManager(Context application, LongSupplier clock, Visuals visuals) {
    this(
        application,
        clock,
        visuals,
        new Channels() {
          public VirtualScreenChannel choose(Context app) {
            return VirtualScreenChannels.choose(app);
          }

          public VirtualScreenChannel find(String id) {
            return VirtualScreenChannels.find(id);
          }
        });
  }

  VirtualScreenManager(
      Context application, LongSupplier clock, Visuals visuals, Channels channels) {
    this(application, clock, visuals, channels, VirtualScreenManager::readBoot);
  }

  VirtualScreenManager(
      Context application,
      LongSupplier clock,
      Visuals visuals,
      Channels channels,
      java.util.function.Supplier<String> bootSource) {
    this(application, clock, visuals, channels, bootSource, lifecycleWorker());
  }

  VirtualScreenManager(
      Context application,
      LongSupplier clock,
      Visuals visuals,
      Channels channels,
      java.util.function.Supplier<String> bootSource,
      ScheduledExecutorService worker) {
    WORKER = java.util.Objects.requireNonNull(worker);
    this.context = application;
    this.clock = java.util.Objects.requireNonNull(clock);
    this.channels = java.util.Objects.requireNonNull(channels);
    this.bootSource = java.util.Objects.requireNonNull(bootSource);
    VirtualScreenJournal storage = null;
    try {
      if (application != null && !BuildConfig.LOW_ANDROID && android.os.Build.VERSION.SDK_INT >= 30)
        storage = Api30JournalFactory.open(application.getFilesDir());
    } catch (Exception unavailable) {
      lastError = "VSCREEN_JOURNAL_UNAVAILABLE";
      recordStartException("VSCREEN_JOURNAL_OPEN_FAILED", unavailable);
    }
    journal = storage;
    restoreJournal();
    this.visuals =
        visuals == null
            ? new Visuals() {
              public void present() {
                foreground().present();
              }

              public void stopped(long fence) {
                if (!supported(context)) return;
                foreground().stopped(fence);
                overlay().hideStopped(fence);
              }
            }
            : visuals;
  }

  public static VirtualScreenManager from(Context context) {
    return DshaApp.from(context).virtualScreenManager();
  }

  public VirtualScreenForeground foreground() {
    return foregroundOwner.get(() -> new VirtualScreenForeground(this));
  }

  public VirtualScreenOverlayController overlay() {
    return overlayOwner.get(() -> new VirtualScreenOverlayController(this));
  }

  Context applicationContext() {
    return context;
  }

  VirtualScreenPreviews previews() {
    return previewsOwner.get(() -> new VirtualScreenPreviews(this));
  }

  public String withBridgeTarget(String target, Supplier<String> action) {
    return withBridgeTarget(target, () -> true, action);
  }

  public String withBridgeTarget(
      String target, BooleanSupplier authority, Supplier<String> action) {
    String before = BRIDGE_TARGET.get();
    BooleanSupplier prior = BRIDGE_AUTHORITY.get();
    BRIDGE_TARGET.set(target);
    BRIDGE_AUTHORITY.set(authority);
    try {
      return action.get();
    } finally {
      if (before == null) BRIDGE_TARGET.remove();
      else BRIDGE_TARGET.set(before);
      if (prior == null) BRIDGE_AUTHORITY.remove();
      else BRIDGE_AUTHORITY.set(prior);
    }
  }

  private static boolean authorized(BooleanSupplier authority) {
    try {
      return authority == null || authority.getAsBoolean();
    } catch (RuntimeException unavailable) {
      return false;
    }
  }

  public static boolean supported(Context c) {
    return !BuildConfig.LOW_ANDROID && android.os.Build.VERSION.SDK_INT >= 30;
  }

  public String channel() {
    return channel;
  }

  public String generation() {
    return generation;
  }

  public String error() {
    return lastError;
  }

  /** 只保留技术结果，不包含握手、token、图像或清理凭据。 */
  public JSONObject lastStartOutcome() {
    try {
      return lastStartOutcomeJson.isEmpty() ? null : new JSONObject(lastStartOutcomeJson);
    } catch (Exception unavailable) {
      return null;
    }
  }

  public long epoch() {
    return LIFECYCLE_EPOCH.get();
  }

  private VirtualScreenRequestScope localScope() {
    return new VirtualScreenRequestScope(null, null, BRIDGE_AUTHORITY.get(), BRIDGE_TARGET.get());
  }

  /** Not a bridge route: only a resumed native Activity can acquire this local owner. */
  VirtualScreenNativeControls openNativeControls(androidx.appcompat.app.AppCompatActivity owner) {
    var controls = new VirtualScreenNativeControls(owner);
    var scope = new VirtualScreenRequestScope(null, null, controls::uiCurrent, "");
    Snapshot captured = snapshot(generation, scope);
    controls.bind(
        new VirtualScreenNativeControls.Backend() {
          public boolean current() {
            if (!VirtualScreenManager.this.current(captured)) return false;
            synchronized (LOCK) {
              return currentLocked(captured)
                  && core != null
                  && captured.coreInstance.equals(core.identity.instance);
            }
          }

          public JSONObject run(
              VirtualScreenAccessibility nativeSession,
              com.deepseekharness.app.screen.NativeScreenControlLease lease,
              String operation,
              JSONObject args) {
            return ordered(
                () -> {
                  if (captured == null) return missingSnapshot(null);
                  if (!current() || !lease.current())
                    return com.deepseekharness.app.screen.ScreenReply.refused(
                        "NATIVE_CONTROL_REVOKED");
                  JSONObject info = perform(captured, "/vscreen/status", "", false, false);
                  if (!info.optBoolean("ok")) return info;
                  if (!captured.coreInstance.equals(info.optString("coreInstance"))
                      || !captured.identity.generation.equals(info.optString("generation")))
                    return com.deepseekharness.app.screen.ScreenReply.refused(
                        "NATIVE_CONTROL_REVOKED");
                  JSONObject result =
                      semantics.nativeControl(
                          nativeSession, lease, info.optInt("displayId", -1), operation, args);
                  if (result.optBoolean("ok") && (!current() || !lease.current()))
                    return com.deepseekharness.app.screen.ScreenReply.of(
                        operation.equals("node")
                            ? com.deepseekharness.app.capabilities.CapabilityResult.unknown(
                                "VIRTUAL_INPUT_RESULT_UNKNOWN", "")
                            : com.deepseekharness.app.capabilities.CapabilityResult.notSent(
                                "SCREEN_TARGET_CHANGED", ""));
                  if (result.optBoolean("ok"))
                    try {
                      result
                          .put("coreInstance", captured.coreInstance)
                          .put("generation", captured.identity.generation)
                          .put("displayId", info.optInt("displayId", -1));
                    } catch (org.json.JSONException impossible) {
                      return failure("NATIVE_CONTROL_RESULT_UNKNOWN");
                    }
                  return result;
                });
          }
        });
    return controls;
  }

  private final class Snapshot {
    final VirtualScreenManager owner = VirtualScreenManager.this;
    final VirtualScreenRequestFence identity;
    final String target;
    final BooleanSupplier authority;
    final String channel;
    final Context app;
    final VirtualScreenRequestScope scope;
    final String coreInstance;

    Snapshot(VirtualScreenRequestScope scope) {
      this.scope = scope;
      coreInstance = core == null ? "" : core.identity.instance;
      identity = new VirtualScreenRequestFence(activeEpoch, port, token, generation, stateRevision);
      target = scope.target;
      authority = scope::current;
      channel = VirtualScreenManager.this.channel;
      app = context;
    }
  }

  private Snapshot snapshot(String requiredGeneration, VirtualScreenRequestScope scope) {
    synchronized (LOCK) {
      if (starting || token.isEmpty() || activeEpoch != LIFECYCLE_EPOCH.get()) return null;
      if (requiredGeneration != null
          && (requiredGeneration.isEmpty() || !generation.equals(requiredGeneration))) return null;
      if (scope.request != null && scope.request.target instanceof VscreenTargetEvidence evidence) {
        if (core == null
            || !core.identity.instance.equals(evidence.coreInstance)
            || !evidence.generation.isEmpty() && !generation.equals(evidence.generation))
          return null;
      }
      return new Snapshot(scope);
    }
  }

  private boolean currentLocked(Snapshot snapshot) {
    return snapshot != null
        && snapshot.owner == this
        && !starting
        && snapshot.identity.matches(
            LIFECYCLE_EPOCH.get(), activeEpoch, port, token, generation, stateRevision);
  }

  private boolean current(Snapshot snapshot) {
    if (snapshot == null || !authorized(snapshot.authority)) return false;
    synchronized (LOCK) {
      return currentLocked(snapshot);
    }
  }

  private JSONObject perform(
      Snapshot snapshot, String route, String query, boolean changeGeneration) {
    return perform(snapshot, route, query, changeGeneration, true);
  }

  private JSONObject perform(
      Snapshot snapshot, String route, String query, boolean changeGeneration, boolean freshFrame) {
    if (snapshot == null) return missingSnapshot(null);
    if (!current(snapshot)) return failure("VSCREEN_REVOKED");
    if (snapshot.target != null
        && !snapshot.target.isEmpty()
        && !route.equals("/vscreen/launch")
        && !route.equals("/vscreen/status")
        && !route.equals("/vscreen/create"))
      query += (query.isEmpty() ? "" : "&") + "expectedPackage=" + encode(snapshot.target);
    if ((VirtualScreenRoutes.mutatesCore(route)
            || route.equals("/vscreen/create")
            || route.equals("/vscreen/preview"))
        && !snapshot.scope.claim()) return failure("SCREEN_TARGET_CHANGED");
    JSONObject value = requestAt(snapshot.identity.port, snapshot.identity.token, route, query);
    // Grant revision and target checks may use platform calls; keep them outside LOCK too.
    if (!authorized(snapshot.authority))
      return afterExecutionOrNotExecuted(
          value,
          VirtualScreenRoutes.mutatesCore(route),
          ScreenActionResult.TARGET_CHANGED_AFTER_EXECUTION,
          "SCREEN_TARGET_CHANGED");
    String rejection = null;
    boolean discardObserved = false;
    synchronized (LOCK) {
      if (!currentLocked(snapshot))
        return afterExecutionOrNotExecuted(
            value,
            VirtualScreenRoutes.mutatesCore(route),
            ScreenActionResult.RUN_CHANGED_AFTER_EXECUTION,
            "VSCREEN_REVOKED");
      if (!value.optBoolean("ok")) {
        lastError = value.optString("error");
        // 只在这里算出要记的摘要，落盘必须放到锁外（记录会写文件）。
        rejection = frameRejectionDetail(route, value);
      } else {
        String returned = value.optString("generation", generation);
        if (!generation.equals(returned)) {
          if (!changeGeneration)
            return afterExecutionOrNotExecuted(
                value,
                VirtualScreenRoutes.mutatesCore(route),
                ScreenActionResult.RUN_CHANGED_AFTER_EXECUTION,
                "STALE_GENERATION");
          generation = returned;
          semantics.invalidateObserved();
          discardObserved = true;
          stateRevision++;
          frameSequence = -1;
        }
        long sequence = value.optLong("frameSeq", -1);
        if (freshFrame
            && com.deepseekharness.app.util.VirtualScreenPolicy.frameRegressed(
                frameSequence,
                sequence,
                route.equals("/vscreen/status") || route.equals("/vscreen/preview")))
          return failure("STALE_FRAME");
        // 输入可能已经真的执行过：保留它的结果，但观察元数据只前进、绝不倒退。
        frameSequence =
            com.deepseekharness.app.util.VirtualScreenPolicy.advanceFrameSequence(
                frameSequence, sequence);
        tagLocked(value);
        try {
          value.put("coreInstance", snapshot.coreInstance);
        } catch (Exception ignored) {
        }
      }
    }
    if (discardObserved) semantics.clearObserved();
    if (rejection != null) recordFrameRejection(rejection);
    return value;
  }

  /** 帧门拒绝的可诊断摘要：稳定原因码 + 实际值/期望值；不含画面、坐标或用户内容。 */
  private static String frameRejectionDetail(String route, JSONObject value) {
    if (!"STALE_FRAME".equals(value.optString("error"))) return null;
    return route
        + " reason="
        + value.optString("reason", "UNKNOWN")
        + " requested="
        + value.optLong("requestedFrameSeq", -1)
        + " current="
        + value.optLong("currentFrameSeq", -1)
        + " observed="
        + value.optLong("observedFrameSeq", -1);
  }

  /** 同一 (route, reason) 的连续拒绝只记一条，且至少间隔 10 秒 —— 有界日志不能被常态拒绝刷掉。 */
  private static final long FRAME_REJECTION_INTERVAL_MS = 10_000;

  private final Object REJECTION_LOG = new Object();
  private String lastFrameRejection = "";
  private long lastFrameRejectionAt;

  /**
   * 帧门拒绝必须可诊断（issue #117）：写进有界事件记录，用户导出「自检与诊断」时能看到
   * 实际值/期望值，而不是只有一个 STALE_FRAME。
   */
  private void recordFrameRejection(String detail) {
    long now = clock.getAsLong();
    synchronized (REJECTION_LOG) {
      String key = detail.split(" requested=", 2)[0];
      if (key.equals(lastFrameRejection)
          && now - lastFrameRejectionAt < FRAME_REJECTION_INTERVAL_MS) return;
      lastFrameRejection = key;
      lastFrameRejectionAt = now;
    }
    recordDiagnostic("VSCREEN_INPUT_REJECTED", detail);
  }

  /** 诊断记录会写文件，一律交给 WORKER：绝不占住 ACTIONS 串行通道，也不在 LOCK 里落盘。 */
  private void recordDiagnostic(String stage, String detail) {
    try {
      WORKER.execute(
          () -> {
            try {
              com.deepseekharness.app.core.DiagnosticLog.record(context, stage, detail);
            } catch (Throwable ignored) {
            }
          });
    } catch (Throwable ignored) {
    }
  }

  private JSONObject missingSnapshot(String requiredGeneration) {
    synchronized (LOCK) {
      if (lifecycleState.cleanupPending())
        return decorate(failure("VSCREEN_" + lifecycleState.name()));
      if (requiredGeneration != null
          && (requiredGeneration.isEmpty() || !generation.equals(requiredGeneration)))
        return failure("STALE_GENERATION");
      return failure(
          token.isEmpty()
              ? "VSCREEN_NOT_RUNNING"
              : starting ? "VSCREEN_START_IN_PROGRESS" : "VSCREEN_REVOKED");
    }
  }

  private JSONObject performRequired(
      String gen, String route, String query, VirtualScreenRequestScope scope) {
    Snapshot session = snapshot(gen, scope);
    return session == null ? missingSnapshot(gen) : perform(session, route, query, false);
  }

  private static JSONObject afterExecutionOrNotExecuted(
      JSONObject value, boolean mutating, String marker, String notExecutedError) {
    if (mutating && value.optBoolean("executionUnknown")) return value;
    if (mutating && value.optBoolean("ok")) return markedAfterExecution(value, marker);
    return value.optBoolean("ok") ? failure(notExecutedError) : value;
  }

  private static JSONObject markedAfterExecution(JSONObject value, String marker) {
    try {
      value.put(marker, true);
    } catch (Exception ignored) {
    }
    return value;
  }

  private void tagLocked(JSONObject value) {
    try {
      value
          .put("channel", channel)
          .put("managerEpoch", activeEpoch)
          .put("managerRevision", stateRevision);
    } catch (Exception ignored) {
    }
  }

  public boolean frameCurrent(JSONObject value) {
    synchronized (LOCK) {
      return value != null
          && value.optBoolean("ok")
          && !token.isEmpty()
          && !starting
          && activeEpoch == LIFECYCLE_EPOCH.get()
          && value.optLong("managerEpoch", -2) == activeEpoch
          && value.optLong("managerRevision", -2) == stateRevision
          && generation.equals(value.optString("generation"))
          && value.optLong("frameSeq", -1) >= frameSequence;
    }
  }

  private JSONObject request(String route, String query, VirtualScreenRequestScope scope) {
    return perform(snapshot(null, scope), route, query, false);
  }

  private JSONObject ordered(Supplier<JSONObject> action) {
    ACTIONS.lock();
    try {
      return action.get();
    } finally {
      ACTIONS.unlock();
    }
  }

  public JSONObject start(String orientation) {
    return start(orientation, false);
  }

  public JSONObject start(String orientation, boolean foreground) {
    return start(orientation, localScope());
  }

  private JSONObject start(String orientation, VirtualScreenRequestScope scope) {
    return ordered(
        () -> {
          JSONObject outcome = startOrdered(orientation, scope);
          rememberStartOutcome(outcome);
          return outcome;
        });
  }

  static JSONObject safeStartOutcome(JSONObject value) throws Exception {
    JSONObject safe = new JSONObject().put("ok", value != null && value.optBoolean("ok"));
    if (value == null) return safe.put("error", "VSCREEN_START_RESULT_UNKNOWN");
    for (String key : new String[] {"error", "reason"}) {
      String code = value.optString(key);
      if (!code.isEmpty()) {
        boolean known =
            "reason".equals(key)
                ? java.util.Set.of("FRAME_NOT_OBSERVED", "FRAME_EXPIRED", "OBSERVATION_TIMEOUT")
                        .contains(code)
                    || startNotSentReason(code).equals(code)
                : !code.startsWith("VSCREEN_CORE_")
                    && code.equals(
                        com.deepseekharness.app.util.VirtualScreenBootstrap.diagnosticCode(code));
        safe.put(key, known ? code : "UNCLASSIFIED_FAILURE");
      }
    }
    if (value.has("cause")) safe.put("causeOmitted", true);
    return safe;
  }

  private void rememberStartOutcome(JSONObject value) {
    try {
      JSONObject safe = safeStartOutcome(value);
      java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
      final long outcomeEpoch;
      for (String key : new String[] {"ok", "error", "reason", "causeOmitted"})
        if (safe.has(key)) fields.put(key, safe.get(key));
      synchronized (LOCK) {
        outcomeEpoch = epoch();
        fields.put("state", lifecycleState.name());
        fields.put("starting", starting);
        fields.put("launchDispatched", launchDispatched);
        fields.put("cleanupPending", lifecycleState.cleanupPending());
        fields.put("coreKnown", core != null);
        fields.put("pendingKnown", pendingCleanup != null);
        fields.put("generationKnown", !generation.isEmpty());
        fields.put("channel", channel);
      }
      lastStartOutcomeJson = safe.toString();
      Context app = context;
      if (app != null)
        WORKER.execute(
            () ->
                com.deepseekharness.app.core.DiagnosticLog.record(
                    app,
                    "VSCREEN_START_RESULT",
                    "epoch=" + outcomeEpoch + " " + new JSONObject(fields)));
    } catch (Exception ignored) {
      // 诊断保存失败不能改变本次动作或解除未确认的退出屏障。
    }
  }

  private void recordStartException(String stage, Throwable error) {
    Context app = context;
    if (app == null) return;
    final long failureEpoch = epoch();
    try {
      WORKER.execute(
          () ->
              com.deepseekharness.app.core.DiagnosticLog.record(
                  app,
                  stage,
                  "epoch="
                      + failureEpoch
                      + " "
                      + new JSONObject(
                          com.deepseekharness.app.diagnostics.Failure.capture(
                                  stage, error, startExceptionMessagePolicy())
                              .toMap())));
    } catch (RuntimeException ignored) {
    }
  }

  static com.deepseekharness.app.diagnostics.Failure.MessagePolicy startExceptionMessagePolicy() {
    return com.deepseekharness.app.diagnostics.Failure.MessagePolicy.safeCodes(
        java.util.Set.of(
            "VSCREEN_JOURNAL_ROOT_UNAVAILABLE",
            "VSCREEN_JOURNAL_INVALID",
            "VSCREEN_JOURNAL_LIMIT",
            "VSCREEN_JOURNAL_FOREIGN",
            "VSCREEN_JOURNAL_LINK",
            "VSCREEN_JOURNAL_PERMISSIONS",
            "START_RESULT_UNKNOWN",
            "CORE_EXITED",
            "CORE_BOOTSTRAP_LIMIT",
            "CORE_BOOTSTRAP_INVALID"));
  }

  private JSONObject startOrdered(String orientation, VirtualScreenRequestScope scope) {
    if (BuildConfig.LOW_ANDROID) return failure("VSCREEN_UNSUPPORTED_LOW");
    if (!supported(context)) return failure("VSCREEN_API_30_REQUIRED");
    if (!"portrait".equals(orientation) && !"landscape".equals(orientation))
      return failure("INVALID_ORIENTATION");
    if (!scope.current()) return failure("SCREEN_TARGET_CHANGED");
    if (context == null) return failure("VSCREEN_APPLICATION_SCOPE_UNAVAILABLE");
    if (pendingCleanup != null) closeDetached(pendingCleanup);
    if (lifecycleState == VirtualScreenLifecycleState.START_UNKNOWN && bootChanged()) {
      synchronized (LOCK) {
        lifecycleState = VirtualScreenLifecycleState.STOPPED;
        attemptBoot = "";
        stateRevision++;
      }
      saveJournal();
    }
    if (lifecycleState.cleanupPending())
      return decorate(failure("VSCREEN_" + lifecycleState.name()));
    int width = "landscape".equals(orientation) ? 1792 : 1008;
    int height = "landscape".equals(orientation) ? 1008 : 1792;
    Snapshot existing = snapshot(null, scope);
    if (existing != null) {
      JSONObject result =
          perform(existing, "/vscreen/create", "width=" + width + "&height=" + height, true);
      if (result.optBoolean("ok")) visuals.present();
      return decorate(result);
    }
    final long launchEpoch;
    synchronized (LOCK) {
      if (starting) return failure("VSCREEN_START_IN_PROGRESS");
      if (!token.isEmpty()) return failure("VSCREEN_REVOKED");
      starting = true;
      launchDispatched = false;
      lifecycleState = VirtualScreenLifecycleState.STARTING;
      launchEpoch = LIFECYCLE_EPOCH.incrementAndGet();
      stateRevision++;
    }
    VirtualScreenChannel selected;
    try {
      selected = channels.choose(context);
    } catch (RuntimeException unavailable) {
      recordStartException("VSCREEN_CHANNEL_SELECTION_FAILED", unavailable);
      failStart(launchEpoch);
      return failure("DEVICE_CHANNEL_UNAVAILABLE");
    }
    if (selected == null) {
      failStart(launchEpoch);
      return failure("DEVICE_CHANNEL_UNAVAILABLE");
    }
    String command = launcher.launchCommand(context);
    synchronized (LOCK) {
      if (epoch() != launchEpoch || !starting) return failure("VSCREEN_START_CANCELLED");
      channel = selected.id();
      attemptBoot = currentBoot();
      stateRevision++;
    }
    if (attemptBoot.isEmpty() || !saveJournal()) {
      failStart(launchEpoch);
      return failure("VSCREEN_JOURNAL_UNAVAILABLE");
    }
    VirtualScreenHandshake launched = null;
    boolean dispatched = false;
    try {
      if (!scope.claim()) {
        failStart(launchEpoch);
        return failure("SCREEN_TARGET_CHANGED");
      }
      if ("adb".equals(selected.id())) reserveAdbLaunch(command, launchEpoch);
      synchronized (LOCK) {
        if (epoch() != launchEpoch || !starting)
          return decorate(failure("VSCREEN_START_CANCELLED"));
        launchDispatched = true;
      }
      dispatched = true;
      com.deepseekharness.app.device.DeviceTransportResult receipt =
          selected.startTyped(context, command);
      if (receipt != null
          && receipt.outcome.state
              == com.deepseekharness.app.capabilities.CapabilityResult.State.NOT_SENT
          && selected.id().equals(receipt.transport)
          && receipt.localExitConfirmed
          && receipt.outputComplete
          && receipt.exitCode == null
          && !receipt.remoteExitConfirmed) {
        String reason = startNotSentReason(receipt.outcome.code);
        CleanupCommit committed = persistStartNotSent(launchEpoch, selected.id(), reason);
        if (committed == CleanupCommit.SUPERSEDED) return failure("VSCREEN_START_CANCELLED");
        if (committed == CleanupCommit.JOURNAL_UNAVAILABLE) markStartUnknown(launchEpoch);
        JSONObject rejected =
            failure(
                committed == CleanupCommit.COMMITTED
                    ? "DEVICE_CHANNEL_UNAVAILABLE"
                    : "VSCREEN_JOURNAL_UNAVAILABLE");
        rejected.put("state", "NOT_SENT").put("reason", reason);
        return decorate(rejected);
      }
      String output = receipt == null ? "" : receipt.output;
      launched = launcher.parse(output);
      if (launched == null) {
        markStartUnknown(launchEpoch);
        String reason = com.deepseekharness.app.util.VirtualScreenBootstrap.error(output);
        recordDiagnostic(
            "VSCREEN_START",
            reason.isEmpty() ? "VSCREEN_START_RESULT_UNKNOWN" : "VSCREEN_" + reason);
        return decorate(failure("VSCREEN_START_UNKNOWN"));
      }
      com.deepseekharness.app.util.SensitiveData.registerKnownSecret(launched.token);
      boolean admitted, authorized = scope.current();
      synchronized (LOCK) {
        admitted = epoch() == launchEpoch && starting && authorized;
        if (admitted) {
          core = launched;
          token = launched.token;
          port = launched.port;
          activeEpoch = launchEpoch;
          stateRevision++;
        }
      }
      if (!admitted || !saveJournal()) {
        cleanupLaunched(launched, selected.id());
        return decorate(failure("VSCREEN_START_CANCELLED"));
      }
      JSONObject created =
          requestAt(
              launched.port,
              launched.token,
              "/vscreen/create",
              "width=" + width + "&height=" + height);
      recordCreateOutcome(created);
      boolean committed, live = scope.current();
      synchronized (LOCK) {
        committed = live && epoch() == launchEpoch && core == launched && created.optBoolean("ok");
        if (committed) {
          starting = false;
          lifecycleState = VirtualScreenLifecycleState.READY;
          generation = created.optString("generation");
          frameSequence = created.optLong("frameSeq", -1);
          stateRevision++;
          tagLocked(created);
        }
      }
      if (!committed) {
        cleanupLaunched(launched, selected.id());
        return decorate(created.optBoolean("ok") ? failure("VSCREEN_START_CANCELLED") : created);
      }
      if (!saveJournal()) {
        cleanupLaunched(launched, selected.id());
        return decorate(failure("VSCREEN_JOURNAL_UNAVAILABLE"));
      }
      ScheduledFuture<?> scheduled =
          WORKER.scheduleWithFixedDelay(() -> heartbeat(launchEpoch), 10, 10, TimeUnit.SECONDS);
      synchronized (LOCK) {
        if (epoch() == launchEpoch && activeEpoch == launchEpoch) heartbeat = scheduled;
        else scheduled.cancel(false);
      }
      visuals.present();
      return decorate(created);
    } catch (Throwable error) {
      recordStartException("VSCREEN_START_EXCEPTION", error);
      if (launched != null) cleanupLaunched(launched, selected.id());
      else if (dispatched) markStartUnknown(launchEpoch);
      else failStart(launchEpoch);
      return decorate(failure(dispatched ? "VSCREEN_START_UNKNOWN" : "VSCREEN_START_CANCELLED"));
    } finally {
      cancelAdbLaunch(launchEpoch);
    }
  }

  private void markStartUnknown(long launchEpoch) {
    synchronized (LOCK) {
      if (core == null
          && (epoch() == launchEpoch
              || lifecycleState == VirtualScreenLifecycleState.START_UNKNOWN)) {
        starting = false;
        lifecycleState = VirtualScreenLifecycleState.START_UNKNOWN;
        stateRevision++;
      }
    }
    saveJournal();
  }

  /** Only a native typed pre-send fact can commit this exact launch as safely stopped. */
  private CleanupCommit persistStartNotSent(long launchEpoch, String transport, String reason) {
    if (journal == null) return CleanupCommit.JOURNAL_UNAVAILABLE;
    try {
      synchronized (journal) {
        long revision;
        synchronized (LOCK) {
          if (!startNotSentCurrentLocked(launchEpoch, transport)) return CleanupCommit.SUPERSEDED;
          revision = ++stateRevision;
        }
        JSONObject terminal =
            new JSONObject()
                .put("state", "STOPPED")
                .put("boot", "")
                .put("channel", "")
                .put("handle", "")
                .put("lastStartOutcome", "NOT_SENT")
                .put("lastStartReason", reason);
        if (!journal.save(revision, terminal)) return CleanupCommit.SUPERSEDED;
        synchronized (LOCK) {
          if (!startNotSentCurrentLocked(launchEpoch, transport) || stateRevision != revision)
            return CleanupCommit.SUPERSEDED;
          starting = false;
          launchDispatched = false;
          lifecycleState = VirtualScreenLifecycleState.STOPPED;
          token = "";
          channel = "";
          generation = "";
          port = 0;
          activeEpoch = -1;
          attemptBoot = "";
          frameSequence = -1;
          return CleanupCommit.COMMITTED;
        }
      }
    } catch (Exception unavailable) {
      lastError = "VSCREEN_JOURNAL_UNAVAILABLE";
      recordStartException("VSCREEN_JOURNAL_SAVE_FAILED", unavailable);
      return CleanupCommit.JOURNAL_UNAVAILABLE;
    }
  }

  private boolean startNotSentCurrentLocked(long launchEpoch, String transport) {
    return epoch() == launchEpoch
        && starting
        && core == null
        && pendingCleanup == null
        && channel.equals(transport);
  }

  private static String startNotSentReason(String code) {
    return java.util.Set.of(
                "ROOT_NOT_ENABLED",
                "ROOT_UNAVAILABLE",
                "DEVICE_POLICY_BLOCKED",
                "SELF_PACKAGE_UNVERIFIED",
                "DEVICE_CANCELLED_BEFORE_SEND",
                "DEVICE_RUNNER_BUSY",
                "SHIZUKU_SERVICE_NOT_READY",
                "VSCREEN_START_NOT_SENT",
                "VSCREEN_ADB_INVALID_TICKET",
                "VSCREEN_ADB_CANCELLED_BEFORE_START",
                "VSCREEN_ADB_ENVIRONMENT_BUSY",
                "VSCREEN_ADB_NOT_COMMITTED")
            .contains(code)
        ? code
        : "VSCREEN_START_NOT_SENT";
  }

  private void cleanupLaunched(VirtualScreenHandshake launched, String selectedChannel) {
    ClosedSession closed;
    synchronized (LOCK) {
      core = launched;
      token = launched.token;
      port = launched.port;
      channel = selectedChannel;
      closed = detachLocked();
    }
    closeDetached(closed);
  }

  private void heartbeat(long launchEpoch) {
    Snapshot session = snapshot(null, new VirtualScreenRequestScope(null, null, null, ""));
    if (session == null || session.identity.epoch != launchEpoch) return;
    VirtualScreenChannel selected = channels.find(session.channel);
    boolean available = selected != null && selected.available(session.app);
    JSONObject ping =
        available
            ? perform(session, "/vscreen/ping", "", false)
            : failure("DEVICE_CHANNEL_UNAVAILABLE");
    if (!ping.optBoolean("ok")) {
      synchronized (LOCK) {
        if (currentLocked(session)) lifecycleState = VirtualScreenLifecycleState.DISCONNECTED;
      }
      stopIfCurrent(session);
    }
  }

  public JSONObject status() {
    return status(localScope());
  }

  private JSONObject status(VirtualScreenRequestScope scope) {
    return decorate(request("/vscreen/status", "", scope));
  }

  public JSONObject preview() {
    return preview(localScope());
  }

  private JSONObject preview(VirtualScreenRequestScope scope) {
    return request("/vscreen/preview", "", scope);
  }

  private JSONObject agentSee(VirtualScreenRequestScope scope) {
    if (scope.request != null
        && scope.request.target instanceof VscreenTargetEvidence approved
        && approved.observation != VscreenTargetEvidence.Observation.DISPLAY)
      return failure("CORE_EVIDENCE_REQUIRED");
    if (scope.request == null
        || !com.deepseekharness.app.DshaAccessibilityService.isConnected()
        || !(scope.request.target instanceof VscreenTargetEvidence evidence)
        || evidence.windowId < 0
        || evidence.consentRevision < 0) {
      JSONObject image = preview(scope);
      try {
        image
            .put("anchoredAvailable", false)
            .put("availabilityReason", "ANCHOR_ACCESSIBILITY_UNAVAILABLE")
            .put("sourceFrameSeq", image.optLong("frameSeq", -1));
      } catch (Exception ignored) {
      }
      return image;
    }
    return observed("see", null, -1, "", -1, -1, scope);
  }

  private JSONObject observed(
      String operation,
      String gen,
      long source,
      String id,
      int x,
      int y,
      VirtualScreenRequestScope scope) {
    return ordered(
        () -> {
          Snapshot session = snapshot(gen, scope);
          if (session == null) return missingSnapshot(gen);
          if (scope.request == null) return failure("EXPLICIT_SCREEN_REQUEST_REQUIRED");
          JSONObject info = perform(session, "/vscreen/status", "", false, false);
          if (!info.optBoolean("ok")) return info;
          try {
            JSONObject args =
                new JSONObject()
                    .put("coreInstance", session.coreInstance)
                    .put("generation", session.identity.generation)
                    .put("frameSeq", source)
                    .put("managerEpoch", session.identity.epoch)
                    .put("managerRevision", session.identity.revision)
                    .put(operation.equals("anchor") ? "observationId" : "anchorId", id)
                    .put("x", x)
                    .put("y", y);
            VirtualScreenHandshake endpoint = core;
            if (endpoint == null || !endpoint.identity.instance.equals(session.coreInstance))
              return failure("VSCREEN_CORE_IDENTITY_CHANGED");
            VirtualScreenAccessibility.ObservedPixels pixels =
                new VirtualScreenAccessibility.ObservedPixels() {
                  public JSONObject capture(boolean retain) {
                    JSONObject frame =
                        perform(
                            session,
                            "/vscreen/observe",
                            "generation="
                                + encode(session.identity.generation)
                                + "&retain="
                                + retain,
                            false,
                            false);
                    if (retain
                        && !frame.optBoolean("ok")
                        && java.util.Set.of(
                                "OBSERVATION_IMAGE_BUDGET", "OBSERVATION_IMAGE_UNAVAILABLE")
                            .contains(frame.optString("error"))) {
                      String reason = frame.optString("error");
                      frame =
                          perform(
                              session,
                              "/vscreen/observe",
                              "generation=" + encode(session.identity.generation) + "&retain=false",
                              false,
                              false);
                      try {
                        frame.put("availabilityReason", reason);
                      } catch (Exception ignored) {
                      }
                    }
                    return frame;
                  }

                  public JSONObject region(String observationId, android.graphics.Rect bounds) {
                    return perform(
                        session,
                        "/vscreen/observation-region",
                        "generation="
                            + encode(session.identity.generation)
                            + "&observationId="
                            + encode(observationId)
                            + "&left="
                            + bounds.left
                            + "&top="
                            + bounds.top
                            + "&right="
                            + bounds.right
                            + "&bottom="
                            + bounds.bottom,
                        false,
                        false);
                  }

                  public void release(String observationId) {
                    // Teardown uses only the original authenticated endpoint, never a newly
                    // selected core.
                    queueObservationRelease(endpoint, observationId);
                  }

                  public ScheduledFuture<?> expireAfter(Runnable cleanup, long millis) {
                    return WORKER.schedule(cleanup, millis, TimeUnit.MILLISECONDS);
                  }
                };
            JSONObject result =
                semantics.observed(
                    scope.broker,
                    scope.request,
                    () -> current(session),
                    info.optInt("displayId", -1),
                    operation,
                    args,
                    pixels);
            return current(session)
                ? result
                : afterExecutionOrNotExecuted(
                    result,
                    operation.equals("anchored-click"),
                    ScreenActionResult.RUN_CHANGED_AFTER_EXECUTION,
                    "VSCREEN_REVOKED");
          } catch (Exception unavailable) {
            return failure("ANCHOR_NOT_AVAILABLE");
          }
        });
  }

  private void queueObservationRelease(VirtualScreenHandshake endpoint, String id) {
    synchronized (OBSERVATION_RELEASES) {
      String key = endpoint.identity.instance + ":" + id;
      // The Core owns at most two raw copies. Superseded cleanup is covered by its eviction/TTL;
      // dropping a cleanup request never authorizes an expired local lease or proves bytes freed.
      while (observationReleases.size() >= 2 && !observationReleases.containsKey(key))
        observationReleases.remove(observationReleases.keySet().iterator().next());
      observationReleases.put(
          key,
          () ->
              transport.request(
                  endpoint, "/vscreen/observation-release", "observationId=" + encode(id)));
      if (!observationReleaseScheduled) scheduleObservationReleasesLocked();
    }
  }

  private void scheduleObservationReleasesLocked() {
    observationReleaseScheduled = true;
    try {
      WORKER.execute(this::drainObservationReleases);
    } catch (RejectedExecutionException stopped) {
      observationReleaseScheduled = false;
      observationReleases.clear();
    }
  }

  private void drainObservationReleases() {
    for (int count = 0; count < 2; count++) {
      Runnable release;
      synchronized (OBSERVATION_RELEASES) {
        if (observationReleases.isEmpty()) break;
        String key = observationReleases.keySet().iterator().next();
        release = observationReleases.remove(key);
      }
      try {
        release.run();
      } catch (RuntimeException unavailable) {
      }
    }
    synchronized (OBSERVATION_RELEASES) {
      observationReleaseScheduled = false;
      if (!observationReleases.isEmpty()) scheduleObservationReleasesLocked();
    }
  }

  /** 授权与语义操作只需要本次显示身份；旧像素元数据不能阻断它们。 */
  public JSONObject targetStatus() {
    return targetStatus(localScope());
  }

  private JSONObject targetStatus(VirtualScreenRequestScope scope) {
    return perform(snapshot(null, scope), "/vscreen/status", "", false, false);
  }

  public JSONObject previewSince(String gen, long seq) {
    return previewSince(gen, seq, localScope());
  }

  private JSONObject previewSince(String gen, long seq, VirtualScreenRequestScope scope) {
    return request("/vscreen/preview", "since=" + seq + "&generation=" + encode(gen), scope);
  }

  public JSONObject touch(String gen, long seq, String stroke, int action, float x, float y) {
    return touch(gen, seq, stroke, action, x, y, localScope());
  }

  private JSONObject touch(
      String gen,
      long seq,
      String stroke,
      int action,
      float x,
      float y,
      VirtualScreenRequestScope scope) {
    return ordered(
        () ->
            performRequired(
                gen,
                "/vscreen/touch",
                "generation="
                    + encode(gen)
                    + "&frameSeq="
                    + seq
                    + "&stroke="
                    + stroke
                    + "&action="
                    + action
                    + "&x="
                    + x
                    + "&y="
                    + y,
                scope));
  }

  public JSONObject editor(String gen, String operation, JSONObject args) {
    return editor(gen, operation, args, localScope());
  }

  private JSONObject editor(
      String gen, String operation, JSONObject args, VirtualScreenRequestScope scope) {
    return ordered(
        () -> {
          Snapshot session = snapshot(gen, scope);
          if (session == null) return missingSnapshot(gen);
          JSONObject info = perform(session, "/vscreen/status", "", false, false);
          if (!info.optBoolean("ok")) return info;
          if (!current(session)) return failure("VSCREEN_REVOKED");
          JSONObject result =
              semantics.control(
                  scope.broker,
                  scope.request,
                  () -> current(session),
                  info.optInt("displayId", -1),
                  operation,
                  args);
          if (current(session)) return result;
          // edit/submit 可能已经写进去了：保留真实结果并标记，只有只读的 editor 仍报「未执行」（#140）。
          return afterExecutionOrNotExecuted(
              result,
              VirtualScreenRoutes.mutates(operation),
              ScreenActionResult.TARGET_CHANGED_AFTER_EXECUTION,
              "SCREEN_TARGET_CHANGED");
        });
  }

  public JSONObject tree() {
    return tree(localScope());
  }

  private JSONObject tree(VirtualScreenRequestScope scope) {
    return ordered(
        () -> {
          Snapshot session = snapshot(null, scope);
          JSONObject frame = perform(session, "/vscreen/status", "", false, false);
          if (!frame.optBoolean("ok")) return frame;
          if (!current(session)) return failure("VSCREEN_REVOKED");
          JSONObject result =
              semantics.control(
                  scope.broker,
                  scope.request,
                  () -> current(session),
                  frame.optInt("displayId", -1),
                  "tree",
                  new JSONObject());
          if (!current(session)) return failure("SCREEN_TARGET_CHANGED");
          try {
            result
                .put("generation", frame.optString("generation"))
                .put("frameSeq", frame.optLong("frameSeq"))
                .put("displayId", frame.optInt("displayId"));
          } catch (Exception ignored) {
          }
          return result;
        });
  }

  public JSONObject node(String gen, long seq, String node, String action, String text) {
    return node(gen, seq, node, action, text, localScope());
  }

  private JSONObject node(
      String gen,
      long seq,
      String node,
      String action,
      String text,
      VirtualScreenRequestScope scope) {
    return ordered(
        () -> {
          Snapshot session = snapshot(gen, scope);
          if (session == null) return missingSnapshot(gen);
          // node 按 nodeId 走无障碍 performAction：目标是否还在、是否还在同一窗口，由无障碍层的
          // STALE_TREE（树 ≤30 秒）+ refresh() + windowId 判定。核心的像素帧门只对坐标注入有意义，
          // 前置它只会让 tree → node 这条零截图链路在动态界面下必然断掉（issue #117）。
          JSONObject info = perform(session, "/vscreen/status", "", false, false);
          if (!info.optBoolean("ok")) return info;
          if (!current(session)) return failure("VSCREEN_REVOKED");
          try {
            JSONObject result =
                semantics.control(
                    scope.broker,
                    scope.request,
                    () -> current(session),
                    info.optInt("displayId", -1),
                    "node",
                    new JSONObject().put("nodeId", node).put("action", action).put("text", text));
            return current(session)
                ? result
                : afterExecutionOrNotExecuted(
                    result,
                    true,
                    ScreenActionResult.TARGET_CHANGED_AFTER_EXECUTION,
                    "SCREEN_TARGET_CHANGED");
          } catch (Exception invalid) {
            return failure("INVALID_NODE_ACTION");
          }
        });
  }

  public JSONObject launch(String pkg) {
    return launch(pkg, localScope());
  }

  private JSONObject launch(String pkg, VirtualScreenRequestScope scope) {
    if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))
      return failure("INVALID_PACKAGE");
    return ordered(
        () -> {
          if (!scope.claim()) return failure("SCREEN_TARGET_CHANGED");
          semantics.clear();
          return request("/vscreen/launch", "package=" + encode(pkg), scope);
        });
  }

  public JSONObject action(String action, String gen, long seq, String params) {
    return action(action, gen, seq, params, localScope());
  }

  private JSONObject action(
      String action, String gen, long seq, String params, VirtualScreenRequestScope scope) {
    return ordered(
        () ->
            performRequired(
                gen,
                "/vscreen/" + action,
                "generation="
                    + encode(gen)
                    + "&frameSeq="
                    + seq
                    + (params.isEmpty() ? "" : "&" + params),
                scope));
  }

  public JSONObject type(String gen, long seq, String text) {
    return type(gen, seq, text, localScope());
  }

  private JSONObject type(String gen, long seq, String text, VirtualScreenRequestScope scope) {
    if (text == null || text.isEmpty() || text.length() > 2000) return failure("INVALID_TEXT");
    return ordered(
        () -> {
          Snapshot session = snapshot(gen, scope);
          if (session == null) return missingSnapshot(gen);
          String query = "generation=" + encode(gen) + "&frameSeq=" + seq;
          if (text.matches("[\\x20-\\x7e]+"))
            return perform(session, "/vscreen/type", query + "&text=" + encode(text), false);
          if (!com.deepseekharness.app.DshaAccessibilityService.isConnected())
            return failure("ACCESSIBILITY_REQUIRED_FOR_UNICODE");
          JSONObject check = perform(session, "/vscreen/check", query, false);
          if (!check.optBoolean("ok")) return check;
          if (!current(session)) return failure("VSCREEN_REVOKED");
          JSONObject result =
              semantics.type(
                  scope.broker,
                  scope.request,
                  () -> current(session),
                  check.optInt("displayId", -1),
                  text);
          return current(session)
              ? result
              : afterExecutionOrNotExecuted(
                  result,
                  true,
                  ScreenActionResult.TARGET_CHANGED_AFTER_EXECUTION,
                  "SCREEN_TARGET_CHANGED");
        });
  }

  private static final class ClosedSession {
    final int port;
    final String token, channel;
    final long fence;
    final VirtualScreenHandshake handle;
    volatile String probeReason = "NOT_CHECKED";

    ClosedSession(VirtualScreenHandshake handle, String channel, long fence) {
      this.handle = handle;
      this.port = handle == null ? 0 : handle.port;
      this.token = handle == null ? "" : handle.token;
      this.channel = channel;
      this.fence = fence;
    }
  }

  private ClosedSession detachLocked() {
    long stopped = LIFECYCLE_EPOCH.getAndIncrement();
    semantics.invalidateObserved();
    if (cleanupProbe != null) {
      cleanupProbe.cancel(false);
      cleanupProbe = null;
    }
    boolean unresolvedStart = starting && core == null && launchDispatched;
    starting = false;
    launchDispatched = false;
    cancelAdbLaunch(stopped);
    if (heartbeat != null) {
      heartbeat.cancel(false);
      heartbeat = null;
    }
    ClosedSession closed =
        core != null
            ? new ClosedSession(core, channel, stopped + 1)
            : pendingCleanup == null
                ? null
                : new ClosedSession(pendingCleanup.handle, pendingCleanup.channel, stopped + 1);
    if (closed != null) {
      pendingCleanup = closed;
      lifecycleState =
          lifecycleState == VirtualScreenLifecycleState.DISCONNECTED
              ? VirtualScreenLifecycleState.DISCONNECTED
              : VirtualScreenLifecycleState.CLOSING;
    } else if (unresolvedStart || lifecycleState == VirtualScreenLifecycleState.START_UNKNOWN)
      lifecycleState = VirtualScreenLifecycleState.START_UNKNOWN;
    else lifecycleState = VirtualScreenLifecycleState.STOPPED;
    core = null;
    token = "";
    channel = "";
    generation = "";
    port = 0;
    activeEpoch = -1;
    stateRevision++;
    frameSequence = -1;
    return closed;
  }

  private JSONObject closeDetached(ClosedSession closed) {
    semantics.clearObserved();
    saveJournal();
    if (closed == null) {
      long fence = epoch();
      ACTIONS.lock();
      try {
        if (epoch() == fence) semantics.clear();
      } finally {
        ACTIONS.unlock();
      }
      visuals.stopped(fence);
      return decorate(
          lifecycleState == VirtualScreenLifecycleState.STOPPED
              ? success()
              : failure("VSCREEN_" + lifecycleState.name()));
    }
    // A close reply proves release only. Only the bound, read-only birth probe closes this record.
    JSONObject release = requestAt(closed.port, closed.token, "/vscreen/close", "");
    VirtualScreenCoreIdentity.Exit proof = probeClosed(closed, 3);
    CleanupCommit commit =
        proof == VirtualScreenCoreIdentity.Exit.EXITED
            ? persistCleanupExited(closed)
            : CleanupCommit.NOT_PROVEN;
    boolean completed = commit == CleanupCommit.COMMITTED;
    synchronized (LOCK) {
      if (!completed && cleanupCurrentLocked(closed)) {
        lifecycleState = VirtualScreenLifecycleState.CLOSE_UNKNOWN;
        stateRevision++;
      }
    }
    if (!completed) {
      saveJournal();
      scheduleCleanupProbe(closed, 0);
    }
    ACTIONS.lock();
    try {
      if (epoch() == closed.fence) semantics.clear();
    } finally {
      ACTIONS.unlock();
    }
    visuals.stopped(closed.fence);
    JSONObject result = completed ? success() : failure("VSCREEN_CLOSE_UNKNOWN");
    try {
      result
          .put("coreExitProof", proof.name())
          .put("displayReleaseAcknowledged", release.optBoolean("ok"));
      if (commit == CleanupCommit.JOURNAL_UNAVAILABLE)
        result.put("reason", "VSCREEN_JOURNAL_UNAVAILABLE");
      else if (commit == CleanupCommit.SUPERSEDED) result.put("reason", "VSCREEN_CLOSE_SUPERSEDED");
    } catch (Exception ignored) {
    }
    return decorate(result);
  }

  private boolean cleanupCurrentLocked(ClosedSession closed) {
    return pendingCleanup == closed && epoch() == closed.fence && core == null;
  }

  /** Read-only birth probes use the original channel and identity; never resend close or input. */
  private VirtualScreenCoreIdentity.Exit probeClosed(ClosedSession closed, int attempts) {
    VirtualScreenCoreIdentity.Exit proof = VirtualScreenCoreIdentity.Exit.UNKNOWN;
    long probeEpoch = closed.fence;
    try {
      synchronized (LOCK) {
        if (!cleanupCurrentLocked(closed)) {
          closed.probeReason = "IDENTITY_SUPERSEDED";
          return proof;
        }
      }
      VirtualScreenChannel selected = channels.find(closed.channel);
      if (selected == null || !selected.available(context)) {
        closed.probeReason = "ORIGINAL_CHANNEL_UNAVAILABLE";
        return proof;
      }
      String command = launcher.probeCommand(context, closed.handle.identity);
      for (int attempt = 0; attempt < attempts; attempt++) {
        synchronized (LOCK) {
          if (!cleanupCurrentLocked(closed)) {
            closed.probeReason = "IDENTITY_SUPERSEDED";
            return VirtualScreenCoreIdentity.Exit.UNKNOWN;
          }
        }
        if ("adb".equals(closed.channel)
            && !ADB_PROBE.issue(
                randomToken(), command, probeEpoch, clock.getAsLong(), ADB_LAUNCH_TTL_MS)) {
          closed.probeReason = "ADB_PROBE_AUTHORITY_UNAVAILABLE";
          return VirtualScreenCoreIdentity.Exit.UNKNOWN;
        }
        try {
          proof = closed.handle.identity.proof(selected.start(context, command));
          closed.probeReason =
              proof == VirtualScreenCoreIdentity.Exit.EXITED
                  ? "EXIT_CONFIRMED"
                  : proof == VirtualScreenCoreIdentity.Exit.ALIVE
                      ? "CORE_STILL_ALIVE"
                      : "EXIT_PROBE_REPLY_UNKNOWN";
        } finally {
          ADB_PROBE.cancel(probeEpoch);
        }
        if (proof != VirtualScreenCoreIdentity.Exit.ALIVE || attempt + 1 == attempts) break;
        Thread.sleep(50);
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      closed.probeReason = "EXIT_PROBE_INTERRUPTED";
    } catch (Throwable unavailable) {
      proof = VirtualScreenCoreIdentity.Exit.UNKNOWN;
      closed.probeReason = "EXIT_PROBE_UNAVAILABLE";
    } finally {
      ADB_PROBE.cancel(probeEpoch);
    }
    return proof;
  }

  private void scheduleCleanupProbe(ClosedSession closed, int attempt) {
    synchronized (LOCK) {
      if (!cleanupCurrentLocked(closed)
          || lifecycleState != VirtualScreenLifecycleState.CLOSE_UNKNOWN
          || attempt < 0
          || attempt >= CLEANUP_PROBE_ATTEMPTS) return;
      // Single lifecycle worker: at most one original-channel probe is in flight. Its existing
      // transport deadline still applies; a stalled/unknown transport never becomes exit proof.
      try {
        cleanupProbe =
            WORKER.schedule(
                () -> reconcileCleanup(closed, attempt),
                cleanupProbeDelayMillis(attempt),
                TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException stopped) {
        cleanupProbe = null;
      }
    }
  }

  private void reconcileCleanup(ClosedSession closed, int attempt) {
    synchronized (LOCK) {
      if (!cleanupCurrentLocked(closed)
          || lifecycleState != VirtualScreenLifecycleState.CLOSE_UNKNOWN) return;
    }
    VirtualScreenCoreIdentity.Exit proof = probeClosed(closed, 1);
    if (proof == VirtualScreenCoreIdentity.Exit.EXITED
        && persistCleanupExited(closed) == CleanupCommit.COMMITTED) return;
    scheduleCleanupProbe(closed, attempt + 1);
  }

  private enum CleanupCommit {
    NOT_PROVEN,
    COMMITTED,
    JOURNAL_UNAVAILABLE,
    SUPERSEDED
  }

  /** Both the initial close and later read-only checks keep the barrier until durable commit. */
  private CleanupCommit persistCleanupExited(ClosedSession closed) {
    if (journal == null) return CleanupCommit.JOURNAL_UNAVAILABLE;
    try {
      synchronized (journal) {
        long revision;
        synchronized (LOCK) {
          if (!cleanupCurrentLocked(closed)) return CleanupCommit.SUPERSEDED;
          revision = ++stateRevision;
        }
        JSONObject terminal =
            new JSONObject()
                .put("state", "STOPPED")
                .put("boot", "")
                .put("channel", "")
                .put("handle", "");
        if (!journal.save(revision, terminal)) return CleanupCommit.SUPERSEDED;
        synchronized (LOCK) {
          if (!cleanupCurrentLocked(closed) || stateRevision != revision)
            return CleanupCommit.SUPERSEDED;
          pendingCleanup = null;
          lifecycleState = VirtualScreenLifecycleState.STOPPED;
          attemptBoot = "";
          cleanupProbe = null;
          return CleanupCommit.COMMITTED;
        }
      }
    } catch (Exception unavailable) {
      lastError = "VSCREEN_JOURNAL_UNAVAILABLE";
      recordStartException("VSCREEN_JOURNAL_SAVE_FAILED", unavailable);
      return CleanupCommit.JOURNAL_UNAVAILABLE;
    }
  }

  private JSONObject stopResult() {
    ClosedSession closed;
    synchronized (LOCK) {
      closed = detachLocked();
    }
    return closeDetached(closed);
  }

  private JSONObject closeExpected(String expectedCore) {
    ClosedSession closed;
    synchronized (LOCK) {
      VirtualScreenHandshake handle =
          core != null ? core : pendingCleanup == null ? null : pendingCleanup.handle;
      if (handle != null && !handle.identity.instance.equals(expectedCore))
        return identityFailure(
            expectedCore.isEmpty()
                ? "VSCREEN_CORE_IDENTITY_REQUIRED"
                : "VSCREEN_CORE_IDENTITY_CHANGED");
      if (handle == null && !expectedCore.isEmpty())
        return identityFailure("VSCREEN_CORE_IDENTITY_CHANGED");
      closed = detachLocked();
    }
    return closeDetached(closed);
  }

  public JSONObject close() {
    return stopResult();
  }

  public void stop() {
    stopResult();
  }

  private void stopIfCurrent(Snapshot session) {
    ClosedSession closed;
    synchronized (LOCK) {
      if (!currentLocked(session)) return;
      closed = detachLocked();
    }
    closeDetached(closed);
  }

  public void discardObservations() {
    semantics.clearObserved();
  }

  public void revoke() {
    long previous = LIFECYCLE_EPOCH.getAndIncrement();
    discardObservations();
    ADB_LAUNCH.cancel(previous);
    long revoked = previous + 1;
    WORKER.execute(
        () -> {
          ClosedSession closed;
          synchronized (LOCK) {
            if (epoch() != revoked) return;
            closed = detachLocked();
          }
          closeDetached(closed);
        });
  }

  private void failStart(long launchEpoch) {
    synchronized (LOCK) {
      if (epoch() == launchEpoch) {
        starting = false;
        launchDispatched = false;
        lifecycleState = VirtualScreenLifecycleState.STOPPED;
        token = "";
        channel = "";
        generation = "";
        port = 0;
        activeEpoch = -1;
        stateRevision++;
        frameSequence = -1;
      }
    }
    saveJournal();
  }

  public VirtualScreenLifecycleState lifecycleState() {
    return lifecycleState;
  }

  private JSONObject decorate(JSONObject value) {
    try {
      ClosedSession cleanup;
      VirtualScreenLifecycleState state;
      synchronized (LOCK) {
        cleanup = pendingCleanup;
        state = lifecycleState;
      }
      value
          .put("lifecycleState", state.name())
          .put("cleanupPending", state.cleanupPending())
          .put("protocol", 1);
      if (state.cleanupPending()) {
        boolean recoverable = cleanup != null && cleanup.handle != null;
        value
            .put("cleanupIdentityAvailable", recoverable)
            .put(
                "cleanupChannel",
                recoverable && java.util.Set.of("root", "shizuku", "adb").contains(cleanup.channel)
                    ? cleanup.channel
                    : "UNKNOWN")
            .put("cleanupProbeReason", recoverable ? cleanup.probeReason : "IDENTITY_NOT_CAPTURED");
      }
      if (!value.has("state"))
        value.put(
            "state",
            value.optBoolean("executionUnknown")
                ? "UNKNOWN"
                : value.optBoolean("ok") ? "COMPLETED" : "REJECTED");
      // Successful observations already carry the snapshot's identity. Never relabel A with B.
      VirtualScreenHandshake current = core;
      if (!value.has("coreInstance") && !value.optBoolean("ok")) {
        if (current != null) value.put("coreInstance", current.identity.instance);
        else if (cleanup != null) value.put("coreInstance", cleanup.handle.identity.instance);
      }
    } catch (Exception ignored) {
    }
    return value;
  }

  private static String readBoot() {
    // Fixed kernel fact, bounded and available on API23; no NIO class is resolved by Low.
    try (java.io.InputStream input =
        new java.io.FileInputStream("/proc/sys/kernel/random/boot_id")) {
      byte[] bytes = new byte[64];
      int used = 0, count;
      while (used < bytes.length && (count = input.read(bytes, used, bytes.length - used)) >= 0) {
        if (count > 0) used += count;
      }
      if (used == bytes.length) return "";
      String boot = new String(bytes, 0, used, java.nio.charset.StandardCharsets.US_ASCII).trim();
      return Ids.uuid(boot) ? boot : "";
    } catch (Exception unavailable) {
      return "";
    }
  }

  private boolean bootChanged() {
    String boot = currentBoot();
    return !attemptBoot.isEmpty() && !boot.isEmpty() && !attemptBoot.equals(boot);
  }

  private String currentBoot() {
    try {
      String boot = bootSource.get();
      return Ids.uuid(boot) ? boot : "";
    } catch (RuntimeException unavailable) {
      return "";
    }
  }

  private void restoreJournal() {
    if (journal == null) return;
    try {
      JSONObject record = journal.load();
      if (record == null || record.optString("state").equals("STOPPED")) return;
      attemptBoot = record.optString("boot");
      if (bootChanged()) return;
      String payload = record.optString("handle");
      if (payload.isEmpty()) lifecycleState = VirtualScreenLifecycleState.START_UNKNOWN;
      else {
        VirtualScreenHandshake handle = VirtualScreenHandshake.parse(payload);
        pendingCleanup = new ClosedSession(handle, record.getString("channel"), epoch());
        lifecycleState = VirtualScreenLifecycleState.DISCONNECTED;
      }
    } catch (Exception invalid) {
      recordStartException("VSCREEN_JOURNAL_LOAD_FAILED", invalid);
      lifecycleState = VirtualScreenLifecycleState.START_UNKNOWN;
      lastError = "VSCREEN_JOURNAL_INVALID";
    }
  }

  private boolean saveJournal() {
    if (journal == null) return false;
    try {
      synchronized (journal) {
        JSONObject record = new JSONObject();
        long revision;
        synchronized (LOCK) {
          revision = stateRevision;
          VirtualScreenHandshake handle =
              core != null ? core : pendingCleanup == null ? null : pendingCleanup.handle;
          record
              .put("state", lifecycleState.name())
              .put("boot", attemptBoot)
              .put(
                  "channel",
                  core != null ? channel : pendingCleanup == null ? "" : pendingCleanup.channel)
              .put("handle", handle == null ? "" : handle.payload());
        }
        return journal.save(revision, record);
      }
    } catch (Exception unavailable) {
      lastError = "VSCREEN_JOURNAL_UNAVAILABLE";
      recordStartException("VSCREEN_JOURNAL_SAVE_FAILED", unavailable);
      return false;
    }
  }

  /** One-shot ADB start authority exists only while this native manager launch is pending. */
  private void reserveAdbLaunch(String command, long epoch) {
    if (!ADB_LAUNCH.issue(randomToken(), command, epoch, clock.getAsLong(), ADB_LAUNCH_TTL_MS))
      throw new IllegalStateException("VSCREEN_ADB_LAUNCH_BUSY");
  }

  public String adbLaunchTicketFor(String command) {
    String ticket = ADB_LAUNCH.ticketFor(command, epoch(), clock.getAsLong());
    return ticket.isEmpty() ? ADB_PROBE.ticketFor(command, epoch(), clock.getAsLong()) : ticket;
  }

  public boolean authorizeAdbLaunchPlan(String ticket, String command) {
    return ADB_LAUNCH.authorize(ticket, command, epoch(), clock.getAsLong())
        || ADB_PROBE.authorize(ticket, command, epoch(), clock.getAsLong());
  }

  public boolean commitAdbLaunch(String ticket) {
    return ADB_LAUNCH.commit(ticket, epoch(), clock.getAsLong())
        || ADB_PROBE.commit(ticket, epoch(), clock.getAsLong());
  }

  /** Native helper completion only: no HTTP route can manufacture this pre-send proof. */
  public boolean withdrawUncommittedAdbLaunch(String ticket, String command) {
    long owner = epoch(), now = clock.getAsLong();
    return ADB_LAUNCH.withdrawUncommitted(ticket, command, owner, now)
        || ADB_PROBE.withdrawUncommitted(ticket, command, owner, now);
  }

  public void cancelAdbLaunch(long epoch) {
    ADB_LAUNCH.cancel(epoch);
  }

  public String bridge(String route, String query) {
    try {
      return bridge(com.deepseekharness.app.util.VscreenBridgeRequest.query(route, query));
    } catch (java.io.IOException invalid) {
      return failure(invalid.getMessage()).toString();
    }
  }

  public String bridge(com.deepseekharness.app.util.VscreenBridgeRequest request) {
    if (request.operation != com.deepseekharness.app.util.VscreenBridgeRequest.Operation.STATUS
        && request.operation != com.deepseekharness.app.util.VscreenBridgeRequest.Operation.CLOSE)
      return failure("EXPLICIT_SCREEN_REQUEST_REQUIRED").toString();
    return dispatch(request, localScope());
  }

  public String bridge(
      CapabilityBroker broker,
      CapabilityRequest capability,
      com.deepseekharness.app.util.VscreenBridgeRequest request,
      BooleanSupplier current) {
    if (broker == null || capability == null)
      return failure("EXPLICIT_SCREEN_REQUEST_REQUIRED").toString();
    String caller = request.parameters.getOrDefault("coreInstance", "");
    if (request.requiresExistingCore() || !caller.isEmpty()) {
      if (caller.isEmpty()) return identityFailure("VSCREEN_CORE_IDENTITY_REQUIRED").toString();
      if (!(capability.target instanceof VscreenTargetEvidence evidence))
        return identityFailure("CORE_EVIDENCE_REQUIRED").toString();
      synchronized (LOCK) {
        VirtualScreenHandshake handle =
            core != null
                ? core
                : request.operation
                            == com.deepseekharness.app.util.VscreenBridgeRequest.Operation.CLOSE
                        && pendingCleanup != null
                    ? pendingCleanup.handle
                    : null;
        if (handle == null
            || !caller.equals(handle.identity.instance)
            || !caller.equals(evidence.coreInstance))
          return identityFailure("VSCREEN_CORE_IDENTITY_CHANGED").toString();
      }
      String generation = request.parameters.getOrDefault("generation", "");
      if (!generation.isEmpty() && !generation.equals(evidence.generation))
        return identityFailure("VSCREEN_GENERATION_CHANGED").toString();
    }
    return dispatch(request, new VirtualScreenRequestScope(broker, capability, current, ""));
  }

  private String dispatch(
      com.deepseekharness.app.util.VscreenBridgeRequest request, VirtualScreenRequestScope scope) {
    return decorate(
            request.execute(
                new com.deepseekharness.app.util.VscreenBridgeRequest.Actions<JSONObject>() {
                  public JSONObject create(String orientation) {
                    return VirtualScreenManager.this.start(orientation, scope);
                  }

                  public JSONObject status() {
                    return VirtualScreenManager.this.status(scope);
                  }

                  public JSONObject launch(String pkg) {
                    return VirtualScreenManager.this.launch(pkg, scope);
                  }

                  public JSONObject tree() {
                    return VirtualScreenManager.this.tree(scope);
                  }

                  public JSONObject node(
                      String gen, long seq, String id, String action, String text) {
                    return VirtualScreenManager.this.node(gen, seq, id, action, text, scope);
                  }

                  public JSONObject editor(
                      String gen, String mode, String id, String text, int start, int end) {
                    try {
                      return VirtualScreenManager.this.editor(
                          gen,
                          mode,
                          new JSONObject()
                              .put("editorId", id)
                              .put("text", text)
                              .put("start", start)
                              .put("end", end),
                          scope);
                    } catch (org.json.JSONException invalid) {
                      return failure("INVALID_EDITOR_ARGUMENTS");
                    }
                  }

                  public JSONObject touch(
                      String gen, long seq, String stroke, int action, float x, float y) {
                    return VirtualScreenManager.this.touch(gen, seq, stroke, action, x, y, scope);
                  }

                  public JSONObject preview() {
                    JSONObject result = VirtualScreenManager.this.preview(scope);
                    return !result.optBoolean("ok") || frameCurrent(result)
                        ? result
                        : failure("VSCREEN_REVOKED");
                  }

                  public JSONObject see() {
                    return VirtualScreenManager.this.agentSee(scope);
                  }

                  public JSONObject anchor(
                      String gen, long frame, String observation, int x, int y) {
                    return observed("anchor", gen, frame, observation, x, y, scope);
                  }

                  public JSONObject anchoredClick(String gen, long frame, String anchor) {
                    return observed("anchored-click", gen, frame, anchor, -1, -1, scope);
                  }

                  public JSONObject action(String mode, String gen, long seq, String query) {
                    return VirtualScreenManager.this.action(mode, gen, seq, query, scope);
                  }

                  public JSONObject type(String gen, long seq, String text) {
                    return VirtualScreenManager.this.type(gen, seq, text, scope);
                  }

                  public JSONObject close() {
                    return closeExpected(request.parameters.getOrDefault("coreInstance", ""));
                  }
                }))
        .toString();
  }

  /**
   * 创建结果留痕：失败写 core 的稳定码 + 根因摘要——这是唯一能定位隐藏 API 缺口的线索
   * （issue #113），否则用户只拿到一个异常类名、诊断页里什么都没有；同样的失败只写一次，
   * 连续重试不再放大诊断。成功则清掉去重键，让下一次复发还能记上。
   */
  private void recordCreateOutcome(JSONObject value) {
    if (value == null) return;
    if (value.optBoolean("ok")) {
      lastCreateFailure = "";
      return;
    }
    Context app = context;
    if (app == null) return;
    try {
      String code = safeStartOutcome(value).optString("error", "UNCLASSIFIED_FAILURE");
      JSONObject graph = value.optJSONObject("failure");
      String origin = value.optString("failureOrigin");
      boolean nativeGraph =
          graph != null
              && java.util.Set.of("ANDROID_DISPLAY_CREATE", "NATIVE_OPERATION").contains(origin);
      String signature = code + ":" + (nativeGraph ? graph.toString().hashCode() : "omitted");
      if (signature.equals(lastCreateFailure)) return;
      lastCreateFailure = signature;
      java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
      fields.put("error", code);
      fields.put("channel", channel);
      fields.putAll(failureCaptureMetadata(nativeGraph ? graph : null));
      if (nativeGraph) {
        fields.put("failureOrigin", origin);
        fields.put(
            "failure",
            com.deepseekharness.app.backup.BackupJson.read(
                graph.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), 224 * 1024));
      }
      com.deepseekharness.app.core.DiagnosticLog.record(
          app, "VSCREEN_CREATE", "epoch=" + epoch() + " " + new JSONObject(fields));
    } catch (Exception unavailable) {
      recordStartException("VSCREEN_CREATE_DIAGNOSTIC_FAILED", unavailable);
    }
  }

  /** The nested producer graph is not DiagnosticLog's top-level Throwable. */
  static java.util.Map<String, Object> failureCaptureMetadata(JSONObject graph) {
    int omissions = failureGraphOmissions(graph);
    java.util.Map<String, Object> fields = new java.util.LinkedHashMap<>();
    fields.put("messageOmitted", (omissions & 2) != 0);
    if ((omissions & 1) != 0) fields.put("capture", "PARTIAL");
    return fields;
  }

  private static int failureGraphOmissions(JSONObject graph) {
    if (graph == null) return 3;
    boolean messageOmitted =
        !graph.optString("messageOmissionReason").isEmpty()
            || graph.optLong("omittedMessageChars") > 0;
    int omissions = messageOmitted ? 3 : 0;
    if (graph.optBoolean("truncated")
        || graph.optBoolean("cycle")
        || graph.optLong("omittedFrames") > 0
        || graph.optLong("omittedSuppressed") > 0) omissions |= 1;
    JSONObject cause = graph.optJSONObject("cause");
    if (cause != null) omissions |= failureGraphOmissions(cause);
    org.json.JSONArray suppressed = graph.optJSONArray("suppressed");
    if (suppressed != null)
      for (int i = 0; i < suppressed.length(); i++)
        omissions |= failureGraphOmissions(suppressed.optJSONObject(i));
    return omissions;
  }

  private JSONObject requestAt(int port, String token, String route, String query) {
    VirtualScreenHandshake endpoint = core;
    ClosedSession cleanup = pendingCleanup;
    if (endpoint == null || endpoint.port != port || !endpoint.token.equals(token))
      endpoint = cleanup == null ? null : cleanup.handle;
    if (endpoint == null || endpoint.port != port || !endpoint.token.equals(token))
      return failure("VSCREEN_CORE_IDENTITY_REQUIRED");
    return transport.request(endpoint, route, query);
  }

  public static Bitmap previewBitmap(JSONObject value) {
    try {
      byte[] b =
          android.util.Base64.decode(value.getString("previewB64"), android.util.Base64.DEFAULT);
      return BitmapFactory.decodeByteArray(b, 0, b.length);
    } catch (Exception e) {
      return null;
    }
  }

  private static JSONObject responseFailure(String route, String code) {
    JSONObject value =
        failure(VirtualScreenRoutes.mutatesCore(route) ? "INPUT_RESULT_UNKNOWN" : code);
    try {
      if (VirtualScreenRoutes.mutatesCore(route)) value.put("executionUnknown", true);
    } catch (Exception ignored) {
    }
    return value;
  }

  public static String encode(String v) {
    try {
      return URLEncoder.encode(v, "UTF-8");
    } catch (Exception e) {
      return "";
    }
  }

  private static String value(String query, String key, String fallback) {
    return com.deepseekharness.app.util.Query.param(query, key, fallback);
  }

  private static long longValue(String q, String k, long d) {
    try {
      return Long.parseLong(value(q, k, String.valueOf(d)));
    } catch (Exception e) {
      return d;
    }
  }

  private static float floatValue(String q, String k, float d) {
    try {
      return Float.parseFloat(value(q, k, String.valueOf(d)));
    } catch (Exception e) {
      return d;
    }
  }

  private String randomToken() {
    byte[] b = new byte[24];
    RANDOM.nextBytes(b);
    StringBuilder s = new StringBuilder();
    for (byte v : b) s.append(String.format(java.util.Locale.ROOT, "%02x", v & 255));
    return s.toString();
  }

  private static JSONObject success() {
    JSONObject j = new JSONObject();
    try {
      j.put("ok", true);
    } catch (Exception ignored) {
    }
    return j;
  }

  /** 失败一律给稳定码 + 根因摘要，别把异常类名当错误码外泄（issue #113）。 */
  private static JSONObject failure(Throwable error) {
    return failure(com.deepseekharness.app.util.VirtualScreenFailures.code(error), error);
  }

  private static JSONObject identityFailure(String code) {
    JSONObject value = failure(code);
    try {
      value.put("state", "NOT_SENT").put("code", code);
    } catch (Exception ignored) {
    }
    return value;
  }

  private static JSONObject failure(String code, Throwable error) {
    JSONObject value = failure(code);
    try {
      String cause = com.deepseekharness.app.util.VirtualScreenFailures.cause(error);
      if (!cause.isEmpty()) value.put("cause", cause);
    } catch (Exception ignored) {
    }
    return value;
  }

  private static JSONObject failure(String code) {
    if (!"VSCREEN_NOT_RUNNING".equals(code)) {
      try {
        android.util.Log.w(
            "DSHA-VSCREEN",
            com.deepseekharness.app.util.VirtualScreenBootstrap.diagnosticCode(code));
      } catch (Throwable unavailableLog) {
        /* 宿主框架桩不能写 Android 日志。 */
      }
    }
    JSONObject j = new JSONObject();
    try {
      j.put("ok", false).put("error", code);
      if (code.equals("INPUT_RESULT_UNKNOWN") || code.equals("VIRTUAL_INPUT_RESULT_UNKNOWN"))
        j.put("executionUnknown", true);
    } catch (Exception ignored) {
    }
    return j;
  }
}
