package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** One argv/env/process construction path; callers retain lifecycle and exit-proof ownership. */
final class RuntimeLauncher {
  private final Context context;
  private final File rootfs, base, lib, tmp;
  private final RuntimeHostPorts ports;

  RuntimeLauncher(
      Context context, File rootfs, File base, File lib, File tmp, RuntimeHostPorts ports) {
    this.context = context;
    this.rootfs = rootfs;
    this.base = base;
    this.lib = lib;
    this.tmp = tmp;
    this.ports = java.util.Objects.requireNonNull(ports);
  }

  ProcessBuilder builder(String command, LaunchSpec spec) throws IOException {
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      try {
        RuntimeTools.prepareResolver(context, rootfs, ports.settings());
      } catch (IOException error) {
        ports.record("DNS_PREPARE", error.getClass().getSimpleName());
      }
      List<String> argv = spec.runtime.baseArgv(rootfs, spec.hardlinks, spec.dataDomain);
      bindPreloads(argv);
      for (var bind : spec.binds) {
        argv.add("-b");
        argv.add(bind.host() + ":" + bind.guest());
      }
      argv.add("/bin/bash");
      argv.add("-c");
      argv.add(command);
      ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
      if (!spec.isolated && !spec.pipedInput) Compat.redirectStdinDevNull(builder);
      environment(builder, spec);
      if (spec.coldTrace)
        ports.record(
            "EXEC",
            "runtime="
                + spec.runtime.id()
                + " isolated="
                + spec.isolated
                + " prootNoSeccomp="
                + "1".equals(builder.environment().get("PROOT_NO_SECCOMP"))
                + " staticLoader="
                + builder.environment().containsKey("PROROOT_STUB_LOADER")
                + " argv="
                + argv.subList(0, argv.size() - 3));
      return builder;
    }
  }

  void environment(ProcessBuilder builder, LaunchSpec spec) throws IOException {
    spec.runtime.applyEnv(builder, base, lib, tmp);
    if ("proot".equals(spec.runtime.id()) && !spec.hardlinks) {
      File l2s = new File(rootfs, ".l2s");
      if (!l2s.isDirectory() && !l2s.mkdirs())
        throw new IOException("PROOT_L2S_DIRECTORY_UNAVAILABLE");
      builder.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
    }
    builder.environment().put("HOME", "/root");
    builder
        .environment()
        .put("PATH", "/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
    builder.environment().put("TMPDIR", "/tmp");
    builder.environment().put("DEBIAN_FRONTEND", "noninteractive");
    RuntimeTools.applyEnvironment(rootfs, builder.environment(), ports.settings());
    if (context != null) {
      RuntimeLauncherAssets.prepare(context);
      String bridge = "--require=" + RuntimeLauncherAssets.GUEST + "/bridge-token-compat.cjs";
      String previous = builder.environment().getOrDefault("NODE_OPTIONS", "");
      if (!java.util.Arrays.asList(previous.split("\\s+")).contains(bridge))
        builder
            .environment()
            .put("NODE_OPTIONS", bridge + (previous.isEmpty() ? "" : " " + previous));
    }
    if (spec.coldMode != null)
      com.deepseekharness.app.util.ColdInstallPlan.applyEnvironment(
          builder.environment(), spec.coldMode);
    builder.environment().putAll(spec.environment);
  }

  Process start(String command, LaunchSpec spec) throws IOException {
    ProcessBuilder builder = builder(command, spec);
    return spec.isolated
        ? IsolatedInstallProcess.start(builder, tmp, context, spec.record)
        : builder.start();
  }

  void bindPreloads(List<String> argv) throws IOException {
    if (context == null) return;
    File host = RuntimeLauncherAssets.prepare(context);
    argv.add("-b");
    argv.add(host.getAbsolutePath() + ":" + RuntimeLauncherAssets.GUEST);
  }
}
