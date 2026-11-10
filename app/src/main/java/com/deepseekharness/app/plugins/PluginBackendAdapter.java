package com.deepseekharness.app.plugins;

import android.content.Context;
import android.os.Looper;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.UserDataLayout;
import com.deepseekharness.app.core.MaintenanceCoordinator;
import com.deepseekharness.app.plugins.PluginChangeDetector.Kind;
import com.deepseekharness.app.plugins.PluginChangeDetector.WatchPath;
import com.deepseekharness.app.util.BuiltinPlugins;
import com.deepseekharness.app.util.ColdInstallPackages;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.GuestPaths;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** 原生只读来源适配；构造不读盘，解析只在后台，不准备环境或启动 Web。 */
public final class PluginBackendAdapter {
  private static final int MARKER_LIMIT = 2 * 1024 * 1024;
  // RuntimeInstallationState/ManagedRuntimeAssets 的安装记录位于 baseDir，即 rootfs 的父目录。
  private static final String[] BASE_MARKERS = {
    ".offline-extracted", ".offline-version", ".offline-identity", ".runtime-descriptor.json"
  };
  private static final String[] ROOT_MARKERS = {
    "usr/local/share/dsha/dsh-runtime.version",
    "usr/local/share/dsha/managed-assets-v2",
    "root/.dsh/script-version",
    "root/.dsh/builtin-plugins.json",
    "root/.dsh/managed-package-proofs.json"
  };

  public static final class ResolvedSource {
    public final PluginSourceIdentity identity;
    public final List<WatchPath> observedPaths;

    private ResolvedSource(PluginSourceIdentity identity, List<WatchPath> observedPaths) {
      this.identity = identity;
      this.observedPaths = Collections.unmodifiableList(new ArrayList<>(observedPaths));
    }
  }

  private final BackupFileSystem fs;
  private final File frameworkDataDir, filesDir, rootfs;
  private final LongSupplier webGeneration;
  private final boolean nativeBackend;

  public PluginBackendAdapter(Context context, LongSupplier webGeneration) {
    this(context, GuestPaths.rootfs(context.getApplicationContext().getFilesDir()), webGeneration);
  }

  /** rootfs 来自实际运行后端的只读路径，不创建或访问 ProotBootstrap。 */
  public PluginBackendAdapter(Context context, File rootfs, LongSupplier webGeneration) {
    this(
        new AndroidBackupFileSystem(),
        new File(context.getApplicationContext().getApplicationInfo().dataDir),
        context.getApplicationContext().getFilesDir(),
        rootfs,
        webGeneration,
        true);
  }

  /** 可注入已经可信的宿主根；不规范化其内部链接，调用方负责安排后台执行。 */
  public PluginBackendAdapter(
      BackupFileSystem fs, File filesDir, File rootfs, LongSupplier webGeneration) {
    this(fs, null, filesDir, rootfs, webGeneration, false);
  }

  /** 测试沿生产的 framework authority 绑定链验证实际文件，不运行 Android 门禁。 */
  PluginBackendAdapter(
      BackupFileSystem fs,
      File frameworkDataDir,
      File filesDir,
      File rootfs,
      LongSupplier webGeneration) {
    this(fs, frameworkDataDir, filesDir, rootfs, webGeneration, false);
  }

  private PluginBackendAdapter(
      BackupFileSystem fs,
      File frameworkDataDir,
      File filesDir,
      File rootfs,
      LongSupplier webGeneration,
      boolean nativeBackend) {
    this.fs = Objects.requireNonNull(fs);
    this.frameworkDataDir = frameworkDataDir;
    this.filesDir = Objects.requireNonNull(filesDir).getAbsoluteFile();
    this.rootfs = Objects.requireNonNull(rootfs).getAbsoluteFile();
    this.webGeneration = Objects.requireNonNull(webGeneration);
    this.nativeBackend = nativeBackend;
  }

  /** 小型门禁同样只在后台检查；已有 Web 运行不构成占用。 */
  public String environmentBlockMessage() throws IOException {
    requireBackground();
    ColdInstallPackages.HostRoot host = bindHost();
    String message = environmentBlockMessage(host == null ? filesDir : host.files);
    if (host != null) host.verify(fs);
    return message;
  }

