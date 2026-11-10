package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class UiLanguagePreferenceTest {

  /** Saved values are canonical; locale variants belong to the system resolution path. */
  @Test
  public void onlyExplicitSelectionDeviatesFromSystem() {
    for (String value : new String[] {null, "", "system", "de", "EN", "zh-CN", "en-US", "SYSTEM"}) {
      assertEquals("跟随系统", UiLanguagePreference.SYSTEM, UiLanguagePreference.normalize(value));
      assertTrue("应视为跟随系统：" + value, UiLanguagePreference.followsSystem(value));
    }
    for (String language : UiLanguagePreference.LANGUAGES) {
      assertEquals(language, UiLanguagePreference.normalize(language));
      assertFalse(UiLanguagePreference.followsSystem(language));
    }
  }

  @Test
  public void supportedCoversSystemAndExactlyTenLanguages() {
    assertTrue(UiLanguagePreference.supported("system"));
    assertTrue(UiLanguagePreference.supported("zh"));
    assertTrue(UiLanguagePreference.supported("en"));
    assertEquals(10, UiLanguagePreference.LANGUAGES.size());
    for (String language : UiLanguagePreference.LANGUAGES) {
      assertTrue(UiLanguagePreference.supported(language));
      assertFalse(UiLanguagePreference.nativeName(language).isEmpty());
    }
    assertFalse(UiLanguagePreference.supported("de"));
    assertFalse(UiLanguagePreference.supported(null));
    assertFalse(UiLanguagePreference.supported(""));
  }

  /** System language retains script/region distinctions when a translated locale exists. */
  @Test
  public void systemLanguageMapsToChineseOnlyForChinese() {
    for (String tag : new String[] {"zh", "zh-CN", "zh-Hans", "ZH", "zh-SG", "zh-Hans-TW"}) {
      assertEquals("中文应识别：" + tag, "zh", UiLanguagePreference.resolveLanguage(tag));
    }
    for (String tag : new String[] {"en", "en-US", "de-DE", "zho", "en-zh"}) {
      assertEquals("非中文应落英文：" + tag, "en", UiLanguagePreference.resolveLanguage(tag));
    }
    for (String tag : new String[] {"zh-Hant", "zh_TW", "zh-HK", "zh-MO", "zh-Hant-CN"})
      assertEquals(tag, "zh-Hant", UiLanguagePreference.resolveLanguage(tag));
    for (String tag :
        new String[] {"ru-RU", "ja-JP", "ko-KR", "ar-EG", "pt-BR", "pt-PT", "la", "fr-CA"})
      assertEquals(tag, tag.split("-")[0], UiLanguagePreference.resolveLanguage(tag));
  }

  /** 显式选择必须压过系统语言；跟随系统时才看系统。 */
  @Test
  public void explicitChoiceWinsOverSystem() {
    assertEquals("zh", UiLanguagePreference.resolve("zh", "en-US"));
    assertEquals("en", UiLanguagePreference.resolve("en", "zh-CN"));
    assertEquals("en", UiLanguagePreference.resolve("system", "en-US"));
    assertEquals("zh", UiLanguagePreference.resolve("system", "zh-CN"));
    // 从未设置（null）等价于跟随系统
    assertEquals("en", UiLanguagePreference.resolve(null, "en-US"));
    assertEquals("zh", UiLanguagePreference.resolve(null, "zh-CN"));
    assertEquals("fr", UiLanguagePreference.resolve(null, "fr"));
    assertEquals("fr", UiLanguagePreference.resolve("system", "fr-FR"));
    assertEquals("zh-Hant", UiLanguagePreference.resolve("zh-Hant", "ar-EG"));
    assertEquals("ar", UiLanguagePreference.resolve("ar", "zh-TW"));
    assertTrue(UiLanguagePreference.isRtl("ar-EG"));
    assertFalse(UiLanguagePreference.isRtl("zh-Hant"));
  }

  @Test
  public void unidentifiedSystemDefaultsToChineseButExplicitChoiceWins() {
    for (String tag : new String[] {null, "", "  ", "und", "?", "123", "x-private"}) {
      assertEquals("zh", UiLanguagePreference.resolveLanguage(tag));
      assertEquals("en", UiLanguagePreference.resolve("en", tag));
    }
  }

  @Test
  public void isChineseHandlesSeparatorsAndCase() {
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
  @Test
  public void systemLanguageStaysPinnedAfterDefaultLocaleChanges() {
    java.util.Locale before = java.util.Locale.getDefault();
    try {
      SystemLanguage.resetForTest();
      java.util.Locale.setDefault(java.util.Locale.US);
      SystemLanguage.initialize();
      String pinned = SystemLanguage.tag();
      assertEquals("en-US", pinned);

      // 模拟 LanguageController.apply()：切到中文
      java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
      assertEquals("锁存值不得被 setDefault 污染（否则跟随系统自我锁死）", pinned, SystemLanguage.tag());

      // 再切英文也一样
      java.util.Locale.setDefault(java.util.Locale.US);
      assertEquals(pinned, SystemLanguage.tag());
    } finally {
      java.util.Locale.setDefault(before);
      SystemLanguage.resetForTest();
    }
  }

  /** initialize() 只生效第一次，重复调用不覆盖。 */
  @Test
  public void initializeIsIdempotent() {
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

  @Test
  public void globalChineseBeatsPersistedEnglishAppLocale() {
    java.util.Locale before = java.util.Locale.getDefault();
    try {
      SystemLanguage.resetForTest();
      java.util.Locale.setDefault(
          java.util.Locale.US); // app locale already restored before Application.onCreate
      assertEquals(
          "en-US", SystemLanguage.tag()); // possible provider read before Application.onCreate
      SystemLanguage.initializeFromSystemTag("zh-CN"); // LocaleManager.getSystemLocales()
      assertEquals("zh-CN", SystemLanguage.tag());
      assertEquals("zh", UiLanguagePreference.resolve("system", SystemLanguage.tag()));
      SystemLanguage.initializeFromSystemTag(
          "en-US"); // later app apply must not replace the first lock
      java.util.Locale.setDefault(java.util.Locale.US);
      assertEquals("zh-CN", SystemLanguage.tag());
    } finally {
      java.util.Locale.setDefault(before);
      SystemLanguage.resetForTest();
    }
  }

  @Test
  public void missingLocaleManagerFallsBackToEarlyLegacySource() {
    java.util.Locale before = java.util.Locale.getDefault();
    try {
      SystemLanguage.resetForTest();
      java.util.Locale.setDefault(java.util.Locale.US);
      SystemLanguage.initializeFromSystemTag(null); // API 23–32 or unavailable service
      assertEquals("en-US", SystemLanguage.tag());
      java.util.Locale.setDefault(java.util.Locale.SIMPLIFIED_CHINESE);
      assertEquals("en-US", SystemLanguage.tag());
    } finally {
      java.util.Locale.setDefault(before);
      SystemLanguage.resetForTest();
    }
  }

  @Test
  public void lateAppLocaleProbeCannotOverwritePublishedSystemLocale() throws Exception {
    SystemLanguage.resetForTest();
    CountDownLatch probing = new CountDownLatch(1), resumeProbe = new CountDownLatch(1);
    AtomicReference<String> observed = new AtomicReference<>();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread early =
        new Thread(
            () -> {
              try {
                observed.set(
                    SystemLanguage.tagWithDetector(
                        () -> {
                          probing.countDown();
                          try {
                            if (!resumeProbe.await(2, TimeUnit.SECONDS))
                              throw new AssertionError("probe was not released");
                          } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                          }
                          return "en-US"; // older app-locale source returns after authoritative
                          // publication
                        }));
              } catch (Throwable error) {
                failure.set(error);
              }
            },
            "early-language-probe");
    early.setDaemon(true);
    try {
      early.start();
      assertTrue("old probe did not begin", probing.await(5, TimeUnit.SECONDS));
      SystemLanguage.initializeFromSystemTag("zh-CN");
    } finally {
      resumeProbe.countDown();
      early.join(5000);
    }
    assertFalse("old probe remained blocked", early.isAlive());
    if (failure.get() != null) throw new AssertionError(failure.get());
    assertEquals("zh-CN", observed.get());
    assertEquals("zh-CN", SystemLanguage.tag());
    SystemLanguage.resetForTest();
  }

  @Test
  public void runtimeTextSwitchesBothWays() {
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
