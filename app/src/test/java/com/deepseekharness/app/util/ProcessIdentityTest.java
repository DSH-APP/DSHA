package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProcessIdentityTest {
    private String stat(int pid, int parent, long started) {
        return pid + " (odd ) command) S " + parent + " 0".repeat(17) + " " + started + " 0 0";
    }
    @Test public void acceptsOnlyPlatformProcessDescriptionsWithPositivePid() {
        assertEquals(321, ProcessIdentity.androidPid("java.lang.UNIXProcess", "Process[pid=321, hasExited=false]"));
        assertEquals(321, ProcessIdentity.androidPid("java.lang.ProcessImpl", "Process[pid=321, exitValue=\"not exited\"]"));
        assertEquals(-1, ProcessIdentity.androidPid("app.FakeProcess", "Process[pid=321, hasExited=false]"));
        for (String value : new String[]{"-321", "0", "1", "2147483648", "12;kill", "12 x"})
            assertEquals(value, -1, ProcessIdentity.androidPid("java.lang.UNIXProcess", "Process[pid=" + value + ", hasExited=false]"));
    }
    @Test public void rejectsUnrecognizedDescriptionInsteadOfGuessingPid() {
        for (String value : new String[]{null, "java.lang.Process@321", "pid=321", "Process[pid=321]", "Process[pid=]"})
            assertEquals(-1, ProcessIdentity.androidPid("java.lang.UNIXProcess", value));
    }
    @Test public void onlyOwnChildWithMatchingKernelStartTimeMayBeSignalled() {
        ProcessIdentity identity = ProcessIdentity.fromStat(stat(321, 123, 555), 321, 123);
        assertNotNull(identity); assertEquals(555, identity.started);
        assertTrue(identity.sameProcess(ProcessIdentity.fromStat(stat(321, 123, 555), 321, 123)));
        assertFalse(identity.sameProcess(ProcessIdentity.fromStat(stat(321, 123, 556), 321, 123)));
        assertNull(ProcessIdentity.fromStat(stat(321, 222, 555), 321, 123));
        assertNull(ProcessIdentity.fromStat(stat(123, 123, 555), 123, 123));
        assertNull(ProcessIdentity.fromStat(stat(322, 123, 555), 321, 123));
    }
    @Test public void missingOrMalformedStatCannotAuthorizeSignal() {
        for (String value : new String[]{null, "", "321 (foo) S 123", stat(321, 123, 0), "321 broken"})
            assertNull(ProcessIdentity.fromStat(value, 321, 123));
    }
    @Test public void prootProtocolIsNotAppliedToProrootOrOtherCommands() {
        assertTrue(ProcessIdentity.isProot("/data/app/pkg/lib/arm64/libproot.so"));
        assertTrue(ProcessIdentity.isProot("/data/app/pkg/lib/arm64/libproot_legacy.so (deleted)"));
        for (String value : new String[]{null, "/bin/bash", "/lib/libproroot.so", "/tmp/libproot.so-other"})
            assertFalse(ProcessIdentity.isProot(value));
    }
}
