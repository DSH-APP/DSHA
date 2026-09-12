package com.deepseekharness.app.util;

/**
 * 备份范围的<b>唯一</b>定义：全量 / 只聊天记录 / 只设置 / 只插件。
 *
 * <p>一处定义，三处派生 —— 备份时 tar 打哪些路径、恢复时合并哪些子树、文件名叫什么，
 * 全都从这里出。这三件事必须一致：备份打了 A 而恢复只合并 B，用户就会拿到一个
 * 「恢复成功但东西没回来」的包，而且看不出哪一步错了。
 *
 * <p><b>文件名前缀是向后兼容的关键</b>：老版本按 {@code DSHA-backup-} 前缀扫描备份，
 * 并且恢复时会把整个 {@code .dsh} 挪走再替换。要是把部分备份也叫 {@code DSHA-backup-*}，
 * 老版本（以及自动恢复提示）会把「只含对话的包」当全量恢复 —— 配置与插件全部丢失。
 *
 * <p>这个类刻意不碰 Android API，纯字符串与数组处理，好让 JVM 单元测试直接下断言。
 */
public final class BackupScope {

    /** 全量：配置 + 对话 + 插件 + 工作区 .env + 日志。与历史行为一致。 */
    public static final int FULL = 0;
    /** 只对话：{@code .dsh/sessions}。换设备只想把聊天记录带走时用。 */
    public static final int SESSIONS = 1;
    /** 只插件：profile 声明 + 内联的本机插件源码。 */
    public static final int PLUGINS = 2;
    /** 只设置：upstream 的 settings.yaml，保持原字节。 */
    public static final int SETTINGS = 3;

    /** UI 与对话框里展示的顺序（也是选项顺序）。 */
    public static final int[] ALL = { FULL, SESSIONS, SETTINGS, PLUGINS };

    private BackupScope() {
    }

    /** 写进备份清单（manifest）的标识。恢复端靠它决定合并范围，所以不能改字面量。 */
    public static String id(int scope) {
        switch (scope) {
            case SESSIONS: return "sessions";
            case PLUGINS:  return "plugins";
            case SETTINGS: return "settings";
            default:       return "full";
        }
    }

    /** 从清单里的标识反解。认不出来一律当全量 —— 老备份没有这个字段，而它们就是全量。 */
    public static int fromId(String id) {
        if (id == null) return FULL;
        String s = id.trim();
        if (s.equals("sessions")) return SESSIONS;
        if (s.equals("plugins")) return PLUGINS;
        if (s.equals("settings")) return SETTINGS;
        return FULL;
    }

    /** 文件名前缀。部分备份刻意不叫 DSHA-backup-（见类注释）。 */
    public static String fileNamePrefix(int scope) {
        switch (scope) {
            case SESSIONS: return "DSHA-sessions-";
            case PLUGINS:  return "DSHA-plugins-";
            case SETTINGS: return "DSHA-settings-";
            default:       return "DSHA-backup-";
        }
    }

