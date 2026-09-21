package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class WebRuntimeFallbackTest {
    @Test public void silentAndCrashExitCanRetryOnce() {
        assertTrue(WebRuntimeFallback.shouldRetry("proroot",false,false,false,true,139));
        assertTrue(WebRuntimeFallback.shouldRetry("proroot",false,false,false,true,0));
        assertFalse(WebRuntimeFallback.shouldRetry("proroot",true,false,false,true,139));
        assertFalse(WebRuntimeFallback.shouldRetry("proot",false,false,false,true,139));
    }
    @Test public void noElapsedTimeOrPluginFailureCanTriggerFallback() {
        assertFalse(WebRuntimeFallback.shouldRetry("proroot",false,false,false,true,null));
        assertFalse(WebRuntimeFallback.shouldRetry("proroot",false,false,true,true,1));
        assertFalse(WebRuntimeFallback.shouldRetry("proroot",false,true,false,true,139));
        assertFalse(WebRuntimeFallback.shouldRetry("proroot",false,false,false,false,139));
    }

    @Test public void bxrootAlsoFallsBackOnce() {
        assertTrue(WebRuntimeFallback.shouldRetry("bxroot",false,false,false,true,139));
        assertFalse(WebRuntimeFallback.shouldRetry("bxroot",true,false,false,true,139));
        assertFalse(WebRuntimeFallback.shouldRetry("bxroot",false,true,false,true,139));
    }

    @Test public void threeStrikesForcesProotForExperimentalRuntimes() {
        assertFalse(WebRuntimeFallback.shouldForceProot(0, "proroot"));
        assertFalse(WebRuntimeFallback.shouldForceProot(1, "proroot"));
        assertFalse(WebRuntimeFallback.shouldForceProot(2, "proroot"));
        assertTrue(WebRuntimeFallback.shouldForceProot(3, "proroot"));
        assertTrue(WebRuntimeFallback.shouldForceProot(4, "proroot"));
        assertTrue(WebRuntimeFallback.shouldForceProot(3, "bxroot"));
    }

    @Test public void prootNeverForcesItself() {
        assertFalse(WebRuntimeFallback.shouldForceProot(3, "proot"));
        assertFalse(WebRuntimeFallback.shouldForceProot(99, "proot"));
        assertFalse(WebRuntimeFallback.shouldForceProot(3, null));
        assertFalse(WebRuntimeFallback.shouldForceProot(3, ""));
    }
}
