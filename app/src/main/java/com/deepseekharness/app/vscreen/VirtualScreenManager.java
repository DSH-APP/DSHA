package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.util.OneShotLaunchAuthority;
import com.deepseekharness.app.util.ShellQuote;
import com.deepseekharness.app.util.VirtualScreenRequestFence;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/** State snapshots use LOCK; remote/platform operations never run while holding it. */
public final class VirtualScreenManager {
  private final Object LOCK = new Object();
  // Ordered mutation channel, separate from the state lock: stop/revoke never wait for it.
  private final ReentrantLock ACTIONS = new ReentrantLock(true);
  private final SecureRandom RANDOM = new SecureRandom();
  private final ScheduledExecutorService WORKER =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "vscreen-lifecycle");
            t.setDaemon(true);
            return t;
          });
  private final Context context;
  private volatile String token = "", channel = "", generation = "", lastError = "";
  private volatile int port;
  private final AtomicLong LIFECYCLE_EPOCH = new AtomicLong();
  private volatile long activeEpoch = -1;
  private long stateRevision, frameSequence = -1;
  private final LongSupplier clock;
  private volatile boolean starting;
  private final ThreadLocal<String> BRIDGE_TARGET = new ThreadLocal<>();
  private final ThreadLocal<BooleanSupplier> BRIDGE_AUTHORITY = new ThreadLocal<>();
  private ScheduledFuture<?> heartbeat;
  private static final long ADB_LAUNCH_TTL_MS = 30_000;
  private final OneShotLaunchAuthority ADB_LAUNCH = new OneShotLaunchAuthority();
  private static final Pattern STARTED =
      Pattern.compile("(?m)^DSHA_VSCREEN_STARTED ([a-f0-9]{48})$");

  private final com.deepseekharness.app.util.ApplicationOwner<VirtualScreenForeground>
      foregroundOwner = new com.deepseekharness.app.util.ApplicationOwner<>();
  private final com.deepseekharness.app.util.ApplicationOwner<VirtualScreenOverlayController>
      overlayOwner = new com.deepseekharness.app.util.ApplicationOwner<>();
  private final com.deepseekharness.app.util.ApplicationOwner<VirtualScreenPreviews> previewsOwner =
      new com.deepseekharness.app.util.ApplicationOwner<>();
  private final VirtualScreenAccessibility accessibility = new VirtualScreenAccessibility();
  private final Visuals visuals;

  interface Visuals {
    void present();

    void stopped(long fence);
  }

  public VirtualScreenManager(Context application) {
    this(DshaApp.from(application), android.os.SystemClock::elapsedRealtime, null);
  }

  /** Isolated host fixtures inject clock/UI effects; production is constructed by DshaApp only. */
  VirtualScreenManager(Context application, LongSupplier clock, Visuals visuals) {
    this.context = application;
    this.clock = java.util.Objects.requireNonNull(clock);
    this.visuals =
        visuals == null
            ? new Visuals() {
              public void present() {
                foreground().present();
              }

              public void stopped(long fence) {
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

  public long epoch() {
    return LIFECYCLE_EPOCH.get();
  }

  private final class Snapshot {
    final VirtualScreenManager owner = VirtualScreenManager.this;
    final VirtualScreenRequestFence identity;
    final String target;
    final BooleanSupplier authority;
    final String channel;
    final Context app;

    Snapshot() {
      identity = new VirtualScreenRequestFence(activeEpoch, port, token, generation, stateRevision);
      target = BRIDGE_TARGET.get();
      authority = BRIDGE_AUTHORITY.get();
      channel = VirtualScreenManager.this.channel;
      app = context;
    }
  }

  private Snapshot snapshot(String requiredGeneration) {
    synchronized (LOCK) {
      if (starting || token.isEmpty() || activeEpoch != LIFECYCLE_EPOCH.get()) return null;
      if (requiredGeneration != null
          && (requiredGeneration.isEmpty() || !generation.equals(requiredGeneration))) return null;
      return new Snapshot();
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
    if (snapshot == null) return missingSnapshot(null);
    if (!current(snapshot)) return failure("VSCREEN_REVOKED");
    if (snapshot.target != null
        && !snapshot.target.isEmpty()
        && !route.equals("/vscreen/launch")
        && !route.equals("/vscreen/status")
        && !route.equals("/vscreen/create"))
      query += (query.isEmpty() ? "" : "&") + "expectedPackage=" + encode(snapshot.target);
    JSONObject value = requestAt(snapshot.identity.port, snapshot.identity.token, route, query);
    // Grant revision and target checks may use platform calls; keep them outside LOCK too.
    if (!authorized(snapshot.authority)) return failure("SCREEN_TARGET_CHANGED");
    synchronized (LOCK) {
      if (!currentLocked(snapshot)) return failure("VSCREEN_REVOKED");
      if (!value.optBoolean("ok")) {
        lastError = value.optString("error");
        return value;
      }
      String returned = value.optString("generation", generation);
      if (!generation.equals(returned)) {
        if (!changeGeneration) return failure("STALE_GENERATION");
        generation = returned;
        stateRevision++;
        frameSequence = -1;
      }
      long sequence = value.optLong("frameSeq", -1);
      if (sequence >= 0
          && sequence < frameSequence
          && (route.equals("/vscreen/status") || route.equals("/vscreen/preview")))
        return failure("STALE_FRAME");
      // A real input may already have executed before a newer preview arrives. Keep its
      // result, but never regress observation metadata or offer it as a current frame.
      if (sequence >= 0) frameSequence = Math.max(frameSequence, sequence);
      tagLocked(value);
      return value;
    }
  }

  private JSONObject missingSnapshot(String requiredGeneration) {
    synchronized (LOCK) {
      if (requiredGeneration != null
          && (requiredGeneration.isEmpty() || !generation.equals(requiredGeneration)))
        return failure("STALE_GENERATION");
      return failure(
          token.isEmpty()
              ? "VSCREEN_NOT_RUNNING"
              : starting ? "VSCREEN_START_IN_PROGRESS" : "VSCREEN_REVOKED");
    }
  }

  private JSONObject performRequired(String gen, String route, String query) {
    Snapshot session = snapshot(gen);
    return session == null ? missingSnapshot(gen) : perform(session, route, query, false);
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

  private JSONObject request(String route, String query) {
    return perform(snapshot(null), route, query, false);
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
    return ordered(() -> startOrdered(orientation));
  }

  private JSONObject startOrdered(String orientation) {
    if (BuildConfig.LOW_ANDROID) return failure("VSCREEN_UNSUPPORTED_LOW");
    if (!supported(context)) return failure("VSCREEN_API_30_REQUIRED");
    if (!"portrait".equals(orientation) && !"landscape".equals(orientation))
      return failure("INVALID_ORIENTATION");
    BooleanSupplier authority = BRIDGE_AUTHORITY.get();
    if (!authorized(authority)) return failure("SCREEN_TARGET_CHANGED");
    Context app = context;
    if (app == null) return failure("VSCREEN_APPLICATION_SCOPE_UNAVAILABLE");
    int w = "landscape".equals(orientation) ? 1792 : 1008,
        h = "landscape".equals(orientation) ? 1008 : 1792;
    Snapshot existing = snapshot(null);
    if (existing != null) {
      JSONObject result = perform(existing, "/vscreen/create", "width=" + w + "&height=" + h, true);
      if (result.optBoolean("ok")) visuals.present();
      return result;
    }
    final long launchEpoch;
    synchronized (LOCK) {
      if (starting) return failure("VSCREEN_START_IN_PROGRESS");
      if (!token.isEmpty()) return failure("VSCREEN_REVOKED");
      starting = true;
      launchEpoch = LIFECYCLE_EPOCH.incrementAndGet();
      stateRevision++;
    }
    // Device discovery can involve su/Binder/ADB: do not hold the state lock.
    VirtualScreenChannel selected;
    try {
      selected = VirtualScreenChannels.choose(app);
    } catch (RuntimeException discovery) {
      failStart(launchEpoch);
      return failure("DEVICE_CHANNEL_UNAVAILABLE");
    }
    if (selected == null) {
      failStart(launchEpoch);
      return failure("DEVICE_CHANNEL_UNAVAILABLE");
    }
    int selectedPort = 8800 + RANDOM.nextInt(100);
    String command =
        "/system/bin/app_process "
            + ShellQuote.arg("-Djava.class.path=" + app.getApplicationInfo().sourceDir)
            + " /system/bin com.deepseekharness.app.vscreen.VirtualScreenCore --launch --port "
            + selectedPort
            + " --package "
            + ShellQuote.arg(app.getPackageName());
    synchronized (LOCK) {
      if (LIFECYCLE_EPOCH.get() != launchEpoch || !starting)
        return failure("VSCREEN_START_CANCELLED");
      port = selectedPort;
      channel = selected.id();
    }
    String coreToken = "";
    try {
      if (!authorized(authority)) {
        failStart(launchEpoch);
        return failure("SCREEN_TARGET_CHANGED");
      }
      if ("adb".equals(selected.id())) reserveAdbLaunch(command, launchEpoch);
      String output = selected.start(app, command);
      Matcher match = output == null ? null : STARTED.matcher(output);
      if (output == null || !output.contains("[EXIT=0]") || match == null || !match.find()) {
        failStart(launchEpoch);
        return failure("VSCREEN_START_RESULT_UNKNOWN");
      }
      coreToken = match.group(1);
      boolean admitted;
      synchronized (LOCK) {
        admitted = LIFECYCLE_EPOCH.get() == launchEpoch && starting;
        if (admitted) {
          token = coreToken;
          activeEpoch = launchEpoch;
        }
      }
      if (!admitted || !authorized(authority)) {
        requestAt(selectedPort, coreToken, "/vscreen/close", "");
        failStart(launchEpoch);
        return failure("VSCREEN_START_CANCELLED");
      }
      JSONObject created =
          requestAt(selectedPort, coreToken, "/vscreen/create", "width=" + w + "&height=" + h);
      boolean live = authorized(authority), committed = false;
      synchronized (LOCK) {
        if (live
            && LIFECYCLE_EPOCH.get() == launchEpoch
            && coreToken.equals(token)
            && created.optBoolean("ok")) {
          starting = false;
          generation = created.optString("generation");
          frameSequence = created.optLong("frameSeq", -1);
          stateRevision++;
          tagLocked(created);
          committed = true;
        }
      }
      if (!committed) {
        requestAt(selectedPort, coreToken, "/vscreen/close", "");
        failStart(launchEpoch);
        return created.optBoolean("ok") ? failure("VSCREEN_START_CANCELLED") : created;
      }
      ScheduledFuture<?> scheduled =
          WORKER.scheduleWithFixedDelay(() -> heartbeat(launchEpoch), 10, 10, TimeUnit.SECONDS);
      synchronized (LOCK) {
        if (LIFECYCLE_EPOCH.get() == launchEpoch && activeEpoch == launchEpoch)
          heartbeat = scheduled;
        else scheduled.cancel(false);
      }
      visuals.present();
      return created;
    } catch (Throwable error) {
      if (!coreToken.isEmpty()) requestAt(selectedPort, coreToken, "/vscreen/close", "");
      failStart(launchEpoch);
      return failure("VSCREEN_START_" + error.getClass().getSimpleName());
    } finally {
      cancelAdbLaunch(launchEpoch);
    }
  }

  private void heartbeat(long launchEpoch) {
    Snapshot session = snapshot(null);
    if (session == null || session.identity.epoch != launchEpoch) return;
    VirtualScreenChannel selected = VirtualScreenChannels.find(session.channel);
    boolean available = selected != null && selected.available(session.app);
    JSONObject ping =
        available
            ? perform(session, "/vscreen/ping", "", false)
            : failure("DEVICE_CHANNEL_UNAVAILABLE");
    if (!ping.optBoolean("ok")) stopIfCurrent(session);
  }

  public JSONObject status() {
    return request("/vscreen/status", "");
  }

  public JSONObject preview() {
    return request("/vscreen/preview", "");
  }

  public JSONObject previewSince(String gen, long seq) {
    return request("/vscreen/preview", "since=" + seq + "&generation=" + encode(gen));
  }

  public JSONObject touch(String gen, long seq, String stroke, int action, float x, float y) {
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
                    + y));
  }

  public JSONObject editor(String gen, String operation, JSONObject args) {
    return ordered(
        () -> {
          Snapshot session = snapshot(gen);
          if (session == null) return missingSnapshot(gen);
          JSONObject info = perform(session, "/vscreen/status", "", false);
          if (!info.optBoolean("ok")) return info;
          if (!current(session)) return failure("VSCREEN_REVOKED");
          JSONObject result =
              com.deepseekharness.app.DshaAccessibilityService.virtualControl(
                  accessibility, info.optInt("displayId", -1), operation, args);
          return current(session) ? result : failure("SCREEN_TARGET_CHANGED");
        });
  }

  public JSONObject tree() {
    return ordered(
        () -> {
          Snapshot session = snapshot(null);
          JSONObject frame = perform(session, "/vscreen/preview", "", false);
          if (!frame.optBoolean("ok")) return frame;
          if (!current(session)) return failure("VSCREEN_REVOKED");
          JSONObject result =
              com.deepseekharness.app.DshaAccessibilityService.virtualControl(
                  accessibility, frame.optInt("displayId", -1), "tree", new JSONObject());
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
    return ordered(
        () -> {
          Snapshot session = snapshot(gen);
          if (session == null) return missingSnapshot(gen);
          JSONObject check =
              perform(
                  session,
                  "/vscreen/check",
                  "generation=" + encode(gen) + "&frameSeq=" + seq,
                  false);
          if (!check.optBoolean("ok")) return check;
          if (!current(session)) return failure("VSCREEN_REVOKED");
          try {
            JSONObject result =
                com.deepseekharness.app.DshaAccessibilityService.virtualControl(
                    accessibility,
                    check.optInt("displayId", -1),
                    "node",
                    new JSONObject().put("nodeId", node).put("action", action).put("text", text));
            return current(session) ? result : failure("SCREEN_TARGET_CHANGED");
          } catch (Exception invalid) {
            return failure("INVALID_NODE_ACTION");
          }
        });
  }

  public JSONObject launch(String pkg) {
    if (pkg == null || !pkg.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))
      return failure("INVALID_PACKAGE");
    return ordered(() -> request("/vscreen/launch", "package=" + encode(pkg)));
  }

  public JSONObject action(String action, String gen, long seq, String params) {
    return ordered(
        () ->
            performRequired(
                gen,
                "/vscreen/" + action,
                "generation="
                    + encode(gen)
                    + "&frameSeq="
                    + seq
                    + (params.isEmpty() ? "" : "&" + params)));
  }

  public JSONObject type(String gen, long seq, String text) {
    if (text == null || text.isEmpty() || text.length() > 2000) return failure("INVALID_TEXT");
    return ordered(
        () -> {
          Snapshot session = snapshot(gen);
          if (session == null) return missingSnapshot(gen);
          String query = "generation=" + encode(gen) + "&frameSeq=" + seq;
          if (text.matches("[\\x20-\\x7e]+"))
            return perform(session, "/vscreen/type", query + "&text=" + encode(text), false);
          if (!com.deepseekharness.app.DshaAccessibilityService.isConnected())
            return failure("ACCESSIBILITY_REQUIRED_FOR_UNICODE");
          JSONObject check = perform(session, "/vscreen/check", query, false);
          if (!check.optBoolean("ok")) return check;
          if (!current(session)) return failure("VSCREEN_REVOKED");
          String result =
              com.deepseekharness.app.DshaAccessibilityService.virtualInput(
                  check.optInt("displayId", -1), text);
          return current(session)
              ? result.equals("OK") ? check : failure(result)
              : failure("SCREEN_TARGET_CHANGED");
        });
  }

  private static final class ClosedSession {
    final int port;
    final String token;
    final long fence;

    ClosedSession(int port, String token, long fence) {
      this.port = port;
      this.token = token;
      this.fence = fence;
    }
  }

  private ClosedSession detachLocked() {
    long stopped = LIFECYCLE_EPOCH.getAndIncrement();
    starting = false;
    cancelAdbLaunch(stopped);
    if (heartbeat != null) {
      heartbeat.cancel(false);
      heartbeat = null;
    }
    ClosedSession closed = new ClosedSession(port, token, stopped + 1);
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
    JSONObject result =
        closed.token.isEmpty()
            ? success()
            : requestAt(closed.port, closed.token, "/vscreen/close", "");
    // Old close completion cannot tear down a newer generation's UI or node resources.
    ACTIONS.lock();
    try {
      if (epoch() == closed.fence) accessibility.clear();
    } finally {
      ACTIONS.unlock();
    }
    visuals.stopped(closed.fence);
    return result;
  }

  private JSONObject stopResult() {
    ClosedSession closed;
    synchronized (LOCK) {
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

  public void revoke() {
    long previous = LIFECYCLE_EPOCH.getAndIncrement();
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
        token = "";
        channel = "";
        generation = "";
        port = 0;
        activeEpoch = -1;
        stateRevision++;
        frameSequence = -1;
      }
    }
  }

  /** One-shot ADB start authority exists only while this native manager launch is pending. */
  private void reserveAdbLaunch(String command, long epoch) {
    if (!ADB_LAUNCH.issue(randomToken(), command, epoch, clock.getAsLong(), ADB_LAUNCH_TTL_MS))
      throw new IllegalStateException("VSCREEN_ADB_LAUNCH_BUSY");
  }

  public String adbLaunchTicketFor(String command) {
    return ADB_LAUNCH.ticketFor(command, LIFECYCLE_EPOCH.get(), clock.getAsLong());
  }

  public boolean authorizeAdbLaunchPlan(String ticket, String command) {
    return ADB_LAUNCH.authorize(ticket, command, LIFECYCLE_EPOCH.get(), clock.getAsLong());
  }

  public boolean commitAdbLaunch(String ticket) {
    return ADB_LAUNCH.commit(ticket, LIFECYCLE_EPOCH.get(), clock.getAsLong());
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
    return request
        .execute(
            new com.deepseekharness.app.util.VscreenBridgeRequest.Actions<JSONObject>() {
              public JSONObject create(String orientation) {
                return VirtualScreenManager.this.start(orientation, true);
              }

              public JSONObject status() {
                return VirtualScreenManager.this.status();
              }

              public JSONObject launch(String pkg) {
                return VirtualScreenManager.this.launch(pkg);
              }

              public JSONObject tree() {
                return VirtualScreenManager.this.tree();
              }

              public JSONObject node(String gen, long seq, String id, String action, String text) {
                return VirtualScreenManager.this.node(gen, seq, id, action, text);
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
                          .put("end", end));
                } catch (org.json.JSONException invalid) {
                  return failure("INVALID_EDITOR_ARGUMENTS");
                }
              }

              public JSONObject touch(
                  String gen, long seq, String stroke, int action, float x, float y) {
                return VirtualScreenManager.this.touch(gen, seq, stroke, action, x, y);
              }

              public JSONObject preview() {
                JSONObject result = VirtualScreenManager.this.preview();
                if (result.optBoolean("ok"))
                  try {
                    result.put(
                        "tree",
                        com.deepseekharness.app.DshaAccessibilityService.virtualDump(
                            result.optInt("displayId", -1)));
                  } catch (Exception ignored) {
                  }
                return !result.optBoolean("ok") || frameCurrent(result)
                    ? result
                    : failure("VSCREEN_REVOKED");
              }

              public JSONObject action(String mode, String gen, long seq, String query) {
                return VirtualScreenManager.this.action(mode, gen, seq, query);
              }

              public JSONObject type(String gen, long seq, String text) {
                return VirtualScreenManager.this.type(gen, seq, text);
              }

              public JSONObject close() {
                return stopResult();
              }
            })
        .toString();
  }

  private static JSONObject requestAt(
      int requestPort, String requestToken, String route, String query) {
    if (requestToken == null || requestToken.isEmpty() || requestPort < 1)
      return failure("VSCREEN_NOT_RUNNING");
    HttpURLConnection c = null;
    try {
      c =
          (HttpURLConnection)
              new java.net.URL(
                      "http://127.0.0.1:"
                          + requestPort
                          + route
                          + (query.isEmpty() ? "" : "?" + query))
                  .openConnection();
      c.setRequestProperty("Authorization", "Bearer " + requestToken);
      c.setConnectTimeout(2000);
      c.setReadTimeout(12000);
      int code = c.getResponseCode();
      java.io.InputStream stream = code >= 400 ? c.getErrorStream() : c.getInputStream();
      if (stream == null) return failure("VSCREEN_HTTP_" + code);
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      try (java.io.InputStream in = stream) {
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) >= 0) {
          if (out.size() + n > 4 * 1024 * 1024) return failure("VSCREEN_RESPONSE_LIMIT");
          out.write(b, 0, n);
        }
      }
      JSONObject result = new JSONObject(out.toString("UTF-8"));
      return result;
    } catch (Exception e) {
      return failure("VSCREEN_CONNECTION_" + e.getClass().getSimpleName());
    } finally {
      if (c != null) c.disconnect();
    }
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

  private static JSONObject failure(String code) {
    JSONObject j = new JSONObject();
    try {
      j.put("ok", false).put("error", code);
    } catch (Exception ignored) {
    }
    return j;
  }
}
