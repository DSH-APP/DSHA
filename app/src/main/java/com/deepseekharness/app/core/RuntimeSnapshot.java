package com.deepseekharness.app.core;

import java.util.Objects;

/** Immutable authority identity only; the build170 controller remains the runtime owner. */
public final class RuntimeSnapshot {
  private RuntimeSnapshot() {}

  public static final class Epoch {
    public final String ownerId, instanceId;
    public final long generation;

    public Epoch(String ownerId, long generation, String instanceId) {
      this.ownerId = Objects.requireNonNull(ownerId);
      this.instanceId = Objects.requireNonNull(instanceId);
      if (generation < 0) throw new IllegalArgumentException("RUNTIME_EPOCH_GENERATION");
      this.generation = generation;
    }

    public boolean runningIdentity() {
      return !ownerId.isEmpty() && generation > 0 && !instanceId.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Epoch value
          && generation == value.generation
          && ownerId.equals(value.ownerId)
          && instanceId.equals(value.instanceId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ownerId, generation, instanceId);
    }
  }
}
