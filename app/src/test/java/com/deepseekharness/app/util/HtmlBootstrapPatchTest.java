package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class HtmlBootstrapPatchTest {
    private static final String PAGE="<html><head><script type=module src=app.js></script></head><body>kept</body></html>";
    @Test public void compatibilityRunsBeforeAnyAppScript() {
        String html=HtmlBootstrapPatch.apply(PAGE,"window.compatReady=true;");
        assertTrue(html.indexOf("window.compatReady")<html.indexOf("type=module"));assertTrue(html.contains("<body>kept</body>"));
    }
    @Test public void repeatedPreparationAndAssetUpgradeUseOneOwnedBlock() {
        String first=HtmlBootstrapPatch.apply(PAGE,"versionOne()");assertEquals(first,HtmlBootstrapPatch.apply(first,"versionOne()"));
        String next=HtmlBootstrapPatch.apply(first,"versionTwo()");assertFalse(next.contains("versionOne()"));assertEquals(2,next.split("DSHA_BROWSER_COMPAT_BEGIN",-1).length);
    }
    @Test public void scriptClosingTextCannotTerminateTheInjectedElement() {
        String html=HtmlBootstrapPatch.apply(PAGE,"var text='</script><p>value</p>';");
        assertTrue(html.contains("<\\/script>"));assertFalse(html.contains("text='</script>"));
    }
    @Test public void rejectsAmbiguousHeadOrDamagedOwnedMarkers() {
        for(String page:new String[]{"<body/>","<head><head>",PAGE+"<!-- DSHA_BROWSER_COMPAT_BEGIN -->"})try{HtmlBootstrapPatch.apply(page,"test()");fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void retainsTheEarlyCharsetDeclaration() {
        String html=HtmlBootstrapPatch.apply(PAGE.replace("<head>","<head><meta charset=\"utf-8\" />"),"compatibility()");
        assertTrue(html.indexOf("charset=")<html.indexOf("DSHA_BROWSER_COMPAT_BEGIN"));
    }
}
