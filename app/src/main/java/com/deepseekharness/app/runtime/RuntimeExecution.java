package com.deepseekharness.app.runtime;

import android.content.Context;
import android.util.Log;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.MaintenanceGate;
import com.deepseekharness.app.util.RuntimeWorkPort;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;
import java.util.Map;

/** 选择运行方式，负责非交互命令启动、收集及未确认退出的 guest lease。 */
final class RuntimeExecution {
  interface Readiness {
    void require() throws IOException;
  }

  private final Context context;
  private final File rootfs, base, tmp;
  private final boolean forceProot, userDomain;
  private final RuntimeHostPorts ports;
  private final Readiness readiness;
  private final RuntimeNativeFiles nativeFiles;
  private final RuntimeHardlinks hardlinks;
  private final RuntimeLauncher launcher;

  RuntimeExecution(
      Context context,
      File rootfs,
      File base,
      File tmp,
      boolean forceProot,
      boolean userDomain,
      RuntimeHostPorts ports,
      Readiness readiness,
      RuntimeNativeFiles nativeFiles,
      RuntimeLauncher launcher) {
    this.context = context;
    this.rootfs = rootfs;
    this.base = base;
    this.tmp = tmp;
    this.forceProot = forceProot;
    this.userDomain = userDomain;
    this.ports = ports;
    this.readiness = readiness;
    this.nativeFiles = nativeFiles;
    this.launcher = launcher;
    hardlinks = new RuntimeHardlinks(context, rootfs, ports);
  }

  ContainerRuntime runtime() {
    RuntimeHostPorts.Settings settings = ports.settings();
    try {
      if (!forceProot && android.os.Build.VERSION.SDK_INT >= 26 && settings.proroot) {
        ContainerRuntime selected =
            new ContainerRuntime.Proroot(
                context, ContainerRuntime.Proroot.defaultDir(context), settings.staticLoader);
        if (selected.available()) {
          selected.prepare();
          return selected;
        }
        ports.record(
            "RUNTIME_SELECTION_FALLBACK", SensitiveData.redact(selected.unavailableReason()));
      }
    } catch (Exception failure) {
      ports.record("RUNTIME_SELECTION_FALLBACK", SensitiveData.redact(String.valueOf(failure)));
    }
    return proot();
  }

  ContainerRuntime proot() {
    return new ContainerRuntime.Proot(context, nativeFiles.find("libproot.so"));
  }

  LaunchSpec.Builder launch(ContainerRuntime runtime) {
    return LaunchSpec.runtime(runtime).dataDomain(userDomain ? context.getFilesDir() : null);
  }

  void requireReady() throws IOException {
    readiness.require();
  }

  boolean hardlinks() {
    return hardlinks.supported();
  }

  void nativeFiles() throws IOException {
    nativeFiles.prepare();
  }

  RuntimeLauncher launcher() {
    return launcher;
  }

  void prepareTools() throws IOException {
    RuntimeTools.prepare(context, rootfs);
  }

  void ensurePatches() {
    if (!RootfsReadiness.ready(rootfs)) return;
    try {
      prepareTools();
    } catch (IOException error) {
      String detail = UiText.format("运行工具准备失败：%s", SensitiveData.redact(String.valueOf(error)));
      Log.e("DSHA", detail, error);
      throw new IllegalStateException(detail, error);
    }
    File home = new File(rootfs, "root"), link = new File(home, "手机存储");
    if (home.isDirectory() && !link.exists() && !Compat.isSymbolicLink(link))
      try {
        Compat.symlink("/sdcard", link);
      } catch (IOException | RuntimeException error) {
        ports.record("OPTIONAL_STORAGE_LINK_UNAVAILABLE", error.getClass().getSimpleName());
      }
  }

  void prepareGroups() {
    try {
      AndroidGroups.prepare(context, rootfs);
    } catch (IOException | RuntimeException error) {
      ports.record(
          "ANDROID_GROUPS_UNAVAILABLE",
          error.getClass().getSimpleName()
              + ":"
              + SensitiveData.redact(String.valueOf(error.getMessage())));
    }
  }

  record Probe(String runtimeMode, BoundedProcessRunner.Result command) {}

