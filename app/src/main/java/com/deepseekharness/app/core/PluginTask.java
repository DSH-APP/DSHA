package com.deepseekharness.app.core;

import com.deepseekharness.app.util.Compat;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** 插件子进程与界面之间的阶段/取消信号。取消在 Python 提交边界协作处理。 */
final class PluginTask implements AutoCloseable {
    final String id = UUID.randomUUID().toString().replace("-", "");
    private final File progress, cancel, approval;
    private volatile boolean requested;
    PluginTask(File rootfs) {
        progress = new File(rootfs, "root/.dsha-plugin-task-" + id + ".json");
        cancel = new File(rootfs, "root/.dsha-plugin-task-" + id + ".cancel");
        approval = new File(rootfs,"root/.dsha-plugin-task-"+id+".approval");
    }
    JSONObject read() {
        try {
            if (progress.isFile() && progress.length() <= 16384)
                return new JSONObject(new String(Compat.readAllBytes(progress), StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        return new JSONObject();
    }
    synchronized void cancel() throws IOException {
        if (!cancel.createNewFile() && !cancel.isFile()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法发送取消请求"));
        requested = true;
    }
    boolean requested() { return requested; }
    synchronized void approve(String digest)throws IOException{
        check();if(!digest.matches("[a-f0-9]{64}")||!com.deepseekharness.app.BackupManager.isDataTaskOwner())throw new IOException("PLUGIN_APPROVAL_REQUIRES_MAINTENANCE");
        if(!approval.createNewFile())throw new IOException("PLUGIN_APPROVAL_EXISTS");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(approval)){out.write(digest.getBytes(StandardCharsets.US_ASCII));out.getFD().sync();}
    }
    void check() throws IOException { if (requested) throw new IOException(com.deepseekharness.app.util.UiText.text("已取消")); }
    @Override public synchronized void close() {
        progress.delete(); cancel.delete(); approval.delete();
        new File(progress.getPath() + ".tmp").delete();
    }
}
