package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.deepseekharness.app.util.WebTransferPolicy;
import java.io.*;
import java.util.*;

/** 复制用户明确选择的内容，不接受任意本地路径；两种浏览内核共用上限。 */
public final class WebUploads {
    private WebUploads() { }
    public static Intent fallback(Intent original) {
        String alternative = Intent.ACTION_GET_CONTENT.equals(original.getAction()) ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT;
        return new Intent(original).setAction(alternative).addCategory(Intent.CATEGORY_OPENABLE)
                .setComponent(null).setPackage(null).setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    }
    public static ArrayList<File> copy(Context context, List<Uri> uris) throws IOException {
        ArrayList<File> files = new ArrayList<>();
        try (com.deepseekharness.app.core.RuntimeTasks ignored = com.deepseekharness.app.core.RuntimeTasks.begin()) {
            if (uris.size() > WebTransferPolicy.UPLOAD_COUNT) throw new IOException("一次最多上传 20 个文件");
            long total = 0;
            File parent = new File(context.getCacheDir(), "web-uploads");
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建上传缓存");
            for (Uri uri : uris) {
                if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("不支持的文件来源，请通过系统文件应用选择");
                String name = "upload.bin";
                try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)) {
                    if (cursor != null && cursor.moveToFirst()) name = WebTransferPolicy.fileName(cursor.getString(0));
                }
                File folder = new File(parent,UUID.randomUUID().toString());
                if (!folder.mkdir()) throw new IOException("无法创建上传缓存");
                File file = new File(folder,name); files.add(file);
                try (InputStream in = context.getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(file)) {
                    if (in == null) throw new IOException("没有文件读取权限，请重新选择");
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) {
                        total += n;
                        if (total > WebTransferPolicy.UPLOAD_LIMIT) throw new IOException("本次上传超过 256 MiB");
                        out.write(buffer,0,n);
                    }
                }
            }
            return files;
        } catch (Exception error) {
            clean(files);
            throw error instanceof IOException ? (IOException) error : new IOException("无法读取文件，请检查授权后重新选择",error);
        }
    }
    public static void clean(List<File> files) {
        for (File file : files) { file.delete(); file.getParentFile().delete(); }
        files.clear();
    }
}
