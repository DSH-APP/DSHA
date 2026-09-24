package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class BrowserDiagnosticTest {
    @Test public void consoleAndInterventionDoNotImplyStartupFailure() {
        assertEquals("BROWSER_INTERVENTION", BrowserDiagnostic.category("[Intervention] Unable to preventDefault", false, false));
        assertEquals("BROWSER_ERROR", BrowserDiagnostic.category("TypeError", false, false));
        assertEquals("STARTUP_ERROR", BrowserDiagnostic.category("Plugin failed", false, true));
        assertEquals("RUNTIME_ERROR", BrowserDiagnostic.category("Plugin failed", true, true));
    }
}
