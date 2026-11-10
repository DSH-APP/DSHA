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
      var apk = context == null ? null : RuntimeApkAssets.get(context);
      File preloads = context == null ? null : RuntimeLauncherAssets.prepare(context, apk);
      bindPreloads(argv, preloads);
      for (var bind : spec.binds) {
        argv.add("-b");
        argv.add(bind.host() + ":" + bind.guest());
      }
      argv.add("/bin/bash");
      argv.add("-c");
      argv.add(command);
      ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
      if (!spec.isolated && !spec.pipedInput) Compat.redirectStdinDevNull(builder);
      environment(builder, spec, preloads, apk);
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
    environment(builder, spec, null, context == null ? null : RuntimeApkAssets.get(context));
  }

  private void environment(
      ProcessBuilder builder, LaunchSpec spec, File preparedPreloads, RuntimeApkAssets.Snapshot apk)
      throws IOException {
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
      // builder() already checked the exact overlay it bound. Independent terminal/recovery
      // callers still prepare here; no mutable filesystem proof survives a builder invocation.
      if (preparedPreloads == null) RuntimeLauncherAssets.prepare(context, apk);
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
    // Classification evidence comes from this APK, never a guest-writable JSON
    // or caller-supplied environment. Native backup independently reads the APK.
    builder.environment().remove("DSHA_MANAGED_PACKAGE_PROOFS_SHA256");
    if (context != null)
      builder.environment().put("DSHA_MANAGED_PACKAGE_PROOFS_SHA256", apk.packageProofSha256);
  }

  static String managedPackageProofAssetSha256(Context context) throws IOException {
    return RuntimeApkAssets.get(context).packageProofSha256;
  }

  Process start(String command, LaunchSpec spec) throws IOException {
    return start(command, spec, null);
  }

  Process start(String command, LaunchSpec spec, RuntimeCommandTiming timing) throws IOException {
    ProcessBuilder builder = builder(command, spec);
    if (timing != null) timing.mark("launcher-prepare");
    Process process =
        spec.isolated
            ? IsolatedInstallProcess.start(builder, tmp, context, spec.record)
            : builder.start();
    if (timing != null) timing.mark("process-create");
    return process;
  }

  void bindPreloads(List<String> argv) throws IOException {
    bindPreloads(argv, context == null ? null : RuntimeLauncherAssets.prepare(context));
  }

  private void bindPreloads(List<String> argv, File host) {
    if (host == null) return;
    argv.add("-b");
    argv.add(host.getAbsolutePath() + ":" + RuntimeLauncherAssets.GUEST);
  }
}
