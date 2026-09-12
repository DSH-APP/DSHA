package com.deepseekharness.app.core;

import com.deepseekharness.app.BackupManager;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.FileIntegrity;
import com.deepseekharness.app.util.ShellQuote;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.function.Consumer;

/** 全量 .dsh 归档之外的个人目录保护；仅用于已停止运行任务的环境维护。 */
final class EnvironmentDataBackup {
    private static final long LIMIT = 16L * 1024 * 1024 * 1024;
    static final class Snapshot {
        final String hash;
        final long unpackedBytes;
        Snapshot(String hash, long unpackedBytes) { this.hash = hash; this.unpackedBytes = unpackedBytes; }
    }
    private EnvironmentDataBackup() { }
    static Snapshot snapshot(HarnessController controller, File destination, Consumer<String> progress) throws Exception {
        requireOwner();
        File temporary = new File(controller.proot().getRootfsDir(), "root/.dsha-personal-" + UUID.randomUUID() + ".tar.gz");
        if (destination.exists()) throw new IOException(com.deepseekharness.app.util.UiText.text("个人数据安全副本已存在"));
        try {
            JSONObject result = run(controller, "snapshot --archive " + ShellQuote.arg("/run/dsha-maintenance-root/root/" + temporary.getName())
                    + " --workdir " + ShellQuote.arg(controller.config().getWorkdir()), progress);
            String expected = result.getString("sha256");
            verify(temporary, expected);
            if (!temporary.renameTo(destination)) throw new IOException(com.deepseekharness.app.util.UiText.text("无法保留个人数据安全副本，原环境未切换"));
            return new Snapshot(expected, result.getLong("bytes"));
        } finally { temporary.delete(); new File(temporary.getPath() + ".part").delete(); }
    }
    static void restore(HarnessController controller, File archive, String hash, Consumer<String> progress) throws Exception {
        requireOwner();
        verify(archive, hash);
        File temporary = new File(controller.proot().getRootfsDir(), "root/.dsha-personal-input-" + UUID.randomUUID() + ".tar.gz");
        try {
            try (FileInputStream in = new FileInputStream(archive); FileOutputStream out = new FileOutputStream(temporary)) {
                FileIntegrity.Result copied = FileIntegrity.copy(in, out, LIMIT); out.getFD().sync();
                if (!hash.equals(copied.sha256)) throw new IOException(com.deepseekharness.app.util.UiText.text("个人数据归档复制校验失败"));
            }
            JSONObject result = run(controller, "restore --archive " + ShellQuote.arg("/run/dsha-maintenance-root/root/" + temporary.getName()), progress);
            if (!result.optBoolean("verified")) throw new IOException(com.deepseekharness.app.util.UiText.text("个人文件恢复后校验未完成"));
        } finally { temporary.delete(); }
    }
    private static JSONObject run(HarnessController controller, String arguments, Consumer<String> progress) throws Exception {
        if (!controller.proot().ensureBundledPython()) throw new IOException(com.deepseekharness.app.util.UiText.text("迁移所需的 Python 未就绪，原环境已保留"));
        String asset = controller.readAsset("environment-data.py");
        if (asset.isEmpty()) throw new IOException(com.deepseekharness.app.util.UiText.text("缺少个人文件迁移脚本"));
        Compat.write(new File(controller.proot().getRootfsDir(), "root/.dsha-environment-data.py"), asset.getBytes(StandardCharsets.UTF_8));
        final String[] result = {null};
        Consumer<String> lines = line -> {
            if (line.startsWith("DSHA_ENV_DATA=")) result[0] = line.substring(14);
            else if (line.startsWith("DSHA_ENV_PROGRESS=")) {
                try {
                    JSONObject value = new JSONObject(line.substring(18));
                    progress.accept(value.getString("stage") + com.deepseekharness.app.util.UiText.text("：") + value.getLong("files") + com.deepseekharness.app.util.UiText.text(" 项，")
                            + com.deepseekharness.app.util.Fmt.bytes(value.getLong("bytes"))
                            + com.deepseekharness.app.util.UiText.text("，已用 ") + value.getLong("seconds") + com.deepseekharness.app.util.UiText.text(" 秒"));
                } catch (org.json.JSONException ignored) { }
            }
        };
        String command = "python3 -u -B /root/.dsha-environment-data.py " + arguments
                + " --rootfs /run/dsha-maintenance-root 2>&1";
        // 目录存在不代表完整遍历/归档可用。与其他 Python 维护操作一样预先选择 proot，
        // 不在已经读写数据后切换执行通道重放，Web 继续使用用户选择的运行方式。
        progress.accept(com.deepseekharness.app.util.UiText.text("正在使用兼容文件迁移，准备统计进度…"));
        com.deepseekharness.app.util.BoundedProcessRunner.Result execution = controller.proot().runPersonalMaintenance(command, lines, false, 3_600_000);
        if (execution.timedOut) throw new IOException(com.deepseekharness.app.util.UiText.text("个人数据迁移达到一小时上限，原环境已保留；请减少数据量后重试"));
        if (execution.exitCode != 0 || result[0] == null)
            throw new IOException(com.deepseekharness.app.util.UiText.text("个人文件迁移失败（退出码 ") + execution.exitCode + "）：\n" + execution.output);
        return new JSONObject(result[0]);
    }
    private static void requireOwner() throws IOException {
        if (!BackupManager.isDataTaskOwner()) throw new IOException(com.deepseekharness.app.util.UiText.text("个人文件迁移必须持有维护任务锁"));
    }
    private static void verify(File file, String expected) throws IOException {
        if (expected == null || !expected.matches("[a-f0-9]{64}")) throw new IOException(com.deepseekharness.app.util.UiText.text("个人文件摘要无效"));
        try (FileInputStream input = new FileInputStream(file)) {
            if (!expected.equals(FileIntegrity.copy(input, null, LIMIT).sha256)) throw new IOException(com.deepseekharness.app.util.UiText.text("个人文件安全副本校验失败"));
        }
    }
}
