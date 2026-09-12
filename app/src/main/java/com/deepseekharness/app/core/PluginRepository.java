package com.deepseekharness.app.core;

import android.app.Application;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.PluginSource;
import com.deepseekharness.app.util.SensitiveData;
import com.deepseekharness.app.util.ShellQuote;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Activity 范围的插件操作状态；任务只持有 Application，切页/旋转不会丢失结果。 */
public final class PluginRepository extends AndroidViewModel {
    private static final long MAX_ARCHIVE = 256L * 1024 * 1024;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AtomicBoolean working = new AtomicBoolean();
    private final MutableLiveData<State> state = new MutableLiveData<>(
            new State(Collections.emptyList(), false, "选择链接安装或导入本地插件包"));
    private volatile List<Item> items = Collections.emptyList();
    private volatile boolean safeMode;
    private volatile boolean installationSucceeded;
    private volatile PluginTask activeTask;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private Preview installedPreview;
    private final MutableLiveData<Preview> preview = new MutableLiveData<>();

    public static final class Item {
        public final String name, description, version, source;
        public final String location;
        public final boolean detected;
        public final boolean dynamic;
        public final String latestVersion, updatePreviewId, updateMessage, rollbackVersion, compatibility;
        public final boolean updateAvailable;
        public final boolean enabled, builtin, official, available, exportable, deletable;
        Item(JSONObject json) {
            name = json.optString("name");
            description = json.optString("description");
            version = json.optString("version");
            source = json.optString("source");
            location = json.optString("location");
            detected = json.optBoolean("detected");
            dynamic = json.optBoolean("dynamic");
            enabled = json.optBoolean("enabled");
            builtin = json.optBoolean("builtin");
            official = json.optBoolean("official");
            available = json.optBoolean("available");
            exportable = json.optBoolean("exportable");
            deletable = json.optBoolean("deletable");
            latestVersion = json.optString("latestVersion");
            updatePreviewId = json.optString("updatePreviewId");
            updateMessage = json.optString("updateMessage");
            rollbackVersion = json.optString("rollbackVersion");
            compatibility = json.optString("compatibility");
            updateAvailable = json.optBoolean("updateAvailable");
        }
    }

    public static final class Preview {
        public final String id, description;
        Preview(JSONObject json) throws Exception {
            id = json.getString("previewId");
            if (!id.matches("[a-f0-9]{32}")) throw new IOException("插件预览标识无效");
            data=new JSONObject(json.toString());
            JSONArray packages=data.getJSONArray("items");
            for(int i=0;i<packages.length();i++)packages.getJSONObject(i).getString("name");
            description=description();
        }
        private final JSONObject data;
        public String description() {
            JSONObject json=data;
            StringBuilder text = new StringBuilder();
            JSONArray packages = json.optJSONArray("items");
            for (int i = 0; i < packages.length(); i++) {
                JSONObject pkg = packages.optJSONObject(i);
                text.append(pkg.optString("name")).append(" · ").append(pkg.optString("version"))
                        .append(com.deepseekharness.app.util.UiText.text("\n作者：")).append(pkg.optString("author", com.deepseekharness.app.util.UiText.text("未注明")))
                        .append("\n").append(pkg.optString("description"))
                        .append("\n").append(com.deepseekharness.app.util.UiStateText.render(pkg.optString("compatibilityMessage"))).append("\n\n");
            }
            text.append(com.deepseekharness.app.util.UiText.text("来源：")).append(json.optString("source").isEmpty() ? com.deepseekharness.app.util.UiText.text("本地插件包") : json.optString("source"))
                    .append("\nSHA-256：").append(json.optString("sha256"))
                    .append(com.deepseekharness.app.util.UiText.text("\n\n确认后安装并登记；完成后重启 Web 生效。同名更新会保留上一版供回退。"));
            return SensitiveData.redact(text.toString());
        }
    }

