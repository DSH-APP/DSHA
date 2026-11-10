package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import com.deepseekharness.app.util.PluginCardState.Kind;
import com.deepseekharness.app.util.PluginCardState.Note;
import com.deepseekharness.app.util.PluginCardState.Tone;
import java.util.List;
import org.junit.Test;

public final class PluginCardStateTest {
  @Test
  public void kindFollowsHistoricalDescriptionPriority() {
    assertEquals(Kind.DYNAMIC, PluginCardState.kind(true, true, true, true, true));
    assertEquals(Kind.OFFICIAL, PluginCardState.kind(false, true, true, true, true));
    assertEquals(Kind.RUNTIME, PluginCardState.kind(false, false, true, true, true));
    assertEquals(Kind.BUILTIN, PluginCardState.kind(false, false, false, true, true));
    assertEquals(Kind.LOCATED, PluginCardState.kind(false, false, false, false, true));
    assertEquals(Kind.THIRD, PluginCardState.kind(false, false, false, false, false));
  }

  @Test
  public void staleOrUnknownRuntimeIsNeitherLoadedNorFailed() {
    assertTrue(PluginCardState.runtimeLoaded("loaded", false));
    assertTrue(PluginCardState.runtimeLoaded("active", false));
    assertFalse(PluginCardState.runtimeLoaded("loaded", true));
    assertFalse(PluginCardState.runtimeLoaded("unknown", false));
    assertFalse(PluginCardState.runtimeLoaded(null, false));
    assertTrue(PluginCardState.runtimeFailed("failed", false));
    assertFalse(PluginCardState.runtimeFailed("failed", true));
  }

  @Test
  public void pendingAndMissingWinOverRuntimeEvidence() {
    assertEquals(Tone.WARN, PluginCardState.tone(true, true, true, false, true, false));
    assertEquals(Tone.WARN, PluginCardState.tone(false, false, true, false, false, true));
  }

  @Test
  public void enabledToneDistinguishesLoadedFailedAndUnconfirmed() {
    assertEquals(Tone.GOOD, PluginCardState.tone(false, true, true, false, true, false));
    assertEquals(Tone.BAD, PluginCardState.tone(false, true, true, false, false, true));
    assertEquals(Tone.ACCENT, PluginCardState.tone(false, true, true, false, false, false));
    assertEquals(Tone.GOOD, PluginCardState.tone(false, true, false, true, true, false));
    assertEquals(Tone.MUTED, PluginCardState.tone(false, true, false, false, true, false));
  }

  @Test
  public void configBadgeToneSeparatesEnabledDisabledAndBlockingStates() {
    assertEquals(Tone.GOOD, PluginCardState.configTone(false, true, true, false, false, false));
    assertEquals(Tone.MUTED, PluginCardState.configTone(false, true, false, false, false, false));
    assertEquals(Tone.WARN, PluginCardState.configTone(true, true, true, false, false, false));
    assertEquals(Tone.WARN, PluginCardState.configTone(false, false, true, false, false, false));
    assertEquals(Tone.WARN, PluginCardState.configTone(false, true, true, false, false, true));
    assertEquals(Tone.ACCENT, PluginCardState.configTone(false, true, false, true, false, false));
    assertEquals(Tone.MUTED, PluginCardState.configTone(false, true, false, false, true, false));
  }

  @Test
  public void runtimeBadgeToneNeverTreatsUnknownAsLoadedOrFailed() {
    assertEquals(Tone.GOOD, PluginCardState.runtimeTone("loaded", false));
    assertEquals(Tone.GOOD, PluginCardState.runtimeTone("running", false));
    assertEquals(Tone.MUTED, PluginCardState.runtimeTone("stopped", false));
    assertEquals(Tone.MUTED, PluginCardState.runtimeTone("unloaded", false));
    assertEquals(Tone.ACCENT, PluginCardState.runtimeTone("loading", false));
    assertEquals(Tone.BAD, PluginCardState.runtimeTone("failed", false));
    assertEquals(Tone.WARN, PluginCardState.runtimeTone("unknown", false));
    assertEquals(Tone.WARN, PluginCardState.runtimeTone("loaded", true));
    assertEquals(Tone.WARN, PluginCardState.runtimeTone(null, false));
  }

  @Test
  public void notesKeepOrderAndHideUpdateWhilePending() {
    assertEquals(
        List.of(Note.RESTART, Note.UPDATE, Note.MESSAGE),
        PluginCardState.notes(true, true, false, true));
    assertEquals(List.of(Note.RESTART), PluginCardState.notes(true, true, true, false));
    assertEquals(List.of(), PluginCardState.notes(false, false, false, false));
  }
}