  private String environmentBlockMessage(File files) {
    if (!nativeBackend) return "";
    if (MaintenanceCoordinator.pending(files)) return "环境维护尚未完成，请先恢复维护，再刷新或操作插件";
    if (MaintenanceCoordinator.isExclusive() && !MaintenanceCoordinator.isOwner()
        || EnvironmentTaskGate.isBusy() && !EnvironmentTaskGate.ownsCurrentThread())
      return "已有环境任务启动，请稍后刷新或重试插件操作";
    return "";
  }

  private ColdInstallPackages.HostRoot bindHost() throws IOException {
    return frameworkDataDir == null
        ? null
        : ColdInstallPackages.bindPrivateFiles(fs, frameworkDataDir, filesDir);
  }

  /** 只读取根身份和小型运行时标记正文；其摘要不是包内容校验证明。 两次正文/根/选择记录/Web 代次核对之间发生换代时拒绝结果，不自动重试。 */
  public ResolvedSource resolveSource() throws IOException {
    requireBackground();
    // 只规范化 framework dataDir 的平台别名；可写 files 自身必须经过 NOFOLLOW 与 inode 绑定。
    ColdInstallPackages.HostRoot host = bindHost();
    File files = host == null ? filesDir : host.files;
    BackupFileSystem.Node filesNode = requireDirectory(files);
    String blocked = environmentBlockMessage(files);
    if (!blocked.isEmpty()) throw new IOException(blocked);
    String relativeRoot = relative(filesDir, rootfs);
    File root = new File(files, relativeRoot);
    checkedPath(files, root);
    BackupFileSystem.Node rootNode = requireDirectory(root);
    long generation = webGeneration.getAsLong();
    List<File> markers = markerPaths(files, root);
    String markerDigest = markerDigest(files, markers);
    UserDataLayout layout = new UserDataLayout(fs, files);
    File data = layout.current();
    checkedPath(files, data);
    BackupFileSystem.Node dataNode = requireDirectory(data);
    List<WatchPath> watches = observedPaths(files, root, data, markers);
    if (!markerDigest.equals(markerDigest(files, markers))
        || !data.equals(layout.current())
        || !sameIdentity(filesNode, fs.stat(files))
        || !sameIdentity(rootNode, fs.stat(root))
        || !sameIdentity(dataNode, fs.stat(data))
        || generation != webGeneration.getAsLong()) throw new IOException("PLUGIN_SOURCE_CHANGED");
    if (host != null) host.verify(fs);
    return new ResolvedSource(
        new PluginSourceIdentity(
            data,
            dataNode.key,
            dataNode.device,
            root,
            rootNode.key,
            rootNode.device,
            markerDigest,
            generation),
        watches);
  }

  private void requireBackground() throws IOException {
    if (nativeBackend) {
      Looper main = Looper.getMainLooper();
      if (main != null && Looper.myLooper() == main)
        throw new IOException("PLUGIN_SOURCE_REQUIRES_BACKGROUND");
    }
    if (Thread.currentThread().isInterrupted()) throw new IOException("PLUGIN_SOURCE_INTERRUPTED");
  }

  private List<File> markerPaths(File files, File root) {
    List<File> result = new ArrayList<>();
    result.add(new File(files, UserDataLayout.RECORD));
    result.add(new File(files, UserDataLayout.RECORD + ".previous"));
    for (String name : BASE_MARKERS) result.add(new File(root.getParentFile(), name));
    for (String name : ROOT_MARKERS) result.add(new File(root, name));
    return result;
  }

