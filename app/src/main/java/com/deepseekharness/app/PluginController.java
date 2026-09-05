package com.deepseekharness.app;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;

/**
 * 插件控制器：已装插件枚举、启用/禁用、安装/卸载、导入导出，以及插件市场
 * （索引抓取与缓存、条目解析、仓库信息、安装前预检）。
 *
 * <p>这是从 {@link HarnessController} 里整块搬出来的职责 —— 那个类曾长到 7793 行，
 * 而「同一份判断散落两处」的坑基本都出在它内部。搬家时逻辑一行未改，只把对宿主的
 * 调用加了 {@code host.} 前缀。
 *
 * <p><b>耦合面刻意收窄</b>：构造器拿到的三样（宿主、Context、ProotBootstrap）
 * 加七个宿主方法（{@code logActivity} / {@code readAsset} / {@code detectWorkdir} /
 * {@code ensureBuiltinBundles} / {@code runAssetScript} / {@code depsSelfHeal} /
 * {@code copyFile}）就是全部。改动时不要把这个面重新摊开。
 *
 * <p><b>UI 层不直接用本类</b>：PluginFragment 与 BackupManager 走
 * {@link HarnessController} 上的同名转发方法，那一小块转发同时也是「插件公开面有多大」
 * 的唯一清单。
 */
class PluginController {

    private final HarnessController host;
    private final Context appContext;
    private final ProotBootstrap proot;

    PluginController(HarnessController host, Context appContext, ProotBootstrap proot) {
        this.host = host;
        this.appContext = appContext;
        this.proot = proot;
    }

    private final java.util.Map<String, String[]> repoCache = new java.util.concurrent.ConcurrentHashMap<>();

    private String getVersionName() {
        try {
            return appContext.getPackageManager().getPackageInfo(appContext.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "1.1.0";
        }
    }

    /** 供 UI 层组装 User-Agent 用（如插件市场 star 刷新请求） */
    public String getVersionNameForUa() {
        return getVersionName();
    }


    /** 列出已装插件：返回 [名称, 状态(启用/禁用)] 数组（合并所有候选目录，先去重） */
    public String[][] listPlugins() {
        return listPlugins(false);
    }

    /**
     * 已装插件：manifest 的 dsh.profile.bundles（系统插件层）+ 实际声明 dsh 元数据的包（用户实装）。
     * 不把 node_modules 顶层普通依赖（react 等）误当插件；隐藏自带后仅剩用户实装。
     */
    public String[][] listPlugins(boolean hideBuiltin) {
        java.util.Set<String> builtin = hideBuiltin ? readBuiltinSnapshot() : new java.util.HashSet<>();
        try {
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            names.addAll(readBundles());            // manifest dsh.profile.bundles（系统插件层）
            names.addAll(scanDshDeclaredPlugins()); // package.json 带 dsh 字段的包（用户实装）
            if (hideBuiltin) names.removeIf(n -> isBuiltinName(n, builtin));
            names.removeIf(n -> n == null || n.startsWith("."));
            if (names.isEmpty()) return new String[0][];
            java.util.List<String[]> list = new java.util.ArrayList<>();
            for (String n : names) {
                list.add(new String[]{n, isPluginDisabled(n) ? "禁用" : "启用"});
            }
            return list.toArray(new String[0][]);
        } catch (Exception ignored) {
        }
        return new String[0][];
    }

    /** 读 profile package.json 的 dsh.profile.bundles（官方插件层清单） */
    private java.util.Set<String> readBundles() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        try {
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return set;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            org.json.JSONObject dshObj = root.optJSONObject("dsh");
            if (dshObj == null) return set;
            org.json.JSONObject profObj = dshObj.optJSONObject("profile");
            if (profObj == null) return set;
            org.json.JSONArray bundles = profObj.optJSONArray("bundles");
            if (bundles != null) for (int i = 0; i < bundles.length(); i++) {
                String v = bundles.optString(i, "").trim();
                if (!v.isEmpty()) set.add(v);
            }
        } catch (Throwable ignored) {
        }
        return set;
    }

