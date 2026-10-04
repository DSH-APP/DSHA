package com.deepseekharness.app.bridge;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.deepseekharness.app.util.DeviceFileOperations;
import com.deepseekharness.app.util.DeviceShellPolicy;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;

/** 固定父目录描述符的设备文件执行边界；没有 cp/mv/rm 子进程或沿用户链接回退。 */
public final class AndroidDeviceFiles implements DeviceFileOperations.Access {
  private static final int O_PATH = 0x200000;
  private static final int O_CLOEXEC = 0x80000;
  // Linux asm-generic UAPI；Java OsConstants 未公开 O_DIRECTORY，arm64/API23 使用同一值。
  private static final int O_DIRECTORY = 0x10000;

  private static IOException failure(ErrnoException error) {
    return new IOException("DEVICE_FILESYSTEM_" + error.errno, error);
  }

  @Override
  public DeviceFileOperations.Entry open(String raw, boolean write, boolean parents)
      throws IOException {
    return open(raw, write, parents, false);
  }

  @Override
  public void validate(String raw, boolean write, boolean parents) throws IOException {
    try (var entry = open(raw, write, parents, true)) {
      if (entry.type().equals("LINK") || entry.type().equals("SPECIAL"))
        throw new IOException("LINK_OR_SPECIAL_FILE");
    }
  }

  private DeviceFileOperations.Entry open(
      String raw, boolean write, boolean parents, boolean inspectOnly) throws IOException {
    String path = DeviceShellPolicy.normalize(raw);
    if (path.isEmpty()) throw new IOException("ABSOLUTE_PATH_REQUIRED");
    String root = write ? DeviceFileOperations.writeRoot(path) : "/";
    String actualRoot = new File(root).getCanonicalPath();
    if (write && !DeviceShellPolicy.normalize(actualRoot).equals(root))
      throw new IOException("STORAGE_ROOT_UNVERIFIED");
    Pinned at = null;
    try {
      FileDescriptor fd =
          Os.open(actualRoot, O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW | O_DIRECTORY, 0);
      ParcelFileDescriptor rootHandle = null;
      try {
        rootHandle = ParcelFileDescriptor.dup(fd);
        at = new Pinned(root, null, "", rootHandle, write);
        rootHandle = null;
      } finally {
        Os.close(fd);
        if (rootHandle != null) rootHandle.close();
      }
      String suffix = root.equals("/") ? path.substring(1) : path.substring(root.length() + 1);
      String[] parts = suffix.split("/");
      if (suffix.isEmpty()) {
        Pinned result = at;
        at = null;
        return result;
      }
      for (int i = 0; i < parts.length; i++) {
        Pinned next = (Pinned) at.child(parts[i]);
        if (i + 1 < parts.length && next.type().equals("MISSING") && parents) {
          if (inspectOnly) {
            at.close();
            at = null;
            return next;
          }
          next.directory();
        }
        if (i + 1 < parts.length && !next.type().equals("DIRECTORY")) {
          next.close();
          throw new IOException("PARENT_LINK_OR_MISSING");
        }
        at.close();
        at = next;
      }
      Pinned result = at;
      at = null;
      return result;
    } catch (ErrnoException error) {
      throw failure(error);
    } finally {
      if (at != null) at.close();
    }
  }

  private static final class Pinned implements DeviceFileOperations.Entry {
    final String logical, name;
    final boolean writable;
    final ParcelFileDescriptor parent;
    final String parentBinding;
    ParcelFileDescriptor directory;
    StructStat expected;
    boolean closed;

    Pinned(
        String logical,
        ParcelFileDescriptor parent,
        String name,
        ParcelFileDescriptor directory,
        boolean writable)
        throws IOException, ErrnoException {
      this.logical = logical;
      this.parent = parent;
      this.parentBinding = parent == null ? "" : Os.readlink("/proc/self/fd/" + parent.getFd());
      this.name = name;
      this.directory = directory;
      this.writable = writable;
      if (writable
          && parent != null
          && !DeviceShellPolicy.normalize(parentBinding)
              .equals(new File(logical).getParentFile().getPath()))
        throw new IOException("FILE_PARENT_CHANGED");
      expected = parent == null ? Os.fstat(directory.getFileDescriptor()) : stat();
      if (expected != null && OsConstants.S_ISDIR(expected.st_mode) && directory == null)
        pinDirectory();
    }

    @Override
    public String path() {
      return logical;
    }

    @Override
    public String name() {
      return name;
    }

    String at() throws IOException {
      if (closed || parent == null) throw new IOException("FILE_HANDLE_CLOSED_OR_ROOT");
      return "/proc/self/fd/" + parent.getFd() + "/" + name;
    }

    StructStat stat() throws ErrnoException, IOException {
      try {
        return Os.lstat(at());
      } catch (ErrnoException error) {
        if (error.errno == OsConstants.ENOENT) return null;
        throw error;
      }
    }

    static boolean same(StructStat a, StructStat b) {
      return a == null
          ? b == null
          : b != null
              && a.st_dev == b.st_dev
              && a.st_ino == b.st_ino
              && (a.st_mode & 0170000) == (b.st_mode & 0170000);
    }

