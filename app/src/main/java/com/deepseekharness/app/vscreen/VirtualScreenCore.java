package com.deepseekharness.app.vscreen;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import com.deepseekharness.app.util.VirtualScreenPolicy;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import com.deepseekharness.app.util.VirtualScreenFailures;
import com.deepseekharness.app.util.VirtualScreenInputResult;
import com.deepseekharness.app.util.VirtualScreenInputPolicy;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 独立 shell/root 进程，只接受认证后的固定虚拟屏操作。 */
@androidx.annotation.RequiresApi(30)
public final class VirtualScreenCore {
  private static final String CLASS = VirtualScreenCore.class.getName();
  private static java.util.function.LongSupplier clock = SystemClock::elapsedRealtime;
  private static volatile long lastRequest;
  private static Runnable processExit = () -> System.exit(0);
  private static DisplayFactory hostDisplayFactory;
  private static java.util.function.Supplier<String> hostPackageObserver;
  private static volatile boolean running = true;
  private static String token;
  private static String selfPackage;
  private static Context context;
  private static VirtualScreenDisplaySession session;
  private static VirtualScreenCoreIdentity identity;
  private static ServerSocket server;
  private static String startupStage = "CONTEXT";
  private static String missingDisplayFlags = "";
  private static final ThreadPoolExecutor CLIENTS =
      new ThreadPoolExecutor(
          2,
          4,
          10,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(8),
          r -> {
            Thread t = new Thread(r, "vscreen-http");
            t.setDaemon(true);
            return t;
          });

  interface DisplayFactory {
    VirtualScreenDisplaySession create(int width, int height) throws Exception;
  }

  interface HostListenerFactory {
    ServerSocket bind() throws IOException;
  }

  /** Host fixtures replace platform facts only. Socket parsing, auth and route are production code. */
  static final class HostControl implements AutoCloseable {
    private final ServerSocket bound;
    private final VirtualScreenHandshake endpoint;

    private HostControl(ServerSocket bound, VirtualScreenHandshake endpoint) {
      this.bound = bound;
      this.endpoint = endpoint;
    }

    VirtualScreenHandshake handshake() {
      return endpoint;
    }

    void tick() {
      synchronized (VirtualScreenCore.class) {
        if (server == bound && elapsed() - lastRequest > 60_000) shutdown(bound);
      }
    }

    public void close() {
      shutdown(bound);
    }
  }

