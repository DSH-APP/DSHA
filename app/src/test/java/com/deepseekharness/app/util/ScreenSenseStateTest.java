package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public final class ScreenSenseStateTest {
  private static final ScreenTarget TARGET = new ScreenTarget(0, 7, "example.app");

  private static ScreenSenseState state(long sid, String scope, String label) {
    return state(sid, scope, TARGET, label);
  }

  private static ScreenSenseState state(long sid, String scope, ScreenTarget target, String label) {
    return new ScreenSenseState(
        sid,
        scope,
        target,
        1080,
        2400,
        true,
        -1,
        "tree",
        4,
        false,
        "",
        List.of(new ScreenSenseState.Element("k000", "B", label, "", "c", true, 0, 0, 100, 80)));
  }

  @Test
  public void stateIsImmutableAndCodecEscapesLabels() {
    ScreenSenseState value = state(1, "scope", "a\"b\\c\n" + ((char) 0x0b));
    try {
      value.elements.add(value.elements.get(0));
      fail("state elements must be immutable");
    } catch (UnsupportedOperationException expected) {
    }
    String encoded = ScreenSenseCodec.encode(value);
    assertTrue(encoded.startsWith("S1 example.app 1080x2400 q=ok f=1 ime=? src=tree v=4\n"));
    assertTrue(encoded.contains("a\\\"b\\\\c\\n\\u000b"));
  }

  @Test
  public void duplicateIdsAndInvalidBoundsAreRejected() {
    ScreenSenseState.Element first =
        new ScreenSenseState.Element("k000", "B", "one", "", "c", false, 0, 0, 0, 0);
    ScreenSenseState.Element second =
        new ScreenSenseState.Element("k000", "T", "two", "", "", false, 0, 0, 0, 0);
    try {
      new ScreenSenseState(1, "scope", TARGET, 1, false, "", List.of(first, second));
      fail("duplicate ids must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_SENSE_DUPLICATE_ID", expected.getMessage());
    }
    try {
      new ScreenSenseState.Element("k001", "B", "bad", "", "c", true, 10, 0, 1, 1);
      fail("reversed bounds must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_SENSE_ELEMENT_INVALID", expected.getMessage());
    }
  }

  @Test
  public void elementsWithHiddenBoundsHaveEqualHashes() {
    ScreenSenseState.Element first =
        new ScreenSenseState.Element("k000", "T", "same", "", "", false, 0, 0, 1, 1);
    ScreenSenseState.Element second =
        new ScreenSenseState.Element("k000", "T", "same", "", "", false, 8, 9, 10, 11);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
  }

  @Test
  public void diffRequiresSameScopeAndMonotonicSid() {
    ScreenSenseState previous = state(1, "scope", "old");
    ScreenSenseState current = state(2, "scope", "new");
    ScreenSenseCodec.Delta delta = ScreenSenseCodec.diff(previous, current);
    assertEquals(1, delta.changedCount);
    assertTrue(ScreenSenseCodec.encodeDelta(previous, current).contains("D 2 1 1 1080x2400"));
    try {
      ScreenSenseCodec.diff(previous, state(2, "other", "new"));
      fail("scope mismatch must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_SENSE_SCOPE_MISMATCH", expected.getMessage());
    }
    try {
      ScreenSenseCodec.diff(previous, state(1, "scope", "new"));
      fail("sid regression must be rejected");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_SENSE_SCOPE_MISMATCH", expected.getMessage());
    }
  }

  @Test
  public void stateStoreIsolatesOwnerEpochTargetAndExpiresAtBoundary() {
    AtomicLong now = new AtomicLong(100);
    ScreenSenseStateStore store = new ScreenSenseStateStore(now::get);
    ScreenSenseState value = state(store.nextSid(), store.scopeId("owner-a", 3, TARGET), "one");
    store.record("owner-a", 3, TARGET, value);
    assertSame(value, store.previous("owner-a", 3, TARGET));
    assertNull(store.previous("owner-b", 3, TARGET));
    assertNull(store.previous("owner-a", 4, TARGET));
    now.addAndGet(30_000);
    assertSame(value, store.previous("owner-a", 3, TARGET));
    now.incrementAndGet();
    assertNull(store.previous("owner-a", 3, TARGET));
  }

  @Test
  public void storeRejectsStateFromAnotherTarget() {
    AtomicLong now = new AtomicLong(100);
    ScreenSenseStateStore store = new ScreenSenseStateStore(now::get);
    ScreenTarget other = new ScreenTarget(0, 8, "example.app");
    ScreenSenseState value = state(store.nextSid(), store.scopeId("owner-a", 3, TARGET), "one");
    try {
      store.record("owner-a", 3, other, value);
      fail("state target must match the storage scope");
    } catch (IllegalArgumentException expected) {
      assertEquals("SCREEN_SENSE_SCOPE_CHANGED", expected.getMessage());
    }
  }

  @Test
  public void invalidationAndOwnerClearRemoveOnlyRequestedState() {
    AtomicLong now = new AtomicLong(100);
    ScreenSenseStateStore store = new ScreenSenseStateStore(now::get);
    ScreenTarget other = new ScreenTarget(0, 8, "example.app");
    ScreenSenseState a = state(store.nextSid(), store.scopeId("a", 1, TARGET), "a");
    ScreenSenseState b = state(store.nextSid(), store.scopeId("b", 1, other), other, "b");
    store.record("a", 1, TARGET, a);
    store.record("b", 1, other, b);
    store.invalidate("a", 1, TARGET);
    assertNull(store.previous("a", 1, TARGET));
    assertSame(b, store.previous("b", 1, other));
    store.clearOwner("b");
    assertNull(store.previous("b", 1, other));
  }
}
