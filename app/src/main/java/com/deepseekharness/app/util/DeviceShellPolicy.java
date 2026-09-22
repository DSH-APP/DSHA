package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 设备命令默认拒绝：只接受可完整识别的单条 argv，不执行用户提供的 shell 程序。 */
public final class DeviceShellPolicy {
    private DeviceShellPolicy() { }
    public enum Kind { READ, FILE, STOP, SENSITIVE_READ, VIRTUAL_SCREEN, DENY }
    public static final class Plan {
        public final Kind kind;
        public final List<String> argv, operands;
        public final String reason;
        private Plan(Kind kind, List<String> argv, List<String> operands, String reason) {
            this.kind = kind; this.argv = Collections.unmodifiableList(new ArrayList<>(argv));
            this.operands = Collections.unmodifiableList(new ArrayList<>(operands)); this.reason = reason;
        }
        public boolean allowed() { return kind != Kind.DENY; }
        public String command() { return argv.isEmpty() ? "" : argv.get(0); }
    }
    private static Set<String> set(String words) { return new HashSet<>(Arrays.asList(words.split(" "))); }
    private static final Set<String> READ = set("id whoami pwd uname getprop getenforce ps pidof ls cat head tail wc grep stat readlink realpath df du free uptime printenv echo printf md5sum sha1sum sha256sum sha512sum true false");
    private static final Set<String> BLOCK = set("dd mkfs mke2fs mkfs.ext4 mkfs.f2fs fdisk sfdisk cfdisk parted sgdisk blockdev losetup wipefs setenforce chcon restorecon setprop mount umount unmount fastboot flash flash_image format erase wipe recovery reboot shutdown poweroff halt bootctl boot_control lpmake lpflash update_engine_client avbctl dmctl fsck tune2fs");
    private static final Set<String> FILE = set("mkdir touch cp mv rm rmdir rename");
    private static final String[] PROTECTED = {"/dcim", "/pictures", "/android/data", "/android/obb"};
    private static final String[] ALIASES = {"/sdcard", "/mnt/sdcard", "/storage/self/primary", "/mnt/user/0/primary"};
    private static final String STORAGE = "^/storage/(?:emulated/[0-9]+|[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4})(/.*)?$";
    /** ADB 客户端使用由原生侧提供的同一份目录规则，不再维护另一份路径黑名单。 */
    public static java.util.Map<String,Object> pathRules() {
        java.util.Map<String,Object> rules = new java.util.LinkedHashMap<>();
        rules.put("protected", Arrays.asList(PROTECTED)); rules.put("aliases", Arrays.asList(ALIASES));
        rules.put("storage", STORAGE); rules.put("temporary", "/data/local/tmp"); return rules;
    }
    private static Plan plan(Kind kind, List<String> args, List<String> paths) { return new Plan(kind, args, paths, ""); }
    private static Plan deny(String reason) { return new Plan(Kind.DENY, Collections.emptyList(), Collections.emptyList(), "[POLICY_BLOCKED] " + reason); }