  static HostControl bindControlForHost(
      VirtualScreenCoreIdentity birth,
      DisplayFactory displays,
      java.util.function.Supplier<String> packages,
      java.util.function.LongSupplier monotonicClock,
      Runnable exit)
      throws IOException {
    return bindControlForHost(
        birth,
        displays,
        packages,
        monotonicClock,
        exit,
        () -> new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")));
  }

  static synchronized HostControl bindControlForHost(
      VirtualScreenCoreIdentity birth,
      DisplayFactory displays,
      java.util.function.Supplier<String> packages,
      java.util.function.LongSupplier monotonicClock,
      Runnable exit,
      HostListenerFactory listeners)
      throws IOException {
    if (server != null && !server.isClosed()) throw new IOException("CORE_ALREADY_BOUND");
    identity = java.util.Objects.requireNonNull(birth);
    hostDisplayFactory = java.util.Objects.requireNonNull(displays);
    hostPackageObserver = java.util.Objects.requireNonNull(packages);
    clock = java.util.Objects.requireNonNull(monotonicClock);
    processExit = java.util.Objects.requireNonNull(exit);
    running = true;
    lastRequest = elapsed();
    token = randomToken();
    server = listeners.bind();
    VirtualScreenHandshake endpoint =
        new VirtualScreenHandshake(server.getLocalPort(), token, identity);
    startAcceptLoop(server, endpoint);
    return new HostControl(server, endpoint);
  }

  static long elapsed() {
    return clock.getAsLong();
  }

  private static void startAcceptLoop(ServerSocket bound, VirtualScreenHandshake endpoint) {
    Thread accept =
        new Thread(
            () -> {
              while (running && server == bound) {
                try {
                  Socket socket = bound.accept();
                  try {
                    CLIENTS.execute(() -> handle(bound, endpoint, socket));
                  } catch (RejectedExecutionException full) {
                    socket.close();
                  }
                } catch (IOException closed) {
                  shutdown(bound);
                }
              }
            },
            "vscreen-listener");
    accept.setDaemon(true);
    accept.start();
  }

  public static void main(String[] args) {
    try {
      if (android.os.Build.VERSION.SDK_INT < 30)
        throw new UnsupportedOperationException("API_30_REQUIRED");
      if (Arrays.asList(args).contains("--token"))
        throw new IllegalArgumentException("TOKEN_MUST_NOT_BE_IN_ARGV");
      Context system =
          com.deepseekharness.app.runtime.PrivilegedPackageContext.virtualScreenContext();
      startupStage = "IDENTITY";
      selfPackage = arg(args, "--package", "");
      if (!com.deepseekharness.app.util.SelfPackageIdentity.validName(selfPackage))
        throw new SecurityException("SELF_PACKAGE_UNVERIFIED");
      int port = Integer.parseInt(arg(args, "--port", "0"));
      if (port != 0) throw new IllegalArgumentException("DYNAMIC_PORT_REQUIRED");
      String classPath = System.getProperty("java.class.path", "");
      if (!installedDshaApkPath(system).equals(classPath))
        throw new SecurityException("INVALID_CLASSPATH");
      if (Arrays.asList(args).contains("--probe-exit")) {
        printExitProof(VirtualScreenCoreIdentity.parse(arg(args, "--identity", "")));
        System.exit(0);
        return;
      }
      if (Arrays.asList(args).contains("--launch")) {
        startupStage = "LAUNCH";
        launch(port);
        // ROM 可能留下非守护线程；一次性启动父进程必须明确结束，常驻服务由子进程承担。
        System.exit(0);
        return;
      }
      if (!Arrays.asList(args).contains("--server"))
        throw new IllegalArgumentException("SERVER_MODE_REQUIRED");
      if (android.os.Build.VERSION.SDK_INT < 30)
        throw new UnsupportedOperationException("API_30_REQUIRED");
      token = randomToken();
      // The shared helper prepares one main Looper before the first systemMain attach.
      startupStage = "SHELL_CONTEXT";
      context = system.createPackageContext("com.android.shell", 0);
      startupStage = "BIND_LISTENER";
      server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
      identity = selfIdentity();
      lastRequest = elapsed();
      Thread watchdog =
          new Thread(
              () -> {
                while (running) {
                  try {
                    Thread.sleep(1000);
                  } catch (InterruptedException e) {
                    return;
                  }
                  VirtualScreenDisplaySession current = session;
                  if (current != null) current.input.expire();
                  if (elapsed() - lastRequest > 60_000) shutdown();
                }
              },
              "vscreen-heartbeat");
      watchdog.setDaemon(true);
      watchdog.start();
      startAcceptLoop(server, new VirtualScreenHandshake(server.getLocalPort(), token, identity));
      // This one-time bootstrap secret travels only through the managed channel result,
      // never in app_process argv or the device command line.
      System.out.println(
          "DSHA_VSCREEN_BOOTSTRAP "
              + new VirtualScreenHandshake(server.getLocalPort(), token, identity).payload());
      System.out.flush();
      Looper.loop();
    } catch (Throwable error) {
      System.err.println(
          "DSHA_VSCREEN_ERROR="
              + com.deepseekharness.app.util.VirtualScreenBootstrap.failureCode(
                  startupStage, android.os.Build.VERSION.SDK_INT, error));
      System.err.flush();
      System.exit(1);
    }
  }

  private static void launch(int port) throws Exception {
    String path = System.getProperty("java.class.path", "");
    if (!path.equals(installedDshaApkPath()))
      throw new IllegalArgumentException("INVALID_CLASSPATH");
    Process child =
        new ProcessBuilder(
                // ADB shell v1 关闭会清理所属进程组，核心需拥有独立会话。
                // 不使用 setsid -d：保持直接子进程及 bootstrap 管道的生命周期可核验。
                "/system/bin/setsid",
                "/system/bin/app_process",
                "-Djava.class.path=" + path,
                "/system/bin",
                CLASS,
                "--server",
                "--port",
                String.valueOf(port),
                "--package",
                selfPackage)
            .redirectInput(new File("/dev/null"))
            .redirectErrorStream(true)
            .start();
    boolean started = false;
    try {
      // The server creates a secret after exec; its parent returns it through the
      // Root/Shizuku/ADB result channel, not an OS-visible command-line argument.
      long deadline = elapsed() + 8000;
      startupStage = "BOOTSTRAP";
      VirtualScreenHandshake handshake = readBootstrap(child, deadline);
      startupStage = "REACHABILITY";
      while (elapsed() < deadline) {
        if (!child.isAlive()) throw new IOException("CORE_EXITED");
        if (new VirtualScreenTransport().request(handshake, "/vscreen/ping", "").optBoolean("ok")) {
          System.out.println("DSHA_VSCREEN_STARTED " + handshake.payload());
          System.out.flush();
          started = true;
          return;
        }
        Thread.sleep(80);
      }
      throw new IOException("START_RESULT_UNKNOWN");
    } finally {
      if (!started && child.isAlive()) child.destroy();
      try {
        child.getInputStream().close();
      } catch (IOException ignored) {
      }
    }
  }

  private static VirtualScreenHandshake readBootstrap(Process child, long deadline)
      throws Exception {
    com.deepseekharness.app.util.VirtualScreenBootstrap bootstrap =
        new com.deepseekharness.app.util.VirtualScreenBootstrap();
    InputStream input = child.getInputStream();
    while (elapsed() < deadline) {
      int available = input.available();
      if (available <= 0) {
        if (!child.isAlive()) throw new IOException("CORE_EXITED");
        Thread.sleep(20);
        continue;
      }
      int value = input.read();
      if (value < 0) throw new EOFException("CORE_BOOTSTRAP_EOF");
      bootstrap.accept(value);
      if (bootstrap.handshake() != null) return bootstrap.handshake();
    }
    throw new IOException("CORE_BOOTSTRAP_TIMEOUT");
  }

  private static String installedDshaApkPath() throws Exception {
    return installedDshaApkPath(
        com.deepseekharness.app.runtime.PrivilegedPackageContext.virtualScreenContext());
  }

  private static String installedDshaApkPath(Context system) throws Exception {
    String source = system.getPackageManager().getApplicationInfo(selfPackage, 0).sourceDir;
    String canonical = new File(source).getCanonicalPath();
    if (!source.equals(canonical)
        || !com.deepseekharness.app.util.DeviceShellPolicy.canonicalApkPath(canonical))
      throw new SecurityException("INVALID_CLASSPATH");
    return canonical;
  }

  private static String randomToken() {
    byte[] bytes = new byte[24];
    new java.security.SecureRandom().nextBytes(bytes);
    StringBuilder value = new StringBuilder();
    for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255));
    return value.toString();
  }

  private static void handle(ServerSocket bound, VirtualScreenHandshake endpoint, Socket socket) {
    boolean close = false;
    try (Socket accepted = socket) {
      accepted.setSoTimeout(3000);
      VirtualScreenPeerProof.Session peer = VirtualScreenPeerProof.server(accepted, endpoint);
      InputStream input = accepted.getInputStream();
      long deadline = elapsed() + 3000;
      String[] parts = line(input, 24576, deadline).split(" ", 3);
      if (parts.length != 3 || !parts[0].equals("GET")) return;
      String authorization = "";
      int remaining = 32768;
      for (; ; ) {
        String header = line(input, Math.min(remaining, 4096), deadline);
        remaining -= header.length() + 2;
        if (header.isEmpty()) break;
        if (remaining <= 0) throw new IOException("HEAD_LIMIT");
        if (header
            .toLowerCase(Locale.ROOT)
            .startsWith(VirtualScreenPeerProof.HEADER.toLowerCase(Locale.ROOT) + ":"))
          authorization = header.substring(VirtualScreenPeerProof.HEADER.length() + 1).trim();
      }
      boolean authorized = peer.authorizes(parts[0], parts[1], authorization);
      String path = parts[1].split("\\?", 2)[0];
      JSONObject result;
      if (!authorized) result = error("UNAUTHORIZED");
      else {
        synchronized (VirtualScreenCore.class) {
          if (!running || server != bound) return;
          lastRequest = elapsed();
          result = route(path, query(parts[1]));
          close = path.equals("/vscreen/close");
        }
      }
      byte[] body = result.toString().getBytes(StandardCharsets.UTF_8);
      OutputStream out = accepted.getOutputStream();
      out.write(
          ("HTTP/1.1 "
                  + (authorized ? "200 OK" : "401 Unauthorized")
                  + "\r\nContent-Type: application/json\r\nContent-Length: "
                  + body.length
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
    } catch (Throwable ignored) {
    } finally {
      if (close) shutdown(bound);
    }
  }

  private static synchronized JSONObject route(String path, Map<String, String> q) {
    try {
      if (path.equals("/vscreen/ping")) return ok();
      if (path.equals("/vscreen/create"))
        return create(
            Integer.parseInt(q.getOrDefault("width", "1008")),
            Integer.parseInt(q.getOrDefault("height", "1792")));
      if (path.equals("/vscreen/status")) return status();
      if (path.equals("/vscreen/close")) {
        release();
        return ok();
      }
      if (session == null) return error("VSCREEN_NOT_CREATED");
      String expected = q.getOrDefault("expectedPackage", "");
      if (!expected.isEmpty()) {
        String actual = currentPackage();
        if (actual.isEmpty()) return error("VSCREEN_TARGET_UNVERIFIABLE");
        if (!expected.equals(actual)) return error("VSCREEN_TARGET_CHANGED");
      }
      if (path.equals("/vscreen/launch")) return launchPackage(q.get("package"));
      if (path.equals("/vscreen/preview"))
        return preview(Long.parseLong(q.getOrDefault("since", "-1")), q.get("generation"));
      if (path.equals("/vscreen/observe")) {
        if (!session.generation.equals(q.get("generation"))) return error("STALE_GENERATION");
        return "true".equals(q.get("retain"))
            ? session.observations.capture(session, VirtualScreenCore::status)
            : preview(-1, session.generation);
      }
      if (path.equals("/vscreen/observation-region")) {
        if (!session.generation.equals(q.get("generation"))) return error("STALE_GENERATION");
        return session.observations.region(
            session,
            q.getOrDefault("observationId", ""),
            new android.graphics.Rect(
                Integer.parseInt(q.getOrDefault("left", "-1")),
                Integer.parseInt(q.getOrDefault("top", "-1")),
                Integer.parseInt(q.getOrDefault("right", "-1")),
                Integer.parseInt(q.getOrDefault("bottom", "-1"))));
      }
      if (path.equals("/vscreen/observation-release")) {
        session.observations.remove(q.getOrDefault("observationId", ""));
        return ok();
      }
      if (path.equals("/vscreen/touch")) {
        if (!session.generation.equals(q.get("generation"))) return error("STALE_GENERATION");
        String stroke = q.getOrDefault("stroke", "");
        if (!stroke.matches("[a-f0-9-]{16,64}")) return error("INVALID_STROKE");
        int action = Integer.parseInt(q.getOrDefault("action", "-1"));
        if (action < 0 || action > 3) return error("INVALID_TOUCH");
        float x = Float.parseFloat(coordinate(q, "x", session.width)),
            y = Float.parseFloat(coordinate(q, "y", session.height));
        if (action == 0) {
          synchronized (session.lock) {
            long requested = Long.parseLong(q.getOrDefault("frameSeq", "-1"));
            String rejection =
                VirtualScreenPolicy.inputRejection(
                    requested, session.sequence, session.observed, elapsed() - session.observedAt);
            if (rejection != null)
              return staleFrame(rejection, requested, session.sequence, session.observed);
            session.observed = -1;
          }
        }
        return inputResult(session.input.touch(stroke, action, x, y));
      }
      if (!Set.of(
              "/vscreen/tap", "/vscreen/swipe", "/vscreen/type", "/vscreen/key", "/vscreen/check")
          .contains(path)) return error("UNKNOWN_ROUTE");
      long observed = Long.parseLong(q.getOrDefault("frameSeq", "-1"));
      synchronized (session.lock) {
        if (!session.generation.equals(q.get("generation"))) return error("STALE_GENERATION");
        // 拒绝码只说"过期"是没用的：没看过这一帧、被新帧顶掉、观察超时是三种不同的自救方式。
        String rejection =
            VirtualScreenPolicy.inputRejection(
                observed, session.sequence, session.observed, elapsed() - session.observedAt);
        if (rejection != null)
          return staleFrame(rejection, observed, session.sequence, session.observed);
        if (path.equals("/vscreen/check")) {
          session.observed = -1;
          return status();
        }
        if (session.input.active()) return error("TOUCH_IN_PROGRESS");
        if (path.equals("/vscreen/tap") || path.equals("/vscreen/swipe")) {
          boolean tap = path.endsWith("/tap");
          float x = Float.parseFloat(coordinate(q, tap ? "x" : "x1", session.width)),
              y = Float.parseFloat(coordinate(q, tap ? "y" : "y1", session.height));
          float endX = tap ? x : Float.parseFloat(coordinate(q, "x2", session.width)),
              endY = tap ? y : Float.parseFloat(coordinate(q, "y2", session.height));
          int ms = tap ? 0 : Integer.parseInt(q.getOrDefault("ms", "300"));
          if (ms < 0 || ms > 3000) return error("INVALID_DURATION");
          String stroke = UUID.randomUUID().toString();
          session.observed = -1;
          try {
            if (!session.input.touch(stroke, 0, x, y).accepted()) return inputFailure(session);
            int steps = Math.max(1, ms / 16);
            for (int n = 1; n < steps; n++) {
              Thread.sleep(16);
              if (!session
                  .input
                  .touch(stroke, 2, x + (endX - x) * n / steps, y + (endY - y) * n / steps)
                  .accepted()) return inputFailure(session);
            }
            return inputResult(session.input.touch(stroke, 1, endX, endY));
          } finally {
            session.input.cancel();
          }
        }
        if (path.equals("/vscreen/key")) {
          int code = Integer.parseInt(q.getOrDefault("keycode", "-1"));
          if (code != 3 && code != 4 && code != 66 && code != 67) return error("INVALID_KEY");
          session.observed = -1;
          return inputResult(session.input.key(code));
        }
        String text = q.getOrDefault("text", "");
        if (text.isEmpty() || text.length() > 2000) return error("INVALID_TEXT");
        if (!text.matches("[\\x20-\\x7e]+")) return error("ACCESSIBILITY_REQUIRED_FOR_UNICODE");
        session.observed = -1;
        return inputResult(session.input.text(text));
      }
    } catch (IllegalArgumentException error) {
      return error("INVALID_ARGUMENT");
    } catch (Throwable error) {
      return failure(error);
    }
  }

  private static JSONObject create(int width, int height) throws Exception {
    if (hostDisplayFactory != null) {
      int[] size = VirtualScreenPolicy.phoneSize(width, height);
      if (session != null && session.width == size[0] && session.height == size[1]) return status();
      release();
      VirtualScreenDisplaySession created = null;
      try {
        created = hostDisplayFactory.create(size[0], size[1]);
        if (created == null
            || created.id <= 0
            || created.width != size[0]
            || created.height != size[1])
          throw new IllegalArgumentException("DISPLAY_ADAPTER_FACTS_INVALID");
        session = created;
        created.start();
        return status();
      } catch (Throwable failure) {
        session = null;
        if (created != null)
          try {
            created.close();
          } catch (Throwable cleanup) {
            failure.addSuppressed(cleanup);
          }
        return failure(failure);
      }
    }
    int[] size = VirtualScreenPolicy.phoneSize(width, height);
    if (session != null && session.width == size[0] && session.height == size[1]) return status();
    release();
    ImageReader reader = ImageReader.newInstance(size[0], size[1], PixelFormat.RGBA_8888, 3);
    boolean trusted =
        VirtualScreenPolicy.requestTrustedDisplay(
            android.os.Process.myUid(), trustedDisplayPermission());
    VirtualDisplay display = null;
    VirtualScreenDisplaySession created = null;
    boolean requestedTrusted = false;
    Throwable displayCreateFailure = null;
    try {
      DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
      List<String> absent = new ArrayList<>();
      int flags =
          DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
              | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
              | flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", absent)
              | flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", absent)
              | flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", absent);
      if (trusted) {
        int trustedFlag = flag("VIRTUAL_DISPLAY_FLAG_TRUSTED", absent);
        requestedTrusted = trustedFlag != 0;
        flags |= trustedFlag;
      }
      missingDisplayFlags = String.join(",", absent);
      try {
        display =
            manager.createVirtualDisplay(
                "DSHA-VirtualScreen", size[0], size[1], 320, reader.getSurface(), flags);
      } catch (Throwable platformFailure) {
        displayCreateFailure = platformFailure;
        throw platformFailure;
      }
      if (display == null) throw new IOException("DISPLAY_CREATE_FAILED");
      created = new VirtualScreenDisplaySession(display, reader, size[0], size[1]);
      session = created;
      created.start();
      return status();
    } catch (Throwable error) {
      session = null;
      if (created != null) {
        created.active = false;
        try {
          synchronized (created.lock) {
            if (created.frame != null) created.frame.recycle();
            created.frame = null;
          }
        } catch (Throwable cleanup) {
          error.addSuppressed(cleanup);
        }
      }
      try {
        if (display != null) display.release();
      } catch (Throwable cleanup) {
        error.addSuppressed(cleanup);
      }
      try {
        reader.close();
      } catch (Throwable cleanup) {
        error.addSuppressed(cleanup);
      }
      String stable = VirtualScreenFailures.code(error);
      boolean denied = requestedTrusted && VirtualScreenFailures.PERMISSION_DENIED.equals(stable);
      JSONObject result = failure(error, error == displayCreateFailure);
      if (error instanceof IllegalArgumentException) return result.put("error", "INVALID_ARGUMENT");
      return denied ? result.put("error", VirtualScreenFailures.TRUSTED_DISPLAY_DENIED) : result;
    }
  }

  /**
   * 自身是否真的持有 ADD_TRUSTED_DISPLAY。{@code checkSelfPermission} 判的是**本进程 uid**
   * （{@code Process.myPid()/myUid()}），与 DisplayManagerService 的 {@code Binder.getCallingUid()}
   * 一致 —— 不要改成按包名判断，那会在 ADB/Shizuku 通道上给出错误答案。查不到就按"不持有"处理：
   * 不请求 TRUSTED 的创建仍然可以成功，方向安全。
   */
  private static VirtualScreenPolicy.TrustedDisplayPermission trustedDisplayPermission() {
    try {
      if (context == null) return VirtualScreenPolicy.TrustedDisplayPermission.UNKNOWN;
      return context.checkSelfPermission("android.permission.ADD_TRUSTED_DISPLAY")
              == android.content.pm.PackageManager.PERMISSION_GRANTED
          ? VirtualScreenPolicy.TrustedDisplayPermission.HELD
          : VirtualScreenPolicy.TrustedDisplayPermission.NOT_HELD;
    } catch (Throwable unavailable) {
      return VirtualScreenPolicy.TrustedDisplayPermission.UNKNOWN;
    }
  }

  /** 帧门拒绝：保留既有的 STALE_FRAME 码，同时给出可区分的原因与实际值/期望值。 */
  private static JSONObject staleFrame(String reason, long requested, long current, long observed) {
    JSONObject value = error("STALE_FRAME");
    try {
      value
          .put("reason", reason)
          .put("requestedFrameSeq", requested)
          .put("currentFrameSeq", current)
          .put("observedFrameSeq", observed);
    } catch (Exception ignored) {
    }
    return value;
  }

  private static JSONObject status() throws Exception {
    if (session == null) return ok().put("active", false);
    String actual = currentPackage();
    return ok().put("active", true)
        .put("protocol", 1)
        .put("coreInstance", identity.instance)
        .put("displayId", session.id)
        .put("generation", session.generation)
        .put("width", session.width)
        .put("height", session.height)
        .put("frameSeq", session.sequence)
        .put("package", actual)
        .put("packageVerified", !actual.isEmpty())
        .put("inputBackend", session.input.backend())
        .put("inputUnavailable", session.input.unavailableReason())
        .put("missingDisplayFlags", missingDisplayFlags)
        .put("orientation", session.width > session.height ? "landscape" : "portrait");
  }

  private static String currentPackage() {
    try {
      if (hostPackageObserver != null) {
        String value = hostPackageObserver.get();
        return com.deepseekharness.app.util.SelfPackageIdentity.validName(value) ? value : "";
      }
      android.app.ActivityManager manager =
          (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
      for (var task : manager.getRunningTasks(100))
        if (task.getClass().getField("displayId").getInt(task) == session.id
            && task.topActivity != null) return task.topActivity.getPackageName();
    } catch (Throwable unavailable) {
    }
    return "";
  }

  private static JSONObject preview(long since, String generation) throws Exception {
    return VirtualScreenPixels.preview(session, since, generation, VirtualScreenCore::status);
  }

  private static JSONObject launchPackage(String name) throws Exception {
    if (name == null || !name.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"))
      return error("INVALID_PACKAGE");
    Result resolved =
        exec(
            "/system/bin/cmd",
            "package",
            "resolve-activity",
            "--brief",
            "--user",
            "current",
            "-a",
            "android.intent.action.MAIN",
            "-c",
            "android.intent.category.LAUNCHER",
            name);
    String component = null;
    for (String line : resolved.text.split("\\r?\\n"))
      if (line.trim().startsWith(name + "/")) component = line.trim();
    if (component == null || resolved.exit != 0) return error("NO_LAUNCH_ACTIVITY");
    Result launched =
        exec(
            "/system/bin/am",
            "start",
            "--user",
            "current",
            "--display",
            String.valueOf(session.id),
            "-n",
            component,
            "-f",
            "0x18000000");
    if (launched.unknown) return error("LAUNCH_RESULT_UNKNOWN");
    if (launched.exit != 0
        || launched.text.contains("Error")
        || launched.text.contains("Exception")) return error("LAUNCH_REJECTED");
    session.packageName = name;
    return status();
  }

  private static Result exec(String... args) throws Exception {
    Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Thread reader =
        new Thread(
            () -> {
              try (InputStream in = process.getInputStream()) {
                byte[] b = new byte[2048];
                int n;
                while ((n = in.read(b)) >= 0)
                  if (output.size() < 16384) output.write(b, 0, Math.min(n, 16384 - output.size()));
              } catch (IOException ignored) {
              }
            });
    reader.setDaemon(true);
    reader.start();
    boolean ended = process.waitFor(8, TimeUnit.SECONDS);
    if (!ended) process.destroy();
    reader.join(500);
    return new Result(ended ? process.exitValue() : -1, output.toString("UTF-8"), !ended);
  }

  private static final class Result {
    final int exit;
    final String text;
    final boolean unknown;

    Result(int e, String t, boolean u) {
      exit = e;
      text = t;
      unknown = u;
    }
  }

  private static String coordinate(Map<String, String> q, String key, int edge) {
    float n = Float.parseFloat(q.getOrDefault(key, "NaN"));
    if (!Float.isFinite(n) || n < 0 || n >= edge) throw new IllegalArgumentException();
    return String.valueOf(n);
  }

  private static void release() {
    VirtualScreenDisplaySession current = session;
    session = null;
    if (current != null) current.close();
  }

  private static synchronized void shutdown(ServerSocket bound) {
    if (server == bound) shutdown();
  }

  private static synchronized void shutdown() {
    if (!running) return;
    running = false;
    try {
      release();
    } catch (Throwable cleanup) {
      // The independent birth probe reports process exit, never this local cleanup attempt.
    } finally {
      try {
        if (server != null) server.close();
      } catch (IOException ignored) {
      }
      processExit.run();
    }
  }

  private static int flag(String name, List<String> absent) {
    try {
      return DisplayManager.class.getField(name).getInt(null);
    } catch (ReflectiveOperationException e) {
      absent.add(name);
      return 0;
    }
  }

  private static JSONObject failure(Throwable failure) {
    return failure(failure, false);
  }

  /** 只有固定参数的显示创建平台调用可保留技术消息；输入/通用异常不得回显正文。 */
  private static JSONObject failure(Throwable failure, boolean displayCreation) {
    JSONObject value = error(VirtualScreenFailures.code(failure));
    try {
      var captured =
          com.deepseekharness.app.diagnostics.Failure.capture(
              value.optString("error"),
              failure,
              displayCreation
                  ? null
                  : com.deepseekharness.app.diagnostics.Failure.MessagePolicy.safeCodes(Set.of()));
      value.put("failureOrigin", displayCreation ? "ANDROID_DISPLAY_CREATE" : "NATIVE_OPERATION");
      if (captured != null) value.put("failure", new JSONObject(captured.toMap()));
      value.put(
          "cause",
          displayCreation
              ? VirtualScreenFailures.cause(failure)
              : failure == null ? "" : failure.getClass().getSimpleName());
    } catch (Exception ignored) {
    }
    return value;
  }

  private static JSONObject inputResult(VirtualScreenInputResult result) throws Exception {
    if (result == VirtualScreenInputResult.ACCEPTED) {
      try {
        return status();
      } catch (Throwable missingMetadata) {
        return ok().put("resultMetadataUnavailable", true);
      }
    }
    JSONObject value =
        error(
            result == VirtualScreenInputResult.UNKNOWN
                ? "INPUT_RESULT_UNKNOWN"
                : session.input.available()
                    ? "INPUT_REJECTED"
                    : VirtualScreenInputPolicy.INPUT_UNAVAILABLE);
    if (result == VirtualScreenInputResult.UNKNOWN) value.put("executionUnknown", true);
    return value;
  }

  private static JSONObject inputFailure(VirtualScreenDisplaySession failed) throws Exception {
    return inputResult(failed.input.lastResult());
  }

  private static JSONObject ok() {
    JSONObject j = new JSONObject();
    try {
      j.put("ok", true);
    } catch (Exception ignored) {
    }
    return j;
  }

  private static JSONObject error(String code) {
    JSONObject j = new JSONObject();
    try {
      j.put("ok", false).put("error", code);
    } catch (Exception ignored) {
    }
    return j;
  }

  private static String arg(String[] args, String name, String fallback) {
    for (int i = 0; i + 1 < args.length; i++) if (name.equals(args[i])) return args[i + 1];
    return fallback;
  }

  private static Map<String, String> query(String target) throws Exception {
    Map<String, String> q = new HashMap<>();
    int at = target.indexOf('?');
    if (at < 0) return q;
    for (String pair : target.substring(at + 1).split("&")) {
      String[] p = pair.split("=", 2);
      if (p.length == 2) q.put(URLDecoder.decode(p[0], "UTF-8"), URLDecoder.decode(p[1], "UTF-8"));
    }
    return q;
  }

  private static String line(InputStream input, int maximum, long until) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    while (bytes.size() < maximum && elapsed() < until) {
      int b = input.read();
      if (b < 0) throw new EOFException();
      if (b == '\n') return bytes.toString("US-ASCII").replace("\r", "");
      bytes.write(b);
    }
    throw new IOException("HEAD_LIMIT");
  }

  private static VirtualScreenCoreIdentity selfIdentity() throws Exception {
    int pid = android.os.Process.myPid();
    var stat = com.deepseekharness.app.util.ProcStat.parse(readSmall("/proc/self/stat"), pid);
    if (stat == null) throw new IOException("CORE_BIRTH_UNAVAILABLE");
    return new VirtualScreenCoreIdentity(
        pid,
        stat.started,
        android.os.Process.myUid(),
        readSmall("/proc/sys/kernel/random/boot_id").trim(),
        UUID.randomUUID().toString());
  }

  private static String readSmall(String path) throws IOException {
    try (InputStream input = new FileInputStream(path)) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int count;
      while ((count = input.read(buffer)) >= 0) {
        if (output.size() + count > 8192) throw new IOException("CORE_IDENTITY_LIMIT");
        output.write(buffer, 0, count);
      }
      return output.toString("UTF-8");
    }
  }

  /** Read-only birth probe: unreadable identity is UNKNOWN; no termination signal. */
  private static void printExitProof(VirtualScreenCoreIdentity expected) {
    VirtualScreenCoreIdentity.Exit result = VirtualScreenCoreIdentity.Exit.UNKNOWN;
    try {
      String boot = readSmall("/proc/sys/kernel/random/boot_id").trim();
      com.deepseekharness.app.util.ProcStat stat = null;
      Integer owner = null;
      boolean absent = false;
      if (boot.equals(expected.boot)) {
        try {
          android.system.Os.kill(expected.pid, 0);
          stat =
              com.deepseekharness.app.util.ProcStat.parse(
                  readSmall("/proc/" + expected.pid + "/stat"), expected.pid);
          owner = android.system.Os.stat("/proc/" + expected.pid).st_uid;
        } catch (android.system.ErrnoException error) {
          absent = error.errno == android.system.OsConstants.ESRCH;
        }
      }
      result =
          com.deepseekharness.app.util.VirtualScreenExitProofPolicy.classify(
              expected, boot, stat, owner, absent);
    } catch (Throwable unavailable) {
      result = VirtualScreenCoreIdentity.Exit.UNKNOWN;
    }
    System.out.println("DSHA_VSCREEN_EXIT_PROOF 1 " + expected.encode() + " " + result);
    System.out.flush();
  }
}
