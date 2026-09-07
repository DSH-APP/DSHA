package com.deepseekharness.app.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** WebProcSel 的判据断言：认得出 dsh 进程、绝不误杀 proot 容器启动器。 */
public class WebProcSelTest {
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
}