    public static Plan inspect(String command) {
        final List<String> args;
        try { args = split(command); }
        catch (IllegalArgumentException error) { return deny(error.getMessage()); }
        if (args.isEmpty()) return deny(com.deepseekharness.app.util.UiText.text("命令为空"));
        String name = executable(args.get(0));
        if (name == null) return deny(com.deepseekharness.app.util.UiText.text("只允许系统目录中的已识别命令"));
        if (name.equals("toybox")) {
            if (args.size() < 2) return deny(com.deepseekharness.app.util.UiText.text("缺少 toybox 子命令"));
            args.remove(0); name = args.get(0);
            if (name.contains("/")) return deny(com.deepseekharness.app.util.UiText.text("不支持嵌套执行器"));
        }
        args.set(0, name);
        if (BLOCK.contains(name) || name.startsWith("mkfs.") || name.startsWith("fsck."))
            return deny(com.deepseekharness.app.util.UiText.text("禁止设备分区、SELinux、系统属性、挂载或刷机操作：") + name);
        if (READ.contains(name)) return plan(Kind.READ, args, Collections.emptyList());
        if (name.equals("content")) {
            try { SmsQuery.validate(args); return plan(Kind.SENSITIVE_READ, args, Collections.emptyList()); }
            catch (IllegalArgumentException rejected) { return deny(rejected.getMessage()); }
        }
        if (name.equals("date")) {
            for (String arg : args.subList(1, args.size()))
                if (!arg.equals("-u") && !arg.equals("-R") && !arg.equals("-I") && !arg.startsWith("+")) return deny(com.deepseekharness.app.util.UiText.text("date 只允许读取时间"));
            return plan(Kind.READ, args, Collections.emptyList());
        }
        if (name.equals("settings")) {
            int i = 1;
            if (args.size() > 3 && args.get(i).equals("--user")) {
                if (!args.get(i + 1).matches("[0-9]+|current")) return deny(com.deepseekharness.app.util.UiText.text("无法识别用户编号"));
                i += 2;
            }
            if (args.size() <= i + 1 || !set("system secure global").contains(args.get(i + 1))) return deny(com.deepseekharness.app.util.UiText.text("无法识别 settings 参数"));
            if (args.get(i).equals("get") && args.size() == i + 3 || args.get(i).equals("list") && args.size() == i + 2)
                return plan(Kind.READ, args, Collections.emptyList());
            return deny(com.deepseekharness.app.util.UiText.text("系统设置只允许 get/list，禁止写入"));
        }
        if (name.equals("pm")) {
            if (args.size() == 3 && set("path dump").contains(args.get(1)) && packageName(args.get(2)))
                return plan(Kind.READ, args, Collections.emptyList());
            if (args.size() >= 3 && args.get(1).equals("list")
                    && set("packages permissions features libraries users instrumentation permission-groups").contains(args.get(2))) {
                for (int i = 3; i < args.size(); i++) {
                    String arg = args.get(i);
                    if (arg.equals("--user")) {
                        if (++i >= args.size() || !args.get(i).matches("[0-9]+|current")) return deny(com.deepseekharness.app.util.UiText.text("无法识别用户编号"));
                    } else if (arg.startsWith("-") && !set("-f -d -e -s -3 -i -U -u -g -a --show-versioncode").contains(arg))
                        return deny(com.deepseekharness.app.util.UiText.text("未识别的 pm 选项"));
                    else if (!arg.startsWith("-") && !arg.matches("[A-Za-z0-9_.]+")) return deny(com.deepseekharness.app.util.UiText.text("无法识别应用筛选条件"));
                }
                return plan(Kind.READ, args, Collections.emptyList());
            }
            return deny(com.deepseekharness.app.util.UiText.text("pm 只允许查询；安装、卸载、清数据和授权不开放给设备命令"));
        }
        if (name.equals("dumpsys")) {
            if (args.size() == 2 && set("-l battery power display connectivity wifi cpuinfo meminfo package window activity input sensorservice diskstats thermalservice").contains(args.get(1)))
                return plan(Kind.READ, args, Collections.emptyList());
            if (args.size() == 3 && (args.get(1).equals("activity") && set("activities processes top recents").contains(args.get(2))
                    || args.get(1).equals("window") && set("windows displays").contains(args.get(2))
                    || set("package meminfo").contains(args.get(1)) && packageName(args.get(2))))
                return plan(Kind.READ, args, Collections.emptyList());
            return deny(com.deepseekharness.app.util.UiText.text("未识别的 dumpsys 参数；禁止用诊断命令改设备状态"));
        }
        if (name.equals("find")) return inspectFind(args);
        if (name.equals("logcat")) return inspectLogcat(args);
        if (name.equals("am") && args.size() == 2 && args.get(1).equals("get-current-user"))
            return plan(Kind.READ, args, Collections.emptyList());
        if (name.equals("app_process")) return inspectVirtualScreen(args);
        if (name.equals("am") && args.size() == 3 && set("force-stop kill").contains(args.get(1)) && packageName(args.get(2)))
            return plan(Kind.STOP, args, Collections.singletonList(args.get(2)));
        if (name.equals("kill") || name.equals("killall") || name.equals("pkill")) {
            int i = 1;
            if (args.size() > i && set("-9 -15 -KILL -TERM -SIGKILL -SIGTERM").contains(args.get(i))) i++;
            if (name.equals("pkill") && args.size() > i && args.get(i).equals("-x")) i++;
            if (i >= args.size()) return deny(com.deepseekharness.app.util.UiText.text("缺少进程目标"));
            List<String> targets = args.subList(i, args.size());
            for (String target : targets) {
                if (name.equals("kill")) { if (WebProcSel.parsePid(target) < 0) return deny(com.deepseekharness.app.util.UiText.text("只接受正数 PID，禁止整组或全部进程信号")); }
                else if (!packageName(target)) return deny(com.deepseekharness.app.util.UiText.text("按名称结束应用必须使用完整包名，禁止模糊匹配"));
            }
            return plan(Kind.STOP, args, targets);
        }
        if (FILE.contains(name)) return inspectFile(args);
        return deny(com.deepseekharness.app.util.UiText.text("不认识的命令不执行：") + name);
    }

