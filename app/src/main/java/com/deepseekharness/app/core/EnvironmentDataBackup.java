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

/** 全量 .dsh 归档之外的个人目录保护；仅用于已停止运行任务的环境维护。 */
final class EnvironmentDataBackup {
    private static final long LIMIT = 16L * 1024 * 1024 * 1024;
    static final class Snapshot {
        final String hash;
        final long unpackedBytes;
        Snapshot(String hash, long unpackedBytes) { this.hash = hash; this.unpackedBytes = unpackedBytes; }
    }
    private EnvironmentDataBackup() { }
    static Snapshot snapshot(HarnessController controller, File destination) throws Exception {
        requireOwner();
        File temporary = new File(controller.proot().getRootfsDir(), "root/.dsha-personal-" + UUID.randomUUID() + ".tar.gz");
        if (destination.exists()) throw new IOException("个人数据安全副本已存在");
        try {
            JSONObject result = run(controller, "snapshot --archive " + ShellQuote.arg("/root/" + temporary.getName())
                    + " --workdir " + ShellQuote.arg(controller.config().getWorkdir()));
            String expected = result.getString("sha256");
            verify(temporary, expected);
            if (!temporary.renameTo(destination)) throw new IOException("无法保留个人数据安全副本，原环境未切换");
            return new Snapshot(expected, result.getLong("bytes"));
        } finally { temporary.delete(); new File(temporary.getPath() + ".part").delete(); }
    }
    static void restore(HarnessController controller, File archive, String hash) throws Exception {
        requireOwner();
        verify(archive, hash);
        File temporary = new File(controller.proot().getRootfsDir(), "root/.dsha-personal-input-" + UUID.randomUUID() + ".tar.gz");
        try {
            try (FileInputStream in = new FileInputStream(archive); FileOutputStream out = new FileOutputStream(temporary)) {
                FileIntegrity.Result copied = FileIntegrity.copy(in, out, LIMIT); out.getFD().sync();
                if (!hash.equals(copied.sha256)) throw new IOException("个人数据归档复制校验失败");
            }
            JSONObject result = run(controller, "restore --archive " + ShellQuote.arg("/root/" + temporary.getName()));
            if (!result.optBoolean("verified")) throw new IOException("个人文件恢复后校验未完成");
        } finally { temporary.delete(); }
    }
    private static JSONObject run(HarnessController controller, String arguments) throws Exception {
        if (!controller.proot().ensureBundledPython()) throw new IOException("迁移所需的 Python 未就绪，原环境已保留");
        String asset = controller.readAsset("environment-data.py");
        if (asset.isEmpty()) throw new IOException("缺少个人文件迁移脚本");
        Compat.write(new File(controller.proot().getRootfsDir(), "root/.dsha-environment-data.py"), asset.getBytes(StandardCharsets.UTF_8));
        String output = controller.proot().execAndReadWithProot("python3 -B /root/.dsha-environment-data.py " + arguments + " 2>&1", 600_000);
        String marker = "DSHA_ENV_DATA=";
        int index = output == null ? -1 : output.lastIndexOf(marker);
        if (index < 0) throw new IOException(output == null ? "个人文件迁移没有返回结果" : output);
        return new JSONObject(output.substring(index + marker.length()).trim());
    }
    private static void requireOwner() throws IOException {
        if (!BackupManager.isDataTaskOwner()) throw new IOException("个人文件迁移必须持有维护任务锁");
    }
    private static void verify(File file, String expected) throws IOException {
        if (expected == null || !expected.matches("[a-f0-9]{64}")) throw new IOException("个人文件摘要无效");
        try (FileInputStream input = new FileInputStream(file)) {
            if (!expected.equals(FileIntegrity.copy(input, null, LIMIT).sha256)) throw new IOException("个人文件安全副本校验失败");
        }
    }
}
