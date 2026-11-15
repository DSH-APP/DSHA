package com.deepseekharness.app.transfer;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public class TransferArtifactTest {
  private static final String HASH = "ab".repeat(32);

  private TransferArtifact artifact(long bytes, String hash, String version) throws IOException {
    return new TransferArtifact("artifact_17", "报告.zip", "application/zip", bytes, hash, version);
  }

  @Test
  public void allowsFullFiveBillionAndEmptyFiles() throws Exception {
    assertEquals(5_000_000_000L, artifact(5_000_000_000L, HASH, "v1").bytes);
    assertEquals(0, artifact(0, HASH, "v1").bytes);
  }

  @Test
  public void rejectsOverLimitAndNegativeSizes() throws Exception {
    for (long size : new long[] {-1, 5_000_000_001L}) {
      try {
        artifact(size, HASH, "v1");
        fail();
      } catch (IOException expected) {
        assertEquals("FILE_LIMIT_5GB", expected.getMessage());
      }
    }
  }

  @Test
  public void identityRequiresBothHashAndVersionAndSize() throws Exception {
    TransferArtifact original = artifact(100, HASH, "v1");
    original.requireSame(artifact(100, HASH.toUpperCase(java.util.Locale.ROOT), "v1"));
    for (TransferArtifact changed :
        new TransferArtifact[] {
          artifact(100, HASH, "v2"), artifact(101, HASH, "v1"), artifact(100, "cd".repeat(32), "v1")
        }) {
      try {
        original.requireSame(changed);
        fail();
      } catch (IOException expected) {
        assertEquals("FILE_SOURCE_CHANGED", expected.getMessage());
      }
    }
  }

  @Test
  public void filenamesCannotEscapeOrInjectHeaders() {
    for (String name : new String[] {"..", "a/b", "a\\b", "x\r\ny", "", " ", "图".repeat(81)})
      assertFalse(name, TransferArtifact.safeName(name));
    assertTrue(TransferArtifact.safeName("图片 2026.png"));
  }

  @Test
  public void mimeAndOpaqueIdCannotContainUrlsOrHeaders() throws Exception {
    try {
      new TransferArtifact("https://host/path", "a", "text/plain", 0, HASH, "v1");
      fail();
    } catch (IOException expected) {
      assertEquals("FILE_ARTIFACT_ID", expected.getMessage());
    }
    try {
      new TransferArtifact("id", "a", "text/plain\r\nx:y", 0, HASH, "v1");
      fail();
    } catch (IOException expected) {
      assertEquals("FILE_MIME_INVALID", expected.getMessage());
    }
  }
}
