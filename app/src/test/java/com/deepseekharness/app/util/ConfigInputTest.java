package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigInputTest {
    @Test public void usablePorts() {
        assertEquals(3080, ConfigInput.port(" 3080 "));
        assertEquals(1, ConfigInput.port("1"));
        assertEquals(1024, ConfigInput.port("1024"));
        assertEquals(65535, ConfigInput.port("65535"));
    }
    @Test public void reservedAndMalformedPortsNeverSilentlyFallback() {
        for (String value : new String[]{"3081", "3090", "0", "-1", "65536", "", "x", "999999999999", "30.80"})
            assertThrows(value, IllegalArgumentException.class, () -> ConfigInput.port(value));
    }
}
