package com.deepseekharness.app.transfer;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.*;

/** Every component of a transfer path is opened beneath directory descriptors with NOFOLLOW. */
final class TransferFiles {
  static final BackupFileSystem FS = new AndroidBackupFileSystem();
  private static final int O_PATH = 0x200000, O_CLOEXEC = 0x80000;

  private TransferFiles() {}

  static File directory(File parent, String name) throws IOException {
    File value = FS.child(parent, name);
    BackupFileSystem.Node node = FS.stat(value);
    if (node.type.equals("MISSING")) FS.directory(value);
    else if (!node.type.equals("DIRECTORY")) throw new IOException("FILE_DIRECTORY_UNSAFE");
    return value;
  }

  static long size(File file) throws IOException {
    BackupFileSystem.Node node = FS.stat(file);
    if (node.type.equals("MISSING")) return 0;
    if (!node.type.equals("FILE")) throw new IOException("FILE_PARTIAL_UNSAFE");
    return node.size;
  }

  static InputStream read(File file) throws IOException {
    return FS.read(file, FS.stat(file));
  }

  static OutputStream write(File file, long offset) throws IOException {
    BackupFileSystem.Node expected = FS.stat(file);
    if (!expected.type.equals("MISSING") && !expected.type.equals("FILE"))
      throw new IOException("FILE_PARTIAL_UNSAFE");
    if (offset > 0 && (!expected.type.equals("FILE") || expected.size != offset))
      throw new IOException("FILE_PARTIAL_CHANGED");
    File absolute = file.getAbsoluteFile();
    String[] components = absolute.getPath().substring(1).split("/");
    FileDescriptor parent = null, opened = null;
    try {
      parent = Os.open("/", O_PATH | O_CLOEXEC, 0);
      for (int index = 0; index < components.length; index++) {
        String component = components[index];
        if (component.isEmpty() || component.equals(".") || component.equals(".."))
          throw new IOException("FILE_PATH_UNSAFE");
        try (ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(parent)) {
          boolean leaf = index == components.length - 1;
          int flags =
              leaf
                  ? OsConstants.O_WRONLY
                      | OsConstants.O_NONBLOCK
                      | O_CLOEXEC
                      | OsConstants.O_NOFOLLOW
                      | (expected.type.equals("MISSING")
                          ? OsConstants.O_CREAT | OsConstants.O_EXCL
                          : 0)
                  : O_PATH | O_CLOEXEC | OsConstants.O_NOFOLLOW;
          opened = Os.open("/proc/self/fd/" + duplicate.getFd() + "/" + component, flags, 0600);
          StructStat stat = Os.fstat(opened);
          if (leaf) {
            if (!OsConstants.S_ISREG(stat.st_mode)
                || (expected.type.equals("FILE")
                    && !expected.key.equals(stat.st_dev + ":" + stat.st_ino)))
              throw new IOException("FILE_PARTIAL_CHANGED");
            if (offset == 0) Os.ftruncate(opened, 0);
            else Os.lseek(opened, offset, OsConstants.SEEK_SET);
          } else if (!OsConstants.S_ISDIR(stat.st_mode))
            throw new IOException("FILE_PARENT_UNSAFE");
        }
        Os.close(parent);
        parent = opened;
        opened = null;
      }
      ParcelFileDescriptor owner = ParcelFileDescriptor.dup(parent);
      FileDescriptor descriptor = owner.getFileDescriptor();
      return new FilterOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(owner)) {
        @Override
        public void write(byte[] bytes, int offset, int count) throws IOException {
          out.write(bytes, offset, count);
        }

        @Override
        public void close() throws IOException {
          try {
            flush();
            Os.fsync(descriptor);
          } catch (ErrnoException error) {
            throw new IOException("FILE_SYNC_FAILED", error);
          } finally {
            super.close();
          }
        }
      };
    } catch (ErrnoException error) {
      throw new IOException("FILE_PRIVATE_IO", error);
    } finally {
      try {
        if (opened != null) Os.close(opened);
      } catch (ErrnoException ignored) {
      }
      try {
        if (parent != null) Os.close(parent);
      } catch (ErrnoException ignored) {
      }
    }
  }
}
