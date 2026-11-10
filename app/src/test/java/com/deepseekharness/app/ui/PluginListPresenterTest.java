package com.deepseekharness.app.ui;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.PluginSort;
import java.util.List;
import java.util.Set;
import org.junit.Test;

/** 纯宿主验证：不创建 Android 页面，不读设备目录，不执行插件意图。 */
public final class PluginListPresenterTest {
  private PluginListPresenter.Entry<String> entry(
      String key, String content, String name, boolean enabled, boolean system) {
    return new PluginListPresenter.Entry<>(
        name, key, content, name, "description", system, enabled, true, false, false);
  }

  private PluginListPresenter.Operation operation(
      String id, String target, Boolean desired, String phase, int percent, boolean pending) {
    return new PluginListPresenter.Operation(
        id, target, desired, phase, "message", percent, pending);
  }

  private List<PluginListPresenter.Row<String>> rows(
      List<PluginListPresenter.Entry<String>> entries,
      List<PluginListPresenter.Operation> operations) {
    return PluginListPresenter.rows(
        entries, operations, "", false, PluginSort.Mode.NAME_ASC, Set.of());
  }

  private PluginListPresenter.Projection<PluginListPresenter.Entry<String>> projection(
      int[] mapped) {
    return new PluginListPresenter.Projection<>(
        item -> item.stableKey,
        item -> item.contentKey,
        item -> {
          mapped[0]++;
          return new PluginListPresenter.Entry<>(
              item,
              item.stableKey,
              item.contentKey,
              item.name,
              item.description,
              item.system,
              item.observedEnabled,
              item.available,
              item.dynamic,
              item.updateAvailable);
        });
  }

  @Test
  public void hundredProgressUpdatesSelectOnceAndReplaceOnlyTargetRow() {
    int[] mapped = {0};
    var projection = projection(mapped);
    var input =
        List.of(
            entry("b", "b", "beta", true, false),
            entry("a", "a", "alpha", false, false),
            entry("c", "c", "charlie", true, false));
    var rows =
        projection.project(
            input,
            List.of(operation("op", "a", true, "RUNNING", 0, true)),
            "",
            false,
            PluginSort.Mode.NAME_ASC,
            Set.of());
    var untouchedB = rows.get(1);
    var untouchedC = rows.get(2);
    for (int percent = 1; percent <= 100; percent++) {
      var next =
          projection.project(
              input,
              List.of(operation("op", "a", true, "RUNNING", percent, true)),
              "",
              false,
              PluginSort.Mode.NAME_ASC,
              Set.of());
      assertNotSame(rows.get(0), next.get(0));
      assertSame(rows.get(0).entry, next.get(0).entry);
      assertSame(untouchedB, next.get(1));
      assertSame(untouchedC, next.get(2));
      assertEquals(
          PluginListPresenter.OPERATION, PluginListPresenter.changes(rows.get(0), next.get(0)));
      rows = next;
    }
    assertEquals(3, mapped[0]);
    assertEquals(1, projection.selectionVersion());
  }

  @Test
  public void equivalentFreshAndReorderedDirectoriesReuseSelection() {
    int[] mapped = {0};
    var projection = projection(mapped);
    var initial =
        projection.project(
            List.of(entry("a", "v1", "alpha", true, false), entry("b", "v1", "beta", true, false)),
            List.of(),
            "",
            false,
            PluginSort.Mode.NAME_ASC,
            Set.of());
    var copied =
        projection.project(
            List.of(entry("a", "v1", "alpha", true, false), entry("b", "v1", "beta", true, false)),
            List.of(),
            "",
            false,
            PluginSort.Mode.NAME_ASC,
            Set.of());
    var reordered =
        projection.project(
            List.of(entry("b", "v1", "beta", true, false), entry("a", "v1", "alpha", true, false)),
            List.of(),
            "",
            false,
            PluginSort.Mode.NAME_ASC,
            Set.of());
    assertSame(initial, copied);
    assertSame(initial, reordered);
    assertEquals(2, mapped[0]);
    assertEquals(1, projection.selectionVersion());
  }

  @Test
  public void unrelatedOperationProgressKeepsWholeListForDiffFastPath() {
    int[] mapped = {0};
    var projection = projection(mapped);
    var input = List.of(entry("a", "a", "alpha", true, false));
    var original =
        projection.project(input, List.of(), "", false, PluginSort.Mode.NAME_ASC, Set.of());
    for (int percent = 0; percent < 100; percent++)
      assertSame(
          original,
          projection.project(
              input,
              List.of(operation("global", "*", null, "RUNNING", percent, true)),
              "",
              false,
              PluginSort.Mode.NAME_ASC,
              Set.of()));
    assertEquals(1, mapped[0]);
    assertEquals(1, projection.selectionVersion());
  }

