package com.deepseekharness.app.util;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;
public class CredentialWriteTest {
    @Test public void onlyRoundTrippedCipherCanBeCommitted() throws Exception {
        assertEquals("cipher", CredentialWrite.prepare("secret", p -> "cipher", c -> CredentialRead.available("secret")));
        assertThrows(IOException.class, () -> CredentialWrite.prepare("secret", p -> "", c -> CredentialRead.available("secret")));
        assertThrows(IOException.class, () -> CredentialWrite.prepare("secret", p -> "cipher", c -> CredentialRead.available("different")));
        for (CredentialRead.Reason reason : new CredentialRead.Reason[]{CredentialRead.Reason.DEVICE_LOCKED, CredentialRead.Reason.KEY_INVALIDATED, CredentialRead.Reason.KEY_MISSING})
            assertThrows(IOException.class, () -> CredentialWrite.prepare("secret", p -> "cipher", c -> CredentialRead.failed(reason)));
    }
    @Test public void explicitClearDoesNotCreateEncryptionKey() throws Exception {
        assertEquals("", CredentialWrite.prepare("", p -> {throw new AssertionError();}, c -> {throw new AssertionError();}));
    }
    @Test public void oversizedValueRejectedBeforeEncryption() {
        assertThrows(IOException.class, () -> CredentialWrite.prepare("s".repeat(16385), p -> {throw new AssertionError();}, c -> {throw new AssertionError();}));
    }
}
