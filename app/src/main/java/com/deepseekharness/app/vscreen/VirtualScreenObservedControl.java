package com.deepseekharness.app.vscreen;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.deepseekharness.app.capabilities.CapabilityRequest;
import com.deepseekharness.app.screen.ScreenRequestContext;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.json.JSONObject;

/** One semantic owner of bounded agent observations; original pixel evidence is never relabeled. */
@androidx.annotation.RequiresApi(30)
final class VirtualScreenObservedControl {
  interface Click {
    boolean perform(
        AccessibilityNodeInfo node,
        AccessibilityNodeInfo root,
        ScreenRequestContext context,
        boolean[] submitted,
        java.util.function.BooleanSupplier leaseCurrent);
  }

  private static final long TTL_MS = 30_000;
  private final LinkedHashMap<String, Observation> observations = new LinkedHashMap<>();
  private long metadataBytes;
  private final java.util.concurrent.locks.ReentrantLock lock =
      new java.util.concurrent.locks.ReentrantLock();
  private final java.util.concurrent.atomic.AtomicLong invalidation =
      new java.util.concurrent.atomic.AtomicLong();
  private long clearedVersion;
  private int readers;

  private static final class Owner {
    final CapabilityRequest original;
    final long consent, managerEpoch, managerRevision;
    final String core, generation;
    final int display, window;
    final String packageName;

    Owner(ScreenRequestContext context, JSONObject frame) throws Exception {
      original = context.request();
      VscreenTargetEvidence target = (VscreenTargetEvidence) original.target;
      consent = target.consentRevision;
      managerEpoch = frame.getLong("managerEpoch");
      managerRevision = frame.getLong("managerRevision");
      core = frame.getString("coreInstance");
      generation = frame.getString("generation");
      display = target.displayId;
      window = target.windowId;
      packageName = target.packageName;
      if (consent < 0
          || !core.equals(target.coreInstance)
          || frame.getInt("displayId") != display
          || !packageName.equals(frame.getString("package"))
          || window < 0
          || packageName.isEmpty())
        throw new IllegalArgumentException("ANCHOR_IDENTITY_UNAVAILABLE");
    }

    boolean matches(ScreenRequestContext context, JSONObject args) {
      CapabilityRequest request = context.request();
      if (!(request.target instanceof VscreenTargetEvidence target)) return false;
      return context.serviceEpoch() >= 0
          && original.runtime.equals(request.runtime)
          && original.installInstanceId.equals(request.installInstanceId)
          && original.bridgeRunId.equals(request.bridgeRunId)
          && original.policyRevision == request.policyRevision
          && original.authorityEpoch == request.authorityEpoch
          && consent == target.consentRevision
          && core.equals(target.coreInstance)
          && generation.equals(target.generation)
          && display == target.displayId
          && window == target.windowId
          && packageName.equals(target.packageName)
          && core.equals(args.optString("coreInstance"))
          && generation.equals(args.optString("generation"))
          && managerEpoch == args.optLong("managerEpoch", -1)
          && managerRevision == args.optLong("managerRevision", -1);
    }
  }

  private static final class Observation {
    final String id;
    final Owner owner;
    final VirtualScreenAnchorProof proof;
    final VirtualScreenAccessibility.ObservedPixels pixels;
    final long source, created, version;
    final int width, height;
    String anchor = "";
    VirtualScreenAnchorProof.Candidate selected;
    boolean consumed, released;
    volatile boolean expired;
    java.util.concurrent.ScheduledFuture<?> expiry;

    Observation(
        JSONObject frame,
        ScreenRequestContext context,
        VirtualScreenAnchorProof proof,
        VirtualScreenAccessibility.ObservedPixels pixels,
        long version)
        throws Exception {
      this.version = version;
      id = frame.getString("observationId");
      owner = new Owner(context, frame);
      source = frame.getLong("sourceFrameSeq");
      width = frame.getInt("width");
      height = frame.getInt("height");
      this.proof = proof;
      this.pixels = pixels;
      created = frame.getLong("observationCreatedElapsedMillis");
      if (source < 1 || !uuid(id) || width <= 0 || height <= 0)
        throw new IllegalArgumentException("ANCHOR_IDENTITY_UNAVAILABLE");
    }
  }

