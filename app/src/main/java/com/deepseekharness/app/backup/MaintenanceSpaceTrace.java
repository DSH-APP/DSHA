package com.deepseekharness.app.backup;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Stage samples describe observed free space; they do not calibrate a device peak-space estimate. */
public final class MaintenanceSpaceTrace {
  public enum Stage {
    PREFLIGHT,
    SNAPSHOT_START,
    SNAPSHOT_READY,
    SWITCHED,
    EXTRACTION_STARTED,
    EXTRACTION_READY,
    DATA_RESTORED,
    RUNTIME_READY,
    COMMITTED,
    RETIRED_CLEANUP,
    FAILED,
    ROLLED_BACK
  }

  public static final class Sample {
    public final Stage stage;
    public final long available, required, observedUsed, peakObservedUsed;

    private Sample(Stage stage, long available, long required, long baseline, long minimum) {
      this.stage = stage;
      this.available = available;
      this.required = required;
      observedUsed = Math.max(0, baseline - available);
      peakObservedUsed = Math.max(0, baseline - minimum);
    }

    public String record() {
      return "stage="
          + stage.name()
          + " availableBytes="
          + available
          + " requiredBytes="
          + required
          + " observedUsedBytes="
          + observedUsed
          + " peakObservedUsedBytes="
          + peakObservedUsed;
    }
  }

  private final LongSupplier available;
  private final Consumer<Sample> observer;
  private long baseline = -1, minimum = Long.MAX_VALUE;

  public MaintenanceSpaceTrace(LongSupplier available, Consumer<Sample> observer) {
    this.available = Objects.requireNonNull(available);
    this.observer = Objects.requireNonNull(observer);
  }

  public Sample sample(Stage stage, long required) {
    Objects.requireNonNull(stage);
    if (required < 0) throw new IllegalArgumentException("SPACE_REQUIRED");
    long free = Math.max(0, available.getAsLong());
    if (baseline < 0) baseline = free;
    minimum = Math.min(minimum, free);
    Sample sample = new Sample(stage, free, required, baseline, minimum);
    observer.accept(sample);
    return sample;
  }
}