  @Test
  public void expansionOnlyReprojectsChangedRowWithoutSelectingAgain() {
    int[] mapped = {0};
    var projection = projection(mapped);
    var input = List.of(entry("a", "a", "same", true, false), entry("b", "b", "same", true, false));
    var initial =
        projection.project(input, List.of(), "", false, PluginSort.Mode.NAME_ASC, Set.of());
    var expanded =
        projection.project(input, List.of(), "", false, PluginSort.Mode.NAME_ASC, Set.of("b"));
    assertSame(initial.get(0), expanded.get(0));
    assertNotSame(initial.get(1), expanded.get(1));
    assertSame(initial.get(1).entry, expanded.get(1).entry);
    assertEquals(
        PluginListPresenter.EXPANSION,
        PluginListPresenter.changes(initial.get(1), expanded.get(1)));
    assertSame(
        expanded,
        projection.project(
            input, List.of(), "", false, PluginSort.Mode.NAME_ASC, Set.of("b", "hidden-target")));
    assertEquals(2, mapped[0]);
    assertEquals(1, projection.selectionVersion());
  }

  @Test
  public void realRuleChangesSelectButNormalizedEquivalentQueryDoesNot() {
    int[] mapped = {0};
    var projection = projection(mapped);
    var input =
        List.of(entry("a", "a", "plugin2", true, false), entry("b", "b", "plugin10", true, true));
    projection.project(input, List.of(), "plugin", false, PluginSort.Mode.NAME_ASC, Set.of());
    assertEquals(1, projection.selectionVersion());
    projection.project(input, List.of(), " PLUGIN ", false, PluginSort.Mode.NAME_ASC, Set.of());
    assertEquals(1, projection.selectionVersion());
    var descending =
        projection.project(input, List.of(), "plugin", false, PluginSort.Mode.NAME_DESC, Set.of());
    assertEquals("b", descending.get(0).entry.stableKey);
    assertEquals(2, projection.selectionVersion());
    var hidden =
        projection.project(input, List.of(), "plugin", true, PluginSort.Mode.NAME_DESC, Set.of());
    assertEquals(1, hidden.size());
    assertEquals(3, projection.selectionVersion());
    var filtered =
        projection.project(input, List.of(), "missing", true, PluginSort.Mode.NAME_DESC, Set.of());
    assertTrue(filtered.isEmpty());
    assertEquals(4, projection.selectionVersion());
    assertEquals(2, mapped[0]);
  }

  @Test
  public void changedContentReselectsButRetainsOtherRowReference() {
    int[] mapped = {0};
    var projection = projection(mapped);
    var initial =
        projection.project(
            List.of(entry("a", "v1", "alpha", true, false), entry("b", "v1", "beta", true, false)),
            List.of(),
            "",
            false,
            PluginSort.Mode.NAME_ASC,
            Set.of());
    var changed =
        projection.project(
            List.of(entry("a", "v2", "alpha", false, false), entry("b", "v1", "beta", true, false)),
            List.of(),
            "",
            false,
            PluginSort.Mode.NAME_ASC,
            Set.of());
    assertNotSame(initial.get(0), changed.get(0));
    assertSame(initial.get(1), changed.get(1));
    assertEquals(
        PluginListPresenter.CONTENT, PluginListPresenter.changes(initial.get(0), changed.get(0)));
    assertEquals(2, projection.selectionVersion());
    assertEquals(4, mapped[0]);
  }

  @Test
  public void directOperationAndExpansionProjectionKeepExistingOrder() {
    var input =
        List.of(entry("a", "a", "alpha", true, false), entry("b", "b", "beta", true, false));
    var initial =
        PluginListPresenter.rows(input, List.of(), "", false, PluginSort.Mode.NAME_DESC, Set.of());
    assertSame(initial, PluginListPresenter.withOperations(initial, List.of()));
    assertSame(initial, PluginListPresenter.withExpansion(initial, Set.of()));
    var changed =
        PluginListPresenter.withOperations(
            initial, List.of(operation("op", "a", false, "QUEUED", -1, true)));
    assertSame(initial.get(0), changed.get(0));
    assertEquals("b", changed.get(0).entry.stableKey);
    assertEquals("a", changed.get(1).entry.stableKey);
    assertEquals(
        PluginListPresenter.OPERATION, PluginListPresenter.changes(initial.get(1), changed.get(1)));
    assertSame(
        changed,
        PluginListPresenter.withOperations(
            changed, List.of(operation("op", "a", false, "QUEUED", -1, true))));
  }

  @Test
  public void equalFreshSnapshotsDoNotRebind() {
    var before = rows(List.of(entry("a", "content-a", "alpha", true, false)), List.of()).get(0);
    var after = rows(List.of(entry("a", "content-a", "alpha", true, false)), List.of()).get(0);
    assertTrue(PluginListPresenter.sameItem(before, after));
    assertEquals(0, PluginListPresenter.changes(before, after));
  }

