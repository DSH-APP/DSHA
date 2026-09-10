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
}
