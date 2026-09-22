package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualScreenPolicyTest {
    @Test public void gatesOldAndroidAndNormalizesPhoneRatios() {
        assertFalse(VirtualScreenPolicy.supported(29));
        assertTrue(VirtualScreenPolicy.supported(30));
        assertArrayEquals(new int[]{720,1280}, VirtualScreenPolicy.phoneSize(720,1520));
        assertArrayEquals(new int[]{1280,720}, VirtualScreenPolicy.phoneSize(1520,720));
    }
    @Test public void mapsCoordinatesAndRejectsStaleFrames() {
        assertArrayEquals(new float[]{500f,1000f}, VirtualScreenPolicy.map(50,100,100,100,1000,1000),0.001f);
        assertArrayEquals(new float[]{0f,1000f}, VirtualScreenPolicy.map(-1,200,100,100,1000,1000),0.001f);
        assertTrue(VirtualScreenPolicy.fresh(4,4));
        assertFalse(VirtualScreenPolicy.fresh(3,4));
    }
}
