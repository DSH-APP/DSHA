package com.deepseekharness.app;

import com.deepseekharness.app.util.DeviceAppPolicy;
import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.SensitiveData;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 设备 shell 的执行前守卫。调用者不能通过确认开关、root 或内部标志跳过判定。 */
public final class DeviceShellExecutor {
    private DeviceShellExecutor() { }
    interface Runner { String run(List<String> argv); }

    static String execute(String command, Runner runner) {
        DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command);
        if (!plan.allowed()) return plan.reason + "\n[EXIT=126]";
        try {
            if (plan.kind == DeviceShellPolicy.Kind.STOP) {
                String users = checked(runner.run(Arrays.asList("pm", "list", "packages", "-U", "-3")));
                String systems = checked(runner.run(Arrays.asList("pm", "list", "packages", "-U", "-s")));
                DeviceAppPolicy.Snapshot snapshot = DeviceAppPolicy.snapshot(users, systems);
                String processes = plan.command().equals("kill") ? checked(runner.run(Arrays.asList("ps", "-A", "-o", "PID,UID,NAME"))) : "";
                final List<String> targets;
                try { targets = snapshot.targets(plan, processes); }
                catch (IllegalArgumentException blocked) { return snapshot.grouped() + "[POLICY_BLOCKED] " + blocked.getMessage() + "\n[EXIT=126]"; }
                StringBuilder output = new StringBuilder(snapshot.grouped());
                for (String target : targets) {
                    // 按包名操作，避免检查完 PID 后号码复用而误伤系统进程。
                    String result = runner.run(Arrays.asList("am", "force-stop", target));
                    output.append("停止用户应用：").append(target).append('\n').append(result).append('\n');
                    if (!result.endsWith("[EXIT=0]")) break;
                }
                return output.toString().trim();
            }
            if (plan.kind == DeviceShellPolicy.Kind.FILE) {
                validateFiles(plan);
                List<String> argv = new ArrayList<>(plan.argv);
                for (int i = 1; i < argv.size(); i++) if (plan.operands.contains(argv.get(i))) argv.set(i, DeviceShellPolicy.normalize(argv.get(i)));
                return runner.run(argv);
            }
            return runner.run(plan.argv);
        } catch (IOException | IllegalArgumentException error) {
            return "[POLICY_BLOCKED] " + SensitiveData.redact(error.getMessage()) + "\n[EXIT=126]";
        }
    }

    private static String checked(String output) throws IOException {
        if (output == null || !output.endsWith("[EXIT=0]") || output.contains("[OUTPUT_TRUNCATED]"))
            throw new IOException("无法取得完整设备信息，未执行写入或停止操作");
        return output.substring(0, output.length() - "[EXIT=0]".length()).trim();
    }

    static void validateFiles(DeviceShellPolicy.Plan plan) throws IOException {
        List<String> paths = plan.operands;
        String op = plan.command();
        if (op.equals("cp") || op.equals("mv")) {
            File destination = checkedPath(paths.get(paths.size() - 1));
            for (int i = 0; i < paths.size() - 1; i++) {
                File source = new File(DeviceShellPolicy.normalize(paths.get(i)));
                if (op.equals("mv")) { source = checkedPath(paths.get(i)); inspectTree(source, new int[]{0}); }
                File actual = destination.isDirectory() ? checkedPath(new File(destination, source.getName()).getPath()) : destination;
                inspectTree(actual, new int[]{0});
            }
        } else {
            for (String path : paths) {
                File file = checkedPath(path);
                if (op.equals("rm") || op.equals("rmdir")) inspectTree(file, new int[]{0});
            }
        }
    }

    private static File checkedPath(String value) throws IOException {
        if (!DeviceShellPolicy.writeAllowed(value)) throw new IOException("受保护目录只读：" + value);
        File file = new File(DeviceShellPolicy.normalize(value));
        String canonical = file.getCanonicalPath();
        if (!DeviceShellPolicy.writeAllowed(canonical)) throw new IOException("链接实际指向受保护目录：" + value);
        // 共享存储别名已统一；写入链上出现其它链接时不猜测行为。
        for (File parent = file; parent != null && DeviceShellPolicy.writeAllowed(parent.getPath()); parent = parent.getParentFile()) {
            try {
                android.system.StructStat metadata = android.system.Os.lstat(parent.getPath());
                if (android.system.OsConstants.S_ISLNK(metadata.st_mode))
                    throw new IOException("写入路径含符号链接，请使用明确的实际目录：" + value);
            } catch (android.system.ErrnoException error) {
                // 新建路径可以不存在；无权读取元数据不能被当成“不是链接”。
                if (error.errno != android.system.OsConstants.ENOENT)
                    throw new IOException("无法核对路径元数据，未执行写入：" + value, error);
            }
        }
        return file;
    }
    private static void inspectTree(File file, int[] count) throws IOException {
        java.util.ArrayDeque<File> pending = new java.util.ArrayDeque<>(); pending.add(file);
        while (!pending.isEmpty()) {
            File current = pending.removeLast();
            if (++count[0] > 20000) throw new IOException("目录过大，无法完整核验；请缩小操作范围");
            checkedPath(current.getPath());
            if (current.isDirectory()) {
                File[] children = current.listFiles();
                if (children == null) throw new IOException("目录无法读取，未执行写入：" + current.getPath());
                if (children.length + count[0] > 20000) throw new IOException("目录过大，请缩小操作范围");
                pending.addAll(Arrays.asList(children));
            }
        }
    }
}
