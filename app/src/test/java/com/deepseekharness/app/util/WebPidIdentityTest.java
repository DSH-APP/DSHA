package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;

public class WebPidIdentityTest {
    private String stat(char state, long started) {
        return "1234 (node (web)) " + state + " 10 11 12 " + "0 ".repeat(15) + started + " 0 0";
    }
    @Test public void kernelBirthTimeDistinguishesReusedPid() {
        WebPidIdentity first = WebPidIdentity.parse(stat('S', 5678), 1234);
        assertNotNull(first); assertEquals(5678, first.started); assertTrue(first.matches("1234 5678\n"));
        assertFalse(first.matches("1234 5679"));
        assertFalse(first.sameProcess(WebPidIdentity.parse(stat('S', 5679), 1234)));
    }
    @Test public void invalidOrTruncatedStatDoesNotGrantIdentity() {
        assertNull(WebPidIdentity.parse(stat('S', 1), 1235));
        assertNull(WebPidIdentity.parse("1234 (node) S 10", 1234));
        assertNull(WebPidIdentity.parse(stat('S', 0), 1234));
        assertNull(WebPidIdentity.parse(null, 1234));
    }
    @Test public void zombieCannotKeepMaintenanceBlocked() {
        assertTrue(WebPidIdentity.parse(stat('Z', 2), 1234).exited());
        assertFalse(WebPidIdentity.parse(stat('S', 2), 1234).exited());
    }
    @Test public void onlyDirectNodeWebMayReceiveSignal() {
        assertTrue(WebProcSel.maySignalWeb("node --expose-internals /usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js web"));
        for (String text : new String[]{"sh -c node /usr/local/bin/dsh web", "node -e 'dsh web'", "node server.js web",
                "node /tmp/bin.js web", "libproot.so -r /root /usr/local/bin/dsh web", "com.dsh.client", "node /usr/local/bin/dsh plugin"})
            assertFalse(text, WebProcSel.maySignalWeb(text));
    }
}
