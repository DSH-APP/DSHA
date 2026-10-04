package com.deepseekharness.app.bridge;

import android.content.Context;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.AdbResult;
import com.deepseekharness.app.util.ShellQuote;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.AdbEnvironmentTask;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.InstallProcess;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.AdbWheelCache;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.runtime.TarGzipExtractor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * ADB 无线配对桥（绕过 Shizuku，通道直连设备 adbd）。
 * 把 assets 里的 adb-pair.py / adb-shell.py / adb-setup.sh 注入 rootfs，
 * 在容器内用 TLS1.3-PSK + SPAKE2 完成「无线调试配对 → 直连 adbd」，拿到 uid=2000(shell)。
 */
public final class AdbBridge {

  private static final java.util.concurrent.atomic.AtomicBoolean PAIRING =
      new java.util.concurrent.atomic.AtomicBoolean();

  public static boolean isPairing() {
    return PAIRING.get();
  }

  private AdbBridge() {}

  /** 完整配对/验证/后台探活的同步范围；内部公开方法自动复用当前 owner。 */
  public static <T> T runEnvironmentTask(
      Context ctx, String kind, EnvironmentTaskGate.Operation<T> operation) throws Exception {
    Context app = ctx.getApplicationContext();
    return AdbEnvironmentTask.run(
        kind,
        () ->
            com.deepseekharness.app.backup.HostMaintenancePending.blocked(app.getFilesDir())
                || com.deepseekharness.app.util.MaintenanceGate.shared().isExclusive(),
        operation);
  }

  private static String environmentResult(
      ProotBootstrap proot, String kind, EnvironmentTaskGate.Operation<String> operation) {
    try {
      File files = proot.getRootfsDir().getParentFile().getParentFile();
      return AdbEnvironmentTask.run(
          kind,
          () ->
              com.deepseekharness.app.backup.HostMaintenancePending.blocked(files)
                  || com.deepseekharness.app.util.MaintenanceGate.shared().isExclusive(),
          operation);
    } catch (AdbEnvironmentTask.Busy e) {
      return "ENVIRONMENT_BUSY: " + e.getMessage();
    } catch (Exception e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      return "ADB_ERROR: " + SensitiveData.redact(String.valueOf(e));
    }
  }

  /** 调用方已经持有环境凭据；包括超时回收在内，进程确实退出后才结束操作。 */
  private static String execOwned(ProotBootstrap proot, String command, long timeoutMs) {
    return execOwned(proot, command, timeoutMs, null);
  }

  /** A bounded, one-use input pipe; currently reserved for the 48-byte native VScreen lease. */
  private static String execOwned(
      ProotBootstrap proot, String command, long timeoutMs, byte[] pipedInput) {
    if (!EnvironmentTaskGate.canExecuteCurrentThread())
      throw new IllegalStateException(com.deepseekharness.app.util.UiText.text("ADB 命令缺少环境任务凭据"));
    if (Thread.currentThread().isInterrupted())
      return com.deepseekharness.app.util.UiText.text("ADB_CANCELLED: 操作已取消，未启动命令");
    if (pipedInput != null && pipedInput.length != 48)
      return "[POLICY_BLOCKED] Invalid native virtual-screen ticket length\n[EXIT=126]";
    StringBuilder output = new StringBuilder();
    Process process = null;
    com.deepseekharness.app.core.RuntimeTasks work =
        com.deepseekharness.app.core.RuntimeTasks.begin();
    try {
      process =
          pipedInput == null
              ? proot.execRootfsForInstall(command)
              : proot.execRootfsForInstallWithPipedInput(command);
      if (pipedInput != null) {
        try (java.io.OutputStream input = process.getOutputStream()) {
          input.write(pipedInput);
        }
      }
      int exit =
          InstallProcess.read(
              process,
              timeoutMs,
              true,
              () -> Thread.currentThread().isInterrupted(),
              line -> {
                if (output.length() < 131072) output.append(line).append('\n');
              },
              Compat::destroy);
      if (exit != 0) output.append("[ADB_PROCESS_EXIT=").append(exit).append("]\n");
    } catch (Exception error) {
      if (process != null && !com.deepseekharness.app.util.ProcessTermination.exited(process)) {
        try {
          Compat.destroy(process);
        } catch (RuntimeException stopFailure) {
          error.addSuppressed(stopFailure);
        }
        if (!com.deepseekharness.app.util.ProcessTermination.exited(process)) {
          try {
            EnvironmentTaskGate.retainCurrentUntilExit(process);
          } finally {
            work.retainUntilExit(process);
          }
          output.append("ADB_PROCESS_EXIT_UNCONFIRMED\n");
        }
      }
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      output.append("ADB_ERROR: ").append(SensitiveData.redact(String.valueOf(error))).append('\n');
    } finally {
      work.close();
    }
    return SensitiveData.redact(output.toString());
  }

