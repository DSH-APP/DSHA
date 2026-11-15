package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.JvmBackupFileSystem;
import com.deepseekharness.app.core.RuntimeTasks;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Real provider queries and isolated disk snapshots; host links are explicit fixture facts. */
@RunWith(DocumentRowsSnapshotTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class DshaDocumentsProviderTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  /** Inject replacement after File.isFile(), precisely at the OS open boundary. */
  @Implements(Os.class)
  public static final class ReplacedFile {
    static boolean symlink;
    static int opens, closes, truncates;

    @Implementation
    protected static FileDescriptor open(String path, int flags, int mode) throws ErrnoException {
      opens++;
      assertEquals(
          "No pathname may be truncated before validation", 0, flags & OsConstants.O_TRUNC);
      if (symlink) {
        assertTrue("Final-link replacement must be refused", (flags & OsConstants.O_NOFOLLOW) != 0);
        throw new ErrnoException("open", OsConstants.ELOOP);
      }
      // A blocking open would hang before fstat; fail the negative control immediately.
      assertTrue("FIFO replacement would block", (flags & OsConstants.O_NONBLOCK) != 0);
      if ((flags & OsConstants.O_ACCMODE) == OsConstants.O_WRONLY)
        throw new ErrnoException("open", OsConstants.ENXIO);
      return new FileDescriptor();
    }

    @Implementation
    protected static StructStat fstat(FileDescriptor descriptor) {
      return new StructStat(1, 9, OsConstants.S_IFIFO | 0600, 1, 0, 0, 0, 0, 0, 0, 0, 4096, 0);
    }

    @Implementation
    protected static void close(FileDescriptor descriptor) {
      closes++;
    }

    @Implementation
    protected static void ftruncate(FileDescriptor descriptor, long length) {
      truncates++;
      fail("Replacement must never reach truncate");
    }
  }

  private static final class Provider extends DshaDocumentsProvider {
    final File base;
    final Map<String, String> links = new HashMap<>();
    Runnable read = () -> {};

    Provider(File base) {
      this.base = base;
    }

    @Override
    protected File documentBase() {
      return base;
    }

    @Override
    protected BackupFileSystem documentFileSystem() {
      return new JvmBackupFileSystem();
    }

    @Override
    protected String link(File file) throws IOException {
      read.run();
      return links.get(file.getPath());
    }
  }

  private Provider provider() throws IOException {
    Provider provider = new Provider(temporary.newFolder());
    ProviderInfo info = new ProviderInfo();
    info.authority = "io.github.sunsetrne.dsha.documents";
    info.exported = true;
    info.grantUriPermissions = true;
    info.readPermission = info.writePermission = "android.permission.MANAGE_DOCUMENTS";
    provider.attachInfo(RuntimeEnvironment.getApplication(), info);
    return provider;
  }

  private void file(Provider provider, String name) throws IOException {
    File file = new File(provider.base, name);
    Files.createDirectories(file.getParentFile().toPath());
    Files.writeString(file.toPath(), "fixture");
  }

  @Test
  public void fullDirectorySurvivesNativePageBoundaryAndKeepsOpaqueIds() throws Exception {
    Provider provider = provider();
    for (int i = 299; i >= 0; i--)
      file(provider, String.format(java.util.Locale.ROOT, "file-%03d.txt", i));
    try (Cursor cursor = provider.queryChildDocuments("root", null, (String) null)) {
      assertEquals(300, cursor.getCount());
      assertTrue(cursor.moveToPosition(200));
      assertEquals("file-200.txt", cursor.getString(0));
      assertEquals("file-200.txt", cursor.getString(2));
      assertTrue(cursor.moveToLast());
      assertEquals("file-299.txt", cursor.getString(0));
      assertTrue(cursor.getNotificationUri().toString().endsWith("/document/root/children"));
    }
    assertFalse(RuntimeTasks.isBusy());
  }

  @Test
  public void guestAbsoluteLinksAndLoopsKeepExistingContainmentRules() throws Exception {
    Provider provider = provider();
    for (String name : new String[] {"actual.txt", "alias.txt", "loop-a", "loop-b"})
      file(provider, "linux/ubuntu/root/" + name);
    provider.links.put(
        new File(provider.base, "linux/ubuntu/root/alias.txt").getPath(), "/root/actual.txt");
    provider.links.put(new File(provider.base, "linux/ubuntu/root/loop-a").getPath(), "loop-b");
    provider.links.put(new File(provider.base, "linux/ubuntu/root/loop-b").getPath(), "loop-a");
    try (Cursor cursor = provider.queryChildDocuments("linux/ubuntu/root", null, (String) null)) {
      assertEquals(2, cursor.getCount());
      assertTrue(cursor.moveToLast());
      assertEquals("linux/ubuntu/root/alias.txt", cursor.getString(0));
      assertEquals(7, cursor.getLong(5));
    }
    file(provider, "outside");
    provider.links.put(new File(provider.base, "outside").getPath(), "../unowned");
    assertThrows(FileNotFoundException.class, () -> provider.queryDocument("outside", null));
    assertFalse(provider.isChildDocument("linux/ubuntu/root", "outside"));
  }

  @Test
  public void cancelledEnumerationClosesSnapshotAndDoesNotPoisonLaterQueries() throws Exception {
    Provider provider = provider();
    for (int i = 0; i < 100; i++) file(provider, "file-" + i);
    CancellationSignal signal = new CancellationSignal();
    int[] reads = {0};
    provider.read =
        () -> {
          if (++reads[0] == 10) signal.cancel();
        };
    try (AutoCloseable scope = DshaDocumentsProvider.withQueryCancellation(signal)) {
      assertThrows(
          OperationCanceledException.class,
          () -> provider.queryChildDocuments("root", null, (String) null));
    }
    assertTrue(reads[0] < 100);
    assertFalse(RuntimeTasks.isBusy());
    provider.read = () -> {};
    try (Cursor cursor = provider.queryChildDocuments("root", null, (String) null)) {
      assertEquals(100, cursor.getCount());
    }
  }

  @Test
  public void nestedQueryScopesRestoreOuterCancellation() throws Exception {
    Provider provider = provider();
    CancellationSignal outer = new CancellationSignal();
    outer.cancel();
    try (AutoCloseable ignored = DshaDocumentsProvider.withQueryCancellation(outer)) {
      try (AutoCloseable inner =
              DshaDocumentsProvider.withQueryCancellation(new CancellationSignal());
          Cursor cursor = provider.queryChildDocuments("root", null, (String) null)) {
        assertEquals(0, cursor.getCount());
      }
      assertThrows(
          OperationCanceledException.class,
          () -> provider.queryChildDocuments("root", null, (String) null));
    }
    assertFalse(RuntimeTasks.isBusy());
  }

  @Test
  public void unavailableFolderIsAnExplicitFailureAndReleasesLease() throws Exception {
    Provider provider = provider();
    file(provider, "file.txt");
    for (String id : new String[] {"missing", "file.txt", "../outside"}) {
      assertThrows(
          FileNotFoundException.class, () -> provider.queryChildDocuments(id, null, (String) null));
      assertFalse(RuntimeTasks.isBusy());
    }
  }

  @Test
  public void cancelledOrInvalidOpenDoesNotLeaveAnActiveRuntimeLease() throws Exception {
    Provider provider = provider();
    file(provider, "file.txt");
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();
    assertThrows(
        OperationCanceledException.class, () -> provider.openDocument("file.txt", "r", signal));
    assertFalse(RuntimeTasks.isBusy());
    for (String id : new String[] {"missing", "root", "../outside"}) {
      assertThrows(FileNotFoundException.class, () -> provider.openDocument(id, "r", null));
      assertFalse(RuntimeTasks.isBusy());
    }
    assertThrows(
        FileNotFoundException.class, () -> provider.openDocument("file.txt", "invalid", null));
    assertFalse(RuntimeTasks.isBusy());
    assertEquals("fixture", Files.readString(new File(provider.base, "file.txt").toPath()));
  }

  @Test
  @Config(shadows = ReplacedFile.class)
  public void fifoSwapCannotBlockOrTruncateAndEveryPinnedDescriptorIsClosed() throws Exception {
    Provider provider = provider();
    file(provider, "file.txt");
    ReplacedFile.symlink = false;
    ReplacedFile.opens = ReplacedFile.closes = ReplacedFile.truncates = 0;
    for (String mode : new String[] {"r", "w", "rw", "wa", "rwt"}) {
      assertThrows(
          FileNotFoundException.class, () -> provider.openDocument("file.txt", mode, null));
      assertFalse(RuntimeTasks.isBusy());
      assertEquals("fixture", Files.readString(new File(provider.base, "file.txt").toPath()));
    }
    assertEquals(5, ReplacedFile.opens);
    assertEquals(3, ReplacedFile.closes); // Write-only FIFO fails at open; r/rw/rwt must close.
    assertEquals(0, ReplacedFile.truncates);
  }

  @Test
  @Config(shadows = ReplacedFile.class)
  public void finalLinkSwapIsRefusedBeforeOpeningTheTargetOrTruncating() throws Exception {
    Provider provider = provider();
    file(provider, "file.txt");
    ReplacedFile.symlink = true;
    ReplacedFile.opens = ReplacedFile.closes = ReplacedFile.truncates = 0;
    assertThrows(FileNotFoundException.class, () -> provider.openDocument("file.txt", "w", null));
    assertEquals(1, ReplacedFile.opens);
    assertEquals(0, ReplacedFile.closes);
    assertEquals(0, ReplacedFile.truncates);
    assertEquals("fixture", Files.readString(new File(provider.base, "file.txt").toPath()));
    assertFalse(RuntimeTasks.isBusy());
  }
}
