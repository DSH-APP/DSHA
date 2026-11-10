package com.deepseekharness.app.transfer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import androidx.core.content.FileProvider;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.util.FileIntegrity;
import java.io.*;

/** Fixed-buffer publication: pending destination, copy, reopen/hash, then make visible. */
final class TransferPublisher {
  record Result(Uri uri, String name, String mime, String location) {}

  interface Journal {
    void pending(Uri uri, String kind) throws Exception;

    /** Called while holding the engine gate, after the destination has been committed. */
    void complete(Result result) throws Exception;
  }

  private TransferPublisher() {}

  static void publish(
      Context context,
      File source,
      TransferArtifact artifact,
      String destination,
      String taskId,
      Uri target,
      BackupControl control,
      Object gate,
      Journal journal)
      throws Exception {
    if ("share".equals(destination)) {
      share(context, source, artifact, taskId, control, gate, journal);
      return;
    }
    String mime = artifact.mime;
    boolean gallery = "gallery".equals(destination);
    if (gallery) {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      try (InputStream in = TransferFiles.read(source)) {
        BitmapFactory.decodeStream(in, null, bounds);
      }
      if (bounds.outWidth <= 0
          || bounds.outHeight <= 0
          || bounds.outMimeType == null
          || !bounds.outMimeType.startsWith("image/")) throw new IOException("FILE_NOT_REAL_IMAGE");
      mime = bounds.outMimeType;
    }
    Uri pending;
    boolean media = !"saveas".equals(destination);
    if (media) {
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, artifact.name);
      values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
      values.put(
          MediaStore.MediaColumns.RELATIVE_PATH,
          (gallery ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS) + "/DSHA/");
      values.put(MediaStore.MediaColumns.IS_PENDING, 1);
      pending =
          context
              .getContentResolver()
              .insert(
                  gallery
                      ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                      : MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                  values);
      if (pending == null) throw new IOException("FILE_DESTINATION_CREATE_FAILED");
    } else {
      if (target == null || !"content".equals(target.getScheme()))
        throw new IOException("FILE_SAVE_TARGET_REQUIRED");
      pending = target;
    }
    // Store the exact row before opening the stream; interruption never broad-cleans Downloads.
    try {
      journal.pending(pending, media ? "media" : "saf");
    } catch (Exception failure) {
      if (media)
        try {
          context.getContentResolver().delete(pending, null, null);
        } catch (Exception ignored) {
        }
      throw failure;
    }
    try (InputStream in = TransferFiles.read(source);
        OutputStream out = DocumentStreams.output(context.getContentResolver(), pending, control)) {
      require(artifact, FileIntegrity.copy(in, out, artifact.bytes, control::check));
    }
    try (InputStream in = DocumentStreams.input(context.getContentResolver(), pending, control)) {
      require(artifact, FileIntegrity.copy(in, null, artifact.bytes, control::check));
    }
    String actualName = artifact.name;
    try (Cursor cursor =
        DocumentStreams.query(
            context.getContentResolver(),
            pending,
            new String[] {MediaStore.MediaColumns.DISPLAY_NAME},
            control)) {
      if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
        actualName = cursor.getString(0);
    }
    synchronized (gate) {
      control.check();
      if (media) {
        ContentValues ready = new ContentValues();
        ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (context.getContentResolver().update(pending, ready, null, null) != 1)
          throw new IOException("FILE_PUBLICATION_FAILED");
      }
      journal.complete(
          new Result(
              pending,
              actualName,
              mime,
              media ? (gallery ? "Pictures/DSHA" : "Download/DSHA") : "所选文档"));
    }
  }

  private static void share(
      Context context,
      File source,
      TransferArtifact artifact,
      String taskId,
      BackupControl control,
      Object gate,
      Journal journal)
      throws Exception {
    File root = TransferFiles.directory(context.getFilesDir().getCanonicalFile(), "transfers");
    File dir = TransferFiles.directory(TransferFiles.directory(root, "share"), taskId);
    File staged = TransferFiles.FS.child(TransferFiles.directory(root, taskId), "share-pending");
    File target = new File(dir, artifact.name);
    if (TransferFiles.FS.stat(target).type.equals("FILE")) {
      try (InputStream in = TransferFiles.read(target)) {
        require(artifact, FileIntegrity.copy(in, null, artifact.bytes, control::check));
      }
      synchronized (gate) {
        control.check();
        journal.complete(
            new Result(
                FileProvider.getUriForFile(context, context.getPackageName() + ".updates", target),
                target.getName(),
                artifact.mime,
                "应用分享缓存"));
      }
      return;
    }
    try (InputStream in = TransferFiles.read(source);
        OutputStream out = TransferFiles.write(staged, 0)) {
      require(artifact, FileIntegrity.copy(in, out, artifact.bytes, control::check));
    }
    try (InputStream in = TransferFiles.read(staged)) {
      require(artifact, FileIntegrity.copy(in, null, artifact.bytes, control::check));
    }
    synchronized (gate) {
      control.check();
      TransferFiles.FS.move(staged, target);
      TransferFiles.FS.syncDirectory(dir);
      Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".updates", target);
      journal.complete(new Result(uri, target.getName(), artifact.mime, "应用分享缓存"));
    }
  }

  static void require(TransferArtifact artifact, FileIntegrity.Result result) throws IOException {
    if (result.size != artifact.bytes || !result.sha256.equals(artifact.sha256))
      throw new IOException("FILE_SHA256_MISMATCH");
  }
}
