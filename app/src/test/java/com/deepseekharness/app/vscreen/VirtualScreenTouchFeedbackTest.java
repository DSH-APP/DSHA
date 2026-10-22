package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import org.junit.Test;

public final class VirtualScreenTouchFeedbackTest {
  private final VirtualScreenTouchFeedback feedback = new VirtualScreenTouchFeedback();

  private VirtualScreenTouchFeedback.Ticket begin() {
    return feedback.begin(1, 2, "display-a", 3, "stroke-a");
  }

  private VirtualScreenTouchFeedback.Update receipt(
      VirtualScreenTouchFeedback.Ticket ticket, boolean ok, boolean terminal) {
    return feedback.receipt(ticket, 1, 2, "display-a", ok, terminal);
  }

  @Test
  public void moveSuccessDoesNotPublishAndOnlyTerminalSuccessCompletesOnce() {
    var ticket = begin();
    assertEquals(VirtualScreenTouchFeedback.Update.NONE, receipt(ticket, true, false));
    assertEquals(VirtualScreenTouchFeedback.Update.NONE, receipt(ticket, true, false));
    assertEquals(VirtualScreenTouchFeedback.Update.COMPLETED, receipt(ticket, true, true));
    assertEquals(VirtualScreenTouchFeedback.Update.NONE, receipt(ticket, true, true));
  }

  @Test
  public void firstFailureIsStickyAndCannotBeHiddenByLaterSuccessOrRepeatedFailure() {
    var ticket = begin();
    assertEquals(VirtualScreenTouchFeedback.Update.FAILED, receipt(ticket, false, false));
    assertEquals(VirtualScreenTouchFeedback.Update.NONE, receipt(ticket, false, false));
    assertEquals(VirtualScreenTouchFeedback.Update.NONE, receipt(ticket, true, true));
  }

  @Test
  public void terminalFailureAfterAcceptedDownIsPublished() {
    var ticket = begin();
    assertEquals(VirtualScreenTouchFeedback.Update.NONE, receipt(ticket, true, false));
    assertEquals(VirtualScreenTouchFeedback.Update.FAILED, receipt(ticket, false, true));
  }

  @Test
  public void newGestureRejectsLatePreviousGesture() {
    var old = begin();
    var current = feedback.begin(1, 2, "display-a", 3, "stroke-b");
    assertEquals(VirtualScreenTouchFeedback.Update.NONE, receipt(old, false, true));
    assertEquals(VirtualScreenTouchFeedback.Update.FAILED, receipt(current, false, true));
  }

  @Test
  public void closeOrNewOperationInvalidatesOldReceiptAndOperationRevision() {
    var ticket = begin();
    long revision = feedback.revision();
    feedback.invalidate();
    assertNotEquals(revision, feedback.revision());
    assertEquals(VirtualScreenTouchFeedback.Update.NONE, receipt(ticket, false, true));
  }

  @Test
  public void pageRunAndDisplayMustMatchTheOriginalGesture() {
    var ticket = begin();
    assertEquals(
        VirtualScreenTouchFeedback.Update.NONE,
        feedback.receipt(ticket, 9, 2, "display-a", false, true));
    assertEquals(
        VirtualScreenTouchFeedback.Update.NONE,
        feedback.receipt(ticket, 1, 9, "display-a", false, true));
    assertEquals(
        VirtualScreenTouchFeedback.Update.NONE,
        feedback.receipt(ticket, 1, 2, "display-b", false, true));
    assertEquals(VirtualScreenTouchFeedback.Update.FAILED, receipt(ticket, false, true));
  }

  @Test
  public void callbackLookupCannotBorrowAnotherFrameSequenceGenerationOrStroke() {
    var ticket = begin();
    assertSame(ticket, feedback.find("display-a", 3, "stroke-a"));
    assertNull(feedback.find("display-a", 4, "stroke-a"));
    assertNull(feedback.find("display-b", 3, "stroke-a"));
    assertNull(feedback.find("display-a", 3, "stroke-b"));
  }

  @Test
  public void replacingDisplayedGenerationRetiresItsGestureButSameGenerationFramesDoNot() {
    var ticket = begin();
    feedback.generation("display-a");
    assertSame(ticket, feedback.find("display-a", 3, "stroke-a"));
    feedback.generation("display-b");
    assertEquals(VirtualScreenTouchFeedback.Update.NONE, receipt(ticket, false, true));
    assertNull(feedback.find("display-a", 3, "stroke-a"));
  }

  @Test
  public void workerFailureRequestsOneCancelAndQueuedUpIsNeverSubmitted() {
    var dispatch = new VirtualScreenTouchFeedback.Dispatch();
    assertTrue(dispatch.allowed());
    assertTrue(dispatch.complete(0, false));
    assertFalse(dispatch.allowed());
    assertFalse(dispatch.complete(1, true));
    assertTrue(dispatch.done());
  }

  @Test
  public void workerAcceptedDownKeepsOwnershipUntilUpOrCancelFinishes() {
    var dispatch = new VirtualScreenTouchFeedback.Dispatch();
    assertFalse(dispatch.complete(0, true));
    assertTrue(dispatch.allowed());
    assertFalse(dispatch.complete(2, true));
    assertTrue(dispatch.allowed());
    assertFalse(dispatch.complete(3, true));
    assertFalse(dispatch.allowed());
    assertFalse(dispatch.complete(3, false));
  }
}
