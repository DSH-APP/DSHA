package com.deepseekharness.app.saf;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared Robolectric fakes: a small real-file-backed {@link Os} and a lifecycle-correct
 * {@link ParcelFileDescriptor}.
 *
 * <p>关键点：PFD 的 close 必须让编号失效——这是 t10 P0-1（fdNumber 返回已关闭编号）能漏过
 * 旧测试的根因。任何在 close 之后读取 getFd()/readlink 的代码都会在这里直接失败。
 */
public final class SafTestOs {
  private SafTestOs() {}

  static final int O_PATH = 0x200000;
  static final Map<FileDescriptor, File> RAW_FILES = new IdentityHashMap<>();
  static final Map<FileDescriptor, Boolean> RAW_DIRS = new IdentityHashMap<>();

  /** 每个 dup 出来的 PFD 的 fake 状态；以真实对象身份为键，不依赖影子实例的具体类型。 */
  static final Map<ParcelFileDescriptor, PfdState> PFD_STATES = new IdentityHashMap<>();

  static final Map<FileDescriptor, RandomAccessFile> RAW_HANDLES = new IdentityHashMap<>();
  static final Map<Integer, File> FD_FILES = new HashMap<>();
  static final Set<Integer> OPEN_FDS = new HashSet<>();
  static final List<String> RENAMES = new ArrayList<>();
  static final List<String> REMOVES = new ArrayList<>();
  static final List<String> MKDIRS = new ArrayList<>();
  static int nextFd = 500;
  static int closedPfd;

  static void reset() {
    // Robolectric 把 shadow 调用的 callsite 按 Android 类缓存在 sandbox 里；上一个测试类用别的
    // shadow 配置（基类 PFD 影子，或本文件里 Backup 测试的 OwnedDescriptor）绑定过的构造调用，
    // 切到 FakePfd 后不会自动重绑，新实例就会挂上基类影子数据，随后按 FakePfd 派发实例方法时
    // 强转失败（本仓 CI 与全量单测复现）。每个 SAF 测试开始时显式失效这两条绑定，让下一次调用
    // 按当前 ShadowWrangler 重建。ParcelFileDescriptorInner 是 ContentResolver 包装 Provider
    // 描述符的子类，有自己的构造 callsite，漏掉它时 push/pull 会在包装对象的 close 上失败。
    invalidateParcelFileDescriptorCallsites();
    RAW_FILES.clear();
    RAW_DIRS.clear();
    for (RandomAccessFile handle : RAW_HANDLES.values()) {
      try {
        handle.close();
      } catch (IOException ignored) {
      }
    }
    RAW_HANDLES.clear();
    PFD_STATES.clear();
    FD_FILES.clear();
    OPEN_FDS.clear();
    RENAMES.clear();
    REMOVES.clear();
    MKDIRS.clear();
    nextFd = 500;
    closedPfd = 0;
  }

  private static void invalidateParcelFileDescriptorCallsites() {
    try {
      org.robolectric.internal.bytecode.RobolectricInternals.getShadowInvalidator()
          .invalidateClasses(
              java.util.Arrays.asList(
                  "android.os.ParcelFileDescriptor",
                  "android.content.ContentResolver$ParcelFileDescriptorInner"));
    } catch (LinkageError | RuntimeException ignored) {
      // 内部 API 不可用时继续用逐对象 adopt 兜底。
    }
  }

  static StructStat stat(File file, boolean directory) {
    int mode = (directory ? OsConstants.S_IFDIR : OsConstants.S_IFREG) | 0600;
    long links = 1;
    try {
      int unixMode = (Integer) Files.getAttribute(file.toPath(), "unix:mode");
      mode = unixMode & 07777;
      if (OsConstants.S_ISDIR(unixMode)) mode |= OsConstants.S_IFDIR;
      else if (OsConstants.S_ISFIFO(unixMode)) mode |= OsConstants.S_IFIFO;
      else if (OsConstants.S_ISLNK(unixMode)) mode |= OsConstants.S_IFLNK;
      else mode |= OsConstants.S_IFREG;
      links = (Integer) Files.getAttribute(file.toPath(), "unix:nlink");
    } catch (Throwable ignored) {
      // 非 POSIX 文件系统：保持默认普通文件。
    }
    if (directory) {
      mode = OsConstants.S_IFDIR | (mode & 07777);
    }
    long size = directory ? 0 : file.length();
    int inode = Math.abs(file.getAbsolutePath().hashCode());
    long mtime = file.lastModified() / 1000L;
    return new StructStat(1, inode, mode, links, 0, 0, 0, size, mtime, mtime, mtime, 4096, 0);
  }

