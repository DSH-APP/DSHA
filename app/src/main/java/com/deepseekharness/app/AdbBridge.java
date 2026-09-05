package com.deepseekharness.app;

import android.content.Context;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

/**
 * ADB 无线配对桥（绕过 Shizuku，通道直连设备 adbd）。
 *
 * 原理：把 assets 里的 adb-pair.py / adb-shell.py / adb-setup.sh 注入 rootfs
 * /root/.dsh/，用用户反馈并实测可行的协议栈（TLS1.3-PSK + SPAKE2(AOSP)）在
 * 容器内完成「无线调试配对 → 直连 adbd」，拿到 uid=2000(shell) 权限。
 *
 * 设备内 agent 用法：/root/dsh-bin/adb-shell "<命令>"（PATH 已含 /root/dsh-bin）
 * 或 App 内 curl 不适用（容器内即本地）。
 */
public final class AdbBridge {

    private static final String[] SCRIPTS = {"adb-pair.py", "adb-shell.py", "adb-setup.sh"};

    private AdbBridge() {
    }

    /** assets 脚本是否已注入 rootfs（校验版本标记，防止旧脚本残留不更新）。
     *  精确比较：contains 会让 "9" 误命中 "19"，将来版本号进两位数就静默失效。 */
    public static boolean injected(ProotBootstrap proot) {
        return "YES".equals(injectedState(proot));
    }

    /** 三态：YES 已注入且版本一致 / NO 确认没有或版本不符 / UNKNOWN 查不了。
     *  execAndRead 拿不到输出（proot 没起来、超时）时以前直接算「没注入」，
     *  于是会去重复注入，甚至把状态显示成「ADB 通道异常」—— 那是查询失败，
     *  不是注入失败。 */
    public static String injectedState(ProotBootstrap proot) {
        String r = proot.execAndRead(
                "test -f /root/.dsh/script-version && cat /root/.dsh/script-version || echo NO");
        if (r == null) return "UNKNOWN";
        String v = r.trim();
        if (v.isEmpty()) return "UNKNOWN";   // 命令没回话，同样不构成证据
        if (SCRIPT_VERSION.equals(v)) return "YES";
        return "NO";
    }

    /** 当前 assets 脚本版本：每次改脚本 +1，旧版 APK 的残留脚本会因版本不符被强制重注入 */
    private static final String SCRIPT_VERSION = "13";

    /** 供自检/诊断读取期望版本（包内可见，避免把常量再抄一份） */
    static String scriptVersion() {
        return SCRIPT_VERSION;
    }

    /** 幂等注入：把三个 assets 脚本 base64 写入 /root/.dsh/ 并加执行位 + 写版本标记 */
    public static String inject(Context ctx, ProotBootstrap proot) {
        StringBuilder cmds = new StringBuilder("set -e; mkdir -p /root/.dsh; ");
        for (String name : SCRIPTS) {
            String content = readAsset(ctx, name);
            if (content.isEmpty()) continue;
            String b64 = Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            cmds.append("printf '%s' '").append(b64).append("' | base64 -d > /root/.dsh/").append(name)
                    .append("; chmod +x /root/.dsh/").append(name).append("; ");
        }
        cmds.append("printf '%s' '").append(SCRIPT_VERSION).append("' > /root/.dsh/script-version; ");
        return proot.execAndRead(cmds.toString());
    }

    /** 幂等安装：Python 依赖 + ADB 密钥 + /root/dsh-bin/adb-shell 包装（仅环境未就绪时调用） */
    public static String setup(ProotBootstrap proot) {
        return proot.execAndRead("bash /root/.dsh/adb-setup.sh 2>&1");
    }

