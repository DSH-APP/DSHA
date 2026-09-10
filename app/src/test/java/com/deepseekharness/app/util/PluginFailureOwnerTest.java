package com.deepseekharness.app.util;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;
public class PluginFailureOwnerTest {
    private final Map<String,String> catalog = Map.of("\"dsh-bad\"", "dsh-bad", "\"entry-two\"", "second-plugin",
            "/root/plugins/dsh-bad/", "dsh-bad");
    @Test public void matchesPendingEntryAndImportStack() {
        assertEquals("second-plugin", PluginFailureOwner.find(catalog, "entry-two: pending (waiting for service: legacyApi)"));
        assertEquals("dsh-bad", PluginFailureOwner.find(catalog, "at file:///root/plugins/dsh-bad/index.js:5"));
    }
    @Test public void refusesPrefixAndAmbiguousSummary() {
        assertEquals("", PluginFailureOwner.find(catalog, "dsh-bad-extra did not activate"));
        assertEquals("", PluginFailureOwner.find(catalog, "dsh-bad entry-two failed"));
        assertEquals("", PluginFailureOwner.find(catalog, "ERROR unknown dependency"));
    }
}
