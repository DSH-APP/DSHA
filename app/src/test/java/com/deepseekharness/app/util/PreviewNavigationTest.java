package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewNavigationTest {
    @Test public void rotatedOwnerCanClaimAfterCookieAndOnlyOnce() {
        PreviewNavigation state = new PreviewNavigation();
        long ticket = state.begin(true);
        assertFalse(state.claim());
        assertTrue(state.cookieCompleted(ticket, true));
        assertTrue(state.claim());
        assertTrue(state.cookieAccepted());
        assertFalse(state.claim());
    }
    @Test public void oldCookieCannotLoadReplacementOrDestroyedView() {
        PreviewNavigation state = new PreviewNavigation();
        long old = state.begin(true);
        long current = state.begin(true);
        assertFalse(state.cookieCompleted(old, true));
        assertFalse(state.claim());
        assertTrue(state.cookieCompleted(current, false));
        assertTrue(state.claim());
        assertFalse(state.cookieAccepted());
        long pending = state.begin(true);
        state.cancel();
        assertFalse(state.cookieCompleted(pending, true));
        assertFalse(state.claim());
    }
    @Test public void noCookieStartsOnceWithoutReloadOnRotation() {
        PreviewNavigation state = new PreviewNavigation();
        state.begin(false);
        assertTrue(state.claim());
        assertTrue(state.started());
        assertFalse(state.claim());
    }
}
