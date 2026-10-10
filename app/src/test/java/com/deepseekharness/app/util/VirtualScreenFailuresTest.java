package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class VirtualScreenFailuresTest {
  @Test
  public void mapsNativeApiLookupFailuresToAStableCode() {
    assertEquals(
        VirtualScreenFailures.NATIVE_API_UNAVAILABLE,
        VirtualScreenFailures.code(
            new NoSuchMethodException(
                "no such method: android.view.InputEvent.setDisplayId(int)")));
    assertEquals(
        VirtualScreenFailures.NATIVE_API_UNAVAILABLE,
        VirtualScreenFailures.code(new NoSuchFieldException("displayId")));
    assertEquals(
        VirtualScreenFailures.NATIVE_API_UNAVAILABLE,
        VirtualScreenFailures.code(
            new NoClassDefFoundError("android.hardware.input.InputManager")));
    assertEquals(
        VirtualScreenFailures.NATIVE_API_UNAVAILABLE,
        VirtualScreenFailures.code(
            new ClassNotFoundException("android.hardware.input.InputManager")));
  }

  @Test
  public void mapsOtherCategoriesAndNeverLeaksExceptionClassNames() {
    assertEquals(
        VirtualScreenFailures.PERMISSION_DENIED,
        VirtualScreenFailures.code(new SecurityException("ADD_TRUSTED_DISPLAY")));
    assertEquals(
        VirtualScreenFailures.INVALID_REQUEST,
        VirtualScreenFailures.code(new IllegalArgumentException("bad size")));
    assertEquals(
        VirtualScreenFailures.DISPLAY_STATE,
        VirtualScreenFailures.code(new IllegalStateException("released")));
    assertEquals(
        VirtualScreenFailures.DISPLAY_IO,
        VirtualScreenFailures.code(new java.io.IOException("DISPLAY_CREATE_FAILED")));
    assertEquals(
        VirtualScreenFailures.OPERATION_FAILED,
        VirtualScreenFailures.code(new RuntimeException("boom")));
    // 码里不许出现异常类名：issue #113 的原始症状就是 VSCREEN_NoSuchMethodException
    assertFalse(VirtualScreenFailures.code(new RuntimeException("boom")).contains("Runtime"));
  }

  @Test
  public void codeUsesTheChainAndTheSummaryUsesTheRootCause() {
    Throwable wrapped =
        new IllegalStateException(
            "outer", new NoSuchMethodException("no such method: InputEvent.setDisplayId(int)"));
    assertEquals(VirtualScreenFailures.NATIVE_API_UNAVAILABLE, VirtualScreenFailures.code(wrapped));
    assertEquals(
        "NoSuchMethodException: no such method: InputEvent.setDisplayId(int)",
        VirtualScreenFailures.cause(wrapped));
    // 根因认不出来时，才退回沿链取第一个能识别的类别
    assertEquals(
        VirtualScreenFailures.DISPLAY_STATE,
        VirtualScreenFailures.code(
            new IllegalStateException("state", new RuntimeException("boom"))));
  }

  @Test
  public void boundsAndSanitizesTheCauseSummary() {
    assertEquals("", VirtualScreenFailures.cause(null));
    assertEquals("NoSuchMethodException", VirtualScreenFailures.cause(new NoSuchMethodException()));
    String summary = VirtualScreenFailures.cause(new RuntimeException("x".repeat(500)));
    assertTrue(summary.endsWith("…"));
    assertEquals(VirtualScreenFailures.MAX_CAUSE + 1, summary.length());
    assertEquals(
        "IllegalStateException: a b",
        VirtualScreenFailures.cause(new IllegalStateException("a\n  b")));
  }

  @Test
  public void survivesACyclicCauseChain() {
    Throwable first = new RuntimeException("first");
    Throwable second = new RuntimeException("second");
    first.initCause(second);
    second.initCause(first);
    assertEquals(VirtualScreenFailures.OPERATION_FAILED, VirtualScreenFailures.code(first));
    assertEquals("RuntimeException: second", VirtualScreenFailures.cause(first));
  }
}