    /** 统一生成归档名，防止调用方绕过范围前缀约定。 */
    public static String archiveName(int scope, String suffix) {
        if (suffix == null || !suffix.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")) {
            throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("备份文件标识无效"));
        }
        return fileNamePrefix(scope) + suffix + ".tar.gz";
    }

    /** 按文件名判断范围（用户手动选包恢复时先看名字，清单里的 scope 优先级更高）。 */
    public static int fromFileName(String name) {
        if (name == null) return FULL;
        String n = name.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < n.length()) n = n.substring(slash + 1);
        if (n.startsWith("DSHA-sessions-")) return SESSIONS;
        if (n.startsWith("DSHA-plugins-")) return PLUGINS;
        if (n.startsWith("DSHA-settings-")) return SETTINGS;
        return FULL;
    }

    /** 这个范围的包会不会被老版本当成全量备份（= 文件名带 DSHA-backup- 前缀）。 */
    public static boolean visibleToLegacyScan(int scope) {
        return fileNamePrefix(scope).equals("DSHA-backup-");
    }

    /** 给用户看的名字。 */
    public static String label(int scope) {
        switch (scope) {
            case SESSIONS: return com.deepseekharness.app.util.UiText.text("仅备份对话记录");
            case PLUGINS:  return com.deepseekharness.app.util.UiText.text("仅备份插件");
            case SETTINGS: return com.deepseekharness.app.util.UiText.text("仅备份设置");
            default:       return com.deepseekharness.app.util.UiText.text("备份全部数据");
        }
    }

    /** 给用户看的一句话说明。 */
    public static String describe(int scope) {
        switch (scope) {
            case SESSIONS:
                return com.deepseekharness.app.util.UiText.text("只打包会话、索引、附件与工作区结构，恢复时不动设置与插件");
            case PLUGINS:
                return com.deepseekharness.app.util.UiText.text("只打包插件清单、配置和已安装插件数据");
            case SETTINGS:
                return com.deepseekharness.app.util.UiText.text("打包 settings.yaml 和运行参数；原生 API Key 遵循备份密钥开关");
            default:
                return com.deepseekharness.app.util.UiText.text("配置、对话、附件、插件与工作区 .env/日志，换机或重装用这个");
        }
    }

    public static String restoreImpact(int scope) {
        switch (scope) {
            case SESSIONS: return com.deepseekharness.app.util.UiText.text("覆盖聊天记录、会话索引与附件，不改设置和插件");
            case SETTINGS: return com.deepseekharness.app.util.UiText.text("覆盖 settings.yaml 和原生运行设置，不改聊天记录和插件");
            case PLUGINS: return com.deepseekharness.app.util.UiText.text("覆盖插件 profile 和插件源码，不改聊天记录和设置");
            default: return com.deepseekharness.app.util.UiText.text("覆盖配置、聊天记录、附件、插件与工作区 .env/日志");
        }
    }

    /** {@code .dsh} 下要打包的子路径；空数组表示<b>整个 {@code .dsh}</b>。 */
    public static String[] dshPaths(int scope) {
        switch (scope) {
            // dsh 1.2：会话文件在 sessions/，但 UI 入口是 storages/workspace.json 注册表
            // （workspace -> sessionIds）。只带 sessions/ 的话恢复后会话文件在、注册表没引用，
            // WebUI 里看不到 —— 所以两个都带（session_projcache 是投影缓存非权威，可重建）。
            case SESSIONS: return new String[] { ".dsh/sessions", ".dsh/storages", ".dsh/attachments" };
            case PLUGINS:  return new String[] { ".dsh/profiles", ".dsh/plugin-src", ".dsh/plugin-sources.json", ".dsh/plugin-history", ".dsh/plugin-safe-mode.json" };
            case SETTINGS: return new String[] { ".dsh/settings.yaml" };
            default:       return new String[0];   // 空 = 整个 .dsh
        }
    }

    /** 恢复时要合并的 {@code .dsh} 子目录名；必须与 {@link #dshPaths(int)} 一一对应。 */
    public static String[] mergeSubdirs(int scope) {
        switch (scope) {
            case SESSIONS: return new String[] { "sessions", "storages", "attachments" };
            case PLUGINS:  return new String[] { "profiles", "plugin-src", "plugin-sources.json", "plugin-history", "plugin-safe-mode.json" };
            case SETTINGS: return new String[] { "settings.yaml" };
            default:       return new String[0];
        }
    }

    /** 这个范围要不要带工作区的 {@code .env} 与 {@code dsh-web.log}（只有全量要）。 */
    public static boolean includesWorkdirFiles(int scope) {
        return scope == FULL;
    }

    /** 这个范围要不要内联本机路径插件的源码（全量与插件备份都要）。 */
    public static boolean includesPluginSrc(int scope) {
        return scope == FULL || scope == PLUGINS;
    }

    /** 这个范围要不要把公开目录里的热数据解引用快照进包。 */
    public static boolean needsPublicDataSnapshot(int scope) {
        return scope == FULL || scope == SESSIONS || scope == SETTINGS;
    }

    /** 公开目录里会被软链出去的热数据条目（与 BackupManager 的软链自修复同一份名单）。 */
    public static final String[] PUBLIC_HOT_ENTRIES = {
            "sessions", "storages", "attachments", "settings.yaml",
    };

    /** 包内承载公开数据快照的目录名（相对 {@code /root}）。 */
    public static final String PUB_SNAPSHOT_DIR = ".dsha-pub";

    /** 这个范围需要快照哪些公开条目。 */
    public static String[] snapshotEntries(int scope) {
        if (scope == SESSIONS) return new String[] { "sessions", "storages", "attachments" };
        if (scope == SETTINGS) return new String[] { "settings.yaml" };
        if (scope == FULL) return PUBLIC_HOT_ENTRIES.clone();
        return new String[0];
    }
}