  JSONObject run(
      AccessibilityService service,
      int display,
      String operation,
      JSONObject args,
      ScreenRequestContext context,
      VirtualScreenAccessibility.ObservedPixels pixels,
      Click click) {
    lock.lock();
    if (clearedVersion != invalidation.get()) clearLocked();
    long version = invalidation.get();
    readers++;
    try {
      JSONObject result = runLocked(service, display, operation, args, context, pixels, click);
      if (operation.equals("anchored-click")) result = clickMetadata(result, args);
      if (version != invalidation.get()) {
        if (operation.equals("see") && result.optBoolean("ok"))
          return unavailable(result, "OBSERVATION_EXPIRED");
        if (operation.equals("anchored-click")
            && (result.optBoolean("ok") || result.optBoolean("executionUnknown")))
          return fail("INPUT_RESULT_UNKNOWN")
              .put("executionUnknown", true)
              .put("mode", "ANCHORED_NODE")
              .put("sourceFrameSeq", args.optLong("frameSeq", -1))
              .put("validatedCurrentSeq", result.optLong("validatedCurrentSeq", -1));
        return operation.equals("anchored-click")
            ? clickMetadata(fail("ANCHOR_EXPIRED"), args)
            : fail("ANCHOR_EXPIRED");
      }
      return result;
    } catch (Exception unavailable) {
      return operation.equals("anchored-click")
          ? clickMetadata(fail("ANCHOR_NOT_AVAILABLE"), args)
          : fail("ANCHOR_NOT_AVAILABLE");
    } finally {
      readers--;
      if (readers == 0) {
        prune();
        if (clearedVersion != invalidation.get()) clearLocked();
      }
      lock.unlock();
    }
  }

  private JSONObject runLocked(
      AccessibilityService service,
      int display,
      String operation,
      JSONObject args,
      ScreenRequestContext context,
      VirtualScreenAccessibility.ObservedPixels pixels,
      Click click) {
    prune();
    try {
      if (context == null
          || !(context.request().target instanceof VscreenTargetEvidence)
          || !context.current()
          || context.target().displayId != display) return fail("ANCHOR_IDENTITY_UNAVAILABLE");
      if (operation.equals("see")) return see(service, context, pixels);
      if (operation.equals("anchor")) return select(context, args);
      if (operation.equals("anchored-click")) {
        JSONObject result = click(service, context, args, pixels, click);
        result.put("mode", "ANCHORED_NODE");
        if (!result.has("sourceFrameSeq"))
          result.put("sourceFrameSeq", args.optLong("frameSeq", -1));
        if (!result.has("validatedCurrentSeq")) result.put("validatedCurrentSeq", -1);
        return result;
      }
      return fail("UNKNOWN_ROUTE");
    } catch (Exception unavailable) {
      return fail(
          unavailable instanceof VirtualScreenAnchorProof.Unavailable known
              ? known.reason
              : "ANCHOR_NOT_AVAILABLE");
    }
  }

  private JSONObject see(
      AccessibilityService service,
      ScreenRequestContext context,
      VirtualScreenAccessibility.ObservedPixels pixels)
      throws Exception {
    if (!context.claimDispatch()) return fail("SCREEN_TARGET_CHANGED");
    VirtualScreenAnchorProof before = null, after = null;
    JSONObject frame = null;
    Observation retained = null;
    String capturedId = "";
    String reason = "ANCHOR_NOT_AVAILABLE";
    try {
      try {
        before = VirtualScreenAnchorProof.capture(service, context);
      } catch (Exception unavailable) {
        reason =
            unavailable instanceof VirtualScreenAnchorProof.Unavailable known
                ? known.reason
                : "ANCHOR_TREE_UNAVAILABLE";
      }
      frame = pixels.capture(before != null);
      capturedId = frame.optString("observationId");
      if (!frame.optBoolean("ok")) return frame;
      if (before == null || !frame.has("observationId")) {
        reason =
            before == null
                ? reason
                : frame.optString("availabilityReason", "OBSERVATION_NOT_AVAILABLE");
        return unavailable(frame, reason);
      }
      after = VirtualScreenAnchorProof.capture(service, context);
      if (!before.sameAs(after) || !context.current())
        return unavailable(frame, "ANCHOR_TREE_CHANGED");
      if (before.candidates.isEmpty()) return unavailable(frame, "ANCHOR_NODE_UNAVAILABLE");
      while (!observations.isEmpty()
          && (observations.size() >= 2
              || metadataBytes + before.bytes > VirtualScreenAnchorProof.METADATA_LIMIT))
        remove(observations.values().iterator().next());
      if (before.bytes > VirtualScreenAnchorProof.METADATA_LIMIT)
        return unavailable(frame, "ANCHOR_TREE_BUDGET");
      retained = new Observation(frame, context, before, pixels, invalidation.get());
      if (expired(retained)) {
        retained = null;
        return unavailable(frame, "OBSERVATION_EXPIRED");
      }
      observations.put(retained.id, retained);
      metadataBytes += before.bytes;
      Observation exact = retained;
      retained.expiry =
          pixels.expireAfter(
              () -> expire(exact),
              Math.max(0, TTL_MS - (android.os.SystemClock.elapsedRealtime() - exact.created) + 1));
      before = null; // the observation exclusively owns this complete proof and candidate handles
      return frame
          .put("anchoredAvailable", true)
          .put("availabilityReason", "AVAILABLE")
          .put("anchorCandidateCount", retained.proof.candidates.size());
    } catch (Exception unavailable) {
      if (retained != null) {
        remove(retained);
        retained = null;
        capturedId = "";
      }
      if (frame != null && frame.optBoolean("ok"))
        return unavailable(
            frame,
            unavailable instanceof VirtualScreenAnchorProof.Unavailable known
                ? known.reason
                : "ANCHOR_NOT_AVAILABLE");
      throw unavailable;
    } finally {
      if (after != null) after.close();
      if (before != null) before.close();
      if (retained == null && !capturedId.isEmpty()) pixels.release(capturedId);
    }
  }

