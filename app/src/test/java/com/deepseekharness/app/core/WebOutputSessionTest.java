package com.deepseekharness.app.core;

import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import static org.junit.Assert.*;

public class WebOutputSessionTest {
  static final class Child extends Process {
    final InputStream input;
    final CountDownLatch exit = new CountDownLatch(1);

    Child(String text) {
      input = new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public InputStream getInputStream() {
      return input;
    }

    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    public int waitFor() throws InterruptedException {
      exit.await();
      return 7;
    }

    public int exitValue() {
      if (exit.getCount() != 0) throw new IllegalThreadStateException();
      return 7;
    }

    public void destroy() {
      exit.countDown();
    }
  }

  static class Events implements WebOutputSession.Events {
    volatile boolean current = true;
    volatile String auth = "";
    volatile int code = -1;
    final StringBuilder output = new StringBuilder();

    public boolean current() {
      return current;
    }

    public void output(String s) {
      output.append(s);
    }

    public boolean authenticated() {
      return !auth.isEmpty();
    }

    public void authentication(String url) {
      auth = url;
    }

    public void readFailure(IOException e) {
      throw new AssertionError(e);
    }

    public void exited(int value) {
      code = value;
    }
  }

  @Test
  public void pipeEofDoesNotReportProcessExitAndTokenNeverEntersOutput() throws Exception {
    String token = "a".repeat(43), url = "http://127.0.0.1:45678/?token=" + token;
    Child process = new Child("dsh web: " + url + "\n");
    Events events = new Events();
    Thread thread = new Thread(() -> new WebOutputSession(0).drain(process, events));
    thread.start();
    for (int attempt = 0; attempt < 100 && events.auth.isEmpty(); attempt++) Thread.sleep(10);
    assertEquals(url, events.auth);
    assertEquals(-1, events.code);
    assertFalse(events.output.toString().contains(token));
    process.exit.countDown();
    thread.join(2000);
    assertFalse(thread.isAlive());
    assertEquals(7, events.code);
  }

  @Test
  public void staleOutputCannotPublishAuthenticationOrLogs() throws Exception {
    Child process = new Child("dsh web: http://127.0.0.1:45678/?token=" + "a".repeat(43) + "\n");
    process.exit.countDown();
    Events events = new Events();
    events.current = false;
    new WebOutputSession(0).drain(process, events);
    assertEquals("", events.auth);
    assertEquals("", events.output.toString());
    assertEquals(7, events.code);
  }

  @Test
  public void brokenOutputCallbackStillObservesExactProcessExit() throws Exception {
    Child process = new Child("ordinary process output\n");
    process.exit.countDown();
    boolean[] failed = {false};
    Events events =
        new Events() {
          public void output(String s) {
            throw new IllegalStateException("closed UI");
          }

          public void readFailure(IOException error) {
            failed[0] = true;
          }
        };
    new WebOutputSession(0).drain(process, events);
    assertTrue(failed[0]);
    assertEquals(7, events.code);
  }
}