  private static String execOwned(ProotBootstrap proot, String command) {
    return execOwned(proot, command, 60_000);
  }

  /** 只供 VirtualScreenManager 使用的受管 app_process 启动入口；票据只经 stdin 传递。 */
  public static String executeVirtualScreen(Context ctx, String command, String ticket) {
    if (ticket == null || !ticket.matches("[a-f0-9]{48}"))
      return "[POLICY_BLOCKED] Invalid native virtual-screen ticket\n[EXIT=126]";
    ProotBootstrap proot = com.deepseekharness.app.core.HarnessController.get(ctx).proot();
    return environmentResult(
        proot,
        com.deepseekharness.app.util.UiText.text("启动虚拟屏核心"),
        () ->
            execOwned(
                proot,
                "python3 /root/.dsh/adb-shell.py --vscreen-launch --timeout 20 --connect-timeout 20 -- "
                    + ShellQuote.arg(command),
                60_000,
                ticket.getBytes(StandardCharsets.US_ASCII)));
  }

  public static boolean injected(ProotBootstrap proot) {
    return "YES"
        .equals(
            environmentResult(
                proot,
                com.deepseekharness.app.util.UiText.text("检查 ADB 脚本"),
                () -> injectedState(proot)));
  }

  private static String injectedState(ProotBootstrap proot) {
    return proot.bundledDeviceScriptsMatch() ? "YES" : "NO";
  }

  /** Signed scripts publish through the host FS; STABLE file binds never receive guest mv writes. */
  public static String inject(Context ctx, ProotBootstrap proot) {
    return environmentResult(
        proot,
        com.deepseekharness.app.util.UiText.text("更新 ADB 脚本"),
        () -> injectOwned(ctx, proot));
  }

  private static String injectOwned(Context ctx, ProotBootstrap proot) throws java.io.IOException {
    if (!EnvironmentTaskGate.canExecuteCurrentThread())
      throw new IllegalStateException("ADB_ENVIRONMENT_LEASE_REQUIRED");
    proot.installBundledDeviceScripts();
    return "ADB_SCRIPTS_INSTALLED";
  }

  private static String setup(ProotBootstrap proot) {
    return execOwned(proot, "bash /root/.dsh/adb-setup.sh 2>&1", 180_000);
  }

  /** 幂等准备：注入脚本 + wheels + glibc python + Java 解包 wheels（适配 Android 无网、无 pip）。 */
  public static String ensureReady(Context ctx, ProotBootstrap proot) {
    return ensureReady(ctx, proot, s -> {});
  }

  public static String ensureReady(
      Context ctx, ProotBootstrap proot, java.util.function.Consumer<String> progress) {
    try (var bridge = com.deepseekharness.app.HttpShellService.acquire(ctx)) {
      return environmentResult(
          proot,
          com.deepseekharness.app.util.UiText.text("准备 ADB 环境"),
          () -> ensureReadyOwned(ctx, proot, progress));
    }
  }