    public static final class State {
        public final List<Item> items;
        public final boolean busy;
        public final String message;
        public final boolean cancellable;
        public final int percent;
        State(List<Item> items, boolean busy, String message) {
            this(items, busy, message, false, -1);
        }
        State(List<Item> items, boolean busy, String message, boolean cancellable, int percent) {
            this.items = items;
            this.busy = busy;
            this.message = message;
            this.cancellable = cancellable;
            this.percent = percent;
        }
    }

    public PluginRepository(@NonNull Application app) { super(app); }
    public LiveData<State> state() { return state; }
    public boolean isBusy() { return working.get(); }
    public boolean isSafeMode() { return safeMode; }
    public boolean installationSucceeded() { return installationSucceeded; }
    public String installedDescription() { return installedPreview==null?"":installedPreview.description(); }
    public LiveData<Preview> preview() { return preview; }

    public void selectionMessage(String message) {
        DiagnosticLog.record(getApplication(), "FILE_SELECTION", message);
        if (!working.get()) state.setValue(new State(items, false, message));
    }

    private interface Work { String run(ProotBootstrap proot) throws Exception; }

    private void submit(String progress, Work work) {
        submit(progress, work, null);
    }

    private void submit(String progress, Work work, Runnable onSuccess) {
        submit(progress, work, onSuccess, true);
    }

    private void submit(String progress, Work work, Runnable onSuccess, boolean cancellable) {
        submit(progress, work, onSuccess, cancellable, null);
    }

    /** 获取凭据后才消耗预览/创建任务；凭据一直持有到主线程完成最后一次文件清理。 */
    private void submit(String progress, Work work, Runnable onSuccess, boolean cancellable, Runnable onAccepted) {
        if (!working.compareAndSet(false, true)) {
            State shown = state.getValue();
            state.setValue(new State(items, true, "上一项插件操作仍在进行，请完成后重新选择。",
                    shown != null && shown.cancellable, shown == null ? -1 : shown.percent));
            return;
        }
        HarnessController controller = HarnessController.get(getApplication());
        String blocked = environmentBlockMessage();
        if (!blocked.isEmpty()) {
            working.set(false); state.setValue(new State(items, false, blocked)); return;
        }
        com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease =
                com.deepseekharness.app.util.EnvironmentTaskGate.tryAcquire("插件操作");
        if (lease == null) {
            working.set(false);
            state.setValue(new State(items, false, "已有环境任务启动，请稍后刷新或重试插件操作"));
            return;
        }
        if (com.deepseekharness.app.BackupManager.hasPendingMaintenance(controller)
                || com.deepseekharness.app.BackupManager.isRestoring()) {
            lease.close(); working.set(false);
            state.setValue(new State(items, false, "环境维护尚未完成，请先恢复维护，再刷新或操作插件"));
            return;
        }
        RuntimeTasks runtimeWork;
        try { runtimeWork = RuntimeTasks.beginDetached(); }
        catch (RuntimeException | Error error) {
            lease.close(); working.set(false);
            state.setValue(new State(items, false, "插件任务未开始：" + SensitiveData.redact(String.valueOf(error.getMessage()))));
            return;
        }
        PluginTask task;
        try { task = new PluginTask(controller.proot().getRootfsDir()); }
        catch (RuntimeException | Error error) { runtimeWork.close(); lease.close(); working.set(false); throw error; }
        activeTask = task;
        Runnable poll = new Runnable() {
            @Override public void run() {
                if (activeTask != task || !working.get()) return;
                JSONObject detail = task.read();
                long total = detail.optLong("total"), current = detail.optLong("current");
                int percent = total > 0 ? (int) Math.min(100, current * 100 / total) : -1;
                String message = task.requested() ? "正在取消，请等待当前提交完成或临时文件清理…"
                        : detail.optString("message", progress);
                if (!task.requested() && current > 0 && detail.optString("stage").equals("download"))
                    message += String.format(java.util.Locale.ROOT, " %.1f MiB%s", current / 1048576.0,
                            percent < 0 ? "" : " · " + percent + "%");
                state.setValue(new State(items, true, message,
                        cancellable && !task.requested() && detail.optBoolean("cancellable", true), percent));
                main.postDelayed(this, 400);
            }
        };
        try {
            if (onAccepted != null) onAccepted.run();
            state.setValue(new State(items, true, progress, cancellable, -1));
            main.post(poll);
            DiagnosticLog.record(getApplication(), "PLUGIN_START", progress);
            IO.execute(() -> runTask(controller, task, lease, runtimeWork, poll, work, onSuccess));
        } catch (RuntimeException error) {
            main.removeCallbacks(poll);
            finishTask(task, lease, runtimeWork, new State(items, false, "插件任务未开始：" + SensitiveData.redact(String.valueOf(error))), null);
        }
    }