  private JSONObject select(ScreenRequestContext context, JSONObject args) throws Exception {
    String id = args.optString("observationId");
    Observation observation = observations.get(id);
    if (observation == null || expired(observation)) return fail("OBSERVATION_EXPIRED");
    VscreenTargetEvidence target = (VscreenTargetEvidence) context.request().target;
    if (!observation.owner.matches(context, args)
        || context.serviceEpoch() != observation.proof.connectionEpoch
        || observation.source != args.optLong("frameSeq", -1)
        || !target.matchesObservedAnchor(
            observation.owner.core,
            observation.owner.generation,
            observation.source,
            id,
            observation.owner.consent)) return fail("ANCHOR_IDENTITY_CHANGED");
    if (!observation.anchor.isEmpty()) return fail("ANCHOR_ALREADY_ISSUED");
    int x = args.getInt("x"), y = args.getInt("y");
    var selected = observation.proof.select(x, y, observation.width, observation.height);
    if (expired(observation)) return fail("OBSERVATION_EXPIRED");
    if (!context.claimDispatch()) return fail("SCREEN_TARGET_CHANGED");
    if (expired(observation)) return fail("OBSERVATION_EXPIRED");
    observation.selected = selected;
    observation.anchor = UUID.randomUUID().toString();
    JSONObject result =
        new JSONObject()
            .put("ok", true)
            .put("anchorId", observation.anchor)
            .put("sourceFrameSeq", observation.source)
            .put("mode", "ANCHORED_NODE");
    return expired(observation) ? fail("OBSERVATION_EXPIRED") : result;
  }

