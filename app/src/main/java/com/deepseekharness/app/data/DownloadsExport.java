package com.deepseekharness.app.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import com.deepseekharness.app.util.FileIntegrity;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Download/DSHA 导出：校验已写入的实际 URI，成功后再发布。 */
public final class DownloadsExport {
    private DownloadsExport() { }
    public static final class Result {
        public final Uri uri;
        public final String displayName;
        public final FileIntegrity.Result integrity;
        Result(Uri uri, String name, FileIntegrity.Result integrity) {
            this.uri = uri; this.displayName = name; this.integrity = integrity;
        }
    }
    public static Result write(Context context, File source, String name) throws Exception {
        if (name == null || name.isEmpty() || !name.equals(new File(name).getName())
                || name.contains("\\") || name.equals(".") || name.equals("..")) throw new IOException(com.deepseekharness.app.util.UiText.text("文件名无效"));
        FileIntegrity.Result expected;
        try (InputStream in = new FileInputStream(source)) { expected = FileIntegrity.copy(in, null, source.length()); }
        if (Build.VERSION.SDK_INT >= 29) return media(context, source, name, expected);
        return direct(source, name, expected);
    }
    @android.annotation.TargetApi(29)
    private static Result media(Context context, File source, String name, FileIntegrity.Result expected) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, name.endsWith(".txt") ? "text/plain" : name.endsWith(".zip") ? "application/zip" : "application/gzip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DSHA/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException(com.deepseekharness.app.util.UiText.text("无法创建下载文件"));
        boolean published = false;
        try {
            try (InputStream in = new FileInputStream(source); OutputStream out = context.getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IOException(com.deepseekharness.app.util.UiText.text("无法写入下载文件"));
                if (!expected.matches(FileIntegrity.copy(in, out, expected.size))) throw new IOException(com.deepseekharness.app.util.UiText.text("源文件在导出期间发生变化"));
            }
            try (InputStream in = context.getContentResolver().openInputStream(uri)) {
                if (!expected.matches(FileIntegrity.copy(in, null, expected.size))) throw new IOException(com.deepseekharness.app.util.UiText.text("导出摘要校验失败"));
            }
            ContentValues ready = new ContentValues();
            ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
            if (context.getContentResolver().update(uri, ready, null, null) != 1) throw new IOException(com.deepseekharness.app.util.UiText.text("下载文件发布失败"));
            String actualName = name;
            try (Cursor cursor = context.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) actualName = cursor.getString(0);
            }
            published = true;
            return new Result(uri, actualName, expected);
        } finally {
            if (!published) try { context.getContentResolver().delete(uri, null, null); } catch (Exception ignored) { }
        }
    }
    @SuppressWarnings("deprecation")
    private static Result direct(File source, String name, FileIntegrity.Result expected) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DSHA");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法创建 Download/DSHA，请检查存储权限"));
        File target = new File(dir, name);
        if (target.exists()) {
            int dot = name.indexOf('.');
            target = new File(dir, (dot < 0 ? name : name.substring(0, dot)) + "-" + UUID.randomUUID().toString().substring(0, 8) + (dot < 0 ? "" : name.substring(dot)));
        }
        File part = new File(dir, ".dsha-export-" + UUID.randomUUID() + ".part");
        try {
            try (InputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(part)) {
                if (!expected.matches(FileIntegrity.copy(in, out, expected.size))) throw new IOException(com.deepseekharness.app.util.UiText.text("源文件在导出期间发生变化"));
                out.getFD().sync();
            }
            try (InputStream in = new FileInputStream(part)) {
                if (!expected.matches(FileIntegrity.copy(in, null, expected.size))) throw new IOException(com.deepseekharness.app.util.UiText.text("导出摘要校验失败"));
            }
            if (target.exists() || !part.renameTo(target)) throw new IOException(com.deepseekharness.app.util.UiText.text("下载文件提交失败，已有备份已保留"));
            return new Result(Uri.fromFile(target), target.getName(), expected);
        } finally { if (part.exists()) part.delete(); }
    }
}
