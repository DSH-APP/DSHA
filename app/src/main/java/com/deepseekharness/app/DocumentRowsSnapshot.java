package com.deepseekharness.app;

import android.database.Cursor;
import android.database.CursorWrapper;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import android.os.CancellationSignal;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Semaphore;

/** A private, disk-backed directory snapshot; a large folder never becomes a MatrixCursor. */
final class DocumentRowsSnapshot implements AutoCloseable {
  private static final Semaphore ACTIVE = new Semaphore(4);
  static final String[] COLUMNS = {
    "document_id", "mime_type", "_display_name", "last_modified", "flags", "_size"
  };

  @FunctionalInterface
  interface DatabaseOpener {
    SQLiteDatabase open(File file);
  }

  private static final DatabaseOpener DEFAULT_DATABASE_OPENER =
      file -> SQLiteDatabase.openOrCreateDatabase(file, null);

  private final File file;
  private SQLiteDatabase database;
  private SQLiteStatement insert;
  private boolean transaction;
  private boolean handedOff;
  private boolean disposed;

  DocumentRowsSnapshot(File cache) throws IOException {
    this(cache, DEFAULT_DATABASE_OPENER);
  }

  DocumentRowsSnapshot(File cache, DatabaseOpener opener) throws IOException {
    if (!ACTIVE.tryAcquire()) throw new IOException("DOCUMENT_LIST_BUSY");
    try {
      file = File.createTempFile("dsha-document-list-", ".db", cache);
    } catch (IOException | RuntimeException | Error error) {
      ACTIVE.release();
      throw error;
    }
    try {
      database = opener.open(file);
      // Sorting can spill to disk, and the page cache does not scale with the folder.
      database.execSQL("PRAGMA temp_store=FILE");
      database.execSQL("PRAGMA cache_size=-256");
      database.execSQL(
          "CREATE TABLE documents (document_id TEXT, mime_type TEXT, _display_name TEXT,"
              + " last_modified INTEGER, flags INTEGER, _size INTEGER)");
      database.beginTransaction();
      transaction = true;
      insert = database.compileStatement("INSERT INTO documents VALUES (?, ?, ?, ?, ?, ?)");
    } catch (Throwable error) {
      try {
        dispose();
      } catch (Throwable cleanupError) {
        error.addSuppressed(cleanupError);
      }
      if (error instanceof Error) throw (Error) error;
      throw new IOException("DOCUMENT_LIST_STORAGE", error);
    }
  }

  void add(Object[] row) {
    if (row.length != COLUMNS.length) throw new IllegalArgumentException("DOCUMENT_COLUMNS");
    insert.clearBindings();
    for (int i = 0; i < row.length; i++) {
      Object value = row[i];
      if (value == null) insert.bindNull(i + 1);
      else if (value instanceof Number) insert.bindLong(i + 1, ((Number) value).longValue());
      else insert.bindString(i + 1, value.toString());
    }
    insert.executeInsert();
  }

  Cursor finish(String[] requested, CancellationSignal signal) {
    insert.close();
    insert = null;
    database.setTransactionSuccessful();
    database.endTransaction();
    transaction = false;
    String[] projection = (requested == null ? COLUMNS : requested).clone();
    StringBuilder sql = new StringBuilder("SELECT ");
    for (int i = 0; i < projection.length; i++) {
      if (i != 0) sql.append(", ");
      String column = projection[i];
      if (column == null) throw new IllegalArgumentException("DOCUMENT_COLUMN_NULL");
      String quoted = "\"" + column.replace("\"", "\"\"") + "\"";
      sql.append(Arrays.asList(COLUMNS).contains(column) ? quoted : "NULL AS " + quoted);
    }
    // SAF projections normally have at least one column. Preserve an empty projection too.
    if (projection.length == 0) sql.append("NULL AS _empty");
    sql.append(" FROM documents ORDER BY _display_name COLLATE NOCASE, _display_name");
    Cursor cursor = database.rawQuery(sql.toString(), null, signal);
    try {
      cursor.getCount(); // Finish the disk sort before releasing the provider's read lease.
    } catch (RuntimeException error) {
      cursor.close();
      throw error;
    }
    handedOff = true;
    return new CursorWrapper(cursor) {
      private boolean closed;

      @Override
      public String[] getColumnNames() {
        return projection.clone();
      }

      @Override
      public int getColumnCount() {
        return projection.length;
      }

      @Override
      public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
          super.close();
        } finally {
          dispose();
        }
      }
    };
  }

  @Override
  public void close() {
    if (!handedOff) dispose();
  }

  private synchronized void dispose() {
    if (disposed) return;
    disposed = true;
    try {
      if (insert != null) {
        insert.close();
        insert = null;
      }
    } finally {
      try {
        if (database != null) {
          try {
            if (transaction) database.endTransaction();
          } finally {
            transaction = false;
            database.close();
            database = null;
          }
        }
      } finally {
        // This exact file was created by this snapshot, outside exported document roots.
        try {
          if (!SQLiteDatabase.deleteDatabase(file) && file.exists())
            android.util.Log.w("DshaDocs", "DOCUMENT_LIST_CACHE_CLEANUP");
        } finally {
          ACTIVE.release();
        }
      }
    }
  }
}
