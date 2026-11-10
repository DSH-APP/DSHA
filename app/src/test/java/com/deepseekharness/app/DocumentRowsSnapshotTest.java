package com.deepseekharness.app;

import static org.junit.Assert.*;

import android.app.Application;
import android.database.Cursor;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import java.io.File;
import java.io.IOException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(DocumentRowsSnapshotTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = Application.class)
public final class DocumentRowsSnapshotTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private Object[] row(String name, long size) {
    return new Object[] {"linux/ubuntu/root/" + name, "text/plain", name, 123L, 0, size};
  }

  @Test
  public void largeDirectoryKeepsAllRowsAndSupportsRandomPages() throws Exception {
    File cache = temporary.newFolder();
    Cursor cursor;
    try (DocumentRowsSnapshot snapshot = new DocumentRowsSnapshot(cache)) {
      for (int i = 19999; i >= 0; i--)
        snapshot.add(row(String.format(java.util.Locale.ROOT, "file-%05d-作品.txt", i), i));
      cursor = snapshot.finish(null, null);
    }
    try (cursor) {
      assertEquals(20000, cursor.getCount());
      assertTrue(cursor.moveToPosition(19800));
      assertEquals("file-19800-作品.txt", cursor.getString(2));
      assertEquals(19800L, cursor.getLong(5));
      assertTrue(cursor.moveToFirst());
      assertEquals("file-00000-作品.txt", cursor.getString(2));
      assertTrue(cursor.moveToLast());
      assertEquals("file-19999-作品.txt", cursor.getString(2));
      assertTrue(cache.list().length > 0);
    }
    assertArrayEquals(new String[0], cache.list());
    cursor.close(); // A redundant client close must not release another active query's slot.
  }

  @Test
  public void projectionsPreserveOrderNullsAndDoNotInterpretSql() throws Exception {
    File cache = temporary.newFolder();
    String[] projection = {"_size", "unknown\"; DROP TABLE documents;--", "document_id", "_size"};
    try (DocumentRowsSnapshot snapshot = new DocumentRowsSnapshot(cache)) {
      snapshot.add(row("quoted\".txt", 9));
      try (Cursor cursor = snapshot.finish(projection, null)) {
        projection[0] = "changed";
        assertEquals("_size", cursor.getColumnName(0));
        assertTrue(cursor.moveToFirst());
        assertEquals(9L, cursor.getLong(0));
        assertTrue(cursor.isNull(1));
        assertEquals("linux/ubuntu/root/quoted\".txt", cursor.getString(2));
        assertEquals(9L, cursor.getLong(3));
      }
    }
    assertArrayEquals(new String[0], cache.list());
  }

  @Test
  public void cancellationBeforeSortReleasesSnapshotAndItsFiles() throws Exception {
    File cache = temporary.newFolder();
    CancellationSignal signal = new CancellationSignal();
    signal.cancel();
    try (DocumentRowsSnapshot snapshot = new DocumentRowsSnapshot(cache)) {
      snapshot.add(row("cancel.txt", 0));
      assertThrows(OperationCanceledException.class, () -> snapshot.finish(null, signal));
    }
    assertArrayEquals(new String[0], cache.list());
  }

  @Test
  public void abandonedBuildReleasesResourcesWithoutDeletingAnotherSnapshot() throws Exception {
    File cache = temporary.newFolder();
    try (DocumentRowsSnapshot owner = new DocumentRowsSnapshot(cache)) {
      owner.add(row("owned.txt", 1));
      try (Cursor cursor = owner.finish(null, null)) {
        File[] original = cache.listFiles();
        try (DocumentRowsSnapshot failed = new DocumentRowsSnapshot(cache)) {
          assertThrows(IllegalArgumentException.class, () -> failed.add(new Object[0]));
        }
        for (File file : original) assertTrue(file.exists());
        assertTrue(cursor.moveToFirst());
        assertEquals("owned.txt", cursor.getString(2));
      }
    }
    assertArrayEquals(new String[0], cache.list());
  }

  @Test
  public void constructorErrorReleasesActivePermit() throws Exception {
    File cache = temporary.newFolder();
    for (int i = 0; i < 5; i++) {
      assertThrows(
          AssertionError.class,
          () ->
              new DocumentRowsSnapshot(
                  cache,
                  file -> {
                    throw new AssertionError("sqlite open failed");
                  }));
    }
    assertArrayEquals(new String[0], cache.list());
  }

  @Test
  public void activeQueryLimitIsReleasedByClientCloseAndConstructorFailure() throws Exception {
    File cache = temporary.newFolder();
    assertThrows(IOException.class, () -> new DocumentRowsSnapshot(new File(cache, "missing")));
    Cursor[] cursors = new Cursor[4];
    try {
      for (int i = 0; i < cursors.length; i++) {
        try (DocumentRowsSnapshot snapshot = new DocumentRowsSnapshot(cache)) {
          snapshot.add(row("file" + i, i));
          cursors[i] = snapshot.finish(null, null);
        }
      }
      IOException busy = assertThrows(IOException.class, () -> new DocumentRowsSnapshot(cache));
      assertEquals("DOCUMENT_LIST_BUSY", busy.getMessage());
      cursors[0].close();
      cursors[0].close();
      try (DocumentRowsSnapshot released = new DocumentRowsSnapshot(cache)) {
        released.add(row("available", 0));
        assertThrows(IOException.class, () -> new DocumentRowsSnapshot(cache));
      }
    } finally {
      for (Cursor cursor : cursors) if (cursor != null) cursor.close();
    }
    assertArrayEquals(new String[0], cache.list());
  }
}