  private static String ensureReadyOwned(
      Context ctx, ProotBootstrap proot, java.util.function.Consumer<String> progress) {
    if (Thread.currentThread().isInterrupted())
      return com.deepseekharness.app.util.UiText.text("ADB_CANCELLED: 环境准备已取消");
    StringBuilder sb = new StringBuilder();
    progress.accept(com.deepseekharness.app.util.UiText.text("正在同步 ADB 授权设置…"));
    String settings = applySettings(ctx, proot);
    if (!AdbResult.marker(settings, "SETTINGS_APPLIED")) return settings;
    progress.accept(com.deepseekharness.app.util.UiText.text("正在检查 ADB 脚本版本…"));
    if (!injected(proot)) {
      sb.append(inject(ctx, proot)).append('\n');
      if (!injected(proot))
        return com.deepseekharness.app.util.UiText.format("SCRIPTS_FAILED: ADB 脚本更新未完成\n%s", sb);
    }
    progress.accept(com.deepseekharness.app.util.UiText.text("正在检查 Ubuntu Python…"));
    if (!proot.ensureGlibcPython()) {
      return com.deepseekharness.app.util.UiText.format(
          "GLIBC_PY_INSTALL_FAIL: 无法安装 Ubuntu Python3，请先修复基础环境\n%s", sb);
    }
    progress.accept(com.deepseekharness.app.util.UiText.text("正在检查离线 ADB 依赖…"));
    if (!depsOk(proot)) {
      progress.accept(
          com.deepseekharness.app.util.UiText.text("正在从 APK 补齐缺少的 wheel，保留已恢复的修改版与额外文件…"));
      String cache = injectWheels(ctx, proot);
      sb.append(cache).append('\n');
      if (!AdbResult.marker(cache, "WHEELS_CACHE_READY")) return sb.toString();
      progress.accept(com.deepseekharness.app.util.UiText.text("正在校验所有缓存 wheel 并安装；损坏缓存会原样保留并报告…"));
      String extracted = extractWheelsJava(proot);
      sb.append(extracted).append('\n');
      if (!AdbResult.marker(extracted, "WHEELS_JAVA_EXTRACTED")) return sb.toString();
    }
    if (keyPresent(proot) && depsOk(proot) && wrapperPresent(proot)) {
      return sb.append("SETUP_DONE\n").toString();
    }
    progress.accept(com.deepseekharness.app.util.UiText.text("正在安装离线依赖与新版 ADB 入口，最长等待 3 分钟…"));
    sb.append(setup(proot)).append('\n');
    if (!keyPresent(proot) || !depsOk(proot) || !wrapperPresent(proot)) {
      return com.deepseekharness.app.util.UiText.format(
          "SETUP_FAILED: 安装后验证未通过，请检查以下输出\n%s",
          sb.toString().replace("SETUP_DONE", "SETUP_INCOMPLETE"));
    }
    return sb.toString();
  }

  /** 保存配置后调用；仅同步授权标记，不启动命令、不读取任何密钥。 */
  public static String applySettings(Context ctx, ProotBootstrap proot) {
    return environmentResult(
        proot,
        com.deepseekharness.app.util.UiText.text("同步 ADB 授权"),
        () -> applySettingsOwned(ctx, proot));
  }

  private static String applySettingsOwned(Context ctx, ProotBootstrap proot) {
    if (!proot.isEnvironmentReady())
      return com.deepseekharness.app.util.UiText.text("SETTINGS_PENDING: 环境未就绪，下次准备 ADB 时同步授权设置");
    // Native command policy reads current ConfigStore; the three old guest flags have no reader.
    // Keep their historical bytes as data, but never create/delete them during configuration
    // changes.
    return com.deepseekharness.app.util.UiText.text("SETTINGS_APPLIED: ADB 授权设置已同步");
  }

  /**
   * 用 Java 把 wheels（zip）解包到 glibc python 的 dist-packages。
   * adb_shell_wifi / spake2 / cryptography 是 manylinux(glibc) 轮子，bionic 的 Termux python
   * 加载不了；glibc python + 本方法 = 与 1.1.9.1（rootfs 预装 glibc python3）等效。
   */
  public static String extractWheelsJava(ProotBootstrap proot) {
    return environmentResult(
        proot,
        com.deepseekharness.app.util.UiText.text("安装 ADB 离线依赖"),
        () -> extractWheelsOwned(proot));
  }

  private static String extractWheelsOwned(ProotBootstrap proot) {
    try {
      return AdbWheelCache.install(
          new com.deepseekharness.app.backup.AndroidBackupFileSystem(),
          new File(proot.getRootfsDir(), "root/.dsh/wheels"),
          new File(proot.getRootfsDir(), "usr/lib/python3/dist-packages"),
          proot.getRootfsDir().getParentFile());
    } catch (Exception e) {
      return "WHEELS_EXTRACT_FAIL: " + SensitiveData.redact(String.valueOf(e));
    }
  }

  private static boolean wrapperPresent(ProotBootstrap proot) {
    return proot.bundledDeviceScriptsMatch();
  }

