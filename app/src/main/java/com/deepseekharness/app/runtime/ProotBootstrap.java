package com.deepseekharness.app.runtime;
import com.deepseekharness.app.util.Compat;

import android.content.Context;
import android.system.Os;
import android.util.Base64;
import android.util.Log;

import com.deepseekharness.app.util.SensitiveData;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * proot/proroot 启动 + rootfs 生命周期（下载/解压/离线包）。
 *
 * <p>关键设计：proot、loader、libtalloc 伪装成 lib*.so 放进 jniLibs，Android 安装时
 * 自动解压到 nativeLibraryDir（可执行目录，绕过 app 私有目录的 noexec）。运行时通过
 * PROOT_LOADER / PROOT_TMP_DIR / LD_LIBRARY_PATH 引导 proot 找到 loader 与依赖库，
 * 直接 exec {@code nativeLibraryDir/libproot.so}。
 */
public class ProotBootstrap {

    private static final String[] BUNDLE_NAMES = {
            "offline-rootfs.bin", "offline-rootfs.tar.gz", "offline-rootfs.tar", "offline-rootfs.tgz",
    };

    private final Context ctx;
    private final File baseDir;
    private final File rootfsDir;
    private final File libDir;
    private final File tmpDir;
    private final String nativeLibDir;
    private final File offlineMarkerFile;
    private final boolean forceProot;

    private static volatile Boolean hardlinkOk = null;

    public ProotBootstrap(Context c) {
        this(c, false);
    }

    /** 本次兼容重试使用 proot，不改用户保存的运行方式。 */
    public ProotBootstrap(Context c, boolean forceProot) {
        this.forceProot = forceProot;
        ctx = c.getApplicationContext();
        baseDir = new File(ctx.getFilesDir(), "linux");
        rootfsDir = new File(baseDir, "ubuntu");
        libDir = new File(baseDir, "lib");
        tmpDir = new File(baseDir, "tmp");
        nativeLibDir = ctx.getApplicationInfo().nativeLibraryDir;
        offlineMarkerFile = new File(baseDir, ".offline-extracted");
    }

    public File getRootfsDir() {
        return rootfsDir;
    }

    public boolean isOfflineExtracted() {
        return offlineMarkerFile.exists();
    }

    public boolean hasBash() {
        return new File(rootfsDir, "usr/bin/bash").exists()
                || new File(rootfsDir, "bin/bash").exists();
    }

    /** Ubuntu 基础环境版本；仅更新受管 dsh 或重新压缩不递增。 */
    public static final String OFFLINE_VERSION_ASSET = "offline-rootfs.version";

    /** 已解压 rootfs 的版本记录文件（app 私有目录，覆盖安装保留）。 */
    private File offlineVersionFile() {
        return new File(baseDir, ".offline-version");
    }

    public String environmentIdentity() {
        return com.deepseekharness.app.util.EnvironmentIdentity.expected(readAssetString(OFFLINE_VERSION_ASSET).trim(),
                com.deepseekharness.app.BuildConfig.VERSION_CODE, com.deepseekharness.app.util.Constants.DSH_VERSION);
    }

    /** 预留解压本体、Python/pnpm 与离线基础工具的安装空间。 */
    public long expandedEnvironmentBytes() {
        try {
            long bytes = Long.parseLong(readAssetString("offline-rootfs.bytes").trim());
            if (bytes > 0 && bytes < 8L * 1024 * 1024 * 1024) return bytes + 384L * 1024 * 1024;
        } catch (RuntimeException ignored) { }
        return 1280L * 1024 * 1024;
    }

    /**
     * 已解压 rootfs 的版本是否与 APK 内置离线包一致。
     * 身份不一致先进入维护；同基础环境局部更新，基础环境变化才备份并重建。
     */
    public boolean rootfsVersionMatches() {
        try {
            File vf = new File(baseDir, ".offline-identity");
            String stored = vf.isFile()
                    ? new String(Compat.readAllBytes(vf),
                    java.nio.charset.StandardCharsets.UTF_8).trim() : "";
            return com.deepseekharness.app.util.EnvironmentIdentity.matches(environmentIdentity(), stored);
        } catch (Throwable e) {
            return false;
        }
    }

    public boolean isEnvironmentReady() {
        return isOfflineExtracted() && hasBash() && rootfsVersionMatches();
    }

    public boolean canUpdateManagedRuntime() {
        try {
            if (!isOfflineExtracted() || !hasBash()) return false;
            String installed = Compat.readAll(new File(baseDir, ".offline-identity")).trim();
            if (!com.deepseekharness.app.util.ManagedRuntimeLayout.sameBase(environmentIdentity(), installed)) return false;
            org.json.JSONObject pkg = new org.json.JSONObject(Compat.readAll(new File(rootfsDir,
                    com.deepseekharness.app.util.ManagedRuntimeLayout.DSH + "/package.json")));
            return pkg.optString("version").equals(installed.split(":", -1)[2]);
        } catch (Exception error) { return false; }
    }

    /** 解压和适配全部在事务的 stage 下完成，此时既有运行时和个人目录保持原位。 */
    public List<String> stageManagedRuntime(File stage, java.util.function.Consumer<String> progress) throws IOException {
        File root = new File(stage, "linux/ubuntu");
        if (!root.mkdirs()) throw new IOException("无法建立独立运行时暂存目录");
        final String prefix = com.deepseekharness.app.util.ManagedRuntimeLayout.DSH;
        progress.accept("正在解压新版 dsh（保留现有 Ubuntu 与个人目录）…");
        try (ZipFile apk = new ZipFile(ctx.getPackageCodePath())) {
            boolean split = apk.getEntry("assets/offline-rootfs.layout") != null;
            ZipEntry bundle = split ? apk.getEntry("assets/dsh-runtime.bin") : findBundleEntry(apk);
            if (bundle == null) throw new IOException("APK 没有内置运行时");
            try (InputStream input = apk.getInputStream(bundle)) {
                TarGzipExtractor.extractSelected(input, root, 0, name -> name.equals(prefix)
                        || name.startsWith(prefix + "/") || com.deepseekharness.app.util.ManagedRuntimeLayout.alias(name)
                        || name.equals("usr/local/share/dsha/dsh-runtime.version"));
            }
        }
        progress.accept("正在准备新版内置插件和界面适配…");
        RuntimeTools.stage(ctx, root);
        for (String name : com.deepseekharness.app.util.BuiltinPlugins.DEFAULT_BUILTINS) {
            File link = new File(root, com.deepseekharness.app.util.BuiltinPlugins.entityDir(name).substring(1) + "/node_modules");
            Compat.symlink("../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules", link);
        }
        Compat.symlink("../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules", new File(root, "root/dsha-app-integration/node_modules"));
        List<String> paths = new ArrayList<>();
        for (String name : com.deepseekharness.app.util.ManagedRuntimeLayout.paths()) paths.add("linux/ubuntu/" + name);
        File global = new File(root, "usr/local/lib/node_modules");
        File[] packages = global.listFiles();
        if (packages == null) throw new IOException("新版 dsh 依赖目录缺失");
        for (File file : packages) {
            if (file.getName().startsWith("@") && !Compat.isSymbolicLink(file)) {
                File[] scoped = file.listFiles();
                if (scoped == null) throw new IOException("新版 dsh 作用域目录无法读取");
                for (File child : scoped) addManagedAlias(paths, root, child);
            } else addManagedAlias(paths, root, file);
        }
        for (String name : new String[]{"dsh", "tsc", "tsserver"}) addManagedAlias(paths, root, new File(root, "usr/local/bin/" + name));
        String identity = environmentIdentity();
        writeInstallMarker(new File(stage, "linux/.offline-identity"), identity);
        writeInstallMarker(new File(stage, "linux/.offline-extracted"), identity);
        writeInstallMarker(new File(stage, "linux/.offline-version"), readAssetString(OFFLINE_VERSION_ASSET).trim());
        paths.add("linux/.offline-identity"); paths.add("linux/.offline-extracted"); paths.add("linux/.offline-version");
        return paths;
    }

