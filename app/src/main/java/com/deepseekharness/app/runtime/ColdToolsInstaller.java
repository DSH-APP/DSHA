package com.deepseekharness.app.runtime;

import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/** Verifies the private package slot, selects a cold runtime and retains uncertain work. */
final class ColdToolsInstaller {
  private ColdToolsInstaller() {}

  static ProotBootstrap.ColdRuntimeSelection run(
      ProotBootstrap boot, java.util.function.BiConsumer<Long, Long> progress) throws IOException {
    String slot = ".dsha-bundled-tools-" + java.util.UUID.randomUUID();
    var fs = new com.deepseekharness.app.backup.AndroidBackupFileSystem();
    String appData = boot.ctx.getApplicationInfo().dataDir;
    if (appData == null) throw new IOException("COLD_PACKAGE_HOST_ROOT");
    var hostRoot =
        com.deepseekharness.app.util.ColdInstallPackages.bindPrivateFiles(
            fs, new File(appData), boot.ctx.getFilesDir());
    ProotBootstrap.requireOwnedColdFiles(hostRoot.files);
    File packages =
        boot.coldCandidate == null
            ? com.deepseekharness.app.util.ColdInstallPackages.ownedSlot(fs, hostRoot.files, slot)
            : com.deepseekharness.app.util.ColdInstallPackages.ownedSlot(
                fs, hostRoot, boot.coldCandidate, slot);
    if (!fs.stat(packages).type.equals("MISSING"))
      throw new IOException("BUNDLED_TOOLS_SLOT_UNAVAILABLE");
    fs.directory(packages);
    // 每轮使用独立受管槽位；失败轮次保留，后续重试不覆盖未知原件。
    try (InputStream input = boot.ctx.getAssets().open("ubuntu-tools.bin")) {
      TarGzipExtractor.extractAuto(input, packages, 0);
    }
    String guest = "/root/" + slot;
    String frozen = packageFingerprint(boot, fs, hostRoot, slot);
    try {
      ContainerRuntime.Proroot candidate =
          new ContainerRuntime.Proroot(boot.ctx, ContainerRuntime.Proroot.defaultDir(boot.ctx));
      boolean available = candidate.available();
      boolean isolated = IsolatedInstallProcess.supported(boot.ctx);
      boot.hostPorts()
          .record(
              "CAPABILITY",
              "proroot="
                  + available
                  + " isolation="
                  + isolated
                  + " supervisor="
                  + IsolatedInstallProcess.sessionLauncher(boot.ctx)
                  + "\n"
                  + (available ? "" : candidate.unavailableReason()));
      var settings = boot.hostPorts().settings();
      var modes =
          com.deepseekharness.app.util.ColdInstallPlan.modes(
              settings.proroot, available, isolated, settings.disableProotSeccomp);
      var selected =
          com.deepseekharness.app.util.ColdInstallPlan.run(
              modes,
              (mode, probe) -> {
                hostRoot.verify(fs);
                ProotBootstrap.requireOwnedColdFiles(hostRoot.files);
                String command;
                if (probe) {
                  boot.extractionStage(
                      progress,
                      com.deepseekharness.app.util.UiText.format("检查离线安装运行方式：%s", mode.name()));
                  command =
                      com.deepseekharness.app.util.ColdInstallPlan.probeCommand(
                          "/root/.dsha-cold-probe-" + java.util.UUID.randomUUID());
                } else {
                  if (!frozen.equals(packageFingerprint(boot, fs, hostRoot, slot)))
                    throw new IOException("COLD_PACKAGE_CHANGED");
                  boot.extractionStage(
                      progress,
                      com.deepseekharness.app.util.UiText.format("使用已验证方式安装离线工具：%s", mode.name()));
                  boot.hostPorts()
                      .record(
                          "PACKAGE_INSTALL_BEGIN",
                          "runtime="
                              + mode.runtime
                              + " prootNoSeccomp="
                              + mode.noSeccomp
                              + " packageSlotSha256="
                              + frozen);
                  command = com.deepseekharness.app.util.ColdInstallPlan.installCommand(guest);
                }
                try {
                  var result = collectColdCommand(boot, mode, command, probe ? 45_000 : 180_000);
                  return new com.deepseekharness.app.util.ColdInstallPlan.Observation(
                      result.exitCode,
                      result.timedOut,
                      true,
                      result.output + "\n" + result.tail,
                      result.diagnostic());
                } catch (IOException failed) {
                  // collectColdCommand throws on unknown exit before control can reach this catch.
                  return new com.deepseekharness.app.util.ColdInstallPlan.Observation(
                      125, false, true, "", SensitiveData.redact(String.valueOf(failed)));
                }
              },
              (mode, probe, result) ->
                  boot.hostPorts()
                      .record(
                          (probe ? "RUNTIME_PROBE_" : "PACKAGE_INSTALL_") + mode.name(),
                          result.diagnostic));
      return new ProotBootstrap.ColdRuntimeSelection(selected, frozen);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IOException(com.deepseekharness.app.util.UiText.text("离线基础工具安装被中断"), error);
    } catch (IOException failed) {
      throw new IOException(
          com.deepseekharness.app.util.UiText.format(
              "离线工具尚未安装完成；已保留解压文件、安装现场和诊断：\n%s",
              SensitiveData.redact(String.valueOf(failed.getMessage()))),
          failed);
    }
  }

