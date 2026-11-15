package com.deepseekharness.app.plugins;

import static org.junit.Assert.*;

import com.deepseekharness.app.core.PluginRepository.Item;
import com.deepseekharness.app.core.PluginRepository.PendingReview;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class PluginRegistryTest {
  private final PluginRegistry registry = new PluginRegistry();

  private PluginSourceIdentity source(long generation) {
    return new PluginSourceIdentity(
        new File("/private/data"),
        "1:101",
        1,
        new File("/private/rootfs"),
        "1:102",
        1,
        "marker-sha256",
        generation);
  }

  private Item item(String name, boolean enabled) throws Exception {
    return new Item(
        new JSONObject()
            .put("name", name)
            .put("enabled", enabled)
            .put("available", true)
            .put("version", "1.0.0")
            .put("loadState", "active"));
  }

  private Item dynamic(String name) throws Exception {
    return new Item(
        new JSONObject()
            .put("name", name)
            .put("dynamic", true)
            .put("enabled", true)
            .put("sessionId", "full-session-a")
            .put("pluginId", "plugin-a")
            .put("generation", "7"));
  }

  private PendingReview pending() throws Exception {
    return new PendingReview(
        new JSONObject()
            .put("previewId", "0123456789abcdef0123456789abcdef")
            .put("names", new JSONArray().put("user-plugin")));
  }

  private void seed(PluginSourceIdentity source, List<Item> items) {
    assertTrue(registry.completeQuery(registry.beginQuery(source), items, List.of(), false, 100));
  }

  @Test
  public void unknownLoadingFailedAndConfirmedEmptyRemainDifferentStates() {
    PluginRegistry.Snapshot initial = registry.snapshot();
    assertFalse(initial.known);
    assertFalse(initial.loading);
    assertTrue(initial.stale);
    assertTrue(initial.items.isEmpty());
    PluginRegistry.Query query = registry.beginQuery(source(1));
    assertTrue(registry.snapshot().loading);
    assertFalse(registry.snapshot().known);
    assertTrue(registry.failQuery(query, "read failed"));
    assertEquals("read failed", registry.snapshot().error);
    assertFalse(registry.snapshot().known);
    assertFalse(registry.snapshot().loading);
    query = registry.beginQuery(source(1));
    assertTrue(registry.completeQuery(query, List.of(), List.of(), false, 123));
    assertTrue(registry.snapshot().known);
    assertFalse(registry.snapshot().stale);
    assertFalse(registry.snapshot().loading);
    assertEquals("", registry.snapshot().error);
    assertEquals(123, registry.snapshot().verifiedAtMillis);
    assertTrue(registry.snapshot().items.isEmpty());
    assertFalse(initial.known);
  }

  @Test
  public void snapshotsDefensivelyCopyListsAndKeepOldVersions() throws Exception {
    Item item = item("first", true);
    ArrayList<Item> input = new ArrayList<>(List.of(item));
    ArrayList<PendingReview> reviews = new ArrayList<>(List.of(pending()));
    PluginRegistry.Query query = registry.beginQuery(source(1));
    assertTrue(registry.completeQuery(query, input, reviews, true, 200));
    PluginRegistry.Snapshot old = registry.snapshot();
    input.clear();
    reviews.clear();
    assertEquals(List.of(item), old.items);
    assertEquals(1, old.pendingReviews.size());
    assertThrows(UnsupportedOperationException.class, () -> old.items.clear());
    assertThrows(UnsupportedOperationException.class, () -> old.pendingReviews.clear());
    registry.invalidate();
    assertTrue(registry.snapshot().version > old.version);
    assertFalse(old.stale);
    assertTrue(old.safeMode);
    assertEquals(200, old.verifiedAtMillis);
  }

  @Test
  public void failureRetainsKnownRowsWithoutClaimingFreshnessOrRequerying() throws Exception {
    seed(source(1), List.of(item("retained", false)));
    PluginRegistry.Snapshot old = registry.snapshot();
    PluginRegistry.Query query = registry.beginQuery(source(1));
    assertTrue(registry.snapshot().known);
    assertTrue(registry.snapshot().loading);
    assertTrue(registry.failQuery(query, "timeout"));
    PluginRegistry.Snapshot failed = registry.snapshot();
    assertSame(old.items, failed.items);
    assertTrue(failed.known);
    assertTrue(failed.stale);
    assertFalse(failed.loading);
    assertEquals(old.verifiedAtMillis, failed.verifiedAtMillis);
    assertEquals("timeout", failed.error);
  }

  @Test
  public void anEventDuringScanSurvivesItsCompletionAndNextScanClearsIt() throws Exception {
    PluginRegistry.Query first = registry.beginQuery(source(1));
    long event = registry.invalidate();
    registry.invalidate();
    assertTrue(registry.completeQuery(first, List.of(item("old", false)), List.of(), false, 10));
    assertTrue(registry.snapshot().stale);
    assertTrue(registry.snapshot().invalidationSeq >= event);
    PluginRegistry.Query next = registry.beginQuery(source(1));
    assertTrue(registry.completeQuery(next, List.of(item("new", false)), List.of(), false, 20));
    assertFalse(registry.snapshot().stale);
    assertEquals("new", registry.snapshot().items.get(0).name);
  }

  @Test
  public void sourceChangeRejectsOldSuccessAndErrorEvenAfterReturningToSameSource()
      throws Exception {
    PluginSourceIdentity a = source(1);
    PluginSourceIdentity b =
        new PluginSourceIdentity(
            a.dataRoot,
            "1:201",
            a.dataRootDevice,
            a.rootfs,
            a.rootfsKey,
            a.rootfsDevice,
            a.runtimeMarkerSha256,
            2);
    seed(a, List.of(item("old", false)));
    PluginRegistry.Query oldQuery = registry.beginQuery(a);
    assertTrue(registry.updateSource(b));
    assertFalse(registry.snapshot().known);
    assertTrue(registry.snapshot().items.isEmpty());
    assertEquals(0, registry.snapshot().verifiedAtMillis);
    assertFalse(registry.snapshot().loading);
    assertTrue(registry.updateSource(a));
    PluginRegistry.Query current = registry.beginQuery(a);
    assertFalse(registry.completeQuery(oldQuery, List.of(), List.of(), false));
    assertFalse(registry.failQuery(oldQuery, "late failure"));
    assertTrue(registry.snapshot().loading);
    assertEquals("", registry.snapshot().error);
    assertTrue(registry.completeQuery(current, List.of(), List.of(), false));
  }

  @Test
  public void queryTicketsAreSingleUseAndRegistryScoped() {
    PluginSourceIdentity source = source(1);
    PluginRegistry.Query query = registry.beginQuery(source);
    assertSame(query, registry.beginQuery(source));
    PluginRegistry other = new PluginRegistry();
    other.beginQuery(source);
    assertFalse(other.completeQuery(query, List.of(), List.of(), false));
    assertTrue(registry.completeQuery(query, List.of(), List.of(), false));
    PluginRegistry.Snapshot finished = registry.snapshot();
    assertFalse(registry.completeQuery(query, List.of(), List.of(), false));
    assertFalse(registry.failQuery(query, "duplicate"));
    assertSame(finished, registry.snapshot());
  }

  @Test
  public void confirmedTargetUsesStableKeyAndPreservesSameNamedDynamicRow() throws Exception {
    PluginSourceIdentity source = source(1);
    Item installed = item("same-name", false), transientItem = dynamic("same-name");
    seed(source, List.of(installed, transientItem, item("unrelated", true)));
    PluginRegistry.Query staleQuery = registry.beginQuery(source);
    Item confirmed = installed.withIntent(true);
    assertTrue(registry.updateConfirmedTarget(source, installed.stableKey, confirmed));
    PluginRegistry.Snapshot snapshot = registry.snapshot();
    assertSame(confirmed, snapshot.items.get(0));
    assertSame(transientItem, snapshot.items.get(1));
    assertTrue(snapshot.items.get(0).enabled);
    assertTrue(snapshot.stale);
    assertFalse(snapshot.loading);
    assertFalse(registry.completeQuery(staleQuery, List.of(installed), List.of(), false));
    assertFalse(registry.updateConfirmedTarget(source, transientItem.stableKey, transientItem));
    assertFalse(registry.updateConfirmedTarget(source(2), installed.stableKey, confirmed));
    assertFalse(registry.updateConfirmedTarget(source, "other-key", confirmed));
    assertSame(snapshot, registry.snapshot());
  }

  @Test
  public void duplicatePersistentIdentityCannotBeUpdatedAmbiguously() throws Exception {
    Item first = item("duplicate", false), second = item("duplicate", false);
    seed(source(1), List.of(first, second));
    PluginRegistry.Snapshot old = registry.snapshot();
    assertFalse(registry.updateConfirmedTarget(source(1), first.stableKey, first.withIntent(true)));
    assertSame(old, registry.snapshot());
  }

  @Test
  public void confirmedListRetiresEarlierScanAndPreservesOtherSnapshotFacts() throws Exception {
    PluginSourceIdentity source = source(1);
    assertTrue(
        registry.completeQuery(
            registry.beginQuery(source),
            List.of(item("deleted", true)),
            List.of(pending()),
            true,
            50));
    PluginRegistry.Snapshot old = registry.snapshot();
    PluginRegistry.Query query = registry.beginQuery(source);
    ArrayList<Item> receipt = new ArrayList<>(List.of(item("survivor", false)));
    assertTrue(registry.recordConfirmedItems(source, receipt));
    receipt.clear();
    assertEquals("survivor", registry.snapshot().items.get(0).name);
    assertSame(old.pendingReviews, registry.snapshot().pendingReviews);
    assertTrue(registry.snapshot().safeMode);
    assertTrue(registry.snapshot().stale);
    assertEquals(old.verifiedAtMillis, registry.snapshot().verifiedAtMillis);
    assertFalse(registry.completeQuery(query, old.items, List.of(), false));
    assertFalse(registry.recordConfirmedItems(source(2), List.of()));
  }

  @Test
  public void webOnlyChangeKeepsVerifiedPersistentRowsAndClearsOldRuntimeEvidence()
      throws Exception {
    PluginSourceIdentity oldSource = source(1), newSource = source(2);
    Item installed = item("persistent", true).withRuntime("active", 1);
    Item transientItem = dynamic("temporary").withRuntime("active", 1);
    assertTrue(
        registry.completeQuery(
            registry.beginQuery(oldSource),
            List.of(installed, transientItem),
            List.of(pending()),
            true,
            321));
    PluginRegistry.Snapshot previous = registry.snapshot();
    PluginRegistry.Query query = registry.beginQuery(oldSource);
    assertTrue(registry.updateSource(newSource));
    PluginRegistry.Snapshot current = registry.snapshot();
    assertTrue(current.known);
    assertTrue(current.stale);
    assertFalse(current.loading);
    assertEquals(321, current.verifiedAtMillis);
    assertEquals(1, current.items.size());
    assertEquals(installed.stableKey, current.items.get(0).stableKey);
    assertTrue(current.items.get(0).enabled);
    assertEquals("unknown", current.items.get(0).runtimeState);
    assertEquals(-1, current.items.get(0).runtimeGeneration);
    assertSame(previous.pendingReviews, current.pendingReviews);
    assertTrue(current.safeMode);
    assertFalse(registry.completeQuery(query, previous.items, List.of(), false));
    assertTrue(registry.updateSource(oldSource));
    assertFalse(registry.completeQuery(query, previous.items, List.of(), false));
    assertTrue(registry.snapshot().known);
  }

  @Test
  public void runtimeReplacementRetainsOnlyStaleDisplayRowsAndRejectsOldEvidence()
      throws Exception {
    PluginSourceIdentity original = source(7);
    for (PluginSourceIdentity replacement :
        List.of(
            new PluginSourceIdentity(
                original.dataRoot,
                original.dataRootKey,
                original.dataRootDevice,
                original.rootfs,
                original.rootfsKey,
                original.rootfsDevice,
                "new-marker",
                7),
            new PluginSourceIdentity(
                original.dataRoot,
                original.dataRootKey,
                original.dataRootDevice,
                original.rootfs,
                "1:202",
                original.rootfsDevice,
                original.runtimeMarkerSha256,
                7),
            new PluginSourceIdentity(
                original.dataRoot,
                original.dataRootKey,
                original.dataRootDevice,
                new File("/private/next-rootfs"),
                original.rootfsKey,
                2,
                original.runtimeMarkerSha256,
                8))) {
      Item installed = item("persistent", true).withRuntime("active", 7);
      assertTrue(
          registry.completeQuery(
              registry.beginQuery(original),
              List.of(installed, dynamic("temporary").withRuntime("active", 7)),
              List.of(pending()),
              true,
              321));
      PluginRegistry.Snapshot previous = registry.snapshot();
      PluginRegistry.Query oldQuery = registry.beginQuery(original);

      assertTrue(original.sameUserDataRoot(replacement));
      assertFalse(original.sameDataSource(replacement));
      assertFalse(original.equals(replacement));
      assertTrue(registry.updateSource(replacement));
      PluginRegistry.Snapshot retained = registry.snapshot();
      assertEquals(replacement, retained.source);
      assertTrue(retained.known);
      assertTrue(retained.stale);
      assertFalse(retained.loading);
      assertEquals(321, retained.verifiedAtMillis);
      assertEquals(1, retained.items.size());
      assertEquals(installed.stableKey, retained.items.get(0).stableKey);
      assertTrue(retained.items.get(0).enabled);
      assertEquals("unknown", retained.items.get(0).runtimeState);
      assertEquals(-1, retained.items.get(0).runtimeGeneration);
      assertSame(previous.pendingReviews, retained.pendingReviews);
      assertTrue(retained.safeMode);
      assertEquals("active", previous.items.get(0).runtimeState);
      assertEquals(2, previous.items.size());
      assertFalse(registry.completeQuery(oldQuery, previous.items, List.of(), false));
      assertFalse(registry.failQuery(oldQuery, "late failure"));
      assertFalse(registry.updateRuntimeItems(original, previous.items));
      assertFalse(registry.recordConfirmedItems(original, List.of()));
      assertFalse(registry.updateConfirmedTarget(original, installed.stableKey, installed));
      registry.failSource("new runtime unavailable");
      assertSame(retained.items, registry.snapshot().items);
      assertTrue(registry.updateSource(original));
      assertFalse(registry.completeQuery(oldQuery, previous.items, List.of(), false));
    }
  }

  @Test
  public void displayRetentionRequiresUserDataRootPathInodeAndDevice() throws Exception {
    PluginSourceIdentity original = source(7);
    assertFalse(original.sameUserDataRoot(null));
    for (PluginSourceIdentity replacement :
        List.of(
            new PluginSourceIdentity(
                new File("/private/other-data"),
                original.dataRootKey,
                original.dataRootDevice,
                original.rootfs,
                original.rootfsKey,
                original.rootfsDevice,
                original.runtimeMarkerSha256,
                7),
            new PluginSourceIdentity(
                original.dataRoot,
                "1:201",
                original.dataRootDevice,
                original.rootfs,
                original.rootfsKey,
                original.rootfsDevice,
                original.runtimeMarkerSha256,
                7),
            new PluginSourceIdentity(
                original.dataRoot,
                original.dataRootKey,
                2,
                original.rootfs,
                original.rootfsKey,
                original.rootfsDevice,
                original.runtimeMarkerSha256,
                7))) {
      assertTrue(
          registry.completeQuery(
              registry.beginQuery(original),
              List.of(item("private", true)),
              List.of(pending()),
              true,
              321));
      assertFalse(original.sameUserDataRoot(replacement));
      assertFalse(original.sameDataSource(replacement));
      assertTrue(registry.updateSource(replacement));
      PluginRegistry.Snapshot empty = registry.snapshot();
      assertFalse(empty.known);
      assertTrue(empty.stale);
      assertFalse(empty.loading);
      assertTrue(empty.items.isEmpty());
      assertTrue(empty.pendingReviews.isEmpty());
      assertFalse(empty.safeMode);
      assertEquals(0, empty.verifiedAtMillis);
    }
  }

  @Test
  public void knownWebGenerationRevokesOldEvidenceBeforeUnavailableSourceProbe() throws Exception {
    PluginSourceIdentity original = source(7);
    Item installed = item("persistent", true).withRuntime("active", 7);
    seed(original, List.of(installed, dynamic("temporary").withRuntime("active", 7)));
    PluginRegistry.Query oldQuery = registry.beginQuery(original);
    assertTrue(registry.updateWebGeneration(8));
    PluginRegistry.Snapshot revoked = registry.snapshot();
    assertEquals(source(8), revoked.source);
    assertTrue(original.sameDataSource(revoked.source));
    assertTrue(revoked.known);
    assertTrue(revoked.stale);
    assertFalse(revoked.loading);
    assertEquals(1, revoked.items.size());
    assertEquals("unknown", revoked.items.get(0).runtimeState);
    assertEquals(-1, revoked.items.get(0).runtimeGeneration);
    registry.failSource("Web 正在切换运行状态");
    assertSame(revoked.items, registry.snapshot().items);
    assertFalse(registry.completeQuery(oldQuery, List.of(installed), List.of(), false));
    assertFalse(registry.updateRuntimeItems(original, List.of(installed)));
    assertFalse(registry.failQuery(oldQuery, "late old-generation failure"));
    PluginRegistry.Snapshot unavailable = registry.snapshot();
    assertFalse(registry.updateWebGeneration(8));
    assertSame(unavailable, registry.snapshot());
  }

  @Test
  public void webGenerationCannotInventSourceOrKnownCatalog() {
    PluginRegistry.Snapshot initial = registry.snapshot();
    assertFalse(registry.updateWebGeneration(7));
    assertSame(initial, registry.snapshot());
    registry.updateSource(source(7));
    assertTrue(registry.updateWebGeneration(8));
    assertFalse(registry.snapshot().known);
    assertEquals(0, registry.snapshot().verifiedAtMillis);
    assertTrue(registry.snapshot().items.isEmpty());
  }

  @Test
  public void runtimeProjectionPreservesScanTicketDirtySequenceAndVerificationTime()
      throws Exception {
    PluginSourceIdentity source = source(7);
    Item installed = item("persistent", false);
    seed(source, List.of(installed));
    PluginRegistry.Query scan = registry.beginQuery(source);
    registry.invalidate();
    PluginRegistry.Snapshot before = registry.snapshot();
    ArrayList<Item> projection =
        new ArrayList<>(
            List.of(
                installed.withRuntime("active", 7), dynamic("temporary").withRuntime("active", 7)));
    assertTrue(registry.updateRuntimeItems(source, projection));
    projection.clear();
    PluginRegistry.Snapshot projected = registry.snapshot();
    assertEquals(before.version + 1, projected.version);
    assertEquals(before.invalidationSeq, projected.invalidationSeq);
    assertEquals(before.verifiedAtMillis, projected.verifiedAtMillis);
    assertEquals(before.known, projected.known);
    assertEquals(before.stale, projected.stale);
    assertEquals(before.error, projected.error);
    assertTrue(projected.loading);
    assertEquals(2, projected.items.size());
    assertThrows(UnsupportedOperationException.class, () -> projected.items.clear());
    assertTrue(registry.completeQuery(scan, List.of(installed), List.of(), false, 200));
    assertTrue(registry.snapshot().stale);
  }

  @Test
  public void identicalRuntimeProjectionDoesNotPublishAnotherVersion() throws Exception {
    PluginSourceIdentity source = source(7);
    Item installed = item("persistent", false);
    seed(source, List.of(installed));
    List<Item> projection =
        List.of(installed.withRuntime("active", 7), dynamic("temporary").withRuntime("active", 7));
    assertTrue(registry.updateRuntimeItems(source, projection));
    PluginRegistry.Snapshot projected = registry.snapshot();
    assertFalse(
        registry.updateRuntimeItems(
            source,
            List.of(
                item("persistent", false).withRuntime("active", 7),
                dynamic("temporary").withRuntime("active", 7))));
    assertSame(projected, registry.snapshot());
  }

  @Test
  public void runtimeProjectionCannotUndoNewerIntentOrInventPersistentInstallation()
      throws Exception {
    PluginSourceIdentity source = source(7);
    Item installed = item("persistent", false);
    seed(source, List.of(installed));
    assertTrue(
        registry.updateConfirmedTarget(source, installed.stableKey, installed.withIntent(true)));
    registry.failSource("retained failure");
    PluginRegistry.Snapshot before = registry.snapshot();
    assertTrue(registry.updateRuntimeItems(source, List.of(installed.withRuntime("active", 7))));
    PluginRegistry.Snapshot projected = registry.snapshot();
    assertTrue(projected.items.get(0).enabled);
    assertTrue(projected.items.get(0).restartRequired);
    assertEquals("retained failure", projected.error);
    assertEquals(before.invalidationSeq, projected.invalidationSeq);
    assertEquals(before.stale, projected.stale);
    assertEquals(before.verifiedAtMillis, projected.verifiedAtMillis);
    assertFalse(registry.updateRuntimeItems(source, List.of(item("unseen", true))));
    assertFalse(registry.updateRuntimeItems(source(8), List.of()));
    assertFalse(
        registry.updateRuntimeItems(source, List.of(dynamic("old").withRuntime("active", 6))));
    assertSame(projected, registry.snapshot());
  }

  @Test
  public void runtimeProjectionPublishesStalenessWithoutDiscardingSameGenerationState()
      throws Exception {
    PluginSourceIdentity source = source(7);
    Item installed = item("persistent", true).withRuntime("loaded", 7, false);
    Item temporary = dynamic("temporary").withRuntime("active", 7, false);
    seed(source, List.of(installed, temporary));
    PluginRegistry.Snapshot before = registry.snapshot();
    List<Item> stale =
        List.of(installed.withRuntime("loaded", 7, true), temporary.withRuntime("active", 7, true));
    assertTrue(registry.updateRuntimeItems(source, stale));
    PluginRegistry.Snapshot retained = registry.snapshot();
    assertEquals("loaded", retained.items.get(0).runtimeState);
    assertTrue(retained.items.get(0).runtimeStale);
    assertEquals("active", retained.items.get(1).runtimeState);
    assertTrue(retained.items.get(1).runtimeStale);
    assertEquals(before.verifiedAtMillis, retained.verifiedAtMillis);
    assertEquals(before.invalidationSeq, retained.invalidationSeq);
    assertFalse(retained.stale);
    assertFalse(registry.updateRuntimeItems(source, stale));
    assertSame(retained, registry.snapshot());
    assertTrue(registry.updateRuntimeItems(source, List.of(installed, temporary)));
    assertFalse(registry.snapshot().items.get(0).runtimeStale);
    assertFalse(registry.snapshot().items.get(1).runtimeStale);
  }

  @Test
  public void onlyFreshMatchingLoadEvidenceClearsPendingRestart() throws Exception {
    PluginSourceIdentity source = source(7);
    for (String state : List.of("loaded", "unloaded", "disabled")) {
      boolean enabled = "loaded".equals(state);
      Item installed = item("persistent", enabled).withIntent(enabled);
      seed(source, List.of(installed));
      assertTrue(
          registry.updateRuntimeItems(
              source, List.of(installed.withRuntime(state, 7, true).withRestartRequired(false))));
      assertTrue(registry.snapshot().items.get(0).restartRequired);
      assertTrue(
          registry.updateRuntimeItems(
              source, List.of(installed.withRuntime(state, 7, false).withRestartRequired(false))));
      assertFalse(registry.snapshot().items.get(0).restartRequired);
      assertTrue(
          registry.updateRuntimeItems(
              source,
              List.of(installed.withRuntime("unknown", 7, true).withRestartRequired(true))));
      assertTrue(registry.snapshot().items.get(0).restartRequired);
    }
  }

  @Test
  public void oldVersionOrOriginRuntimeProjectionCannotClaimCurrentInstallation() throws Exception {
    PluginSourceIdentity source = source(7);
    Item installed = item("persistent", true).withIntent(true);
    seed(source, List.of(installed));
    PluginRegistry.Snapshot before = registry.snapshot();
    for (JSONObject value :
        List.of(
            new JSONObject().put("name", "persistent").put("version", "0.9.0"),
            new JSONObject()
                .put("name", "persistent")
                .put("version", "1.0.0")
                .put("runtimeProvided", true))) {
      Item late = new Item(value.put("enabled", true)).withRuntime("loaded", 7, false);
      assertTrue(!installed.version.equals(late.version) || !installed.origin.equals(late.origin));
      assertFalse(registry.updateRuntimeItems(source, List.of(late)));
      assertSame(before, registry.snapshot());
      assertTrue(registry.snapshot().items.get(0).restartRequired);
    }
  }

  @Test
  public void newWebGenerationRetiresOldRestartHintWithoutClearingDesiredIntent() throws Exception {
    Item installed = item("persistent", false).withIntent(true).withRuntime("loaded", 7, false);
    seed(source(7), List.of(installed));
    assertTrue(registry.updateWebGeneration(8));
    Item retained = registry.snapshot().items.get(0);
    assertTrue(retained.enabled);
    assertTrue(retained.runtimeStale);
    assertEquals("unknown", retained.runtimeState);
    assertFalse(retained.restartRequired);
  }

  @Test
  public void runtimeProjectionDoesNotTurnUnknownIntoKnownInstallation() throws Exception {
    PluginSourceIdentity source = source(7);
    registry.updateSource(source);
    assertTrue(
        registry.updateRuntimeItems(
            source, List.of(dynamic("temporary").withRuntime("active", 7))));
    assertFalse(registry.snapshot().known);
    assertTrue(registry.snapshot().stale);
    assertEquals(0, registry.snapshot().verifiedAtMillis);
  }

  @Test
  public void sourceReadFailureKeepsOldKnownRowsAndRetiresItsQuery() throws Exception {
    seed(source(1), List.of(item("kept", true)));
    PluginRegistry.Query query = registry.beginQuery(source(1));
    registry.failSource("source unreadable");
    assertTrue(registry.snapshot().known);
    assertTrue(registry.snapshot().stale);
    assertFalse(registry.snapshot().loading);
    assertEquals("kept", registry.snapshot().items.get(0).name);
    assertEquals("source unreadable", registry.snapshot().error);
    assertFalse(registry.completeQuery(query, List.of(), List.of(), false));
  }
}
