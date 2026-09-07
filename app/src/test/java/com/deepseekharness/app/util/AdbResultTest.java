package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdbResultTest {
    @Test public void pairingIsNotAConnection() {
        assertEquals(AdbResult.PairState.PAIRED, AdbResult.pairState("PAIR_OK: 授权完成\nCONNECT_WARN: 超时"));
        assertEquals(AdbResult.PairState.CONNECTED, AdbResult.pairState("PAIR_OK: 完成\nCONNECT_OK: 已验证"));
        assertEquals(AdbResult.PairState.FAILED, AdbResult.pairState("错误说明里包含 PAIR_OK，不算成功"));
        assertEquals(AdbResult.PairState.FAILED, AdbResult.pairState(null));
        assertEquals(AdbResult.PairState.FAILED, AdbResult.pairState("CONNECT_OKAY"));
    }
    @Test public void successfulShellRequiresIdentityAndFinalZeroExit() {
        assertTrue(AdbResult.shellReady("uid=2000(shell) gid=2000(shell)\n[EXIT=0]\n"));
        assertFalse(AdbResult.shellReady("uid=2000(shell)\n[EXIT=1]"));
        assertFalse(AdbResult.shellReady("uid=2000(shell)\n[EXIT=0]\nEXECUTION_UNKNOWN"));
        assertFalse(AdbResult.shellReady("ERROR: uid=2000\n[EXIT=0]"));
        assertFalse(AdbResult.shellReady("[EXIT=0]"));
    }
    @Test public void validatesPortsAndExactSixDigitCode() {
        assertEquals(0, AdbResult.port(" "));
        assertEquals(1, AdbResult.port("1"));
        assertEquals(65535, AdbResult.port("65535"));
        for (String input : new String[]{"0", "65536", "-1", "1.2", "1;id", "9999999999"}) {
            try { AdbResult.port(input); fail(input); } catch (IllegalArgumentException expected) { }
        }
        assertTrue(AdbResult.code("000123"));
        assertFalse(AdbResult.code("12345"));
        assertFalse(AdbResult.code("1234567"));
        assertFalse(AdbResult.code("１２３４５６"));
    }
}
