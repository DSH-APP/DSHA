package com.deepseekharness.app.transfer;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public class TransferProtocolTest {
  private static final String HASH = "a".repeat(64);
  private static final String ETAG = '"' + HASH + '"';

  private interface Throwing {
    void run() throws Exception;
  }

  private void rejects(String code, Throwing action) throws Exception {
    try {
      action.run();
      fail("Accepted hostile response");
    } catch (IOException expected) {
      assertEquals(code, expected.getMessage());
    }
  }

  @Test
  public void resumesBeyondSignedIntWithoutTruncation() throws Exception {
    assertEquals(
        4_000_000_000L,
        TransferProtocol.responseOffset(
            206,
            4_000_000_000L,
            5_000_000_000L,
            "bytes 4000000000-4999999999/5000000000",
            "1000000000",
            ETAG,
            HASH));
  }

  @Test
  public void rangeIgnoredReplacesRatherThanAppends() throws Exception {
    assertEquals(0, TransferProtocol.responseOffset(200, 700, 1000, null, "1000", ETAG, HASH));
  }

  @Test
  public void wrongStartCannotJoinExistingPrefix() throws Exception {
    rejects(
        "FILE_RANGE_INVALID",
        () ->
            TransferProtocol.responseOffset(
                206, 700, 1000, "bytes 0-999/1000", "1000", ETAG, HASH));
  }

  @Test
  public void wrongEndAndTotalCannotJoin() throws Exception {
    rejects(
        "FILE_RANGE_INVALID",
        () ->
            TransferProtocol.responseOffset(
                206, 700, 1000, "bytes 700-998/1000", "299", ETAG, HASH));
    rejects(
        "FILE_RANGE_INVALID",
        () ->
            TransferProtocol.responseOffset(
                206, 700, 1000, "bytes 700-999/1001", "300", ETAG, HASH));
  }

  @Test
  public void validatorMustBeStrongAndMatchWholeSha() throws Exception {
    for (String tag : new String[] {null, "W/" + ETAG, '"' + "b".repeat(64) + '"'})
      rejects(
          "FILE_SOURCE_CHANGED",
          () ->
              TransferProtocol.responseOffset(
                  206, 700, 1000, "bytes 700-999/1000", "300", tag, HASH));
  }

  @Test
  public void missingOrWrongLengthIsNotAccepted() throws Exception {
    for (String length : new String[] {null, "-1", "301", "x", "999999999999999999999999"})
      rejects(
          "FILE_LENGTH_INVALID",
          () ->
              TransferProtocol.responseOffset(
                  206, 700, 1000, "bytes 700-999/1000", length, ETAG, HASH));
  }

  @Test
  public void overflowRangeIsRejected() throws Exception {
    rejects(
        "FILE_RANGE_INVALID",
        () ->
            TransferProtocol.responseOffset(
                206, 700, 1000, "bytes 999999999999999999999999-999/1000", "300", ETAG, HASH));
  }

  @Test
  public void authenticationAndRedirectFailuresNeverBecomeContent() throws Exception {
    rejects(
        "FILE_HTTP_403",
        () -> TransferProtocol.responseOffset(403, 0, 1000, null, "9", null, HASH));
    rejects(
        "FILE_HTTP_302",
        () -> TransferProtocol.responseOffset(302, 0, 1000, null, "0", null, HASH));
  }

  @Test
  public void spaceIncludesRemainingPartialAndFinalCopy() throws Exception {
    assertEquals(
        10_000_000_000L + 16L * 1024 * 1024,
        TransferProtocol.requiredSpace(5_000_000_000L, 0, true));
    assertEquals(
        5_000_000_100L + 16L * 1024 * 1024,
        TransferProtocol.requiredSpace(5_000_000_000L, 4_999_999_900L, true));
    rejects("FILE_SIZE_INVALID", () -> TransferProtocol.requiredSpace(5_000_000_001L, 0, true));
  }

  @Test
  public void emptyFilesRemainValid() throws Exception {
    assertEquals(0, TransferProtocol.responseOffset(200, 0, 0, null, "0", ETAG, HASH));
  }

  @Test
  public void sourceHashWaitIsLongButContentAndReleaseRemainBounded() {
    assertEquals(15 * 60 * 1000, TransferProtocol.SOURCE_VERIFY_READ_TIMEOUT_MS);
    assertEquals(30_000, TransferProtocol.CONTENT_READ_TIMEOUT_MS);
    assertEquals(5_000, TransferProtocol.RELEASE_TIMEOUT_MS);
    assertEquals(15_000, TransferProtocol.CONNECT_TIMEOUT_MS);
    assertTrue(
        TransferProtocol.SOURCE_VERIFY_READ_TIMEOUT_MS > TransferProtocol.CONTENT_READ_TIMEOUT_MS);
    assertTrue(TransferProtocol.CONTENT_READ_TIMEOUT_MS > TransferProtocol.RELEASE_TIMEOUT_MS);
    assertTrue(TransferProtocol.RELEASE_TIMEOUT_MS > 0);
  }
}
