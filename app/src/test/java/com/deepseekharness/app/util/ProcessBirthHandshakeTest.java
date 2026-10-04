package com.deepseekharness.app.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProcessBirthHandshakeTest {
  static String stat(String state, long started) {
    return "321 (signed session) " + state + " 99 321 321 " + "0 ".repeat(15) + started;
  }

  @Test
  public void nativeSelfStatRequiresParentSessionAndPositiveBirth() throws Exception {
    ProcessIdentity id =
        ProcessBirthHandshake.parse(ProcessBirthHandshake.PREFIX + stat("S", 123), 99);
    assertNotNull(id);
    assertEquals(321, id.pid);
    assertEquals(123, id.started);
    assertTrue(id.ownsSession());
    assertNull(ProcessBirthHandshake.parse(ProcessBirthHandshake.PREFIX + stat("S", 123), 88));
    assertNull(ProcessBirthHandshake.parse(ProcessBirthHandshake.PREFIX + stat("Z", 123), 99));
    assertNull(ProcessBirthHandshake.parse(ProcessBirthHandshake.PREFIX + stat("S", 0), 99));
    assertNull(ProcessBirthHandshake.parse("321", 99));
  }

  @Test
  public void consumesExactlyHandshakeLineAndLeavesGuestOutputUntouched() throws Exception {
    var input =
        new ByteArrayInputStream(
            (ProcessBirthHandshake.PREFIX + stat("S", 123) + "\nGUEST\n")
                .getBytes(StandardCharsets.US_ASCII));
    assertEquals(321, ProcessBirthHandshake.read(input, 99, () -> true, 100).pid);
    assertEquals("GUEST\n", new String(input.readAllBytes(), StandardCharsets.US_ASCII));
  }

  @Test
  public void malformedOversizedAndSilentHandshakeFailWithoutInventingPid() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            ProcessBirthHandshake.read(
                new ByteArrayInputStream("unexpected\n".getBytes()), 99, () -> true, 50));
    assertThrows(
        IOException.class,
        () ->
            ProcessBirthHandshake.read(
                new ByteArrayInputStream(new byte[8193]), 99, () -> true, 50));
    assertThrows(
        IOException.class,
        () ->
            ProcessBirthHandshake.read(new ByteArrayInputStream(new byte[0]), 99, () -> true, 20));
  }
}
