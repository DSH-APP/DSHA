package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class AppLocaleDispatchTest {
  @Test
  public void api33WritesResolvedSystemLanguageBeforeFirstActivity() {
    AtomicReference<String> platformLocale = new AtomicReference<>("en");
    List<String> sequence = new ArrayList<>();
    AppLocaleDispatch.apply(
        33,
        "zh",
        new AppLocaleDispatch.Writer() {
          @Override
          public void platform(String language) {
            sequence.add("platform");
            platformLocale.set(language);
          }

          @Override
          public void compat(String language) {
            fail("no Activity delegate exists yet");
          }
        });
    sequence.add("activity-inflate:" + platformLocale.get());
    assertEquals(List.of("platform", "activity-inflate:zh"), sequence);
  }

  @Test
  public void oldApiKeepsAppCompatPathAndEnglishPolicy() {
    List<String> calls = new ArrayList<>();
    AppLocaleDispatch.Writer writer =
        new AppLocaleDispatch.Writer() {
          @Override
          public void platform(String language) {
            fail("platform LocaleManager is unavailable");
          }

          @Override
          public void compat(String language) {
            calls.add(language);
          }
        };
    AppLocaleDispatch.apply(23, "en", writer);
    AppLocaleDispatch.apply(32, "zh", writer);
    assertEquals(List.of("en", "zh"), calls);
  }

  @Test
  public void unsupportedLocaleCannotReachEitherWriter() {
    List<String> calls = new ArrayList<>();
    AppLocaleDispatch.Writer writer =
        new AppLocaleDispatch.Writer() {
          @Override
          public void platform(String language) {
            calls.add("platform");
          }

          @Override
          public void compat(String language) {
            calls.add("compat");
          }
        };
    assertThrows(
        IllegalArgumentException.class, () -> AppLocaleDispatch.apply(33, "system", writer));
    assertThrows(IllegalArgumentException.class, () -> AppLocaleDispatch.apply(23, "fr", writer));
    assertTrue(calls.isEmpty());
  }
}