    /** 只读门禁提示；已运行的 Web 不占用 EnvironmentTaskGate，不影响正常插件操作。 */
    public String environmentBlockMessage() {
        HarnessController controller = HarnessController.get(getApplication());
        if (com.deepseekharness.app.BackupManager.hasPendingMaintenance(controller))
            return "上次环境维护未完成，请到安装与修复页恢复维护，再刷新或操作插件";
        if (com.deepseekharness.app.BackupManager.isEnvironmentTaskBusy()) {
            String kind = com.deepseekharness.app.util.EnvironmentTaskGate.activeKind();
            return (kind.isEmpty() ? "正在执行环境任务" : "正在" + kind) + "，请稍后刷新或重试插件操作";
        }
        return "";
    }

    private void runTask(HarnessController controller, PluginTask task,
                         com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease,
                         RuntimeTasks runtimeWork, Runnable poll, Work work, Runnable onSuccess) {
        State completed;
        boolean[] success = {false};
        try {
            completed = lease.run(() -> {
                // 防止排队前检查与真正访问 rootfs 之间出现未完成的维护事务。
                if (com.deepseekharness.app.BackupManager.hasPendingMaintenance(controller)
                        || com.deepseekharness.app.BackupManager.isRestoring())
                    throw new IOException("环境维护尚未完成，请先恢复维护，再刷新或操作插件");
                ProotBootstrap proot = controller.proot();
                String message;
                try {
                    task.check();
                    if (!proot.isEnvironmentReady()) throw new IOException("环境未就绪，请先完成解压 / 安装");
                    message = work.run(proot);
                    success[0] = true;
                } catch (Exception error) {
                    message = "操作失败：" + SensitiveData.redact(String.valueOf(error.getMessage()));
                }
                main.removeCallbacks(poll);
                // list 也可能 prepare/同步资产，仍在同一凭据内，不能作为只读旁路。
                try {
                    if (proot.isEnvironmentReady()) items = loadItems(proot);
                } catch (Exception error) {
                    message += "\n列表未能同步：" + SensitiveData.redact(String.valueOf(error.getMessage()));
                }
                return new State(items, false, message);
            });
        } catch (Exception error) {
            completed = new State(items, false, "操作失败：" + SensitiveData.redact(String.valueOf(error.getMessage())));
        }
        main.removeCallbacks(poll);
        State result = completed;
        if (!main.post(() -> finishTask(task, lease, runtimeWork, result, success[0] ? onSuccess : null))) {
            // 主 Looper 已关闭时不再发布 UI，但清理文件仍先于释放环境凭据。
            task.close();
            runtimeWork.close(); activeTask = null; working.set(false); lease.close();
        }
    }

    private void finishTask(PluginTask task, com.deepseekharness.app.util.EnvironmentTaskGate.Lease lease,
                            RuntimeTasks runtimeWork, State completed, Runnable onSuccess) {
        // 任一步清理抛错都不交还环境所有权；不能让维护越过尚未完成的结束阶段。
        try { task.close(); }
        catch (RuntimeException error) {
            state.setValue(new State(items, true, "插件清理失败，保留环境占用：" + SensitiveData.redact(String.valueOf(error)), false, -1));
            return;
        }
        runtimeWork.close(); activeTask = null; working.set(false); lease.close();
        DiagnosticLog.record(getApplication(), "PLUGIN_RESULT", completed.message);
        state.setValue(completed);
        // startWeb 等后续动作要在凭据释放之后执行。
        if (onSuccess != null) onSuccess.run();
    }