  @Test
  public void contentChangeKeepsIdentity() {
    var before = rows(List.of(entry("a", "v1", "alpha", true, false)), List.of()).get(0);
    var after = rows(List.of(entry("a", "v2", "alpha", false, false)), List.of()).get(0);
    assertTrue(PluginListPresenter.sameItem(before, after));
    assertEquals(PluginListPresenter.CONTENT, PluginListPresenter.changes(before, after));
  }

  @Test
  public void equalNamesFromDifferentSessionsRemainSeparate() {
    var values =
        List.of(
            entry("dynamic/g1/s1/p1", "v1", "same", true, false),
            entry("dynamic/g1/s2/p1", "v1", "same", true, false));
    var rows =
        rows(values, List.of(operation("op", "dynamic/g1/s2/p1", false, "QUEUED", -1, true)));
    assertEquals(2, rows.size());
    assertFalse(PluginListPresenter.sameItem(rows.get(0), rows.get(1)));
    assertFalse(rows.get(0).pending());
    assertTrue(rows.get(1).pending());
  }

  @Test
  public void dynamicGenerationChangeIsNewIdentity() {
    var before =
        rows(List.of(entry("dynamic/g1/s1/p1", "v1", "same", true, false)), List.of()).get(0);
    var after =
        rows(List.of(entry("dynamic/g2/s1/p1", "v1", "same", true, false)), List.of()).get(0);
    assertFalse(PluginListPresenter.sameItem(before, after));
  }

  @Test
  public void progressOnlyChangesTargetOperationPayload() {
    var entries =
        List.of(entry("a", "a", "alpha", true, false), entry("b", "b", "beta", true, false));
    var before = rows(entries, List.of(operation("op", "a", false, "RUNNING", 10, true)));
    var after = rows(entries, List.of(operation("op", "a", false, "RUNNING", 40, true)));
    assertEquals(
        PluginListPresenter.OPERATION, PluginListPresenter.changes(before.get(0), after.get(0)));
    assertEquals(0, PluginListPresenter.changes(before.get(1), after.get(1)));
    assertTrue(after.get(1).toggleEnabled());
  }

  @Test
  public void pendingDesiredDoesNotRewriteObservedConfiguration() {
    var row =
        rows(
                List.of(entry("a", "a", "alpha", false, false)),
                List.of(operation("op", "a", true, "QUEUED", -1, true)))
            .get(0);
    assertTrue(row.checked());
    assertFalse(row.entry.observedEnabled);
    assertTrue(row.toggleEnabled());
  }

  @Test
  public void latestTargetIntentWinsAndOtherTargetsRemainIndependent() {
    var entries =
        List.of(entry("a", "a", "alpha", true, false), entry("b", "b", "beta", false, false));
    var rows =
        rows(
            entries,
            List.of(
                operation("old", "a", true, "RUNNING", 50, true),
                operation("b", "b", true, "QUEUED", -1, true),
                operation("new", "a", false, "QUEUED", -1, true)));
    assertFalse(rows.get(0).checked());
    assertTrue(rows.get(1).checked());
    assertEquals("new", rows.get(0).operation.operationId);
  }

  @Test
  public void historyCannotOverridePendingIntentAndNewestTerminalWins() {
    var entries = List.of(entry("a", "a", "alpha", false, false));
    var pending =
        rows(
                entries,
                List.of(
                    operation("running", "a", true, "RUNNING", 20, true),
                    operation("waiting", "a", false, "QUEUED", -1, true),
                    operation("history", "a", true, "SUCCEEDED", 100, false)))
            .get(0);
    assertEquals("waiting", pending.operation.operationId);
    assertTrue(pending.pending());
    assertFalse(pending.checked());
    var completed =
        rows(
                entries,
                List.of(
                    operation("recent", "a", true, "FAILED", -1, false),
                    operation("older", "a", false, "SUCCEEDED", 100, false)))
            .get(0);
    assertEquals("recent", completed.operation.operationId);
  }

  @Test
  public void terminalFailureFallsBackToVerifiedConfiguration() {
    var row =
        rows(
                List.of(entry("a", "a", "alpha", false, false)),
                List.of(operation("op", "a", true, "FAILED", -1, false)))
            .get(0);
    assertFalse(row.pending());
    assertFalse(row.checked());
    assertEquals("FAILED", row.operation.phase);
  }

  @Test
  public void nonToggleOperationKeepsVerifiedSwitch() {
    var row =
        rows(
                List.of(entry("a", "a", "alpha", true, false)),
                List.of(operation("op", "a", null, "RUNNING", 20, true)))
            .get(0);
    assertTrue(row.checked());
    assertTrue(row.pending());
  }