    private void addManagedAlias(List<String> paths, File stageRoot, File staged) throws IOException {
        if (!Compat.isSymbolicLink(staged)) return;
        String relative = staged.getAbsolutePath().substring(stageRoot.getAbsolutePath().length() + 1).replace(File.separatorChar, '/');
        if (!com.deepseekharness.app.util.ManagedRuntimeLayout.alias(relative)) throw new IOException("运行时别名不在受管范围");
        File current = new File(rootfsDir, relative);
        // 用户另外安装的全局实体或自行改写的链接保持原样。
        boolean owned = Compat.isSymbolicLink(current) && current.getCanonicalPath().startsWith(
                new File(rootfsDir, com.deepseekharness.app.util.ManagedRuntimeLayout.DSH).getCanonicalPath() + File.separator);
        if (!current.exists() && !Compat.isSymbolicLink(current) || owned) paths.add("linux/ubuntu/" + relative);
    }

    public void markOfflineExtracted() throws IOException {
        if (!baseDir.isDirectory() && !baseDir.mkdirs()) throw new IOException("无法建立安装标记目录");
        String identity = environmentIdentity();
        if (identity.isEmpty()) throw new IOException("APK 缺少有效环境版本，无法确认安装完成");
        writeInstallMarker(offlineVersionFile(), readAssetString(OFFLINE_VERSION_ASSET).trim());
        writeInstallMarker(new File(baseDir, ".offline-identity"), identity);
        writeInstallMarker(offlineMarkerFile, identity);
    }

