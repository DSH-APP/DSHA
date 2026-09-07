package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class UpdatePolicyTest {
    private UpdatePolicy.Release release(int code, String channel, String flavor, int minSdk) {
        return new UpdatePolicy.Release(code, "1.2.0-rc1.3", channel, flavor, minSdk,
                "arm64-v8a", "https://dsha.cc/downloads/a.apk", "a".repeat(64), 100,
                "更新说明", "https://dsha.cc/download/");
    }
    @Test public void separatesChannelsAndEditionsAndNeverDowngrades() {
        UpdatePolicy.Release stable = release(113, "stable", "low", 23);
        UpdatePolicy.Release preview = release(114, "preview", "low", 23);
        java.util.List<UpdatePolicy.Release> entries = Arrays.asList(
                release(110, "preview", "low", 23), release(112, "preview", "low", 23),
                release(120, "preview", "standard", 30), stable, preview);
        assertSame(stable, UpdatePolicy.select(entries, 112, "low", 23, "stable"));
        assertSame(preview, UpdatePolicy.select(entries, 112, "low", 23, "preview"));
        assertNull(UpdatePolicy.select(entries, 120, "low", 23, "preview"));
        assertNull(UpdatePolicy.select(entries, 112, "standard", 29, "preview"));
    }
    @Test public void defaultsAndReleasePromotionUseVersionCode() {
        assertEquals("preview", UpdatePolicy.defaultChannel("1.2.0-rc1.3low"));
        assertEquals("stable", UpdatePolicy.defaultChannel("1.2.0"));
        assertNotNull(UpdatePolicy.select(Arrays.asList(release(113, "stable", "standard", 30)),
                112, "standard", 37, "preview"));
    }
    @Test public void rejectsUnsafeOrIncompleteArtifacts() {
        assertFalse(UpdatePolicy.https("http://dsha.cc/a.apk"));
        assertFalse(UpdatePolicy.https("https://user:secret@dsha.cc/a.apk"));
        assertFalse(UpdatePolicy.https("https://dsha.cc/a.apk#secret"));
        UpdatePolicy.Release bad = new UpdatePolicy.Release(120, "1.3", "preview", "low", 23,
                "arm64-v8a", "https://dsha.cc/a.apk", "bad", 100, "", "https://dsha.cc/");
        assertNull(UpdatePolicy.select(Arrays.asList(bad), 112, "low", 23, "preview"));
    }
    @Test public void legacyStableArtifactDoesNotIdentifyTheQueryChannel() {
        UpdatePolicy.Release stable = release(113, "stable", "standard", 30);
        assertSame(stable, UpdatePolicy.select(Arrays.asList(stable), 112, "standard", 37, "stable"));
        assertSame(stable, UpdatePolicy.select(Arrays.asList(stable), 112, "standard", 37, "preview"));
        assertNull(UpdatePolicy.restoreCheckedChannel(false, null, stable.channel));
    }
    @Test public void legacyPreviewArtifactHasOnlyOnePossibleQueryChannel() {
        UpdatePolicy.Release preview = release(113, "preview", "standard", 30);
        assertNull(UpdatePolicy.select(Arrays.asList(preview), 112, "standard", 37, "stable"));
        assertSame(preview, UpdatePolicy.select(Arrays.asList(preview), 112, "standard", 37, "preview"));
        assertEquals("preview", UpdatePolicy.restoreCheckedChannel(false, null, preview.channel));
    }
    @Test public void explicitSourceWinsAndUnknownMarkerNeverBecomesLegacy() {
        assertEquals("preview", UpdatePolicy.restoreCheckedChannel(true, "preview", "stable"));
        assertEquals("stable", UpdatePolicy.restoreCheckedChannel(true, "stable", "stable"));
        for (String invalid : new String[]{null, "", "unknown", "preview "}) {
            assertNull(UpdatePolicy.restoreCheckedChannel(true, invalid, "stable"));
            assertNull(UpdatePolicy.restoreCheckedChannel(true, invalid, "preview"));
        }
        assertNull(UpdatePolicy.restoreCheckedChannel(false, null, "invalid"));
    }
}
