package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.deepseekharness.app.util.WebStopEvidence.Kind.*;

public class WebStopEvidenceTest {
    private WebPidIdentity identity(long started) {
        return WebPidIdentity.parse("42 (node) S 1 1 1 " + "0 ".repeat(15) + started + " 0 0", 42);
    }
    @Test public void deniedDoesNotRetireRecordedProcessOrReleaseScan() {
        assertFalse(WebStopEvidence.mayRetire(DENIED, false, "42 100", null));
        assertFalse(WebStopEvidence.mayRetire(DENIED, false, null, null));
        assertTrue(WebStopEvidence.scanUnconfirmed(DENIED, false));
    }
    @Test public void confirmedOtherUidAndGoneCanRetireWithoutReadableIdentity() {
        assertTrue(WebStopEvidence.mayRetire(OTHER, true, "42 100", null));
        assertFalse(WebStopEvidence.scanUnconfirmed(DENIED, true));
        assertTrue(WebStopEvidence.mayRetire(GONE, false, null, null));
    }
    @Test public void onlyDifferentBirthRetiresAliveRecordedPid() {
        assertNotNull(identity(100));
        assertTrue(WebStopEvidence.mayRetire(WEB, false, "42 100", identity(101)));
        assertFalse(WebStopEvidence.mayRetire(OTHER, false, "42 100", identity(100)));
        assertFalse(WebStopEvidence.mayRetire(OTHER, false, null, identity(100)));
        assertFalse(WebStopEvidence.mayRetire(WEB, false, "broken", identity(100)));
        assertFalse(WebStopEvidence.mayRetire(WEB, false, "43 100", identity(100)));
        assertTrue(WebStopEvidence.scanUnconfirmed(WEB, false));
        assertFalse(WebStopEvidence.scanUnconfirmed(OTHER, false));
    }
}
