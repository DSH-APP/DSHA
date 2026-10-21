package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.BundledRootfsAsset;
import com.deepseekharness.app.util.ColdInstallPackages;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.Ids;
import com.deepseekharness.app.util.MaintenanceGate;
import com.deepseekharness.app.util.RootfsBootstrap;
import com.deepseekharness.app.util.RuntimeWorkPort;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipFile;

/** 从签名 APK 提取到独立根的过渡数据维护工具。 */
final class RecoveryToolchain {
  private final Context context;
  private final File rootfs, lib, tmp;
  private final RuntimeNativeFiles nativeFiles;
  private final RuntimeLauncher launcher;
  private final RuntimeHostPorts ports;
  private File tools;

  RecoveryToolchain(
      Context context,
      File rootfs,
      File base,
      File lib,
      File tmp,
      RuntimeNativeFiles nativeFiles,
      RuntimeLauncher launcher,
      RuntimeHostPorts ports) {
    this.context = context;
    this.rootfs = rootfs;
    this.lib = lib;
    this.tmp = tmp;
    this.nativeFiles = nativeFiles;
    this.launcher = launcher;
    this.ports = ports;
  }

  void prepareData() throws IOException {
    requireOwner("数据维护必须持有停止屏障");
    File actual = RuntimeAssetFiles.root(context, rootfs);
    var fs = new AndroidBackupFileSystem();
    File home = fs.child(actual, "root");
    if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
    if (!fs.stat(home).type.equals("DIRECTORY"))
      throw new IOException(UiText.text("个人数据目录无效，已停止操作"));
  }

  private synchronized File prepareRoot() throws IOException {
    if (tools != null && RootfsReadiness.ready(new File(tools, "linux/ubuntu")))
      return new File(tools, "linux/ubuntu");
    var fs = new AndroidBackupFileSystem();
    var host =
        ColdInstallPackages.bindPrivateFiles(
            fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
    String relative = "recovery-maintenance-tools/" + java.util.UUID.randomUUID();
    fs.parents(host.files, relative + "/linux/ubuntu/root/.directory-probe");
    tools = fs.child(host.files, relative);
    File root = fs.child(tools, "linux/ubuntu");
    try {
      try (ZipFile apk = new ZipFile(context.getPackageCodePath())) {
        var entry = BundledRootfsAsset.find(apk);
        if (entry == null) throw new IOException(UiText.text("APK 缺少独立恢复所需的基础文件"));
        try (InputStream input = apk.getInputStream(entry)) {
          TarGzipExtractor.extractSelected(input, root, 0, RootfsBootstrap::recoveryAsset);
        }
      }
      RootfsInstaller.python(context, root);
      if (!RootfsReadiness.ready(root) || !new File(root, "usr/bin/python3").isFile())
        throw new IOException(UiText.text("独立恢复工具校验失败"));
      host.verify(fs);
      return root;
    } catch (IOException | RuntimeException failure) {
      try {
        fs.removeOwned(tools.getParentFile(), tools.getName());
        tools = null;
      } catch (IOException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  synchronized void release() {
    if (tools == null || RuntimeWorkPort.hasOtherTasks()) return;
    try {
      var fs = new AndroidBackupFileSystem();
      var host =
          ColdInstallPackages.bindPrivateFiles(
              fs, new File(context.getApplicationInfo().dataDir), context.getFilesDir());
      File parent = fs.child(host.files, "recovery-maintenance-tools");
      if (!parent.equals(tools.getParentFile()) || !Ids.uuid(tools.getName()))
        throw new IOException("RECOVERY_TOOLS_DIRECTORY");
      fs.removeOwned(parent, tools.getName());
      tools = null;
      host.verify(fs);
    } catch (IOException failure) {
      ports.record("RECOVERY_TOOLS_RETAINED", failure.getClass().getSimpleName());
    }
  }

  BoundedProcessRunner.Result run(String command, Consumer<String> onLine, long timeoutMs)
      throws IOException, InterruptedException {
    requireOwner("独立恢复必须持有维护停止屏障");
    RuntimeWorkPort.Work work = RuntimeWorkPort.begin("容器命令");
    Process process = null;
    try (RuntimeHostPorts.Scope ignored = ports.open()) {
      File root = prepareRoot();
      nativeFiles.prepare();
      File source = RuntimeAssetFiles.root(context, rootfs);
      var fs = new AndroidBackupFileSystem();
      File home = fs.child(source, "root");
      if (fs.stat(home).type.equals("MISSING")) fs.directory(home);
      if (!fs.stat(home).type.equals("DIRECTORY"))
        throw new IOException(UiText.text("个人数据目录无效，已停止操作"));
      ContainerRuntime runtime =
          new ContainerRuntime.Proot(context, nativeFiles.find("libproot.so"));
      List<String> argv = runtime.baseArgv(root, false, null);
      launcher.bindPreloads(argv);
      // 源数据单独绑定；Bash、glibc、Python 始终来自签名工具根。
      for (String bind :
          new String[] {
            source.getAbsolutePath() + ":/run/dsha-maintenance-root",
            source.getAbsolutePath() + ":" + source.getAbsolutePath(),
            home.getAbsolutePath() + ":/root"
          }) {
        argv.add("-b");
        argv.add(bind);
      }
      File local = fs.child(source, "usr/local");
      if (fs.stat(local).type.equals("DIRECTORY")) {
        argv.add("-b");
        argv.add(local.getAbsolutePath() + ":/usr/local");
      }
      argv.add("/bin/bash");
      argv.add("-c");
      argv.add(command);
      ProcessBuilder builder = new ProcessBuilder(argv).redirectErrorStream(true);
      Compat.redirectStdinDevNull(builder);
      launcher.environment(builder, LaunchSpec.runtime(runtime).hardlinks(true).build());
      builder.environment().put("PROOT_L2S_DIR", new File(root, ".l2s").getAbsolutePath());
      builder.environment().put("PATH", "/usr/bin:/bin");
      process = builder.start();
      return BoundedProcessRunner.collect(process, timeoutMs, 256 * 1024, Compat::destroy, onLine);
    } finally {
      if (process != null && !com.deepseekharness.app.util.ProcessTermination.exited(process))
        work.retainUntilExit(process);
      else work.close();
    }
  }

  private static void requireOwner(String message) throws IOException {
    if (!MaintenanceGate.shared().isOwner()) throw new IOException(UiText.text(message));
  }
}
