package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProcessIdentityTest {
    @Test public void terminalSessionIncludesSeparateJobGroupsButExcludesOtherSessions() {
        String child = "444 (sleep) S 321 444 321" + " 0".repeat(15) + " 555 0 0";
        ProcessIdentity member = ProcessIdentity.inSession(child, 444, 321);
        assertNotNull(member); assertFalse(member.ownsSession()); assertFalse(member.exited());
        assertNull(ProcessIdentity.inSession(child, 444, 123));
        assertNull(ProcessIdentity.inSession(child, 321, 321));
        assertNull(ProcessIdentity.inSession(child, 444, 0));
        assertFalse(member.sameProcess(ProcessIdentity.inSession(child.replace("555", "556"), 444, 321)));
        assertTrue(ProcessIdentity.inSession(child.replace(") S ", ") Z "), 444, 321).exited());
        assertNull(ProcessIdentity.inSession("444 (sleep) S 321", 444, 321));
    }
    @Test public void isolatedSessionRequiresBothGroupAndSessionToMatchOwnPid() {
        String own = "321 (sh) T 123 321 321" + " 0".repeat(15) + " 555 0 0";
        ProcessIdentity identity = ProcessIdentity.fromStat(own, 321, 123);
        assertNotNull(identity); assertTrue(identity.ownsSession()); assertEquals('T', identity.state);
        assertFalse(ProcessIdentity.fromStat(own.replace("123 321 321", "123 222 321"), 321, 123).ownsSession());
        assertFalse(ProcessIdentity.fromStat(own.replace("123 321 321", "123 321 222"), 321, 123).ownsSession());
        assertNull(ProcessIdentity.fromStat(own, 321, 999));
        assertFalse(identity.sameProcess(ProcessIdentity.fromStat(own.replace("555", "556"), 321, 123)));
    }
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