  static File resolve(String path) throws ErrnoException {
    if (path == null) throw new ErrnoException("resolve", OsConstants.ENOENT);
    if (!path.startsWith("/proc/self/fd/")) return new File(path);
    String rest = path.substring("/proc/self/fd/".length());
    int slash = rest.indexOf('/');
    String numberText = slash < 0 ? rest : rest.substring(0, slash);
    int number;
    try {
      number = Integer.parseInt(numberText);
    } catch (NumberFormatException bad) {
      throw new ErrnoException("resolve", OsConstants.ENOENT);
    }
    File base = FD_FILES.get(number);
    if (base == null || !OPEN_FDS.contains(number))
      throw new ErrnoException("resolve", OsConstants.ENOENT);
    if (slash < 0) return base;
    return new File(base, rest.substring(slash + 1));
  }

  @org.robolectric.annotation.Implements(Os.class)
  @android.annotation.SuppressLint("NewApi")
  public static final class FakeOs {
    @org.robolectric.annotation.Implementation
    protected static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
      File file = resolve(path);
      boolean directory = (flags & O_PATH) != 0 || file.isDirectory();
      if ((flags & OsConstants.O_NOFOLLOW) != 0 && Files.isSymbolicLink(file.toPath()))
        throw new ErrnoException("open", OsConstants.ELOOP);
      if ((flags & O_PATH) != 0 && !file.isDirectory()) {
        // 模拟「O_PATH 打开了非目录」：production 的 S_ISDIR 失败分支必须关闭这个 fd。
        try {
          RandomAccessFile handle = new RandomAccessFile(file, "r");
          FileDescriptor fd = handle.getFD();
          RAW_FILES.put(fd, file);
          RAW_DIRS.put(fd, false);
          RAW_HANDLES.put(fd, handle);
          return fd;
        } catch (IOException error) {
          throw new ErrnoException("open", OsConstants.EACCES);
        }
      }
      if (directory) {
        if (!file.isDirectory()) throw new ErrnoException("open", OsConstants.ENOTDIR);
        FileDescriptor fd = new FileDescriptor();
        RAW_FILES.put(fd, file);
        RAW_DIRS.put(fd, true);
        return fd;
      }
      boolean write = (flags & OsConstants.O_WRONLY) != 0 || (flags & OsConstants.O_RDWR) != 0;
      try {
        if (!file.exists()) {
          if ((flags & OsConstants.O_CREAT) == 0)
            throw new ErrnoException("open", OsConstants.ENOENT);
          File parent = file.getParentFile();
          if (parent == null || !parent.isDirectory())
            throw new ErrnoException("open", OsConstants.ENOENT);
          if (!file.createNewFile()) throw new ErrnoException("open", OsConstants.EACCES);
        }
        RandomAccessFile handle = new RandomAccessFile(file, write ? "rw" : "r");
        FileDescriptor fd = handle.getFD();
        RAW_FILES.put(fd, file);
        RAW_DIRS.put(fd, false);
        RAW_HANDLES.put(fd, handle);
        return fd;
      } catch (IOException error) {
        throw new ErrnoException("open", OsConstants.EACCES);
      }
    }

    @org.robolectric.annotation.Implementation
    protected static StructStat lstat(String path) throws ErrnoException {
      File file = resolve(path);
      if (!file.exists() && !Files.isSymbolicLink(file.toPath()))
        throw new ErrnoException("lstat", OsConstants.ENOENT);
      return SafTestOs.stat(file, file.isDirectory());
    }

    @org.robolectric.annotation.Implementation
    protected static StructStat stat(String path) throws ErrnoException {
      return lstat(path);
    }

