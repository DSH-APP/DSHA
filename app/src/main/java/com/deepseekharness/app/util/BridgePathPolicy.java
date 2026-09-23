package com.deepseekharness.app.util;

import java.util.Locale;

/**
 * 3090 桥的<b>敏感路径</b>判据（纯逻辑，无 Android 依赖）。
 *
 * <p><b>为什么需要它</b>：{@code /app/export} 与 {@code /app/readfile} 原先接受任意绝对路径，
 * 而容器内 root 能读 {@code /root/.dsh} 下的全部凭据。真机实测确认了一条完整链路：
 *
 * <pre>
 *   插件/agent 执行一行 curl
 *     → /app/export?path=/root/.dsh/.bridge_token
 *     → 文件落到 /sdcard/Download/DSHA/（公共目录，任何 App 可读）
 *     → 同一台手机的任意应用拿到桥 token
 *     → 完全接管 3090 桥（读屏、点按、输入、执行设备命令）
 * </pre>
 *
 * <p>受损的不只是桥 token：{@code .credentials.yaml}（含 API key 与会话密钥）、
 * {@code adbkeys/adbkey}（ADB 私钥）、全部会话历史都在同一目录下。
 *
 * <p><b>取舍</b>：桥的正当用途是「把 agent 产出的报告交给用户」
 * （{@code /app/export?path=/root/report.md}），所以策略是<b>拒绝清单</b>而不是白名单 ——
 * 白名单会让用户自建的工作目录（{@code /sdcard/我的项目}）无法导出，破坏既有用法。
 * 拒绝清单只覆盖「凭据与运行时内部状态」，这些目录本来就不该被 agent 主动导出。
 */
public final class BridgePathPolicy {

    private BridgePathPolicy() {
    }

    /**
     * 禁止经桥导出/读取的路径前缀（宿主视角与 guest 视角都包含）。
     *
     * <p>容器内 guest 的 {@code /root/...} 会被 {@code appExport} 映射回宿主私有目录，
     * 因此两个视角都要判。这些目录里是凭据、密钥和运行时内部状态，不属于「用户产物」。
     */
    private static final String[] DENIED = {
            // dsh 自己的凭据与运行时状态
            "/root/.dsh",
            "/.dsh",
            // 本机 Android 凭据
            "/root/.android",
            "/.android",
            // SSH / 云凭据
            "/root/.ssh",
            "/.ssh",
            "/root/.aws",
            "/root/.config/gcloud",
            "/root/.kube",
            // 桥自己的凭据文件（与 .dsh 重叠，显式列出以防路径被改写）
            "/root/.dsha-",
    };

    /**
     * canonical（宿主视角）判据用的拒绝前缀。
     *
     * <p><b>为什么不能直接用 {@link #DENIED}</b>：容器 rootfs 本身就在
     * {@code /data/data/com.dsh.client/files/linux/ubuntu} 下，把 {@code /data/data}
     * 放进通用拒绝表会把<b>整个 rootfs</b>都封掉 —— 连 {@code /root/report.md} 都导不出去
     * （开发时实测到的自伤）。所以宿主视角只拒绝「不在 rootfs 里的 App 私有数据」，
     * rootfs 内的凭据由 guest 视角判据负责。
     */
    private static final String[] DENIED_HOST = {
            "/data/data",
            "/data/user",
    };

    /**
     * 这个路径是否禁止经桥读取或导出。
     *
     * @param raw 调用方给的原始路径（可以是 guest 路径，如 {@code /root/.dsh/x}）
     * @return true = 拒绝
     */
    public static boolean denied(String raw) {
        if (raw == null) return true;
        // 相对路径必须拒绝：appExport 会把相对路径映射进 rootfs，
        // 于是 "../.dsh/x" 这类写法能绕过绝对路径判据（真机测试发现的漏判）。
        if (!isAbsolute(raw)) return true;
        String path = normalize(raw);
        if (path.isEmpty() || !path.startsWith("/")) return true;
        for (String prefix : DENIED) {
            if (startsWithPath(path, prefix)) return true;
        }
        return false;
    }

