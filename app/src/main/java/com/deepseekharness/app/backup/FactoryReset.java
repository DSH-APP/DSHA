package com.deepseekharness.app.backup;

import android.app.job.JobScheduler;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import com.deepseekharness.app.BackupManager;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** 用户明确确认后的完整格式化：在维护屏障内清理数据，并让当前进程直接返回欢迎页。 */
public final class FactoryReset {
    private static final int MAX_REMOVE_DEPTH = 512;
    private static final int MAX_RELIST_PASSES = 8;
    private static final long REPORT_INTERVAL = 128;
    private static final String[] KNOWN_PREFERENCES = {
            "deepseekharness", "dsha-data-task", "dsha_device_grants",
            "dsha_environment_upgrade", "dsha_automatic_backup", "dsha_log_exports",
            "dsha-updates", "dsha-install-link", "dsha_storage_cleanup",
            "dsha-install-diagnostics", "dsha-portable-settings"
    };
    private FactoryReset() { }

    public static final class Result {
        public final List<String> warnings;
        Result(List<String> warnings) { this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings)); }
        public boolean hasWarnings() { return !warnings.isEmpty(); }
    }

    /** 阶段名只在宿主内部流转，界面在 BackupTask 中按当前语言渲染。 */
    public static final String STAGE_PUBLIC = "FORMAT_PUBLIC";
    public static final String STAGE_RUNTIME = "FORMAT_RUNTIME";
    public static final String STAGE_PRIVATE = "FORMAT_PRIVATE";
    public static final String STAGE_CACHE = "FORMAT_CACHE";
    public static final String STAGE_SETTINGS = "FORMAT_SETTINGS";
    public static final String STAGE_FINISH = "FORMAT_FINISH";

    public static void eraseLegacyPublicData(BackupControl control) throws IOException {
        if (!BackupManager.isDataTaskOwner()) throw new IOException("FORMAT_REQUIRES_MAINTENANCE");
        eraseLegacyPublicData(new AndroidBackupFileSystem(),
                Environment.getExternalStorageDirectory().getCanonicalFile(), control);
    }

    static void eraseLegacyPublicData(BackupFileSystem fs, File storage,
                                      BackupControl control) throws IOException {
        BackupFileSystem.Node storageNode = fs.stat(storage);
        if (!storageNode.type.equals("DIRECTORY")) throw new IOException("FORMAT_STORAGE_UNAVAILABLE");
        File documents = new File(storage, "Documents");
        BackupFileSystem.Node documentsNode = fs.stat(documents);
        if (documentsNode.type.equals("MISSING")) return;
        if (!documentsNode.type.equals("DIRECTORY")) throw new IOException("FORMAT_DOCUMENTS_PATH");
        File legacy = new File(documents, "dshdata");
        if (fs.stat(legacy).type.equals("MISSING")) { control.report(STAGE_PUBLIC, 0, 0); return; }
        long[] removed = {0, 0};
        erase(fs, documents, legacy, control, STAGE_PUBLIC, removed, 0, false);
        control.report(STAGE_PUBLIC, removed[0], removed[1]);
        fs.syncDirectory(documents);
    }

    /** 外置公共目录权限和 FUSE 目录 fsync 不可靠；失败留下明确警告，但不挡住私有清理。 */
    static String eraseLegacyPublicDataBestEffort(BackupFileSystem fs, File storage,
                                                  BackupControl control) throws IOException {
        try { eraseLegacyPublicData(fs, storage, control); return ""; }
        catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException unavailable) { return "LEGACY_PUBLIC:" + BackupErrorCode.from(unavailable); }
    }

    /** 测试与应用内格式化共用：保留根目录，只删除其中内容，不跟随符号链接。 */
    static void eraseContents(BackupFileSystem fs, File root, BackupControl control,
                              String stage, long[] removed) throws IOException {
        BackupFileSystem.Node rootNode = fs.stat(root);
        if (rootNode.type.equals("MISSING")) return;
        if (!rootNode.type.equals("DIRECTORY")) throw new IOException("FORMAT_ROOT_TYPE");
        fs.prepareOwnedRemoval(root);
        for (int pass = 0; pass < MAX_RELIST_PASSES; pass++) {
            control.check();
            List<String> names = fs.list(root);
            for (String name : names) {
                safeName(name);
                erase(fs, root, new File(root, name), control, stage, removed, 0, true);
            }
            BackupFileSystem.Node after = fs.stat(root);
            if (!after.type.equals("DIRECTORY") || !rootNode.key.equals(after.key))
                throw new IOException("FORMAT_ROOT_CHANGED");
            if (!fs.list(root).isEmpty()) continue;
            control.report(stage, removed[0], removed[1]);
            fs.syncDirectory(root);
            if (fs.list(root).isEmpty()) return;
        }
        throw new IOException("FORMAT_DIRECTORY_BUSY");
    }

    static String eraseOptionalContents(BackupFileSystem fs, File root, BackupControl control,
                                        String stage, long[] removed) throws IOException {
        try { eraseContents(fs, root, control, stage, removed); return ""; }
        catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException unavailable) { return "EXTERNAL:" + BackupErrorCode.from(unavailable); }
    }

    /**
     * WebView、cache 与 code_cache 由 Android/渲染进程管理，即使业务写者已经停止，
     * 系统仍可能在删除窗口重建锁文件或缓存目录。它们不承载 DSHA 的会话、配置、
     * API Key 或插件；API 层数据清除完成后，残留的可再生文件只能形成明确警告，
     * 不能让核心数据已经清空的整次格式化停在 FILESYSTEM_39。
     */
    static String eraseRegenerableContents(BackupFileSystem fs, File root, BackupControl control,
                                            String stage, long[] removed) throws IOException {
        try { eraseContents(fs, root, control, stage, removed); return ""; }
        catch (InterruptedIOException cancelled) { throw cancelled; }
        catch (IOException unavailable) { return "REGENERABLE:" + BackupErrorCode.from(unavailable); }
    }

    private static void erase(BackupFileSystem fs, File protectedRoot, File target,
                              BackupControl control, String stage, long[] removed,
                              int depth, boolean prepareOwned) throws IOException {
        control.check();
        if (depth > MAX_REMOVE_DEPTH) throw new IOException("FORMAT_TREE_DEPTH");
        if (!target.getAbsolutePath().startsWith(protectedRoot.getAbsolutePath() + File.separator))
            throw new IOException("FORMAT_OUTSIDE_ROOT");
        BackupFileSystem.Node before = fs.stat(target);
        if (before.type.equals("MISSING")) return;
        if (before.type.equals("DIRECTORY")) {
            if (prepareOwned) fs.prepareOwnedRemoval(target);
            for (int pass = 0; pass < MAX_RELIST_PASSES; pass++) {
                for (String name : fs.list(target)) {
                    safeName(name);
                    erase(fs, protectedRoot, new File(target, name), control, stage,
                            removed, depth + 1, prepareOwned);
                }
                BackupFileSystem.Node after = fs.stat(target);
                if (!after.type.equals("DIRECTORY") || !before.key.equals(after.key))
                    throw new IOException("FORMAT_SOURCE_CHANGED");
                try { fs.delete(target); removed(removed, before, control, stage); return; }
                catch (IOException busy) {
                    if (!directoryNotEmpty(busy) || pass + 1 >= MAX_RELIST_PASSES) throw busy;
                    control.check();
                }
            }
            throw new IOException("FORMAT_DIRECTORY_BUSY");
        }
        // 文件和符号链接都只删除目录项本身；从不跟随链接到用户的其他目录。
        fs.delete(target);
        removed(removed, before, control, stage);
    }

    private static void removed(long[] removed, BackupFileSystem.Node before,
                                BackupControl control,String stage) throws IOException {
        removed[0]++;
        if (before.size > 0 && removed[1] <= Long.MAX_VALUE - before.size) removed[1] += before.size;
        if (removed[0] % REPORT_INTERVAL == 0) control.report(stage, removed[0], removed[1]);
    }

    private static boolean directoryNotEmpty(IOException error) {
        for (Throwable at = error; at != null; at = at.getCause()) {
            String message = at.getMessage();
            if (hasTypeNamed(at, "java.nio.file.DirectoryNotEmptyException")
                    || "FILESYSTEM_39".equals(message)
                    || "DIRECTORY_NOT_EMPTY".equals(message)) return true;
        }
        return false;
    }

    /** Low 版最低 API 23，不能让 API 26 的 java.nio.file 类型进入应用字节码。 */
    private static boolean hasTypeNamed(Throwable value, String expectedName) {
        for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
            if (expectedName.equals(type.getName())) return true;
        }
        return false;
    }

    private static void safeName(String name) throws IOException {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0)
            throw new IOException("FORMAT_ENTRY_NAME");
    }

    /**
     * 模拟“清除应用数据”，但不调用会杀死当前进程的 clearApplicationUserData。
     * 手动导出的 SAF/Downloads 归档与 Documents 中 DSHA 以外的个人内容不在这些根下。
     */
    public static Result eraseApplicationData(Context context, BackupControl control) throws IOException {
        if (!BackupManager.isDataTaskOwner()) throw new IOException("FORMAT_REQUIRES_MAINTENANCE");
        if (!AutomaticBackups.factoryResetSuspended()) throw new IOException("FORMAT_REQUIRES_BACKUP_QUIESCENCE");
        Context app = context.getApplicationContext();
        com.deepseekharness.app.core.UpdateEngine.get(app).stopForFactoryReset();
        stopBackgroundComponents(app);
        awaitMainThreadBarrier();
        com.deepseekharness.app.core.HarnessController.get(app).startupDiagnostics().drainForFactoryReset(10_000);

        List<String> warnings = new ArrayList<>();
        String web = clearWebViewMemory(app);
        if (!web.isEmpty()) warnings.add(web);

        control.report(STAGE_PUBLIC, 0, 0);
        String legacy = eraseLegacyPublicDataBestEffort(new AndroidBackupFileSystem(),
                Environment.getExternalStorageDirectory().getAbsoluteFile(), control);
        if (!legacy.isEmpty()) warnings.add(legacy);

        AndroidBackupFileSystem fs = new AndroidBackupFileSystem();
        long[] removed = {0, 0};
        control.report(STAGE_RUNTIME, 0, 0);
        eraseContents(fs, app.getFilesDir().getCanonicalFile(), control, STAGE_RUNTIME, removed);

        control.report(STAGE_PRIVATE, removed[0], removed[1]);
        File noBackup = app.getNoBackupFilesDir();
        if (noBackup != null) eraseContents(fs, noBackup.getCanonicalFile(), control, STAGE_PRIVATE, removed);
        for (File directory : unique(app.getExternalFilesDirs(null))) {
            String warning=eraseOptionalContents(fs,directory,control,STAGE_PRIVATE,removed);
            if(!warning.isEmpty())warnings.add("EXTERNAL_FILES:"+warning.substring("EXTERNAL:".length()));
        }

        control.report(STAGE_CACHE, removed[0], removed[1]);
        File cache = app.getCacheDir();
        if (cache != null) addRegenerableWarning(warnings, "CACHE", eraseRegenerableContents(
                fs, cache.getAbsoluteFile(), control, STAGE_CACHE, removed));
        File codeCache = app.getCodeCacheDir();
        if (codeCache != null) addRegenerableWarning(warnings, "CODE_CACHE", eraseRegenerableContents(
                fs, codeCache.getAbsoluteFile(), control, STAGE_CACHE, removed));
        for (File directory : unique(app.getExternalCacheDirs())) {
            String warning=eraseOptionalContents(fs,directory,control,STAGE_CACHE,removed);
            if(!warning.isEmpty())warnings.add("EXTERNAL_CACHE:"+warning.substring("EXTERNAL:".length()));
        }
        File webView = new File(app.getApplicationInfo().dataDir, "app_webview");
        addRegenerableWarning(warnings, "WEBVIEW_FILES", eraseRegenerableContents(
                fs, webView.getAbsoluteFile(), control, STAGE_CACHE, removed));

        control.report(STAGE_SETTINGS, removed[0], removed[1]);
        clearDatabases(app);
        clearPreferences(app);
        // 密文偏好已经同步清空；即使厂商 Keystore 暂时拒绝删除，旧密钥本身也无法还原凭据。
        new com.deepseekharness.app.data.KeyVault(app).clearForFactoryReset();
        if(!warnings.isEmpty()&&noBackup!=null){
            File retained=noBackup.getCanonicalFile();
            if(fs.stat(retained).type.equals("MISSING"))fs.directory(retained);
            fs.atomic(retained,"factory-reset-warnings.txt",String.join("\n",warnings).getBytes(StandardCharsets.UTF_8));
        }
        control.report(STAGE_FINISH, removed[0], removed[1]);
        return new Result(warnings);
    }

    private static void addRegenerableWarning(List<String> warnings,String root,String warning) {
        if (!warning.isEmpty()) warnings.add(root + ":" + warning.substring("REGENERABLE:".length()));
    }

    private static Set<File> unique(File[] directories) {
        Set<File> result = new LinkedHashSet<>();
        if (directories == null) return result;
        for (File directory : directories) if (directory != null) result.add(directory.getAbsoluteFile());
        return result;
    }

    private static String clearWebViewMemory(Context app) throws IOException {
        CountDownLatch finished=new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> failure=new java.util.concurrent.atomic.AtomicReference<>("");
        new android.os.Handler(android.os.Looper.getMainLooper()).post(()->{
            try{
                android.webkit.WebStorage.getInstance().deleteAllData();
                android.webkit.WebViewDatabase database=android.webkit.WebViewDatabase.getInstance(app);
                database.clearHttpAuthUsernamePassword();database.clearFormData();
                android.webkit.CookieManager cookies=android.webkit.CookieManager.getInstance();
                cookies.removeAllCookies(removed->{try{cookies.flush();}catch(RuntimeException error){failure.set("WEBVIEW_COOKIE_FLUSH");}finally{finished.countDown();}});
            }catch(RuntimeException error){failure.set("WEBVIEW_MEMORY_CLEAR");finished.countDown();}
        });
        try{if(!finished.await(10,TimeUnit.SECONDS))return "WEBVIEW_CLEAR_TIMEOUT";}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("FORMAT_WEBVIEW_INTERRUPTED");}
        return failure.get();
    }

    private static void awaitMainThreadBarrier() throws IOException {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IOException("FORMAT_MAIN_THREAD");
        CountDownLatch reached=new CountDownLatch(1);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(reached::countDown);
        try{if(!reached.await(10,TimeUnit.SECONDS))throw new IOException("FORMAT_MAIN_DRAIN_TIMEOUT");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new InterruptedIOException("FORMAT_MAIN_DRAIN_INTERRUPTED");}
    }

    private static void clearDatabases(Context app) throws IOException {
        String[] names = app.databaseList();
        if (names == null) return;
        for (String name : names) {
            safeName(name);
            if (!app.deleteDatabase(name) && app.getDatabasePath(name).exists())
                throw new IOException("FORMAT_DATABASE_DELETE");
        }
    }

    private static void clearPreferences(Context app) throws IOException {
        Set<String> names = new LinkedHashSet<>();
        java.util.Collections.addAll(names, KNOWN_PREFERENCES);
        File directory = new File(app.getApplicationInfo().dataDir, "shared_prefs");
        File[] existing = directory.listFiles();
        if (existing != null) for (File file : existing) {
            String name = file.getName();
            if (name.endsWith(".xml") && name.length() > 4) names.add(name.substring(0, name.length() - 4));
        }
        for (String name : names) {
            if (!app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit())
                throw new IOException("FORMAT_PREFERENCES_CLEAR");
        }
        com.deepseekharness.app.data.PortableSettings.resetForFreshStart(app);
    }

    private static void stopBackgroundComponents(Context app) {
        try { com.deepseekharness.app.LanProxyService.stop(); } catch (RuntimeException ignored) { }
        try { com.deepseekharness.app.OverlayController.resetForFreshStart(app); } catch (RuntimeException ignored) { }
        try { com.deepseekharness.app.AdbKeepAliveReceiver.cancel(app); } catch (RuntimeException ignored) { }
        try { app.stopService(new Intent(app, com.deepseekharness.app.HarnessService.class)); } catch (RuntimeException ignored) { }
        try { app.stopService(new Intent(app, com.deepseekharness.app.DeviceBridgeService.class)); } catch (RuntimeException ignored) { }
        try { app.stopService(new Intent(app, com.deepseekharness.app.UpdateDownloadService.class)); } catch (RuntimeException ignored) { }
        try { app.stopService(new Intent(app, DataProtectionService.class)); } catch (RuntimeException ignored) { }
        try {
            JobScheduler scheduler = app.getSystemService(JobScheduler.class);
            if (scheduler != null) scheduler.cancelAll();
        } catch (RuntimeException ignored) { }
    }
}
