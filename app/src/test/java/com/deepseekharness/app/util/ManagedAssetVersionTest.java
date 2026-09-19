package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ManagedAssetVersionTest {
    private static final String A = "a".repeat(64), B = "b".repeat(64);

    @Test public void markerBindsExactRuntimeIdentity() {
        String marker = ManagedAssetVersion.marker(A);
        assertTrue(ManagedAssetVersion.current(marker, A));
        assertFalse(ManagedAssetVersion.current(marker, B));
        assertFalse(ManagedAssetVersion.current(marker + "extra", A));
        assertArrayEquals(marker.getBytes(java.nio.charset.StandardCharsets.US_ASCII), ManagedAssetVersion.bytes(A));
    }

    @Test public void rejectsUntrustedIdentityText() {
        for (String value : new String[]{"", "A".repeat(64), "a".repeat(63), "../" + A}) {
            assertFalse(ManagedAssetVersion.validRuntimeId(value));
            assertThrows(IllegalArgumentException.class, () -> ManagedAssetVersion.marker(value));
        }
    }
}
