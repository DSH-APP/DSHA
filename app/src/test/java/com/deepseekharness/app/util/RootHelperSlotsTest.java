package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

public class RootHelperSlotsTest {
  /** 用字符串代表进程，alive 集合代表仍在运行的 helper。 */
  private final Set<String> alive = new HashSet<>();

  private final RootHelperSlots<String> slots = new RootHelperSlots<>(2, alive::contains);

  @Test
  public void refusesBeforeLaunchWhenFull() {
    assertNotNull(slots.tryAcquire("exec", 0));
    assertNotNull(slots.tryAcquire("exec", 0));
    assertNull(slots.tryAcquire("exec", 0));
    assertTrue(slots.snapshot(0).full());
  }

  @Test
  public void exitedHelperReleasesImmediately() {
    var ticket = slots.tryAcquire("exec", 0);
    slots.attach(ticket, "p1"); // 未在 alive 中 = 已退出
    assertFalse(slots.release(ticket, 10));
    assertEquals(0, slots.snapshot(10).active);
  }

  @Test
  public void neverStartedHelperReleasesImmediately() {
    var ticket = slots.tryAcquire("exec", 0);
    assertFalse(slots.release(ticket, 0));
    assertNotNull(slots.tryAcquire("exec", 0));
    assertNotNull(slots.tryAcquire("exec", 0));
  }

  @Test
  public void helperThatCannotBeKilledKeepsItsSlotUntilItExits() {
    // T38：App 的 uid 结束不了 uid 0 的 helper；超时后它仍占着槽位。
    var first = slots.tryAcquire("exec", 0);
    slots.attach(first, "p1");
    alive.add("p1");
    assertTrue(slots.release(first, 1_000));
    var second = slots.tryAcquire("exec", 1_000);
    slots.attach(second, "p2");
    alive.add("p2");
    assertTrue(slots.release(second, 2_000));
    assertNull("两个滞留 helper 未退出前不能再起新的", slots.tryAcquire("exec", 3_000));

    RootHelperSlots.Snapshot snapshot = slots.snapshot(5_000);
    assertEquals(2, snapshot.lingering);
    assertEquals(0, snapshot.active);
    assertEquals(4_000, snapshot.oldestLingeringMs);
    assertTrue(snapshot.render().contains("lingering=2"));

    alive.remove("p1"); // helper 看门狗到期自行退出
    assertNotNull(slots.tryAcquire("exec", 6_000));
    assertEquals(1, slots.snapshot(6_000).lingering);
  }

  @Test
  public void repeatedOrForeignReleaseHasNoEffect() {
    var ticket = slots.tryAcquire("exec", 0);
    assertFalse(slots.release(ticket, 0));
    assertFalse(slots.release(ticket, 0));
    assertFalse(slots.release(null, 0));
    assertEquals(0, slots.snapshot(0).active);
  }

  @Test
  public void labelsAreRecordedForDiagnostics() {
    slots.tryAcquire("virtual-screen-start", 0);
    slots.tryAcquire("", 0);
    assertEquals("virtual-screen-start,exec", String.join(",", slots.snapshot(0).labels));
  }

  @Test(expected = IllegalArgumentException.class)
  public void capacityMustBePositive() {
    new RootHelperSlots<String>(0, p -> true);
  }
}
