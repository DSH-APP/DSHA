package com.deepseekharness.app.saf;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 容器内路径的「固定身份」读写。
 *
 * <p>{@code /app/saf/pull}、{@code /app/saf/push} 的容器路径先经 canonical 与政策判据，
 * 但「判完再开」之间容器里的进程可以把路径或祖先目录换成软链接（TOCTOU）。这里在打开前后
 * 比对 {@code lstat(path)} 与 {@code fstat(fd)} 的 dev/ino，并用
 * {@code readlink /proc/self/fd/<fd>} 核对内核记录的真实路径：祖先目录被换链时真实路径
 * 必然不同，直接拒绝（失败关闭）。
 *
 * <p>目录操作走逐级 {@code O_PATH|O_NOFOLLOW} 锚定：从 {@code /} 开始，每级用
 * {@code /proc/self/fd/<父 fd>/<名字>} 打开并核对，最后通过同一个目录 fd 创建/发布文件，
 * 不再用可被祖先替换重新解释的路径字符串做 {@code rename}。
 *
 * <p><b>描述符生命周期</b>：{@code /proc/self/fd/<n>} 里的 {@code n} 必须是仍然打开的编号。
 * 任何从 {@link FileDescriptor} 取编号的地方都不能「dup 后立刻 close 再返回编号」——
 * {@link AnchoredDirectory} 因此持有 dup 后的 {@link ParcelFileDescriptor}，在 {@link
 * AnchoredDirectory#close()} 时关闭；单次 readlink 校验则在 handle 仍打开时完成。
 *
 * <p>{@code O_NONBLOCK} 是必须的：目标可能在 lstat 与 open 之间被换成 FIFO；确认是普通文件后
 * 再清掉该标志。还拒绝 {@code st_nlink > 1} 的硬链接与 proot {@code --link2symlink} 的
 * {@code .l2s} 实体。
 */
public final class SafLocalFiles {
  /** proot --link2symlink 把「硬链接」落成指向 .l2s 目录实体的软链接。 */
  private static final String LINK2SYMLINK = ".l2s";

  /** Linux UAPI O_PATH：只持有目录，不要求列目录权限。 */
  private static final int O_PATH = 0x200000;

  /** 调用方对打开后真实路径的额外复核（例如重新跑 BridgePathPolicy 的 guest 判据）。 */
  public interface PathCheck {
    boolean denied(String realPath);
  }

  private SafLocalFiles() {}

  /** 只读打开并固定身份；文件必须已存在且是普通文件。返回的流负责关闭底层 fd。 */
  public static java.io.InputStream openRead(File expected) throws IOException {
    return openRead(expected, null);
  }

  /** 只读打开并固定身份；返回的流负责关闭底层 fd。 */
  public static java.io.InputStream openRead(File expected, PathCheck check) throws IOException {
    FileDescriptor fd = pin(expected, OsConstants.O_RDONLY, 0, true, check);
    try {
      return new OwningInputStream(new FileInputStream(fd), fd);
    } catch (RuntimeException error) {
      closeRaw(fd);
      throw error;
    }
  }

  /**
   * 创建写打开并固定身份（0600）；目标可以不存在。
   *
   * <p>校验之前不带 {@code O_TRUNC}：若打开的不是预期文件，不能先把原文件截断；身份、
   * 真实路径都核对通过后才 {@code ftruncate(fd, 0)}。
   */
  public static java.io.OutputStream openWrite(File expected) throws IOException {
    FileDescriptor fd =
        pin(expected, OsConstants.O_WRONLY | OsConstants.O_CREAT, 0600, false, null);
    try {
      truncateAfterVerify(fd);
      return new OwningOutputStream(new FileOutputStream(fd), fd);
    } catch (IOException | RuntimeException error) {
      closeRaw(fd);
      throw error;
    }
  }

  /** 一个已锚定的目录句柄；子项创建/发布/清理都在这个仍打开的 fd 下完成。 */
  public static final class AnchoredDirectory implements AutoCloseable {
    /** 仍打开的目录 fd 编号；只在 {@link #close()} 之前有效。 */
    public final int fd;

    private final String path;
    private final ParcelFileDescriptor handle;

    private AnchoredDirectory(ParcelFileDescriptor handle, String path) {
      this.handle = handle;
      this.fd = handle.getFd();
      this.path = path;
    }

    /** 目录的规范化路径（打开时经 readlink 核对）。 */
    public String path() {
      return path;
    }

    @Override
    public void close() {
      try {
        handle.close();
      } catch (IOException ignored) {
      }
    }
  }

  /** 逐级 O_PATH|O_NOFOLLOW 打开目录并核对最终真实路径。 */
  public static AnchoredDirectory openAnchoredDirectory(File directory) throws IOException {
    return openAnchoredDirectory(directory, null);
  }

  /** 逐级锚定打开目录，并对最终真实路径再跑一次调用方判据。 */
  public static AnchoredDirectory openAnchoredDirectory(File directory, PathCheck check)
      throws IOException {
    FileDescriptor current = walkOpen(directory, false);
    try {
      String realPath = readlinkOf(current);
      String expected = normalized(directory);
      if (!expected.equals(realPath)) throw new IOException("PARENT_CHANGED");
      if (check != null && check.denied(realPath)) throw new IOException("DENIED");
      ParcelFileDescriptor handle = dupOf(current);
      try {
        return new AnchoredDirectory(handle, expected);
      } catch (RuntimeException error) {
        closeQuietly(handle);
        throw error;
      }
    } finally {
      closeRaw(current);
    }
  }

  /** 确保目录存在并返回锚定句柄；缺失的每一级都在父目录 fd 下创建，不按路径 mkdir。 */
  public static AnchoredDirectory ensureAnchoredDirectory(File directory, PathCheck check)
      throws IOException {
    FileDescriptor current = walkOpen(directory, true);
    try {
      String realPath = readlinkOf(current);
      String expected = normalized(directory);
      if (!expected.equals(realPath)) throw new IOException("PARENT_CHANGED");
      if (check != null && check.denied(realPath)) throw new IOException("DENIED");
      ParcelFileDescriptor handle = dupOf(current);
      try {
        return new AnchoredDirectory(handle, expected);
      } catch (RuntimeException error) {
        closeQuietly(handle);
        throw error;
      }
    } finally {
      closeRaw(current);
    }
  }

  /** 在已锚定的目录下创建/截断文件并核对真实路径。 */
  public static java.io.OutputStream createInDirectory(AnchoredDirectory directory, String name)
      throws IOException {
    return createInDirectory(directory, name, null);
  }

  /** 在已锚定的目录下创建/截断文件，并对真实路径再跑一次调用方判据。 */
  public static java.io.OutputStream createInDirectory(
      AnchoredDirectory directory, String name, PathCheck check) throws IOException {
    if (directory == null || !validName(name)) throw new IOException("BAD_PATH");
    String path = "/proc/self/fd/" + directory.fd + "/" + name;
    FileDescriptor fd;
    try {
      fd =
          Os.open(
              path,
              OsConstants.O_WRONLY
                  | OsConstants.O_CREAT
                  | OsConstants.O_NOFOLLOW
                  | OsConstants.O_NONBLOCK
                  | OsConstants.O_CLOEXEC,
              0600);
    } catch (ErrnoException error) {
      throw new IOException(error);
    }
    try {
      StructStat opened = Os.fstat(fd);
      if (!OsConstants.S_ISREG(opened.st_mode)) throw new IOException("NOT_REGULAR");
      if (opened.st_nlink > 1) throw new IOException("HARDLINK");
      String realPath = readlinkOf(fd);
      if (!directory.path.concat("/").concat(name).equals(realPath))
        throw new IOException("PATH_CHANGED");
      if (check != null && check.denied(realPath)) throw new IOException("DENIED");
      truncateAfterVerify(fd);
      clearNonBlock(fd);
      return new OwningOutputStream(new FileOutputStream(fd), fd);
    } catch (IOException | ErrnoException error) {
      closeRaw(fd);
      throw error instanceof IOException ? (IOException) error : new IOException(error);
    }
  }

  /** 在同一个目录 fd 内原子发布；祖先目录即使被换链也不会改变 rename 的真实目标。 */
  public static void publishInDirectory(
      AnchoredDirectory directory, String tempName, String targetName) throws IOException {
    if (directory == null || !validName(tempName) || !validName(targetName))
      throw new IOException("BAD_PATH");
    String base = "/proc/self/fd/" + directory.fd + "/";
    try {
      Os.rename(base + tempName, base + targetName);
    } catch (ErrnoException error) {
      throw new IOException("PUBLISH_FAILED", error);
    }
  }

  /** 在已锚定目录内删除一个名字（用于临时文件清理）。 */
  public static void deleteInDirectory(AnchoredDirectory directory, String name)
      throws IOException {
    if (directory == null || !validName(name)) throw new IOException("BAD_PATH");
    try {
      Os.remove("/proc/self/fd/" + directory.fd + "/" + name);
    } catch (ErrnoException missing) {
      if (missing.errno != OsConstants.ENOENT) throw new IOException(missing);
    }
  }

  /** 目录列举 seam：生产默认读 /proc/self/fd/<fd>，测试可注入受控列表。 */
  interface DirectoryLister {
    String[] list(int fd);
  }

  private static volatile DirectoryLister directoryLister =
      fd -> {
        String[] names = new File("/proc/self/fd/" + fd).list();
        return names == null ? new String[0] : names;
      };

  static void setDirectoryListerForTests(DirectoryLister lister) {
    directoryLister = lister == null ? fd -> new String[0] : lister;
  }

  /** 已锚定目录里的文件名列表；只用于清理本端点自己的临时文件。 */
  public static String[] listInDirectory(AnchoredDirectory directory) {
    if (directory == null) return new String[0];
    return directoryLister.list(directory.fd);
  }

  /** 路径里是否有 proot --link2symlink 的内部实体目录；命中时一律不读写。 */
  public static boolean link2SymlinkPath(String path) {
    if (path == null || path.isEmpty()) return false;
    for (String segment : path.split("/")) if (LINK2SYMLINK.equals(segment)) return true;
    return false;
  }

  /** 尽力删除半截目标文件；失败不掩盖原始错误。 */
  public static void deleteQuietly(File target) {
    if (target == null) return;
    try {
      java.nio.file.Files.deleteIfExists(target.toPath());
    } catch (Throwable ignored) {
    }
  }

  private static FileDescriptor pin(
      File expected, int flags, int mode, boolean mustExist, PathCheck check) throws IOException {
    if (expected == null) throw new IOException("NO_PATH");
    String path = expected.getPath();
    if (link2SymlinkPath(path)) throw new IOException("LINK2SYMLINK");
    StructStat before = lstatOrNull(path);
    if (mustExist && before == null) throw new IOException("NOT_FOUND");
    if (before != null && !OsConstants.S_ISREG(before.st_mode))
      throw new IOException("NOT_REGULAR");
    FileDescriptor fd;
    try {
      fd =
          Os.open(
              path,
              flags | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK | OsConstants.O_CLOEXEC,
              mode);
    } catch (ErrnoException error) {
      throw new IOException(error);
    }
    try {
      StructStat opened = Os.fstat(fd);
      if (!OsConstants.S_ISREG(opened.st_mode)) throw new IOException("NOT_REGULAR");
      if (opened.st_nlink > 1) throw new IOException("HARDLINK");
      StructStat after = lstatOrNull(path);
      if (after == null) throw new IOException("PATH_CHANGED");
      boolean pathStable =
          before != null && before.st_dev == after.st_dev && before.st_ino == after.st_ino;
      boolean openedMatchesPath = opened.st_dev == after.st_dev && opened.st_ino == after.st_ino;
      if (!mustExist && before == null && openedMatchesPath) {
        verifyOpenedPath(fd, path, check);
        clearNonBlock(fd);
        return fd; // 新创建的文件
      }
      if (!pathStable || !openedMatchesPath) throw new IOException("PATH_CHANGED");
      if (before.st_nlink > 1) throw new IOException("HARDLINK");
      verifyOpenedPath(fd, path, check);
      clearNonBlock(fd);
      return fd;
    } catch (IOException | ErrnoException error) {
      closeRaw(fd);
      throw error instanceof IOException ? (IOException) error : new IOException(error);
    }
  }

  /** 从 `/` 开始逐级打开；{@code create} 时缺失层在父目录 fd 下 mkdir。 */
  private static FileDescriptor walkOpen(File directory, boolean create) throws IOException {
    if (directory == null) throw new IOException("NO_PATH");
    String path = directory.getPath();
    if (!path.startsWith("/") || path.contains("/../") || path.equals("/.."))
      throw new IOException("BAD_PATH");
    if (link2SymlinkPath(path)) throw new IOException("LINK2SYMLINK");
    String normalized = normalized(directory);
    FileDescriptor current;
    try {
      current = Os.open("/", O_PATH | OsConstants.O_CLOEXEC, 0);
    } catch (ErrnoException error) {
      throw new IOException("PARENT_UNVERIFIED", error);
    }
    if (normalized.equals("/")) return current;
    try {
      for (String segment : normalized.substring(1).split("/")) {
        if (!validName(segment)) throw new IOException("BAD_PATH");
        FileDescriptor next;
        try {
          next = openChild(current, segment);
        } catch (ErrnoException missing) {
          if (!create || missing.errno != OsConstants.ENOENT) throw missing;
          ParcelFileDescriptor mkdirParent = dupOf(current);
          try {
            Os.mkdir("/proc/self/fd/" + mkdirParent.getFd() + "/" + segment, 0700);
          } catch (ErrnoException mkdirError) {
            if (mkdirError.errno != OsConstants.EEXIST) throw mkdirError;
          } finally {
            closeQuietly(mkdirParent);
          }
          next = openChild(current, segment);
        } catch (IOException error) {
          throw error;
        }
        boolean isDirectory;
        try {
          isDirectory = OsConstants.S_ISDIR(Os.fstat(next).st_mode);
        } catch (ErrnoException error) {
          closeRaw(next);
          throw new IOException("PARENT_UNVERIFIED", error);
        }
        if (!isDirectory) {
          closeRaw(next);
          throw new IOException("PARENT_NOT_DIRECTORY");
        }
        closeRaw(current);
        current = next;
      }
      return current;
    } catch (ErrnoException error) {
      closeRaw(current);
      throw new IOException("PARENT_CHANGED", error);
    } catch (IOException error) {
      closeRaw(current);
      throw error;
    }
  }

  /** 用仍打开的父目录 fd（dup 后保持到 open 完成）打开子目录，O_NOFOLLOW 拒绝符号链接。 */
  private static FileDescriptor openChild(FileDescriptor parent, String name)
      throws ErrnoException, IOException {
    ParcelFileDescriptor handle = dupOf(parent);
    try {
      return Os.open(
          "/proc/self/fd/" + handle.getFd() + "/" + name,
          O_PATH | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC,
          0);
    } finally {
      closeQuietly(handle);
    }
  }

  /**
   * 用仍打开的 dup 句柄反查真实路径，封住「祖先目录在判据与 open 之间被换成符号链接」的 TOCTOU。
   * handle 一直保持打开到 readlink 返回，避免返回已关闭的 fd 编号。
   */
  private static void verifyOpenedPath(FileDescriptor fd, String expected, PathCheck check)
      throws IOException {
    String opened = readlinkOf(fd);
    if (opened == null || !opened.equals(expected)) throw new IOException("PATH_CHANGED");
    if (check != null && check.denied(opened)) throw new IOException("DENIED");
  }

  private static String readlinkOf(FileDescriptor fd) throws IOException {
    ParcelFileDescriptor handle = dupOf(fd);
    try {
      return Os.readlink("/proc/self/fd/" + handle.getFd());
    } catch (ErrnoException error) {
      throw new IOException("PATH_UNVERIFIED", error);
    } finally {
      closeQuietly(handle);
    }
  }

  private static ParcelFileDescriptor dupOf(FileDescriptor fd) throws IOException {
    try {
      return ParcelFileDescriptor.dup(fd);
    } catch (IOException error) {
      throw new IOException("PATH_UNVERIFIED", error);
    }
  }

  /** 校验通过后才允许截断；openWrite/createInDirectory 共用，失败时由调用方关闭 fd。 */
  private static void truncateAfterVerify(FileDescriptor fd) throws IOException {
    try {
      Os.ftruncate(fd, 0);
    } catch (ErrnoException error) {
      throw new IOException("TRUNCATE_FAILED", error);
    }
  }

  /** 类型与身份核对通过后清掉 O_NONBLOCK，恢复普通文件的阻塞读写语义。 */
  private static void clearNonBlock(FileDescriptor fd) throws IOException {
    try {
      int flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0);
      Os.fcntlInt(fd, OsConstants.F_SETFL, flags & ~OsConstants.O_NONBLOCK);
    } catch (ErrnoException error) {
      throw new IOException("NONBLOCK_CLEAR", error);
    }
  }

  private static StructStat lstatOrNull(String path) {
    try {
      return Os.lstat(path);
    } catch (ErrnoException missing) {
      return null;
    }
  }

  private static void closeRaw(FileDescriptor fd) {
    if (fd == null) return;
    try {
      Os.close(fd);
    } catch (ErrnoException ignored) {
    }
  }

  private static void closeQuietly(AutoCloseable value) {
    if (value == null) return;
    try {
      value.close();
    } catch (Exception ignored) {
    }
  }

  private static String normalized(File directory) {
    String path = directory.getPath();
    if (path.equals("/")) return "/";
    return path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
  }

  /** 拥有底层 fd 的输入流：close 一定释放 fd，不依赖 Android FileInputStream 的实现细节。 */
  static final class OwningInputStream extends java.io.InputStream {
    private final FileInputStream delegate;
    private final FileDescriptor fd;
    private boolean closed;

    OwningInputStream(FileInputStream delegate, FileDescriptor fd) {
      this.delegate = delegate;
      this.fd = fd;
    }

    @Override
    public int read() throws IOException {
      return delegate.read();
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      return delegate.read(buffer, offset, length);
    }

    @Override
    public int available() throws IOException {
      return delegate.available();
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      try {
        delegate.close();
      } finally {
        closeRaw(fd);
      }
    }
  }

  /** 拥有底层 fd 的输出流：close 先 flush，再关闭 fd。 */
  static final class OwningOutputStream extends java.io.OutputStream {
    private final FileOutputStream delegate;
    private final FileDescriptor fd;
    private boolean closed;

    OwningOutputStream(FileOutputStream delegate, FileDescriptor fd) {
      this.delegate = delegate;
      this.fd = fd;
    }

    @Override
    public void write(int value) throws IOException {
      delegate.write(value);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
      delegate.write(buffer, offset, length);
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      try {
        delegate.close();
      } finally {
        closeRaw(fd);
      }
    }
  }

  private static boolean validName(String name) {
    return name != null
        && !name.isEmpty()
        && !".".equals(name)
        && !"..".equals(name)
        && name.indexOf('/') < 0
        && name.indexOf('\0') < 0;
  }
}
