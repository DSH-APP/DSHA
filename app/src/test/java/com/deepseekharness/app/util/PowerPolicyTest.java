package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class PowerPolicyTest {
    @Test public void ecoWaitsForContinuousIdleAndResumesForWork() {
        PowerPolicy p = new PowerPolicy();
        assertTrue(p.keepCpu(true,true,false,false,false,false,true,0));
        assertFalse(p.keepCpu(true,true,false,false,false,false,true,60000));
        assertTrue(p.keepCpu(true,true,false,false,false,false,false,61000));
        assertTrue(p.keepCpu(true,true,false,false,false,false,true,62000));
        assertTrue(p.keepCpu(true,true,false,false,true,false,true,180000));
        assertTrue(p.keepCpu(true,true,false,false,false,true,true,240000));
    }
    @Test public void stoppedWebReleasesAndPersistentModeKeepsRunning() {
        PowerPolicy p = new PowerPolicy();
        assertTrue(p.keepCpu(false,true,false,false,false,false,true,0));
        assertTrue(p.keepCpu(false,true,false,false,false,false,true,100000));
        assertFalse(p.keepCpu(false,false,false,false,false,false,false,100001));
        assertTrue(p.keepCpu(true,true,true,false,false,false,true,110000));
        assertTrue(p.keepCpu(true,true,false,true,false,false,true,210000));
    }
}