    void verify() throws IOException, ErrnoException {
      DeviceFileOperations.cancelled();
      if (closed) throw new IOException("FILE_HANDLE_CLOSED");
      if (parent != null && !parentBinding.equals(Os.readlink("/proc/self/fd/" + parent.getFd())))
        throw new IOException("FILE_PARENT_CHANGED");
      if (parent != null && !same(expected, stat())) throw new IOException("FILE_TARGET_CHANGED");
      if (directory != null && !same(expected, Os.fstat(directory.getFileDescriptor())))
        throw new IOException("DIRECTORY_TARGET_CHANGED");
      if (writable && parent != null && !DeviceShellPolicy.writeAllowed(logical))
        throw new IOException("PROTECTED_WRITE_PATH");
    }

    void pinDirectory() throws ErrnoException, IOException {
      FileDescriptor fd =
          Os.open(at(), O_PATH | O_CLOEXEC | O_DIRECTORY | OsConstants.O_NOFOLLOW, 0);
      try {
        if (!same(expected, Os.fstat(fd))) throw new IOException("DIRECTORY_TARGET_CHANGED");
        directory = ParcelFileDescriptor.dup(fd);
      } finally {
        Os.close(fd);
      }
    }

    @Override
    public String type() throws IOException {
      try {
        verify();
        return expected == null
            ? "MISSING"
            : OsConstants.S_ISREG(expected.st_mode)
                ? "FILE"
                : OsConstants.S_ISDIR(expected.st_mode)
                    ? "DIRECTORY"
                    : OsConstants.S_ISLNK(expected.st_mode) ? "LINK" : "SPECIAL";
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    @Override
    public DeviceFileOperations.Entry child(String child) throws IOException {
      if (child == null
          || child.isEmpty()
          || child.equals(".")
          || child.equals("..")
          || child.contains("/")
          || child.indexOf('\0') >= 0) throw new IOException("CHILD_NAME_INVALID");
      if (!type().equals("DIRECTORY") || directory == null)
        throw new IOException("DIRECTORY_REQUIRED");
      ParcelFileDescriptor copy = ParcelFileDescriptor.dup(directory.getFileDescriptor());
      try {
        return new Pinned(
            logical.equals("/") ? "/" + child : logical + "/" + child, copy, child, null, writable);
      } catch (ErrnoException | IOException error) {
        copy.close();
        if (error instanceof ErrnoException) throw failure((ErrnoException) error);
        throw (IOException) error;
      }
    }

    @Override
    public List<String> names() throws IOException {
      if (!type().equals("DIRECTORY")) throw new IOException("DIRECTORY_REQUIRED");
      String[] names = new File("/proc/self/fd/" + directory.getFd()).list();
      if (names == null || names.length > 20000)
        throw new IOException("DIRECTORY_UNREADABLE_OR_LIMIT");
      Arrays.sort(names);
      type();
      return Arrays.asList(names);
    }

    ParcelFileDescriptor file(int flags, int mode) throws IOException, ErrnoException {
      verify();
      FileDescriptor fd =
          Os.open(at(), flags | O_CLOEXEC | OsConstants.O_NOFOLLOW | OsConstants.O_NONBLOCK, mode);
      try {
        StructStat opened = Os.fstat(fd);
        if (!OsConstants.S_ISREG(opened.st_mode) || expected != null && !same(expected, opened))
          throw new IOException("FILE_TARGET_CHANGED");
        if (writable && opened.st_nlink > 1) throw new IOException("MULTIPLE_FILE_LINKS");
        return ParcelFileDescriptor.dup(fd);
      } finally {
        Os.close(fd);
      }
    }

    @Override
    public InputStream read() throws IOException {
      if (!type().equals("FILE")) throw new IOException("REGULAR_FILE_REQUIRED");
      try {
        ParcelFileDescriptor fd = file(OsConstants.O_RDONLY, 0);
        try {
          String actual = Os.readlink("/proc/self/fd/" + fd.getFd());
          if (DeviceShellPolicy.smsProviderPath(actual))
            throw new IOException("SMS_PROVIDER_READ_BLOCKED");
          return new ParcelFileDescriptor.AutoCloseInputStream(fd);
        } catch (IOException | ErrnoException error) {
          fd.close();
          throw error;
        }
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    @Override
    public OutputStream write() throws IOException {
      if (!writable || !DeviceShellPolicy.writeAllowed(logical))
        throw new IOException("READ_ONLY_HANDLE");
      String type = type();
      if (!type.equals("FILE") && !type.equals("MISSING"))
        throw new IOException("REGULAR_FILE_REQUIRED");
      try {
        // 不能在 open 时截断：先核对已打开的 inode 与 hard-link 数，再执行 ftruncate。
        ParcelFileDescriptor handle =
            file(
                OsConstants.O_WRONLY
                    | (expected == null ? OsConstants.O_CREAT | OsConstants.O_EXCL : 0),
                0644);
        try {
          expected = Os.fstat(handle.getFileDescriptor());
          Os.ftruncate(handle.getFileDescriptor(), 0);
          return new ParcelFileDescriptor.AutoCloseOutputStream(handle);
        } catch (ErrnoException error) {
          handle.close();
          throw error;
        }
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    @Override
    public void directory() throws IOException {
      if (!writable || !DeviceShellPolicy.writeAllowed(logical))
        throw new IOException("READ_ONLY_HANDLE");
      try {
        verify();
        if (expected != null) throw new IOException("FILE_EXISTS");
        Os.mkdir(at(), 0755);
        expected = stat();
        pinDirectory();
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    @Override
    public void touch() throws IOException {
      if (!writable || !DeviceShellPolicy.writeAllowed(logical))
        throw new IOException("READ_ONLY_HANDLE");
      if (type().equals("MISSING")) {
        try (OutputStream output = write()) {}
        return;
      }
      if (type().equals("DIRECTORY")) {
        if (!new File("/proc/self/fd/" + directory.getFd())
            .setLastModified(System.currentTimeMillis())) throw new IOException("TOUCH_FAILED");
        return;
      }
      try (ParcelFileDescriptor handle = file(OsConstants.O_WRONLY, 0)) {
        if (!new File("/proc/self/fd/" + handle.getFd())
            .setLastModified(System.currentTimeMillis())) throw new IOException("TOUCH_FAILED");
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    @Override
    public void metadataTo(DeviceFileOperations.Entry value) throws IOException {
      if (!(value instanceof Pinned)) throw new IOException("FILE_HANDLE_MISMATCH");
      Pinned target = (Pinned) value;
      if (!target.writable || !DeviceShellPolicy.writeAllowed(target.logical))
        throw new IOException("READ_ONLY_HANDLE");
      try {
        verify();
        target.verify();
        if (expected == null) throw new IOException("FILE_MISSING");
        if (target.type().equals("DIRECTORY")) {
          Os.chmod("/proc/self/fd/" + target.directory.getFd(), expected.st_mode & 0777);
          Os.chown("/proc/self/fd/" + target.directory.getFd(), expected.st_uid, expected.st_gid);
          if (!new File("/proc/self/fd/" + target.directory.getFd())
              .setLastModified(expected.st_mtime * 1000)) throw new IOException("FILE_TIME_FAILED");
        } else
          try (ParcelFileDescriptor handle = target.file(OsConstants.O_WRONLY, 0)) {
            Os.fchmod(handle.getFileDescriptor(), expected.st_mode & 0777);
            Os.fchown(handle.getFileDescriptor(), expected.st_uid, expected.st_gid);
            if (!new File("/proc/self/fd/" + handle.getFd())
                .setLastModified(expected.st_mtime * 1000))
              throw new IOException("FILE_TIME_FAILED");
          }
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    @Override
    public void delete() throws IOException {
      if (!writable || !DeviceShellPolicy.writeAllowed(logical))
        throw new IOException("READ_ONLY_HANDLE");
      try {
        verify();
        if (expected == null) throw new IOException("FILE_MISSING");
        Os.remove(at());
        expected = null;
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    @Override
    public void move(DeviceFileOperations.Entry value, boolean noClobber) throws IOException {
      if (!(value instanceof Pinned)) throw new IOException("MOVE_HANDLE_MISMATCH");
      Pinned target = (Pinned) value;
      if (!writable
          || !target.writable
          || !DeviceShellPolicy.writeAllowed(logical)
          || !DeviceShellPolicy.writeAllowed(target.logical))
        throw new IOException("READ_ONLY_HANDLE");
      try {
        verify();
        target.verify();
        if (expected == null || same(expected, target.expected))
          throw new IOException("MOVE_SOURCE_INVALID");
        if (target.expected != null
            && (OsConstants.S_ISLNK(target.expected.st_mode)
                || !OsConstants.S_ISREG(target.expected.st_mode)))
          throw new IOException("MOVE_TARGET_EXISTS_OR_LINK");
        if (noClobber) {
          // 对普通文件用内核独占 link 发布；不支持 hard-link 的挂载返回明确错误且保留源。
          if (!OsConstants.S_ISREG(expected.st_mode) || target.expected != null)
            throw new IOException("ATOMIC_NOCLOBBER_MOVE_UNSUPPORTED");
          Os.link(at(), target.at());
          target.expected = target.stat();
          verify();
          target.verify();
          if (!same(expected, target.expected)) throw new IOException("FILE_TARGET_CHANGED");
          Os.remove(at());
        } else {
          // rename 对最终链接只改名该链接本身；两个父目录已固定，EXDEV 不改通道重放。
          Os.rename(at(), target.at());
        }
        target.expected = target.stat();
        expected = null;
      } catch (ErrnoException error) {
        throw failure(error);
      }
    }

    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      IOException failure = null;
      try {
        if (directory != null) directory.close();
      } catch (IOException error) {
        failure = error;
      }
      try {
        if (parent != null) parent.close();
      } catch (IOException error) {
        if (failure == null) failure = error;
        else failure.addSuppressed(error);
      }
      if (failure != null) throw failure;
    }
  }
}
