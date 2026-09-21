package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** WebProcSel 的判据断言：认得出 dsh 进程、绝不误杀 proot 容器启动器。 */
public class WebProcSelTest {
    private static final String BRIDGE = "libproroot-bridge.so /data/app/pkg/lib/arm64/libproroot-linker.so "
            + "--argv0 node --preload /data/app/pkg/lib/arm64/libproroot-runtime.so "
            + "/data/data/com.dsh.client/files/linux/ubuntu/usr/local/bin/node ";

    @Test public void recognizesOnlyProrootNodeWebPayload() {
        String command = BRIDGE + "/usr/local/bin/dsh web --no-open --host 127.0.0.1 --port 3080";
        assertTrue(WebProcSel.looksLikeWeb(command));
        assertTrue(WebProcSel.looksLikeWeb(command.replace(' ', '\0') + '\0'));
        assertTrue(WebProcSel.looksLikeWeb(BRIDGE
                + "--expose-internals /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web"));
        assertFalse(WebProcSel.looksLikeWeb("libproroot.so -r /rootfs " + command));
        assertFalse(WebProcSel.looksLikeWeb("libproot.so -r /rootfs " + command));
        assertFalse(WebProcSel.looksLikeWeb(BRIDGE + "server.js --message 'dsh web'"));
        assertFalse(WebProcSel.looksLikeWeb(BRIDGE + "-e 'import(\"dsh web\")'"));
        assertFalse(WebProcSel.looksLikeWeb(BRIDGE + "/usr/local/bin/dsh plugin list"));
        assertFalse(WebProcSel.looksLikeWeb(BRIDGE.replace("--argv0 node", "--argv0 bash")
                + "/usr/local/bin/dsh web"));
        assertFalse(WebProcSel.looksLikeWeb(command.replace("--preload", "-r")));
        assertFalse(WebProcSel.looksLikeWeb(command.replace("libproroot-linker.so", "other.so")));
        assertFalse(WebProcSel.looksLikeWeb(BRIDGE + "/usr/local/bin/dsh web-not-a-command"));
        assertFalse(WebProcSel.looksLikeWeb("libproroot-bridge.so dsh web"));
        assertFalse(WebProcSel.looksLikeWeb(command.replace("/usr/local/bin/dsh web", "/usr/local/bin/dsh\u0000web injected")));
    }

    // ================= bxroot（第三运行时）=================

    private static final String BXROOT_LAUNCHER =
            "libbxroot.so -r /data/user/0/com.dsh.client/files/linux/ubuntu -0 -w /root "
            + "-b /dev:/dev --link2symlink ";

    @Test public void recognizesBxrootLauncherWebPayload() {
        // 形态 A（launcher 直启）。真实 /proc/pid/cmdline 用 NUL 分隔且
        // bash -c 的命令是**单个 argv 元素**。构造必须逐段 NUL 连接，
        // 不能"拼空格串再整体 replace"——那会把 cmd 内部空格也变成 NUL，
        // 命令文本被切碎，isBxrootWebPayload 的 contains 判定失效。
        String[] argv = {"libbxroot.so", "-r",
                "/data/user/0/com.dsh.client/files/linux/ubuntu", "-0", "-w", "/root",
                "-b", "/dev:/dev", "--link2symlink", "/bin/bash", "-c",
                "cd /root && rm -f /root/.dsha-web.identity; "
                + "echo $$ > /root/.dsha-web.pid; exec dsh web --no-open --host 127.0.0.1 --port 3080"};
        String nul = String.join("\0", argv) + "\0";
        assertTrue(WebProcSel.looksLikeWeb(nul));
        assertTrue(WebProcSel.maySignalWeb(nul));
        // exec 全路径形态
        String[] argv2 = {"libbxroot.so", "-r",
                "/data/user/0/com.dsh.client/files/linux/ubuntu", "-0", "-w", "/root",
                "--link2symlink", "/bin/bash", "-c",
                "exec /usr/local/bin/dsh web --no-open --port 3080"};
        assertTrue(WebProcSel.looksLikeWeb(String.join("\0", argv2) + "\0"));
    }

    @Test public void neverMistakesBxrootLauncherForWeb() {
        // ★ 核心安全断言（bxroot 侧 docs/DSHA-适配说明.md 点名的 trap）：
        //   "bxroot" 不含 "proot" 子串，旧的 contains("proot") 排除对它无效；
        //   而 launcher cmdline 含 guest 命令文本。以下形态都**不是** Web，
        //   杀到启动器 = 整个环境连带 App 一起没。
        assertFalse(WebProcSel.looksLikeWeb(nul(
                "libbxroot.so", "-r", "/data/user/0/com.dsh.client/files/linux/ubuntu",
                "--link2symlink", "/bin/bash", "-c", "echo 'dsh web'")));
        // 非 exec 形态的 dsh 调用（诊断类命令）
        assertFalse(WebProcSel.looksLikeWeb(nul(
                "libbxroot.so", "-r", "/data/user/0/com.dsh.client/files/linux/ubuntu",
                "--link2symlink", "/bin/bash", "-c", "dsh plugin list")));
        assertFalse(WebProcSel.looksLikeWeb(nul(
                "libbxroot.so", "-r", "/data/user/0/com.dsh.client/files/linux/ubuntu",
                "--link2symlink", "/bin/bash", "-c", "dsh --version")));
        // 没有 -r（不是 bxroot 启动器形态）
        assertFalse(WebProcSel.looksLikeWeb(nul(
                "libbxroot.so", "-w", "/root", "/bin/bash", "-c", "exec dsh web")));
        // 任何含 libbxroot 的其它形态：宁漏杀不误杀
        assertFalse(WebProcSel.looksLikeWeb("libbxroot-runtime.so -r ..."));
    }

