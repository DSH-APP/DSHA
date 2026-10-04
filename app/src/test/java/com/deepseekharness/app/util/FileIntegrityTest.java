package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class FileIntegrityTest {
  @Test
  public void digestAndCopyAgree() throws Exception {
    byte[] bytes = "abc".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    FileIntegrity.Result result = FileIntegrity.copy(new ByteArrayInputStream(bytes), out, 3);
    assertArrayEquals(bytes, out.toByteArray());
    assertEquals(3, result.size);
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result.sha256);
    assertFalse(
        result.matches(
            FileIntegrity.copy(new ByteArrayInputStream(new byte[] {1, 2, 3}), null, 3)));
  }

  @Test
  public void oversizedInputNeverCompletes() throws Exception {
    try {
      FileIntegrity.copy(new ByteArrayInputStream(new byte[4]), new ByteArrayOutputStream(), 3);
      fail("必须拒绝超限输入");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("大小"));
    }
  }

  @Test
  public void interruptedCopyFails() throws Exception {
    Thread.currentThread().interrupt();
    try {
      FileIntegrity.copy(new ByteArrayInputStream(new byte[1]), null, 10);
      fail("必须响应中断");
    } catch (IOException expected) {
      assertTrue(expected instanceof java.io.InterruptedIOException);
      assertEquals("CANCELLED", expected.getMessage());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void externalCancellationCheckStopsBeforeTheNextChunkIsPublished() throws Exception {
    int[] checks = {0};
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    assertThrows(
        java.io.InterruptedIOException.class,
        () ->
            FileIntegrity.copy(
                new ByteArrayInputStream(new byte[100_000]),
                output,
                100_000,
                () -> {
                  if (++checks[0] == 3) throw new java.io.InterruptedIOException("CANCELLED");
                }));
    assertEquals(65_536, output.size());
  }
}