    @org.robolectric.annotation.Implementation
    protected static StructStat fstat(FileDescriptor fd) throws ErrnoException {
      File file = RAW_FILES.get(fd);
      if (file == null) throw new ErrnoException("fstat", OsConstants.EBADF);
      return SafTestOs.stat(file, Boolean.TRUE.equals(RAW_DIRS.get(fd)));
    }

    @org.robolectric.annotation.Implementation
    protected static String readlink(String path) throws ErrnoException {
      File file = resolve(path);
      try {
        return file.getCanonicalPath();
      } catch (IOException error) {
        throw new ErrnoException("readlink", OsConstants.EIO);
      }
    }

    @org.robolectric.annotation.Implementation
    protected static int fcntlInt(FileDescriptor fd, int command, int argument)
        throws ErrnoException {
      return command == OsConstants.F_GETFL ? 0 : argument;
    }

    @org.robolectric.annotation.Implementation
    protected static void ftruncate(FileDescriptor fd, long length) throws ErrnoException {
      RandomAccessFile handle = RAW_HANDLES.get(fd);
      if (handle == null) throw new ErrnoException("ftruncate", OsConstants.EBADF);
      try {
        handle.setLength(length);
      } catch (IOException error) {
        throw new ErrnoException("ftruncate", OsConstants.EIO);
      }
    }

    @org.robolectric.annotation.Implementation
    protected static void close(FileDescriptor fd) {
      RAW_FILES.remove(fd);
      RAW_DIRS.remove(fd);
      RandomAccessFile handle = RAW_HANDLES.remove(fd);
      if (handle != null) {
        try {
          handle.close();
        } catch (IOException ignored) {
        }
      }
    }

    @org.robolectric.annotation.Implementation
    protected static void mkdir(String path, int mode) throws ErrnoException {
      File file = resolve(path);
      if (!file.mkdir()) throw new ErrnoException("mkdir", OsConstants.EEXIST);
      MKDIRS.add(file.getAbsolutePath());
    }

    @org.robolectric.annotation.Implementation
    protected static void rename(String from, String to) throws ErrnoException {
      File source = resolve(from);
      File target = resolve(to);
      try {
        Files.move(
            source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        RENAMES.add(source.getAbsolutePath() + " -> " + target.getAbsolutePath());
      } catch (IOException error) {
        throw new ErrnoException("rename", OsConstants.EIO);
      }
    }

    @org.robolectric.annotation.Implementation
    protected static void remove(String path) throws ErrnoException {
      File file = resolve(path);
      if (!file.delete()) throw new ErrnoException("remove", OsConstants.ENOENT);
      REMOVES.add(file.getAbsolutePath());
    }

    @org.robolectric.annotation.Implementation
    protected static void chmod(String path, int mode) {
      // no-op：只验证调用发生在真实路径上
    }
  }

  /**
   * PFD 生命周期正确的影子：close 后编号不可再用于 dup/readlink。
   *
   * <p>状态按真实 PFD 对象的身份记录，实例方法用 {@link org.robolectric.annotation.RealObject}
   * 反查；不再假定新实例上挂的必定是本类影子（旧实现在这里做 {@code Shadow.extract(handle)}
   * 强转，Robolectric 选择/注册顺序变化时会抛 ClassCastException，CI 上已实际发生）。dup 与
   * open 两条创建路径返回前都会把影子换成（或补建成）本类实例，并保留基类私有字段，使后续
   * 实例方法分发不再强转失败；查不到 fake 状态的对象回退 super，保持基类原行为。
   */
  @org.robolectric.annotation.Implements(ParcelFileDescriptor.class)
  public static final class FakePfd extends org.robolectric.shadows.ShadowParcelFileDescriptor {
    /** 基类影子里与文件/生命周期相关的实例字段；换影子实例时原样搬运。 */
    private static final java.lang.reflect.Field[] BASE_FIELDS = collectBaseFields();

    @org.robolectric.annotation.RealObject private ParcelFileDescriptor realObject;

