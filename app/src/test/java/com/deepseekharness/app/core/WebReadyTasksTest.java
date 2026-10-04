package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import org.junit.Test;

public class WebReadyTasksTest {
  static final class Fake implements WebReadyTasks.Ports {
    boolean current = true, bound, cancelOnCookie, cancelOnDelay;
    int exchanges, notices, ready;
    String result =
        "DSHA_RC1_MIGRATION={\"status\":\"committed\",\"sourcePreserved\":true,\"settingsImported\":true}";
    Runnable async;
    java.util.List<String> logs = new java.util.ArrayList<>();
    java.util.List<Long> delays = new java.util.ArrayList<>();

    public boolean current() {
      return current;
    }

    public void asynchronous(String name, Runnable work) {
      async = work;
    }

    public void delay(long ms) {
      delays.add(ms);
      if (cancelOnDelay) current = false;
    }

    public String finalizeMigration() {
      return result;
    }

    public void migrationLog(String value) {
      logs.add(value);
    }

    public void migrationNeedsAttention() {
      notices++;
    }

    public boolean exchangeCookie() {
      exchanges++;
      if (cancelOnCookie) current = false;
      return false;
    }

    public boolean lanBound() {
      return bound;
    }

    public void lanReady() {
      ready++;
    }
  }

  @Test
  public void successfulFinalizationIsQuietAndIncompleteCommitIsVisible() {
    Fake ok = new Fake();
    WebReadyTasks.run(false, false, ok);
    ok.async.run();
    assertEquals(java.util.List.of(3000L), ok.delays);
    assertEquals(0, ok.notices);
    assertTrue(ok.logs.isEmpty());
    Fake bad = new Fake();
    bad.result =
        "DSHA_RC1_MIGRATION={\"status\":\"committed\",\"sourcePreserved\":false,\"settingsImported\":true}";
    WebReadyTasks.run(false, false, bad);
    bad.async.run();
    assertEquals(1, bad.notices);
    assertEquals(1, bad.logs.size());
  }

  @Test
  public void staleMigrationCannotLogOrWarn() {
    Fake fake = new Fake();
    WebReadyTasks.run(false, false, fake);
    fake.cancelOnDelay = true;
    fake.async.run();
    assertTrue(fake.logs.isEmpty());
    assertEquals(0, fake.notices);
  }

  @Test
  public void lanRetriesAndPollingAreBoundedAndSafeModeSkipsMigration() {
    Fake fake = new Fake();
    WebReadyTasks.run(true, true, fake);
    assertNull(fake.async);
    assertEquals(3, fake.exchanges);
    assertEquals(15, fake.delays.size());
    assertEquals(0, fake.ready);
    Fake bound = new Fake();
    bound.bound = true;
    WebReadyTasks.run(true, true, bound);
    assertEquals(1, bound.ready);
  }

  @Test
  public void generationLossDoesNotPublishLanReadiness() {
    Fake fake = new Fake();
    fake.bound = true;
    fake.cancelOnCookie = true;
    WebReadyTasks.run(true, true, fake);
    assertEquals(1, fake.exchanges);
    assertEquals(0, fake.ready);
  }
}