    /** 幂等准备：注入脚本 + 确保依赖/密钥（首次较慢，此后秒回）。返回执行日志 */
    public static String ensureReady(Context ctx, ProotBootstrap proot) {
        StringBuilder sb = new StringBuilder();
        if (!injected(proot)) {
            sb.append(inject(ctx, proot));
        }
        if (!wheelsPresent(proot)) {
            sb.append(injectWheels(ctx, proot));
        }
        if (keyPresent(proot) && depsOk(proot) && wrapperPresent(proot)) {
            return "SETUP_DONE"; // 环境已就绪，跳过安装
        }
        sb.append(setup(proot));
        return sb.toString();
    }

    /** /root/dsh-bin/adb-shell 包装命令是否已装（agent/引导提示依赖它） */
    public static boolean wrapperPresent(ProotBootstrap proot) {
        String r = proot.execAndRead("test -x /root/dsh-bin/adb-shell && echo YES || echo NO");
        return r != null && r.contains("YES");
    }

    /** wheels 离线包是否已就位（≥15 个 whl：13 依赖 + pip + setuptools） */
    public static boolean wheelsPresent(ProotBootstrap proot) {
        String r = proot.execAndRead("ls /root/.dsh/wheels/*.whl 2>/dev/null | wc -l");
        try {
            return r != null && Integer.parseInt(r.trim()) >= 15;
        } catch (Exception e) {
            return false;
        }
    }

    /** 注入 wheels 离线包：Java 直接把 assets 的 tar.gz 写进 rootfs（不经 shell 命令行，
     *  避免 10MB base64 超长），再 shell 解压到 /root/.dsh/wheels/。
     *  注意 aapt 会把 assets 里的 .tar.gz 解包成 .tar，所以两种后缀都试。 */
    public static String injectWheels(Context ctx, ProotBootstrap proot) {
        try {
            java.io.File dst = new java.io.File(proot.getRootfsDir(), "root/.dsh/adb-wheels.tar.gz");
            dst.getParentFile().mkdirs();
            java.io.InputStream in = null;
            try {
                in = ctx.getAssets().open("adb-wheels.tar.gz");
            } catch (java.io.IOException e1) {
                try {
                    in = ctx.getAssets().open("adb-wheels.tar");
                } catch (java.io.IOException e2) {
                    return "WHEELS_INJECT_FAIL: assets 里找不到 adb-wheels.tar.gz/.tar（APK 可能没打进去）";
                }
            }
            java.io.FileOutputStream fos = new java.io.FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            long total = 0;
            while ((n = in.read(buf)) != -1) {
                fos.write(buf, 0, n);
                total += n;
            }
            fos.close();
            in.close();
            // 关键：aapt 打包时可能把 .tar.gz 解包成裸 .tar（HANDOFF 坑F）。
            // 不能固定 tar xzf（强制 gzip，裸 tar 会报 'not in gzip format'）：
            // 先看魔数，1f 8b = gzip → xzf，否则 = 裸 tar → xf
            String r = proot.execAndRead("mkdir -p /root/.dsh/wheels && "
                    + "M=$(head -c2 /root/.dsh/adb-wheels.tar.gz | od -An -tx1 | tr -d ' \\n'); "
                    + "if [ \"$M\" = \"1f8b\" ]; then tar xzf /root/.dsh/adb-wheels.tar.gz -C /root/.dsh/wheels/; "
                    + "else tar xf /root/.dsh/adb-wheels.tar.gz -C /root/.dsh/wheels/; fi && "
                    + "ls /root/.dsh/wheels/*.whl | wc -l");
            return "WHEELS_INJECTED(" + total + "B): " + (r == null ? "?" : r.trim()) + " whl";
        } catch (Throwable t) {
            return "WHEELS_INJECT_FAIL: " + t;
        }
    }

    /** 密钥是否已生成 */
    public static boolean keyPresent(ProotBootstrap proot) {
        String r = proot.execAndRead("test -f /root/.dsh/adbkeys/adbkey && echo YES || echo NO");
        return r != null && r.contains("YES");
    }