    @org.robolectric.annotation.Implementation
    protected static ParcelFileDescriptor dup(FileDescriptor descriptor) throws IOException {
      File mapped = RAW_FILES.get(descriptor);
      if (mapped == null) throw new IOException("EBADF");
      ParcelFileDescriptor handle =
          adopt(org.robolectric.shadow.api.Shadow.newInstanceOf(ParcelFileDescriptor.class));
      PfdState state = new PfdState(nextFd++, descriptor, mapped);
      PFD_STATES.put(handle, state);
      FD_FILES.put(state.number, mapped);
      OPEN_FDS.add(state.number);
      return handle;
    }

    /** Provider 常见路径：文件描述符由 ContentResolver 打开，同样要保证挂的是本类影子。 */
    @org.robolectric.annotation.Implementation
    protected static ParcelFileDescriptor open(File file, int mode) throws FileNotFoundException {
      return adopt(org.robolectric.shadows.ShadowParcelFileDescriptor.open(file, mode));
    }

    @org.robolectric.annotation.Implementation
    protected static ParcelFileDescriptor open(
        File file,
        int mode,
        android.os.Handler handler,
        ParcelFileDescriptor.OnCloseListener listener)
        throws IOException {
      return adopt(
          org.robolectric.shadows.ShadowParcelFileDescriptor.open(file, mode, handler, listener));
    }

    /**
     * 保证 handle 上挂的是本类影子实例。
     *
     * <p>Robolectric 的构造函数 callsite 在 shadow 注册顺序切换时可能仍缓存基类 creator，导致
     * {@code Shadow.newInstanceOf}/{@code ShadowParcelFileDescriptor.open} 出来的实例
     * {@code __robo_data__} 是基类影子；随后实例方法按本类分发时会强转失败。这里不做 downcast：
     * 只在类型不符时代入一个本类实例并复制基类私有字段（文件句柄、close 监听等原样保留），
     * 状态仍由 {@link #PFD_STATES} 按真实对象身份提供。
     */
    private static ParcelFileDescriptor adopt(ParcelFileDescriptor handle) {
      Object attached;
      try {
        attached = org.robolectric.shadow.api.Shadow.extract(handle);
      } catch (RuntimeException | LinkageError mismatch) {
        attached = null;
      }
      if (attached instanceof FakePfd) return handle;
      FakePfd shadow = new FakePfd();
      shadow.realObject = handle;
      if (attached != null) {
        for (java.lang.reflect.Field field : BASE_FIELDS) {
          try {
            field.set(shadow, field.get(attached));
          } catch (ReflectiveOperationException | RuntimeException ignored) {
            // 单个内部字段不可搬运时保留默认值，不阻断测试。
          }
        }
      }
      try {
        handle.getClass().getField("__robo_data__").set(handle, shadow);
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // 无 __robo_data__ 说明不是 Robolectric 插桩对象；只能保留原样。
      }
      return handle;
    }

    private static java.lang.reflect.Field[] collectBaseFields() {
      java.util.List<java.lang.reflect.Field> fields = new java.util.ArrayList<>();
      for (java.lang.reflect.Field field :
          org.robolectric.shadows.ShadowParcelFileDescriptor.class.getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
        try {
          field.setAccessible(true);
        } catch (RuntimeException denied) {
          continue;
        }
        fields.add(field);
      }
      return fields.toArray(new java.lang.reflect.Field[0]);
    }

    @org.robolectric.annotation.Implementation
    protected int getFd() {
      PfdState state = state();
      if (state == null) return super.getFd();
      if (state.closed) throw new IllegalStateException("closed ParcelFileDescriptor");
      return state.number;
    }

    @org.robolectric.annotation.Implementation
    protected void close() throws IOException {
      PfdState state = state();
      if (state == null) {
        super.close();
        return;
      }
      if (state.closed) return;
      state.closed = true;
      if (OPEN_FDS.remove(state.number)) closedPfd++;
      FD_FILES.remove(state.number);
    }

    private PfdState state() {
      return realObject == null ? null : PFD_STATES.get(realObject);
    }
  }

  /** 一个 dup 出来的 PFD 的 fake 状态；number 用作 {@code /proc/self/fd/<number>} 编号。 */
  static final class PfdState {
    final int number;
    final FileDescriptor raw;
    final File file;
    boolean closed;

    PfdState(int number, FileDescriptor raw, File file) {
      this.number = number;
      this.raw = raw;
      this.file = file;
    }
  }
}
