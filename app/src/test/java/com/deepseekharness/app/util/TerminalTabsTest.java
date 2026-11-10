package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class TerminalTabsTest {
  @Test
  public void visibleNumbersReuseVacanciesWithoutReusingSessionIdentity() {
    TerminalTabs<String> tabs = new TerminalTabs<>();
    var first = tabs.add("one");
    assertEquals(1, first.number);
    assertTrue(tabs.beginClose(first.id));
    var second = tabs.add("two");
    assertEquals(2, second.number);
    tabs.closeFailed(first.id);
    assertEquals(1, first.number);
    tabs.remove(first.id);
    var replacement = tabs.add("replacement");
    assertEquals(1, replacement.number);
    assertNotEquals(first.id, replacement.id);
    tabs.closeFailed(first.id);
    tabs.remove(first.id);
    assertSame(replacement, tabs.current());
    assertEquals(2, second.number);
    tabs.remove(second.id);
    tabs.remove(replacement.id);
    assertEquals(1, tabs.add("fresh").number);
  }

  @Test
  public void stableSelectionAndIndependentValues() {
    TerminalTabs<String> tabs = new TerminalTabs<>();
    assertFalse(tabs.wasInitialized());
    var a = tabs.add("a");
    var b = tabs.add("b");
    var c = tabs.add("c");
    assertTrue(tabs.select(b.id));
    tabs.remove(a.id);
    assertEquals("b", tabs.current().value);
    tabs.remove(b.id);
    assertEquals("c", tabs.current().value);
    tabs.remove(c.id);
    assertNull(tabs.current());
    assertTrue(tabs.wasInitialized());
    assertTrue(tabs.add("d").id > c.id);
    assertFalse(tabs.select(a.id));
  }

  @Test
  public void failedCloseRetainsSessionAndCanRetry() {
    TerminalTabs<Object> tabs = new TerminalTabs<>();
    Object value = new Object();
    var tab = tabs.add(value);
    assertTrue(tabs.beginClose(tab.id));
    assertFalse(tabs.beginClose(tab.id));
    assertSame(value, tabs.current().value);
    tabs.closeFailed(tab.id);
    assertFalse(tab.isClosing());
    assertTrue(tabs.beginClose(tab.id));
    tabs.remove(tab.id);
    assertFalse(tabs.beginClose(tab.id));
  }

  @Test
  public void closeErrorPersistsAndOldGenerationCannotOverwriteRetry() {
    TerminalTabs<String> tabs = new TerminalTabs<>();
    var first = tabs.add("one");
    assertTrue(tabs.beginClose(first.id));
    long old = first.closeSnapshot().generation;
    assertTrue(tabs.closeFailed(first.id, old, "Authorization: Bearer secret-token\nfailed"));
    assertEquals(TerminalTabs.CleanupState.UNCONFIRMED, first.cleanupState());
    assertTrue(first.isCloseUnconfirmed());
    assertFalse(first.closeError().contains("secret-token"));
    assertFalse(first.closeError().contains("\n"));
    assertFalse(first.isClosing());
    assertTrue(tabs.beginClose(first.id));
    assertTrue(first.closeSnapshot().generation > old);
    assertFalse(tabs.closeFailed(first.id, old, "late failure"));
    assertTrue(first.isClosing());
    assertEquals("", first.error());
    tabs.remove(first.id);
    assertEquals(TerminalTabs.CleanupState.VERIFIED, first.cleanupState());
    var replacement = tabs.add("new");
    assertFalse(tabs.closeFailed(first.id, old, "late old close"));
    assertEquals(TerminalTabs.CleanupState.NOT_REQUESTED, replacement.cleanupState());
  }

  @Test
  public void customTitlesAreBoundedSanitizedAndIndependentOfOutput() {
    TerminalTabs<String> tabs = new TerminalTabs<>();
    var first = tabs.add("original output");
    assertTrue(tabs.rename(first.id, "\u202e API_KEY=private-token\nhello"));
    assertFalse(first.customTitle().contains("private-token"));
    assertFalse(first.customTitle().contains("\u202e"));
    assertFalse(first.customTitle().contains("\n"));
    assertEquals("original output", first.value);
    assertTrue(tabs.rename(first.id, "😀".repeat(120)));
    assertEquals(80, first.customTitle().codePointCount(0, first.customTitle().length()));
    assertEquals(160, first.customTitle().length());
    assertTrue(tabs.rename(first.id, null));
    assertEquals("", first.customTitle());
    assertFalse(tabs.rename(first.id + 1, "other"));
  }

  @Test
  public void snapshotIsDetachedAndImmutable() {
    TerminalTabs<String> tabs = new TerminalTabs<>();
    tabs.add("one");
    var copy = tabs.snapshot();
    tabs.add("two");
    assertEquals(1, copy.size());
    try {
      copy.clear();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }

  @Test
  public void readOnlyViewIsLiveButCannotBecomeTheMutableTable() {
    TerminalTabs<String> tabs = new TerminalTabs<>();
    TerminalTabs.ReadOnly<String> view = tabs.readOnly();
    assertSame(view, tabs.readOnly());
    assertFalse(((Object) view) instanceof TerminalTabs);
    assertFalse(view.wasInitialized());
    var first = tabs.add("one");
    var detached = view.snapshot();
    assertSame(first, view.current());
    assertTrue(view.wasInitialized());
    assertTrue(tabs.beginClose(first.id));
    assertTrue(view.find(first.id).isClosing());
    tabs.closeFailed(first.id);
    assertFalse(view.find(first.id).isClosing());
    tabs.add("two");
    assertEquals(1, detached.size());
    assertEquals(2, view.snapshot().size());
    try {
      detached.clear();
      fail();
    } catch (UnsupportedOperationException expected) {
    }
  }
}
