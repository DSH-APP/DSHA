package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import com.deepseekharness.app.data.DownloadsExport;
import com.deepseekharness.app.util.FileIntegrity;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

/** 通过真实流和私有隔离 Provider 执行现有 MediaStore 发布器。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class BackupDownloadsPublicationTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private static final String NAME = "DSHA-data-v5-aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.tar.gz";

  public static final class Provider extends ContentProvider {
    File file;
    ContentValues inserted;
    int publishes, deletes;
    boolean corrupt, rejectPublish, cancelOnWrite;
    BackupControl control;
    final List<ParcelFileDescriptor> opened = new ArrayList<>();

    public boolean onCreate() {
      return true;
    }

    public Uri insert(Uri uri, ContentValues values) {
      inserted = new ContentValues(values);
      return Uri.parse("content://media/external/downloads/73");
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
      try {
        if (mode.equals("r") && corrupt) Files.write(file.toPath(), new byte[] {9, 9, 9, 9});
        ParcelFileDescriptor p =
            ParcelFileDescriptor.open(
                file,
                mode.equals("r")
                    ? ParcelFileDescriptor.MODE_READ_ONLY
                    : ParcelFileDescriptor.MODE_READ_WRITE
                        | ParcelFileDescriptor.MODE_CREATE
                        | ParcelFileDescriptor.MODE_TRUNCATE);
        opened.add(p);
        if (!mode.equals("r") && cancelOnWrite) control.cancel();
        return p;
      } catch (IOException e) {
        throw new FileNotFoundException(e.toString());
      }
    }

    public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
      MatrixCursor c = new MatrixCursor(columns);
      c.addRow(new Object[] {NAME});
      return c;
    }

    public String getType(Uri uri) {
      return "application/gzip";
    }

    public int delete(Uri uri, String selection, String[] args) {
      deletes++;
      assertEquals("content://media/external/downloads/73", uri.toString());
      file.delete();
      return 1;
    }

    public int update(Uri uri, ContentValues values, String selection, String[] args) {
      assertEquals(0, values.getAsInteger(MediaStore.MediaColumns.IS_PENDING).intValue());
      assertTrue(file.length() > 0);
      publishes++;
      return rejectPublish ? 0 : 1;
    }
  }

  Provider provider;
  File source;
  BackupControl control;
  final JvmBackupFileSystem fs = new JvmBackupFileSystem();

  @Before
  public void setup() throws Exception {
    source = temporary.newFile("source.tar.gz");
    Files.write(source.toPath(), new byte[] {1, 2, 3, 4});
    provider = new Provider();
    provider.file = temporary.newFile("destination.tar.gz");
    control = new BackupControl(null);
    provider.control = control;
    var info = new android.content.pm.ProviderInfo();
    info.authority = "media";
    info.exported = true;
    info.grantUriPermissions = true;
    provider.attachInfo(RuntimeEnvironment.getApplication(), info);
    ShadowContentResolver.registerProviderInternal("media", provider);
  }

  private DownloadsExport.Result publish() throws Exception {
    FileIntegrity.Result expected;
    try (InputStream input = new FileInputStream(source)) {
      expected = FileIntegrity.copy(input, null, source.length(), control::check);
    }
    var method =
        DownloadsExport.class.getDeclaredMethod(
            "media",
            Context.class,
            BackupFileSystem.class,
            File.class,
            BackupFileSystem.Node.class,
            String.class,
            FileIntegrity.Result.class,
            BackupControl.class);
    method.setAccessible(true);
    try {
      return (DownloadsExport.Result)
          method.invoke(
              null,
              RuntimeEnvironment.getApplication(),
              fs,
              source,
              fs.stat(source),
              NAME,
              expected,
              control);
    } catch (InvocationTargetException e) {
      throw (Exception) e.getCause();
    }
  }

  @Test
  public void uuidFileIsReadBackBeforePublishingAndStreamsAreClosed() throws Exception {
    var result = publish();
    assertEquals(NAME, result.displayName);
    assertArrayEquals(
        Files.readAllBytes(source.toPath()), Files.readAllBytes(provider.file.toPath()));
    assertEquals(
        "Download/DSHA/", provider.inserted.getAsString(MediaStore.MediaColumns.RELATIVE_PATH));
    assertEquals(1, provider.inserted.getAsInteger(MediaStore.MediaColumns.IS_PENDING).intValue());
    assertEquals(1, provider.publishes);
    assertEquals(0, provider.deletes);
    assertEquals(2, provider.opened.size());
    for (var p : provider.opened) assertThrows(IllegalStateException.class, p::getFd);
  }

  @Test
  public void corruptReadbackNeverPublishesAndDeletesOnlyTheNewPendingUri() throws Exception {
    provider.corrupt = true;
    assertThrows(IOException.class, this::publish);
    assertEquals(2, provider.opened.size());
    assertEquals(0, provider.publishes);
    assertEquals(1, provider.deletes);
    assertFalse(provider.file.exists());
  }

  @Test
  public void cancellationDoesNotPublishAndCleansTheNewPendingUri() throws Exception {
    provider.cancelOnWrite = true;
    assertThrows(IOException.class, this::publish);
    assertEquals(1, provider.opened.size());
    assertEquals(0, provider.publishes);
    assertEquals(1, provider.deletes);
    assertFalse(provider.file.exists());
  }

  @Test
  public void rejectedPublicationIsAFailureAndDoesNotLeaveAPendingSuccess() throws Exception {
    provider.rejectPublish = true;
    assertThrows(IOException.class, this::publish);
    assertEquals(1, provider.publishes);
    assertEquals(1, provider.deletes);
  }
}
