package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class UiLanguagePreferenceTest {
    @Test public void chineseIsDefaultAndOnlyExplicitEnglishSwitches() {
        for(String value:new String[]{null,"","system","fr","EN","zh-CN"})assertEquals("zh",UiLanguagePreference.normalize(value));
        assertEquals("en",UiLanguagePreference.normalize("en"));assertTrue(UiLanguagePreference.supported("zh"));
        assertTrue(UiLanguagePreference.supported("en"));assertFalse(UiLanguagePreference.supported("fr"));
    }
    @Test public void runtimeTextSwitchesBothWays() {
        try{UiText.setLanguage("en");assertEquals("New",UiText.choose("新建","New"));
            UiText.setLanguage("zh");assertEquals("新建",UiText.choose("新建","New"));}
        finally{UiText.setLanguage("zh");}
    }
}
