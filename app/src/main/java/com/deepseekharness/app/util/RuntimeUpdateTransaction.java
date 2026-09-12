package com.deepseekharness.app.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** 受管运行时的同盘改名事务。意图先落盘，失败和进程中断均能回切，不复制用户数据。 */
public final class RuntimeUpdateTransaction {
    private final File files, directory;
    private RuntimeUpdateTransaction(File files, File directory) throws IOException {
        this.files = files.getCanonicalFile(); this.directory = directory.getAbsoluteFile();
        local(this.directory); local(new File(this.files, "runtime-updates"));
    }
    public static RuntimeUpdateTransaction create(File files) throws IOException {
        if (pending(files) != null) throw new IOException(com.deepseekharness.app.util.UiText.text("先恢复上次运行时更新"));
        File home = new File(files.getCanonicalFile(), "runtime-updates");
        if (!home.getAbsoluteFile().equals(home.getCanonicalFile())) throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护目录不安全"));
        if (!home.isDirectory() && !home.mkdirs()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法建立运行时维护目录"));
        File entry = new File(home, UUID.randomUUID().toString());
        if (!entry.mkdir()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法建立运行时事务"));
        return new RuntimeUpdateTransaction(files, entry);
    }
    public static RuntimeUpdateTransaction pending(File files) throws IOException {
        File home = new File(files.getCanonicalFile(), "runtime-updates");
        if (!home.getAbsoluteFile().equals(home.getCanonicalFile())) throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护目录不安全"));
        File[] entries = home.listFiles();
        if (entries == null) { if (home.exists()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法读取运行时维护日志")); return null; }
        RuntimeUpdateTransaction pending = null;
        for (File entry : entries) if (entry.getName().matches("[a-f0-9-]{36}")) {
            RuntimeUpdateTransaction item = new RuntimeUpdateTransaction(files, entry);
            if (new File(entry, "intent.properties").isFile() && !item.finished()) {
                if (pending != null) throw new IOException(com.deepseekharness.app.util.UiText.text("多份运行时维护未完成，已停止切换"));
                pending = item;
            }
        }
        return pending;
    }
    public File directory() { return directory; }
    public File stage() { return new File(directory, "stage"); }
    private boolean finished() { return new File(directory, "committed").isFile() || new File(directory, "rolled-back").isFile(); }
    private void local(File file) throws IOException {
        File absolute = file.getAbsoluteFile();
        if (!absolute.getCanonicalFile().equals(absolute) || !absolute.getPath().startsWith(files.getPath() + File.separator))
            throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护路径不安全：") + file.getName());
    }
    private void parent(File file) throws IOException { local(file.getParentFile()); }
    private static boolean present(File file) throws IOException {
        if (file.exists()) return true;
        // 依赖树先切换时，待切换别名会短暂悬空；exists/canonical 不能可靠识别这类链接。
        File parent = file.getParentFile();
        String[] names = parent.list();
        if (names == null) { if (parent.exists()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法读取运行时路径状态")); return false; }
        for (String name : names) if (name.equals(file.getName())) return true;
        return false;
    }
    private void mark(String name, String text) throws IOException {
        File target = new File(directory, name), tmp = new File(directory, name + ".tmp"); local(target); local(tmp);
        if (target.exists()) throw new IOException(com.deepseekharness.app.util.UiText.text("运行时阶段已存在：") + name);
        try (FileOutputStream output = new FileOutputStream(tmp)) {
            output.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.getFD().sync();
        }
        if (!tmp.renameTo(target)) throw new IOException(com.deepseekharness.app.util.UiText.text("无法提交运行时维护阶段"));
    }
    public void begin(List<String> paths) throws IOException {
        if (paths.isEmpty() || paths.size() > 2048 || new HashSet<>(paths).size() != paths.size()) throw new IOException(com.deepseekharness.app.util.UiText.text("运行时更新清单无效"));
        StringBuilder intent = new StringBuilder("count=" + paths.size() + "\n");
        for (int i = 0; i < paths.size(); i++) {
            String path = paths.get(i);
            if (!ManagedRuntimeLayout.allowed(path)) throw new IOException(com.deepseekharness.app.util.UiText.text("禁止更新用户数据路径"));
            File target = new File(files, path), source = new File(stage(), path);
            parent(target); parent(source);
            if (!present(source)) throw new IOException(com.deepseekharness.app.util.UiText.text("新运行时缺少文件：") + path);
            intent.append("path.").append(i).append('=').append(path).append('\n')
                    .append("had.").append(i).append('=').append(present(target)).append('\n');
        }
        mark("intent.properties", intent.toString());
    }
    private Properties intent() throws IOException {
        Properties value = new Properties();
        File log = new File(directory, "intent.properties"); local(log);
        try (FileInputStream input = new FileInputStream(log)) { value.load(input); }
        return value;
    }
    private List<String> paths(Properties intent) throws IOException {
        int count;
        try { count = Integer.parseInt(intent.getProperty("count", "")); }
        catch (NumberFormatException error) { throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护日志无效"), error); }
        if (count <= 0 || count > 2048) throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护清单数量无效"));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String path = intent.getProperty("path." + i), had = intent.getProperty("had." + i);
            if (!ManagedRuntimeLayout.allowed(path) || result.contains(path) || !("true".equals(had) || "false".equals(had)))
                throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护清单无效"));
            result.add(path);
        }
        return result;
    }
    private void move(File from, File to) throws IOException {
        parent(from); parent(to);
        if (!to.getParentFile().isDirectory() && !to.getParentFile().mkdirs()) throw new IOException(com.deepseekharness.app.util.UiText.text("无法建立切换目录"));
        if (present(to) || !from.renameTo(to)) throw new IOException(com.deepseekharness.app.util.UiText.text("无法切换运行时：") + from.getName());
    }
    public void replace() throws IOException {
        if (finished()) throw new IOException(com.deepseekharness.app.util.UiText.text("运行时事务已结束"));
        Properties intent = intent(); List<String> paths = paths(intent);
        for (int i = 0; i < paths.size(); i++) {
            File target = new File(files, paths.get(i)), source = new File(stage(), paths.get(i));
            File previous = new File(directory, "previous/" + i);
            if ("true".equals(intent.getProperty("had." + i))) move(target, previous);
            move(source, target);
        }
    }
    public void commit() throws IOException {
        for (String path : paths(intent())) {
            File target = new File(files, path); parent(target);
            if (!present(target) || present(new File(stage(), path))) throw new IOException(com.deepseekharness.app.util.UiText.text("运行时文件尚未切换完整"));
        }
        mark("committed", "ok\n");
    }
    public void rollback() throws IOException {
        if (finished()) return;
        Properties intent = intent(); List<String> paths = paths(intent);
        for (int i = paths.size() - 1; i >= 0; i--) {
            File target = new File(files, paths.get(i)), previous = new File(directory, "previous/" + i);
            parent(target); parent(previous);
            boolean had = "true".equals(intent.getProperty("had." + i));
            if (present(previous) || !had) {
                if (present(target)) move(target, new File(directory, "failed/" + i));
                if (present(previous)) move(previous, target);
            } else if (!present(target)) throw new IOException(com.deepseekharness.app.util.UiText.text("旧运行时位置不明，已保留全部现场"));
        }
        mark("rolled-back", "ok\n");
    }
    public void cleanup(MaintenanceTransaction.TreeCleaner cleaner) throws IOException {
        if (!new File(directory, "committed").isFile() || new File(directory, "cleaned").isFile()) return;
        for (String name : new String[]{"previous", "stage"}) {
            File child = new File(directory, name); local(child);
            if (child.exists()) cleaner.delete(child);
        }
        mark("cleaned", "ok\n");
    }
    public static void cleanupCompleted(File files, MaintenanceTransaction.TreeCleaner cleaner) throws IOException {
        File home = new File(files.getCanonicalFile(), "runtime-updates");
        if (!home.getAbsoluteFile().equals(home.getCanonicalFile())) throw new IOException(com.deepseekharness.app.util.UiText.text("运行时维护目录不安全"));
        File[] entries = home.listFiles();
        if (entries != null) for (File entry : entries) if (entry.getName().matches("[a-f0-9-]{36}"))
            new RuntimeUpdateTransaction(files, entry).cleanup(cleaner);
    }
}