  @Test
  public void missingAndDynamicEntriesCannotBeEnabled() {
    var missing =
        new PluginListPresenter.Entry<>(
            "a", "a", "a", "missing", "", false, false, false, false, false);
    var dynamic =
        new PluginListPresenter.Entry<>(
            "b", "b", "b", "temporary", "", false, true, true, true, false);
    var rows = rows(List.of(missing, dynamic), List.of());
    assertFalse(rows.get(0).toggleEnabled());
    assertFalse(rows.get(1).toggleEnabled());
  }

  @Test
  public void searchHiddenFilterAndNaturalSortCompose() {
    var entries =
        List.of(
            entry("10", "10", "plugin10", false, false),
            entry("2", "2", "plugin2", true, false),
            entry("built", "built", "plugin1", true, true),
            entry("else", "else", "different", false, false));
    var rows =
        PluginListPresenter.rows(
            entries, List.of(), " PLUGIN ", true, PluginSort.Mode.NAME_ASC, Set.of("2"));
    assertEquals(List.of("plugin2", "plugin10"), rows.stream().map(row -> row.entry.name).toList());
    assertTrue(rows.get(0).expanded);
  }

  @Test
  public void enabledSortUsesObservedRatherThanPending() {
    var entries =
        List.of(entry("a", "a", "alpha", false, false), entry("z", "z", "zulu", true, false));
    var rows =
        PluginListPresenter.rows(
            entries,
            List.of(operation("op", "a", true, "QUEUED", -1, true)),
            "",
            false,
            PluginSort.Mode.ENABLED_FIRST,
            Set.of());
    assertEquals("zulu", rows.get(0).entry.name);
  }

  @Test
  public void expansionUsesStableIdentityAndHasDedicatedPayload() {
    var entries =
        List.of(entry("a", "a", "same", true, false), entry("b", "b", "same", true, false));
    var before = rows(entries, List.of());
    var after =
        PluginListPresenter.rows(
            entries, List.of(), "", false, PluginSort.Mode.NAME_ASC, Set.of("b"));
    assertFalse(after.get(0).expanded);
    assertEquals(0, PluginListPresenter.changes(before.get(0), after.get(0)));
    assertEquals(
        PluginListPresenter.EXPANSION, PluginListPresenter.changes(before.get(1), after.get(1)));
  }

  @Test
  public void distinguishesNoSnapshotLoadingFailureAndRealZero() {
    assertEquals(
        PluginListPresenter.EmptyState.INITIAL,
        PluginListPresenter.emptyState(false, false, "", 0, 0));
    assertEquals(
        PluginListPresenter.EmptyState.LOADING,
        PluginListPresenter.emptyState(false, true, "", 0, 0));
    assertEquals(
        PluginListPresenter.EmptyState.FAILED,
        PluginListPresenter.emptyState(false, false, "failed", 0, 0));
    assertEquals(
        PluginListPresenter.EmptyState.EMPTY,
        PluginListPresenter.emptyState(true, false, "", 0, 0));
    assertEquals(
        PluginListPresenter.EmptyState.FILTERED,
        PluginListPresenter.emptyState(true, false, "", 4, 0));
  }

  @Test
  public void failedRefreshKeepsCachedRowsAndDoesNotClaimConfirmedZero() {
    assertEquals(
        PluginListPresenter.EmptyState.CONTENT,
        PluginListPresenter.emptyState(true, false, "failed", 2, 2));
    assertEquals(
        PluginListPresenter.EmptyState.FAILED,
        PluginListPresenter.emptyState(true, false, "failed", 0, 0));
  }

  @Test
  public void anchorFollowsStableKeyAcrossInsertionsAndSort() {
    var rows =
        rows(
            List.of(entry("a", "a", "alpha", true, false), entry("b", "b", "beta", true, false)),
            List.of());
    var anchor = new PluginListPresenter.Anchor("b", 1, -17);
    assertEquals(2, PluginListPresenter.anchorPosition(anchor, rows));
    assertEquals(-17, anchor.offset);
    assertEquals(
        0,
        PluginListPresenter.anchorPosition(
            new PluginListPresenter.Anchor(PluginListPresenter.HEADER_KEY, 0, 2), rows));
    assertEquals(
        3,
        PluginListPresenter.anchorPosition(
            new PluginListPresenter.Anchor(PluginListPresenter.FOOTER_KEY, 1, 2), rows));
  }

  @Test
  public void removedAnchorClampsToFooterAndNotificationsSkipHeader() {
    var rows = rows(List.of(entry("a", "a", "alpha", true, false)), List.of());
    assertEquals(
        2,
        PluginListPresenter.anchorPosition(new PluginListPresenter.Anchor("removed", 8, 4), rows));
    assertEquals(1, PluginListPresenter.adapterPosition(0));
    assertEquals(5, PluginListPresenter.adapterPosition(4));
  }
}