  /** APK 归档先进入临时目录，再按名称补缺；不覆盖恢复出来的 wheel 或缓存归档。 */
  private static String injectWheels(Context ctx, ProotBootstrap proot) {
    File stage = new File(ctx.getCacheDir(), "adb-wheel-bundle-" + java.util.UUID.randomUUID());
    String boundary;
    try {
      if (!stage.mkdir())
        throw new java.io.IOException(
            com.deepseekharness.app.util.UiText.text("无法创建 APK wheel 临时目录"));
      boundary = stage.getCanonicalPath();
    } catch (Exception e) {
      return "WHEELS_INJECT_FAIL: " + SensitiveData.redact(String.valueOf(e));
    }
    String result, cleanup = "";
    try {
      File archive = new File(stage, "adb-wheels.bundle");
      InputStream source;
      try {
        source = ctx.getAssets().open("adb-wheels.bin");
      } catch (java.io.IOException missingBin) {
        source = ctx.getAssets().open("adb-wheels.tar.gz");
      }
      try (InputStream in = source;
          FileOutputStream out = new FileOutputStream(archive)) {
        FileIntegrity.copy(in, out, 128L * 1024 * 1024);
      }
      File bundled = new File(stage, "wheels");
      if (!bundled.mkdir())
        throw new java.io.IOException(com.deepseekharness.app.util.UiText.text("无法创建 wheel 解包目录"));
      TarGzipExtractor.extract(archive, bundled);
      AdbWheelCache.Merge report =
          AdbWheelCache.fillMissing(
              new com.deepseekharness.app.backup.AndroidBackupFileSystem(),
              bundled,
              new File(proot.getRootfsDir(), "root/.dsh/wheels"),
              archive,
              new File(proot.getRootfsDir(), "root/.dsh/adb-wheels.tar.gz"));
      result = report.message();
    } catch (Exception e) {
      result =
          com.deepseekharness.app.util.UiText.format(
              "WHEELS_INJECT_FAIL: %s；已有缓存原样保留，未用 APK 覆盖", SensitiveData.redact(String.valueOf(e)));
    } finally {
      try {
        AdbWheelCache.removeStage(stage, boundary);
      } catch (java.io.IOException e) {
        cleanup = "\nWHEELS_STAGE_CLEANUP_WARN: " + SensitiveData.redact(String.valueOf(e));
      }
    }
    return result + cleanup;
  }

  private static boolean keyPresent(ProotBootstrap proot) {
    String r =
        execOwned(
            proot,
            "test -f /root/.dsh/adbkeys/adbkey && test -f /root/.dsh/adbkeys/adbkey.pub && echo YES || echo NO");
    return r != null && r.contains("YES");
  }

  private static boolean depsOk(ProotBootstrap proot) {
    String r =
        execOwned(
            proot,
            "python3 -c 'import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob' 2>/dev/null && echo YES || echo NO",
            60_000);
    return r != null && r.contains("YES");
  }

  /** 单次配对。pairPort 为空时脚本内尝试 mdns 发现；host 为 App 解析出的真实 IP。 */
  public static String pair(
      ProotBootstrap proot, String code, String pairPort, String connectPort, String host) {
    return environmentResult(
        proot,
        com.deepseekharness.app.util.UiText.text("ADB 配对与连接验证"),
        () -> pairOwned(proot, code, pairPort, connectPort, host));
  }

  private static String pairOwned(
      ProotBootstrap proot, String code, String pairPort, String connectPort, String host) {
    if (!AdbResult.code(code))
      return com.deepseekharness.app.util.UiText.text("INVALID_CODE: 配对码必须恰好为 6 位数字");
    if (!PAIRING.compareAndSet(false, true))
      return com.deepseekharness.app.util.UiText.text("PAIR_BUSY: 已有配对正在进行，请等待结果");
    try {
      String c =
          "python3 -u /root/.dsh/adb-pair.py --code "
              + ShellQuote.arg(code)
              + pairOptions(pairPort, connectPort, host);
      String out = execOwned(proot, c, 120_000);
      if (AdbResult.pairState(out) == AdbResult.PairState.CONNECTED) {
        out += "\n" + grantSecureSettings(proot);
      }
      return SensitiveData.redact(
          out == null ? com.deepseekharness.app.util.UiText.text("PAIR_ERROR: 配对进程没有返回结果") : out);
    } catch (Exception e) {
      return "PAIR_ERROR: " + SensitiveData.redact(String.valueOf(e));
    } finally {
      PAIRING.set(false);
    }
  }

  public static String verify(ProotBootstrap proot, String connectPort, String host) {
    return environmentResult(
        proot,
        com.deepseekharness.app.util.UiText.text("验证 ADB 连接"),
        () -> verifyOwned(proot, connectPort, host));
  }

