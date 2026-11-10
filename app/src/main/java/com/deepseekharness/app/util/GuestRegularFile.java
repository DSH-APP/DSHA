package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按 guest 语义判断 Ubuntu 运行根内的运行文件是否为普通文件。
 *
 * <p>apt/dpkg（无论由容器内 proot 模拟的 root 还是经外部 su 授权的 root 执行）会把 soname 等文件换成符号链接，
 * 例如 {@code libsqlite3.so.0 -> libsqlite3.so.0.8.6}；proot {@code --link2symlink} 也会把硬链接改成指向 {@code
 * .l2s} 的宿主绝对路径链接，外部 root 还可能写入宿主绝对路径。这里只在 rootfs 边界内逐级跟随：相对链接按所在目录解析，
 * guest 绝对链接从 rootfs 根解析，宿主镜像路径只映射回本次 rootfs；宿主绑定目录（/system、/proc 等）不跟随，
 * {@code ..} 不能越过 guest 根。只做 lstat/readlink，不读取内容、不写入、不修复；无权访问时由文件系统抛出失败码。
 */
public final class GuestRegularFile {
  /** 与 Linux 单次路径解析的符号链接上限同量级；超过视为循环。 */
  public static final int MAX_LINKS = 40;

  private static final int MAX_DEPTH = 64;
  private static final int MAX_TARGET = 4096;
  // 与 ContainerRuntime.binds 及宿主 /data 一致：这些 guest 路径不在 rootfs 内，运行文件不能借链接指向它们。
  private static final Pattern EXTERNAL =
      Pattern.compile("^/(?:proc|sys|dev|system|apex|sdcard|storage|data)(?:/|$)");

  /** 宿主绝对路径到本次 rootfs 相对路径的映射；不属于本次 rootfs 时返回 null。 */
  public interface HostMirror {
    String relative(String absoluteTarget);
  }

  public static final class Result {
    /** 解析后的宿主文件；失败时为 null。 */
    public final File file;

    /** 解析后普通文件的 lstat 结果（权限位取自目标文件而非链接）；失败时为 null。 */
    public final BackupFileSystem.Node node;

    /** null 表示是普通文件；否则为不含文件内容的失败码，供诊断定位。 */
    public final String failure;

    public final int links;

    private Result(File file, BackupFileSystem.Node node, String failure, int links) {
      this.file = file;
      this.node = node;
      this.failure = failure;
      this.links = links;
    }

    public boolean regular() {
      return failure == null;
    }
  }

  private GuestRegularFile() {}

  /**
   * 父目录与末级链接统一逐级解析：不让宿主 lstat 跟随父链接把 rootfs 外的宿主文件当成运行文件，
   * 也不因末级是 apt/外部 root 写入的有效 soname 链接而误判未就绪。
   */
  public static Result check(BackupFileSystem fs, File rootfs, String relative, HostMirror mirror)
      throws IOException {
    return resolve(fs, rootfs, relative, mirror);
  }

  public static Result resolve(BackupFileSystem fs, File rootfs, String relative)
      throws IOException {
    return resolve(fs, rootfs, relative, null);
  }

  public static Result resolve(BackupFileSystem fs, File rootfs, String relative, HostMirror mirror)
      throws IOException {
    ArrayDeque<String> pending = requested(relative);
    File root = rootfs.getAbsoluteFile();
    String rootPath = root.getPath();
    List<String> current = new ArrayList<>();
    int links = 0;
    while (!pending.isEmpty()) {
      String name = pending.removeFirst();
      if (name.equals("..")) {
        // guest 根的 .. 仍是根；只回退已确认的真实目录，不能借链接走出 rootfs。
        if (!current.isEmpty()) current.remove(current.size() - 1);
        continue;
      }
      File next = file(root, current, name);
      BackupFileSystem.Node node = fs.stat(next);
      if (node.type.equals("LINK")) {
        if (++links > MAX_LINKS) return failed("LINK_LOOP", links);
        String target = fs.readLink(next);
        if (target == null
            || target.isEmpty()
            || target.length() > MAX_TARGET
            || target.indexOf('\0') >= 0) return failed("LINK_FORMAT", links);
        String path = target;
        if (target.startsWith("/")) {
          String mirrored = mirrored(rootPath, target, mirror);
          if (mirrored == null) {
            if (EXTERNAL.matcher(target).find()) return failed("LINK_EXTERNAL_BINDING", links);
            mirrored = target;
          }
          current.clear();
          path = mirrored;
        }
        ArrayDeque<String> replacement = parts(path);
        replacement.addAll(pending);
        pending = replacement;
        continue;
      }
      if (node.type.equals("MISSING")) return failed("MISSING", links);
      if (!pending.isEmpty() && !node.type.equals("DIRECTORY"))
        return failed("PARENT_" + node.type, links);
      current.add(name);
      if (current.size() > MAX_DEPTH) return failed("DEPTH", links);
    }
    if (current.isEmpty()) return failed("DIRECTORY", links);
    File resolved = file(root, current, null);
    // 末级以解析后的真实路径重新 lstat：期间被换成链接或其它类型都按未就绪处理。
    BackupFileSystem.Node node = fs.stat(resolved);
    if (!node.type.equals("FILE")) return failed(node.type, links);
    return new Result(resolved, node, null, links);
  }

  private static Result failed(String failure, int links) {
    return new Result(null, null, failure, links);
  }

  private static String mirrored(String rootPath, String target, HostMirror mirror) {
    if (target.equals(rootPath)) return "";
    if (target.startsWith(rootPath + "/")) return target.substring(rootPath.length() + 1);
    return mirror == null ? null : mirror.relative(target);
  }

  private static ArrayDeque<String> requested(String relative) throws IOException {
    if (relative == null
        || relative.isEmpty()
        || relative.startsWith("/")
        || relative.indexOf('\\') >= 0
        || relative.indexOf('\0') >= 0) throw new IOException("GUEST_FILE_PATH");
    ArrayDeque<String> parts = new ArrayDeque<>();
    for (String part : relative.split("/", -1)) {
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw new IOException("GUEST_FILE_PATH");
      parts.add(part);
    }
    return parts;
  }

  private static ArrayDeque<String> parts(String path) {
    ArrayDeque<String> parts = new ArrayDeque<>();
    for (String part : path.split("/")) if (!part.isEmpty() && !part.equals(".")) parts.add(part);
    return parts;
  }

  private static File file(File root, List<String> parts, String last) {
    File result = root;
    for (String part : parts) result = new File(result, part);
    return last == null ? result : new File(result, last);
  }
}
