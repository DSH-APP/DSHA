package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.ProcessTermination;
import com.deepseekharness.app.util.RuntimeWorkPort;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 用同一次运行方式选择准备终端 argv 与环境；会话所有者继续持有出生身份。 */
final class TerminalRuntime {
  record Launch(String[] argv, String[] environment) {}

  private final Context context;
  private final File rootfs;
  private final RuntimeExecution execution;
  private final RuntimeHostPorts ports;

  TerminalRuntime(
      Context context, File rootfs, RuntimeExecution execution, RuntimeHostPorts ports) {
    this.context = context;
    this.rootfs = rootfs;
    this.execution = execution;
    this.ports = ports;
  }

  private LaunchSpec spec() throws IOException {
    execution.requireReady();
    execution.nativeFiles();
    RootfsInstaller.ensurePython(context, rootfs);
    RootfsInstaller.ensurePnpm(context, rootfs);
    execution.prepareTools();
    execution.prepareGroups();
    ContainerRuntime selected = execution.runtime();
    return execution.launch(selected).hardlinks(execution.hardlinks()).build();
  }

  Launch launch(String... guestCommand) {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      return prepare(execution.launcher(), rootfs, spec(), guestCommand);
    } catch (IOException error) {
      throw new IllegalStateException("PTY_ENV_UNAVAILABLE", error);
    }
  }

  static Launch prepare(
      RuntimeLauncher launcher, File rootfs, LaunchSpec spec, String... guestCommand)
      throws IOException {
    List<String> argv = spec.runtime.baseArgv(rootfs, spec.hardlinks, spec.dataDomain);
    launcher.bindPreloads(argv);
    if (guestCommand == null || guestCommand.length == 0) {
      argv.add("/bin/bash");
      argv.add("-c");
      argv.add("stty sane 2>/dev/null || stty echo icanon 2>/dev/null || true; exec /bin/bash -l");
    } else java.util.Collections.addAll(argv, guestCommand);
    ProcessBuilder probe = new ProcessBuilder("/system/bin/true");
    launcher.environment(probe, spec);
    probe.environment().put("LANG", "C.UTF-8");
    probe.environment().put("LC_ALL", "C.UTF-8");
    List<String> environment = new ArrayList<>(probe.environment().size());
    probe
        .environment()
        .forEach(
            (key, value) -> {
              if (key != null && value != null) environment.add(key + "=" + value);
            });
    return new Launch(argv.toArray(new String[0]), environment.toArray(new String[0]));
  }

  Process interactive() throws IOException {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      RuntimeWorkPort.Work work = RuntimeWorkPort.beginDetached("后台容器进程");
      Process process = null;
      try {
        LaunchSpec spec = spec();
        List<String> argv = spec.runtime.baseArgv(rootfs, spec.hardlinks, spec.dataDomain);
        execution.launcher().bindPreloads(argv);
        argv.add("/bin/bash");
        ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
        execution.launcher().environment(builder, spec);
        builder.environment().put("DSH_CONFIRM", "0");
        builder.environment().put("DSH_INTERACTIVE", "1");
        process = builder.start();
        work.retainUntilExit(process);
        return process;
      } catch (IOException | RuntimeException | Error error) {
        if (process == null || ProcessTermination.exited(process)) work.close();
        throw error;
      }
    }
  }
}