    public void cancelTask() {
        PluginTask task = activeTask;
        State shown = state.getValue();
        if (task == null || shown == null || !shown.cancellable) return;
        try { task.cancel(); }
        catch (IOException error) { state.setValue(new State(items, true, error.getMessage())); }
    }

    private String runManager(ProotBootstrap proot, String args) throws IOException {
        PluginTask task = activeTask;
        if (task != null) task.check();
        return proot.runPluginManager(args, task == null ? "" : task.id);
    }

    public void refresh() {
        submit("正在检测 Web、终端和本地安装的插件…", proot -> {
            String output = proot.registerBuiltinPlugins();
            if (output.contains("FAIL") || output.contains("ERROR:"))
                throw new IOException(shortError(output));
            return output.contains("PARTIAL") ? "部分内置插件待修复，请检查环境"
                    : "插件检测完成；新检测到的插件可开启开关加入 Web，变更后重启 Web 生效";
        }, null, false);
    }

    public void install(PluginSource source) {
        inspect(source, "", "", "");
    }

    public void inspect(PluginSource source, String sha256, String name, String version) {
        if (working.get()) return;
        Preview old = preview.getValue();
        submit("正在下载并解析：" + source.description(), proot -> {
            discardPreview(proot, old);
            JSONObject request = new JSONObject().put("command", source.command())
                    .put("sha256", sha256).put("name", name).put("version", version);
            return receivePreview(result(runManager(proot, "inspect " + ShellQuote.arg(request.toString()))));
        }, null, true, () -> { installationSucceeded = false; preview.setValue(null); });
    }

    private String receivePreview(JSONObject output) throws Exception {
        if (!"ok".equals(output.optString("status"))) throw new IOException(output.optString("message"));
        if (activeTask != null && activeTask.requested()) {
            HarnessController.get(getApplication()).proot().runPluginManager("discard-preview "
                    + ShellQuote.arg(output.getJSONObject("preview").getString("previewId")));
            throw new IOException("已取消解析并清理临时包");
        }
        preview.postValue(new Preview(output.getJSONObject("preview")));
        return "插件包已解析，请确认作者、版本和兼容范围后安装";
    }

    public void confirmPreview() {
        Preview selected = preview.getValue();
        if (selected == null || working.get()) return;
        submit("正在安装已确认的插件包…", proot -> {
            JSONObject output = result(runManager(proot, "install-preview " + ShellQuote.arg(selected.id)));
            installationSucceeded = "ok".equals(output.optString("status"));
            return operationMessage(output);
        }, null, true, () -> {
            installationSucceeded = false;
            installedPreview = selected;
            preview.setValue(null);
        });
    }

    public void discardPreview() {
        Preview old = preview.getValue();
        if (old == null) return;
        submit("正在清理插件安装预览…", proot -> {
            discardPreview(proot, old);
            return "插件安装预览已取消";
        }, null, false, () -> preview.setValue(null));
    }

    private static void discardPreview(ProotBootstrap proot, Preview old) {
        if (old != null) proot.runPluginManager("discard-preview " + ShellQuote.arg(old.id));
    }

    public void checkUpdates(Item item) {
        submit("正在检查插件版本…", proot -> operationMessage(result(runManager(proot,
                "check-updates" + (item == null ? "" : " " + ShellQuote.arg(item.name))))));
    }

    public void prepareUpdate(Item item) {
        if (working.get() || !item.updateAvailable) return;
        Preview old = preview.getValue();
        submit("正在下载并核对更新包…", proot -> {
            discardPreview(proot, old);
            return receivePreview(result(runManager(proot, "prepare-update " + ShellQuote.arg(item.name))));
        }, null, true, () -> preview.setValue(null));
    }