  private static String packageFingerprint(
      ProotBootstrap boot,
      com.deepseekharness.app.backup.BackupFileSystem fs,
      com.deepseekharness.app.util.ColdInstallPackages.HostRoot host,
      String slot)
      throws IOException {
    boot.requireColdCandidate();
    return boot.coldCandidate == null
        ? com.deepseekharness.app.util.ColdInstallPackages.fingerprint(fs, host, slot)
        : com.deepseekharness.app.util.ColdInstallPackages.fingerprint(
            fs, host, boot.coldCandidate, slot);
  }

  private static com.deepseekharness.app.util.BoundedProcessRunner.Result collectColdCommand(
      ProotBootstrap boot,
      com.deepseekharness.app.util.ColdInstallPlan.Mode mode,
      String command,
      long timeout)
      throws IOException, InterruptedException {
    try (RuntimeHostPorts.Scope scope = boot.hostPorts().open()) {
      com.deepseekharness.app.util.RuntimeWorkPort.Work work =
          com.deepseekharness.app.util.RuntimeWorkPort.begin("容器命令");
      Process process = null;
      BoundedGuestSessions.Operation coldRecord = null;
      try {
        ContainerRuntime rt =
            mode == com.deepseekharness.app.util.ColdInstallPlan.Mode.PROROOT
                ? new ContainerRuntime.Proroot(
                    boot.ctx, ContainerRuntime.Proroot.defaultDir(boot.ctx))
                : new ContainerRuntime.Proot(boot.ctx, boot.findNativeLib("libproot.so"));
        boolean isolated = IsolatedInstallProcess.supported(boot.ctx);
        coldRecord = isolated ? BoundedGuestSessions.begin(boot.ctx.getFilesDir()) : null;
        if (boot.coldCandidate != null)
          boot.coldCandidate.beforeLaunch(coldRecord == null ? null : coldRecord.id());
        process =
            boot.startRootfs(
                command,
                boot.launch(rt)
                    .isolated(isolated)
                    .record(coldRecord)
                    .coldTrace(true)
                    .coldMode(mode)
                    .build());
        return com.deepseekharness.app.util.BoundedProcessRunner.collect(
            process, timeout, 256 * 1024, Compat::destroy);
      } finally {
        if (process == null && coldRecord != null) coldRecord.uncertain();
        try {
          if (process instanceof IsolatedInstallProcess) ((IsolatedInstallProcess) process).close();
        } finally {
          if (process != null && !com.deepseekharness.app.util.ProcessTermination.exited(process)) {
            work.retainUntilExit(process);
            throw new IllegalStateException("COLD_INSTALL_PROCESS_EXIT_UNCONFIRMED");
          } else work.close();
        }
      }
    }
  }
}
