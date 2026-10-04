package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ApplicationOwnerTest {
  @Test
  public void concurrentCallersShareExactlyOneOwner() throws Exception {
    ApplicationOwner<Object> owner = new ApplicationOwner<>();
    AtomicInteger constructed = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    var workers = Executors.newFixedThreadPool(8);
    try {
      var tasks = new java.util.ArrayList<java.util.concurrent.Future<Object>>();
      for (int i = 0; i < 16; i++)
        tasks.add(
            workers.submit(
                () -> {
                  assertTrue(start.await(2, TimeUnit.SECONDS));
                  return owner.get(
                      () -> {
                        constructed.incrementAndGet();
                        return new Object();
                      });
                }));
      start.countDown();
      Object first = tasks.get(0).get(3, TimeUnit.SECONDS);
      for (var task : tasks) assertSame(first, task.get(3, TimeUnit.SECONDS));
      assertEquals(1, constructed.get());
    } finally {
      workers.shutdownNow();
    }
  }

  @Test
  public void failedCreationDoesNotPublishOrPoisonTheOwner() {
    ApplicationOwner<Object> owner = new ApplicationOwner<>();
    try {
      owner.get(
          () -> {
            throw new IllegalStateException("failed");
          });
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("failed", expected.getMessage());
    }
    Object result = new Object();
    assertSame(result, owner.get(() -> result));
    assertSame(
        result,
        owner.get(
            () -> {
              throw new AssertionError("replacement");
            }));
    assertNotSame(result, new ApplicationOwner<>().get(Object::new));
  }

  @Test
  public void recursionAndNullAreRejectedBeforePublication() {
    ApplicationOwner<Object> owner = new ApplicationOwner<>();
    try {
      owner.get(() -> owner.get(Object::new));
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("APPLICATION_OWNER_RECURSION", expected.getMessage());
    }
    try {
      owner.get(() -> null);
      fail();
    } catch (IllegalStateException expected) {
      assertEquals("APPLICATION_OWNER_MISSING", expected.getMessage());
    }
    assertNotNull(owner.get(Object::new));
  }
}
