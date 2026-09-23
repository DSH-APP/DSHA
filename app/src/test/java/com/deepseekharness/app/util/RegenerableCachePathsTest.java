package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.HashSet;
import static org.junit.Assert.*;

public class RegenerableCachePathsTest {
    @Test public void cleanupListIsNarrowUniqueAndExcludesPersonalData() {
        var paths = RegenerableCachePaths.rootfs();
        assertEquals(paths.size(), new HashSet<>(paths).size());
        assertTrue(paths.contains("root/.dsh/.runtime-links-cache.json"));
        for (String path : paths) {
            assertTrue(path, RegenerableCachePaths.safeRelative(path));
            assertFalse(path, path.contains("settings"));
            assertFalse(path, path.contains("credential"));
            assertFalse(path, path.contains("plugin-src"));
        }
        for (String path : RegenerableCachePaths.webViewData()) assertTrue(path, RegenerableCachePaths.safeRelative(path));
    }
}