    /** 扫描所有已装包（node_modules 顶层 + .pnpm），返回 package.json 声明了 dsh 字段的包名（dsh 插件的判定标准）。
     *  注意：禁用 = 改名 .disabled，其 package.json 读不到（实体改名后路径没了 / 链接悬空）。
     *  所以 .disabled 条目用「.dsha-src 源记录 或 实体目录仍存在」判定为已装插件，
     *  否则禁用后插件从列表消失（用户反馈：禁用就原地消失）。 */
    private java.util.Set<String> scanDshDeclaredPlugins() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String d : HarnessController.PLUGIN_DIRS) {
            java.io.File base = new java.io.File(proot.getRootfsDir(), d.substring(1));
            // 顶层
            java.io.File[] top = base.isDirectory() ? base.listFiles() : null;
            if (top != null) for (java.io.File f : top) {
                String n = f.getName();
                String plain = n.endsWith(".disabled") ? n.substring(0, n.length() - 9) : n;
                if (plain.startsWith(".")) continue;
                if (hasDshField(f)) {
                    set.add(plain);
                } else if (n.endsWith(".disabled")) {
                    // 禁用条目：实体 package.json 读不到，但只要有 .dsha-src 源记录
                    // （togglePlugin 禁用时保存）或实体目录仍在 → 仍算已装（禁用态）
                    java.io.File srcRec = new java.io.File(proot.getRootfsDir(),
                            "root/.dsh/profiles/web/.dsha-src-" + plain);
                    java.io.File onDir = new java.io.File(base, plain);
                    if (srcRec.isFile() || onDir.exists()) set.add(plain);
                }
            }
            // .pnpm 虚拟目录
            java.io.File pnpm = new java.io.File(base, ".pnpm");
            java.io.File[] es = pnpm.isDirectory() ? pnpm.listFiles(java.io.File::isDirectory) : null;
            if (es == null) continue;
            for (java.io.File e : es) {
                java.io.File nm = new java.io.File(e, "node_modules");
                java.io.File[] pkgs = nm.isDirectory() ? nm.listFiles() : null;
                if (pkgs == null) continue;
                for (java.io.File p : pkgs) {
                    String n = p.getName();
                    if (n.startsWith(".")) continue;
                    if (hasDshField(p)) set.add(n);
                }
            }
        }
        return set;
    }

    /** 判断包目录的 package.json 是否声明 dsh 元数据（顶层 "dsh" 对象存在） */
    private boolean hasDshField(java.io.File pkgDir) {
        try {
            java.io.File pf = new java.io.File(pkgDir, "package.json");
            if (!pf.isFile() || pf.length() > 300000) return false;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            return root.has("dsh");
        } catch (Exception e) {
            return false;
        }
    }




    /** 判断插件当前启用/禁用：存在 <name>.disabled 则禁用（顶层或 .pnpm 内精确匹配）。
     *  注意：必须用 existsOrBrokenLink（悬空链接的 File.exists() 返回 false，
     *  会把禁用误判成启用——用户反馈"切页回来按钮显示启用"）。 */
    private boolean isPluginDisabled(String name) {
        // 新机制：profile 的 patch 层里写了 disabled: true
        try {
            java.util.List<String> ids = readPluginPatchIds(name);
            if (!ids.isEmpty()) {
                java.util.Set<String> off = PatchToggle.disabledIds(
                        readTextFile(profilePatchFile()));
                for (String id : ids) {
                    if (off.contains(id)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        // 老机制：目录改名（历史状态仍要认，否则升级后所有旧的禁用都会失效）
        for (String d : HarnessController.PLUGIN_DIRS) {
            java.io.File base = new java.io.File(proot.getRootfsDir(), d.substring(1));
            if (existsOrBrokenLink(new java.io.File(base, name + ".disabled"))) return true;
            java.io.File pnpm = new java.io.File(base, ".pnpm");
            if (!pnpm.isDirectory()) continue;
            java.io.File[] es = pnpm.listFiles(java.io.File::isDirectory);
            if (es == null) continue;
            for (java.io.File e : es) {
                java.io.File nm = new java.io.File(e, "node_modules");
                if (!nm.isDirectory()) continue;
                if (existsOrBrokenLink(new java.io.File(nm, name + ".disabled"))) return true;
            }
        }
        return false;
    }

    /** 文件条目是否存在（含悬空符号链接）：
     *  File.exists() 跟随链接，实体缺失时悬空链接返回 false —— 但禁用/启用
     *  状态由链接本身（条目）决定，悬空也要识别（否则「操作失败」）。 */
    private boolean existsOrBrokenLink(java.io.File f) {
        try {
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) return true;
        } catch (Throwable ignored) {
        }
        return f.exists();
    }

    /** 读取内置插件快照（rootfs /root/dsha-builtin.txt，安装时生成）；缺失时用内置兜底名单 */
    private java.util.Set<String> readBuiltinSnapshot() {
        java.util.Set<String> set = null;
        try {
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/dsha-builtin.txt");
            if (f.isFile()) {
                set = new java.util.HashSet<>();
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) {
                        String t = l.trim();
                        if (!t.isEmpty()) set.add(t);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        // 快照缺失/为空时兜底：profile 已知自带项（确保"隐藏自带"随时可用）
        if (set == null || set.isEmpty()) {
            set = new java.util.HashSet<>(java.util.Arrays.asList(
                    "@deepseek-ai", "@standard-schema", "persona-settings", "ui-scale"));
        }
        // DSHA 自己安置进去的内置插件也算「自带」。用户拨这个开关是想「只看自己装的」，
        // 而 device-shell-guide / dsh-web-mobile / status-overlay / task-notifier 都不是他装的。
        // 它们不在 dsh 的快照里（那份快照只记 dsh 首次启动时的自带项），必须显式并进来 ——
        // 否则开关打开后列表里还剩四个他没装过的东西，看着就像开关没生效。
        set.addAll(java.util.Arrays.asList(BUILTIN_PLUGIN_NAMES));
        return set;
    }

    /**
     * 这个插件名算不算「自带」。
     *
     * <p><b>名单里不带斜杠的条目按 scope 前缀处理</b>：dsh 的自带快照里记的是
     * {@code @deepseek-ai} 这样的 scope，而真实包名是 {@code @deepseek-ai/dsh-web-app}、
     * {@code @deepseek-ai/dsh-base}。原来这里是 {@code names.removeAll(builtin)} ——
     * 纯精确匹配，scope 条目永远对不上任何一个包，于是 dsh 原生的那批包一个都没被隐藏，
     * 用户打开「隐藏自带插件」还是看见它们。
     */
    private static boolean isBuiltinName(String name, java.util.Set<String> builtin) {
        if (name == null || name.isEmpty()) return false;
        // **dsh 官方作用域一律算自带。**@deepseek-ai/* 是内核与官方插件（dsh-base、
        // dsh-web-app 这些），随环境一起装进来的，用户不可能自己装。
        // 光靠快照名单指望不上它们 —— readBuiltinSnapshot 记的是首次安装那一刻的目录清单，
        // 后来被 dsh 自己升级或补装的官方包就漏在名单外，于是勾了「隐藏自带插件」
        // 还留着两个 @deepseek 开头的条目（正是反馈里那两个）。
        if (name.startsWith("@deepseek-ai/") || name.startsWith("@deepseek/")) return true;
        if (builtin == null) return false;
        if (builtin.contains(name)) return true;
        if (name.startsWith("@")) {
            int slash = name.indexOf('/');
            // @scope/包名 → 看名单里有没有整个 @scope
            if (slash > 0 && builtin.contains(name.substring(0, slash))) return true;
        }
        return false;
    }

    /** 校验可进入 shell 的插件 spec：npm 名 / github: / link: / file: 路径。 */
    /**
     * 校验可进入 shell 的插件<b>安装来源</b>。判据整体交给 {@link PluginSpec} ——
     * 它按 pnpm 支持的全部来源来认（npm / jsr / 命名 registry / git 简写与完整 URL /
     * 远程 tarball / 本地目录与压缩包），而不是原来那条只放过 npm 包名与 github: 的
     * 字符白名单。那条白名单把 #commit 锁定、#path: 子目录、gitlab:／bitbucket:、
     * 完整 git URL 全挡在门外，而它们在插件 README 里到处都是。
     *
     * <p>注意与 {@link PluginSpec#isPackageName} 分工：这里管「从哪装」，那里管
     * 「叫什么名字」——{@code owner/repo} 是合法来源但不是合法包名。
     */
    static boolean isValidPluginSpec(String spec) {
        return PluginSpec.isUsable(spec);
    }


    /** 启用/禁用插件：禁用=从 dependencies+bundles 移除声明并改名；启用=还原（避开引号嵌套：用 heredoc 临时脚本）。
     *  注意：禁用/启用状态由链接条目决定（含悬空链接）——实体缺失时 File.exists()
     *  返回 false 会导致「操作失败」，必须用 existsOrBrokenLink 判断。 */
    /** 上一次 togglePlugin 失败的原因（供 UI 展示；成功时清空） */
    /**
     * 插件安装的 single-flight。并发 {@code pnpm install} 同一个 profile 会撞 pnpm 的
     * lockfile 与 store，profile 可能被弄坏 —— 而这几轮新增了不少安装入口（市场卡片、
     * 直接 spec、装完自救、allowBuilds 重试、源码构建），并发面比以前大得多。
     *
     * <p>用 ReentrantLock 而不是 AtomicBoolean：{@code installSubdirFromSource} 构建完
     * 会回头调 {@code installPlugin}，用一次性标志会<b>自己把自己锁死</b>。
     * confirmBusy / askBusy 早就是 CAS 了，安装这条一直没跟上。
     */
    private final java.util.concurrent.locks.ReentrantLock installLock =
            new java.util.concurrent.locks.ReentrantLock();

    /**
     * profile 的 {@code cordis.patch.yml} 读改写必须串行。
     * togglePlugin 现在跑在后台线程，用户连点两个开关就是两个线程同时「读 → 改 → 写回」
     * 同一个文件 —— 后写的把前一个的结果整段盖掉。
     */
    private final Object patchLock = new Object();

    private volatile String lastToggleError = "";

    public String getLastToggleError() {
        return SensitiveData.redact(lastToggleError == null ? "" : lastToggleError);
    }

    /**
     * pnpm 因为 {@code prepare} 脚本被挡而失败时，按 <b>dsh 自己给出的修法</b>授权该包再重试。
     *
     * <p>dsh 的错误消息原话：<i>git-hosted plugins build on install via their prepare
     * script, which pnpm blocks until allowed — add the exact key pnpm printed above under
     * allowBuilds in …/pnpm-workspace.yaml, then re-run</i>。这条路比我们自己 clone + 构建
     * 轻得多（pnpm 直接跑插件自己的 prepare），所以排在那之前。
     */
    private String allowBuildsAndRetry(String pkg, String spec, String firstOut) {
        try {
            java.io.File ws = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/pnpm-workspace.yaml");
            if (ws.getParentFile() == null || !ws.getParentFile().isDirectory()) return "";
            String key = extractAllowBuildKey(firstOut);
            if (key.isEmpty()) key = pkg;                 // 抠不到就用包名（最常见的形态）
            if (key == null || key.trim().isEmpty()) return "";
            String before = readTextFile(ws);
            String after = PatchToggle.withAllowBuild(before, key.trim());
            if (after.equals(before)) return "";          // 已经授权过还失败 → 别空转
            if (!writeTextAtomic(ws, after)) {
                return "\n\n[想按 dsh 的提示授权构建脚本，但写 pnpm-workspace.yaml 失败]";
            }
            host.logActivity("授权构建脚本 allowBuilds: " + key + "，重试安装 " + pkg);
            String again = runPluginInstall(spec == null || spec.isEmpty() ? pkg : spec);
            return "\n\n[按 dsh 的提示把 " + key.trim() + " 加进 allowBuilds 后重试…]\n" + again;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 从 pnpm/dsh 的输出里抠出它要求授权的那个确切键。 */
    private static String extractAllowBuildKey(String out) {
        if (out == null || out.isEmpty()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:[Ii]gnored build scripts?|allowBuilds)[:\\s]+([A-Za-z0-9@._/+-]+)")
                .matcher(out);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 读文本文件（读不到给空串）。 */
    private String readTextFile(java.io.File f) {
        try {
            if (f == null || !f.isFile() || f.length() > 8L * 1024 * 1024) return "";
            return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 原子写：同目录临时文件 + {@code ATOMIC_MOVE}。
     *
     * <p>原来是「先 {@code delete()} 目标、再 {@code renameTo()}」—— 那两步之间失败，
     * 用户手写的整个 patch 文件就<b>没了</b>。rename(2) 本身允许覆盖已存在的文件，
     * 根本不需要先删，所以那个 delete 是净风险。
     */
    private boolean writeTextAtomic(java.io.File f, String text) {
        java.io.File tmp = new java.io.File(f.getParentFile(), f.getName() + ".dsha-tmp");
        try {
            try (java.io.OutputStream os = new java.io.FileOutputStream(tmp)) {
                os.write(text.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            java.nio.file.Files.move(tmp.toPath(), f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "写 " + f.getName() + " 失败: "
                    + SensitiveData.redact(String.valueOf(t)));
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    /**
     * 读插件<b>自己的</b> {@code cordis.patch.yml}，抠出它 insert 进 loader 的行 id。
     *
     * <p>patch 层的 disabled 要按 <b>id</b> 定位那一行，而 id 是插件作者在自己 patch 里
     * 定的，跟包名往往不一样 —— 拿包名去写是写不中的（loader 只会 warn 一句然后忽略）。
     */
    private java.util.List<String> readPluginPatchIds(String name) {
        for (String d : HarnessController.PLUGIN_DIRS) {
            java.io.File pkgDir = new java.io.File(proot.getRootfsDir(),
                    d.substring(1) + "/" + name);
            if (!pkgDir.isDirectory()) continue;
            String rel = "";
            try {
                org.json.JSONObject o = new org.json.JSONObject(
                        readTextFile(new java.io.File(pkgDir, "package.json")));
                org.json.JSONObject dsh = o.optJSONObject("dsh");
                org.json.JSONObject b = dsh == null ? null : dsh.optJSONObject("bundle");
                if (b != null) rel = b.optString("patch", "").trim();
            } catch (Throwable ignored) {
            }
            if (rel.isEmpty()) rel = "cordis.patch.yml";
            while (rel.startsWith("./")) rel = rel.substring(2);
            java.util.List<String> ids = PatchToggle.insertedIds(
                    readTextFile(new java.io.File(pkgDir, rel)));
            if (!ids.isEmpty()) return ids;
        }
        return new java.util.ArrayList<>();
    }

    /** profile 自己的 patch 文件。 */
    private java.io.File profilePatchFile() {
        return new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/cordis.patch.yml");
    }

    /**
     * 用<b>官方 patch 层</b>开关插件：往 profile 的 {@code cordis.patch.yml} 写
     * {@code disabled: true|false}。不搬文件、约 1 秒 HMR 热生效、不用重启。
     *
     * @return {@code null} 表示这条路走不通（抠不到 loader 行 id、或 profile 还没初始化），
     *         调用方应退回搬文件的老路 —— 半途而废比走老路更糟
     */
    private Boolean togglePluginViaPatch(String name, boolean enable) {
        try {
            java.io.File patch = profilePatchFile();
            if (patch.getParentFile() == null || !patch.getParentFile().isDirectory()) return null;
            java.util.List<String> ids = readPluginPatchIds(name);
            if (ids.isEmpty()) return null;      // 写了也不生效，不如让老路去搬文件
            // 读 → 改 → 写回必须串行：连点两个开关就是两个后台线程同时干这件事，
            // 后写的会把前一个的结果整段盖掉
            synchronized (patchLock) {
                String yaml = readTextFile(patch);
                java.util.Set<String> off =
                        new java.util.LinkedHashSet<>(PatchToggle.disabledIds(yaml));
                if (enable) off.removeAll(ids);
                else off.addAll(ids);
                if (!writeTextAtomic(patch, PatchToggle.withDisabled(yaml, off))) {
                    lastToggleError = "写 profile 的 cordis.patch.yml 失败";
                    return null;
                }
            }
            // 老状态兼容：以前用改目录名禁用过的，启用时得把目录名改回来，
            // 否则 patch 里写着「启用」而包根本不在 node_modules 里
            if (enable) restoreDisabledDir(name);
            host.logActivity((enable ? "启用" : "禁用") + "插件 " + name
                    + "（patch 层，刷新页面即生效）");
            return true;
        } catch (Throwable t) {
            lastToggleError = SensitiveData.redact("patch 层切换失败：" + t);
            return null;
        }
    }

    /** 老状态兼容：以前用改目录名禁用过的，启用时把目录名改回来。 */
    private void restoreDisabledDir(String name) {
        for (String d : HarnessController.PLUGIN_DIRS) {
            java.io.File base = new java.io.File(proot.getRootfsDir(), d.substring(1));
            java.io.File offDir = new java.io.File(base, name + ".disabled");
            java.io.File onDir = new java.io.File(base, name);
            if (existsOrBrokenLink(offDir) && !existsOrBrokenLink(onDir)
                    && !(offDir.isFile() && offDir.length() == 0)) {
                //noinspection ResultOfMethodCallIgnored
                offDir.renameTo(onDir);
            }
        }
    }

    /**
     * 批量启用/禁用。
     *
     * <p><b>一次写 patch</b>：逐个调 {@link #togglePlugin} 会「读 → 改 → 写」N 遍，
     * 选十个插件就是十次文件往返，慢，而且每一轮之间都给并发留了缝。这里把所有 id
     * 合起来算完再写一次。
     *
     * <p>抠不到 loader 行 id 的（写 patch 也不生效）退回逐个搬文件的老路。
     */
    public String togglePlugins(java.util.List<String> names, boolean enable) {
        if (names == null || names.isEmpty()) return "没有选中任何插件";
        java.util.LinkedHashMap<String, java.util.List<String>> idsOf = new java.util.LinkedHashMap<>();
        for (String n : names) {
            java.util.List<String> ids = readPluginPatchIds(n);
            if (!ids.isEmpty()) idsOf.put(n, ids);
        }
        int okCount = 0;
        java.util.List<String> failed = new java.util.ArrayList<>();
        if (!idsOf.isEmpty()) {
            // 批量启停一次改多个插件的 patch 行 —— 点错了逐个改回来很烦，留一份存档点。
            // 单个开关刻意不打：用户再点一下就回去了，打了反而把有用的存档点挤掉。
            PluginSavepoint.create(proot, host,
                    (enable ? "批量启用 " : "批量禁用 ") + idsOf.size() + " 个插件");
            synchronized (patchLock) {
                java.io.File patch = profilePatchFile();
                if (patch.getParentFile() != null && patch.getParentFile().isDirectory()) {
                    String yaml = readTextFile(patch);
                    java.util.Set<String> off =
                            new java.util.LinkedHashSet<>(PatchToggle.disabledIds(yaml));
                    for (java.util.List<String> ids : idsOf.values()) {
                        if (enable) off.removeAll(ids);
                        else off.addAll(ids);
                    }
                    if (writeTextAtomic(patch, PatchToggle.withDisabled(yaml, off))) {
                        okCount += idsOf.size();
                        if (enable) {
                            for (String n : idsOf.keySet()) restoreDisabledDir(n);
                        }
                    } else {
                        failed.addAll(idsOf.keySet());
                    }
                } else {
                    failed.addAll(idsOf.keySet());
                }
            }
        }
        for (String n : names) {
            if (idsOf.containsKey(n)) continue;
            if (togglePlugin(n, enable)) okCount++;
            else failed.add(n + "（" + getLastToggleError() + "）");
        }
        host.logActivity("批量" + (enable ? "启用" : "禁用") + " " + okCount + " 个插件");
        return "已" + (enable ? "启用" : "禁用") + " " + okCount + " 个"
                + (failed.isEmpty() ? "，刷新页面即可生效"
                : "；这些没成功：" + String.join("、", failed));
    }

    /** 批量卸载。以「是否还在 profile 清单里」判成败，比解析命令输出可靠。 */
    public String removePlugins(java.util.List<String> names) {
        if (names == null || names.isEmpty()) return "没有选中任何插件";
        int ok = 0;
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (String n : names) {
            String out;
            try {
                out = removePlugin(n);
            } catch (Throwable t) {
                out = String.valueOf(t);
            }
            if (!isInProfileManifest(n)) ok++;
            else bad.add(n + "（" + shortOf(out) + "）");
        }
        host.logActivity("批量卸载 " + ok + " 个插件");
        return "已卸载 " + ok + " 个" + (bad.isEmpty() ? "，刷新页面即可生效"
                : "；这些没成功：" + String.join("、", bad));
    }

    private static String shortOf(String s) {
        if (s == null || s.isEmpty()) return "无输出";
        String t = SensitiveData.redact(s.replace('\n', ' ').trim());
        return t.length() > 60 ? t.substring(0, 60) + "…" : t;
    }

    /**
     * 把选中的若干插件打成<b>一个</b>压缩包放进 {@code Download/DSHA/插件/}。
     *
     * <p>{@code -h} 解引用是必须的：已装插件在 node_modules 里多半只是软链，
     * 不解引用打出来是一把空链接（备份那边为同一个原因丢过对话）。
     */
    public String exportSelectedPlugins(java.util.List<String> names) {
        if (names == null || names.isEmpty()) return "NO_SELECTION";
        final String OUT_GUEST = "/root/.dsha-plugins-sel.tar.gz";
        java.io.File outHost = new java.io.File(proot.getRootfsDir(), "root/.dsha-plugins-sel.tar.gz");
        try {
            for (String d : HarnessController.PLUGIN_DIRS) {
                java.io.File dir = new java.io.File(proot.getRootfsDir(), d.substring(1));
                if (!dir.isDirectory()) continue;
                StringBuilder args = new StringBuilder();
                int found = 0;
                for (String n : names) {
                    if (n == null || !PluginSpec.isPackageName(n)) continue;
                    if (!existsOrBrokenLink(new java.io.File(dir, n))) continue;
                    args.append(' ').append(ShellQuote.arg(n));
                    found++;
                }
                if (found == 0) continue;
                String r = proot.execAndRead("rm -f " + ShellQuote.arg(OUT_GUEST)
                        + "; cd " + ShellQuote.arg(d)
                        + " && tar -czhf " + ShellQuote.arg(OUT_GUEST) + args
                        + " 2>&1; echo TAR_EXIT=$?");
                if (r == null || !r.contains("TAR_EXIT=0") || !outHost.isFile()) continue;
                String file = "DSHA-plugins-" + found + "个-"
                        + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(new java.util.Date()) + ".tar.gz";
                String path = copyToDownloads(outHost, file, PublicDirs.PLUGINS);
                if (path != null) {
                    host.logActivity("批量导出 " + found + " 个插件 → " + path);
                    return path;
                }
            }
            return "NOT_FOUND";
        } catch (Exception e) {
            android.util.Log.w("DSHA", "批量导出失败: "
                    + SensitiveData.redact(String.valueOf(e)));
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            outHost.delete();
        }
    }

    public boolean togglePlugin(String name, boolean enable) {
        lastToggleError = "";
        // 首选官方 patch 层：不搬文件、HMR 约 1 秒生效、不用重启。走不通才退回搬文件。
        Boolean viaPatch = togglePluginViaPatch(name, enable);
        if (viaPatch != null) return viaPatch;
        lastToggleError = "";
        try {
            final String PKG = "/root/.dsh/profiles/web/package.json";
            for (String d : HarnessController.PLUGIN_DIRS) {
                java.io.File dir = new java.io.File(proot.getRootfsDir(), d.substring(1));
                if (!dir.isDirectory()) continue;
                java.io.File on = new java.io.File(dir, name);
                java.io.File off = new java.io.File(dir, name + ".disabled");
                if (enable && existsOrBrokenLink(off)) {
                    // 上次禁用时若 mv 失败（实体已丢失或链接悬空），代码会 touch 一个空文件
                    // 占位来记下「用户已禁用」。此刻若把这个空文件改名回去，就造出一个
                    // 「看起来在、其实加载不了」的幽灵插件 —— 更糟的是 ensureBuiltinBundles
                    // 会被它骗过，认为实体已存在而不再补回。issue #9 报的「新装插件关闭后
                    // 无法再次开启」「卸载显示成功但 UI 永久存在」就是这么来的。
                    if (off.isFile() && off.length() == 0) {
                        boolean builtin = isBuiltinPlugin(name);
                        if (!off.delete()) {
                            lastToggleError = "删不掉残留的禁用标记，请重启 App 再试";
                            return false;
                        }
                        if (builtin) {
                            // 内置插件的实体在 APK 里，直接补回
                            try {
                                host.ensureBuiltinBundles();
                                lastToggleError = name + " 的实体之前丢了，已从内置资源补回；"
                                        + "请再点一次开关启用";
                            } catch (Throwable t) {
                                lastToggleError = SensitiveData.redact(
                                        name + " 的实体已丢失，补回失败：" + t);
                            }
                        } else {
                            lastToggleError = name + " 的实体已丢失（只剩一个禁用标记），"
                                    + "无法直接启用 —— 请到市场重新安装";
                        }
                        return false;
                    }
                    String src = readPluginSrc(name);
                    // 源记录缺失：内置插件（dsh-web-mobile / dsh-device-shell-guide，
                    // 注意名字不带 dsha- 前缀！旧判断 name.startsWith("dsha-") 永远不命中）
                    // 兜底回 file: 路径；普通插件兜底 "*"（包体在磁盘即可加载）
                    if (src == null || src.isEmpty() || "null".equals(src)) {
                        // 内置插件（link: 指向实体目录，与 registerMobileNavBundle 语义一致）
                        src = isBuiltinPlugin(name)
                                ? "link:" + builtinRealPath(name)
                                : "*";
                    }
                    if (!"*".equals(src) && !isValidPluginSpec(src)) {
                        return false; // 脏/恶意源记录：拒绝写入 dependencies
                    }
                    String r = proot.execAndRead(
                            toggleScript() +
                            "node /root/dsha-toggle.js " + ShellQuote.arg(PKG) + " " + ShellQuote.arg(name)
                                    + " on " + ShellQuote.arg(src) + " && " +
                            // 这里过去有个 `|| touch <name>` 兜底：mv 失败就凭空造一个空文件，
                            // 于是插件「启用成功」但根本加载不了。宁可如实失败。
                            "rm -f /root/dsha-toggle.js && mv " + ShellQuote.arg(d + "/" + name + ".disabled")
                                    + " " + ShellQuote.arg(d + "/" + name) + " && echo OK");
                    boolean okOn = r != null && r.contains("OK");
                    if (!okOn) {
                        lastToggleError = SensitiveData.redact(
                                "启用失败：实体改名没成功（输出："
                                + (r == null ? "无" : r.trim()) + "）");
                        android.util.Log.w("DSHA", "启用插件失败 " + name + ": "
                                + SensitiveData.redact(r));
                    }
                    return okOn;
                } else if (!enable) {
                    // 禁用：不依赖 on 存在（链接缺失/悬空也执行）——
                    // 移除声明 + 改名；改名失败（链接缺失）则 touch .disabled 占位，
                    // 让 ensureDeviceShellGuide/ensureBuiltinBundles 识别「用户已禁用」跳过补回。
                    String r = proot.execAndRead(
                            toggleScript() +
                            "node /root/dsha-toggle.js " + ShellQuote.arg(PKG) + " " + ShellQuote.arg(name) + " off && " +
                            "rm -f /root/dsha-toggle.js && " +
                            "( mv " + ShellQuote.arg(d + "/" + name) + " " + ShellQuote.arg(d + "/" + name + ".disabled")
                                    + " 2>/dev/null || touch " + ShellQuote.arg(d + "/" + name + ".disabled") + " ) && echo OK");
                    boolean ok = r != null && r.contains("OK");
                    if (!ok) {
                        android.util.Log.w("DSHA", "禁用插件失败 " + name + " 输出: "
                                + SensitiveData.redact(r == null ? "null" : r));
                    }
                    return ok;
                }
            }
        } catch (Exception e) {
            lastToggleError = SensitiveData.redact("开关插件时出错：" + e);
            android.util.Log.w("DSHA", "togglePlugin 异常 " + name + ": "
                    + SensitiveData.redact(String.valueOf(e)));
            return false;
        }
        if (lastToggleError.isEmpty()) {
            lastToggleError = enable
                    ? "找不到 " + name + " 的禁用标记，可能已经是启用状态"
                    : "在 profile 里找不到 " + name;
        }
        return false;
    }

    /** 生成修改 package.json 的临时脚本（heredoc，避免嵌套引号） */
    private String toggleScript() {
        return "cat > /root/dsha-toggle.js <<'EOF'\n" +
                "const fs=require('fs');\n" +
                "const pkg=process.argv[2]||'';const pn=process.argv[3]||'';const mode=process.argv[4]||'off';const src=process.argv[5]||'';\n" +
                "if(!pkg||!pn)process.exit(1);\n" +
                "const p=JSON.parse(fs.readFileSync(pkg,'utf-8'));\n" +
                "if(!p.dependencies)p.dependencies={};\n" +
                "if(mode==='on'){\n" +
                "  if(src&&src!=='null'&&src!=='*')p.dependencies[pn]=src;\n" +
                "  if(p.dsh&&p.dsh.profile&&Array.isArray(p.dsh.profile.bundles)&&p.dsh.profile.bundles.indexOf(pn)<0)p.dsh.profile.bundles.push(pn);\n" +
                "}else{\n" +
                "  if(p.dependencies[pn])fs.writeFileSync('/root/.dsh/profiles/web/.dsha-src-'+pn,String(p.dependencies[pn]));\n" +
                "  delete p.dependencies[pn];\n" +
                "  if(p.dsh&&p.dsh.profile&&Array.isArray(p.dsh.profile.bundles))p.dsh.profile.bundles=p.dsh.profile.bundles.filter(function(x){return x!==pn;});\n" +
                "}\n" +
                "fs.writeFileSync(pkg,JSON.stringify(p,null,2));\n" +
                "EOF\n";
    }

    /** 判断是否为 App 内置插件（名称不带 dsha- 前缀！）。用于启用时依赖源兜底。 */
    private boolean isBuiltinPlugin(String name) {
        return "dsh-web-mobile".equals(name)
                || "dsh-device-shell-guide".equals(name);
    }

    /** 内置插件实体目录真实路径（name ≠ 目录名：
     *  dsh-web-mobile → /root/dsha-web-mobile；
     *  旧实现 "dsha-"+name 会拼成不存在的路径）。 */
    private String builtinRealPath(String name) {
        if ("dsh-web-mobile".equals(name)) return "/root/dsha-web-mobile";
        if ("dsh-device-shell-guide".equals(name)) return "/root/dsha-device-shell-guide";
        return "/root/dsha-" + name;
    }

    /** 读取曾禁用的插件原安装源（启用时还原到 package.json） */
    private String readPluginSrc(String name) {
        try {
            java.io.File f = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/.dsha-src-" + name);
            if (!f.isFile()) return "";
            try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
                // 源记录只有一行；旧实现 `readLine()==null ? "" : readLine()` 会读两行
                // 导致永远返回第二行（通常 null）→ 启用时依赖源恢复失败，只能走 "*" 兜底
                String line = br.readLine();
                return line == null ? "" : line.trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 导出已启用插件为 tar.gz（Android Download/DSHA 目录，MediaStore）
     *  返回：文件路径=成功 / "NO_PLUGINS"=没有可导出插件 / null=失败 */
    public String exportPlugins() {
        try {
            // rootfs 内中转文件（先打包到 rootfs，再从宿主路径读出来拷贝到 Download）
            java.io.File outHost = new java.io.File(proot.getRootfsDir(), "root/plugins-export.tar.gz");
            final String OUT_GUEST = "/root/plugins-export.tar.gz";
            for (String d : HarnessController.PLUGIN_DIRS) {
                java.io.File dir = new java.io.File(proot.getRootfsDir(), d.substring(1));
                if (!dir.isDirectory()) continue;
                // 有可导出条目才打包（空目录/无启用插件直接跳过）
                String has = proot.execAndRead("cd '" + d + "' && ls 2>/dev/null | grep -v disabled | grep -v '^$' | head -1");
                if (has == null || has.trim().isEmpty()) continue;
                String r = proot.execAndRead(
                        "cd '" + d + "' && " +
                        "tar -czhf '" + OUT_GUEST + "' $(ls | grep -v disabled) 2>&1; echo TAR_EXIT=$?");
                if (r == null || !r.contains("TAR_EXIT=0") || !outHost.isFile()) continue;
                String name = "DSHA-plugins-all-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date()) + ".tar.gz";
                String path = copyToDownloads(outHost, name, PublicDirs.PLUGINS);
                if (path != null) return path;
            }
            return "NO_PLUGINS";
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 导入插件包：先安全解压到 staging（拒绝路径穿越/符号链接/硬链接），再原子移入插件目录。
     *  只解压不注册会导致插件「列表可见但不生效」，因此解压成功后统一注册。 */
    /**
     * 导入插件归档：<b>自动识别格式与布局</b>。
     *
     * <p>格式按文件头判（tar.gz / tar / zip），不看扩展名 —— 用户挑的文件叫什么都有可能。
     * 布局交给 {@link ArchiveProbe#pluginRoots}：单插件（根有 package.json）、多插件
     * （每个子目录一个）、多包一层（GitHub zip 的 {@code repo-main/插件/}）三种都认。
     *
     * <p>安置目录名用 <b>package.json 里的 {@code name}</b>，不是压缩包里的目录名 ——
     * dsh 按包名解析 {@code node_modules}，目录名对不上等于没装。
     *
     * @return {@code {status, message}}，status 为 {@code "OK"} 或 {@code "ERR"}；
     *         message 是可以直接给用户看的人话
     */
    public String[] importArchive(java.io.File archive) {
        if (archive == null || !archive.isFile() || archive.length() == 0) {
            return new String[]{"ERR", "文件读不到或者是空的"};
        }
        int kind;
        try (java.io.InputStream in = new java.io.FileInputStream(archive)) {
            byte[] head = new byte[512];
            int n = in.read(head);
            kind = ArchiveProbe.kindOf(n <= 0 ? new byte[0] : java.util.Arrays.copyOf(head, n));
        } catch (Exception e) {
            return new String[]{"ERR", "读文件失败："
                    + SensitiveData.redact(e.getMessage())};
        }
        if (!ArchiveProbe.canExtract(kind)) {
            return new String[]{"ERR", "不支持的格式（按文件头识别为 "
                    + ArchiveProbe.kindName(kind) + "）。请用 tar.gz、tar 或 zip"};
        }
        java.io.File staging = new java.io.File(appContext.getCacheDir(),
                "plugin-import-" + System.currentTimeMillis());
        try {
            //noinspection ResultOfMethodCallIgnored
            staging.mkdirs();
            if (kind == ArchiveProbe.ZIP) {
                String err = unzipSafe(archive, staging);
                if (err != null) return new String[]{"ERR", err};
            } else {
                // 解压器自己 sniff gzip magic，tar 与 tar.gz 走同一条路；
                // extractSafe 拒绝绝对路径 / .. / 链接类条目（防逃逸）
                TarGzipExtractor.extractSafe(archive, staging);
            }
            java.util.List<String> rels = new java.util.ArrayList<>();
            collectRelative(staging, "", rels, 0);
            // 逐层往下找第一层「真插件」。只取最浅一层是不够的：GitHub 下载的 monorepo zip
            // 里 repo-main/package.json 是仓库的管理包（private + workspaces，不是插件），
            // 真插件在 repo-main/plugins/*。装管理包还会「成功」——它本身是个合法 npm 包，
            // 于是用户得到一个什么都没发生的「安装成功」。
            java.util.List<String[]> layers =
                    ArchiveProbe.pluginRootsByDepth(rels.toArray(new String[0]));
            String[] roots = new String[0];
            for (String[] layer : layers) {
                java.util.List<String> real = new java.util.ArrayList<>();
                for (String root : layer) {
                    java.io.File dir = root.isEmpty() ? staging : new java.io.File(staging, root);
                    if (looksLikePluginPkg(new java.io.File(dir, "package.json"))) real.add(root);
                }
                if (!real.isEmpty()) {
                    roots = real.toArray(new String[0]);
                    break;
                }
            }
            if (roots.length == 0) {
                return new String[]{"ERR", "包里没找到插件。有 package.json 的目录都像是"
                        + "仓库管理包（private / workspaces），不是插件本体 —— "
                        + "如果这是 monorepo，请把里面**某个插件目录**单独打包再导入。"};
            }
            java.io.File dir = new java.io.File(proot.getRootfsDir(),
                    HarnessController.PLUGIN_DIRS[0].substring(1));
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return new String[]{"ERR", "插件目录不可写，环境可能还没装好"};
            }
            java.util.List<String> done = new java.util.ArrayList<>();
            java.util.List<String> skipped = new java.util.ArrayList<>();
            for (String root : roots) {
                java.io.File src = root.isEmpty() ? staging : new java.io.File(staging, root);
                String name = readPkgName(new java.io.File(src, "package.json"));
                String label = root.isEmpty() ? archive.getName() : root;
                if (name == null || name.isEmpty()) {
                    skipped.add(label + "（package.json 里没有 name）");
                    continue;
                }
                if (!PluginSpec.isPackageName(name)) {
                    skipped.add(label + "（包名 " + name + " 不合法）");
                    continue;
                }
                java.io.File target = new java.io.File(dir, name);
                if (target.getParentFile() != null) {
                    //noinspection ResultOfMethodCallIgnored
                    target.getParentFile().mkdirs();   // @scope/name 是两级目录
                }
                host.deleteRecursively(target);        // 只删目标里的同名旧条目
                boolean ok = src.renameTo(target);
                if (!ok && src.isDirectory()) ok = copyRecursivelySafe(src, target);
                if (ok) {
                    registerImportedPlugin(name);
                    done.add(name);
                } else {
                    skipped.add(name + "（写入插件目录失败）");
                }
            }
            if (done.isEmpty()) {
                return new String[]{"ERR", "一个都没装上：" + String.join("；", skipped)};
            }
            host.logActivity("导入插件 " + done.size() + " 个：" + String.join(", ", done));
            StringBuilder msg = new StringBuilder();
            msg.append(ArchiveProbe.isSinglePlugin(roots) ? "已导入插件 " : "已导入 ")
               .append(done.size() > 1 ? done.size() + " 个插件：" : "")
               .append(String.join(", ", done));
            if (!skipped.isEmpty()) msg.append("\n跳过：").append(String.join("；", skipped));
            msg.append("\n刷新页面即可生效（多数插件热加载）");
            return new String[]{"OK", msg.toString()};
        } catch (Exception e) {
            return new String[]{"ERR", "导入失败：" + HarnessController.describe(e)};
        } finally {
            host.deleteRecursively(staging);
        }
    }

    /** 解 zip 到目标目录。返回 null 表示成功，否则是人话错误原因。 */
    private String unzipSafe(java.io.File zip, java.io.File dst) {
        int files = 0;
        long total = 0;
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(zip)))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                String nm = e.getName();
                // zip slip：条目名带 .. 或绝对路径就能写到目标目录外面去。
                // 插件包是用户从网上下的，不能假设它善良。
                if (!ArchiveProbe.safeEntryName(nm)) {
                    android.util.Log.w("DSHA", "zip 里跳过可疑条目: "
                            + SensitiveData.redact(nm));
                    continue;
                }
                java.io.File out = new java.io.File(dst, nm);
                // 再校验一次真实路径落在 dst 内（symlink/规范化后的兜底）
                if (!out.getCanonicalPath().startsWith(dst.getCanonicalPath() + java.io.File.separator)) {
                    continue;
                }
                if (e.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    out.mkdirs();
                    continue;
                }
                if (out.getParentFile() != null) {
                    //noinspection ResultOfMethodCallIgnored
                    out.getParentFile().mkdirs();
                }
                try (java.io.OutputStream os = new java.io.FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = zis.read(buf)) != -1) {
                        os.write(buf, 0, n);
                        total += n;
                        // 解压炸弹兜底：插件包没有正常理由超过 512MB
                        if (total > 512L * 1024 * 1024) {
                            return "解包超过 512MB，已中止（这不像插件包）";
                        }
                    }
                }
                files++;
                if (files > 200000) return "包内文件数异常（超过 20 万），已中止";
            }
        } catch (Exception ex) {
            return "解压 zip 失败：" + SensitiveData.redact(ex.getMessage());
        }
        return files == 0 ? "zip 里没有任何文件" : null;
    }

    /** 递归收集相对路径（只收文件，深度设上限防病态目录树）。 */
    private void collectRelative(java.io.File dir, String prefix,
                                 java.util.List<String> out, int depth) {
        if (depth > 12 || out.size() > 50000) return;
        java.io.File[] cs = dir.listFiles();
        if (cs == null) return;
        for (java.io.File c : cs) {
            String rel = prefix.isEmpty() ? c.getName() : prefix + "/" + c.getName();
            if (c.isDirectory()) {
                collectRelative(c, rel, out, depth + 1);
            } else {
                out.add(rel);
            }
        }
    }

    /**
     * 这个 package.json 像不像<b>插件本体</b>，而不是 monorepo 的管理包。
     *
     * <p>判据从宽到严：有 {@code dsh} 字段 = 确定是插件；{@code private} 或带
     * {@code workspaces} = 确定是管理包（GitHub monorepo 的根就是这样）；
     * 其余给一次机会 —— 有些插件的 bundle 声明只写在 patch 文件里。
     */
    private boolean looksLikePluginPkg(java.io.File pkgJson) {
        try {
            if (pkgJson == null || !pkgJson.isFile()) return false;
            org.json.JSONObject o = new org.json.JSONObject(readTextFile(pkgJson));
            if (o.optString("name", "").trim().isEmpty()) return false;
            if (o.has("dsh")) return true;
            if (o.optBoolean("private", false)) return false;
            if (o.has("workspaces")) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读 package.json 的 name 字段；读不出返回 null。 */
    private String readPkgName(java.io.File pkgJson) {
        try {
            if (!pkgJson.isFile() || pkgJson.length() > 4L * 1024 * 1024) return null;
            String txt = new String(java.nio.file.Files.readAllBytes(pkgJson.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String n = new org.json.JSONObject(txt).optString("name", "").trim();
            return n.isEmpty() ? null : n;
        } catch (Exception e) {
            return null;
        }
    }

    public boolean importPlugins(java.io.File tarGz) {
        // 导入等价于安装（往 node_modules 解包 + 注册），同样留一份存档点 ——
        // 导入这条路的坑还更多：归档布局认错就会把 monorepo 的管理包当插件搬进去
        // （端到端测试抓到过）。
        String savepoint = PluginSavepoint.create(proot, host,
                "导入插件归档 " + (tarGz == null ? "?" : tarGz.getName()));
        try {
            java.io.File staging = new java.io.File(proot.getRootfsDir(),
                    "root/plugins-import-stage-" + System.currentTimeMillis());
            staging.mkdirs();
            boolean moved = false;
            java.util.Set<String> importedNames = new java.util.LinkedHashSet<>();
            try {
                // 安全解压：拒绝绝对路径/..（宽松仅限备份恢复）；链接类条目一律丢弃，防止逃逸
                TarGzipExtractor.extractSafe(tarGz, staging);
                for (String d : HarnessController.PLUGIN_DIRS) {
                    java.io.File dir = new java.io.File(proot.getRootfsDir(), d.substring(1));
                    if (!dir.isDirectory()) dir.mkdirs();
                    java.io.File[] children = staging.listFiles();
                    if (children == null) continue;
                    for (java.io.File c : children) {
                        String n = c.getName();
                        if (n.startsWith(".") || n.endsWith(".disabled")) continue;
                        if (!n.matches("[A-Za-z0-9@._+\\-]+")) continue; // 非法包名直接忽略
                        java.io.File target = new java.io.File(dir, n);
                        host.deleteRecursively(target); // 只删目标目录内的同名旧条目
                        boolean ok = c.renameTo(target);
                        if (!ok && c.isDirectory()) ok = copyRecursivelySafe(c, target);
                        if (ok) {
                            moved = true;
                            if (target.isDirectory() && new java.io.File(target, "package.json").isFile()) {
                                importedNames.add(n);
                            }
                        }
                    }
                }
            } finally {
                host.deleteRecursively(staging);
            }
            if (moved && !importedNames.isEmpty()) {
                for (String name : importedNames) {
                    registerImportedPlugin(name);
                }
                android.util.Log.i("DSHA", "插件导入完成并注册: "
                        + SensitiveData.redact(importedNames.toString()));
            }
            if (!moved && savepoint != null) PluginSavepoint.restore(proot, host);
            return moved;
        } catch (Exception ignored) {
            if (savepoint != null) PluginSavepoint.restore(proot, host);
            return false;
        }
    }

    /** 只允许写入目标目录内的递归拷贝（导入 staging→final 用；拒绝跟随符号链接）。 */
    private boolean copyRecursivelySafe(java.io.File src, java.io.File dst) {
        try {
            java.nio.file.Path root = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/node_modules").toPath().toAbsolutePath().normalize();
            java.nio.file.Path target = dst.toPath().toAbsolutePath().normalize();
            if (!target.startsWith(root)) return false;
            if (java.nio.file.Files.isSymbolicLink(src.toPath())) return false;
            if (src.isDirectory()) {
                if (!dst.isDirectory() && !dst.mkdirs()) return false;
                java.io.File[] cs = src.listFiles();
                if (cs != null) for (java.io.File c : cs) {
                    if (!copyRecursivelySafe(c, new java.io.File(dst, c.getName()))) return false;
                }
                return true;
            }
            if (src.isFile()) {
                host.copyFile(src, dst);
                return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 把导入的插件注册进 web profile（dependencies + bundles + node_modules 链接/实体）。
     *  幂等：已在 bundles 则跳过。与 registerMobileNavBundle 思路一致（不跑 pnpm，
     *  避免破坏 profile node_modules）。 */
    private void registerImportedPlugin(String name) {
        registerImportedPlugin(name, null);
    }

    /**
     * 把插件写进 web profile 的 {@code dependencies} + {@code dsh.profile.bundles}。
     *
     * <p>两者必须同时写：dsh 的 reconcile 会把「bundles 里列了、dependencies 解析不到」
     * 的条目剪掉，于是补一次剪一次，用户看到的是插件装了却永远不生效。
     *
     * @param spec dependencies 里写的来源。{@code null} 表示实体已经在 node_modules 里
     *             （导入插件那条路）—— 这时会先把实体挪出 node_modules 再 {@code link:} 回去，
     *             <b>绝不能</b>写 {@code file:./node_modules/<name>}（那是指向自己的路径，
     *             会让后续每次 pnpm install 都 ELOOP）；
     *             从源码构建的插件传 {@code link:<源码绝对路径>}，那才是它真实的来源。
     */
    /**
     * 自愈自指依赖 —— 让已经中招的设备恢复安装能力。
     *
     * <p>历史上注册插件写的是 {@code file:./node_modules/<name>}，那个路径就是安装目标自己，
     * pnpm 会建出指向自身的符号链接，之后每次 {@code pnpm install} 都 ELOOP、
     * <b>所有插件都装不上</b>（用户看到的却是「装 A 失败」，坏的是 B）。根因已在
     * {@link #registerImportedPlugin} 掐掉，这里负责把已经写进 profile 的那些修回来。
     *
     * <p>分两种处置：
     * <ul>
     *   <li><b>实体还读得出来</b>：挪到 {@code /root/plugin-src/} 再 {@code link:} 回去，
     *       插件继续可用；</li>
     *   <li><b>实体已经 ELOOP</b>：摘出 dependencies 与 bundles，实体<b>移进隔离目录</b>
     *       而不是 {@code rm -rf}。主人授权了自动处理，但「自动」不该等于「不可逆」——
     *       坏条目本身只是个软链，留一份几乎不占地方，万一判错了还能捞回来。</li>
     * </ul>
     *
     * <p><b>probe 做两次</b>：判据是「能不能读出 package.json」，而这个环境的 proot
     * 会偶发抖动（本项目里 shell 调用返回空、输出被截断都实测过）。一次失败就删用户的插件
     * 太草率，连续两次读不出来才认。
     *
     * @return 给用户看的处理结果；没有要修的返回空串
     */
    public String healSelfRefDeps() {
        return healSelfRefDeps(new LazySavepoint("自愈自指依赖"));
    }

    String healSelfRefDeps(LazySavepoint sp) {
        try {
            java.io.File pf = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return "";
            org.json.JSONObject root = new org.json.JSONObject(readTextFile(pf));
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps == null) return "";
            java.util.List<String> selfRef = new java.util.ArrayList<>();
            for (java.util.Iterator<String> k = deps.keys(); k.hasNext(); ) {
                String name = k.next();
                String v = deps.optString(name, "");
                if (v.startsWith("file:./node_modules/") || v.startsWith("file:node_modules/")) {
                    selfRef.add(name);
                }
            }
            if (selfRef.isEmpty()) return "";
            // 要改 package.json、还要 mv 掉 node_modules 条目 —— 自愈本身也可能判错，
            // 所以先留一份能退回的状态
            sp.ensure();
            java.util.List<String> fixed = new java.util.ArrayList<>();
            java.util.List<String> quarantined = new java.util.ArrayList<>();
            for (String name : selfRef) {
                if (probeReadable(name) || probeReadable(name)) {   // 两次机会，抗抖动
                    String link = moveOutOfNodeModules(name);
                    if (link != null) {
                        deps.put(name, link);
                        fixed.add(name);
                        continue;
                    }
                }
                if (quarantineEntry(name)) {
                    deps.remove(name);
                    removeFromBundles(root, name);
                    quarantined.add(name);
                }
            }
            if (fixed.isEmpty() && quarantined.isEmpty()) return "";
            if (!writeTextAtomic(pf, root.toString(2))) {
                return "发现自指依赖，但写回 profile 失败 —— 安装可能仍会 ELOOP";
            }
            StringBuilder msg = new StringBuilder();
            if (!fixed.isEmpty()) {
                msg.append("修好 ").append(fixed.size()).append(" 个自指依赖：")
                   .append(String.join("、", fixed))
                   .append("（它们会让每次装插件都 ELOOP）");
            }
            if (!quarantined.isEmpty()) {
                if (msg.length() > 0) msg.append('\n');
                msg.append("这些插件的实体已经坏掉，已移入隔离目录并摘出 profile，需要重装：")
                   .append(String.join("、", quarantined))
                   .append("\n（隔离目录 /root/.dsha-quarantine/，确认不需要可自行删）");
            }
            host.logActivity("自愈自指依赖：修 " + fixed.size()
                    + " 个、隔离 " + quarantined.size() + " 个");
            return msg.toString();
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "自愈自指依赖失败: "
                    + SensitiveData.redact(String.valueOf(t)));
            return "";
        }
    }

    /** 能不能读出这个插件的 package.json（ELOOP / 悬空链接都会失败）。 */
    private boolean probeReadable(String name) {
        try {
            String r = proot.execAndRead("cd /root/.dsh/profiles/web && "
                    + "cat node_modules/" + ShellQuote.arg(name) + "/package.json >/dev/null 2>&1 "
                    + "&& echo READABLE || echo BROKEN", 30_000);
            return r != null && r.contains("READABLE");
        } catch (Throwable t) {
            return false;
        }
    }

    /** 把坏条目移进隔离目录（不 rm -rf：自动处理不等于不可逆）。 */
    private boolean quarantineEntry(String name) {
        try {
            final String nm = ShellQuote.arg(name);
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                    .format(new java.util.Date());
            String dest = "/root/.dsha-quarantine/" + stamp;
            // 这里原来是 `mv …; rm -rf node_modules/<name>; echo QUARANTINED` —— 三条用分号
            // 串起来，于是 **mv 失败也照样 rm**，而且 QUARANTINED 无论如何都会打印：
            // 上层据此把插件从 dependencies 与 bundles 里摘掉，用户看到「已移入隔离目录」，
            // 实际上实体被删了、隔离目录里什么都没有。这个环境里 mv 失败并不罕见
            // （含 .l2s 替身的目录、跨设备的坏链接都会失败）。
            // 现在：mv 成功才算隔离，失败就什么都不动，下次启动再试。
            String r = proot.execAndRead("cd /root/.dsh/profiles/web && "
                    + "mkdir -p \"$(dirname " + ShellQuote.arg(dest + "/" + name) + ")\" && "
                    // 坏链接不能 cp（一 cp 就又 ELOOP），只能整条 mv 走
                    + "if mv node_modules/" + nm + " " + ShellQuote.arg(dest + "/" + name)
                    + " 2>&1; then echo QUARANTINED; else echo QUARANTINE_FAILED; fi",
                    60_000);
            if (r != null && r.contains("QUARANTINE_FAILED")) {
                android.util.Log.w("DSHA", "隔离 " + name + " 失败（实体保持原样）: "
                        + SensitiveData.redact(r));
                return false;
            }
            return r != null && r.contains("QUARANTINED");
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "隔离 " + name + " 失败: "
                    + SensitiveData.redact(String.valueOf(t)));
            return false;
        }
    }

    /** 从 dsh.profile.bundles 里摘掉一个条目。 */
    private void removeFromBundles(org.json.JSONObject root, String name) {
        try {
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            org.json.JSONArray b = prof == null ? null : prof.optJSONArray("bundles");
            if (b == null) return;
            org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < b.length(); i++) {
                if (!name.equals(b.optString(i, ""))) out.put(b.opt(i));
            }
            prof.put("bundles", out);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 把插件实体从 profile 的 {@code node_modules} 挪到 {@code /root/plugin-src}，
     * 返回可以写进 dependencies 的 {@code link:} spec；挪不动返回 {@code null}。
     *
     * <p>{@code cp -rL} 的 {@code -L} 是必须的：实体本身多半就是一根软链
     * （pnpm 的 store 链接、或我们自己安置内置插件时建的），不解引用挪过去还是一根链接，
     * 换个位置继续悬空。
     */
    private String moveOutOfNodeModules(String name) {
        try {
            final String nm = ShellQuote.arg(name);
            String r = proot.execAndRead(
                    "cd /root/.dsh/profiles/web || exit 1; "
                    + "test -e node_modules/" + nm + " || exit 2; "
                    + "mkdir -p \"$(dirname /root/plugin-src/" + nm + ")\" && "
                    + "rm -rf /root/plugin-src/" + nm + " && "
                    + "cp -rL node_modules/" + nm + " /root/plugin-src/" + nm + " 2>&1 && "
                    + "rm -rf node_modules/" + nm + " && echo MOVED_OK", 120_000);
            if (r != null && r.contains("MOVED_OK")) {
                return "link:/root/plugin-src/" + name;
            }
            android.util.Log.w("DSHA", "把 " + name + " 挪出 node_modules 失败: "
                    + SensitiveData.redact(r));
            return null;
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "把 " + name + " 挪出 node_modules 异常: "
                    + SensitiveData.redact(String.valueOf(t)));
            return null;
        }
    }

    private void registerImportedPlugin(String name, String spec) {
        try {
            java.io.File pf = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/package.json");
            if (!pf.isFile()) return;
            String txt = new String(java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8);
            org.json.JSONObject root = new org.json.JSONObject(txt);
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps == null) { deps = new org.json.JSONObject(); root.put("dependencies", deps); }
            if (!deps.has(name)) {
                // 实体已在 node_modules，用本地引用（零网络）；scoped 包名原样保留。
                // 从源码构建来的插件传 link:<源码目录>，指回它真正的来源。
                deps.put(name, spec != null && !spec.isEmpty()
                        ? spec : "file:./node_modules/" + name);
            }
            // dsh.profile.bundles 追加
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            org.json.JSONObject profile = dsh == null ? null : dsh.optJSONObject("profile");
            if (profile == null) {
                profile = new org.json.JSONObject();
                if (dsh == null) dsh = new org.json.JSONObject();
                dsh.put("profile", profile);
                root.put("dsh", dsh);
            }
            org.json.JSONArray bundles = profile.optJSONArray("bundles");
            if (bundles == null) { bundles = new org.json.JSONArray(); profile.put("bundles", bundles); }
            boolean has = false;
            for (int i = 0; i < bundles.length(); i++) {
                if (name.equals(bundles.optString(i, "").trim())) { has = true; break; }
            }
            if (!has) bundles.put(name);
            java.nio.file.Files.write(pf.toPath(), root.toString(2).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
        }
    }

    /** 拉取插件市场快照 JSON（GitHub API 列最新快照 → jsdelivr/raw 下载），返回 JSON 文本 */
    /** 拉取插件市场索引：PLUGINS-ALL.md（jsdelivr 优先，含多镜像）；失败时回退本地缓存 */
    /**
     * 拉市场列表（<b>首选 plugins.json</b>，失败退回 Markdown 表格）。
     *
     * <p>换数据源的理由：{@code awesome-dsh-plugin.com/plugins.json} 比我们原来解析的
     * {@code PLUGINS-ALL.md} 多两样关键东西 ——
     * <ul>
     *   <li><b>npm 包名映射</b>（{@code npm} 字段）。我们原来是拿 owner/repo 去仓库读
     *       package.json 猜包名（{@code fetchNpmName}），走网络、多源回退、还常常查不到 ——
     *       「没查到 npm 包名」这条提示就是它。索引里现成有，直接用；</li>
     *   <li>作者给的 {@code install} 命令、双语描述、每日 CI 刷新的星标与下载量。</li>
     * </ul>
     * 这是社区市场 dsh-market 用的同一份数据源（它的 README 明说 npm mapping 由 CI 每天刷新）。
     *
     * <p>字段位置沿用既有约定，UI 与安装流程不用改：
     * {@code [0]=名字 [1]=星标 [2]=owner [3]=收录日期 [4]=npm包名或「仅GitHub仓库」
     * [5]=描述 [6]=仓库URL}。
     */
    public java.util.List<String[]> fetchMarketRows() {
        // ① 未过期缓存直接秒开，完全不碰网络。
        //    Markdown 索引那条路（fetchMarketIndex）一直是这么做的，plugins.json 这条
        //    上线时漏了缓存 —— 于是每次打开市场页都要现下 800KB+，网络慢就是干等，
        //    离线更是直接退回没有 npm 映射的 Markdown（装插件的成功率跟着掉）。
        String fresh = readPluginsJsonCache(true);
        if (fresh != null) {
            java.util.List<String[]> cachedRows = parsePluginsJson(fresh);
            if (!cachedRows.isEmpty()) {
                host.logActivity("市场索引来自本地缓存（" + cachedRows.size() + " 条，未过期）");
                return cachedRows;
            }
        }
        String[] sources = {
                "https://awesome-dsh-plugin.com/plugins.json",
                "https://cdn.jsdelivr.net/gh/awesome-dsh-plugin/awesome-dsh-plugin@main/plugins.json",
                HarnessController.gitHubProxy("https://raw.githubusercontent.com/"
                        + "awesome-dsh-plugin/awesome-dsh-plugin/main/plugins.json"),
        };
        for (String u : sources) {
            // plugins.json 有 800KB+，必须放开上限，否则被截断后解析失败、静默退回 Markdown
            String body = httpGetText(u, 15000, 60000, 8 * 1024 * 1024);
            if (body == null || body.length() < 200) continue;
            java.util.List<String[]> out = parsePluginsJson(body);
            if (!out.isEmpty()) {
                writePluginsJsonCache(body); // 拉成功即缓存，之后 6 小时内秒开
                host.logActivity("市场索引来自 plugins.json（" + out.size() + " 条，含 npm 映射）");
                return out;
            }
        }
        // ② 联网全失败：用过期缓存。它带 npm 映射，比退回 Markdown 有用得多
        String stale = readPluginsJsonCache(false);
        if (stale != null) {
            java.util.List<String[]> staleRows = parsePluginsJson(stale);
            if (!staleRows.isEmpty()) {
                host.logActivity("市场索引用了过期缓存（" + staleRows.size() + " 条，网络不通）");
                return staleRows;
            }
        }
        // ③ 最后退回老路：Markdown 表格（没有 npm 映射，安装时还得自己去猜包名）
        java.util.List<String[]> rows = parseMarketTable(fetchMarketIndex());
        return rows == null ? new java.util.ArrayList<>() : rows;
    }

    /** plugins.json 的本地缓存文件（与 Markdown 索引的 market-index.md 对称）。 */
    private java.io.File pluginsJsonCacheFile() {
        return new java.io.File(appContext.getFilesDir(), "market-plugins.json");
    }

    /**
     * 读 plugins.json 缓存。
     *
     * @param freshOnly true 时超过 {@link HarnessController#MARKET_CACHE_TTL_MS}（6 小时）
     *                  就当没有；false 用于「联网失败，拿旧的顶上」
     */
    private String readPluginsJsonCache(boolean freshOnly) {
        try {
            java.io.File f = pluginsJsonCacheFile();
            // 20KB 门槛：挡住写了一半的残缺文件（整份索引 800KB 以上）
            if (f.isFile() && f.length() > 20000) {
                if (freshOnly && System.currentTimeMillis() - f.lastModified()
                        > HarnessController.MARKET_CACHE_TTL_MS) {
                    return null;
                }
                return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 写 plugins.json 缓存（先落临时文件再 rename —— 中途被杀不会留下半份索引）。 */
    private void writePluginsJsonCache(String body) {
        try {
            java.io.File f = pluginsJsonCacheFile();
            java.io.File tmp = new java.io.File(f.getParentFile(), f.getName() + ".tmp");
            java.nio.file.Files.write(tmp.toPath(),
                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 把 plugins.json 解析成市场行（列语义见 {@link #fetchMarketRows} 的说明）。 */
    private java.util.List<String[]> parsePluginsJson(String body) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try {
            org.json.JSONObject root = new org.json.JSONObject(body);
            org.json.JSONArray arr = root.optJSONArray("plugins");
            // 索引自带分类的中英文名（categories.<key>.zh），直接用它 ——
            // 筛选菜单是按 it[4] 的实际值动态列的，所以这里换成中文，
            // 筛选项就跟着变中文，不用在 UI 里硬编码一张对照表
            org.json.JSONObject catMap = root.optJSONObject("categories");
            if (arr == null || arr.length() == 0) return out;
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "").trim();
                if (name.isEmpty()) continue;
                String owner = o.optString("owner", "").trim();
                String url = o.optString("url", "").trim();
                if (url.isEmpty() && !owner.isEmpty()) {
                    url = "https://github.com/" + owner + "/" + name;
                }
                String npm = o.optString("npm", "").trim();
                String desc;
                org.json.JSONObject d = o.optJSONObject("description");
                if (d != null) {
                    desc = d.optString("zh", "").trim();
                    if (desc.isEmpty()) desc = d.optString("en", "").trim();
                } else {
                    desc = o.optString("description", "").trim();
                }
                // 收录日期只能进描述。COMPAT 那一列是**兼容性标记**（「仅显示兼容」
                // 读的就是它），往那儿塞日期筛选器就永远筛不掉东西 —— 踩过一次。
                // 这份索引不带兼容性标注，填「⏳待定」，按既有语义「未知不误杀」。
                String added = o.optString("added", "").trim();
                if (!added.isEmpty()) desc = desc.isEmpty() ? "收录于 " + added
                        : desc + "（收录于 " + added + "）";
                String[] row = new String[MarketCol.NPM + 1];
                row[MarketCol.NAME] = name;
                row[MarketCol.STARS] = String.valueOf(o.optInt("stars", 0));
                row[MarketCol.OWNER] = owner;
                row[MarketCol.COMPAT] = "⏳待定";
                row[MarketCol.CATEGORY] = categoryLabel(catMap, o.optString("category", ""));
                row[MarketCol.DESC] = desc;
                row[MarketCol.URL] = url;
                row[MarketCol.NPM] = npm;
                // 形状自检：挡住「往某列塞了别的东西」这类错（日期落进 COMPAT、
                // 星标不是数字…）。宁可丢一条脏数据，也别让整页筛选/排序静默失灵。
                if (!MarketCol.isSaneRow(row)) {
                    android.util.Log.w("DSHA", "市场索引里一行形状不对，已跳过: " + name);
                    continue;
                }
                out.add(row);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** 分类 key → 中文名（索引里带 categories 映射；取不到就原样返回 key）。 */
    private static String categoryLabel(org.json.JSONObject catMap, String key) {
        String k = key == null ? "" : key.trim();
        if (k.isEmpty() || catMap == null) return k;
        org.json.JSONObject c = catMap.optJSONObject(k);
        if (c == null) return k;
        String zh = c.optString("zh", "").trim();
        if (!zh.isEmpty()) return zh;
        String en = c.optString("en", "").trim();
        return en.isEmpty() ? k : en;
    }

    public String fetchMarketIndex() {
        // 未过期缓存直接秒开（不请求网络）；失败再回退旧缓存
        String fresh = readMarketCache(true);
        if (fresh != null) return fresh;
        String[] urls = {
                HarnessController.gitHubProxy("https://raw.githubusercontent.com/AdamPlatin123/awesome-dsh-plugins/main/PLUGINS-ALL.md"),
                "https://cdn.jsdelivr.net/gh/AdamPlatin123/awesome-dsh-plugins@main/PLUGINS-ALL.md",
                "https://cdn.jsdelivr.net/gh/AdamPlatin123/awesome-dsh-plugins@master/PLUGINS-ALL.md",
                "https://gcore.jsdelivr.net/gh/AdamPlatin123/awesome-dsh-plugins@main/PLUGINS-ALL.md",
                "https://fastly.jsdelivr.net/gh/AdamPlatin123/awesome-dsh-plugins@main/PLUGINS-ALL.md",
                "https://raw.githubusercontent.com/AdamPlatin123/awesome-dsh-plugins/main/PLUGINS-ALL.md",
                "https://ghfast.top/https://raw.githubusercontent.com/AdamPlatin123/awesome-dsh-plugins/main/PLUGINS-ALL.md",
                "https://ghproxy.net/https://raw.githubusercontent.com/AdamPlatin123/awesome-dsh-plugins/main/PLUGINS-ALL.md",
                "https://cdn.jsdelivr.net/gh/AdamPlatin123/awesome-dsh-plugins@main/README.md"
        };
        String cached = readMarketCache(false); // 全部源失败时回退旧缓存（离线可浏览）
        for (String u : urls) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                conn.setConnectTimeout(6000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "DSHA/" + getVersionName());
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    continue;
                }
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                    if (sb.length() > 1200000) break;
                }
                conn.disconnect();
                String j = sb.toString();
                // 兼容两代索引格式：旧版折叠块（<summary><b>）/ 新版分组列表（"- `[状态]` [name](url)"，2026-08 起）
                boolean ok = j.length() > 8000 && j.indexOf("](http") >= 0
                        && (j.indexOf("- `[") >= 0
                            || (j.indexOf("<summary>") >= 0 && j.indexOf("<b>") >= 0));
                if (ok) {
                    writeMarketCache(j); // 拉成功即缓存，网络抽风时也能秒开
                    return j;
                }
            } catch (Exception ignored) {
            }
        }
        // 全部源失败：回退本地缓存（离线下仍可浏览上次成功的 1998 条）
        if (cached != null && cached.length() > 8000) return cached;
        return null;
    }

    /** 读市场索引本地缓存（App 私有目录） */
    /** 读市场索引本地缓存。freshOnly=true 仅当未超 {@link #HarnessController.MARKET_CACHE_TTL_MS} 才返回（过期则由调用方决定是否用旧缓存）。 */
    private String readMarketCache(boolean freshOnly) {
        try {
            java.io.File f = new java.io.File(appContext.getFilesDir(), "market-index.md");
            if (f.isFile() && f.length() > 8000) {
                if (freshOnly && System.currentTimeMillis() - f.lastModified() > HarnessController.MARKET_CACHE_TTL_MS) {
                    return null; // 已过期：需要去拉取刷新
                }
                return new String(java.nio.file.Files.readAllBytes(f.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 市场索引缓存已有多旧（ms），供 UI 显示“缓存于 N 分钟前”；无缓存返回 -1 */
    public long getMarketCacheAgeMs() {
        try {
            java.io.File f = new java.io.File(appContext.getFilesDir(), "market-index.md");
            if (f.isFile() && f.length() > 8000) return System.currentTimeMillis() - f.lastModified();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /** 强制刷新市场索引：先清本地缓存，下次拉取即走网络 */
    public void refreshMarketIndex() {
        try {
            java.io.File f = new java.io.File(appContext.getFilesDir(), "market-index.md");
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        } catch (Throwable ignored) {
        }
    }

    /** 写市场索引缓存 */
    private void writeMarketCache(String s) {
        try {
            java.io.File f = new java.io.File(appContext.getFilesDir(), "market-index.md");
            f.getParentFile().mkdirs();
            java.nio.file.Files.write(f.toPath(), s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** 解析市场索引（PLUGINS-ALL.md 列表式 / README 旧表格式）为 [name, star, owner, 兼容, 分类, 说明, url] */
    /** 非插件黑名单：客户端壳/桌面端/TUI/Docker/合集/启动器 等不是 dsh 插件的东西。
     * 上游 awesome-dsh-plugins 的 PLUGINS-ALL.md 是全量清单，混入了大量
     * Desktop/TUI/Docker/awesome 合集条目，不滤掉市场页会"乱拉"。
     * 只匹配条目名（desc/url 不参与，避免"支持 macOS"这类描述误伤真插件）。 */
    private static final String[] NON_PLUGIN_NAME_KEYS = {
            "desktop", "tui", "docker", "electron", "launcher",
            "windows", "macos", "swiftui"
    };

    /** 判断条目是否像"非插件"（客户端壳/合集/主仓库/市场UI）。只查名字与URL，命中即隐藏。
     * 规则分层（用真实索引 1940 条验证过）：
     * 1. 客户端壳关键词（desktop/tui/docker/...）
     * 2. 官方主仓库本体 deepseek-ai/deepseek-harness（DSH 本身不是插件）
     * 3. 市场 UI 插件（plugin-market/plugin-hub/... 几十个重复的市场入口，App 自带市场，滤掉）；
     *    注意别误伤 dsh-stock-market(股票)/dsh-webui-market-plugin(真插件)
     * 4. awesome- 合集 */
    private static boolean isLikelyNonPlugin(String name, String url, String desc) {
        String n = name.toLowerCase();
        String u = url == null ? "" : url.toLowerCase();
        // 例外：atuin（shell 历史工具插件）名字含 tui 子串，放行
        if (n.contains("atuin")) return false;
        for (String k : NON_PLUGIN_NAME_KEYS) {
            if (n.contains(k)) return true;
        }
        // 官方主仓库本体：deepseek-ai/deepseek-harness（DSH 本身不是插件）
        if (u.contains("github.com/deepseek-ai/deepseek-harness")) return true;
        // 市场 UI 插件（重复度高，App 自带市场；不含 stock-market 等真功能词）
        if (n.contains("plugin-market") || n.contains("plugins-market")
                || n.contains("plugin-hub") || n.contains("plugins-hub")
                || n.contains("plugin-store") || n.contains("dsh-market")) return true;
        // awesome- 合集（任意位置；含 plugin/skill/theme/pack 词干的可能是真插件，放行）
        if (n.contains("awesome-") && !(n.contains("plugin") || n.contains("skill")
                || n.contains("theme") || n.contains("pack"))) return true;
        return false;
    }

    public static java.util.List<String[]> parseMarketTable(String md) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        if (md == null) return out;
        String category = "";
        for (String raw : md.split("\n")) {
            String t = raw.trim();
            // ===== 分类：<summary><b>🎓 技能包（2）</b></summary> =====
            int b1 = t.indexOf("<b>");
            if (b1 >= 0) {
                int b2 = t.indexOf("</b>", b1);
                if (b2 > b1) {
                    String c = t.substring(b1 + 3, b2).trim();
                    c = c.replaceAll("（\\s*\\d+\\s*）$", "").replaceAll("\\(\\s*\\d+\\s*\\)$", "").trim();
                    int k = 0;
                    while (k < c.length()) {
                        int cp = c.codePointAt(k);
                        if (cp > 0x2E80) k += Character.charCount(cp); else break;
                    }
                    category = c.substring(k).trim();
                }
                continue;
            }
            // ===== 分类（新版）：## 🎓 技能包（20） =====
            if (t.startsWith("## ") || t.startsWith("### ")) {
                String c = t.replaceFirst("^#+\\s*", "").trim();
                // 只认带条目计数（N）的标题，跳过"统一度量衡/汇总"等说明性标题
                if (c.matches(".*（\\s*\\d+\\s*）$") || c.matches(".*\\(\\s*\\d+\\s*\\)$")) {
                    c = c.replaceAll("（\\s*\\d+\\s*）$", "").replaceAll("\\(\\s*\\d+\\s*\\)$", "").trim();
                    int k = 0;
                    while (k < c.length()) {
                        int cp = c.codePointAt(k);
                        if (cp > 0x2E80) k += Character.charCount(cp); else break;
                    }
                    category = c.substring(k).trim();
                }
                continue;
            }
            // ===== 条目：列表式  - `[可用]` [name](url) ★12 — desc（新版 star 无★前缀：… url) 67 — desc）=====
            if (t.startsWith("- `[")) {
                int c1 = t.indexOf('`'), c2 = t.indexOf('`', c1 + 1);
                if (c1 < 0 || c2 < 0) continue;
                String compat = t.substring(c1 + 1, c2).trim();
                int lb = t.indexOf('[', c2), rb = t.indexOf(']', lb + 1);
                if (lb < 0 || rb < 0) continue;
                String name = t.substring(lb + 1, rb).trim();
                int u1 = t.indexOf('(', rb), u2 = t.indexOf(')', u1 + 1);
                if (u1 < 0 || u2 < 0) continue;
                String url = t.substring(u1 + 1, u2).trim();
                // 有的加速代理（如 GH_PROXY）会改写内容里的链接为 <代理>/https://github.com/…，剥掉前缀还原
                int ghPos = url.indexOf("https://github.com/");
                if (ghPos > 0) url = url.substring(ghPos);
                if (!url.startsWith("http")) continue;
                String rest = t.substring(u2 + 1);
                // ★star
                String star = "0";
                int st = rest.indexOf("★");
                if (st >= 0) {
                    String sx = rest.substring(st + 1).trim();
                    int d = 0;
                    while (d < sx.length() && Character.isDigit(sx.charAt(d))) d++;
                    if (d > 0) star = sx.substring(0, d);
                } else {
                    // 新版格式：url 后直接跟裸数字 star（"…) 67 — desc"）
                    String sx = rest.trim();
                    int d = 0;
                    while (d < sx.length() && Character.isDigit(sx.charAt(d))) d++;
                    if (d > 0) star = sx.substring(0, d);
                }
                String desc = "";
                int dash = rest.indexOf("—");
                if (dash >= 0) desc = rest.substring(dash + 1).trim();
                String owner = "";
                String uu = url.replace("https://github.com/", "").replace("http://github.com/", "");
                int slash = uu.indexOf('/');
                if (slash > 0) owner = uu.substring(0, slash);
                compat = compat.replace("可用", "✅可用").replace("不兼容", "❌不兼容")
                        .replace("待定", "⏳待定").replace("未测", "⏳未测");
                if (compat.length() > 8) compat = compat.substring(0, 8);
                // 过滤非插件（桌面壳/TUI/合集等），避免市场乱拉
                if (isLikelyNonPlugin(name, url, desc)) continue;
                String[] row = new String[MarketCol.BASE_WIDTH];
                row[MarketCol.NAME] = name;
                row[MarketCol.STARS] = star;
                row[MarketCol.OWNER] = owner;
                row[MarketCol.COMPAT] = compat;
                row[MarketCol.CATEGORY] = category;
                row[MarketCol.DESC] = desc;
                row[MarketCol.URL] = url;
                if (MarketCol.isSaneRow(row)) out.add(row);
                continue;
            }
            // ===== 条目：表格式  | [name](url) | 类型 | 兼容 | 说明 |  =====
            if (t.startsWith("| [") && t.contains("](")) {
                String[] cells = t.split("\\|");
                if (cells.length < 5) continue;
                String first = cells[1].trim();
                int lb = first.indexOf('['), rb = first.indexOf("](");
                if (lb < 0 || rb < 0) continue;
                String name = first.substring(lb + 1, rb).trim();
                int u1 = first.indexOf('('), u2 = first.lastIndexOf(')');
                if (u1 < 0 || u2 < 0) continue;
                String url = first.substring(u1 + 1, u2).trim();
                // 同上：剥掉代理改写前缀
                int ghPos2 = url.indexOf("https://github.com/");
                if (ghPos2 > 0) url = url.substring(ghPos2);
                if (!url.startsWith("http")) continue;
                String compat = cells.length > 3 ? cells[3].trim() : "";
                String desc = cells.length > 4 ? cells[4].trim() : "";
                String owner = "";
                String uu = url.replace("https://github.com/", "").replace("http://github.com/", "");
                int slash = uu.indexOf('/');
                if (slash > 0) owner = uu.substring(0, slash);
                compat = compat.replace("✅ 运行级可用", "✅可用").replace("⏳ 未测", "⏳未测")
                        .replace("❌ 运行级不兼容", "❌不兼容").replace("✅", "✅可用");
                if (compat.isEmpty() || compat.equals("插件") || compat.equals("合集")) compat = "⏳未测";
                if (compat.length() > 8) compat = compat.substring(0, 8);
                // 过滤非插件（表格格式同样适用）
                if (isLikelyNonPlugin(name, url, desc)) continue;
                String[] row2 = new String[MarketCol.BASE_WIDTH];
                row2[MarketCol.NAME] = name;
                row2[MarketCol.STARS] = "0";
                row2[MarketCol.OWNER] = owner;
                row2[MarketCol.COMPAT] = compat;
                row2[MarketCol.CATEGORY] = category;
                row2[MarketCol.DESC] = desc;
                row2[MarketCol.URL] = url;
                if (MarketCol.isSaneRow(row2)) out.add(row2);
            }
        }
        return out;
    }

    /** 拉取单个仓库详情（最近更新/star/作者），GitHub API 单查 + 内存缓存 */
    public String[] fetchRepoInfo(String owner, String repo) {
        if (owner == null || owner.isEmpty() || repo == null || repo.isEmpty()) return null;
        String cacheKey = owner + "/" + repo;
        String[] cached = repoCache.get(cacheKey);
        if (cached != null) return cached;
        String[] urls = {
                "https://api.github.com/repos/" + cacheKey,
                "https://ghfast.top/https://api.github.com/repos/" + cacheKey
        };
        for (String u : urls) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                // 5 个源串行回退，超时必须短：原来 8s+10s 走到底最坏要等 90 秒，
                // 界面上就表现为「点了解析没反应」（issue #4）
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "DSHA/" + getVersionName());
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    continue;
                }
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                String all = "";
                String line;
                while ((line = r.readLine()) != null) {
                    all += line;
                    if (all.length() > 100000) break;
                }
                conn.disconnect();
                org.json.JSONObject j = new org.json.JSONObject(all);
                String pushed = j.optString("pushed_at", "");
                if (pushed.length() > 10) pushed = pushed.substring(0, 10);
                String[] info = new String[]{
                        pushed,
                        String.valueOf(j.optInt("stargazers_count", 0)),
                        j.optJSONObject("owner") == null ? "" : j.optJSONObject("owner").optString("login", ""),
                        j.optString("description", "")
                };
                repoCache.put(cacheKey, info);
                return info;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 从 GitHub 仓库拉取 npm 包名（package.json 的 name 字段），用于安装 */
    public String fetchNpmName(String owner, String repo) {
        String body = fetchGitHubPackageJson(owner, repo);
        if (body == null) return null;
        try {
            String name = new org.json.JSONObject(body).optString("name", "");
            if (!name.isEmpty() && !name.contains("${")) return name;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 取 GitHub 仓库根目录的 package.json 原文（多源回退：代理 → 直连 → ghfast）。
     *
     *  单独抽出来是因为**不止一个地方要读它**：查 npm 包名要读、安装前预检要读。
     *  各写一份的话就成了两套取包逻辑 —— 这个项目已经在「两套实现并存、
     *  改动落在没被调用的那一套」上栽过一次（内置插件安置连续六个版本无效），
     *  不能再来一遍。 */
    public String fetchGitHubPackageJson(String owner, String repo) {
        if (owner == null || owner.isEmpty() || repo == null || repo.isEmpty()) return null;
        String[] urls = {
                HarnessController.gitHubProxy("https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/package.json"),
                HarnessController.gitHubProxy("https://raw.githubusercontent.com/" + owner + "/" + repo + "/master/package.json"),
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/package.json",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/master/package.json",
                "https://ghfast.top/https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/package.json"
        };
        for (String u : urls) {
            try {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "DSHA/" + getVersionName());
                if (conn.getResponseCode() != 200) {
                    conn.disconnect();
                    continue;
                }
                java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                String all = "";
                String line;
                while ((line = r.readLine()) != null) {
                    all += line;
                    if (all.length() > 50000) break;
                }
                conn.disconnect();
                if (all.contains("\"name\"")) return all;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 卸载插件：先物理清理（Java+系统rm双通道）→ dsh remove → manifest 直改兜底；结果全部回显 */
    public String removePlugin(String pkg) {
        StringBuilder log = new StringBuilder();
        try {
            if (!isValidPluginSpec(pkg)) {
                log.append("卸载失败：非法插件名（").append(pkg == null ? "null" : pkg).append("）\n");
                return log.toString();
            }
            String esc = ShellQuote.arg(pkg); // 防注入（dsh remove / python argv 共用）
            // 1. 先物理清理（即使后面异常，实体也已删除）
            boolean cleared = physicalRemovePluginRobust(pkg);
            log.append("[DSHA] 实体清理").append(cleared ? "完成 ✅" : "失败（仍存在）⚠️").append("\n");
            // 2. dsh remove + manifest 直改（包名走 python argv，避免拼进 heredoc 内容）
            String py = "python3 - " + esc + " <<'PY'\n" +
                    "import json,sys\n" +
                    "p='/root/.dsh/profiles/web/package.json'\n" +
                    "pn=sys.argv[1]\n" +
                    "try:\n" +
                    " d=json.load(open(p))\n" +
                    " d.get('dependencies',{}).pop(pn,None)\n" +
                    " b=d.get('dsh',{}).get('profile',{}).get('bundles')\n" +
                    " if b: d['dsh']['profile']['bundles']=[x for x in b if x!=pn]\n" +
                    " json.dump(d,open(p,'w'),indent=2,ensure_ascii=False)\n" +
                    "except Exception as e:\n" +
                    " print('[DSHA] manifest 修改失败:',e); sys.exit(1)\n" +
                    "print('[DSHA] manifest 已移除: '+pn)\n" +
                    "PY";
            String r = proot.execAndRead(
                    "( dsh plugin --profile web remove " + esc + " 2>&1 || " +
                    "node apps/cli/lib/bin.js plugin --profile web remove " + esc + " 2>&1 || " +
                    "echo '[DSHA] dsh remove 未生效，走 manifest 直改' ) ; " +
                    py + "; echo REMOVE_EXIT=$?");
            log.append(SensitiveData.redact(r));
        } catch (Exception e) {
            log.append("卸载执行异常: ").append(SensitiveData.redact(e.getMessage()));
        }
        // 上游报告过一种更阴的情形：插件卸载后 manifest 看着干净了，但 profile 的
        // package-map/lock 仍留着 profile-local 的 @deepseek-ai 副本，故障延后爆发
        // （表现依旧是所有工具调用失败）。所以卸完也查一次。
        String dupes = checkAndFixDshDupes();
        if (dupes != null && dupes.contains("DUPES_FIXED=") && !dupes.contains("DUPES_FIXED=0")) {
            log.append("\n\n已顺带清掉残留的 @deepseek-ai 重复副本 —— 它们会在卸载后留下来，"
                    + "让工具调用全部失败。");
        }
        return SensitiveData.redact(log.toString());
    }

    /** 双通道物理清理：Java 递归删 + 系统 rm -rf 兜底；返回是否删干净 */
    private boolean physicalRemovePluginRobust(String pkg) {
        java.io.File nm = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/node_modules");
        try {
            physicalRemovePlugin(pkg); // Java 递归删（.disabled 与 scoped 容器一并处理）
            // 双保险：系统 rm -rf（Android /system/bin/rm，绕过一切 Java/Proot 层怪问题）
            String[] targets = {pkg, pkg + ".disabled"};
            for (String t : targets) {
                java.io.File f = new java.io.File(nm, t);
                if (f.exists()) {
                    Process p = new ProcessBuilder("/system/bin/rm", "-rf", f.getAbsolutePath())
                            .redirectErrorStream(true).start();
                    // 有超时：这是 UI 触发的同步路径，rm 卡住（目录巨大 / 文件系统异常）
                    // 会把线程永久挂住，用户看到的是「点删除就卡死」。
                    if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        android.util.Log.w("DSHA", "rm -rf 超时已放弃: " + f.getAbsolutePath());
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return !new java.io.File(nm, pkg).exists()
                && !new java.io.File(nm, pkg + ".disabled").exists();
    }

    /** Java 侧物理清理：删 node_modules 顶层实体(.disabled 变体) + scoped 容器 + .pnpm 模糊匹配 */
    private void physicalRemovePlugin(String pkg) {
        try {
            java.io.File nm = new java.io.File(proot.getRootfsDir(), "root/.dsh/profiles/web/node_modules");
            if (!nm.isDirectory()) return;
            String core = pkg;
            if (pkg.startsWith("@") && pkg.contains("/")) {
                // scoped：先删容器内子包，空容器顺手删
                java.io.File container = new java.io.File(nm, pkg.substring(0, pkg.indexOf('/')));
                String sub = pkg.substring(pkg.indexOf('/') + 1);
                host.deleteRecursively(new java.io.File(container, sub));
                host.deleteRecursively(new java.io.File(container, sub + ".disabled"));
                if (container.isDirectory()) {
                    String[] left = container.list();
                    if (left == null || left.length == 0) host.deleteRecursively(container);
                }
                core = pkg.substring(1).replace("/", "+");
            } else {
                host.deleteRecursively(new java.io.File(nm, pkg));
                host.deleteRecursively(new java.io.File(nm, pkg + ".disabled"));
            }
            // .pnpm 模糊匹配（不管版本号变体）
            java.io.File pnpm = new java.io.File(nm, ".pnpm");
            if (pnpm.isDirectory()) {
                java.io.File[] es = pnpm.listFiles(java.io.File::isDirectory);
                if (es != null) for (java.io.File e : es) {
                    String n = e.getName();
                    if (n.equals(core) || n.startsWith(core + "@")) host.deleteRecursively(e);
                }
            }
        } catch (Throwable ignored) {
        }
    }


    /** 安装插件：优先源码目录（node bin.js），无源码目录自动回退全局 dsh；依赖自愈前置 */
    public String installPlugin(String pkg) {
        return installPlugin(pkg, null);
    }

    /** 已经内置在 APK 里的插件（用 link: 指向本地目录注册，不走网络）。
     *  再从 GitHub 装一份同名的，只会和内置版本抢同一个包名，还得走 git-hosted
     *  那条最容易出错的路（clone → 跑 prepare → 硬链接进 store）。 */
    static final String[] BUILTIN_PLUGIN_NAMES = {
            "dsh-device-shell-guide",
            "dsh-task-notifier",
            "dsh-status-overlay",
    };

    private static String builtinPluginHit(String spec) {
        if (spec == null || spec.isEmpty()) return null;
        String s = spec.toLowerCase(java.util.Locale.ROOT);
        for (String n : BUILTIN_PLUGIN_NAMES) {
            if (s.contains(n)) return n;
        }
        return null;
    }

    /** pnpm 在容器里最常见的一类失败：git-hosted 包在 store 的临时目录里
     *  clone/prepare 时 ENOENT。根子是 proot 下硬链接只能靠 --link2symlink 模拟。 */
    private static boolean isPnpmEnvFailure(String out) {
        if (out == null) return false;
        if (out.contains("Failed to prepare git-hosted package")) return true;
        boolean storePath = out.contains("pnpm/store") || out.contains("/store/v");
        return storePath && (out.contains("ENOENT") || out.contains("EEXIST"));
    }

    /** 通用 GET 取文本（插件解析用）。失败返回 null，不抛。 */
    private String httpGetText(String url, int connectMs, int readMs) {
        return httpGetText(url, connectMs, readMs, 512 * 1024);
    }

    /** 带上限的版本。默认 512KB 对 package.json / registry 元数据够用，但市场索引
     *  plugins.json 已经 800KB 以上 —— 用默认上限会**静默截断**，JSON 解析失败后悄悄
     *  退回 Markdown 那条老路，看起来一切正常而 npm 映射根本没生效。 */
    private String httpGetText(String url, int connectMs, int readMs, int maxBytes) {
        java.net.HttpURLConnection conn = null;
        try {
            conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(connectMs);
            conn.setReadTimeout(readMs);
            conn.setRequestProperty("User-Agent", "DSHA/" + getVersionNameForUa());
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                final int MAX = maxBytes > 0 ? maxBytes : 512 * 1024;
                while ((n = in.read(buf)) != -1 && total < MAX) {
                    bos.write(buf, 0, n);
                    total += n;
                }
            }
            return bos.toString("UTF-8");
        } catch (Throwable e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 「这个插件**只能**从 npm registry 装」的硬信号。
     *
     *  三条判据都来自实测，命中任一条就说明 git 源那条路走不通：
     *
     *  ① `workspace:*` 依赖 —— monorepo 内部引用。git clone 下来只有一个子目录，
     *     那些 workspace 包压根不存在，pnpm 直接报解析失败。
     *  ② `scripts.prepare` —— **pnpm ≥11 默认拒绝执行 git 依赖的 prepare 脚本**。
     *     而 prepare 往往正是编译产物的地方（tsc / vite build），
     *     拒绝执行就等于装了个空壳。
     *  ③ `bundledDependencies` —— 这些包只在 `npm publish` 打 tarball 时才塞进去，
     *     git 源永远拿不到。
     *
     *  dsh-TUI（生态里 2343 星那个）同时命中①②③，它自己 README 就写着
     *  「Git URL 安装不受支持，请安装 registry 包」。
     *
     *  命中之后的正确行为是**强制走 registry 且不回退 git** ——
     *  回退只会浪费用户几分钟然后给一段看不懂的 pnpm 堆栈。 */
    private String mustUseRegistryReason(String pkgJson) {
        if (pkgJson == null) return null;
        String compact = pkgJson.replace(" ", "").replace("\n", "");
        if (compact.contains("\"workspace:")) {
            return "依赖里有 workspace:*（monorepo 内部引用），git 源拿不到这些包";
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(pkgJson);
            org.json.JSONObject sc = o.optJSONObject("scripts");
            if (sc != null && sc.has("prepare")) {
                return "它靠 prepare 脚本生成产物，而 pnpm 11 起默认拒绝执行 git 依赖的 prepare";
            }
            if (o.optJSONArray("bundledDependencies") != null) {
                return "它有 bundledDependencies（只在 npm 发布时打包），git 源拿不到";
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 兜底：从 README 里捞 registry 包名。
     *
     *  用在 package.json 的 name 拿不到、或者那个 name 不是发布名的时候
     *  （monorepo 根包很常见）。插件作者几乎都会在 README 写一行安装命令，
     *  那行就是最权威的「该装哪个包」。 */
    private String fetchNpmNameFromReadme(String owner, String repo) {
        String[] urls = {
                HarnessController.gitHubProxy("https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/README.md"),
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/main/README.md",
                "https://raw.githubusercontent.com/" + owner + "/" + repo + "/master/README.md",
        };
        // 只认明确的安装命令，别在正文里瞎猜包名
        java.util.regex.Pattern pat = java.util.regex.Pattern.compile(
                "(?:dsh\\s+plugin(?:\\s+--profile\\s+\\S+)?\\s+add|npm\\s+i(?:nstall)?(?:\\s+-g)?"
                        + "|pnpm\\s+add|yarn\\s+add)\\s+((?:@[a-z0-9._-]+/)?[a-z0-9._-]+)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        for (String u : urls) {
            String body = httpGetText(u, 6000, 12000);
            if (body == null) continue;
            java.util.regex.Matcher m = pat.matcher(body);
            while (m.find()) {
                String cand = m.group(1);
                // 跳过官方 dsh 本体这类明显不是「本插件」的候选
                if (cand.startsWith("@deepseek-ai/") || cand.equals("dsh")
                        || cand.equals("pnpm") || cand.equals("corepack")) {
                    continue;
                }
                if (npmRegistryHas(cand, null)) return cand;
            }
        }
        return null;
    }

    /** 安装前预检的**对外**版本：给市场页在用户等待之前就把结论摆出来。
     *
     *  社区标准（dsh-community-standard v0.15 §3）要求市场用五态展示兼容性，
     *  并且「不得互相升级」——「声明兼容」不等于「已实测」，更不等于「安全」。
     *  我们没有协商器，所以不照抄它的标签（那样等于吹牛），
     *  只报**自己真能判断**的四种结论。
     *
     *  返回 {verdict, 给用户看的说明, 建议使用的包名或 null}：
     *    ok       registry 上有，直接装
     *    build    只能从 git 装且需要现场构建 —— 慢，且 pnpm ≥11 可能直接拒绝
     *    blocked  基本装不上（monorepo 内部依赖且 npm 上没有）
     *    unknown  信息不足（拿不到 package.json），照原样试 */
    public String[] precheckForMarket(String spec, String npmNameHint) {
        try {
            String[] ref = parseGitHubRef(spec);
            String body = ref == null ? null : fetchGitHubPackageJson(ref[0], ref[1]);
            if (body == null) {
                // 拿不到仓库信息：如果调用方已经有 npm 名，就按它查 registry
                if (npmNameHint != null && npmRegistryHas(npmNameHint, null)) {
                    return new String[]{"ok",
                            "npm registry 上有 " + npmNameHint + "，可以直接安装。", npmNameHint};
                }
                return new String[]{"unknown",
                        "拿不到这个仓库的 package.json（网络不通或仓库结构特殊），"
                                + "安装能不能成只有试过才知道。", null};
            }
            org.json.JSONObject o = new org.json.JSONObject(body);
            String name = o.optString("name", "");
            boolean onNpm = !name.isEmpty() && npmRegistryHas(name, null);
            String onlyWhy = mustUseRegistryReason(body);
            if (onNpm) {
                return new String[]{"ok",
                        "npm registry 上有 " + name + "（发布版含构建产物），"
                                + "会按 npm 包安装 —— 这是最稳的一条路。"
                                + (onlyWhy == null ? ""
                                        : "\n\n（顺带说：这个插件只能这么装，"
                                                + onlyWhy + "。已自动处理。）"), name};
            }
            // package.json 的 name 查不到 → 从 README 的安装命令兜底
            String fromReadme = ref == null ? null : fetchNpmNameFromReadme(ref[0], ref[1]);
            if (fromReadme != null) {
                return new String[]{"ok",
                        "仓库 package.json 的名字在 npm 上查不到，但 README 里写的是 "
                                + fromReadme + "，registry 上有这个包，会用它安装。", fromReadme};
            }
            if (onlyWhy != null) {
                return new String[]{"blocked",
                        (name.isEmpty() ? "这个插件" : name)
                                + " 只能从 npm registry 安装（" + onlyWhy + "），"
                                + "但 npm 上找不到发布版。\n\n"
                                + "从 git 源装必然失败，所以不建议白等。\n"
                                + "去仓库 README 找「registry 包名」那一行，或等作者发布。", null};
            }
            boolean hasWorkspace = body.replace(" ", "").contains("\"workspace:");
            org.json.JSONObject scripts = o.optJSONObject("scripts");
            boolean needBuild = scripts != null && scripts.has("build");
            String files = o.optJSONArray("files") == null ? "" : o.optJSONArray("files").toString();

            if (hasWorkspace) {
                return new String[]{"blocked",
                        (name.isEmpty() ? "这个插件" : name) + " 的依赖里有 workspace:*"
                                + "（monorepo 内部引用），从 git 源装必然失败，"
                                + "而 npm registry 上又没有发布版。\n\n"
                                + "这类插件只能等作者发布到 npm。可以先去仓库看看 README "
                                + "里有没有写「registry 包名」。", null};
            }
            if (needBuild && files.contains("dist")) {
                return new String[]{"build",
                        (name.isEmpty() ? "这个插件" : name) + " 的产物在 dist/ 且需要现场构建，"
                                + "而 git 仓库通常不含 dist。\n\n"
                                + "会尝试 clone 下来自己装依赖并构建，可能要好几分钟，"
                                + "也可能因为缺构建工具而失败。\n"
                                + "另外 pnpm 11 起默认拒绝执行 git 依赖的 prepare 脚本，"
                                + "这条路本身就不太稳。", null};
            }
            return new String[]{"unknown",
                    (name.isEmpty() ? "这个插件" : name)
                            + " 没发布到 npm，会按 GitHub 仓库方式安装。\n"
                            + "没发现明显的阻碍，但仓库源安装的成功率本来就低一些。", null};
        } catch (Throwable e) {
            return new String[]{"unknown", "预检没跑成：" + HarnessController.describe(e), null};
        }
    }

    /** 安装前预检：读仓库的 package.json，判断「这个插件从 git 装能不能成」。
     *
     *  社区标准（oh-my-dsh/dsh-community-standard v0.15）提的第二条裂缝正是这个：
     *  「装上才知道炸 —— 装之前没人能回答这个插件能不能跑，唯一的报错方式是崩溃」。
     *  它给了 dsh-plugin.json 的 JSON Schema，但那是一周前的 Draft，
     *  生态里 3800+ 个插件目前都还在用 package.json 的 dsh 字段，
     *  拿新 schema 去校验现有插件会全部不合格 —— 所以这里不用它的 schema，
     *  而是照**现有插件的真实结构**做判断。判据都来自这轮踩到的实际故障。
     *
     *  返回 null 表示没发现问题；否则返回给用户看的提示。 */
    private String precheckGitPlugin(String spec) {
        try {
            String[] ref = parseGitHubRef(spec);
            if (ref == null) return null;
            String body = fetchGitHubPackageJson(ref[0], ref[1]);
            if (body == null) return null;               // 拿不到就别拦，让安装自己去试
            org.json.JSONObject o = new org.json.JSONObject(body);
            String name = o.optString("name", "");
            StringBuilder warn = new StringBuilder();

            // ① workspace: 依赖 —— 这种包的 git 源装不了（dsh-TUI 自己 README 就说明了）
            String all = body.replace(" ", "");
            if (all.contains("\"workspace:")) {
                warn.append("· 它的依赖里有 workspace:*（monorepo 内部引用），"
                        + "从 git 源装必然失败\n");
            }
            // ② 声明了 dist 但仓库里没有 → 需要现场构建，pnpm 11 起还会拒绝跑 prepare
            String files = o.optJSONArray("files") == null ? "" : o.optJSONArray("files").toString();
            org.json.JSONObject scripts = o.optJSONObject("scripts");
            boolean needBuild = scripts != null && scripts.has("build");
            if (files.contains("dist") && needBuild) {
                warn.append("· 产物在 dist/ 且需要现场构建（build 脚本存在），"
                        + "git 源通常不含 dist\n");
            }
            // ③ 压根不是 dsh 插件
            if (!o.has("dsh") && !name.contains("dsh")) {
                warn.append("· package.json 里没有 dsh 字段，可能不是 dsh 插件\n");
            }
            if (warn.length() == 0) return null;

            // 有问题的话，顺手看看 registry 上有没有 —— 有就直接给出正确装法
            String fix = "";
            if (!name.isEmpty() && npmRegistryHas(name, o.optString("version", ""))) {
                fix = "\n好消息：npm 上有 " + name + "，装这个就行（已自动改用它）。\n";
            } else if (!name.isEmpty()) {
                fix = "\nnpm registry 上暂时没有 " + name + "，"
                        + "会尝试 clone + 构建，可能比较慢。\n";
            }
            return "[安装前预检] " + (name.isEmpty() ? spec : name) + " 从 git 源安装可能有问题：\n"
                    + warn + fix;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 从各种 GitHub 写法里取出 {owner, repo}；不是 GitHub 地址返回 null。 */
    private String[] parseGitHubRef(String spec) {
        if (spec == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:github:|https?://github\\.com/)([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)")
                .matcher(spec);
        if (!m.find()) return null;
        return new String[]{m.group(1), m.group(2).replaceAll("\\.git$", "")};
    }

    /** registry 上是否有这个包（顺带比对版本，版本一致才值得替换 GitHub 源）。 */
    private boolean npmRegistryHas(String name, String wantVersion) {
        try {
            String url = "https://registry.npmmirror.com/"
                    + name.replace("/", "%2F");
            String body = httpGetText(url, 6000, 15000);
            if (body == null || body.length() < 20) return false;
            org.json.JSONObject o = new org.json.JSONObject(body);
            org.json.JSONObject tags = o.optJSONObject("dist-tags");
            String latest = tags == null ? "" : tags.optString("latest", "");
            if (latest.isEmpty()) return false;
            if (wantVersion == null || wantVersion.isEmpty()) return true;
            // 版本不一致也允许（registry 可能更新），只是记一笔
            if (!latest.equals(wantVersion)) {
                android.util.Log.i("DSHA", "registry 版本 " + latest
                        + " 与仓库 " + wantVersion + " 不同，仍优先用 registry");
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 最后一招：自己 clone 下来、装依赖、必要时构建，再以本地目录 link 进 profile。
     *
     *  这条路**完全不经过 pnpm 的 store/tmp 与 git-hosted prepare 流程**，
     *  所以能绕开 npm/cli#2144 那类上游 bug。代价是慢（要装 devDeps 并构建）。 */
    private String installFromGitClone(String spec) {
        try {
            String[] ref = parseGitHubRef(spec);
            if (ref == null) return "CLONE_SKIP: 不是 GitHub 地址";
            String owner = ref[0], repo = ref[1];
            String dir = "/root/dsha-plug-" + repo.toLowerCase(java.util.Locale.ROOT)
                    .replaceAll("[^a-z0-9._-]", "-");
            String cmd = "set -e; rm -rf " + ShellQuote.arg(dir) + "; "
                    + "echo '[1/4] clone…'; "
                    + "(git clone --depth 1 -q " + ShellQuote.arg("https://github.com/" + owner + "/" + repo)
                    + " " + ShellQuote.arg(dir)
                    + " || git clone --depth 1 -q " + ShellQuote.arg(HarnessController.gitHubProxy("https://github.com/" + owner + "/" + repo))
                    + " " + ShellQuote.arg(dir) + "); "
                    + "cd " + ShellQuote.arg(dir) + "; "
                    + "echo '[2/4] 装依赖…'; "
                    + "npm install --registry=https://registry.npmmirror.com 2>&1 | tail -3; "
                    + "echo '[3/4] 构建（若有 build 脚本）…'; "
                    + "if node -e \"process.exit((require('./package.json').scripts||{}).build?0:1)\" 2>/dev/null; then "
                    + "npm run build 2>&1 | tail -5; fi; "
                    + "echo '[4/4] 以本地目录注册…'; "
                    + "cd /root/.dsh/profiles/web && "
                    + "pnpm add " + ShellQuote.arg("link:" + dir) + " 2>&1 | tail -5; "
                    + "echo CLONE_INSTALL_EXIT=$?";
            String out = proot.execAndRead(cmd, 600_000);
            return out == null ? "CLONE_FAIL: 无输出" : out;
        } catch (Throwable e) {
            return "CLONE_FAIL: " + HarnessController.describe(e);
        }
    }

    /**
     * 安装插件（带 GitHub 兜底）：先按 pkg 装（npm 名），若 404/找不到包 且给了 fallbackSpec，
     * 自动用 github:owner/repo 重试一次（市场条目多为仅 GitHub 发布的仓库插件）。
     */
    public String installPlugin(String pkg, String fallbackSpec) {
        // single-flight：并发装同一个 profile 会撞 pnpm 的 lockfile 与 store。
        // tryLock 对已持有锁的线程直接成功，所以 installSubdirFromSource 构建完
        // 回头调这里不会自锁。
        if (!installLock.tryLock()) {
            return "已经有一个插件在安装了。并发装同一个 profile 会撞 pnpm 的锁、"
                    + "可能把 profile 弄坏 —— 等那个装完再点。";
        }
        try {
            // 装之前留一份存档点，装坏了能一键退回（PluginSavepoint 的类注释里写了它能
            // 还原什么、还原不了什么）。
            //
            // 只在**最外层**打：installSubdirFromSource 构建完会回头调 installPlugin，
            // 那是同一线程重入 tryLock，再打一份就会把「装之前」的干净状态覆盖成
            // 「装了一半」的状态 —— 保险变成了拍下事故现场。
            boolean outer = installLock.getHoldCount() == 1;
            String savepoint = outer ? PluginSavepoint.create(proot, host, pkg) : null;
            String result = installPluginLocked(pkg, fallbackSpec);
            if (outer && savepoint != null && (result == null || !result.contains("INSTALL_EXIT=0"))) {
                String rollback = PluginSavepoint.restore(proot, host);
                result = (result == null ? "安装失败" : result)
                        + "\n\n[安装失败，已还原安装前 profile]\n" + rollback;
            }
            return result;
        } finally {
            installLock.unlock();
        }
    }

    private String installPluginLocked(String pkg, String fallbackSpec) {
        if (!isValidPluginSpec(pkg)) {
            return "安装失败：非法插件名/来源：" + (pkg == null ? "null" : pkg);
        }
        // 内置插件直接劝退：装了只会打架，而且白踩一次 git-hosted 的坑
        String builtin = builtinPluginHit(pkg);
        if (builtin == null) builtin = builtinPluginHit(fallbackSpec);
        if (builtin != null) {
            return "无需安装：" + builtin + " 已经内置在 DSHA 里。\n\n"
                    + "它每次启动都会自动注册（本地 link:，不走网络），再从 GitHub 装一份"
                    + "同名插件会和内置版本抢同一个包名。\n"
                    + "如果市场里显示它未安装，去「配置」页点「重启 Web」让内置版本生效即可。";
        }
        // ── 第 0 级：GitHub 地址先问 npm registry 有没有同名包 ──
        // registry 上的 tarball 是发布时**构建好的**，而 git 主干往往不含 dist；
        // 更要紧的是 git-hosted 那条路会撞 pnpm/npm 的上游 bug
        // （npm/cli#2144：clone 到 store/tmp 时丢 .gitignore → prepare 阶段 ENOENT，
        //  用户报的正是这个）。所以能走 registry 就别走 git。
        StringBuilder tried = new StringBuilder();
        // issue #49：装之前先清掉残缺实体 —— pnpm 看到同名目录就报 Already up to date、
        // added 0，压根不会重新下载，于是后续诊断全在读一个上次中断留下的坏包。
        tried.append(dropBrokenEntity(pkg));
        String spec = pkg;
        String pre = precheckGitPlugin(fallbackSpec != null ? fallbackSpec : pkg);
        if (pre != null) {
            tried.append(pre);
            host.logActivity("安装前预检提示：" + pre.replace("\n", " ").trim());
        }
        String[] ghRef = parseGitHubRef(fallbackSpec);
        boolean registryOnly = false;          // 命中硬信号：git 源必死，禁止回退
        String registryOnlyWhy = null;
        if (ghRef != null) {
            String pkgJson = fetchGitHubPackageJson(ghRef[0], ghRef[1]);
            registryOnlyWhy = mustUseRegistryReason(pkgJson);

            // 找 registry 包名：先用 package.json 的 name，不行再从 README 的安装命令捞
            String real = null;
            if (pkgJson != null) {
                try {
                    String n = new org.json.JSONObject(pkgJson).optString("name", "");
                    if (!n.isEmpty() && !n.contains("${") && npmRegistryHas(n, null)) real = n;
                } catch (Throwable ignored) {
                }
            }
            if (real == null) {
                real = fetchNpmNameFromReadme(ghRef[0], ghRef[1]);
                if (real != null) {
                    tried.append("· package.json 的 name 在 npm 上查不到，"
                            + "从 README 的安装命令认出 ").append(real).append("\n");
                }
            }

            if (real != null) {
                if (!real.equals(spec)) {
                    host.logActivity("插件改走 npm registry：" + real
                            + (registryOnlyWhy == null ? "" : "（" + registryOnlyWhy + "）"));
                    tried.append("· 按 npm 包安装 ").append(real).append("\n");
                }
                spec = real;
                registryOnly = registryOnlyWhy != null;
            } else if (registryOnlyWhy != null) {
                // 只能用 npm、而 registry 上又没有 —— 但这**不等于装不了**。
                // pnpm 拒绝执行 git 依赖的 prepare 脚本，不代表我们不能自己 clone 下来跑构建：
                // 容器里就是完整的 Node 24 + pnpm 工具链，这正是该用它的时候。
                // 生态里大量插件都是「TS 源码 + 只有 build 脚本 + 产物不进仓库 + 没发 npm」，
                // 之前在这里一律劝退，就是「市场里大部分东西都装不了」的主要来源。
                GitHubRef gr = GitHubRef.parse(fallbackSpec);
                String srcName = "", srcMain = "", srcBuild = "";
                if (pkgJson != null) {
                    try {
                        org.json.JSONObject o = new org.json.JSONObject(pkgJson);
                        srcName = o.optString("name", "").trim();
                        srcMain = o.optString("main", "").trim();
                        org.json.JSONObject sc = o.optJSONObject("scripts");
                        if (sc != null) srcBuild = sc.optString("build", "").trim();
                    } catch (Throwable ignored) {
                    }
                }
                if (gr != null && !srcName.isEmpty() && !srcBuild.isEmpty()) {
                    host.logActivity("插件 npm 上没有，改走容器内 clone+构建：" + fallbackSpec);
                    return "这个插件没有 npm 发布版（" + registryOnlyWhy + "），"
                            + "改为在容器里 clone 源码自己构建 —— 这一步要几分钟。\n\n"
                            + installSubdirFromSource(gr, srcName, srcMain, srcBuild);
                }
                // 连包名或构建脚本都没有，才真的没办法
                host.logActivity("插件只能从 npm 装但 registry 上没有：" + fallbackSpec);
                return "无法安装：" + fallbackSpec + "\n\n"
                        + "这个插件**只能从 npm registry 安装**（" + registryOnlyWhy + "），"
                        + "但 npm 上找不到它的发布版"
                        + (srcBuild.isEmpty() ? "，而它的 package.json 里也没有 build 脚本"
                                             : "") + "。\n\n"
                        + "从 git 源装必然失败，所以就不白等了。\n"
                        + "建议去仓库 README 找「registry 包名」那一行，"
                        + "或者等作者发布到 npm。";
            }
        }

        String r = runPluginInstall(spec);
        // ENOENT 这类是环境问题而不是包的问题：修掉硬链接配置与残留后重试一次
        if (isPnpmEnvFailure(r)) {
            String fix = host.runAssetScript("pnpm-env-fix.sh", "dsha-pnpm-env-fix.sh", 60_000);
            tried.append("· 第 1 次失败（pnpm 环境问题），已修配置并重试\n");
            r = r + "\n\n[检测到 pnpm 环境问题，已修复并重试]\n"
                    + (fix == null ? "" : fix.trim() + "\n") + runPluginInstall(spec);
        }
        // ── 第 1.5 级：网络故障 → 换镜像源重试同一个包 ──
        // 关键是**不换机制**：网络不好跟「包在哪」无关，换去 git 源只会把
        // 一次可重试的失败变成一次必然失败。
        if (isNetworkFailure(r) && !r.contains("INSTALL_EXIT=0")) {
            tried.append("· 网络故障（不是包不存在），换镜像源重试\n");
            host.logActivity("插件安装遇到网络故障，换镜像源重试：" + spec);
            String alt = proot.execAndRead(
                    "cd /root/.dsh/profiles/web 2>/dev/null || cd /root/.dsh; "
                            + "pnpm add --registry=https://registry.npmjs.org "
                            + ShellQuote.arg(spec) + " 2>&1 | tail -20; echo INSTALL_EXIT=$?",
                    300_000);
            r = r + "\n\n[换用 npm 官方源重试…]\n" + (alt == null ? "无输出" : alt);
        }
        // ── 第 2 级：包名找不到 → 用 GitHub 源再试 ──
        if (r != null && registryOnly && !r.contains("INSTALL_EXIT=0")) {
            // 已知 git 源必死，不做无意义的回退 —— 那只会再耗几分钟再失败一次
            tried.append("· 不回退 git 源：").append(registryOnlyWhy).append("\n");
            r = r + "\n\n[跳过 GitHub 回退：这个插件只能从 npm 装（" + registryOnlyWhy + "）]";
        } else if (r != null && fallbackSpec != null && !fallbackSpec.equals(spec)
                && isPkgNotFound(r)) {
            if (!isValidPluginSpec(fallbackSpec)) {
                r += "\n[自动回退被忽略：非法来源 " + fallbackSpec + "]";
            } else {
                tried.append("· registry 里没有，改用 GitHub 源\n");
                r = "\n[自动回退 GitHub 仓库方式安装…]\n" + runPluginInstall(fallbackSpec);
            }
        }
        // ── 第 2.5 级：prepare 脚本被 pnpm 挡住 → 按 dsh 给的修法写 allowBuilds 再重试 ──
        // 这条比自己 clone+构建 轻得多（pnpm 直接跑插件的 prepare），所以排在它前面。
        if (r != null && !r.contains("INSTALL_EXIT=0")
                && (r.contains("allowBuilds") || r.contains("prepare script"))) {
            tried.append("· prepare 脚本被 pnpm 挡住，按 dsh 的提示授权后重试\n");
            String retry = allowBuildsAndRetry(pkg, fallbackSpec != null ? fallbackSpec : spec, r);
            if (!retry.isEmpty()) r = r + retry;
        }
        // ── 第 3 级：仍然是 git-hosted 的 prepare/ENOENT → 自己 clone、构建、link ──
        // 这条路完全不经过 pnpm 的 store/tmp 与 prepare，所以能绕开那个上游 bug。
        // 慢（要装 devDeps 并构建），所以放最后。
        if (r != null && !r.contains("INSTALL_EXIT=0") && isPnpmEnvFailure(r)
                && !registryOnly && fallbackSpec != null && fallbackSpec.contains("github")) {
            tried.append("· git-hosted 装不上（上游已知问题），改为自己 clone + 构建 + 本地注册\n");
            host.logActivity("插件 " + fallbackSpec + " 走 clone+构建 兜底安装");
            String cl = installFromGitClone(fallbackSpec);
            r = r + "\n\n[改用 clone + 构建 + 本地 link 安装…]\n" + cl;
        }
        if (r != null && r.contains("INSTALL_EXIT=0") && pre != null) {
            r = pre + "\n" + r;
        }
        if (tried.length() > 0 && r != null && !r.contains("INSTALL_EXIT=0")) {
            r = r + "\n\n=== 试过的几条路 ===\n" + tried;
            // pnpm ≥11 默认拒绝 git 依赖的 prepare 脚本 —— 上游生态已经形成共识：
            // 从 git URL 装插件这条路基本走不通了。dsh-TUI（生态里最火的插件）
            // 在自己 README 里就写着「Git URL 安装不受支持，请安装 registry 包」。
            // 用户看到一堆 pnpm 堆栈时最需要知道的就是这句话，而不是去猜自己环境坏了。
            if (isPnpmEnvFailure(r) || r.contains("prepare")) {
                r = r + "\n很可能这个插件**只支持从 npm registry 安装**：\n"
                        + "pnpm 11 起默认拒绝执行 git 依赖的 prepare 脚本，"
                        + "而多数插件的 git 仓库里不含构建产物（dist），装了也用不了。\n"
                        + "解决办法：在市场里搜它的 npm 包名（通常是 @作者/插件名），"
                        + "或到插件仓库 README 找「registry 包名」那一行。\n";
            }
            r = r + "\n如果都失败，把上面的输出贴到 DSHA 的 GitHub issue，我们跟进。";
        }
        if (r != null && r.contains("INSTALL_EXIT=0")) {
            if (!validateProfileAfterInstall()) {
                return "PROFILE_VALIDATE_FAIL: 新版 dsh profile 启动校验未通过";
            }
            // 装完当场查双副本：pnpm 可能刚把 @deepseek-ai/* 物理复制进 profile，
            // 那会让下次启动后所有工具调用失败
            String dupes = checkAndFixDshDupes();
            String dupeNote = "";
            if (dupes != null && dupes.contains("DUPES_FIXED=")
                    && !dupes.contains("DUPES_FIXED=0")) {
                dupeNote = "\n\n已顺手修掉安装过程产生的 @deepseek-ai 重复副本"
                        + "（不修的话下次启动后所有工具调用都会失败）。";
            } else if (dupes != null && dupes.contains("★关键包")) {
                dupeNote = "\n\n⚠️ 检测到 @deepseek-ai 重复副本且无法自动处理（版本不一致）——"
                        + "工具调用可能全部失败，建议卸载这个插件。";
            }
            // 装完却没进 profile = pnpm 把包放进去了、dsh 的 reconcile 不认它。
            // 不能只报告，得按原因去救（缺构建产物就构建、只是没写就补注册、
            // 压根不是 bundle 就说清楚）。
            String rescue = isInProfileManifest(pkg) ? "" : rescueUnregistered(pkg, fallbackSpec);
            return SensitiveData.redact(r + explainPeerWarnings(r) + dupeNote + rescue
                    + "\n\n[已安装到 profile，刷新页面即可生效（多数插件热加载）]" + verifyNote(pkg));
        }
        return r == null ? "无输出" : SensitiveData.redact(r);
    }

    /** Commit gate for installs: validate only the profile metadata, without touching sessions. */
    private boolean validateProfileAfterInstall() {
        try {
            String out = proot.execAndRead(
                    "cd /root/.dsh/profiles/web 2>/dev/null || exit 1; "
                            + "test -s package.json && node -e \"JSON.parse(require('fs').readFileSync('package.json','utf8'))\"; "
                            + "test -f cordis.patch.yml 2>/dev/null || true; echo PROFILE_OK",
                    45_000);
            return out != null && out.contains("PROFILE_OK");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 解析 GitHub 仓库链接为插件信息（不安装，供市场列表展示）：
     *  返回 String[3] {npm名, owner/repo, 仓库URL}；无法解析返回 null。
     *  链接格式：https://github.com/owner/repo、owner/repo、带 .git 后缀等。 */
    public String[] parseGithubUrl(String url) {
        // 与 installFromGithubUrl 共用同一份解析（GitHubRef）——两处各写一套截断逻辑
        // 正是本项目反复栽的模式。这里保持历史返回形状 {null, "owner/repo", 仓库首页}，
        // 子目录的处理在 installFromGithubUrl 里。
        GitHubRef ref = GitHubRef.parse(url);
        if (ref == null) return null;
        return new String[]{null, ref.slug(), "https://github.com/" + ref.slug()};
    }

    /** 从 GitHub 仓库链接安装插件（插件市场顶部入口）：
     *  解析 URL → owner/repo → 拉 package.json 拿 npm 名 → 安装（npm 名找不到回退 github: 方式）。
     *  返回执行输出。链接格式支持：https://github.com/owner/repo、owner/repo、带 .git 后缀等。 */
    public String installFromGithubUrl(String url) {
        try {
            GitHubRef ref = GitHubRef.parse(url);
            if (ref == null) {
                return "无法解析仓库链接：" + url + "\n格式应为 https://github.com/owner/repo";
            }
            // ===== monorepo 子目录：链接指向仓库里的某个包，绝不能按整仓库装 =====
            // 原来这里把 /tree/main/plugins/x 直接截掉，装的是仓库根 —— 根那个
            // package.json 通常是 monorepo 的管理包，不是插件，于是「命令报成功、
            // 插件管理页空无一物」。
            if (ref.hasSubdir()) {
                final String br1 = ref.branch.isEmpty() ? "main" : ref.branch;
                String pkgJson = null;
                for (String br : new String[]{br1, "master"}) {
                    String t = httpGetText(HarnessController.gitHubProxy(
                            ref.rawPrefix(br) + "/package.json"), 8000, 20000);
                    if (t != null && t.contains("\"name\"")) { pkgJson = t; break; }
                }
                if (pkgJson == null) {
                    return "这个链接指向仓库子目录 " + ref.subdir + "，但那里没有 package.json"
                            + " —— 不像插件目录。\n请把链接换成插件目录本身（里面应该有 package.json）。";
                }
                String name = "", main = "", buildScript = "";
                try {
                    org.json.JSONObject o = new org.json.JSONObject(pkgJson);
                    name = o.optString("name", "").trim();
                    main = o.optString("main", "").trim();
                    org.json.JSONObject sc = o.optJSONObject("scripts");
                    if (sc != null) buildScript = sc.optString("build", "").trim();
                } catch (Throwable ignored) {
                }
                if (name.isEmpty()) {
                    return "子目录 " + ref.subdir + " 的 package.json 里没有 name，装不了。";
                }
                // npm 上有发布版是最稳的一条路（发布版带构建产物）
                if (npmRegistryHas(name, null)) {
                    return installPlugin(name) + verifyNote(name);
                }
                // 第二优先：作者上传到 Release 的预构建包（秒级，不用在手机上构建）
                String relTgz = findReleaseTarball(ref);
                if (relTgz != null) {
                    host.logActivity("插件 " + name + " 用 Release 预构建包安装");
                    String out = runPluginInstall(relTgz);
                    if (out != null && out.contains("INSTALL_EXIT=0")) {
                        if (!isInProfileManifest(name)) registerImportedPlugin(name);
                        return "用作者发布的预构建包安装 " + name + "：\n" + relTgz + "\n\n"
                                + out + verifyNote(name);
                    }
                    host.logActivity("Release 预构建包装不上，退回源码构建");
                }
                // 没发布 npm：入口文件必须真的在仓库里，否则源码装进去也是空壳
                if (!main.isEmpty()) {
                    String probe = null;
                    for (String br : new String[]{br1, "master"}) {
                        probe = httpGetText(HarnessController.gitHubProxy(
                                ref.rawPrefix(br) + "/" + main), 8000, 20000);
                        if (probe != null) break;
                    }
                    if (probe == null) {
                        // 入口不在仓库里 = 得先构建。容器里就是完整的 Node 24 + pnpm 工具链，
                        // 所以不劝退，直接 clone → 装依赖 → 构建 —— 这条路只有 DSHA 走得通。
                        return installSubdirFromSource(ref, name, main, buildScript);
                    }
                }
                // 入口确实在仓库里：也走同一条路，用本地路径装那个子目录
                // （作者 README 写的就是 dsh plugin --profile web add ./plugins/xxx）
                return installSubdirFromSource(ref, name, main, buildScript);
            }
            // ===== 普通整仓库 =====
            String npmName = fetchNpmName(ref.owner, ref.repo);
            if (npmName == null) {
                return "未在该仓库找到 package.json / npm 包名，可能未发布 npm。\n"
                        + "仓库：" + ref.slug() + "\n"
                        + "只能源码安装：dsh plugin --profile web add github:" + ref.slug();
            }
            return installPlugin(npmName, "github:" + ref.slug()) + verifyNote(npmName);
        } catch (Throwable e) {
            return "安装失败: " + SensitiveData.redact(e.getMessage());
        }
    }

    /**
     * monorepo 子目录插件：在容器里 <b>clone → 装依赖 → 构建 → 按本地路径安装</b>。
     *
     * <p>为什么值得做而不是劝退：这类插件（一个仓库放多个包、源码是 TypeScript、构建产物
     * 不进仓库、也没发 npm）在 dsh 生态里很常见，作者 README 写的安装方式就是
     * {@code pnpm build} 之后 {@code dsh plugin --profile web add ./plugins/xxx}。
     * 而 DSHA 的 rootfs 里就是完整的 Node 24 + pnpm —— Termux 派和纯 App 壳做不到这一步，
     * 我们能。这正是「完整 glibc 环境」该兑现的地方。
     *
     * <p>耗时以分钟计（clone + 装 monorepo 依赖 + tsc），所以每一步都把失败原因带回去，
     * 不让它塌成一句笼统的「安装失败」。源码落在 {@code /root/plugin-src/<repo>}，
     * 与 {@code link:} 类插件的源码同一处 —— 备份时会被内联进包里。
     */
    private String installSubdirFromSource(GitHubRef ref, String name, String main, String buildScript) {
        final String branch = ref.branch.isEmpty() ? "main" : ref.branch;
        final String repoDir = "/root/plugin-src/" + ref.repo;
        // subdir 为空 = 整个仓库就是插件（普通单包仓库），此时构建与安装都指向仓库根
        final String sub = ref.subdir.isEmpty() ? repoDir : repoDir + "/" + ref.subdir;
        final String where = ref.subdir.isEmpty() ? "" : " 子目录 " + ref.subdir;
        final String repoUrl = "https://github.com/" + ref.slug() + ".git";

        // 1) 浅克隆（先走加速代理，再直连兜底）
        String r = proot.execAndRead("mkdir -p /root/plugin-src && rm -rf " + ShellQuote.arg(repoDir)
                + " && ( git clone --depth 1 -b " + ShellQuote.arg(branch) + " "
                + ShellQuote.arg(HarnessController.gitHubProxy(repoUrl)) + " " + ShellQuote.arg(repoDir)
                + " || git clone --depth 1 -b " + ShellQuote.arg(branch) + " "
                + ShellQuote.arg(repoUrl) + " " + ShellQuote.arg(repoDir)
                + " ) 2>&1; echo STEP_EXIT=$?", 420_000);
        if (r == null || !r.contains("STEP_EXIT=0")) {
            return "克隆 " + ref.slug() + "（分支 " + branch + "）失败：\n" + lastLines(r, 12);
        }
        java.io.File subHost = new java.io.File(proot.getRootfsDir(),
                ref.subdir.isEmpty() ? "root/plugin-src/" + ref.repo
                        : "root/plugin-src/" + ref.repo + "/" + ref.subdir);
        if (!subHost.isDirectory()) {
            return "克隆成功，但 " + branch + " 分支上没有 " + ref.subdir + " 这个目录。";
        }
        boolean mainReady = !main.isEmpty() && new java.io.File(subHost, main).isFile();

        if (!mainReady) {
            // 2) 装依赖。pnpm workspace 必须在**仓库根**装 —— 子目录单独装会缺 workspace
            // 内部依赖，tsc 一跑就是一屏 Cannot find module @deepseek-ai/…
            r = proot.execAndRead("cd " + ShellQuote.arg(repoDir)
                    + " && pnpm install --prefer-offline 2>&1; echo STEP_EXIT=$?", 900_000);
            if (r == null || !r.contains("STEP_EXIT=0")) {
                return "装依赖失败（在 " + ref.repo + " 仓库根跑 pnpm install）：\n" + lastLines(r, 20)
                        + "\n\n这一步要联网拉 monorepo 的全部依赖，网络不稳时容易断，可以重试。";
            }
            // 3) 构建。先按 workspace 包名过滤（作者 README 的写法），失败再退到子目录里直接 build
            r = proot.execAndRead("cd " + ShellQuote.arg(repoDir)
                    + " && ( pnpm --filter " + ShellQuote.arg(name) + " build 2>&1"
                    + " || ( cd " + ShellQuote.arg(sub) + " && pnpm build 2>&1 ) )"
                    + "; echo STEP_EXIT=$?", 900_000);
            boolean built = r != null && r.contains("STEP_EXIT=0");
            mainReady = main.isEmpty() || new java.io.File(subHost, main).isFile();
            if (!built && !mainReady) {
                return "构建失败" + (buildScript.isEmpty() ? "" : "（作者的构建命令：" + buildScript + "）")
                        + "：\n" + lastLines(r, 20)
                        + "\n\n源码已经留在容器里的 " + sub + "，可以进内置终端手动接着试。";
            }
            if (!mainReady) {
                return "构建命令跑完了，但入口文件 " + main + " 还是没生成。\n"
                        + "源码在 " + sub + "，进内置终端能看 pnpm build 的完整输出。";
            }
        }

        // 4) 按本地路径安装：file: 让 pnpm 直接链这个目录，等价于作者说的
        //    dsh plugin --profile web add ./plugins/xxx
        String out = installPlugin(name, "file:" + sub);
        // 自动注册：pnpm 只负责把包放进 node_modules，写 dependencies + dsh.profile.bundles
        // 才叫「注册」。不写的话插件页看不到、dsh 也不会加载它 ——
        // 这就是「装成功却什么都没发生」的最后一环。
        String reg = "";
        if (!isInProfileManifest(name)) {
            registerImportedPlugin(name, "link:" + sub);
            reg = isInProfileManifest(name)
                    ? "\n[已自动注册进 web profile（link: 指向上面那份源码）]"
                    : "\n⚠ 自动注册没成功 —— web profile 可能还没生成，先启动一次 WebUI 再装。";
        }
        return "已从源码构建并安装 " + name + "\n仓库 " + ref.slug() + where
                + "\n源码保留在 " + sub + "（备份时会随包内联）\n\n" + out + reg + verifyNote(name);
    }

    /** 取输出的最后 n 行 —— 命令失败时真正有用的信息都在尾部。 */
    private static String lastLines(String s, int n) {
        if (s == null || s.isEmpty()) return "（没有输出）";
        String[] lines = s.trim().split("\n");
        int from = Math.max(0, lines.length - n);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return SensitiveData.redact(sb.toString());
    }

    /**
     * <b>真正的</b>「已注册」：web profile 的 {@code dependencies} 与
     * {@code dsh.profile.bundles} 里都有它。
     *
     * <p>两者缺一都不算：dsh 的 reconcile 会把「bundles 里列了、dependencies 解析不到」
     * 的条目剪掉，而只在 dependencies 里、没进 bundles 的包 dsh 压根不加载。
     * {@link #isRegisteredNow} 那个判据（在 bundles 里 <b>或</b> 声明了 dsh 字段）
     * 回答的是「算不算已装」，比这里宽 —— 别混用。
     */
    private boolean isInProfileManifest(String name) {
        try {
            java.io.File pf = new java.io.File(proot.getRootfsDir(),
                    "root/.dsh/profiles/web/package.json");
            if (!pf.isFile() || name == null || name.isEmpty()) return false;
            org.json.JSONObject root = new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(pf.toPath()), StandardCharsets.UTF_8));
            org.json.JSONObject deps = root.optJSONObject("dependencies");
            if (deps == null || !deps.has(name)) return false;
            org.json.JSONObject dsh = root.optJSONObject("dsh");
            org.json.JSONObject prof = dsh == null ? null : dsh.optJSONObject("profile");
            org.json.JSONArray b = prof == null ? null : prof.optJSONArray("bundles");
            if (b == null) return false;
            for (int i = 0; i < b.length(); i++) {
                if (name.equals(b.optString(i, ""))) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * package.json 里有没有 {@code dsh.bundle} 声明。
     *
     * <p><b>只有声明了 bundle 的包才该进 {@code dsh.profile.bundles}</b>。官方文档写得很明确：
     * 没有 {@code dsh.bundle} 的包仍然可以安装，但只作为普通依赖、不激活任何层 ——
     * 那是「给插件 import 的库包」这种格式。把库包写进 bundles，dsh 只会打警告，
     * 等于往 profile 里塞垃圾。
     *
     * <p>所以自动注册不能只看「有没有 dsh 字段」（{@link #hasDshField}）：
     * 一个只声明 {@code dsh.client} 的包也有 dsh 字段，但它不是 bundle。
     */
    private boolean hasDshBundle(java.io.File pkgDir) {
        try {
            java.io.File pj = new java.io.File(pkgDir, "package.json");
            if (!pj.isFile() || pj.length() > 4L * 1024 * 1024) return false;
            org.json.JSONObject o = new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(pj.toPath()), StandardCharsets.UTF_8));
            org.json.JSONObject dsh = o.optJSONObject("dsh");
            return dsh != null && dsh.optJSONObject("bundle") != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 自动注册「本地已经存在、但没注册进 profile」的插件。
     *
     * <p><b>为什么需要它</b>：DSHA 里可以自己做插件 —— 进内置终端、或者让 agent 在容器里
     * 直接写一个插件目录（{@code node_modules/<name>/package.json} + 入口文件），
     * 在完整 Linux 环境里这是最自然的做法，也是 DSHA 相对纯 App 壳的意义所在。
     *
     * <p>但<b>放进目录不等于注册</b>：dsh 只加载 {@code dsh.profile.bundles} 里列出、
     * 且 {@code dependencies} 解析得到的包。手写的插件会出现在插件列表里
     * （{@link #scanDshDeclaredPlugins} 认 package.json 的 dsh 字段），却完全不生效 ——
     * 看起来就是「插件坏了」，而且没有任何地方会说出真正的原因。
     *
     * <p>用户手动禁用过的（{@code <name>.disabled}）一律不动 —— 那是用户的决定，
     * 自动注册不该把它复活。
     *
     * @return 这一轮补注册了哪些插件名
     */
    /**
     * 懒存档点：第一次真要改 profile 时才打。
     *
     * <p><b>为什么要懒</b>：{@link #autoRegisterLocalPlugins} 每次启动都跑，而绝大多数启动
     * 它什么都不用改（幂等空转）。若进门就打一份，{@code KEEP} 份很快被空存档点占满，
     * 把真正有用的那份挤掉 —— 保险机制自己先失效了。
     *
     * <p>同一个实例<b>可以跨多个操作共用</b>：启动路径上 {@link #healSelfRefDeps} 与
     * {@code autoRegisterLocalPlugins} 是连着跑的，共用一份才是「两个都没动之前」的干净状态；
     * 各打一份的话，第二份拍到的是「已经改了一半」的现场 —— 那就不是保险，是事故照片。
     */
    final class LazySavepoint {
        private final String what;
        private boolean created;
        private String id;

        LazySavepoint(String what) {
            this.what = what;
        }

        /** 真要动 profile 了 —— 确保存档点存在（幂等，只打一次）。 */
        void ensure() {
            if (created) return;
            created = true;                 // 先置位：create 失败也不重试，免得每个插件试一次
            id = PluginSavepoint.create(proot, host, what);
        }

        /** 打过存档点没有（给调用方决定要不要在结果里提「可撤销」）。 */
        boolean taken() {
            return id != null;
        }
    }

    /**
     * 启动时的 profile 维护：先自愈自指依赖，再自动注册本地插件。
     *
     * <p>两件事共用<b>同一个</b>存档点 —— 它们连着跑、都在动 profile，
     * 拆成两份的话第二份拍到的是改了一半的状态。
     *
     * @return 自愈的结果文案（没有要修的返回空串）
     */
    public String startupProfileMaintenance() {
        LazySavepoint sp = new LazySavepoint("启动时维护 profile（自愈自指依赖 + 自动注册本地插件）");
        String healed = healSelfRefDeps(sp);
        autoRegisterLocalPlugins(sp);
        return healed;
    }

    public java.util.List<String> autoRegisterLocalPlugins() {
        return autoRegisterLocalPlugins(new LazySavepoint("自动注册本地插件"));
    }

    java.util.List<String> autoRegisterLocalPlugins(LazySavepoint sp) {
        java.util.List<String> done = new java.util.ArrayList<>();
        try {
            java.util.Set<String> declared = scanDshDeclaredPlugins();
            if (declared.isEmpty()) return done;
            for (String n : declared) {
                if (n == null || n.isEmpty() || n.startsWith(".")) continue;
                if (isInProfileManifest(n)) continue;      // 已经注册好了
                if (isPluginDisabled(n)) continue;         // 用户手动禁用过的不要复活
                // 只有 dsh.bundle 包才进 bundles —— 库包（只给别人 import 的）不算插件
                java.io.File entity = new java.io.File(proot.getRootfsDir(),
                        HarnessController.PLUGIN_DIRS[0].substring(1) + "/" + n);
                if (!hasDshBundle(entity)) continue;
                // 入口文件不在 = 注册了也加载不起来，只会在插件列表里造出一个幽灵条目。
                // 用户报过「显示安装失败，却注册进插件管理了」—— 就是装到一半的残留
                // 被这里当成「本地已有插件」补注册了。
                String mainRel = readPkgField(entity, "main");
                if (!mainRel.isEmpty() && !new java.io.File(entity, mainRel).isFile()) continue;
                // 要动 profile 了 —— 存档点在这里打（前面那些 continue 都是空转，不该占一份）
                sp.ensure();
                registerImportedPlugin(n);                 // 实体在 node_modules → 挪出去再 link:
                if (isInProfileManifest(n)) done.add(n);
            }
            if (!done.isEmpty()) {
                host.logActivity("自动注册本地插件 " + done.size() + " 个："
                        + String.join(", ", done) + "（刷新页面即可生效（多数插件热加载））");
            }
        } catch (Throwable t) {
            android.util.Log.w("DSHA", "自动注册本地插件失败: "
                    + SensitiveData.redact(String.valueOf(t)));
        }
        return done;
    }

    /**
     * 找作者发布的<b>预构建</b> tarball（GitHub Release 的上传资产）。
     *
     * <p>这是 dsh-market（社区插件市场，2.2k star、MIT）实测出来的安装优先级里的第二级：
     * <b>先 npm 的已验证包，再作者上传的 Release tarball，最后才退到整仓库源码</b>。
     * 预构建装起来是秒级、且不需要本地跑构建脚本，而源码那条路要拉全部依赖 + tsc，
     * 在手机上以分钟计。所以这一级必须先试。
     *
     * <p><b>只认 {@code assets} 里的资产</b>：GitHub 给每个 tag 自动生成的
     * {@code tarball_url} / {@code zipball_url} 是<b>源码</b>快照，里面同样没有构建产物 ——
     * 拿它当预构建用等于绕了一圈回到原地。作者手动上传的（多半是 {@code pnpm pack} 的产物）
     * 才是我们要的。
     *
     * @return 可直接交给 pnpm 的 tarball URL；没有就返回 null
     */
    private String findReleaseTarball(GitHubRef ref) {
        if (ref == null) return null;
        for (String api : new String[]{
                "https://api.github.com/repos/" + ref.slug() + "/releases/latest",
                "https://api.github.com/repos/" + ref.slug() + "/releases?per_page=5"}) {
            // release 列表带每个版本的发布说明全文，很容易超过默认的 512KB 上限；
            // 截断之后 JSON 解析失败 → 静默返回 null → 悄悄退回最慢的源码构建。
            // 这是「同一份教训只应用到一处」的又一例：市场索引那边刚放开，这里没跟上。
            String body = httpGetText(HarnessController.gitHubProxy(api), 8000, 20000,
                    4 * 1024 * 1024);
            if (body == null || body.isEmpty()) continue;
            try {
                org.json.JSONArray releases;
                String t = body.trim();
                if (t.startsWith("[")) {
                    releases = new org.json.JSONArray(t);
                } else {
                    releases = new org.json.JSONArray();
                    releases.put(new org.json.JSONObject(t));
                }
                for (int i = 0; i < releases.length(); i++) {
                    org.json.JSONObject rel = releases.optJSONObject(i);
                    if (rel == null) continue;
                    org.json.JSONArray assets = rel.optJSONArray("assets");
                    if (assets == null) continue;
                    for (int j = 0; j < assets.length(); j++) {
                        org.json.JSONObject a = assets.optJSONObject(j);
                        if (a == null) continue;
                        String name = a.optString("name", "").toLowerCase(java.util.Locale.US);
                        String url = a.optString("browser_download_url", "").trim();
                        if (url.isEmpty()) continue;
                        if (name.endsWith(".tgz") || name.endsWith(".tar.gz")) return url;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 读 package.json 的顶层字符串字段（读不到给空串）。 */
    private String readPkgField(java.io.File pkgDir, String field) {
        try {
            java.io.File pj = new java.io.File(pkgDir, "package.json");
            if (!pj.isFile() || pj.length() > 4L * 1024 * 1024) return "";
            org.json.JSONObject o = new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(pj.toPath()), StandardCharsets.UTF_8));
            return o.optString(field, "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 读 package.json 的 {@code scripts.build}。 */
    private String readPkgBuild(java.io.File pkgDir) {
        try {
            java.io.File pj = new java.io.File(pkgDir, "package.json");
            if (!pj.isFile()) return "";
            org.json.JSONObject o = new org.json.JSONObject(new String(
                    java.nio.file.Files.readAllBytes(pj.toPath()), StandardCharsets.UTF_8));
            org.json.JSONObject sc = o.optJSONObject("scripts");
            return sc == null ? "" : sc.optString("build", "").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * pnpm 装成功了、插件却没进 profile 时的自救。
     *
     * <p><b>为什么会出现这种状态</b>：{@code dsh plugin add} 转发给 pnpm 装完之后要做一次
     * reconcile —— 官方的判据是「<b>dependencies 解析得到的</b>、且声明了 {@code dsh.bundle}
     * 的包才加入层栈」。TypeScript 插件从 git 装进来的是源码，{@code main} 指的
     * {@code lib/index.js} 并不存在，于是这个包<b>解析不到</b>，reconcile 就不把它写进
     * bundles。pnpm 那边一切正常（{@code INSTALL_EXIT=0}、Packages: +2），dsh 也不算失败，
     * 结果就是「提示安装成功，插件却没生效」。
     *
     * <p>所以这里按原因分三种处置，而不是笼统报一句「没注册上」：
     * <ul>
     *   <li>没有 {@code dsh.bundle} → 它按 dsh 的规矩就只是个普通依赖（供别的插件 import
     *       的库），不该被加载。说清楚，别瞎补。</li>
     *   <li>有 bundle 但入口文件不存在 → 源码缺构建产物，转去容器里 clone + 构建。</li>
     *   <li>有 bundle、入口也在 → 那只是 reconcile 没写，直接补注册。</li>
     * </ul>
     */
    private String rescueUnregistered(String pkg, String fallbackSpec) {
        if (pkg == null || pkg.isEmpty()) return "";
        java.io.File entity = new java.io.File(proot.getRootfsDir(),
                HarnessController.PLUGIN_DIRS[0].substring(1) + "/" + pkg);
        if (!existsOrBrokenLink(entity)) return "";   // 连实体都没有，没什么可救
        if (!hasDshBundle(entity)) {
            return "\n\n这个包没有声明 dsh.bundle —— 按 dsh 的规矩它只是一个普通依赖"
                    + "（供别的插件 import 的库），本来就不会作为插件加载。这不是装错了。";
        }
        String mainRel = readPkgField(entity, "main");
        boolean entryMissing = !mainRel.isEmpty()
                && !new java.io.File(entity, mainRel).isFile();
        if (entryMissing) {
            GitHubRef gr = GitHubRef.parse(fallbackSpec);
            String why = "\n\n入口文件 " + mainRel + " 不存在 —— pnpm 装进来的是源码，"
                    + "构建产物没跟着走，dsh 的 reconcile 因此认为这个包解析不到、"
                    + "不把它加进 bundles。这就是「装成功了却没生效」的原因。";
            if (gr != null) {
                // 第一优先：作者上传到 Release 的预构建包。秒级，且不用在手机上跑构建 ——
                // 这是社区市场 dsh-market 实测出来的次序（npm → Release 预构建 → 源码）。
                String tgz = findReleaseTarball(gr);
                if (tgz != null) {
                    host.logActivity("插件 " + pkg + " 改用 Release 预构建包");
                    String out = runPluginInstall(tgz);
                    String reg = "";
                    if (out != null && out.contains("INSTALL_EXIT=0")
                            && !isInProfileManifest(pkg)) {
                        registerImportedPlugin(pkg);
                        reg = isInProfileManifest(pkg) ? "\n[已自动补注册]" : "";
                    }
                    if (out != null && out.contains("INSTALL_EXIT=0")) {
                        return why + "\n作者在 Release 里放了预构建包，改用它重装（这条路是秒级）：\n"
                                + tgz + "\n\n" + out + reg + verifyNote(pkg);
                    }
                    // 预构建包也没装上 → 继续退到源码构建，不在这里断掉
                    host.logActivity("Release 预构建包安装失败，退回源码构建");
                }
                host.logActivity("插件 " + pkg + " 缺构建产物，转 clone+构建");
                return why + "\n作者没在 Release 里放预构建包，改为在容器里 clone 并构建"
                        + "（要几分钟）：\n\n"
                        + installSubdirFromSource(gr, pkg, mainRel, readPkgBuild(entity));
            }
            return why + "\n来源不是 GitHub，没法自动构建。源码在容器里的 "
                    + HarnessController.PLUGIN_DIRS[0] + "/" + pkg
                    + "，可以进内置终端手动跑它的 build。";
        }
        registerImportedPlugin(pkg);
        return isInProfileManifest(pkg)
                ? "\n\n[dsh 没把它写进 bundles，已自动补注册（刷新页面即可生效（多数插件热加载））]"
                : "\n\n[尝试补注册但没成功 —— web profile 可能还没初始化]";
    }

    /**
     * 装完<b>眼见为实</b>：命令退出 0 不等于插件真的注册上了。
     *
     * <p>「提示安装成功，插件管理页空无一物」就是只看退出码的后果。没注册上就把实话
     * 追加在结果后面 —— 用户至少知道该往哪查，而不是以为装好了在等它生效。
     */
    /** profile 里这个包的实体目录（scoped 名字带斜杠，File 会自然当成子目录）。 */
    private java.io.File entityDir(String pkg) {
        return new java.io.File(proot.getRootfsDir(),
                "root/.dsh/profiles/web/node_modules/" + pkg);
    }

    /** 实体是不是完整的：package.json 能解析、main 指向的入口文件真实存在。
     *  空目录、写了一半的 JSON、指向不存在文件的 main —— 都算坏。 */
    private boolean entityLooksComplete(java.io.File dir) {
        try {
            if (!dir.isDirectory()) return false;
            java.io.File pj = new java.io.File(dir, "package.json");
            if (!pj.isFile()) return false;
            String txt = new String(java.nio.file.Files.readAllBytes(pj.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(txt);
            String main = o.optString("main", "").trim();
            if (main.isEmpty()) return true;   // 没声明 main 就用默认 index.js，交给 node 去判
            return new java.io.File(dir, main).isFile();
        } catch (Throwable t) {
            return false;      // JSON 解析炸了同样算坏
        }
    }

    /** issue #49：装之前先把残缺实体清掉。
     *
     *  <p>pnpm 看到同名实体就报 {@code Already up to date} / {@code added 0}，压根不会
     *  重新下载 —— 哪怕那个实体是上一次安装中断留下的残骸。后续所有诊断都在读这个坏包，
     *  于是得出「这个包没有声明 dsh.bundle」这种离题的结论，用户照提示反复重装也没用。
     *
     *  @return 删掉了坏实体就返回一句说明（拼进安装报告），实体正常则返回空串 */
    private String dropBrokenEntity(String pkg) {
        try {
            if (pkg == null || pkg.isEmpty() || pkg.contains(":")) return "";
            java.io.File dir = entityDir(pkg);
            if (!dir.isDirectory() || entityLooksComplete(dir)) return "";
            DataPreserve.deleteRecursively(dir);
            host.logActivity("安装前清掉 " + pkg + " 的残缺实体 —— pnpm 会把它当「已装」而跳过下载");
            return "· 发现上次安装留下的残缺实体，已删除后重新装\n";
        } catch (Throwable t) {
            return "";
        }
    }

    /** 实体的真实状态。给不出结论的时候，把事实摆出来比让用户在三个「常见原因」里猜有用。 */
    private String entityDiag(String pkg) {
        try {
            java.io.File dir = entityDir(pkg);
            if (!dir.isDirectory()) return "实体目录不存在：node_modules/" + pkg + "\n";
            java.io.File pj = new java.io.File(dir, "package.json");
            if (!pj.isFile()) return "实体目录在，里面却没有 package.json —— 装到一半断了\n";
            String txt = new String(java.nio.file.Files.readAllBytes(pj.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            org.json.JSONObject o = new org.json.JSONObject(txt);
            java.io.File entry = new java.io.File(dir, o.optString("main", "index.js"));
            return "实体现状：name=" + o.optString("name", "?")
                    + " · main=" + o.optString("main", "(默认 index.js)")
                    + " · 入口文件" + (entry.isFile() ? "在" : "不在")
                    + " · dsh 声明" + (o.opt("dsh") != null ? "有" : "没有") + "\n";
        } catch (Throwable t) {
            return "实体的 package.json 解析失败 —— 内容不完整\n";
        }
    }

    private String verifyNote(String name) {
        try {
            if (isInProfileManifest(name)) return "";
        } catch (Throwable t) {
            return "";      // 查不了就别冤枉安装结果
        }
        return "\n\n⚠ 但它没有出现在已装列表里 —— 命令报了成功，实际没注册上。\n"
                + entityDiag(name)
                + "常见原因：装到的不是插件本体（比如 monorepo 的仓库根）、入口文件缺失"
                + "（源码需要先编译）、包名与实际注册名不一致，或者上次安装中断留下了残缺实体。";
    }

    /** 这个名字现在是否真的算「已装」（在 bundles 里，或包里声明了 dsh 字段）。 */
    private boolean isRegisteredNow(String name) {
        if (name == null || name.isEmpty()) return false;
        java.util.Set<String> all = new java.util.LinkedHashSet<>();
        try {
            all.addAll(readBundles());
        } catch (Throwable ignored) {
        }
        try {
            all.addAll(scanDshDeclaredPlugins());
        } catch (Throwable ignored) {
        }
        return all.contains(name);
    }

    /** 判定安装输出是否为"包在 registry 找不到"（npm 404 类） */
    private boolean isPkgNotFound(String out) {
        if (out == null) return false;
        // 这里的判据必须**只认「registry 说没有这个包」**。
        //
        // 原来的版本把 ENOTFOUND（DNS 解析失败）、ERR_PNPM_FETCH（可能只是超时）
        // 和裸 "404"（输出里任何位置出现都算）都算成「包不存在」，
        // 于是一次网络抖动就会被判成「npm 上没有」，然后自动回退到 git 源 ——
        // 而 git 源在 pnpm ≥11 下基本必死（默认拒绝执行 git 依赖的 prepare 脚本）。
        //
        // 用户报的 modlens 就是这个形状：@liustack/modlens 明明在 npm 上，
        // 报错里却出现了「自动回退 GitHub 仓库方式安装」。
        // 一次网络抖动，被这条判据变成了一次必然失败。
        return out.contains("not in the npm registry")
                || out.contains("ERR_PNPM_FETCH_404")
                || out.contains("is not in this registry")
                || (out.contains("404") && (out.contains("registry.np") || out.contains("Not Found -")));
    }

    /** 网络故障：值得**原地重试或换镜像**，绝不该换成 git 源。 */
    private boolean isNetworkFailure(String out) {
        if (out == null) return false;
        return out.contains("ENOTFOUND") || out.contains("ETIMEDOUT")
                || out.contains("ECONNRESET") || out.contains("ECONNREFUSED")
                || out.contains("socket hang up") || out.contains("ERR_PNPM_FETCH")
                || out.contains("ERR_SOCKET_TIMEOUT") || out.contains("request to ")
                && out.contains("failed");
    }

    /** 单次插件安装执行（源码目录优先，无则全局 dsh）；pkg 已由入口校验，这里再兜一道。 */
    /** 装/卸插件后立刻查 @deepseek-ai 双副本并顺手修掉。
     *
     *  为什么必须在这里做：pnpm 把 @deepseek-ai/dsh-tools 物理复制进 profile 之后，
     *  故障不会立刻显现 —— 要等下次启动、用户让 agent 干活时才爆，而且症状是
     *  「所有工具调用都失败」，完全指不到插件安装这一步。装完当场查当场修，
     *  能把一次灾难降级成一行提示。 */
    private String checkAndFixDshDupes() {
        try {
            String script = host.readAsset("check-dsh-dupes.py");
            if (script == null || script.isEmpty()) return null;
            java.io.File dst = new java.io.File(proot.getRootfsDir(), "root/.dsha-dupes.py");
            if (dst.getParentFile() != null) dst.getParentFile().mkdirs();
            java.nio.file.Files.write(dst.toPath(),
                    script.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String out = proot.execAndRead(
                    "python3 /root/.dsha-dupes.py --fix 2>&1 | tail -12; "
                            + "rm -f /root/.dsha-dupes.py", 90_000);
            android.util.Log.i("DSHA", "插件副本检查: "
                    + SensitiveData.redact(out == null ? "无输出" : out.trim()));
            return SensitiveData.redact(out);
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "插件副本检查失败: "
                    + SensitiveData.redact(String.valueOf(e)));
            return null;
        }
    }

    /** 安装输出里的 peer 警告要主动解释：生态里大量「插件不可用」的判定就是
     *  把这几行当成了失败（实测某索引站 23 个精选插件里 15 个被误判）。 */
    private static String explainPeerWarnings(String out) {
        if (out == null) return "";
        if (!out.contains("missing peer") && !out.contains("Peer dependencies that should be installed")) {
            return "";
        }
        return "\n\n提示：上面那些 missing peer **不是错误**。profile 的 pnpm-workspace.yaml"
                + " 里 autoInstallPeers=false，@deepseek-ai/* 由 dsh 本体提供，本来就不需要"
                + "单独解析。只要下面写着安装成功，插件就是装好了。";
    }

    private String runPluginInstall(String pkg) {
            try {
                if (!isValidPluginSpec(pkg)) return "安装失败：非法插件名/来源：" + (pkg == null ? "null" : pkg);
                String wd = host.detectWorkdir();
                String arg = ShellQuote.arg(pkg);
                return proot.execAndRead(
                        "if [ -d /root/" + wd + " ]; then cd /root/" + wd + "; " + host.depsSelfHeal() +
                        // 用 echo 而不是 printf：原来写的是 printf 'registry=…\\\\n'，
                        // 经 Java 与 shell 两层转义后 printf 收到的是字面反斜杠，
                        // 写进 .npmrc 的 registry 值末尾就带上了 \n（甚至 \\n）。
                        // npm 规范化后变成 https://registry.npmmirror.com/n —— 于是每次
                        // 真正要查 registry 的安装都是 404（报错里那个诡异的 /n/ 就是它）。
                        // 之前没暴露，是因为包已在 pnpm store 里时 reused、不查 registry。
                        "echo 'registry=https://registry.npmmirror.com' > /root/.npmrc; " +
                        // 先判断源码仓库的 CLI 入口在不在。直接 node 一个不存在的文件会吐
                        // 一整段 MODULE_NOT_FOUND 堆栈（requireStack: []），它把 dsh 自己那句
                        // 关键警告从 tail 里挤了出去 —— 用户看到一屏 Node 堆栈，却看不到
                        // 「这个包没有 dsh.bundle」这种真正的原因。
                        "( if [ -f apps/cli/lib/bin.js ]; then node apps/cli/lib/bin.js plugin --profile web add "
                                + arg + " 2>&1; else dsh plugin --profile web add " + arg + " 2>&1; fi ); " +
                        "else echo '[DSHA] 无源码目录，回退全局 dsh'; " +
                        // 用 echo 而不是 printf：原来写的是 printf 'registry=…\\\\n'，
                        // 经 Java 与 shell 两层转义后 printf 收到的是字面反斜杠，
                        // 写进 .npmrc 的 registry 值末尾就带上了 \n（甚至 \\n）。
                        // npm 规范化后变成 https://registry.npmmirror.com/n —— 于是每次
                        // 真正要查 registry 的安装都是 404（报错里那个诡异的 /n/ 就是它）。
                        // 之前没暴露，是因为包已在 pnpm store 里时 reused、不查 registry。
                        "echo 'registry=https://registry.npmmirror.com' > /root/.npmrc; " +
                        "dsh plugin --profile web add " + arg + " 2>&1; fi | tail -40; echo INSTALL_EXIT=${PIPESTATUS[0]}");
            } catch (Exception e) {
                return "安装失败: " + SensitiveData.redact(e.getMessage());
            }
        }


    private String copyToDownloads(java.io.File src, String name) {
        return copyToDownloads(src, name, PublicDirs.PLUGINS);
    }

    /** 拷到公开 Download 目录的指定子目录（子目录定义见 {@link PublicDirs}）。 */
    private String copyToDownloads(java.io.File src, String name, String sub) {
        final String base = android.os.Environment.DIRECTORY_DOWNLOADS;
        // 方案1：MediaStore（Android 10+ 免权限）
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name);
                cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/gzip");
                cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        PublicDirs.relative(base, sub));
                android.net.Uri uri = appContext.getContentResolver().insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (java.io.OutputStream os = appContext.getContentResolver().openOutputStream(uri)) {
                        try (java.io.FileInputStream fis = new java.io.FileInputStream(src)) {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = fis.read(buf)) != -1) os.write(buf, 0, n);
                        }
                    }
                    return PublicDirs.display(android.os.Environment
                            .getExternalStorageDirectory().getAbsolutePath(), base, sub) + "/" + name;
                }
            } catch (Exception ignored) {
            }
        }
        // 方案2：All files access 直写（Android 11+ 授权后）
        try {
            if (android.os.Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()) {
                java.io.File dir = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                        base), sub == null || sub.isEmpty()
                        ? PublicDirs.ROOT : PublicDirs.ROOT + "/" + sub);
                if (dir.isDirectory() || dir.mkdirs()) {
                    java.io.File dst = new java.io.File(dir, name);
                    host.copyFile(src, dst);
                    return dst.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 导出**单个**插件到 {@code Download/DSHA/插件/<名字>-<时间>.tar.gz}，一个插件一个文件。
     *
     * <p>用 {@code tar -czhf}（带 -h 解引用）：已装插件在 node_modules 里多半只是一根指向
     * {@code /root/dsha-*} 或 {@code /root/plugin-src/*} 的符号链接，不解引用导出来的就是
     * 一根链接，拿到别的设备上什么都没有。备份那边刚因为同一个原因丢过对话。
     *
     * @return 用户可见的完整路径；{@code BAD_NAME} 名字不合法；{@code NOT_FOUND} 没装这个插件；
     *         {@code null} 打包或写出失败
     */
    public String exportOnePlugin(String name) {
        if (name == null || name.trim().isEmpty()) return "BAD_NAME";
        final String n = name.trim();
        if (!PluginSpec.isPackageName(n)) return "BAD_NAME";
        final String OUT_GUEST = "/root/.dsha-plugin-one.tar.gz";
        java.io.File outHost = new java.io.File(proot.getRootfsDir(), "root/.dsha-plugin-one.tar.gz");
        try {
            for (String d : HarnessController.PLUGIN_DIRS) {
                java.io.File entity = new java.io.File(proot.getRootfsDir(),
                        d.substring(1) + "/" + n);
                if (!existsOrBrokenLink(entity)) continue;
                String r = proot.execAndRead("rm -f " + ShellQuote.arg(OUT_GUEST)
                        + "; cd " + ShellQuote.arg(d)
                        + " && tar -czhf " + ShellQuote.arg(OUT_GUEST) + " " + ShellQuote.arg(n)
                        + " 2>&1; echo TAR_EXIT=$?");
                if (r == null || !r.contains("TAR_EXIT=0") || !outHost.isFile()) continue;
                String file = PublicDirs.safeFileName(n) + "-"
                        + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                        .format(new java.util.Date()) + ".tar.gz";
                String path = copyToDownloads(outHost, file, PublicDirs.PLUGINS);
                if (path != null) {
                    host.logActivity("已导出插件 " + n + " → " + path);
                    return path;
                }
            }
            return "NOT_FOUND";
        } catch (Exception e) {
            android.util.Log.w("DSHA", "导出单个插件失败 " + n + ": "
                    + SensitiveData.redact(String.valueOf(e)));
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            outHost.delete();
        }
    }
}
