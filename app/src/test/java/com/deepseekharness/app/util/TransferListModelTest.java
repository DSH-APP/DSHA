package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.TransferListModel.Action;
import com.deepseekharness.app.util.TransferListModel.Entry;
import com.deepseekharness.app.util.TransferListModel.Failure;
import com.deepseekharness.app.util.TransferListModel.Filter;
import com.deepseekharness.app.util.TransferListModel.Section;
import com.deepseekharness.app.util.TransferListModel.SectionKind;
import com.deepseekharness.app.util.TransferListModel.Step;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class TransferListModelTest {
  private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

  private static long at(int day, int hour) {
    return LocalDateTime.of(2026, 11, day, hour, 0).atZone(ZONE).toInstant().toEpochMilli();
  }

  private static final List<Entry> MIXED =
      List.of(
          new Entry("done-today", "COMPLETE", at(2, 6)),
          new Entry("failed", "FAILED", at(2, 5)),
          new Entry("downloading", "DOWNLOADING", at(2, 7)),
          new Entry("paused", "PAUSED", at(1, 9)),
          new Entry("cancel-yesterday", "CANCELLED", at(1, 21)),
          new Entry("done-yesterday", "COMPLETE", at(1, 22)),
          new Entry("waiting", "WAITING_DESTINATION", at(2, 6)),
          new Entry("old", "COMPLETE", at(1, 0) - 3 * 86_400_000L));

  @Test
  public void attentionThenTransferringThenNewestDaysFirst() {
    List<Section> sections = TransferListModel.sections(MIXED, Filter.ALL, ZONE);
    assertEquals(SectionKind.ATTENTION, sections.get(0).kind);
    assertEquals(
        List.of("waiting", "failed", "paused"),
        sections.get(0).entries.stream().map(e -> e.id).toList());
    assertEquals(SectionKind.TRANSFERRING, sections.get(1).kind);
    assertEquals("downloading", sections.get(1).entries.get(0).id);
    assertEquals(SectionKind.DAY, sections.get(2).kind);
    assertEquals(List.of("done-today"), sections.get(2).entries.stream().map(e -> e.id).toList());
    assertEquals(
        List.of("done-yesterday", "cancel-yesterday"),
        sections.get(3).entries.stream().map(e -> e.id).toList());
    assertEquals(5, sections.size());
    assertTrue(sections.get(2).epochDay > sections.get(3).epochDay);
  }

  @Test
  public void filtersKeepPausedAndWaitingUnderActiveAndCountEverySegment() {
    Map<Filter, Integer> counts =
        TransferListModel.counts(
            MIXED.stream().map(e -> new Entry(e.id, e.status, e.createdAt)).toList());
    assertEquals(8, (int) counts.get(Filter.ALL));
    assertEquals(3, (int) counts.get(Filter.ACTIVE));
    assertEquals(1, (int) counts.get(Filter.FAILED));
    assertEquals(4, (int) counts.get(Filter.DONE));
    assertTrue(TransferListModel.showFilter(counts));
    List<Section> active = TransferListModel.sections(MIXED, Filter.ACTIVE, ZONE);
    assertEquals(
        List.of("waiting", "paused", "downloading"),
        active.stream().flatMap(s -> s.entries.stream()).map(e -> e.id).toList());
    Map<Filter, Integer> onlyDone =
        TransferListModel.counts(
            List.of(new Entry("a", "COMPLETE", 1), new Entry("b", "CANCELLED", 2)));
    assertFalse(TransferListModel.showFilter(onlyDone));
    assertEquals(Filter.ALL, TransferListModel.effective(Filter.FAILED, onlyDone));
    assertEquals(Filter.DONE, TransferListModel.effective(Filter.DONE, onlyDone));
  }

  @Test
  public void stepsMarkWhereTheFailureHappened() {
    assertArrayEquals(
        new Step[] {Step.FAILED, Step.TODO, Step.TODO},
        TransferListModel.steps("FAILED", Failure.NO_SPACE));
    assertArrayEquals(
        new Step[] {Step.DONE, Step.FAILED, Step.TODO},
        TransferListModel.steps("FAILED", Failure.INTEGRITY));
    assertArrayEquals(
        new Step[] {Step.DONE, Step.DONE, Step.FAILED},
        TransferListModel.steps("FAILED", Failure.SAVE));
    assertArrayEquals(
        new Step[] {Step.DONE, Step.DONE, Step.CURRENT},
        TransferListModel.steps("WAITING_DESTINATION", Failure.OTHER));
    assertArrayEquals(
        new Step[] {Step.PAUSED, Step.TODO, Step.TODO}, TransferListModel.steps("PAUSED", null));
    assertEquals(0, TransferListModel.steps("CANCELLED", null).length);
  }

  @Test
  public void runningRecordsCanOnlyBeCancelledAndFinishedOnlyRemoved() {
    assertEquals(List.of(Action.OPEN, Action.SHARE), TransferListModel.primary("COMPLETE"));
    assertEquals(List.of(), TransferListModel.primary("VERIFYING"));
    assertEquals(List.of(Action.CHOOSE), TransferListModel.primary("WAITING_DESTINATION"));
    assertEquals(Action.CANCEL, TransferListModel.manage("QUEUED"));
    assertEquals(Action.CANCEL, TransferListModel.manage("WAITING_DESTINATION"));
    assertEquals(Action.REMOVE, TransferListModel.manage("FAILED"));
    assertEquals(Action.REMOVE, TransferListModel.manage("CANCELLED"));
    assertTrue(TransferListModel.cancelDiscards("PAUSED", 10));
    assertFalse(TransferListModel.cancelDiscards("PAUSED", 0));
    assertFalse(TransferListModel.cancelDiscards("WAITING_DESTINATION", 10));
  }

  @Test
  public void failureClassificationKeepsTheOriginalPrecedence() {
    assertEquals(Failure.CANCELLED, TransferListModel.failure("FILE_TASK_CANCELLED"));
    assertEquals(Failure.NO_SPACE, TransferListModel.failure("write ENOSPC"));
    assertEquals(Failure.INTEGRITY, TransferListModel.failure("FILE_SHA256 mismatch"));
    assertEquals(Failure.SOURCE_GONE, TransferListModel.failure("HTTP_404"));
    assertEquals(Failure.INTERRUPTED, TransferListModel.failure("HTTP_503"));
    assertEquals(Failure.SAVE, TransferListModel.failure("FILE_SAVE_TARGET_INVALID"));
    assertEquals(Failure.OTHER, TransferListModel.failure(null));
  }

  @Test
  public void namesProgressAndEta() {
    assertEquals(TransferListModel.Kind.CODE, TransferListModel.kind("preview (1).HTML"));
    assertEquals(TransferListModel.Kind.ARCHIVE, TransferListModel.kind("logs.tar.gz"));
    assertEquals(TransferListModel.Kind.TEXT, TransferListModel.kind("README"));
    assertEquals("HTML", TransferListModel.extension("preview.html"));
    assertEquals("", TransferListModel.extension(".bashrc"));
    assertEquals("", TransferListModel.extension("a.verylongext1"));
    assertTrue(TransferListModel.renamed("preview (1).html", "preview.html"));
    assertFalse(TransferListModel.renamed("preview.html", "preview.html"));
    assertEquals(-1, TransferListModel.percent(5, -1));
    assertEquals(42, TransferListModel.percent(42, 100));
    assertEquals(3, TransferListModel.etaSeconds(0, 25, 10));
    assertEquals(-1, TransferListModel.etaSeconds(0, 25, 0));
  }
}