    private void writeInstallMarker(File target, String value) throws IOException {
        File temporary = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); out.getFD().sync();
        }
        if (!temporary.renameTo(target)) throw new IOException("无法提交安装标记：" + target.getName());
    }

    /** 撤销解压标记：下次启动走 ExtractActivity 重新解压（配置保留在 .dsh，不删除）。 */
    public void markNotExtracted() {
        //noinspection ResultOfMethodCallIgnored
        offlineMarkerFile.delete();
    }

    /** 清除整个容器环境（rootfs + 运行时文件），下次启动重新解压。配置/对话在 .dsh，不受影响。 */
    public void uninstall() {
        try {
            new ProcessBuilder("/system/bin/rm", "-rf", baseDir.getAbsolutePath())
                    .redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            deleteRecursively(baseDir);
        }
        hardlinkOk = null; // 下次解压重新探测
    }

    private void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        try {
            if (Compat.isSymbolicLink(f)) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                return;
            }
        } catch (Throwable ignored) {
        }
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) for (File c : children) deleteRecursively(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    // ================= 运行时文件 =================

    private File findNativeLib(String name) {
        if (com.deepseekharness.app.BuildConfig.LOW_ANDROID) {
            if (name.equals("libproot.so")) name = "libproot_legacy.so";
            else if (name.equals("libprootloader.so")) name = "libprootloader_legacy.so";
        }
        File direct = new File(nativeLibDir, name);
        if (direct.isFile()) return direct;
        File libRoot = new File(nativeLibDir).getParentFile();
        if (libRoot != null && libRoot.isDirectory()) {
            File[] subs = libRoot.listFiles();
            if (subs != null) {
                for (File sub : subs) {
                    if (sub.isDirectory()) {
                        File f = new File(sub, name);
                        if (f.isFile()) return f;
                    }
                }
            }
        }
        return direct;
    }

    private String prootPath() {
        return findNativeLib("libproot.so").getAbsolutePath();
    }

    private void copyExec(File src, File dst) {
        if (src.isFile() && !dst.exists()) {
            try (InputStream in = new FileInputStream(src);
                 FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            } catch (IOException ignored) {
            }
            chmod(dst);
        }
    }

    private void chmod(File f) {
        f.setReadable(true, false);
        f.setExecutable(true, false);
        try {
            Os.chmod(f.getAbsolutePath(), 0755);
        } catch (Throwable ignored) {
        }
    }

    /** 复制 proot 的 NEEDED 依赖（libtalloc.so.2、libandroid-shmem.so），匹配 SONAME。 */
    public void ensureRuntimeFiles() {
        baseDir.mkdirs();
        tmpDir.mkdirs();
        libDir.mkdirs();
        // 这两个是 proot 的 NEEDED 依赖；proroot 只链 libdl/libc，用不到
        if ("proot".equals(runtime().id())) {
            copyExec(findNativeLib("libtalloc.so"), new File(libDir, "libtalloc.so.2"));
            copyExec(findNativeLib("libandroidshmem.so"), new File(libDir, "libandroid-shmem.so"));
        }
        ensureDshRuntimePatches();
        if (hasBash()) ensureNetworkTools();
    }

    private void ensureNetworkTools() {
        try { RuntimeTools.prepare(ctx, getRootfsDir()); }
        catch (IOException error) { Log.w("DSHA", "运行工具准备失败：" + SensitiveData.redact(String.valueOf(error))); }
    }

    // ================= dsh 运行补丁（dsh 1.2-alpha 在 Android proot 下的兼容） =================

    /**
     * 启动 dsh 前把两个已知兼容问题修掉（幂等，重装/升级后自动恢复）：
     *
     * 1. {@code /etc/resolv.conf} 为空 → node 的 DNS 解析 EAI_AGAIN（curl 是 Android
     *    二进制走 netd 不受影响，Ubuntu 的 node 读 rootfs 的 resolv.conf）。
     * 2. dsh-session-persistence-jsonl 用 {@code link(tmp, final)} 原子发布 session 日志，
     *    而 SELinux 禁 app 私有目录的 link(2)；proot 的 --link2symlink 转出的 symlink 链
     *    在 dsh 的 rm(tmp) 清理后悬空 → 发消息报 ENOENT。换成 rename（同目录原子替换，
     *    SELinux 允许），写入即正常。dsh 装了两份（顶层 + dsh 嵌套），都要 patch。
     */
    public void ensureDshRuntimePatches() {
        try {
            File resolv = new File(rootfsDir, "etc/resolv.conf");
            String r = resolv.isFile()
                    ? new String(Compat.readAllBytes(resolv),
                    java.nio.charset.StandardCharsets.UTF_8) : "";
            if (!r.contains("nameserver")) {
                Compat.write(resolv, "nameserver 8.8.8.8\nnameserver 223.5.5.5\n"
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Log.i("DSHA", "已写入容器 /etc/resolv.conf（node DNS 修复）");
            }
        } catch (Throwable e) {
            Log.w("DSHA", "resolv.conf 写入失败: " + SensitiveData.redact(String.valueOf(e)));
        }
        String[][] jsonlCopies = {
                {"usr/local/lib/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
                        "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js"},
        };
        for (String[] rels : jsonlCopies) {
            for (String rel : rels) {
                try {
                    patchLinkToRename(new File(rootfsDir, rel));
                } catch (Throwable ignored) {
                }
            }
        }
        // WebUI 目录选择器/终端直达手机存储：在 /root 下建「手机存储」软链 → /sdcard。
        // dsh 的 browse 目录选择器浏览 home(/root) 时会列出软链并对目标 stat（/sdcard 由
        // proot bind 可见），于是主目录里出现可进入的「手机存储」，工作区可建到 dsha 目录
        // 外的任意位置（配合「所有文件访问权限」即可读写）。幂等。
        try {
            File rootHome = new File(rootfsDir, "root");
            if (rootHome.isDirectory()) {
                File sdcardLink = new File(rootHome, "手机存储");
                if (!sdcardLink.exists()) {
                    try {
                        Compat.symlink("/sdcard", sdcardLink);
                        Log.i("DSHA", "已建 /root/手机存储 -> /sdcard 软链（WebUI 选工作区直达手机存储）");
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        patchLanSettingsPersistence();
    }

    /**
     * 补丁：dsh 客户端 settings 持久化强制 host。
     *
     * <p>dsh 客户端的 {@code ctx.remote.$host.isLoopback} 用 {@code window.location.hostname}
     * 判定「本页是否回环」——局域网代理页面上地址是 192.168.x.x，必然非回环 → persistence 变
     * memory → settings.describe 不加载 →「settings are unavailable in this browser」，
     * 提供方目录/模型配置在局域网设备上全不可用。而请求经 LAN 代理转发时 Host/Origin 已被改
     * 成 127.0.0.1，host 侧 isTrustedApiRequest 是接受的，所以只需把客户端 persistence 固定为
     * "host"。幂等：已 patch（字符串已变）或版本不同（找不到原串）就跳过。
     */
    private void patchLanSettingsPersistence() {
        try {
            File f = new File(rootfsDir,
                    "usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules/"
                            + "@deepseek-ai/dsh-client-ui-settings/lib/client.js");
            if (!f.isFile()) return;
            String c = new String(Compat.readAllBytes(f),
                    java.nio.charset.StandardCharsets.UTF_8);
            String target = "const persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";";
            if (!c.contains(target)) return; // 已 patch 或 dsh 版本改了写法
            String replacement = "const persistence = \"host\"; // DSHA patch: LAN 代理场景强制 host 持久化";
            Compat.write(f, c.replace(target, replacement).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            Log.i("DSHA", "已 patch dsh 客户端 settings persistence→host（局域网可用）");
        } catch (Throwable e) {
            Log.w("DSHA", "settings persistence patch 失败（不影响启动）: "
                    + SensitiveData.redact(String.valueOf(e)));
        }
    }

    /** 内置 .l2s 摊平脚本（proot --link2symlink 残留链会让目录删除/备份 ELOOP 失败）。 */
    public static final String L2S_FLATTEN_SCRIPT = "flatten-l2s.py";

    /**
     * 摊平 proot --link2symlink 留下的 .l2s 链（只供显式修复调用）。
     *
     * <p>为什么需要：Android 私有目录禁真硬链接，proot 用 --link2symlink 把 link() 模拟成
     * {@code 目标 → .l2s.<名>.<hash>.tmp0001 → ….0001} 的符号链接链。老的会话/工作区文件
     * 里散落这种链后，目录删除（rm -rf）与备份（tar）会因 ELOOP 失败 —— 这正是
     * 「工作区删不掉」的根源之一。flatten-l2s.py 把可解析的链实体化成真实文件，
     * 悬空的只报告不动，安全幂等。写入侧已由 fs-write-patch 治本，这里只清存量。
     */
    public void flattenL2sChains() {
        try {
            if (!isEnvironmentReady()) return;
            String script = readAssetString(L2S_FLATTEN_SCRIPT);
            if (script.isEmpty()) return;
            String b64 = Base64.encodeToString(script.getBytes(
                    java.nio.charset.StandardCharsets.UTF_8), Base64.NO_WRAP);
            String inject = "set -e; mkdir -p /root/.dsh; "
                    + "printf '%s' '" + b64 + "' | base64 -d > /root/.dsh/" + L2S_FLATTEN_SCRIPT + "; "
                    + "chmod +x /root/.dsh/" + L2S_FLATTEN_SCRIPT + "; ";
            // 覆盖 .dsh（会话/附件）与工作区目录两类最容易堆积 .l2s 的地方
            String workdir = ctx.getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS,
                            android.content.Context.MODE_PRIVATE)
                    .getString(com.deepseekharness.app.util.Constants.KEY_WORKDIR,
                            com.deepseekharness.app.util.Constants.DEFAULT_WORKDIR);
            String wdArg = com.deepseekharness.app.util.ShellQuote.arg(workdir);
            String cmd = inject
                    + "python3 /root/.dsh/" + L2S_FLATTEN_SCRIPT + " --root /root/.dsh 2>&1; "
                    + "test -d " + wdArg + " && python3 /root/.dsh/" + L2S_FLATTEN_SCRIPT
                    + " --root " + wdArg + " 2>&1 || true";
            String out = execAndRead(cmd, 120_000);
            if (out != null && out.contains("flattened=")
                    && !out.contains("flattened=0 dangling=0 removed=0")) {
                Log.i("DSHA", "l2s 摊平完成: " + out.trim().replace("\n", " | "));
            }
        } catch (Throwable e) {
            Log.w("DSHA", "l2s 摊平失败（不影响启动）: "
                    + SensitiveData.redact(String.valueOf(e)));
        }
    }
    /** 幂等 patch：session 持久化的 link(tmp,final) → rename(tmp,final)（见 ensureDshRuntimePatches 说明）。 */
    private void patchLinkToRename(File f) throws Exception {
        if (!f.isFile()) return;
        String c = new String(Compat.readAllBytes(f),
                java.nio.charset.StandardCharsets.UTF_8);
        if (c.contains("DSHA_ATOMIC_PUBLISH_V1")) return; // 新版保留排他发布语义，不能降回旧 rename 补丁。
        if (!c.contains("await link(tmp, finalPath)")) return; // 已 patch 或版本不同
        c = c.replace("await link(tmp, finalPath);", "await rename(tmp, finalPath);");
        c = c.replace("import { link, mkdir, mkdtemp, open,",
                "import { mkdir, mkdtemp, open, rename,");
        Compat.write(f, c.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Log.i("DSHA", "已 patch dsh session 持久化 link→rename: " + f.getAbsolutePath());
    }

    // ================= 内置插件注册 =================

    /** 内置插件注册脚本（rootfs 烘焙的四个内置插件 → web profile），资产名。 */
    public static final String BUILTIN_REGISTER_SCRIPT = "register-builtin-plugins.py";
    private static final Object PLUGIN_SCRIPT_LOCK = new Object();

    /**
     * 幂等：把内置插件注册脚本注入 rootfs 并运行，把 dsh-device-shell-guide 等四个
     * 内置插件登记进 web profile（bundles + dependencies[link:] + node_modules 链接）。
     *
     * <p>为什么需要：插件的<b>实体</b>随离线 rootfs 烘焙在 /root/dsha-*，但 dsh 只在
     * profile 的 dsh.profile.bundles 里列名、且 node_modules 下能解析到实体时才加载。
     * 重构骨架曾丢失这一步 —— 覆盖安装（rootfs 保留）与全新安装（rootfs 重新解压）
     * 两条路径都要靠它补齐注册。脚本幂等、只合并不删除；用户禁用过的插件
     * （node_modules/<name>.disabled 标记）会被尊重而跳过。见脚本头部注释。
     *
     * @return 脚本输出摘要（BUILTIN_REGISTER_OK / PARTIAL / FAIL），供日志与插件页对账。
     */
    public String registerBuiltinPlugins() {
        return runBuiltinScript("");
    }

    /**
     * 启用 / 禁用某个插件（内置或官方核心）：交给容器脚本改 profile 的 bundles。
     * 内置插件禁用会写 {@code node_modules/<name>.disabled} 标记（注册流程会尊重它），
     * 官方核心只移出 bundles。改动需重启 Web 后生效。
     *
     * @param name   插件名（如 dsh-web-mobile 或 @deepseek-ai/dsh-web-app）
     * @param enable true=启用 false=禁用
     */
    public String setPluginEnabled(String name, boolean enable) {
        if (name == null || name.isEmpty()) return "NO_NAME";
        String flag = enable ? "--enable " : "--disable ";
        return runBuiltinScript(flag + com.deepseekharness.app.util.ShellQuote.arg(name));
    }

    /** 注入注册脚本（幂等覆盖）并按需带参数运行。 */
    private String runBuiltinScript(String extraArgs) {
        synchronized (PLUGIN_SCRIPT_LOCK) {
        if (!isEnvironmentReady()) return "ENV_NOT_READY";
        if (!ensureBundledPython()) return "ERROR: Ubuntu Python 环境未就绪";
        ensureBundledPnpm(); // 包管理器异常不能阻断列表、开关和删除；缺依赖的安装会单独报错。
        try {
            // 资产由同一运行时准备器按摘要安装；重复改写会让启动缓存每次失效。
            RuntimeTools.prepare(ctx, rootfsDir);
            String cmd = "python3 -u /root/.dsh/" + BUILTIN_REGISTER_SCRIPT
                    + (extraArgs.isEmpty() ? "" : " " + extraArgs) + " 2>&1";
            return execAndRead(cmd, 90_000);
        } catch (Throwable e) {
            Log.w("DSHA", "内置插件脚本执行失败: " + SensitiveData.redact(String.valueOf(e)));
            return "ERROR: " + SensitiveData.redact(String.valueOf(e));
        }
        }
    }

    // ================= 第三方插件管理（导入/导出/GitHub 下载） =================

    /** 插件管理脚本（导入、导出、链接安装与状态读取），资产名。 */
    public static final String PLUGIN_MANAGER_SCRIPT = "plugin-manager.py";

    /**
     * 注入插件管理脚本及共用注册模块，再通过 python3 执行。
     * 末行 PLUGIN_RESULT JSON 区分成功、部分成功和失败。
     *
     * @param extraArgs 例如 {@code import /root/.dsh/import-upload.bin}、
     *                  {@code export '["dsh-web-mobile"]' /root/.dsh/export.tar.gz}、
     *                  {@code github owner repo 'branch/subdir'}
     */
    public String runPluginManager(String extraArgs) { return runPluginManager(extraArgs, ""); }

    public String runPluginManager(String extraArgs, String taskId) {
        synchronized (PLUGIN_SCRIPT_LOCK) {
            if (!isEnvironmentReady()) return "ENV_NOT_READY";
            if (!ensureBundledPython()) return "ERROR: Ubuntu Python 环境未就绪";
            ensureBundledPnpm();
            try {
                // RuntimeTools 原子写入所有共用资产，终端与界面使用同一套版本解析和管理脚本。
                RuntimeTools.prepare(ctx, getRootfsDir());
                String task = taskId != null && taskId.matches("[a-f0-9]{32}")
                        ? "DSHA_PLUGIN_TASK=" + taskId + " " : "";
                return execAndRead(task + "python3 /root/.dsh/" + PLUGIN_MANAGER_SCRIPT
                        + (extraArgs == null || extraArgs.isEmpty() ? "" : " " + extraArgs) + " 2>&1", 600_000);
            } catch (Throwable e) {
                Log.w("DSHA", "插件管理脚本执行失败: " + SensitiveData.redact(String.valueOf(e)));
                return "ERROR: " + SensitiveData.redact(String.valueOf(e));
            }
        }
    }

    /** 把本地文件推入容器（containerPath 为容器内绝对路径，如 /root/.dsh/import-upload.bin）。 */
    public boolean pushFileIntoContainer(java.io.File src, String containerPath) {
        if (src == null || !src.isFile() || containerPath == null) return false;
        try {
            java.io.File target = containerFile(containerPath);
            if (target.getParentFile() != null) target.getParentFile().mkdirs();
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return true;
        } catch (Throwable e) {
            Log.w("DSHA", "推文件进容器失败: " + SensitiveData.redact(String.valueOf(e)));
            return false;
        }
    }

    /** 从容器取出文件到本地（containerPath 为容器内绝对路径）。 */
    public boolean pullFileFromContainer(String containerPath, java.io.File dest) {
        if (containerPath == null || dest == null) return false;
        try {
            java.io.File src = containerFile(containerPath);
            if (!src.isFile()) return false;
            if (dest.getParentFile() != null) dest.getParentFile().mkdirs();
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return true;
        } catch (Throwable e) {
            Log.w("DSHA", "从容器取文件失败: " + SensitiveData.redact(String.valueOf(e)));
            return false;
        }
    }

    /** 容器内绝对路径 → 宿主文件系统路径（rootfs 根下）。 */
    private java.io.File containerFile(String containerPath) {
        String rel = containerPath.startsWith("/")
                ? containerPath.substring(1) : containerPath;
        return new java.io.File(rootfsDir, rel);
    }

    /** 读 assets 文本（Windows 检出可能是 CRLF，统一转 LF 再交给容器脚本）。 */
    private String readAssetString(String name) {
        try (InputStream in = ctx.getAssets().open(name);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8").replace("\r\n", "\n").replace("\r", "\n");
        } catch (IOException e) {
            return "";
        }
    }

    // ================= Android 组补丁（id -Gn 报错） =================

    /**
     * 把 Android 的 GID 名字补进 rootfs 的 {@code /etc/group}（幂等）。
     *
     * <p>为什么：proot 不隔离 group —— 容器进程的真实组是 Android 的
     * （1004=input、1007=log、1011=adb…），而 Ubuntu 的 {@code /etc/group} 里没有这些 ID。
     * 登录 shell 会执行 {@code $(groups)}（/etc/bash.bashrc 的 sudo 检测），
     * 逐个解析失败就刷一屏 {@code id: cannot find name for group ID 1004}。
     * 补上映射后 {@code id -Gn} / {@code groups} 正常返回名字，错误消失。
     * 名字与 AOSP android_filesystem_config.h 一致，避免误读。
     */
    public void ensureAndroidGroups() {
        try {
            if (!rootfsDir.isDirectory()) return;
            File groupFile = new File(rootfsDir, "etc/group");
            if (!groupFile.isFile()) return;
            String content = new String(Compat.readAllBytes(groupFile),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[][] known = {
                    {"input", "1004"},
                    {"log", "1007"},
                    {"adb", "1011"},
                    {"sdcard_rw", "1015"},
                    {"sdcard_r", "1028"},
                    {"ext_data_rw", "1078"},
                    {"ext_obb_rw", "1079"},
                    {"net_bt_admin", "3001"},
                    {"net_bt", "3002"},
                    {"inet", "3003"},
                    {"net_bw_stats", "3006"},
                    {"readproc", "3009"},
                    {"uhid", "3011"},
                    {"readtracefs", "3012"},
                    {"everybody", "9997"},
                    {"all_a428", "50428"},
                    {"u0_a428", "20428"},
            };
            // 静态已知映射 + 动态读本进程全部真实组（PTY 的 bash 继承 App 进程的组，
            // ROM 自定义组如 99909997 枚举补不完，直接读 /proc/self/status 全覆盖）
            java.util.LinkedHashMap<String, String> groups = new java.util.LinkedHashMap<>();
            for (String[] g : known) groups.put(g[0], g[1]);
            for (int gid : readSelfGroupList()) {
                if (!groups.containsValue(String.valueOf(gid))) {
                    groups.put("aid_" + gid, String.valueOf(gid));
                }
            }
            StringBuilder need = new StringBuilder();
            for (java.util.Map.Entry<String, String> g : groups.entrySet()) {
                // 精确匹配「:GID:」段，避免误判名字相同但 ID 不同的行
                if (content.indexOf(":" + g.getValue() + ":") < 0) {
                    need.append(g.getKey()).append(":x:").append(g.getValue()).append(":\n");
                }
            }
            if (need.length() == 0) return;
            Compat.append(groupFile, need.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Log.i("DSHA", "已补 " + groups.size() + " 个 Android 组到 /etc/group");
            // 兜底：把 /etc/bash.bashrc 里登录时执行的 $(groups) 改成吞掉 stderr。
            // 未来出现未列出的新 GID 时，id 仍会打 cannot find name，但不会再刷到终端里。
            patchBashrcGroups(groupFile);
        } catch (Throwable e) {
            Log.w("DSHA", "补 /etc/group 失败（不影响核心功能）: "
                    + SensitiveData.redact(String.valueOf(e)));
        }
    }

    /** 读本进程全部 supplementary groups（/proc/self/status 的 Groups 行）。 */
    private static java.util.List<Integer> readSelfGroupList() {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        try {
            String st = new String(Compat.readAllBytes(
                    new java.io.File("/proc/self/status")),
                    java.nio.charset.StandardCharsets.UTF_8);
            for (String line : st.split("\n")) {
                if (line.startsWith("Groups:")) {
                    for (String id : line.substring(7).trim().split("\\s+")) {
                        if (id.isEmpty()) continue;
                        try {
                            int v = Integer.parseInt(id);
                            if (v > 0 && v != 0x7fffffff) out.add(v);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 把 /etc/bash.bashrc 的 sudo 检测 {@code $(groups)} 改为 {@code $(groups 2>/dev/null)}。 */
    private void patchBashrcGroups(File groupFile) {
        try {
            File bashrc = new File(rootfsDir, "etc/bash.bashrc");
            if (!bashrc.isFile()) return;
            String c = new String(Compat.readAllBytes(bashrc),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (c.contains("groups 2>/dev/null")) return; // 已 patch
            String patched = c.replace("$(groups) ", "$(groups 2>/dev/null) ");
            if (!patched.equals(c)) {
                Compat.write(bashrc, patched.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                Log.i("DSHA", "已 patch /etc/bash.bashrc：$(groups) 加 2>/dev/null");
            }
        } catch (Throwable e) {
            Log.w("DSHA", "patch /etc/bash.bashrc 失败: "
                    + SensitiveData.redact(String.valueOf(e)));
        }
    }

    // ================= 硬链接探测 =================

    /**
     * rootfs 所在文件系统是否支持真实硬链接。支持时 proot 不加 {@code --link2symlink}
     * （该扩展会把 dsh 新建文件变成悬空链接）。Android app 私有目录（ext4/f2fs）支持，
     * 探测失败才保留扩展。
     */
    private boolean hardlinkSupported() {
        Boolean cached = hardlinkOk;
        if (cached != null) return cached;
        synchronized (ProotBootstrap.class) {
            if (hardlinkOk != null) return hardlinkOk;
            boolean ok = false;
            File dir = rootfsDir.isDirectory() ? rootfsDir : baseDir;
            File src = new File(dir, ".dsha-linkprobe");
            File dst = new File(dir, ".dsha-linkprobe.hl");
            try {
                dir.mkdirs();
                src.delete();
                dst.delete();
                Compat.write(src, new byte[]{'o', 'k'});
                Compat.link(src, dst);
                ok = dst.isFile() && dst.length() == 2;
            } catch (Throwable e) {
                ok = false;
                Log.w("DSHA", "硬链接探测失败，保留 --link2symlink: "
                        + SensitiveData.redact(String.valueOf(e)));
            } finally {
                src.delete();
                dst.delete();
            }
            hardlinkOk = ok;
            Log.i("DSHA", "硬链接支持=" + ok);
            return ok;
        }
    }

    private void applyL2sEnv(ProcessBuilder pb) {
        if (hardlinkSupported()) return;
        try {
            File l2s = new File(rootfsDir, ".l2s");
            //noinspection ResultOfMethodCallIgnored
            l2s.mkdirs();
            pb.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    // ================= 运行时选择 =================

    public ContainerRuntime runtime() {
        try {
            if (!forceProot && android.os.Build.VERSION.SDK_INT >= 26 && "proroot".equals(ctx.getSharedPreferences("deepseekharness", Context.MODE_PRIVATE)
                    .getString("container_runtime", "proot"))) {
                ContainerRuntime pr = new ContainerRuntime.Proroot(
                        ctx, ContainerRuntime.Proroot.defaultDir(ctx));
                if (pr.available()) {
                    pr.prepare();
                    return pr;
                }
                Log.w("DSHA", "proroot 不可用，本次降回 proot: "
                        + SensitiveData.redact(pr.unavailableReason()));
            }
        } catch (Throwable e) {
            Log.w("DSHA", "选择运行时失败，降回 proot: "
                    + SensitiveData.redact(String.valueOf(e)));
        }
        return new ContainerRuntime.Proot(ctx, findNativeLib("libproot.so"));
    }

    private List<String> baseProotArgv() {
        return runtime().baseArgv(rootfsDir, hardlinkSupported());
    }

    /** proot 运行环境（两个 exec 入口共用）。proroot 是 LD_PRELOAD 方案，对 LD_LIBRARY_PATH 敏感。 */
    private void applyProotEnv(ProcessBuilder pb) {
        ensureNetworkTools();
        applyProotEnv(pb, runtime(), hardlinkSupported());
    }

    /** 维护恢复后重新同步受管资产；此入口保留异常，让维护事务能回滚。 */
    public void prepareRuntimeTools() throws IOException { RuntimeTools.invalidate(); RuntimeTools.prepare(ctx, rootfsDir); }

    /** 显式运行时入口不重读偏好；argv 与 env 必须属于同一个运行时。 */
    private void applyProotEnv(ProcessBuilder pb, ContainerRuntime rt, boolean hardlinks) {
        if ("proot".equals(rt.id())) {
            pb.environment().put("PROOT_TMP_DIR", tmpDir.getAbsolutePath());
            if (!hardlinks) {
                File l2s = new File(rootfsDir, ".l2s");
                l2s.mkdirs();
                pb.environment().put("PROOT_L2S_DIR", l2s.getAbsolutePath());
            }
            pb.environment().put("PROOT_LOADER",
                    findNativeLib("libprootloader.so").getAbsolutePath());
            pb.environment().put("PROOT_LOADER_32",
                    findNativeLib("libprootloader32.so").getAbsolutePath());
            pb.environment().put("LD_LIBRARY_PATH",
                    libDir.getAbsolutePath() + ":" + findNativeLib("libproot.so").getParent());
        }
        try {
            rt.applyEnv(pb, baseDir, libDir, tmpDir);
        } catch (Throwable ignored) {
        }
        // guest 侧环境
        pb.environment().put("HOME", "/root");
        pb.environment().put("PATH",
                "/root/dsh-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("DEBIAN_FRONTEND", "noninteractive");
        RuntimeTools.applyEnvironment(pb.environment());
    }

    // ================= 执行 =================

    /** 在 rootfs 内执行 bash 命令，返回进程（stderr 并入 stdout）。 */
    public Process execRootfs(String bashCommand) throws IOException {
        ContainerRuntime rt = runtime();
        boolean hardlinks = hardlinkSupported();
        ensureNetworkTools();
        return startRootfs(bashCommand, rt, hardlinks);
    }

    /**
     * 安装流式入口：固定 proot，沿用兼容版 native 库选择和共用挂载，不修改用户运行时偏好。
     * 仅补原生 loader 依赖与临时目录，不写 Python/pnpm/补丁/插件资产；调用方拥有进程并回收。
     */
    public Process execRootfsForInstall(String bashCommand) throws IOException {
        synchronized (ProotBootstrap.class) {
            // 诊断与安装可以同时首次调用，不能在另一线程尚未复制完时执行半份 native 依赖。
            baseDir.mkdirs(); tmpDir.mkdirs(); libDir.mkdirs();
            copyExec(findNativeLib("libtalloc.so"), new File(libDir, "libtalloc.so.2"));
            copyExec(findNativeLib("libandroidshmem.so"), new File(libDir, "libandroid-shmem.so"));
        }
        // 安装始终用 link2symlink；避免检查为探测硬链接额外写用户目录。
        return startRootfs(bashCommand, new ContainerRuntime.Proot(ctx, findNativeLib("libproot.so")), false);
    }

    private Process startRootfs(String bashCommand, ContainerRuntime rt, boolean hardlinks) throws IOException {
        return startRootfs(bashCommand, rt, hardlinks, false);
    }

    private Process startRootfs(String bashCommand, ContainerRuntime rt, boolean hardlinks, boolean isolated) throws IOException {
        List<String> argv = rt.baseArgv(rootfsDir, hardlinks);
        argv.add("/bin/bash");
        argv.add("-c");
        argv.add(bashCommand);
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        Compat.redirectStdinDevNull(pb);
        applyProotEnv(pb, rt, hardlinks);
        return isolated ? IsolatedInstallProcess.start(pb, tmpDir) : pb.start();
    }

    /** 仅供新环境离线安装；独立宿主进程组保证 proroot 的子进程一并回收。 */
    Process execRootfsForColdInstall(String command) throws IOException {
        ContainerRuntime rt = new ContainerRuntime.Proroot(ctx, ContainerRuntime.Proroot.defaultDir(ctx));
        return startRootfs(command, rt, false, true);
    }

    /** 同步执行 rootfs 命令并读回输出（默认 60s 超时防卡死）。 */
    public String execAndRead(String bashCommand) {
        return execAndRead(bashCommand, 60_000);
    }

    public String execAndRead(String bashCommand, long timeoutMs) {
        return execAndRead(bashCommand, timeoutMs, false);
    }

    private String execAndRead(String bashCommand, long timeoutMs, boolean forceProot) {
        try {
            com.deepseekharness.app.util.BoundedProcessRunner.Result result =
                    collectRootfs(bashCommand, timeoutMs, forceProot);
            if (result.timedOut) return "ERROR: 命令执行超时（" + timeoutMs / 1000 + " 秒），本次进程已停止";
            return result.output;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); return "ERROR: 命令等待被中断";
        } catch (Throwable e) {
            return "ERROR: " + SensitiveData.redact(String.valueOf(e));
        }
    }

    /** 同步作用域覆盖启动准备和读取；回收未确认时，后台进程仍计入维护保护。 */
    private com.deepseekharness.app.util.BoundedProcessRunner.Result collectRootfs(
            String command, long timeoutMs, boolean forceProot) throws IOException, InterruptedException {
        com.deepseekharness.app.core.RuntimeTasks work = com.deepseekharness.app.core.RuntimeTasks.begin();
        Process process = null;
        try {
            process = forceProot ? execRootfsForInstall(command) : execRootfs(command);
            return com.deepseekharness.app.util.BoundedProcessRunner.collect(process, timeoutMs, 256 * 1024, Compat::destroy);
        } finally {
            if (process != null && !com.deepseekharness.app.util.ProcessTermination.exited(process))
                work.retainUntilExit(process);
            else work.close();
        }
    }

    /**
     * 用 proot（非 proroot）运行时执行并读回输出。
     * python 等依赖 Android linker 的二进制在 proroot（LD_PRELOAD 方案）下可能找不到 libc，
     * 而 proot 走真实 linker64，对这类二进制最稳。显式选择运行时，不临时改 SharedPreferences。
     */
    public String execAndReadWithProot(String bashCommand, long timeoutMs) {
        return execAndRead(bashCommand, timeoutMs, true);
    }

    /** 同步执行 rootfs 命令，退出码非 0 抛异常。 */
    public String execChecked(String bashCommand) throws IOException {
        try {
            com.deepseekharness.app.util.BoundedProcessRunner.Result result = collectRootfs(bashCommand, 600_000, false);
            if (result.timedOut) throw new IOException("命令执行超时（600 秒），本次进程已停止");
            if (result.exitCode != 0) {
                String out = result.output;
                throw new IOException("退出码 " + result.exitCode + "：\n"
                        + SensitiveData.redact(out.length() > 600 ? out.substring(out.length() - 600) : out));
            }
            return result.output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("命令被中断", e);
        }
    }

    // ================= PTY 终端（Termux terminal-view） =================

    /**
     * 交互式 bash 会话（持久进程，可读写 stdin/stdout；cd/export 状态保持，供内置终端）。
     * 与 execRootfs 的差别：不带 -c、不重定向 stdin 到 /dev/null，且补 DSH_CONFIRM 交互确认。
     */
    public Process execRootfsInteractive() throws IOException {
        com.deepseekharness.app.core.RuntimeTasks work = com.deepseekharness.app.core.RuntimeTasks.beginDetached();
        Process process = null;
        try {
        ensureRuntimeFiles();
        ensureBundledPython();
        ensureBundledPnpm();
        ensureAndroidGroups(); // 登录 shell 的 $(groups) 依赖 /etc/group 里有 Android GID，先补齐
        java.util.List<String> argv = baseProotArgv();
        argv.add("/bin/bash");
        ProcessBuilder pb = new ProcessBuilder(argv).redirectErrorStream(true);
        applyProotEnv(pb);
        // 交互终端：危险命令启用确认
        pb.environment().put("DSH_CONFIRM", "1");
        pb.environment().put("DSH_INTERACTIVE", "1");
            process = pb.start();
            work.retainUntilExit(process);
            return process;
        } catch (IOException | RuntimeException | Error error) {
            if (process == null || com.deepseekharness.app.util.ProcessTermination.exited(process)) work.close();
            // 已启动的进程保留异步登记，不能在 watcher 启动失败时放行环境维护。
            throw error;
        }
    }

    /** PTY 会话的 argv：与 execRootfs 共用同一份 proot 构造逻辑（见 AGENTS.md 单源约束）。 */
    public String[] ptyArgv(String... guestCmd) {
        java.util.List<String> argv = baseProotArgv();
        if (guestCmd == null || guestCmd.length == 0) {
            // 部分 Android/容器运行时组合创建的 PTY 会保留 -echo（输入看不到、回车却执行）。
            // 先在同一个 PTY 上恢复标准模式再 exec 登录 shell，这条准备命令不留中间进程。
            argv.add("/bin/bash");
            argv.add("-c");
            argv.add("stty sane 2>/dev/null || stty echo icanon 2>/dev/null || true; "
                    + "exec /bin/bash -l");
        } else {
            java.util.Collections.addAll(argv, guestCmd);
        }
        return argv.toArray(new String[0]);
    }

    /** PTY 会话的环境变量（KEY=VALUE）。借临时 ProcessBuilder 复用 applyProotEnv，避免重抄漏项。 */
    public String[] ptyEnv() {
        ensureRuntimeFiles();
        ensureBundledPython();
        ensureBundledPnpm();
        ProcessBuilder probe = new ProcessBuilder("/system/bin/true");
        applyProotEnv(probe);
        java.util.Map<String, String> m = probe.environment();
        // UTF-8 locale：不设的话 bash 用 C locale，中文输入/显示会乱码（中文字节被当单字节处理）
        m.put("LANG", "C.UTF-8");
        m.put("LC_ALL", "C.UTF-8");
        java.util.List<String> out = new ArrayList<>(m.size());
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out.add(e.getKey() + "=" + e.getValue());
        }
        return out.toArray(new String[0]);
    }

    /** 冒烟测试：proot 能否 exec + 进 rootfs。 */
    public String smokeTest() {
        try {
            ensureRuntimeFiles();
            StringBuilder diag = new StringBuilder();
            diag.append("proot 路径: ").append(prootPath()).append("\n");
            diag.append("nativeLibDir: ").append(nativeLibDir).append("\n");
            String out = execAndRead("/bin/echo SMOKE_OK");
            diag.append("rootfs exec: ").append(out == null ? "" : out.trim()).append("\n");
            return SensitiveData.redact(diag.toString());
        } catch (Throwable e) {
            return SensitiveData.redact("PROOT_FAIL: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage());
        }
    }

    // ================= 离线 rootfs 解压 =================

    public boolean hasOfflineBundle() {
        try (ZipFile z = new ZipFile(ctx.getPackageCodePath())) {
            if (findBundleEntry(z) != null) return true;
        } catch (Exception ignored) {
        }
        for (String n : BUNDLE_NAMES) {
            try {
                ctx.getAssets().open(n).close();
                return true;
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    private ZipEntry findBundleEntry(ZipFile z) {
        for (String n : BUNDLE_NAMES) {
            ZipEntry e = z.getEntry("assets/" + n);
            if (e != null && !e.isDirectory()) return e;
            e = z.getEntry(n);
            if (e != null && !e.isDirectory()) return e;
        }
        ZipEntry best = null;
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String name = e.getName();
            if (e.isDirectory()) continue;
            if (name.contains("offline-rootfs") || name.contains("offline_rootfs")) {
                if (best == null || e.getSize() > best.getSize()) best = e;
            }
        }
        return best;
    }

    /**
     * 从 APK 内置包解压 rootfs。优先按 zip 条目流式解压（不经 AssetManager，
     * 也不先拷 300MB 到 tmp）。
     */
    /** 解压字节与后续安装阶段分开报告，避免安装工具时界面仍显示“正在解压”。 */
    public interface ExtractionProgress extends java.util.function.BiConsumer<Long, Long> {
        void onStage(String stage);
    }

    private static void extractionStage(java.util.function.BiConsumer<Long, Long> progress, String stage) {
        if (progress instanceof ExtractionProgress) ((ExtractionProgress) progress).onStage(stage);
    }

    public void extractOfflineBundle(java.util.function.BiConsumer<Long, Long> onProgress)
            throws IOException {
        // 进程重启后旧环境可能正被维护日志保护；必须在任何目录/资产写入之前拒绝覆盖。
        if (com.deepseekharness.app.BackupManager.hasPendingMaintenance(ctx.getFilesDir())
                && !com.deepseekharness.app.BackupManager.isDataTaskOwner())
            throw new IOException("上次环境维护尚未完成，请先恢复中断维护；现有目录未覆盖");
        File[] previous = rootfsDir.listFiles();
        if (rootfsDir.exists() && (previous == null || previous.length != 0))
            throw new IOException("已有运行环境，必须先通过备份和维护事务重建；禁止直接覆盖旧数据");
        extractionStage(onProgress, "准备解压");
        ensureRuntimeFiles();
        ZipFile apk = null;
        InputStream raw = null;
        long archiveBytes = -1;
        try {
        try {
            apk = new ZipFile(ctx.getPackageCodePath());
            ZipEntry e = findBundleEntry(apk);
            if (e != null) { raw = apk.getInputStream(e); archiveBytes = e.getSize(); }
        } catch (IOException ignored) {
            if (apk != null) {
                try { apk.close(); } catch (IOException ignored2) { }
                apk = null;
            }
        }
        if (raw == null) {
            IOException last = null;
            for (String n : BUNDLE_NAMES) {
                try {
                    raw = ctx.getAssets().open(n);
                    break;
                } catch (IOException e) {
                    last = e;
                }
            }
            if (raw == null) {
                throw last != null ? last : new IOException("assets 里也没有离线包");
            }
        }

        InputStream counted = raw;
        final java.util.function.BiConsumer<Long, Long> cb = onProgress;
        final long totalBytes = archiveBytes;
        if (cb != null) {
            counted = new java.io.FilterInputStream(raw) {
                long done = 0;
                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = super.read(b, off, len);
                    if (n > 0) {
                        done += n;
                        cb.accept(done, totalBytes);
                    }
                    return n;
                }
            };
        }

        // 覆盖安装换了内置包（版本不符）时，先清掉旧 rootfs 再解压，
        // 避免旧版残留文件（alpha.5 独有的 dsh 文件）与新包混在一起
        rootfsDir.mkdirs();
        extractionStage(onProgress, "解压 Ubuntu 与 Node");
        TarGzipExtractor.extractAuto(counted, rootfsDir, 0);
        if ("split-runtime-v1".equals(readAssetString("offline-rootfs.layout").trim())) {
            extractionStage(onProgress, "解压 dsh 与内置依赖");
            ZipEntry runtime = apk == null ? null : apk.getEntry("assets/dsh-runtime.bin");
            if (apk != null && runtime == null) throw new IOException("APK 缺少独立 dsh 运行时，安装未完成");
            try (InputStream input = apk == null ? ctx.getAssets().open("dsh-runtime.bin") : apk.getInputStream(runtime)) {
                TarGzipExtractor.extractAuto(input, rootfsDir, 0);
            }
        }
        extractionStage(onProgress, "安装 Python 与 pnpm");
        installBundledPython(rootfsDir);
        installBundledPnpm(rootfsDir);
        extractionStage(onProgress, "准备应用工具");
        RuntimeTools.prepare(ctx, getRootfsDir());
        ensureAndroidGroups();
        extractionStage(onProgress, "安装离线 curl、git 与证书");
        installBundledUbuntuTools(onProgress);
        extractionStage(onProgress, "适配 dsh 运行时");
        ensureDshRuntimePatches();
        markOfflineExtracted();
        extractionStage(onProgress, "解压与离线安装完成");
        } finally {
            try { if (raw != null) raw.close(); }
            finally { if (apk != null) apk.close(); }
        }
    }

    private static final Object PYTHON_LOCK = new Object();

    /** 在新解压环境中通过 dpkg 离线安装，保留正常包数据库与维护脚本。 */
    private void installBundledUbuntuTools(java.util.function.BiConsumer<Long, Long> progress) throws IOException {
        File packages = new File(rootfsDir, "root/.dsha-bundled-tools");
        if (!packages.mkdir()) throw new IOException("离线工具临时目录已存在，已停止覆盖");
        try (InputStream input = ctx.getAssets().open("ubuntu-tools.bin")) {
            TarGzipExtractor.extractAuto(input, packages, 0);
        }
        try {
            boolean fast = IsolatedInstallProcess.supported()
                    && new ContainerRuntime.Proroot(ctx, ContainerRuntime.Proroot.defaultDir(ctx)).available();
            com.deepseekharness.app.util.BoundedProcessRunner.Result result = null;
            if (fast) {
                try { result = collectColdInstall(true); }
                catch (IOException unavailable) { Log.w("DSHA", "快速离线安装不可用：" + SensitiveData.redact(String.valueOf(unavailable))); }
                if (result == null || result.timedOut || result.exitCode != 0
                        || !result.output.contains("\nDSHA_UBUNTU_TOOLS_READY\n")) {
                    extractionStage(progress, "使用兼容方式继续安装离线工具");
                    result = collectColdInstall(false);
                }
            } else result = collectColdInstall(false);
            if (result.timedOut || result.exitCode != 0 || !result.output.contains("\nDSHA_UBUNTU_TOOLS_READY\n"))
                throw new IOException("离线基础工具安装失败，原环境可回切：\n" + SensitiveData.redact(
                        result.output.substring(Math.max(0, result.output.length() - 1800))));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new IOException("离线基础工具安装被中断", error);
        }
    }

    private com.deepseekharness.app.util.BoundedProcessRunner.Result collectColdInstall(boolean fast)
            throws IOException, InterruptedException {
        com.deepseekharness.app.core.RuntimeTasks work = com.deepseekharness.app.core.RuntimeTasks.begin();
        Process process = null;
        try {
            String command = "/bin/bash /root/dsh-bin/install-ubuntu-tools";
            process = fast ? execRootfsForColdInstall(command) : execRootfsForInstall(command);
            return com.deepseekharness.app.util.BoundedProcessRunner.collect(process, 180_000, 256 * 1024, Compat::destroy);
        } finally {
            try { if (process instanceof IsolatedInstallProcess) ((IsolatedInstallProcess) process).close(); }
            finally {
                if (process != null && !com.deepseekharness.app.util.ProcessTermination.exited(process)) work.retainUntilExit(process);
                else work.close();
            }
        }
    }

    /** 标准版统一用 glibc Python；不再把另一套 Termux Python 重复写入 rootfs。 */
    private void installBundledPython(File stage) throws IOException {
        synchronized (PYTHON_LOCK) {
            File py = new File(stage, "usr/bin/python3.12");
            File enc = new File(stage, "usr/lib/python3.12/encodings/__init__.py");
            if (!py.isFile() || py.length() == 0 || !enc.isFile()) {
                try (InputStream input = openPythonAsset()) {
                    TarGzipExtractor.extractAuto(input, stage, 0);
                }
            }
            if (!py.isFile() || !enc.isFile()) throw new IOException("Ubuntu Python 运行环境不完整");
            // 标准库的 C 扩展还依赖 SQLite/readline；仅有 Python 主程序并不代表它们可用。
            File sqlite = new File(stage, "usr/lib/aarch64-linux-gnu/libsqlite3.so.0");
            File readline = new File(stage, "usr/lib/aarch64-linux-gnu/libreadline.so.8");
            if (!sqlite.isFile() || sqlite.length() == 0 || !readline.isFile() || readline.length() == 0) {
                try (InputStream input = ctx.getAssets().open("python-support.bin")) {
                    TarGzipExtractor.extractAuto(input, stage, 0);
                }
            }
            if (!sqlite.isFile() || !readline.isFile()) throw new IOException("Python 动态库不完整");
            py.setExecutable(true, false);
            File command = new File(stage, "usr/bin/python3");
            if (!command.getCanonicalFile().equals(py.getCanonicalFile())) {
                if ((command.exists() || Compat.isSymbolicLink(command)) && !command.delete())
                    throw new IOException("无法更新 Python 命令入口");
                try {
                    Compat.symlink("python3.12", command);
                } catch (Exception error) {
                    Compat.copy(py, command, true);
                    command.setExecutable(true, false);
                }
            }
            Compat.write(new File(stage, "root/.dsha-python-version"),
                    "3.12-glibc-arm64\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** 老用户覆盖安装时按需补齐 Python，不重解压或删除其 rootfs。 */
    public boolean ensureBundledPython() {
        return ensureGlibcPython();
    }

    public boolean ensureGlibcPython() {
        if (!rootfsDir.isDirectory()) return false;
        try {
            installBundledPython(rootfsDir);
            return true;
        } catch (Exception error) {
            Log.w("DSHA", "Ubuntu Python 安装失败: " + SensitiveData.redact(String.valueOf(error)));
            return false;
        }
    }

    private static final Object PNPM_LOCK = new Object();

    /** 放在独立目录，不覆盖用户通过 npm 安装或升级的全局包管理器。 */
    private void installBundledPnpm(File stage) throws IOException {
        synchronized (PNPM_LOCK) {
            File entry = new File(stage, "usr/local/lib/dsha-pnpm/bin/pnpm.cjs");
            File marker = new File(stage, "root/.dsha-pnpm-version");
            if (!entry.isFile() || !marker.isFile()
                    || !"10.34.5".equals(new String(Compat.readAllBytes(marker),
                    java.nio.charset.StandardCharsets.UTF_8).trim())) {
                try (InputStream input = ctx.getAssets().open("pnpm-runtime.bin")) {
                    TarGzipExtractor.extractAuto(input, stage, 0);
                }
                if (!entry.isFile()) throw new IOException("离线 pnpm 入口缺失");
                Compat.write(marker, "10.34.5\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            File wrapper = new File(stage, "root/dsh-bin/pnpm");
            if (!wrapper.isFile() || wrapper.length() == 0) {
                File directory = wrapper.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs())
                    throw new IOException("无法创建 pnpm 命令目录");
                if ((wrapper.exists() || Compat.isSymbolicLink(wrapper)) && !wrapper.delete())
                    throw new IOException("无法更新 pnpm 命令入口");
                Compat.write(wrapper, ("#!/bin/sh\n"
                        + "exec /usr/local/bin/node /usr/local/lib/dsha-pnpm/bin/pnpm.cjs \"$@\"\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                wrapper.setExecutable(true, false);
            }
        }
    }

    public boolean ensureBundledPnpm() {
        try {
            installBundledPnpm(rootfsDir);
            return true;
        } catch (Exception error) {
            Log.w("DSHA", "离线 pnpm 安装失败: " + SensitiveData.redact(String.valueOf(error)));
            return false;
        }
    }

    private InputStream openPythonAsset() throws IOException {
        try {
            return ctx.getAssets().open("glibc-python.bin");
        } catch (IOException ignored) {
            // 兼容旧资产构建入口，新的标准版只打包 bin。
            try {
                return ctx.getAssets().open("glibc-python.tar.gz");
            } catch (IOException missing) {
                return ctx.getAssets().open("glibc-python.tar");
            }
        }
    }
}
