package com.deepseekharness.app.backup;

import static org.junit.Assert.*;

import android.app.Application;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;

/** Controlled Android final-open facts; real FIFO syscall behavior is tested separately on POSIX. */
@RunWith(com.deepseekharness.app.core.PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = Application.class,
    shadows = {
      AndroidBackupFileSystemReadTest.LeafOs.class,
      AndroidBackupFileSystemReadTest.OwnedDescriptor.class
    })
public final class AndroidBackupFileSystemReadTest {
  // This shadow models the production arm64 ABI; host syscalls use os.O_DIRECTORY.
  private static final int ARM64_O_DIRECTORY = 040000;
  // Exercise the final-open branch on Windows too, without inheriting its drive paths.
  private static final File LEAF =
      new File("/") {
        @Override
        public File getAbsoluteFile() {
          return this;
        }

        @Override
        public String getPath() {
          return "/";
        }
      };

  private final AndroidBackupFileSystem fs = new AndroidBackupFileSystem();

  private static StructStat info(int type, long inode) {
    return new StructStat(1, inode, type | 0600, 1, 0, 0, 0, 0, 0, 0, 0, 4096, 0);
  }

  @Implements(Os.class)
  public static final class LeafOs {
    static int type, opens, closes, syncs;
    static long inode;
    static boolean directory, symlink;

    @Implementation
    protected static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
      opens++;
      assertEquals(0, flags & (OsConstants.O_TRUNC | OsConstants.O_CREAT));
      assertTrue(
          "FIFO replacement must not block before fstat", (flags & OsConstants.O_NONBLOCK) != 0);
      assertTrue("Final links must remain forbidden", (flags & OsConstants.O_NOFOLLOW) != 0);
      if (directory) {
        assertTrue(
            "Directory reads must demand an arm64 directory", (flags & ARM64_O_DIRECTORY) != 0);
        if (type != OsConstants.S_IFDIR) throw new ErrnoException("open", OsConstants.ENOTDIR);
      }
      if (symlink) throw new ErrnoException("open", OsConstants.ELOOP);
      return new FileDescriptor();
    }

    @Implementation
    protected static StructStat lstat(String path) {
      return info(directory ? OsConstants.S_IFDIR : OsConstants.S_IFREG, 9);
    }

    @Implementation
    protected static StructStat fstat(FileDescriptor descriptor) {
      return info(type, inode);
    }

    @Implementation
    protected static void close(FileDescriptor descriptor) {
      closes++;
    }

    @Implementation
    protected static void fsync(FileDescriptor descriptor) {
      syncs++;
    }
  }

  @Implements(ParcelFileDescriptor.class)
  public static final class OwnedDescriptor {
    static int duplicates, closes;
    private FileDescriptor descriptor;
    private boolean closed;

    @Implementation
    protected static ParcelFileDescriptor dup(FileDescriptor descriptor) {
      ParcelFileDescriptor handle = Shadow.newInstanceOf(ParcelFileDescriptor.class);
      OwnedDescriptor shadow = Shadow.extract(handle);
      shadow.descriptor = descriptor;
      duplicates++;
      return handle;
    }

    @Implementation
    protected FileDescriptor getFileDescriptor() {
      return descriptor;
    }

    @Implementation
    protected void close() {
      if (!closed) {
        closed = true;
        closes++;
      }
    }
  }

  @Before
  public void reset() {
    LeafOs.type = OsConstants.S_IFIFO;
    LeafOs.inode = 9;
    LeafOs.directory = LeafOs.symlink = false;
    LeafOs.opens = LeafOs.closes = LeafOs.syncs = 0;
    OwnedDescriptor.duplicates = OwnedDescriptor.closes = 0;
  }

  private BackupFileSystem.Node expectedFile() {
    return AndroidBackupFileSystem.node(info(OsConstants.S_IFREG, 9));
  }

  private void assertClosed() {
    assertEquals(LeafOs.opens, LeafOs.closes);
    assertEquals(OwnedDescriptor.duplicates, OwnedDescriptor.closes);
  }

  @Test
  public void regularReplacedByFifoIsRejectedBeforeReturningStreamAndClosesDescriptors() {
    IOException error = assertThrows(IOException.class, () -> fs.read(LEAF, expectedFile()));
    assertEquals("SOURCE_CHANGED", error.getMessage());
    assertClosed();
  }

  @Test
  public void currentSnapshotRejectsFifoBeforeReadingAndClosesDescriptors() {
    IOException error = assertThrows(IOException.class, () -> fs.currentSnapshot(LEAF, 4096));
    assertEquals("METADATA_LIMIT", error.getMessage());
    assertClosed();
  }

  @Test
  public void directoryReplacedByFifoCannotReachEnumeration() {
    LeafOs.directory = true;
    IOException error = assertThrows(IOException.class, () -> fs.list(LEAF));
    assertTrue(error.getCause() instanceof ErrnoException);
    assertEquals(OsConstants.ENOTDIR, ((ErrnoException) error.getCause()).errno);
    assertEquals(0, OwnedDescriptor.duplicates);
    assertEquals(0, LeafOs.closes); // Failed open created no descriptor.
  }

  @Test
  public void syncDirectoryReplacedByFifoCannotReachFsync() {
    LeafOs.directory = true;
    IOException error = assertThrows(IOException.class, () -> fs.syncDirectory(LEAF));
    assertEquals(OsConstants.ENOTDIR, ((ErrnoException) error.getCause()).errno);
    assertEquals(0, LeafOs.syncs);
    assertEquals(0, LeafOs.closes);
  }

  @Test
  public void normalDirectoryStillFsyncsOnceAndCloses() throws Exception {
    LeafOs.directory = true;
    LeafOs.type = OsConstants.S_IFDIR;
    fs.syncDirectory(LEAF);
    assertEquals(1, LeafOs.syncs);
    assertClosed();
  }

  @Test
  public void regularDescriptorStillOpensAndClosesExactlyOnce() throws Exception {
    LeafOs.type = OsConstants.S_IFREG;
    InputStream input = fs.read(LEAF, expectedFile());
    input.close();
    input.close();
    assertClosed();
    assertEquals(1, OwnedDescriptor.closes);
  }

  @Test
  public void changedRegularDescriptorOnCloseStillFailsAndCloses() throws Exception {
    LeafOs.type = OsConstants.S_IFREG;
    InputStream input = fs.read(LEAF, expectedFile());
    LeafOs.inode = 10;
    assertEquals("SOURCE_CHANGED", assertThrows(IOException.class, input::close).getMessage());
    assertClosed();
  }

  @Test
  public void symlinkReplacementStillFailsWithoutOpeningTarget() {
    LeafOs.symlink = true;
    IOException error = assertThrows(IOException.class, () -> fs.read(LEAF, expectedFile()));
    assertEquals(OsConstants.ELOOP, ((ErrnoException) error.getCause()).errno);
    assertEquals(0, OwnedDescriptor.duplicates);
    assertEquals(0, LeafOs.closes);
  }
}