  private static String verifyOwned(ProotBootstrap proot, String connectPort, String host) {
    try {
      String out =
          execOwned(
              proot,
              "python3 -u /root/.dsh/adb-pair.py --verify-only"
                  + pairOptions("", connectPort, host),
              60_000);
      if (AdbResult.pairState(out) == AdbResult.PairState.CONNECTED)
        out += "\n" + grantSecureSettings(proot);
      return SensitiveData.redact(
          out == null ? com.deepseekharness.app.util.UiText.text("CONNECT_WARN: 验证没有返回结果") : out);
    } catch (Exception e) {
      return "CONNECT_WARN: " + SensitiveData.redact(String.valueOf(e));
    }
  }

  private static String pairOptions(String pairPort, String connectPort, String host) {
    StringBuilder out = new StringBuilder();
    int pp = AdbResult.port(pairPort), cp = AdbResult.port(connectPort);
    if (pp > 0) out.append(" --port ").append(pp);
    if (cp > 0) out.append(" --connect-port ").append(cp);
    if (host != null && !host.trim().isEmpty()) {
      if (!localAddresses().contains(host.trim()))
        throw new IllegalArgumentException(
            com.deepseekharness.app.util.UiText.text("请填写本机无线调试页面的 IP 地址"));
      out.append(" --host ").append(ShellQuote.arg(host.trim()));
    }
    return out.toString();
  }

  /** 配对成功后通过 adb shell（uid=2000）给本 App 授予 WRITE_SECURE_SETTINGS，
   *  之后开机广播可自动开启无线调试（保活依赖）。 */
  private static String grantSecureSettings(ProotBootstrap proot) {
    try {
      String r = execOwned(proot, "python3 /root/.dsh/adb-pair.py --grant-keepalive 2>&1", 45_000);
      android.util.Log.i(
          "DSHA-ADB",
          com.deepseekharness.app.util.UiText.format(
              "WRITE_SECURE_SETTINGS 授权结果: %s", SensitiveData.redact(r)));
      return r != null && r.trim().endsWith("[EXIT=0]")
          ? com.deepseekharness.app.util.UiText.text("KEEPALIVE_OK: 已允许自动恢复无线调试")
          : com.deepseekharness.app.util.UiText.format(
              "KEEPALIVE_WARN: 连接已验证，但自动恢复授权未完成；重启后可能需手动打开无线调试\n%s", SensitiveData.redact(r));
    } catch (Throwable t) {
      android.util.Log.w(
          "DSHA-ADB",
          com.deepseekharness.app.util.UiText.format(
              "WRITE_SECURE_SETTINGS 授权失败: %s", SensitiveData.redact(String.valueOf(t))));
      return com.deepseekharness.app.util.UiText.text("KEEPALIVE_WARN: 自动恢复授权未完成；重启后可能需手动打开无线调试");
    }
  }

  /** 状态快照：key/deps/connect_port（供 UI 展示）。 */
  public static String status(ProotBootstrap proot) {
    return environmentResult(
        proot, com.deepseekharness.app.util.UiText.text("读取 ADB 状态"), () -> statusOwned(proot));
  }

  private static String statusOwned(ProotBootstrap proot) {
    String cmd =
        "K=$(test -f /root/.dsh/adbkeys/adbkey && echo YES || echo NO); "
            + "D=$(python3 -c 'import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob' 2>/dev/null && echo YES || echo NO); "
            + "P=$(test -f /root/.dsh/adbkeys/connect_port && cat /root/.dsh/adbkeys/connect_port || echo -); "
            + "echo 'key='$K' deps='$D' port='$P";
    return execOwned(proot, cmd, 60_000);
  }

  /** 后台探测也使用同一进程回收与环境任务范围。 */
  public static String probe(ProotBootstrap proot, Endpoint endpoint) {
    return environmentResult(
        proot,
        com.deepseekharness.app.util.UiText.text("ADB 后台探活"),
        () -> {
          String options =
              endpoint == null
                  ? ""
                  : " --host " + ShellQuote.arg(endpoint.host) + " --port " + endpoint.port;
          return execOwned(
              proot,
              "python3 /root/.dsh/adb-shell.py"
                  + " --connect-timeout 20 --timeout 10"
                  + options
                  + " id 2>&1",
              60_000);
        });
  }