  private String markerDigest(File files, List<File> markers) throws IOException {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException unavailable) {
      throw new IOException("SHA256_UNAVAILABLE", unavailable);
    }
    for (File marker : markers) {
      requireBackground();
      BackupFileSystem.Node node = checkedPath(files, marker);
      byte[] value;
      if (node.type.equals("MISSING")) value = new byte[0];
      else if (node.type.equals("FILE")) value = fs.small(marker, MARKER_LIMIT);
      else throw new IOException("PLUGIN_SOURCE_MARKER_TYPE");
      digestPart(digest, relative(files, marker).getBytes(StandardCharsets.UTF_8));
      digestPart(digest, node.type.getBytes(StandardCharsets.US_ASCII));
      digestPart(digest, value);
    }
    StringBuilder hex = new StringBuilder(64);
    for (byte value : digest.digest()) {
      hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
      hex.append(Character.forDigit(value & 15, 16));
    }
    return hex.toString();
  }

  private static void digestPart(MessageDigest digest, byte[] value) {
    int length = value.length;
    digest.update((byte) (length >>> 24));
    digest.update((byte) (length >>> 16));
    digest.update((byte) (length >>> 8));
    digest.update((byte) length);
    digest.update(value);
  }

  private List<WatchPath> observedPaths(File files, File root, File data, List<File> markers)
      throws IOException {
    List<WatchPath> result = new ArrayList<>();
    addWatch(result, files, root, Kind.SOURCE_IDENTITY, false);
    addWatch(result, files, data, Kind.SOURCE_IDENTITY, false);
    for (File marker : markers) addWatch(result, files, marker, Kind.SOURCE_IDENTITY, false);
    for (String directory :
        new String[] {
          "plugin-src",
          "node_modules",
          "profiles",
          "plugin-previews",
          "plugin-history",
          "plugin-dependency-locks",
          "plugin-install-operations"
        }) addWatch(result, data, new File(data, directory), Kind.CONTENT, true);
    for (String file :
        new String[] {
          "plugin-sources.json",
          "plugin-updates.json",
          "plugin-activations.json",
          "plugin-safe-mode.json"
        }) addWatch(result, data, new File(data, file), Kind.CONTENT, false);
    addWatch(
        result,
        files,
        new File(data.getParentFile(), ".dsha-restore-journal.json"),
        Kind.CONTENT,
        false);
    addWatch(result, root, new File(root, "root/node_modules"), Kind.CONTENT, true);
    addWatch(result, root, new File(root, "usr/local/lib/node_modules"), Kind.CONTENT, true);
    addWatch(result, root, new File(root, "root/dsha-builtin.txt"), Kind.CONTENT, false);
    for (String name : BuiltinPlugins.SIGNED_BUILTINS) {
      String guest = BuiltinPlugins.entityDir(name);
      if (!guest.startsWith("/root/")) throw new IOException("PLUGIN_BUILTIN_PATH");
      addWatch(result, root, new File(root, guest.substring(1)), Kind.CONTENT, true);
    }
    addWatch(
        result,
        root,
        new File(root, "root/.dsha-web-activity.json"),
        Kind.RUNTIME_PROJECTION,
        false);
    return result;
  }

  private void addWatch(
      List<WatchPath> result, File trustedRoot, File path, Kind kind, boolean recursive)
      throws IOException {
    checkedPath(trustedRoot, path);
    result.add(new WatchPath(trustedRoot, path, kind, recursive));
  }

  /** 从可信根逐段 lstat；缺失的父链仅作为将来的观察目标，绝不创建或跟随链接。 */
  private BackupFileSystem.Node checkedPath(File trustedRoot, File path) throws IOException {
    String suffix = relative(trustedRoot, path);
    BackupFileSystem.Node node = requireDirectory(trustedRoot);
    if (suffix.isEmpty()) return node;
    File at = trustedRoot;
    String[] parts = suffix.split("/");
    for (int i = 0; i < parts.length; i++) {
      at = new File(at, parts[i]);
      node = fs.stat(at);
      if (node.type.equals("MISSING")) return node;
      if (node.type.equals("LINK") || node.type.equals("SPECIAL"))
        throw new IOException("PLUGIN_SOURCE_UNSAFE_PATH");
      if (i < parts.length - 1 && !node.type.equals("DIRECTORY"))
        throw new IOException("PLUGIN_SOURCE_PARENT_TYPE");
    }
    return node;
  }

  private BackupFileSystem.Node requireDirectory(File path) throws IOException {
    BackupFileSystem.Node node = fs.stat(path);
    if (!node.type.equals("DIRECTORY") || node.key == null || node.key.isEmpty())
      throw new IOException("PLUGIN_SOURCE_ROOT_UNAVAILABLE");
    return node;
  }

  private static boolean sameIdentity(BackupFileSystem.Node before, BackupFileSystem.Node after) {
    return before.type.equals(after.type)
        && before.key.equals(after.key)
        && before.device == after.device;
  }

  private static String relative(File root, File child) throws IOException {
    String base = root.getAbsolutePath(), path = child.getAbsolutePath();
    if (path.equals(base)) return "";
    if (!path.startsWith(base + File.separator))
      throw new IOException("PLUGIN_SOURCE_OUTSIDE_ROOT");
    String relative = path.substring(base.length() + 1).replace(File.separatorChar, '/');
    for (String part : relative.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new IOException("PLUGIN_SOURCE_UNSAFE_PATH");
    return relative;
  }
}
