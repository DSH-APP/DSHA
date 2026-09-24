package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;

public class UiLanguagePreferenceTest {

    /** 只认显式的小写 en/zh；其它（含 null、旧脏值、大写、区域标签）一律「跟随系统」。 */
    @Test public void onlyExplicitSelectionDeviatesFromSystem() {
        for (String value : new String[]{null, "", "system", "fr", "EN", "zh-CN", "en-US", "SYSTEM"}) {
            assertEquals("跟随系统", UiLanguagePreference.SYSTEM, UiLanguagePreference.normalize(value));
            assertTrue("应视为跟随系统：" + value, UiLanguagePreference.followsSystem(value));
        }
        assertEquals("en", UiLanguagePreference.normalize("en"));
        assertEquals("zh", UiLanguagePreference.normalize("zh"));
        assertFalse(UiLanguagePreference.followsSystem("en"));
        assertFalse(UiLanguagePreference.followsSystem("zh"));
    }

    @Test public void supportedCoversExactlyThreeValues() {
        assertTrue(UiLanguagePreference.supported("system"));
        assertTrue(UiLanguagePreference.supported("zh"));
        assertTrue(UiLanguagePreference.supported("en"));
        assertFalse(UiLanguagePreference.supported("fr"));
        assertFalse(UiLanguagePreference.supported(null));
        assertFalse(UiLanguagePreference.supported(""));
    }

    /** 系统语言映射：中文（含区域变体）→ 中文；其它任何语言 → 英文。 */
    @Test public void systemLanguageMapsToChineseOnlyForChinese() {
        for (String tag : new String[]{"zh", "zh-CN", "zh-Hans", "zh-Hant", "zh_TW", "ZH", "zh-HK"}) {
            assertEquals("中文应识别：" + tag, "zh", UiLanguagePreference.resolveLanguage(tag));
        }
        for (String tag : new String[]{"en", "en-US", "fr", "de-DE", "ja", "ko", "ar", "ru",
                "zho", "en-zh"}) {
            assertEquals("非中文应落英文：" + tag, "en", UiLanguagePreference.resolveLanguage(tag));
        }
    }

    /** 显式选择必须压过系统语言；跟随系统时才看系统。 */
    @Test public void explicitChoiceWinsOverSystem() {
        assertEquals("zh", UiLanguagePreference.resolve("zh", "en-US"));
        assertEquals("en", UiLanguagePreference.resolve("en", "zh-CN"));
        assertEquals("en", UiLanguagePreference.resolve("system", "en-US"));
        assertEquals("zh", UiLanguagePreference.resolve("system", "zh-CN"));
        // 从未设置（null）等价于跟随系统
        assertEquals("en", UiLanguagePreference.resolve(null, "en-US"));
        assertEquals("zh", UiLanguagePreference.resolve(null, "zh-CN"));
        // 法语等非中文系统落英文：英语是唯一可用的国际通用语言
        assertEquals("en", UiLanguagePreference.resolve(null, "fr"));
        assertEquals("en", UiLanguagePreference.resolve("system", "fr-FR"));
    }

    @Test public void unidentifiedSystemDefaultsToChineseButExplicitChoiceWins() {
        for (String tag : new String[]{null, "", "  ", "und", "?", "123", "x-private"}) {
            assertEquals("zh", UiLanguagePreference.resolveLanguage(tag));
            assertEquals("en", UiLanguagePreference.resolve("en", tag));
        }
    }

    @Test public void isChineseHandlesSeparatorsAndCase() {
        assertTrue(UiLanguagePreference.isChinese("zh"));
        assertTrue(UiLanguagePreference.isChinese("zh-CN"));
        assertTrue(UiLanguagePreference.isChinese("zh_Hant_TW"));
        assertFalse(UiLanguagePreference.isChinese("zhx"));
        assertFalse(UiLanguagePreference.isChinese(null));
        assertFalse(UiLanguagePreference.isChinese(""));
    }

    /**
     * 真机回归：{@code Locale.setDefault} 之后，已锁存的系统语言不得改变。
     *
     * <p>这是真机上实测到的坑 —— vivo/Android 16 下 {@code Resources.getSystem()}
     * 会跟着默认 Locale 变，导致「跟随系统」在实际改过语言后读到界面语言（自我锁死）。
     * 修法是进程最早时 {@code initialize()} 锁存；本测试在 JVM 上锁住这个契约。
     */
    @Test public void systemLanguageStaysPinnedAfterDefaultLocaleChanges() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            SystemLanguage.resetForTest();
            java.util.Locale.setDefault(java.util.Locale.US);
            SystemLanguage.initialize();
            String pinned = SystemLanguage.tag();
            assertEquals("en-US", pinned);

            // 模拟 LanguageController.apply()：切到中文
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
            assertEquals("锁存值不得被 setDefault 污染（否则跟随系统自我锁死）",
                    pinned, SystemLanguage.tag());

            // 再切英文也一样
            java.util.Locale.setDefault(java.util.Locale.US);
            assertEquals(pinned, SystemLanguage.tag());
        } finally {
            java.util.Locale.setDefault(before);
            SystemLanguage.resetForTest();
        }
    }

    /** initialize() 只生效第一次，重复调用不覆盖。 */
    @Test public void initializeIsIdempotent() {
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            SystemLanguage.resetForTest();
            java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
            SystemLanguage.initialize();
            String first = SystemLanguage.tag();
            java.util.Locale.setDefault(java.util.Locale.US);
            SystemLanguage.initialize();
            assertEquals("重复 initialize 不得覆盖首次锁存值", first, SystemLanguage.tag());
        } finally {
            java.util.Locale.setDefault(before);
            SystemLanguage.resetForTest();
        }
    }

    @Test public void runtimeTextSwitchesBothWays() {
        try {
            UiText.setLanguage("en");
            assertEquals("New", UiText.choose("新建", "New"));
            UiText.setLanguage("zh");
            assertEquals("新建", UiText.choose("新建", "New"));
        } finally {
            UiText.setLanguage("zh");
        }
    }
}
