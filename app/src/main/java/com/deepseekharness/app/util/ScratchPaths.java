package com.deepseekharness.app.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/**
 * DSHA 临时文件的唯一约定：guest 侧 {@code /root/.cache/dsha}。
 *
 * <p>AI 与随包脚本把临时/中间产物写在这里；应用内「清理可再生缓存」只清空这个目录，以及本 App
 * 自己命名的 guest 临时文件（{@code /tmp/dsha-tool-script.*}，见 {@code runtime/GuestScripts}）。
 *
 * <p>这里是闭集：{@link #DIRECTORY_WHITELIST} 与 {@link #OWNED_FILE_WHITELIST} 只接受逐条评审过
 * 的字面量，不做前缀/通配泛化，也不接受调用方计算出来的路径。删除前必须经 {@link #resolve} 或
 * {@link #verifyOwnedFile} 解析绝对路径并逐级 lstat 核验父链，避免链接把删除带出边界。
 *
 * <p>提示词侧同一条约定写在 {@code dsh-device-shell-guide} 插件里（该插件同时负责建目录）；
 * 仓库的 AGENTS.md 讲的是开发约定，两者不重复。改动本类时必须同步插件里的路径字面量。
 */
public final class ScratchPaths {
  private ScratchPaths() {}

  /** guest 绝对路径；插件提示词、终端环境变量（DSHA_CACHE_DIR）与 AI 共用这一个约定。 */
  public static final String GUEST_DIRECTORY = "/root/.cache/dsha";

  /** rootfs 相对路径；与 {@link RegenerableCachePaths} 的清理坐标系一致。 */
  public static final String ROOTFS_DIRECTORY = "root/.cache/dsha";

  /** 可整体清空的声明目录；每条都要能被完整重建，且不承载用户数据。 */
  public static final List<String> DIRECTORY_WHITELIST = List.of(ROOTFS_DIRECTORY);

  /** guest 临时文件目录（rootfs 相对）。 */
  static final String GUEST_TMP = "tmp";

  /** 本 App 独占的临时文件名前缀；只有本 App 会写这个名字。 */
  static final String OWNED_TMP_PREFIX = "dsha-tool-script.";

  /** 允许删除的「声明目录 + 本 App 命名前缀」条目：只删该目录下的直接子文件，不递归。 */
  public static final List<String> OWNED_FILE_WHITELIST =
      List.of(GUEST_TMP + "/" + OWNED_TMP_PREFIX);

  /** 是否是可整体清空的声明目录。 */
  public static boolean declaredDirectory(String relative) {
    return DIRECTORY_WHITELIST.contains(relative);
  }

  /** 是否是承载本 App 命名临时文件的声明目录（只删这里面的直接子文件，不递归）。 */
  public static boolean declaredOwnedDirectory(String directory) {
    return directory != null && OWNED_FILE_WHITELIST.contains(directory + "/" + OWNED_TMP_PREFIX);
  }

  /** 是否是本 App 自己命名的 guest 临时文件（单层文件名，不含分隔符）。 */
  public static boolean ownedTemporary(String name) {
    return name != null
        && name.startsWith(OWNED_TMP_PREFIX)
        && name.length() > OWNED_TMP_PREFIX.length()
        && name.indexOf('/') < 0
        && name.indexOf('\\') < 0;
  }

  /**
   * 解析声明目录的绝对路径。只接受白名单里的字面量，逐级 lstat 核验父链（链接、非目录、缺失一律拒绝），
   * 最后确认解析后的父目录仍落在已核验的 root 内。目标本身若是链接，调用方只会删除链接本身。
   *
   * @return 已核验的绝对路径；父链不存在时抛 {@link IOException}，由调用方跳过并记录。
   */
  public static File resolve(File root, String relative) throws IOException {
    if (root == null) throw new IOException("SCRATCH_ROOT_MISSING");
    if (!declaredDirectory(relative)) throw new IOException("SCRATCH_PATH_UNDECLARED");
    File verified = root.getCanonicalFile();
    File at = verified;
    String[] parts = relative.split("/");
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new IOException("SCRATCH_PATH_UNSAFE");
      File next = new File(at, part);
      if (i < parts.length - 1) {
        if (Files.isSymbolicLink(next.toPath())) throw new IOException("SCRATCH_PARENT_LINK");
        if (!next.isDirectory()) throw new IOException("SCRATCH_PARENT_TYPE");
      } else if (Files.isSymbolicLink(next.toPath())) {
        // 声明目录本身是链接时整条跳过：不删链接，更不动链接目标。
        throw new IOException("SCRATCH_PATH_LINK");
      }
      at = next;
    }
    if (!inside(verified, at.getParentFile())) throw new IOException("SCRATCH_PATH_ESCAPE");
    return at;
  }

  /**
   * 核验承载本 App 临时文件的声明目录本身（lstat 必须是真实目录，链接或缺失一律拒绝），
   * 让调用方在列目录之前就拒绝跟随链接。
   */
  public static File verifyOwnedDirectory(File root, String directory) throws IOException {
    if (root == null) throw new IOException("SCRATCH_ROOT_MISSING");
    if (!declaredOwnedDirectory(directory)) throw new IOException("SCRATCH_PATH_UNDECLARED");
    return resolveDirectory(root.getCanonicalFile(), directory);
  }

  /**
   * 解析并核验本 App 自己命名的临时文件。只接受 {@link #OWNED_FILE_WHITELIST} 里声明的目录与前缀组合，
   * 且必须是被核验 root 的直接子文件路径。
   */
  public static File verifyOwnedFile(File root, String relative) throws IOException {
    if (root == null) throw new IOException("SCRATCH_ROOT_MISSING");
    int slash = relative == null ? -1 : relative.indexOf('/');
    boolean accepted = false;
    if (slash > 0) {
      String directory = relative.substring(0, slash);
      String name = relative.substring(slash + 1);
      accepted = declaredOwnedDirectory(directory) && ownedTemporary(name);
    }
    if (!accepted) throw new IOException("SCRATCH_PATH_UNDECLARED");
    File verified = root.getCanonicalFile();
    File directory = verifyOwnedDirectory(verified, relative.substring(0, slash));
    File target = new File(directory, relative.substring(slash + 1));
    if (!inside(verified, target.getParentFile())) throw new IOException("SCRATCH_PATH_ESCAPE");
    return target;
  }

  /** 单层目录（例如 guest /tmp）：lstat 必须是真实目录，链接或缺失一律拒绝。 */
  private static File resolveDirectory(File root, String name) throws IOException {
    if (name.isEmpty() || name.indexOf('/') >= 0 || name.equals(".") || name.equals(".."))
      throw new IOException("SCRATCH_PATH_UNSAFE");
    File directory = new File(root, name);
    if (Files.isSymbolicLink(directory.toPath())) throw new IOException("SCRATCH_PARENT_LINK");
    if (!directory.isDirectory()) throw new IOException("SCRATCH_PARENT_TYPE");
    return directory;
  }

  /** 解析后的路径必须真的落在已核验的 root 里。 */
  private static boolean inside(File root, File candidate) throws IOException {
    if (candidate == null) return false;
    File resolved = candidate.getCanonicalFile();
    return resolved.equals(root) || resolved.getPath().startsWith(root.getPath() + File.separator);
  }
}