    /** 仅允许 DSHA 自己生成的 app_process 启动器；不把 app_process 变成通用 shell。 */
    private static Plan inspectVirtualScreen(List<String> args) {
        if (args.size() != 9 || !args.get(1).startsWith("-Djava.class.path=/data/app/")
                || !args.get(1).endsWith(".apk") || !args.get(2).equals("/system/bin")
                || !args.get(3).equals("com.deepseekharness.app.vscreen.VirtualScreenCore")
                || !args.get(4).equals("--launch") || !args.get(5).equals("--port")
                || !args.get(7).equals("--token")
                || !args.get(6).matches("8[0-9]{3,4}")
                || !args.get(8).matches("[a-f0-9]{32,128}"))
            return deny(com.deepseekharness.app.util.UiText.text("虚拟屏启动参数无法核验"));
        return plan(Kind.VIRTUAL_SCREEN, args, Collections.emptyList());
    }

    private static Plan inspectFile(List<String> args) {
        String name = args.get(0);
        String flags = name.equals("cp") ? "Rrfnpv" : name.equals("mv") ? "fnv" : name.equals("rm") ? "rfRdv"
                : name.equals("mkdir") ? "pv" : name.equals("rmdir") ? "v" : name.equals("touch") ? "c" : "";
        List<String> paths = new ArrayList<>(); boolean options = true;
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (options && arg.equals("--")) { options = false; continue; }
            if (options && arg.startsWith("-")) {
                if (arg.length() < 2) return deny(com.deepseekharness.app.util.UiText.text("未识别的文件操作参数"));
                for (int j = 1; j < arg.length(); j++) if (flags.indexOf(arg.charAt(j)) < 0) return deny(com.deepseekharness.app.util.UiText.text("未识别的文件操作选项"));
            } else {
                if (!absolutePath(arg) || hasGlob(arg) || Arrays.asList(arg.split("/")).contains("..")) return deny(com.deepseekharness.app.util.UiText.text("写操作必须使用明确的绝对路径，禁止通配符或上级跳转"));
                paths.add(arg);
            }
        }
        int minimum = set("cp mv rename").contains(name) ? 2 : 1;
        if (paths.size() < minimum || name.equals("rename") && paths.size() != 2) return deny(com.deepseekharness.app.util.UiText.text("文件操作参数不完整"));
        // 复制只读取源文件；目标、移动源、删除等均必须在普通可写目录。
        List<String> writes = name.equals("cp") ? Collections.singletonList(paths.get(paths.size() - 1)) : paths;
        for (String path : writes) if (!writeAllowed(path)) return deny(com.deepseekharness.app.util.UiText.text("受保护目录只读：") + path);
        if (name.equals("rename")) args.set(0, "mv");
        return plan(Kind.FILE, args, paths);
    }
    private static Plan inspectFind(List<String> args) {
        if (args.size() < 2 || !absolutePath(args.get(1))) return deny(com.deepseekharness.app.util.UiText.text("find 需要明确的读取目录"));
        for (int i = 2; i < args.size(); i++) {
            String arg = args.get(i);
            if (set("-print -print0 -ls -empty").contains(arg)) continue;
            if (set("-name -iname -path -type -maxdepth -mindepth -size -mtime -mmin").contains(arg) && ++i < args.size()) continue;
            return deny(com.deepseekharness.app.util.UiText.text("find 只允许读取条件，禁止 exec/delete 等动作"));
        }
        return plan(Kind.READ, args, Collections.emptyList());
    }
    private static Plan inspectLogcat(List<String> args) {
        boolean finite = false;
        for (int i = 1; i < args.size(); i++) {
            String arg = args.get(i);
            if (arg.equals("-d")) { finite = true; continue; }
            if (arg.equals("-t")) { if (++i >= args.size() || !args.get(i).matches("[0-9]{1,5}")) return deny(com.deepseekharness.app.util.UiText.text("无效日志行数")); finite = true; continue; }
            if (set("-v -b").contains(arg)) { if (++i >= args.size() || !args.get(i).matches("[A-Za-z0-9_,]+")) return deny(com.deepseekharness.app.util.UiText.text("无效日志参数")); continue; }
            if (arg.matches("[A-Za-z0-9_.*-]+:[VDIWEFS]")) continue;
            return deny(com.deepseekharness.app.util.UiText.text("logcat 只允许读取，禁止清日志、写文件和修改缓冲区"));
        }
        return finite ? plan(Kind.READ, args, Collections.emptyList()) : deny(com.deepseekharness.app.util.UiText.text("读取日志请指定 -d 或 -t 行数"));
    }

    public static boolean packageName(String name) { return name != null && name.matches("android|[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+"); }
    public static boolean absolutePath(String path) { return path != null && path.startsWith("/") && path.indexOf('\0') < 0; }
    public static boolean hasGlob(String path) { return path.indexOf('*') >= 0 || path.indexOf('?') >= 0 || path.indexOf('[') >= 0 || path.indexOf(']') >= 0; }
    public static String normalize(String path) {
        if (!absolutePath(path)) return "";
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
            else parts.add(part);
        }
        String normal = "/" + String.join("/", parts);
        for (String alias : ALIASES)
            if (within(normal, alias)) return "/storage/emulated/0" + normal.substring(alias.length());
        return normal.replaceFirst("^/mnt/runtime/(default|read|write|full)/emulated/", "/storage/emulated/");
    }
    private static boolean within(String path, String root) { return path.equals(root) || path.startsWith(root + "/"); }
    public static boolean writeAllowed(String raw) {
        if (!absolutePath(raw) || hasGlob(raw) || Arrays.asList(raw.split("/")).contains("..")) return false;
        String path = normalize(raw), lower = path.toLowerCase(Locale.ROOT);
        if (path.startsWith("/data/local/tmp/")) return true;
        java.util.regex.Matcher root = java.util.regex.Pattern.compile(STORAGE).matcher(path);
        if (!root.matches() || root.group(1) == null || root.group(1).equals("/")) return false;
        String suffix = root.group(1).toLowerCase(Locale.ROOT);
        for (String protectedPath : PROTECTED)
            if (within(suffix, protectedPath) || within(protectedPath, suffix)) return false;
        return !lower.endsWith("/.");
    }
    private static String executable(String name) {
        if (!name.contains("/")) return name;
        return name.startsWith("/system/bin/") && name.lastIndexOf('/') == 11 ? name.substring(12) : null;
    }
    public static List<String> split(String command) {
        if (command == null || command.length() > 8192) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("命令为空或过长"));
        List<String> result = new ArrayList<>(); StringBuilder word = new StringBuilder(); char quote = 0; boolean present = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c < 32 && c != '\t' || c == 127) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("禁止多行脚本或控制字符"));
            if (quote != 0) {
                if (c == quote) quote = 0;
                else {
                    if (quote == '"' && (c == '$' || c == '`' || c == '\\')) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("禁止变量、命令展开和转义脚本"));
                    word.append(c);
                }
            } else if (c == '\'' || c == '"') { quote = c; present = true; }
            else if (Character.isWhitespace(c)) {
                if (present) { result.add(word.toString()); word.setLength(0); present = false; }
            } else {
                if (";&|><$`(){}\\#".indexOf(c) >= 0) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("禁止脚本、管道、重定向、变量或嵌套命令；请分条执行"));
                word.append(c); present = true;
            }
        }
        if (quote != 0) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("引号不完整"));
        if (present) result.add(word.toString());
        if (result.size() > 128) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("参数过多"));
        return result;
    }
}
