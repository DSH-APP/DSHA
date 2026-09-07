package com.deepseekharness.app.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

/** 同一私有文件系统上的环境切换。只重命名；旧环境、失败的新环境和校验归档均不删除。 */
public final class MaintenanceTransaction {
    private final File files, directory, environment;
    private MaintenanceTransaction(File files, File directory) throws IOException {
        this.files = files.getCanonicalFile(); this.directory = directory;
        environment = new File(this.files, "linux");
        requireLocal(directory.getParentFile()); requireLocal(directory); requireLocal(environment);
    }
    public static MaintenanceTransaction create(File files) throws IOException {
        File home = new File(files.getCanonicalFile(), "maintenance");
        if (!home.getCanonicalFile().equals(home.getAbsoluteFile())) throw new IOException("维护目录不能是外部链接");
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException("无法建立私有维护目录");
        File directory = new File(home, UUID.randomUUID().toString());
        if (!directory.mkdir()) throw new IOException("无法建立独立维护任务目录");
        return new MaintenanceTransaction(files, directory);
    }
    public static MaintenanceTransaction pending(File files) throws IOException {
        File home = new File(files.getCanonicalFile(), "maintenance");
        if (!home.getCanonicalFile().equals(home.getAbsoluteFile())) throw new IOException("维护目录不安全");
        File[] entries = home.listFiles();
        if (entries == null) { if (home.exists()) throw new IOException("无法读取维护日志"); return null; }
        MaintenanceTransaction pending = null;
        for (File entry : entries) {
            if (!entry.getName().matches("[a-f0-9-]{36}")) continue;
            MaintenanceTransaction task = new MaintenanceTransaction(files, entry);
            if (new File(entry, "intent.properties").exists() && !task.finished()) {
                if (pending != null) throw new IOException("存在多份未完成维护，已阻止覆盖环境");
                pending = task;
            }
        }
        return pending;
    }
    public File directory() { return directory; }
    public File archive() { return new File(directory, "safety.tar.gz"); }
    public boolean finished() { return new File(directory, "committed").isFile() || new File(directory, "rolled-back").isFile(); }
    private void requireLocal(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        if (!absolute.getCanonicalFile().equals(absolute) || !absolute.getPath().startsWith(files.getPath() + File.separator))
            throw new IOException("维护路径不在预期私有目录：" + file.getName());
    }
    private void mark(String name, String text) throws IOException {
        File target = new File(directory, name); requireLocal(target);
        if (target.exists()) throw new IOException("维护阶段已存在：" + name);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.getFD().sync();
        }
    }
    public void verify(String expectedHash) throws IOException {
        requireLocal(archive());
        try (FileInputStream input = new FileInputStream(archive())) {
            if (archive().length() == 0 || !expectedHash.equals(FileIntegrity.copy(input, null, 16L * 1024 * 1024 * 1024).sha256))
                throw new IOException("安全备份校验失败，原环境未切换");
        }
        mark("verified", expectedHash);
    }
    public void begin(boolean fresh) throws IOException {
        requireLocal(environment);
        boolean exists = environment.exists();
        if (fresh && exists) throw new IOException("已有环境，必须先完成安全备份");
        if (!fresh && !new File(directory, "verified").isFile()) throw new IOException("未校验安全备份，禁止切换环境");
        // 意图先落盘；任意进程退出点均可按 old/new 的存在情况恢复。
        mark("intent.properties", "hadEnvironment=" + exists + "\n");
        if (exists) move(environment, new File(directory, "previous-linux"));
    }
    public void commit() throws IOException { mark("committed", "ok\n"); }
    private void move(File source, File target) throws IOException {
        requireLocal(source); requireLocal(target);
        if (target.exists() || !source.renameTo(target)) throw new IOException("无法保留/切换环境：" + source.getName());
    }
    public void rollback() throws IOException {
        if (finished()) return;
        Properties intent = new Properties();
        try (FileInputStream input = new FileInputStream(new File(directory, "intent.properties"))) { intent.load(input); }
        String previous = intent.getProperty("hadEnvironment");
        if (!"true".equals(previous) && !"false".equals(previous)) throw new IOException("维护日志不完整，已阻止覆盖环境");
        File old = new File(directory, "previous-linux"), failed = new File(directory, "failed-linux");
        requireLocal(old); requireLocal(failed); requireLocal(environment);
        if (old.exists() || "false".equals(previous)) {
            if (environment.exists()) move(environment, failed);
            if (old.exists()) move(old, environment);
        } else if (!environment.isDirectory()) {
            throw new IOException("原环境位置不明，已保留所有现场文件");
        }
        mark("rolled-back", "ok\n");
    }
}
