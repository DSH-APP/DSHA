package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class ScreenSessionGrantTest {
    @Test public void grantLastsOnlyForTheSameRun() {
        ScreenSessionGrant grant=new ScreenSessionGrant();
        assertFalse(grant.allowed(1));assertFalse(grant.accept(0,0));
        assertTrue(grant.accept(1,grant.revision()));
        assertTrue(grant.allowed(1));assertFalse(grant.allowed(2));
    }
    @Test public void revokeRejectsPendingConfirmation() {
        ScreenSessionGrant grant=new ScreenSessionGrant();long pending=grant.revision();
        grant.revoke();assertFalse(grant.accept(1,pending));assertFalse(grant.allowed(1));
        assertTrue(grant.accept(1,grant.revision()));grant.revoke();assertFalse(grant.allowed(1));
    }
}