    /** 是否以 / 或 \ 开头（两种分隔符都算绝对，避免 Windows 风格绕过）。 */
    public static boolean isAbsolute(String raw) {
        if (raw == null) return false;
        String value = raw.trim();
        return value.startsWith("/") || value.startsWith("\\");
    }

    /**
     * 按<b>宿主 canonical 路径</b>判断是否拒绝。
     *
     * <p>用于拦住「先在容器里建软链接指向凭据」这类绕过：调用方拿到
     * {@code File.getCanonicalPath()} 后用它复核。rootfs 内部路径不受本判据影响
     * （见 {@link #DENIED_HOST} 的说明），因此 rootfs 内的凭据仍需调用方映射回
     * guest 视角再判一次。
     */
    public static boolean deniedHostPath(String canonical, String rootfsCanonical) {
        if (canonical == null || canonical.isEmpty()) return true;
        String path = normalize(canonical);
        // rootfs 内的路径由 guest 视角判据负责，这里返回 false（不因它在 /data/data 下而拒绝）
        if (rootfsCanonical != null && !rootfsCanonical.isEmpty()) {
            String root = normalize(rootfsCanonical);
            if (startsWithPath(path, root)) return false;
        }
        for (String prefix : DENIED_HOST) {
            if (startsWithPath(path, prefix)) return true;
        }
        return false;
    }

    /**
     * 把宿主 canonical 路径还原成 guest 视角（{@code /root/.dsh/x}），并判断是否拒绝。
     *
     * @param canonical      {@code File.getCanonicalPath()} 的结果
     * @param rootfsCanonical rootfs 根目录的 canonical 路径；为 null 时只做通用判据
     */
    public static boolean deniedGuestView(String canonical, String rootfsCanonical) {
        if (canonical == null || canonical.isEmpty()) return true;
        String path = normalize(canonical);
        if (deniedHostPath(path, rootfsCanonical)) return true;
        if (rootfsCanonical == null || rootfsCanonical.isEmpty()) return false;
        String root = normalize(rootfsCanonical);
        if (!startsWithPath(path, root)) return true;   // 不在 rootfs 里又不属于允许区 → 拒绝
        if (path.equals(root)) return true;
        String guest = "/" + path.substring(root.length() + 1);
        return denied(guest);
    }

    /** 拒绝时的用户可见原因。 */
    public static String reason() {
        return UiText.text("该路径属于凭据或运行时内部状态，不允许经设备桥读取/导出；请改为导出工作产物（如 /root/report.md）");
    }

    /**
     * 规范化成可比较的绝对路径：统一分隔符、解析 {@code .} 与 {@code ..}、压缩重复斜杠。
     *
     * <p>不解析符号链接 —— 这一步只做<b>字符串</b>判据，真正的边界由调用方在打开文件后
     * 用 canonical path 复核（见 {@code appExport}）。两者缺一不可：
     * 字符串判据拦住显而易见的路径，canonical 复核拦住「先创建一个指向凭据的软链接」。
     */
    public static String normalize(String raw) {
        if (raw == null) return "";
        String value = raw.replace('\\', '/').trim();
        if (value.isEmpty()) return "";
        boolean absolute = value.startsWith("/");
        java.util.ArrayDeque<String> parts = new java.util.ArrayDeque<>();
        for (String part : value.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
                // 越出根部就停在根（绝对路径的 .. 等同于 .）
                continue;
            }
            parts.addLast(part);
        }
        StringBuilder out = new StringBuilder();
        if (absolute) out.append('/');
        boolean first = true;
        for (String part : parts) {
            if (!first) out.append('/');
            out.append(part);
            first = false;
        }
        if (out.length() == 0) return absolute ? "/" : "";
        // 目录前缀均以宿主绝对路径书写，比较时统一去掉结尾斜杠
        return out.length() > 1 && out.charAt(out.length() - 1) == '/'
                ? out.substring(0, out.length() - 1) : out.toString();
    }

    /** {@code path} 是否等于 {@code prefix} 或位于其下（按路径段比较，不是字符串前缀）。 */
    public static boolean startsWithPath(String path, String prefix) {
        if (path == null || prefix == null) return false;
        String clean = prefix.length() > 1 && prefix.endsWith("/")
                ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (path.equals(clean)) return true;
        return path.startsWith(clean + "/");
    }
}
