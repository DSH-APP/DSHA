package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class CredentialReadTest {
    @Test public void absenceAndSuccessfulReadAreDistinct(){
        var empty=CredentialRead.missing();assertEquals(CredentialRead.State.NOT_CONFIGURED,empty.state);assertEquals("",empty.requireValue());
        var configured=CredentialRead.available("owned synthetic key");assertEquals(CredentialRead.State.AVAILABLE,configured.state);assertEquals("owned synthetic key",configured.requireValue());
        assertFalse(configured.toString().contains("owned synthetic key"));
    }
    @Test public void failuresNeverBecomeEmptyCredentials(){
        for(var reason:CredentialRead.Reason.values())if(reason!=CredentialRead.Reason.NONE){
            var value=CredentialRead.failed(reason);assertFalse(value.usable());
            var failure=assertThrows(CredentialRead.Unavailable.class,value::requireValue);assertSame(value,failure.result);assertFalse(failure.getMessage().contains("synthetic"));
        }
    }
    @Test public void onlyReliableTemporaryReasonsPermitRetryClassification(){
        for(var reason:new CredentialRead.Reason[]{CredentialRead.Reason.DEVICE_LOCKED,CredentialRead.Reason.AUTHENTICATION_REQUIRED,CredentialRead.Reason.TRANSIENT_STORE})assertEquals(CredentialRead.State.TEMPORARILY_UNAVAILABLE,CredentialRead.failed(reason).state);
        for(var reason:new CredentialRead.Reason[]{CredentialRead.Reason.KEY_MISSING,CredentialRead.Reason.KEY_INVALIDATED,CredentialRead.Reason.UNREADABLE})assertEquals(CredentialRead.State.NEEDS_ATTENTION,CredentialRead.failed(reason).state);
        assertEquals(CredentialRead.State.NEEDS_ATTENTION,CredentialRead.available("").state);
    }
}
