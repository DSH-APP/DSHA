package com.deepseekharness.app.vscreen;

import com.deepseekharness.app.capabilities.WindowTargetEvidence;

/** 像素观察与一次语义节点分开携带；共同绑定core/display/窗口身份。 */
public final class VscreenTargetEvidence implements WindowTargetEvidence {
  public enum Observation {
    PIXEL,
    OBSERVED_ANCHOR,
    ANCHORED_NODE,
    NODE,
    EDITOR,
    DISPLAY
  }

  public final String coreInstance, generation, packageName, nodeOrEditorId;
  public final int displayId, windowId;
  public final long frameSequence, consentRevision;
  public final Observation observation;

  public VscreenTargetEvidence(
      String coreInstance,
      String generation,
      int displayId,
      int windowId,
      String packageName,
      Observation observation,
      long frameSequence,
      String nodeOrEditorId) {
    this(
        coreInstance,
        generation,
        displayId,
        windowId,
        packageName,
        observation,
        frameSequence,
        nodeOrEditorId,
        -1);
  }

  public VscreenTargetEvidence(
      String coreInstance,
      String generation,
      int displayId,
      int windowId,
      String packageName,
      Observation observation,
      long frameSequence,
      String nodeOrEditorId,
      long consentRevision) {
    this.coreInstance = java.util.Objects.requireNonNull(coreInstance);
    this.generation = java.util.Objects.requireNonNull(generation);
    this.displayId = displayId;
    this.windowId = windowId;
    this.packageName = java.util.Objects.requireNonNull(packageName);
    this.observation = java.util.Objects.requireNonNull(observation);
    this.frameSequence = frameSequence;
    this.consentRevision = consentRevision;
    this.nodeOrEditorId = java.util.Objects.requireNonNull(nodeOrEditorId);
    if (consentRevision < -1
        || consentRevision > com.deepseekharness.app.util.NativeConsentPolicy.MAX_REVISION)
      throw new IllegalArgumentException("INVALID_CONSENT_EVIDENCE");
    if ((observation == Observation.ANCHORED_NODE || observation == Observation.OBSERVED_ANCHOR)
        && (!uuid(coreInstance)
            || generation.isEmpty()
            || generation.length() > 80
            || displayId <= 0
            || windowId < 0
            || packageName.isEmpty()
            || packageName.length() > 200
            || frameSequence < 1
            || !uuid(nodeOrEditorId)
            || consentRevision < 0))
      throw new IllegalArgumentException("INVALID_ANCHORED_EVIDENCE");
  }

  /** A current validation frame must never replace the caller's original source observation. */
  public boolean matchesAnchored(
      String core,
      String sourceGeneration,
      long sourceFrameSequence,
      String anchorId,
      long expectedConsentRevision) {
    return matches(
        Observation.ANCHORED_NODE,
        core,
        sourceGeneration,
        sourceFrameSequence,
        anchorId,
        expectedConsentRevision);
  }

  public boolean matchesObservedAnchor(
      String core,
      String sourceGeneration,
      long sourceFrameSequence,
      String observationId,
      long expectedConsentRevision) {
    return matches(
        Observation.OBSERVED_ANCHOR,
        core,
        sourceGeneration,
        sourceFrameSequence,
        observationId,
        expectedConsentRevision);
  }

  private boolean matches(
      Observation expected,
      String core,
      String sourceGeneration,
      long sourceFrameSequence,
      String id,
      long expectedConsentRevision) {
    return observation == expected
        && coreInstance.equals(core)
        && generation.equals(sourceGeneration)
        && frameSequence == sourceFrameSequence
        && nodeOrEditorId.equals(id)
        && consentRevision == expectedConsentRevision;
  }

  private static boolean uuid(String value) {
    return value.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
  }

  public String kind() {
    return "vscreen/" + observation.name().toLowerCase(java.util.Locale.ROOT);
  }

  public int displayId() {
    return displayId;
  }

  public int windowId() {
    return windowId;
  }

  public String packageName() {
    return packageName;
  }
}