    /** 依赖是否已装（adb_shell_wifi + spake2-cffi；注意新版库从 spake2.spake2 导入，模块名无下划线） */
    public static boolean depsOk(ProotBootstrap proot) {
        String r = proot.execAndRead("python3 -c 'import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob' 2>/dev/null && echo YES || echo NO");
        return r != null && r.contains("YES");
    }

    /** 单次配对。pairPort 为空时脚本内尝试 mdns 发现；connectPort 默认 5555；
     *  host 为 App mDNS 解析出的真实 IP（部分 ROM 配对服务只监听 WiFi 接口）。 */
    public static String pair(ProotBootstrap proot, String code, String pairPort, String connectPort, String host) {
        String c = "python3 /root/.dsh/adb-pair.py --code '" + esc(code) + "'";
        if (host != null && !host.trim().isEmpty()) {
            c += " --host " + host.trim();
        }
        if (pairPort != null && !pairPort.trim().isEmpty()) {
            c += " --port " + pairPort.trim();
        }
        if (connectPort != null && !connectPort.trim().isEmpty()) {
            c += " --connect-port " + connectPort.trim();
        }
        String out = proot.execAndRead(c);
        // 配对成功 → 通过刚建立的 adb 通道（uid=2000 shell）给 App 授予
        // WRITE_SECURE_SETTINGS（thedjchi/Shizuku 开机自启机制的前提）：
        // 之后开机广播可直接自动开启无线调试，无需 Shizuku/手动操作
        if (out != null && out.contains("PAIR_OK")) {
            grantSecureSettings(proot);
        }
        return out;
    }

    /** 通过 adb shell（uid=2000）给本 App 授予 WRITE_SECURE_SETTINGS 权限。
     *  shell 身份可授予该 signature 权限；授予后 BootReceiver 可在开机时自动
     *  开启无线调试（Settings.Global.adb_wifi_enabled=1），实现"永不掉"。 */
    public static void grantSecureSettings(ProotBootstrap proot) {
        try {
            // 用 BuildConfig 而不是写死字符串：applicationId 一改，写死的那个会静默失效，
            // 表现成「开机自动开无线调试」莫名不工作，却查不到原因
            String pkg = BuildConfig.APPLICATION_ID;
            // pm grant 是幂等的，重复执行无害，所以不必先查一次
            String r = proot.execAndRead("DSH_INTERNAL=" + HttpShellService.mintInternalTicket()
                    + " python3 /root/.dsh/adb-shell.py pm grant " + pkg
                    + " android.permission.WRITE_SECURE_SETTINGS 2>&1 | head -2");
            android.util.Log.i("DSHA-ADB", "WRITE_SECURE_SETTINGS 授权结果: " + r);
        } catch (Throwable t) {
            // 别静默：这一步失败会让「开机自动开无线调试」整条链失效，
            // 而现象出现在很久之后，没有这条日志就无从追溯
            android.util.Log.w("DSHA-ADB", "WRITE_SECURE_SETTINGS 授权失败: " + t);
        }
    }

    /** 状态快照：key/deps/connect_port（供 UI 展示） */
    public static String status(ProotBootstrap proot) {
        String cmd = "K=$(test -f /root/.dsh/adbkeys/adbkey && echo YES || echo NO); "
                // 注意：新版库模块名是 spake2.spake2（无下划线），旧名 spake2_cffi 已不存在，
                // 用旧名会导致 import 永远失败 → UI 永远显示依赖缺失
                + "D=$(python3 -c 'import adb_shell_wifi; from spake2.spake2 import Spake2_Alice, Spake2_Bob' 2>/dev/null && echo YES || echo NO); "
                + "P=$(test -f /root/.dsh/adbkeys/connect_port && cat /root/.dsh/adbkeys/connect_port || echo -); "
                + "echo 'key='$K' deps='$D' port='$P";
        return proot.execAndRead(cmd);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("'", "'\\''");
    }

    private static String readAsset(Context ctx, String name) {
        try {
            java.io.InputStream in = ctx.getAssets().open(name);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            return bos.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }
}