  Probe probe() throws IOException, InterruptedException {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      nativeFiles();
      ensurePatches();
      ContainerRuntime selected = runtime();
      return new Probe(selected.id(), collect("/bin/echo SMOKE_OK", 60_000, false, selected));
    }
  }

  String smokeText() {
    try {
      Probe result = probe();
      String output = GuestCommandOutput.legacy(result.command, 60_000);
      return SensitiveData.redact(
          UiText.format(
              "proot 路径: %s\nnativeLibDir: %s\nruntimeMode: %s\nrootfs exec: %s\n",
              nativeFiles.find("libproot.so").getAbsolutePath(),
              context.getApplicationInfo().nativeLibraryDir,
              result.runtimeMode,
              output == null ? "" : output.trim()));
    } catch (Exception error) {
      return SensitiveData.redact(
          "PROOT_FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
    }
  }

  Process exec(String command, Map<String, String> environment) throws IOException {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      requireReady();
      ContainerRuntime runtime = runtime();
      boolean links = hardlinks();
      prepareTools();
      return start(command, launch(runtime).hardlinks(links).environment(environment).build());
    }
  }

  private Process bounded(String command, ContainerRuntime selected) throws IOException {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      requireReady();
      ContainerRuntime runtime = selected == null ? runtime() : selected;
      boolean links = hardlinks();
      prepareTools();
      if (!"proroot".equals(runtime.id()))
        return start(command, launch(runtime).hardlinks(links).build());
      if (IsolatedInstallProcess.bundledLauncher(context) == null)
        throw new IOException("BOUNDED_PROROOT_SESSION_UNAVAILABLE");
      nativeFiles();
      BoundedGuestSessions.Operation record = BoundedGuestSessions.begin(context.getFilesDir());
      try {
        return start(
            command, launch(runtime).hardlinks(links).isolated(true).record(record).build());
      } catch (IOException | RuntimeException | Error failure) {
        record.uncertain();
        throw failure;
      }
    }
  }

  Process install(String command, boolean pipedInput) throws IOException {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      nativeFiles();
      return start(command, launch(proot()).pipedInput(pipedInput).build());
    }
  }

  Process trial(String command, File privateData, String guest, String expectedMode)
      throws IOException {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      if (!MaintenanceGate.shared().isOwner()) throw new IOException("TRIAL_REQUIRES_MAINTENANCE");
      File parent = new File(base.getParentFile(), "runtime-trials").getCanonicalFile();
      if (!privateData.getCanonicalFile().equals(privateData.getAbsoluteFile())
          || !privateData.getParentFile().getParentFile().equals(parent)
          || !guest.matches("/root/\\.dsha-runtime-trial-[a-f0-9]{32}"))
        throw new IOException("TRIAL_DIRECTORY");
      nativeFiles();
      ContainerRuntime runtime = "proot".equals(expectedMode) ? proot() : runtime();
      if (!runtime.id().equals(expectedMode)) throw new IOException("TRIAL_RUNTIME_CHANGED");
      try {
        runtime.prepare();
      } catch (Exception error) {
        throw new IOException("TRIAL_RUNTIME_UNAVAILABLE", error);
      }
      return start(command, launch(runtime).bind(privateData.getAbsolutePath(), guest).build());
    }
  }

  Process start(String command, LaunchSpec spec) throws IOException {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      return launcher.start(command, spec);
    }
  }

  Process cold(String command) throws IOException {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      ContainerRuntime runtime =
          new ContainerRuntime.Proroot(context, ContainerRuntime.Proroot.defaultDir(context));
      return start(command, launch(runtime).isolated(true).build());
    }
  }

  BoundedProcessRunner.Result collect(String command, long timeoutMs, boolean proot)
      throws IOException, InterruptedException {
    return collect(command, timeoutMs, proot, null);
  }

  BoundedProcessRunner.Result collect(
      String command, long timeoutMs, boolean proot, ContainerRuntime selected)
      throws IOException, InterruptedException {
    RuntimeWorkPort.Work work = RuntimeWorkPort.begin("容器命令");
    Process process = null;
    Throwable original = null;
    try {
      process = proot ? install(command, false) : bounded(command, selected);
      return BoundedProcessRunner.collect(process, timeoutMs, 256 * 1024, Compat::destroy);
    } catch (IOException | InterruptedException | RuntimeException | Error failure) {
      original = failure;
      throw failure;
    } finally {
      Process ending = process;
      Runnable close =
          ending instanceof IsolatedInstallProcess
              ? ((IsolatedInstallProcess) ending)::close
              : null;
      Runnable uncertain =
          ending instanceof IsolatedInstallProcess
              ? ((IsolatedInstallProcess) ending)::markRecordUncertain
              : null;
      BoundedGuestLifetime.finish(ending, close, uncertain, work, original);
    }
  }

  String read(String command, long timeoutMs, boolean proot) {
    try {
      return GuestCommandOutput.legacy(collect(command, timeoutMs, proot), timeoutMs);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return UiText.text("ERROR: 命令等待被中断");
    } catch (IOException | RuntimeException error) {
      return "ERROR: " + SensitiveData.redact(String.valueOf(error));
    }
  }

  String checked(String command) throws IOException {
    try {
      var result = collect(command, 600_000, false);
      if (result.timedOut) throw new IOException(UiText.format("命令执行超时（%s 秒），已请求停止本次进程", 600));
      if (result.exitCode != 0) {
        String out = result.output;
        throw new IOException(
            UiText.format(
                "退出码 %s：\n%s",
                result.exitCode,
                SensitiveData.redact(
                    out.length() > 600 ? out.substring(out.length() - 600) : out)));
      }
      return result.output;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException(UiText.text("命令被中断"), error);
    }
  }
}