  private JSONObject click(
      AccessibilityService service,
      ScreenRequestContext context,
      JSONObject args,
      VirtualScreenAccessibility.ObservedPixels pixels,
      Click click)
      throws Exception {
    Observation observation = null;
    String id = args.optString("anchorId");
    for (Observation candidate : observations.values())
      if (candidate.anchor.equals(id) && !id.isEmpty()) {
        observation = candidate;
        break;
      }
    if (observation == null || observation.consumed || expired(observation))
      return fail("ANCHOR_EXPIRED");
    // First send consumes the exact lease, including a later refusal or unknown result.
    observation.consumed = true;
    boolean[] submitted = {false};
    long validated = -1;
    try {
      VscreenTargetEvidence target = (VscreenTargetEvidence) context.request().target;
      if (!observation.owner.matches(context, args)
          || observation.source != args.optLong("frameSeq", -1)
          || !target.matchesAnchored(
              observation.owner.core,
              observation.owner.generation,
              observation.source,
              id,
              observation.owner.consent)) return fail("ANCHOR_IDENTITY_CHANGED");
      try (VirtualScreenAnchorProof live = VirtualScreenAnchorProof.capture(service, context)) {
        if (!observation.proof.sameAs(live)) return fail("ANCHOR_TREE_CHANGED");
        var original = observation.selected;
        if (original == null || !original.node.refresh()) return fail("ANCHOR_NODE_CHANGED");
        var selected = live.same(original);
        if (selected == null) return fail("ANCHOR_NODE_CHANGED");
        Rect region =
            VirtualScreenAnchorProof.region(original.bounds, observation.width, observation.height);
        if (!live.unobscured(region)) return fail("ANCHOR_OCCLUDED");
        JSONObject check = pixels.region(observation.id, region);
        if (!check.optBoolean("ok")) return check;
        if (check.optLong("sourceFrameSeq", -1) != observation.source
            || !observation.owner.generation.equals(check.optString("generation"))
            || check.optInt("displayId", -1) != observation.owner.display)
          return fail("ANCHOR_IDENTITY_CHANGED");
        validated = check.getLong("validatedCurrentSeq");
        if (observation.version != invalidation.get() || expired(observation))
          return fail("ANCHOR_EXPIRED");
        if (!context.current()) return fail("SCREEN_TARGET_CHANGED");
        Observation owned = observation;
        boolean accepted =
            click.perform(
                original.node,
                live.root,
                context,
                submitted,
                () -> !expired(owned) && owned.version == invalidation.get());
        JSONObject result =
            accepted ? new JSONObject().put("ok", true) : fail("NODE_ACTION_REJECTED");
        if (submitted[0] && !context.current())
          result = fail("INPUT_RESULT_UNKNOWN").put("executionUnknown", true);
        return result
            .put("mode", "ANCHORED_NODE")
            .put("sourceFrameSeq", observation.source)
            .put("validatedCurrentSeq", validated);
      }
    } catch (Throwable unknown) {
      JSONObject result =
          fail(
              submitted[0]
                  ? "INPUT_RESULT_UNKNOWN"
                  : expired(observation) ? "ANCHOR_EXPIRED" : "ANCHOR_NOT_AVAILABLE");
      if (submitted[0]) result.put("executionUnknown", true);
      return result
          .put("mode", "ANCHORED_NODE")
          .put("sourceFrameSeq", observation.source)
          .put("validatedCurrentSeq", validated);
    } finally {
      remove(observation);
    }
  }

  private static JSONObject clickMetadata(JSONObject result, JSONObject args) {
    try {
      result.put("mode", "ANCHORED_NODE");
      if (!result.has("sourceFrameSeq")) result.put("sourceFrameSeq", args.optLong("frameSeq", -1));
      if (!result.has("validatedCurrentSeq")) result.put("validatedCurrentSeq", -1);
    } catch (Exception ignored) {
    }
    return result;
  }

  private static JSONObject unavailable(JSONObject frame, String reason) throws Exception {
    frame.remove("observationId");
    if (!frame.has("sourceFrameSeq")) frame.put("sourceFrameSeq", frame.optLong("frameSeq", -1));
    return frame.put("anchoredAvailable", false).put("availabilityReason", reason);
  }

  private void expire(Observation exact) {
    exact.expired = true;
    // The timer never waits on an A11/Core reader and never invalidates a replacement owner.
    if (lock.isHeldByCurrentThread() || !lock.tryLock()) return;
    try {
      if (observations.get(exact.id) == exact) remove(exact);
    } finally {
      lock.unlock();
    }
  }

  private static boolean expired(Observation observation) {
    long now = android.os.SystemClock.elapsedRealtime();
    return observation.expired
        || observation.released
        || now < observation.created
        || now - observation.created > TTL_MS;
  }

  private void prune() {
    for (Observation observation : new java.util.ArrayList<>(observations.values()))
      if (expired(observation)) remove(observation);
  }

  private void remove(Observation exact) {
    if (observations.get(exact.id) == exact) {
      observations.remove(exact.id);
      metadataBytes -= exact.proof.bytes;
    }
    if (exact.released) return;
    exact.released = true;
    if (exact.expiry != null) {
      exact.expiry.cancel(false);
      exact.expiry = null;
    }
    exact.proof.close();
    try {
      exact.pixels.release(exact.id);
    } catch (RuntimeException unavailable) {
      // Local evidence stays invalid. The exact Core owner has its own raw-image TTL and teardown.
    }
  }

  void invalidate() {
    invalidation.incrementAndGet();
  }

  /** Never recycle a proof still in use, and never wait for a remote read on the stop thread. */
  void clear() {
    invalidate();
    if (lock.isHeldByCurrentThread() || !lock.tryLock()) return;
    try {
      clearLocked();
    } finally {
      lock.unlock();
    }
  }

  private void clearLocked() {
    for (Observation observation : new java.util.ArrayList<>(observations.values()))
      remove(observation);
    clearedVersion = invalidation.get();
  }

  static boolean uuid(String id) {
    return id != null && id.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}");
  }

  private static JSONObject fail(String code) {
    return VirtualScreenTransport.failure(code, false);
  }
}
