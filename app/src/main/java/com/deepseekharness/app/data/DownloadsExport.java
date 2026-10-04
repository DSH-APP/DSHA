package com.deepseekharness.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupControl;
import com.deepseekharness.app.backup.DocumentStreams;
import com.deepseekharness.app.backup.VerifiedFilePublication;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Download/DSHA 导出：校验已写入的实际 URI，成功后再发布。 */
public final class DownloadsExport {
  private DownloadsExport() {}

  public static final class Result {
    public final Uri uri;
    public final String displayName;
    public final FileIntegrity.Result integrity;

    Result(Uri uri, String name, FileIntegrity.Result integrity) {
      this.uri = uri;
      this.displayName = name;
      this.integrity = integrity;
    }
  }

  public static Result write(Context context, File source, String name) throws Exception {
    return write(context, source, name, new BackupControl(null));
  }

  public static Result write(Context context, File source, String name, BackupControl control)
      throws Exception {
    control.check();
    if (name == null
        || name.isEmpty()
        || !name.equals(new File(name).getName())
        || name.contains("\\")
        || name.equals(".")
        || name.equals(".."))
      throw new IOException(com.deepseekharness.app.util.UiText.text("文件名无效"));
    BackupFileSystem fs = new AndroidBackupFileSystem();
    var before = fs.stat(source);
    if (!before.type.equals("FILE")) throw new IOException("EXPORT_SOURCE_TYPE");
    FileIntegrity.Result expected;
    try (InputStream in = fs.read(source, before)) {
      expected = FileIntegrity.copy(in, null, before.size, control::check);
    }
    control.check();
    if (Build.VERSION.SDK_INT >= 29)
      return media(context, fs, source, before, name, expected, control);
    return direct(fs, source, name, expected, control);
  }

  @android.annotation.TargetApi(29)
  private static Result media(
      Context context,
      BackupFileSystem fs,
      File source,
      BackupFileSystem.Node before,
      String name,
      FileIntegrity.Result expected,
      BackupControl control)
      throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
    values.put(
        MediaStore.MediaColumns.MIME_TYPE,
        com.deepseekharness.app.util.BackupFileNames.exportMimeType(name));
    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSHA/");
    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
    Uri uri =
        context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    if (uri == null) throw new IOException(com.deepseekharness.app.util.UiText.text("无法创建下载文件"));
    boolean published = false;
    try {
      try (InputStream in = fs.read(source, before);
          OutputStream out = DocumentStreams.output(context.getContentResolver(), uri, control)) {
        if (out == null)
          throw new IOException(com.deepseekharness.app.util.UiText.text("无法写入下载文件"));
        if (!expected.matches(FileIntegrity.copy(in, out, expected.size, control::check)))
          throw new IOException(com.deepseekharness.app.util.UiText.text("源文件在导出期间发生变化"));
      }
      try (InputStream in = DocumentStreams.input(context.getContentResolver(), uri, control)) {
        if (!expected.matches(FileIntegrity.copy(in, null, expected.size, control::check)))
          throw new IOException(com.deepseekharness.app.util.UiText.text("导出摘要校验失败"));
      }
      ContentValues ready = new ContentValues();
      ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
      if (context.getContentResolver().update(uri, ready, null, null) != 1)
        throw new IOException(com.deepseekharness.app.util.UiText.text("下载文件发布失败"));
      String actualName = name;
      try (Cursor cursor =
          DocumentStreams.query(
              context.getContentResolver(),
              uri,
              new String[] {MediaStore.MediaColumns.DISPLAY_NAME},
              control)) {
        if (cursor != null && cursor.moveToFirst()) actualName = cursor.getString(0);
      }
      published = true;
      return new Result(uri, actualName, expected);
    } finally {
      if (!published)
        try {
          context.getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
        }
    }
  }

  @SuppressWarnings("deprecation")
  private static Result direct(
      BackupFileSystem fs,
      File source,
      String name,
      FileIntegrity.Result expected,
      BackupControl control)
      throws Exception {
    File downloads =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            .getCanonicalFile();
    if (!fs.stat(downloads).type.equals("DIRECTORY"))
      throw new IOException(com.deepseekharness.app.util.UiText.text("无法创建 Download/DSHA，请检查存储权限"));
    File dir = fs.child(downloads, "DSHA");
    if (fs.stat(dir).type.equals("MISSING")) fs.directory(dir);
    if (!fs.stat(dir).type.equals("DIRECTORY")) throw new IOException("EXPORT_DIRECTORY_TYPE");
    File target = fs.child(dir, name);
    if (!fs.stat(target).type.equals("MISSING")) {
      if (name.matches("DSHA-data-v5-[a-f0-9-]{36}\\.tar\\.gz"))
        target = fs.child(dir, com.deepseekharness.app.util.BackupFileNames.portableName());
      else {
        int dot = name.indexOf('.');
        target =
            new File(
                dir,
                (dot < 0 ? name : name.substring(0, dot))
                    + "-"
                    + UUID.randomUUID().toString().substring(0, 8)
                    + (dot < 0 ? "" : name.substring(dot)));
      }
    }
    VerifiedFilePublication.publish(fs, source, target, expected, control);
    return new Result(Uri.fromFile(target), target.getName(), expected);
  }
}
