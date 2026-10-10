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
import com.deepseekharness.app.util.VirtualScreenErrors;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 独立 shell/root 进程，只接受认证后的固定虚拟屏操作。 */
@android.annotation.SuppressLint("NewApi")
@androidx.annotation.RequiresApi(30)
public final class VirtualScreenCore {
  private static final String CLASS = VirtualScreenCore.class.getName();
  private static volatile long lastRequest = SystemClock.elapsedRealtime();
  private static volatile boolean running = true;
  private static String token;
  private static String selfPackage;
  private static Context context;
  private static Session session;
  private static ServerSocket server;
  private static String startupStage = "CONTEXT";
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
      int port = Integer.parseInt(arg(args, "--port", "8998"));
      if (port < 8000 || port > 8999) throw new IllegalArgumentException("INVALID_PORT");
      String classPath = System.getProperty("java.class.path", "");
      if (!installedDshaApkPath(system).equals(classPath))
        throw new SecurityException("INVALID_CLASSPATH");
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
      server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
      Thread watchdog =
          new Thread(
              () -> {
                while (running) {
                  try {
                    Thread.sleep(1000);
                  } catch (InterruptedException e) {
                    return;
                  }
                  Session current = session;
                  if (current != null) current.input.expire();
                  if (SystemClock.elapsedRealtime() - lastRequest > 60_000) shutdown();
                }
              },
              "vscreen-heartbeat");
      watchdog.setDaemon(true);
      watchdog.start();
      Thread accept =
          new Thread(
              () -> {
                while (running)
                  try {
                    Socket socket = server.accept();
                    try {
                      CLIENTS.execute(() -> handle(socket));
                    } catch (RejectedExecutionException full) {
                      socket.close();
                    }
                  } catch (IOException closed) {
                    if (running) shutdown();
                  }
              },
              "vscreen-listener");
      accept.setDaemon(true);
      accept.start();
      // This one-time bootstrap secret travels only through the managed channel result,
      // never in app_process argv or the device command line.
      System.out.println("DSHA_VSCREEN_BOOTSTRAP " + token);
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
      long deadline = SystemClock.elapsedRealtime() + 8000;
      startupStage = "BOOTSTRAP";
      String childToken = readBootstrap(child, deadline);
      startupStage = "REACHABILITY";
      while (SystemClock.elapsedRealtime() < deadline) {
        if (!child.isAlive()) throw new IOException("CORE_EXITED");
        HttpURLConnection c = null;
        try {
          c =
              (HttpURLConnection)
                  new URL("http://127.0.0.1:" + port + "/vscreen/ping").openConnection();
          c.setRequestProperty("Authorization", "Bearer " + childToken);
          c.setConnectTimeout(200);
          c.setReadTimeout(200);
          if (c.getResponseCode() == 200) {
            System.out.println("DSHA_VSCREEN_STARTED " + childToken);
            System.out.flush();
            started = true;
            return;
          }
        } catch (IOException waiting) {
          Thread.sleep(80);
        } finally {
          if (c != null) c.disconnect();
        }
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

  private static String readBootstrap(Process child, long deadline) throws Exception {
    com.deepseekharness.app.util.VirtualScreenBootstrap bootstrap =
        new com.deepseekharness.app.util.VirtualScreenBootstrap();
    InputStream input = child.getInputStream();
    while (SystemClock.elapsedRealtime() < deadline) {
      int available = input.available();
      if (available <= 0) {
        if (!child.isAlive()) throw new IOException("CORE_EXITED");
        Thread.sleep(20);
        continue;
      }
      int value = input.read();
      if (value < 0) throw new EOFException("CORE_BOOTSTRAP_EOF");
      bootstrap.accept(value);
      if (!bootstrap.token().isEmpty()) return bootstrap.token();
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

  private static void handle(Socket socket) {
    boolean close = false;
    try (Socket accepted = socket) {
      accepted.setSoTimeout(3000);
      InputStream input = accepted.getInputStream();
      long deadline = SystemClock.elapsedRealtime() + 3000;
      String[] parts = line(input, 24576, deadline).split(" ", 3);
      if (parts.length != 3 || !parts[0].equals("GET")) return;
      String authorization = "";
      int remaining = 32768;
      for (; ; ) {
        String header = line(input, Math.min(remaining, 4096), deadline);
        remaining -= header.length() + 2;
        if (header.isEmpty()) break;
        if (remaining <= 0) throw new IOException("HEAD_LIMIT");
        if (header.toLowerCase(Locale.ROOT).startsWith("authorization:"))
          authorization = header.substring(14).trim();
      }
      boolean authorized =
          java.security.MessageDigest.isEqual(
              ("Bearer " + token).getBytes(StandardCharsets.US_ASCII),
              authorization.getBytes(StandardCharsets.US_ASCII));
      String path = parts[1].split("\\?", 2)[0];
      JSONObject result;
      if (!authorized) result = error("UNAUTHORIZED");
      else {
        lastRequest = SystemClock.elapsedRealtime();
        result = route(path, query(parts[1]));
        close = path.equals("/vscreen/close");
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
      if (close) shutdown();
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
            String rejection = VirtualScreenPolicy.frameRejection(requested, session.sequence);
            if (rejection != null)
              return staleFrame(rejection, requested, session.sequence, session.observed);
          }
        }
        return session.input.touch(stroke, action, x, y) ? status() : error("TOUCH_REJECTED");
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
                observed,
                session.sequence,
                session.observed,
                SystemClock.elapsedRealtime() - session.observedAt);
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
            if (!session.input.touch(stroke, 0, x, y)) return error("INPUT_REJECTED");
            int steps = Math.max(1, ms / 16);
            for (int n = 1; n < steps; n++) {
              Thread.sleep(16);
              if (!session.input.touch(
                  stroke, 2, x + (endX - x) * n / steps, y + (endY - y) * n / steps))
                return error("INPUT_REJECTED");
            }
            return session.input.touch(stroke, 1, endX, endY) ? status() : error("INPUT_REJECTED");
          } finally {
            session.input.cancel();
          }
        }
        if (path.equals("/vscreen/key")) {
          int code = Integer.parseInt(q.getOrDefault("keycode", "-1"));
          if (code != 3 && code != 4 && code != 66 && code != 67) return error("INVALID_KEY");
          session.observed = -1;
          return session.input.key(code) ? status() : error("INPUT_REJECTED");
        }
        List<String> argv =
            new ArrayList<>(List.of("/system/bin/input", "-d", String.valueOf(session.id)));
        switch (path) {
          case "/vscreen/type":
            String text = q.getOrDefault("text", "");
            if (text.isEmpty() || text.length() > 2000) return error("INVALID_TEXT");
            if (!text.matches("[\\x20-\\x7e]+")) return error("ACCESSIBILITY_REQUIRED_FOR_UNICODE");
            argv.addAll(List.of("text", text.replace(" ", "%s")));
            break;
          default:
            return error("UNKNOWN_ROUTE");
        }
        // 消耗观察，执行失败或结果未知也不能重放同一动作。
        session.observed = -1;
        Result r = exec(argv.toArray(new String[0]));
        if (r.unknown) return error("INPUT_RESULT_UNKNOWN");
        if (r.exit != 0 || r.text.contains("Error") || r.text.contains("Exception"))
          return error("INPUT_REJECTED");
        return status();
      }
    } catch (IllegalArgumentException error) {
      return error("INVALID_ARGUMENT");
    } catch (Throwable error) {
      return error(VirtualScreenErrors.stable(error));
    }
  }

  private static JSONObject create(int width, int height) throws Exception {
    int[] size = VirtualScreenPolicy.phoneSize(width, height);
    if (session != null && session.width == size[0] && session.height == size[1]) return status();
    release();
    ImageReader reader = ImageReader.newInstance(size[0], size[1], PixelFormat.RGBA_8888, 3);
    // 只有真的持有 ADD_TRUSTED_DISPLAY 才请求受信任显示：API 31+ 不持有却请求会被系统直接拒绝
    // （issue #123：Android 12 的 shell 未申请该权限，ADB/Shizuku 通道因此恒失败）。
    boolean trusted =
        VirtualScreenPolicy.requestTrustedDisplay(
            android.os.Process.myUid(), holdsTrustedDisplay());
    try {
      DisplayManager manager = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
      int flags =
          DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
              | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
              | flag("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH")
              | flag("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL")
              | flag("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS");
      if (trusted) flags |= flag("VIRTUAL_DISPLAY_FLAG_TRUSTED");
      VirtualDisplay display =
          manager.createVirtualDisplay(
              "DSHA-VirtualScreen", size[0], size[1], 320, reader.getSurface(), flags);
      if (display == null) throw new IOException("DISPLAY_CREATE_FAILED");
      session = new Session(display, reader, size[0], size[1]);
      session.start();
      return status();
    } catch (IllegalArgumentException invalid) {
      reader.close();
      return error("INVALID_ARGUMENT");
    } catch (Throwable error) {
      reader.close();
      // 创建失败既给稳定码、也不丢掉真实原因：只有"确实请求过受信任显示且被系统拒绝"才是
      // 该通道拿不到 ADD_TRUSTED_DISPLAY，其余按异常类别映射（issue #123）。
      String stable = VirtualScreenErrors.stable(error);
      boolean trustedDenied = trusted && VirtualScreenErrors.PERMISSION_DENIED.equals(stable);
      return error(trustedDenied ? VirtualScreenErrors.TRUSTED_DISPLAY_DENIED : stable);
    }
  }

  /**
   * 自身是否真的持有 ADD_TRUSTED_DISPLAY。{@code checkSelfPermission} 判的是**本进程 uid**
   * （{@code Process.myPid()/myUid()}），与 DisplayManagerService 的 {@code Binder.getCallingUid()}
   * 一致 —— 不要改成按包名判断，那会在 ADB/Shizuku 通道上给出错误答案。查不到就按"不持有"处理：
   * 不请求 TRUSTED 的创建仍然可以成功，方向安全。
   */
  private static boolean holdsTrustedDisplay() {
    try {
      return context != null
          && context.checkSelfPermission("android.permission.ADD_TRUSTED_DISPLAY")
              == android.content.pm.PackageManager.PERMISSION_GRANTED;
    } catch (Throwable unavailable) {
      return false;
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
        .put("displayId", session.id)
        .put("generation", session.generation)
        .put("width", session.width)
        .put("height", session.height)
        .put("frameSeq", session.sequence)
        .put("package", actual)
        .put("packageVerified", !actual.isEmpty())
        .put("orientation", session.width > session.height ? "landscape" : "portrait");
  }

  private static String currentPackage() {
    try {
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
    Bitmap copy;
    long seq, captured;
    synchronized (session.lock) {
      if (session.frame == null) return error("FRAME_NOT_READY");
      if (since == session.sequence && session.generation.equals(generation))
        return status().put("unchanged", true).put("frameSeq", session.sequence);
      copy = session.frame.copy(Bitmap.Config.ARGB_8888, false);
      seq = session.sequence;
      captured = session.capturedAt;
      session.observed = seq;
      session.observedAt = SystemClock.elapsedRealtime();
    }
    Bitmap scaled = null;
    try {
      if (session.encodedSequence == seq)
        return status()
            .put("frameSeq", seq)
            .put("previewB64", session.encoded)
            .put("frameAgeMs", SystemClock.elapsedRealtime() - captured);
      int width = Math.min(720, copy.getWidth()),
          height = Math.round(copy.getHeight() * width / (float) copy.getWidth());
      scaled = Bitmap.createScaledBitmap(copy, width, height, true);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      scaled.compress(Bitmap.CompressFormat.JPEG, 65, out);
      session.encoded =
          android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
      session.encodedSequence = seq;
      return status()
          .put("frameSeq", seq)
          .put("previewWidth", width)
          .put("previewHeight", height)
          .put("frameAgeMs", SystemClock.elapsedRealtime() - captured)
          .put("previewB64", session.encoded);
    } finally {
      if (scaled != null && scaled != copy) scaled.recycle();
      copy.recycle();
    }
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
    if (session != null) {
      session.close();
      session = null;
    }
  }

  private static synchronized void shutdown() {
    running = false;
    release();
    try {
      if (server != null) server.close();
    } catch (IOException ignored) {
    }
    System.exit(0);
  }

  private static int flag(String name) {
    try {
      return DisplayManager.class.getField(name).getInt(null);
    } catch (ReflectiveOperationException e) {
      return 0;
    }
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
    while (bytes.size() < maximum && SystemClock.elapsedRealtime() < until) {
      int b = input.read();
      if (b < 0) throw new EOFException();
      if (b == '\n') return bytes.toString("US-ASCII").replace("\r", "");
      bytes.write(b);
    }
    throw new IOException("HEAD_LIMIT");
  }

  private static final class Session {
    final VirtualDisplay display;
    final ImageReader reader;
    final int width, height, id;
    final Object lock = new Object();
    final String generation = UUID.randomUUID().toString();
    volatile boolean active = true;
    volatile long sequence;
    long observed = -1, observedAt, capturedAt, encodedSequence = -1;
    String packageName = "", encoded = "";
    Bitmap frame;
    final VirtualScreenInput input;

    Session(VirtualDisplay d, ImageReader r, int w, int h) throws Exception {
      display = d;
      reader = r;
      width = w;
      height = h;
      id = d.getDisplay().getDisplayId();
      input = new VirtualScreenInput(id);
    }

    void start() {
      Thread t =
          new Thread(
              () -> {
                while (active) {
                  Image image = null;
                  try {
                    image = reader.acquireLatestImage();
                    if (image != null) {
                      Bitmap next = copy(image);
                      synchronized (lock) {
                        if (!active) {
                          next.recycle();
                          break;
                        }
                        if (frame != null && frame.sameAs(next)) next.recycle();
                        else {
                          if (frame != null) frame.recycle();
                          frame = next;
                          sequence++;
                        }
                        capturedAt = SystemClock.elapsedRealtime();
                      }
                    }
                  } catch (Throwable ignored) {
                  } finally {
                    if (image != null) image.close();
                  }
                  try {
                    Thread.sleep(33);
                  } catch (InterruptedException end) {
                    return;
                  }
                }
              },
              "vscreen-frames");
      t.setDaemon(true);
      t.start();
    }

    void close() {
      active = false;
      try {
        input.cancel();
      } catch (Exception ignored) {
      }
      synchronized (lock) {
        if (frame != null) {
          frame.recycle();
          frame = null;
        }
      }
      display.release();
      reader.close();
    }

    Bitmap copy(Image image) {
      Image.Plane p = image.getPlanes()[0];
      int paddedWidth = p.getRowStride() / p.getPixelStride();
      ByteBuffer buffer = p.getBuffer();
      buffer.rewind();
      Bitmap b = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888);
      b.copyPixelsFromBuffer(buffer);
      if (paddedWidth == width) return b;
      Bitmap cropped = Bitmap.createBitmap(b, 0, 0, width, height);
      b.recycle();
      return cropped;
    }
  }
}
