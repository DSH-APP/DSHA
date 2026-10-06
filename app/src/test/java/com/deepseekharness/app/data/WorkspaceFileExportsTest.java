package com.deepseekharness.app.data;

import static org.junit.Assert.*;
import com.deepseekharness.app.backup.BackupControl;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

public final class WorkspaceFileExportsTest {
  @Test
  public void exportedWorkFileHasItsOriginalBytesAndNoArchiveWrapper() throws Exception {
    byte[] content = "作品文件\n\u0000PDF/image bytes\r\n".getBytes(StandardCharsets.UTF_8);
    var path = Files.createTempFile("dsha-selected-work", ".pdf");
    try {
      Files.write(path, content);
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      try (InputStream input = Files.newInputStream(path)) {
        var result = WorkspaceFileExports.copy(input, output, content.length, () -> {});
        assertArrayEquals(content, output.toByteArray());
        WorkspaceFileExports.verify(
            new ByteArrayInputStream(output.toByteArray()), result, () -> {});
      }
    } finally {
      Files.delete(path);
    }
  }

  @Test
  public void zeroByteWorkFilesAreValid() throws Exception {
    var result =
        WorkspaceFileExports.copy(
            new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 0, () -> {});
    assertEquals(0, result.size);
    WorkspaceFileExports.verify(new ByteArrayInputStream(new byte[0]), result, () -> {});
  }

  @Test
  public void bothShrinkingAndGrowingSourcesFail() throws Exception {
    for (int actual : new int[] {2, 4}) {
      try {
        WorkspaceFileExports.copy(
            new ByteArrayInputStream(new byte[actual]), new ByteArrayOutputStream(), 3, () -> {});
        fail("Source changed without a failure");
      } catch (IOException expected) {
      }
    }
  }

  @Test
  public void sameSizeDestinationCorruptionIsDetected() throws Exception {
    var result =
        WorkspaceFileExports.copy(
            new ByteArrayInputStream(new byte[] {1, 2, 3}), null, 3, () -> {});
    try {
      WorkspaceFileExports.verify(new ByteArrayInputStream(new byte[] {1, 9, 3}), result, () -> {});
      fail("Corrupted target passed");
    } catch (IOException expected) {
      assertEquals("WORKSPACE_EXPORT_VERIFY", expected.getMessage());
    }
  }

  @Test
  public void cancellationDoesNotWriteAnyTargetBytes() throws Exception {
    BackupControl control = new BackupControl(null);
    control.cancel();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try {
      WorkspaceFileExports.copy(
          new ByteArrayInputStream(new byte[] {1, 2, 3}), output, 3, control::check);
      fail("Cancellation was ignored");
    } catch (InterruptedIOException expected) {
      assertEquals(0, output.size());
    }
  }
}