    public void rollback(Item item) {
        submit("正在回退 " + item.name + "…", proot -> operationMessage(result(runManager(proot, "rollback "
                + ShellQuote.arg(item.name) + " " + ShellQuote.arg(item.rollbackVersion)))));
    }

    public void safeMode(boolean enable, Runnable afterSuccess) {
        submit(enable ? "正在暂时停用第三方插件…" : "正在恢复此前启用的第三方插件…", proot -> {
            JSONObject output = result(runManager(proot, "safe-mode " + (enable ? "on" : "off")));
            if (!"ok".equals(output.optString("status"))) throw new IOException(output.optString("message"));
            return output.getString("message");
        }, afterSuccess);
    }

    public void importArchive(Uri uri) {
        if (working.get()) return;
        Preview old = preview.getValue();
        submit("正在解析本地插件包，安装前需确认…", proot -> {
            discardPreview(proot, old);
            File temporary = File.createTempFile("plugin-import-", ".bin", getApplication().getCacheDir());
            String container = "/root/.dsh/plugin-upload-" + UUID.randomUUID() + ".bin";
            try {
                try (InputStream in = getApplication().getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(temporary)) {
                    copy(in, out);
                }
                if (!proot.pushFileIntoContainer(temporary, container)) throw new IOException("插件包写入容器失败");
                JSONObject request = new JSONObject().put("command", "file " + ShellQuote.arg(container));
                return receivePreview(result(runManager(proot, "inspect " + ShellQuote.arg(request.toString()))));
            } finally {
                temporary.delete();
                cleanup(proot, container);
            }
        }, null, true, () -> { installationSucceeded = false; preview.setValue(null); });
    }

    public void exportArchives(List<String> names, Uri uri) {
        submit("正在打包并保存插件…", proot -> {
            File temporary = File.createTempFile("plugin-export-", ".tar.gz", getApplication().getCacheDir());
            String container = "/root/.dsh/plugin-export-" + UUID.randomUUID() + ".tar.gz";
            boolean saved = false;
            try {
                JSONObject output = result(runManager(proot, "export "
                        + ShellQuote.arg(new JSONArray(names).toString()) + " " + ShellQuote.arg(container)));
                if (!"ok".equals(output.optString("status"))) throw new IOException(output.optString("message"));
                if (!proot.pullFileFromContainer(container, temporary)) throw new IOException("插件包取回失败");
                try (InputStream in = new FileInputStream(temporary);
                     OutputStream out = getApplication().getContentResolver().openOutputStream(uri, "wt")) {
                    copy(in, out);
                }
                saved = true;
                return "已将 " + names.size() + " 个插件导出到所选位置，可在另一台 DSHA 中导入";
            } finally {
                temporary.delete();
                cleanup(proot, container);
                if (!saved) {
                    try { DocumentsContract.deleteDocument(getApplication().getContentResolver(), uri); }
                    catch (Exception ignored) { }
                }
            }
        });
    }

    public void setEnabled(Item item, boolean enable) {
        submit("正在" + (enable ? "启用 " : "禁用 ") + item.name, proot -> {
            String output = proot.setPluginEnabled(item.name, enable);
            if (!output.contains("BUILTIN_REGISTER_OK")) throw new IOException(shortError(output));
            return "已" + (enable ? "启用 " : "禁用 ") + item.name + "；重启 Web 后生效";
        }, null, false);
    }

    public void delete(Item item) {
        if (!item.deletable) return;
        submit("正在删除 " + item.name + "…", proot -> {
            JSONObject output = result(runManager(proot, "delete " + ShellQuote.arg(item.name)));
            if (!"ok".equals(output.optString("status"))) throw new IOException(output.optString("message"));
            return output.getString("message");
        });
    }

