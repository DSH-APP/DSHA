package com.deepseekharness.app.backup;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.deepseekharness.app.core.HarnessController;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 只读扫描旧版 Download/DSHA tar 备份。
 *
 * <p>Android 11+ 卸载重装后，旧 MediaStore 行可能不再归属于当前安装，
 * 因此「查不到」不能直接等同于「没有备份」。调用方用 {@link #canSeeAllFiles()}
 * 区分这两种情况，并始终给用户留下 SAF 手动选择入口。</p>
 */
public final class ExternalBackupScanner {
    private ExternalBackupScanner() { }

    public static final class Candidate {
        public final String name;
        public final Uri uri;
        public final File file;
        public final long modified;
        public final long bytes;

        Candidate(String name, Uri uri, File file, long modified, long bytes) {
            this.name = name == null ? "" : name;
            this.uri = uri;
            this.file = file;
            this.modified = modified;
            this.bytes = bytes;
        }

        public String describe() {
            String size = bytes > 0 ? com.deepseekharness.app.util.Fmt.bytes(bytes) : "大小未知";
            String time = modified > 0
                    ? java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(modified))
                    : "时间未知";
            return name + "\n" + time + " · " + size;
        }
    }

    public static final class Scan {
        public final Candidate best;
        public final int readable;
        public final int unreadable;

        Scan(Candidate best, int readable, int unreadable) {
            this.best = best;
            this.readable = readable;
            this.unreadable = unreadable;
        }

        public int total() { return readable + unreadable; }
    }

    /** 接受 MediaStore 同名去重产生的「.tar (1).gz」形式。 */
    public static boolean looksLikeBackupName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        if (!lower.startsWith("dsha-backup-") && !lower.startsWith("dsha-migration-")) return false;
        String normalized = lower.replaceAll("\\s*\\(\\d+\\)", "");
        return normalized.endsWith(".tar.gz") || normalized.endsWith(".tgz") || normalized.endsWith(".tar");
    }

    public static boolean canSeeAllFiles() {
        if (Build.VERSION.SDK_INT < 30) return true;
        try { return Environment.isExternalStorageManager(); }
        catch (Throwable ignored) { return false; }
    }

    public static Scan scan(Context context) {
        Context app = context.getApplicationContext();
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DSHA");
        LinkedHashMap<String, Candidate> found = new LinkedHashMap<>();
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                String path = Environment.DIRECTORY_DOWNLOADS;
                String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? OR "
                        + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                String[] args = {path + "/DSHA/", path + "/DSHA"};
                ContentResolver resolver = app.getContentResolver();
                try (Cursor cursor = resolver.query(collection,
                        new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME,
                                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE},
                        selection, args, null)) {
                    if (cursor != null) while (cursor.moveToNext()) {
                        String name = cursor.getString(1);
                        if (!looksLikeBackupName(name)) continue;
                        Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(0));
                        long modified = cursor.isNull(2) ? 0L : cursor.getLong(2) * 1000L;
                        long bytes = cursor.isNull(3) ? 0L : cursor.getLong(3);
                        found.put(name, new Candidate(name, uri, new File(directory, name), modified, bytes));
                    }
                }
            } catch (Throwable ignored) { }
        }
        try {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) {
                if (!file.isFile() || !looksLikeBackupName(file.getName()) || found.containsKey(file.getName())) continue;
                found.put(file.getName(), new Candidate(file.getName(), null, file, file.lastModified(), file.length()));
            }
        } catch (Throwable ignored) { }

        Candidate best = null; int readable = 0; int unreadable = 0;
        for (Candidate candidate : found.values()) {
            if (canOpen(app, candidate)) {
                readable++;
                if (best == null || candidate.modified > best.modified) best = candidate;
            } else unreadable++;
        }
        return new Scan(best, readable, unreadable);
    }

    private static boolean canOpen(Context context, Candidate candidate) {
        if (candidate.uri != null) {
            try (InputStream input = context.getContentResolver().openInputStream(candidate.uri)) {
                if (input != null && input.read() != -1) return true;
            } catch (Throwable ignored) { }
        }
        if (candidate.file != null) {
            try (InputStream input = new FileInputStream(candidate.file)) {
                return input.read() != -1;
            } catch (Throwable ignored) { }
        }
        return false;
    }

    /** 只认用户数据本体，不能把离线包自带的版本标记当成已有会话。 */
    public static boolean hasUserData(HarnessController controller) {
        try {
            File root = controller.proot().getRootfsDir();
            File dsh = new File(root, "root/.dsh");
            String[] probes = {"sessions", "storages", "settings.yaml", "settings.json",
                    ".credentials.yaml", ".credentials", ".dsha-apikey"};
            for (String name : probes) {
                File file = new File(dsh, name);
                if (file.isDirectory()) {
                    String[] children = file.list();
                    if (children != null && children.length > 0) return true;
                } else if (file.isFile() && file.length() > 0) return true;
            }
            File files = controller.proot().getRootfsDir().getParentFile().getParentFile();
            File stable = new File(files, "user-data-v5/dsh");
            for (String name : probes) {
                File file = new File(stable, name);
                if (file.isDirectory()) {
                    String[] children = file.list();
                    if (children != null && children.length > 0) return true;
                } else if (file.isFile() && file.length() > 0) return true;
            }
            return false;
        } catch (Throwable unreadable) {
            // 判不清时宁可不自动恢复，避免覆盖未知用户数据。
            return true;
        }
    }
}
