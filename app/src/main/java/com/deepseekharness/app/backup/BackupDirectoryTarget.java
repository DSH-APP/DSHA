package com.deepseekharness.app.backup;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;

/** 仅管理本次新建的 SAF 文件，不遍历、覆盖或清理备份目录。 */
public final class BackupDirectoryTarget {
  public final Uri uri;
  public final String name;
  private final ContentResolver resolver;
  private boolean retained;
  private boolean owned, discarded;

  private BackupDirectoryTarget(ContentResolver resolver, Uri uri, String name) {
    this.resolver = resolver;
    this.uri = uri;
    this.name = name;
  }

  public static BackupDirectoryTarget create(
      ContentResolver resolver, Uri tree, String name, BackupControl control) throws IOException {
    if (tree == null
        || !"content".equals(tree.getScheme())
        || !androidx.core.provider.DocumentsContractCompat.isTreeUri(tree)
        || name == null
        || !name.matches("DSHA-data-v5-[a-f0-9-]{36}\\.tar\\.gz"))
      throw new IOException("SAF_TREE_REQUIRED");
    control.check();
    Uri parent =
        DocumentsContract.buildDocumentUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree));
    try (Cursor c =
        DocumentStreams.query(
            resolver,
            parent,
            new String[] {
              DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS
            },
            control)) {
      if (c == null
          || !c.moveToFirst()
          || !DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(0))
          || (c.getInt(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0)
        throw new IOException("SAF_DIRECTORY_NOT_WRITABLE");
    }
    Uri created;
    try {
      created = DocumentsContract.createDocument(resolver, parent, "application/gzip", name);
    } catch (java.io.FileNotFoundException | SecurityException error) {
      throw new IOException("SAF_WRITE_FAILED", error);
    }
    if (created == null) throw new IOException("SAF_WRITE_FAILED");
    if (!tree.getAuthority().equals(created.getAuthority()) || created.equals(parent))
      throw new IOException("DESTINATION_NOT_NEW");
    BackupDirectoryTarget target = new BackupDirectoryTarget(resolver, created, name);
    try {
      control.check();
      try (Cursor c =
          DocumentStreams.query(
              resolver,
              created,
              new String[] {
                android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE
              },
              control)) {
        if (c == null
            || !c.moveToFirst()
            || !name.equals(c.getString(0))
            || !c.isNull(1) && c.getLong(1) != 0) throw new IOException("DESTINATION_NOT_NEW");
      }
      target.owned = true;
      return target;
    } catch (IOException | RuntimeException error) {
      target.discard();
      throw error;
    }
  }

  /** 写完但无法读回时保留文件，并由作业明确显示未验证状态。 */
  public void retain() {
    retained = true;
  }

  public void discard() {
    if (retained || !owned || discarded) return;
    discarded = true;
    try {
      DocumentsContract.deleteDocument(resolver, uri);
    } catch (Exception ignored) {
    }
  }
}