    private List<Item> loadItems(ProotBootstrap proot) throws Exception {
        JSONObject output = result(proot.runPluginManager("list"));
        if (!"ok".equals(output.optString("status"))) throw new IOException(output.optString("message"));
        JSONArray array = output.getJSONArray("items");
        safeMode = output.optBoolean("safeMode");
        List<Item> next = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            Item item = new Item(array.getJSONObject(i));
            if (!com.deepseekharness.app.util.BuiltinPlugins.internal(item.name)) next.add(item);
        }
        // 临时插件由当前 Web 进程提供摘要；旧进程或过期文件不能当成仍在运行。
        HarnessController controller = HarnessController.get(getApplication());
        File activity = new File(proot.getRootfsDir(), "root/.dsha-web-activity.json");
        if (!controller.getWebAuthUrl().isEmpty() && activity.isFile() && activity.length() <= 256 * 1024) {
            try {
                JSONObject snapshot = new JSONObject(com.deepseekharness.app.util.Compat.readAll(activity));
                long age = System.currentTimeMillis() - snapshot.optLong("at");
                if (age >= 0 && age <= 10_000 && Long.toString(controller.getWebGeneration()).equals(snapshot.optString("generation"))) {
                    JSONArray dynamic = snapshot.optJSONArray("plugins");
                    if (dynamic != null) for (int i = 0; i < Math.min(256, dynamic.length()); i++) {
                        JSONObject row = dynamic.getJSONObject(i);
                        String session = row.optString("sessionId");
                        JSONObject item = new JSONObject().put("name", row.optString("name"))
                                .put("dynamic", true).put("available", true).put("enabled", row.optBoolean("active"))
                                .put("location", "会话 " + session.substring(Math.max(0, session.length() - 8)))
                                .put("description", "会话内临时插件，请在 Web 页面管理；重启后需重新定义，不作为安装包备份。");
                        next.add(new Item(item));
                    }
                }
            } catch (Exception ignored) { /* 临时摘要失效不影响持久插件列表。 */ }
        }
        return Collections.unmodifiableList(next);
    }

    private static JSONObject result(String output) throws Exception {
        String text = output == null ? "" : output.trim();
        String result = com.deepseekharness.app.util.PluginOutput.resultJson(text);
        if (result.isEmpty()) throw new IOException(shortError(text));
        JSONObject json = new JSONObject(result);
        if (!json.has("status") || !json.has("message")) throw new IOException("插件操作返回了不完整的结果");
        // error/partial 也保留脚本给出的完整原因；不能靠输出中出现一次 OK 判断整个操作成功。
        json.put("message", SensitiveData.redact(json.getString("message")));
        return json;
    }

    private static String operationMessage(JSONObject result) throws Exception {
        String status = result.getString("status");
        return ("partial".equals(status) ? "部分完成：\n" : "error".equals(status) ? "安装失败：\n" : "")
                + result.getString("message");
    }

    private static String shortError(String text) {
        if (text == null || text.isEmpty()) return "没有收到插件管理结果";
        if (text.equals("ENV_NOT_READY")) return "环境未就绪，请先完成解压 / 安装";
        String safe = SensitiveData.redact(text.trim());
        return safe.length() <= 800 ? safe : safe.substring(safe.length() - 800);
    }

    private void copy(InputStream in, OutputStream out) throws IOException {
        if (in == null || out == null) throw new IOException("无法打开所选文件，请重新选择位置");
        long size = 0;
        byte[] bytes = new byte[65536];
        int count;
        while ((count = in.read(bytes)) != -1) {
            if (activeTask != null) activeTask.check();
            size += count;
            if (size > MAX_ARCHIVE) throw new IOException("插件包不能超过 256 MiB");
            out.write(bytes, 0, count);
        }
        if (size == 0) throw new IOException("插件包为空");
    }

    private static void cleanup(ProotBootstrap proot, String path) {
        // 只清理由本类生成的单个暂存文件，不递归清理插件目录。
        new File(proot.getRootfsDir(), path.substring(1)).delete();
    }
}