  public static java.util.Set<String> localAddresses() {
    java.util.Set<String> result = new java.util.HashSet<>();
    result.add("127.0.0.1");
    result.add("::1");
    try {
      java.util.Enumeration<java.net.NetworkInterface> interfaces =
          java.net.NetworkInterface.getNetworkInterfaces();
      while (interfaces != null && interfaces.hasMoreElements()) {
        java.net.NetworkInterface ni = interfaces.nextElement();
        if (!ni.isUp()) continue;
        java.util.Enumeration<java.net.InetAddress> addresses = ni.getInetAddresses();
        while (addresses.hasMoreElements()) result.add(addresses.nextElement().getHostAddress());
      }
    } catch (Exception ignored) {
    }
    return result;
  }

  public static final class Endpoint {
    public final String host;
    public final int port;

    Endpoint(String host, int port) {
      this.host = host;
      this.port = port;
    }
  }

  /** 后台调用。每轮独立、串行解析，结束后停止发现，迟到回调不能污染新一轮。 */
  public static Endpoint discover(
      Context ctx, String type, long timeoutMs, java.util.function.Consumer<String> progress) {
    android.net.nsd.NsdManager manager =
        (android.net.nsd.NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
    if (manager == null) {
      progress.accept(com.deepseekharness.app.util.UiText.text("系统没有端口发现服务，请手动填写端口"));
      return null;
    }
    java.util.Set<String> local = localAddresses();
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean closed =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<Endpoint> result =
        new java.util.concurrent.atomic.AtomicReference<>();
    class Discovery implements android.net.nsd.NsdManager.DiscoveryListener {
      final java.util.ArrayDeque<android.net.nsd.NsdServiceInfo> queue =
          new java.util.ArrayDeque<>();
      boolean resolving;

      @Override
      public void onDiscoveryStarted(String t) {
        if (closed.get()) stop();
      }

      @Override
      public void onDiscoveryStopped(String t) {}

      @Override
      public void onStartDiscoveryFailed(String t, int error) {
        if (!closed.get())
          progress.accept(
              com.deepseekharness.app.util.UiText.format("端口发现未启动（错误 %s），可手动填写端口", error));
        done.countDown();
      }

      @Override
      public void onStopDiscoveryFailed(String t, int error) {}

      @Override
      public synchronized void onServiceFound(android.net.nsd.NsdServiceInfo info) {
        if (closed.get() || queue.size() >= 32) return;
        queue.add(info);
        next();
      }

      @Override
      public synchronized void onServiceLost(android.net.nsd.NsdServiceInfo info) {
        queue.removeIf(item -> item.getServiceName().equals(info.getServiceName()));
      }

      synchronized void next() {
        if (closed.get() || resolving || queue.isEmpty()) return;
        resolving = true;
        android.net.nsd.NsdServiceInfo info = queue.remove();
        try {
          manager.resolveService(
              info,
              new android.net.nsd.NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(android.net.nsd.NsdServiceInfo i, int error) {
                  if (!closed.get())
                    progress.accept(
                        com.deepseekharness.app.util.UiText.format(
                            "一个服务解析失败（错误 %s），继续查找本机端口…", error));
                  resolved();
                }

                @Override
                public void onServiceResolved(android.net.nsd.NsdServiceInfo i) {
                  if (!closed.get()
                      && i.getHost() != null
                      && i.getPort() > 0
                      && i.getPort() <= 65535
                      && local.contains(i.getHost().getHostAddress())) {
                    result.compareAndSet(
                        null, new Endpoint(i.getHost().getHostAddress(), i.getPort()));
                    done.countDown();
                  }
                  resolved();
                }
              });
        } catch (RuntimeException e) {
          resolving = false;
          next();
        }
      }

      synchronized void resolved() {
        resolving = false;
        next();
      }

      void stop() {
        try {
          manager.stopServiceDiscovery(this);
        } catch (RuntimeException ignored) {
        }
      }
    }
    Discovery discovery = new Discovery();
    try {
      manager.discoverServices(type, android.net.nsd.NsdManager.PROTOCOL_DNS_SD, discovery);
      if (!done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS))
        progress.accept(
            com.deepseekharness.app.util.UiText.text("未在时限内发现本机端口，请确认系统配对弹窗仍打开，或手动填写端口"));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (RuntimeException e) {
      progress.accept(
          com.deepseekharness.app.util.UiText.format(
              "端口发现失败：%s", SensitiveData.redact(String.valueOf(e))));
    } finally {
      closed.set(true);
      discovery.stop();
    }
    return result.get();
  }
}