    @Test public void bxrootStillRecognizedThroughProrootBridgeForm() {
        // 形态 B（bridge+linker，preload 指向 bxroot runtime）——
        // 现有 isProrootWebPayload 硬编码 libproroot-runtime.so，识别不到；
        // 该形态目前不在 DSHA 使用（DSHA 走形态 A），此处只锁"不误判成 proot 启动器"。
        String bForm = "libproroot-bridge.so /data/app/pkg/lib/arm64/libproroot-linker.so "
                + "--argv0 node --preload /data/app/pkg/lib/arm64/libbxroot-runtime.so "
                + "/data/data/com.dsh.client/files/linux/ubuntu/usr/local/bin/node "
                + "/usr/local/bin/dsh web --no-open --port 3080";
        // 不误杀为启动器（launchers 排除集不含它，但它也不是已知 Web 载荷 →
        // 停止走 pid 文件兜底；这条断言锁定"不因 bxroot 命名而崩溃/误判启动器"）
        assertFalse(WebProcSel.looksLikeWeb("libbxroot.so " + bForm));
    }

    /** 用 NUL 连接 argv，模拟真实 /proc/pid/cmdline。 */
    private static String nul(String... argv) { return String.join("\0", argv) + "\0"; }

    @org.junit.Test public void pidFileContainsOnlyOneSafePid() {
        org.junit.Assert.assertEquals(1234, WebProcSel.parsePid("1234\n"));
        for (String value : new String[]{"", "0", "1", "-1234", "+1234", "1234;echo bad", "1234\n5678", "9999999999", "１２３４"})
            org.junit.Assert.assertEquals(value, -1, WebProcSel.parsePid(value));
        org.junit.Assert.assertEquals(-1, WebProcSel.parsePid(null));
    }

    @Test
    public void recognizesRealDshCmdline() {
        String real = "node --expose-internals "
                + "/usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web";
        assertTrue(WebProcSel.looksLikeWeb(real));
        assertTrue(WebProcSel.looksLikeWeb("node .../bin.js web"));
    }

    @Test
    public void neverKillsContainerLauncher() {
        // proot/proroot 命令行里带着 rootfs 路径和待执行命令，可能包含 bin.js/web，
        // 但必须被排除 —— 杀到它等于把整个环境一起带走。
        assertFalse(WebProcSel.looksLikeWeb(
                "libproot.so -r /data/user/0/com.dsh.client/files/linux/ubuntu "
                        + "/usr/bin/env bash -c node .../bin.js web"));
        assertFalse(WebProcSel.looksLikeWeb("libproroot-runtime.so -r ..."));
        assertFalse(WebProcSel.looksLikeWeb("proot -w /root"));
    }

    @Test
    public void ignoresUnrelatedProcesses() {
        assertFalse(WebProcSel.looksLikeWeb(null));
        assertFalse(WebProcSel.looksLikeWeb(""));
        assertFalse(WebProcSel.looksLikeWeb("node server.js")); // 用户自己的 node 进程
        assertFalse(WebProcSel.looksLikeWeb("com.dsh.client"));
    }

    @Test
    public void portHexIsFourDigitUpperHex() {
        assertEquals("0C08", WebProcSel.portHex(3080));
        assertEquals("0C12", WebProcSel.portHex(3090));
        assertEquals("15B3", WebProcSel.portHex(5555));
    }

    @Test
    public void pidFileRelStripsLeadingSlash() {
        assertEquals("root/.dsha-web.pid", WebProcSel.pidFileRel("/root/.dsha-web.pid"));
        assertEquals("root/.dsha-watchdog.pid", WebProcSel.pidFileRel("/root/.dsha-watchdog.pid"));
    }

    @Test
    public void sentinelIsAnAbsoluteGuestPath() {
        assertTrue(WebProcSel.STOP_SENTINEL.startsWith("/root/"));
    }

    @Test public void recoveryProfileStillStopsOnlyDirectWebNode() {
        String command = "node /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js --profile dsha-recovery-0123456789abcdef --no-open --host 127.0.0.1 --port 3080";
        assertTrue(WebProcSel.maySignalWeb(command));
        assertTrue(WebProcSel.looksLikeWeb(command));
        assertFalse(WebProcSel.maySignalWeb("bash -c " + command));
        assertFalse(WebProcSel.maySignalWeb("libproot.so " + command));
        assertFalse(WebProcSel.maySignalWeb(command.replace("dsha-recovery-0123456789abcdef", "user-profile")));
        assertFalse(WebProcSel.maySignalWeb(command.replace("dsha-recovery-0123456789abcdef", "dsha-recovery-invalid")));
    }
}
