package com.deepseekharness.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import com.deepseekharness.app.util.TerminalSession;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class TerminalSessionOwnerTest {
  @Test
  public void failedCloseKeepsIdAndNumberUntilVerifiedRetry() throws Exception {
    TerminalSessionOwner owner = new TerminalSessionOwner();
    assertFalse(((Object) owner.simpleTabs()) instanceof com.deepseekharness.app.util.TerminalTabs);
    assertFalse(((Object) owner.ptyTabs()) instanceof com.deepseekharness.app.util.TerminalTabs);
    var first = owner.addSimple(backend());
    var second = owner.addSimple(backend());
    assertEquals(1, first.number);
    assertEquals(2, second.number);
    assertTrue(owner.beginCloseSimple(first.id));
    assertThrows(IOException.class, () -> owner.closeSimple(first.id, 1, (tab, wait) -> false));
    assertNotNull(owner.simpleTabs().find(first.id));
    assertFalse(owner.simpleTabs().find(first.id).isClosing());
    var third = owner.addSimple(backend());
    assertEquals(3, third.number);
    owner.closeSimple(first.id, 1, (tab, wait) -> true);
    var reused = owner.addSimple(backend());
    assertEquals(1, reused.number);
    assertTrue(first.id < second.id && second.id < third.id && third.id < reused.id);
    owner.closeAllSimpleAndConfirm(1000);
    assertTrue(owner.simpleTabs().snapshot().isEmpty());
  }

  @Test
  public void detachingOldObserverCannotRemoveNewPageObserver() {
    TerminalSessionOwner owner = new TerminalSessionOwner();
    AtomicInteger oldEvents = new AtomicInteger(), newEvents = new AtomicInteger();
    TerminalSessionOwner.SimpleObserver old = observer(oldEvents);
    TerminalSessionOwner.SimpleObserver current = observer(newEvents);
    owner.attachSimple(old);
    owner.addSimple(backend());
    assertEquals(1, oldEvents.get());
    owner.attachSimple(current);
    owner.detachSimple(old);
    owner.addSimple(backend());
    assertEquals(1, oldEvents.get());
    assertEquals(1, newEvents.get());
    owner.detachSimple(current);
    owner.addSimple(backend());
    assertEquals(1, newEvents.get());
  }

  private static TerminalSessionOwner.SimpleObserver observer(AtomicInteger events) {
    return new TerminalSessionOwner.SimpleObserver() {
      @Override
      public void onOutput(TerminalSessionOwner.SimpleTab tab) {
        events.incrementAndGet();
      }

      @Override
      public void onState(TerminalSessionOwner.SimpleTab tab) {
        events.incrementAndGet();
      }

      @Override
      public void onTabsChanged() {
        events.incrementAndGet();
      }
    };
  }

  private static TerminalSession.Backend backend() {
    return new TerminalSession.Backend() {
      @Override
      public Process open() {
        throw new AssertionError("Do not start guest in owner policy test");
      }

      @Override
      public void terminate(Process process, long group) {}
    };
  }
}
