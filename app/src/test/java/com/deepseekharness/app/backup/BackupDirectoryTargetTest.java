package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.provider.DocumentsContract;
import com.deepseekharness.app.BuildConfig;
import java.io.IOException;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContentResolver;

/** 真实 DocumentsContract/ContentResolver 配合隔离 Provider，不接触共享存储。 */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = BuildConfig.LOW_ANDROID ? 23 : 34,
    manifest = Config.NONE,
    application = android.app.Application.class)
public final class BackupDirectoryTargetTest {
  private static final String AUTH = "test.dsha.backup.directory";
  private static final String TREE = "content://" + AUTH + "/tree/root";
  private static final String NAME = "DSHA-data-v5-11111111-2222-3333-4444-555555555555.tar.gz";

  public static final class Provider extends ContentProvider {
    int creates, deletes, queries;
    boolean writable = true, wrongName, nonempty, returnParent;
    final List<Uri> deleted = new ArrayList<>();

    public boolean onCreate() {
      return true;
    }

    @Override
    public Cursor query(Uri uri, String[] columns, String selection, String[] args, String order) {
      queries++;
      MatrixCursor c = new MatrixCursor(columns);
      Object[] row = new Object[columns.length];
      for (int i = 0; i < columns.length; i++)
        row[i] =
            switch (columns[i]) {
              case DocumentsContract.Document.COLUMN_MIME_TYPE ->
                  DocumentsContract.Document.MIME_TYPE_DIR;
              case DocumentsContract.Document.COLUMN_FLAGS ->
                  writable ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE : 0;
              case android.provider.OpenableColumns.DISPLAY_NAME ->
                  wrongName ? "existing-user-backup.tar.gz" : NAME;
              case android.provider.OpenableColumns.SIZE -> nonempty ? 34L : 0L;
              default -> null;
            };
      c.addRow(row);
      return c;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
      Bundle out = new Bundle();
      if (method.equals("android:createDocument")) {
        creates++;
        out.putParcelable(
            "uri",
            DocumentsContract.buildDocumentUriUsingTree(
                Uri.parse(TREE), returnParent ? "root" : "new-" + creates));
      } else if (method.equals("android:deleteDocument")) {
        deletes++;
        deleted.add(extras.getParcelable("uri"));
      } else throw new IllegalArgumentException(method);
      return out;
    }

    public String getType(Uri uri) {
      return "application/gzip";
    }

    public Uri insert(Uri uri, ContentValues values) {
      throw new UnsupportedOperationException();
    }

    public int delete(Uri uri, String selection, String[] args) {
      throw new UnsupportedOperationException();
    }

    public int update(Uri uri, ContentValues values, String selection, String[] args) {
      throw new UnsupportedOperationException();
    }
  }

  Provider provider;

  @Before
  public void register() {
    assertEquals(BuildConfig.LOW_ANDROID ? 23 : 34, Build.VERSION.SDK_INT);
    provider = new Provider();
    ProviderInfo info = new ProviderInfo();
    info.authority = AUTH;
    info.exported = true;
    info.grantUriPermissions = true;
    provider.attachInfo(RuntimeEnvironment.getApplication(), info);
    ShadowContentResolver.registerProviderInternal(AUTH, provider);
  }

  private BackupDirectoryTarget create() throws IOException {
    return BackupDirectoryTarget.create(
        RuntimeEnvironment.getApplication().getContentResolver(),
        Uri.parse(TREE),
        NAME,
        new BackupControl(null));
  }

  @Test
  public void createsUniqueNewLeafWithoutDirectoryEnumerationAndDiscardsOnlyThatLeaf()
      throws Exception {
    var target = create();
    assertEquals(1, provider.creates);
    assertEquals(NAME, target.name);
    target.discard();
    target.discard();
    assertEquals(1, provider.deletes);
    assertEquals(List.of(target.uri), provider.deleted);
  }

  @Test
  public void completedWriteIsRetainedEvenWhenLaterStateReportingFails() throws Exception {
    var target = create();
    target.retain();
    target.discard();
    assertEquals(0, provider.deletes);
  }

  @Test
  public void cancelledBeforeCreationNeverTouchesProvider() {
    BackupControl c = new BackupControl(null);
    c.cancel();
    assertThrows(
        IOException.class,
        () ->
            BackupDirectoryTarget.create(
                RuntimeEnvironment.getApplication().getContentResolver(),
                Uri.parse(TREE),
                NAME,
                c));
    assertEquals(0, provider.creates);
    assertEquals(0, provider.queries);
    assertEquals(0, provider.deletes);
  }

  @Test
  public void revokedOrReadOnlyDirectoryCannotCreateAFile() {
    provider.writable = false;
    assertThrows(IOException.class, this::create);
    assertEquals(0, provider.creates);
    assertEquals(0, provider.deletes);
  }

  @Test
  public void providerReturningAnotherNameCannotCauseAnExistingBackupDeletion() {
    provider.wrongName = true;
    assertThrows(IOException.class, this::create);
    assertEquals(0, provider.deletes);
  }

  @Test
  public void providerReturningNonemptyObjectCannotOverwriteOrDeleteIt() {
    provider.nonempty = true;
    assertThrows(IOException.class, this::create);
    assertEquals(0, provider.deletes);
  }

  @Test
  public void providerReturningDirectoryItselfNeverGetsDeleted() {
    provider.returnParent = true;
    assertThrows(IOException.class, this::create);
    assertEquals(0, provider.deletes);
  }
}
