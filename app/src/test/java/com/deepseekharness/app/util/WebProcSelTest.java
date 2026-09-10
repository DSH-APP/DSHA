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
