package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PictureInPicturePolicyTest {
    @Test public void actualWindowSizeReflowsHeightAndPreservesLogicalWidth() {
        assertArrayEquals(new int[]{1080,1440},PictureInPicturePolicy.fittedViewport(1080,2256,270,360,"auto"));
        assertArrayEquals(new int[]{2256,1269},PictureInPicturePolicy.fittedViewport(1080,2256,480,270,"landscape"));
        assertArrayEquals(new int[]{1080,2256},PictureInPicturePolicy.fittedViewport(1080,2256,0,0,"auto"));
        assertArrayEquals(new int[]{1080,16384},PictureInPicturePolicy.fittedViewport(1080,2256,1,Integer.MAX_VALUE,"auto"));
    }
    @Test public void onlySystemAllowedReadyContentMayEnter() {
        assertTrue(PictureInPicturePolicy.canEnter(true, true, true, false, false));
        for (int denied = 0; denied < 5; denied++) {
            boolean[] conditions = {true, true, true, false, false};
            conditions[denied] = !conditions[denied];
            assertFalse(PictureInPicturePolicy.canEnter(conditions[0], conditions[1], conditions[2], conditions[3], conditions[4]));
        }
    }
    @Test public void aspectRatioHandlesPortraitLandscapeAndExtremeScreens() {
        assertArrayEquals(new int[]{1080, 2256}, PictureInPicturePolicy.aspectRatio(1080, 2256));
        assertArrayEquals(new int[]{1920, 1080}, PictureInPicturePolicy.aspectRatio(1920, 1080));
        assertArrayEquals(new int[]{100, 239}, PictureInPicturePolicy.aspectRatio(1, Integer.MAX_VALUE));
        assertArrayEquals(new int[]{239, 100}, PictureInPicturePolicy.aspectRatio(Integer.MAX_VALUE, 1));
        assertArrayEquals(new int[]{9, 16}, PictureInPicturePolicy.aspectRatio(0, 0));
        assertArrayEquals(new int[]{9, 16}, PictureInPicturePolicy.aspectRatio(-1, 400));
    }
    @Test public void wholePageFitsWithoutCropping() {
        assertEquals(0.2f, PictureInPicturePolicy.scale(216, 480, 1080, 2256), 0.0001f);
        assertEquals(0.1f, PictureInPicturePolicy.scale(300, 200, 1000, 2000), 0.0001f);
        assertEquals(1f, PictureInPicturePolicy.scale(300, 200, 0, 0), 0.0001f);
    }

    @Test public void manualOrientationReflowsTheViewportWithoutChangingTheDevice() {
        assertArrayEquals(new int[]{2256, 1080}, PictureInPicturePolicy.viewport(1080, 2256, "landscape"));
        assertArrayEquals(new int[]{1080, 2256}, PictureInPicturePolicy.viewport(2256, 1080, "portrait"));
        assertArrayEquals(new int[]{2256, 1080}, PictureInPicturePolicy.viewport(2256, 1080, "auto"));
        assertArrayEquals(new int[]{1080, 2256}, PictureInPicturePolicy.viewport(1080, 2256, "portrait"));
        assertArrayEquals(new int[]{1920, 1080}, PictureInPicturePolicy.viewport(0, -1, "landscape"));
        assertEquals("auto", PictureInPicturePolicy.layout(null));
        assertEquals("auto", PictureInPicturePolicy.layout("invalid"));
        assertEquals("portrait", PictureInPicturePolicy.layout("portrait"));
        assertEquals("landscape", PictureInPicturePolicy.layout("landscape"));
    }
    @Test public void stoppedOrReplacedWebSessionMustNotKeepAnOldWindow() {
        assertTrue(PictureInPicturePolicy.sessionActive(7, 7, false, false));
        assertFalse(PictureInPicturePolicy.sessionActive(0, 0, false, false));
        assertFalse(PictureInPicturePolicy.sessionActive(7, 8, false, false));
        assertFalse(PictureInPicturePolicy.sessionActive(7, 7, true, false));
        assertFalse(PictureInPicturePolicy.sessionActive(7, 7, false, true));
    }
}
