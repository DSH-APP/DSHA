package com.deepseekharness.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.system.Os;
import com.deepseekharness.app.core.RuntimeTasks;
import com.deepseekharness.app.util.Compat;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** 通过真实 ContentResolver / DocumentsContract 验证 MT 使用的树 URI 契约，兼容非调试自插桩包。 */
public final class DocumentsAudit extends Instrumentation {
    private static final String AUTH = "com.dsh.client.documents.audit";
    private ContentResolver resolver;
    private int assertions;
    @Override public void onCreate(Bundle args) { start(); }
    private void check(boolean okay, String message) { assertions++; if (!okay) throw new AssertionError(message); }
    private Uri uri(String tree, String id) {
        return DocumentsContract.buildDocumentUriUsingTree(DocumentsContract.buildTreeDocumentUri(AUTH, tree), id);
    }
    private Uri uri(String id) { return uri("root", id); }
    private void write(Uri uri, String mode, String text) throws Exception {
        try (OutputStream out = resolver.openOutputStream(uri, mode)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
    private String read(Uri uri) throws Exception {
        try (InputStream input = resolver.openInputStream(uri)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] bytes = new byte[256];
            int n; while ((n = input.read(bytes)) >= 0) out.write(bytes, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private void idle() throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + 5000;
        while (RuntimeTasks.isBusy() && android.os.SystemClock.elapsedRealtime() < end) Thread.sleep(20);
        check(!RuntimeTasks.isBusy(), "文件关闭后维护锁未释放");
    }
    @Override public void onStart() {
        Bundle result = new Bundle(); File fixture = new File(getTargetContext().getCacheDir(), "documents-audit-fixture");
        String folder = "linux/ubuntu/root/audit-" + java.util.UUID.randomUUID();
        try {
            resolver = getTargetContext().getContentResolver();
            check(!RuntimeTasks.isBusy(), "请等待当前运行任务完成后再进行文件测试");
            new File(fixture, folder).mkdirs();
            try (Cursor cursor = resolver.query(DocumentsContract.buildRootsUri(AUTH), new String[]{"mime_types", "document_id"}, null, null, null)) {
                check(cursor.moveToFirst() && "*/*".equals(cursor.getString(0)) && "root".equals(cursor.getString(1)), "根目录投影或 MIME 列类型错误");
            }
            Uri json = DocumentsContract.createDocument(resolver, uri(folder), "application/json", "灵儿.120516.json");
            check(json != null && DocumentsContract.getDocumentId(json).equals(folder + "/灵儿.120516.json"), "中文文件创建/旧路径编号错误");
            write(json, "w", "{\"名字\":\"灵儿\"}"); check(read(json).equals("{\"名字\":\"灵儿\"}"), "中文 JSON 读写不一致");
            write(json, "wa", "\n"); check(read(json).endsWith("\n"), "追加模式失败");
            write(json, "wt", "{}"); check(read(json).equals("{}"), "截断写入失败");
            try (ParcelFileDescriptor fd = resolver.openFileDescriptor(json, "rw")) {
                check(fd.getStatSize() == 2, "读写打开错误");
                check(RuntimeTasks.tryEnterMaintenance() == null, "文件还打开时允许替换环境");
            }
            idle();
            try (com.deepseekharness.app.util.RuntimeTaskRegistry.Maintenance gate = RuntimeTasks.tryEnterMaintenance()) {
                check(gate != null, "关闭后无法取得维护屏障");
                boolean blocked = false;
                try (ParcelFileDescriptor ignored = resolver.openFileDescriptor(json, "r")) { }
                catch (Exception expected) { blocked = true; }
                check(blocked, "维护期间仍能打开文件");
            }
            try (Cursor cursor = resolver.query(json, new String[]{"_display_name", "flags", "document_id"}, null, null, null)) {
                check(cursor.moveToFirst() && cursor.getString(0).equals("灵儿.120516.json"), "子集投影失败");
                int flags = cursor.getInt(1);
                check((flags & DocumentsContract.Document.FLAG_SUPPORTS_WRITE) != 0
                        && (flags & DocumentsContract.Document.FLAG_SUPPORTS_DELETE) != 0
                        && (flags & DocumentsContract.Document.FLAG_SUPPORTS_RENAME) != 0, "文件管理操作未声明");
            }
            Uri duplicate = DocumentsContract.createDocument(resolver, uri(folder), "application/json", "灵儿.120516.json");
            check(!json.equals(duplicate) && read(json).equals("{}"), "同名创建覆盖了已有文件");
            String renamedName = "重命名 100% " + new File(folder).getName() + ".json";
            Uri renamed = DocumentsContract.renameDocument(resolver, json, renamedName);
            check(renamed != null && read(renamed).equals("{}"), "重命名返回的 URI 不可读");
            String renamedId = DocumentsContract.getDocumentId(renamed);
            check(read(uri(folder, renamedId)).equals("{}"), "有效子目录树授权被拒绝");
            File guestLink = new File(fixture, folder + "/guest-link.json");
            Os.symlink("/root/" + folder.substring("linux/ubuntu/root/".length()) + "/" + renamedName, guestLink.getPath());
            check(read(uri(folder + "/guest-link.json")).equals("{}"), "guest 绝对链接打不开");
            File l2s = new File(fixture, "linux/ubuntu/.l2s/" + new File(folder).getName()); l2s.getParentFile().mkdirs();
            Compat.write(l2s, "l2s");
            Os.symlink(l2s.getAbsolutePath(), new File(fixture, folder + "/l2s-link").getPath());
            check(read(uri(folder + "/l2s-link")).equals("l2s"), "宿主 L2S 链接打不开");
            File sibling = new File(fixture, "linux/ubuntu/root/outside-" + new File(folder).getName()); Compat.write(sibling, "outside");
            Os.symlink("../" + sibling.getName(), new File(fixture, folder + "/outside-link").getPath());
            boolean denied = false;
            try { read(uri(folder, folder + "/outside-link")); } catch (Exception expected) { denied = true; }
            check(denied, "子目录授权被链接扩展到兄弟目录");
            denied = false;
            try { read(uri("../shared_prefs/preferences.xml")); } catch (Exception expected) { denied = true; }
            check(denied, "路径穿越未拒绝");
            Os.symlink("missing.json", new File(fixture, folder + "/dangling").getPath());
            check(DocumentsContract.deleteDocument(resolver, uri(folder + "/dangling")), "失效链接不能删除");
            CancellationSignal cancelled = new CancellationSignal(); cancelled.cancel(); denied = false;
            try (ParcelFileDescriptor ignored = resolver.openFileDescriptor(renamed, "wt", cancelled)) { }
            catch (android.os.OperationCanceledException expected) { denied = true; }
            check(denied && read(renamed).equals("{}"), "取消请求仍然截断文件");
            check(DocumentsContract.deleteDocument(resolver, uri(folder + "/guest-link.json")), "不能删除链接");
            check(read(renamed).equals("{}"), "删除链接误删目标");
            // 目录软链接环不能使搜索无限递归。
            Os.symlink(".", new File(fixture, folder + "/cycle").getPath());
            Bundle search = new Bundle(); search.putString(DocumentsContract.QUERY_ARG_DISPLAY_NAME, renamedName);
            try (Cursor cursor = resolver.query(DocumentsContract.buildSearchDocumentsUri(AUTH, "dsha-root", renamedName), null, search, null)) {
                check(cursor != null && cursor.getCount() == 1, "搜索没有限制重复的链接目录");
            }
            try (Cursor cursor = resolver.query(DocumentsContract.buildSearchDocumentsUri(AUTH, "dsha-root", ""), null, null, null, null)) {
                check(cursor != null && cursor.getCount() == 0, "空搜索参数不能安全返回");
            }
            idle();
            check(DocumentsContract.deleteDocument(resolver, uri(folder)), "递归删除目录失败");
            check(sibling.isFile() && l2s.isFile(), "删除目录跟随了内部链接"); sibling.delete(); l2s.delete();
            idle();
            result.putString("result", "PASS"); result.putInt("assertions", assertions);
        } catch (Throwable error) { result.putString("failure", android.util.Log.getStackTraceString(error)); }
        result.putInt("assertions", assertions);
        result.putString("fixture", fixture.getPath());
        finish(result.containsKey("failure") ? Activity.RESULT_CANCELED : Activity.RESULT_OK, result);
    }
}
