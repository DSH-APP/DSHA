package com.deepseekharness.app.bridge;

import com.deepseekharness.app.device.DeviceTransportIdentity;
import com.deepseekharness.app.device.DeviceTransportResult;
import com.deepseekharness.app.util.AdbEnvironmentTask;
import com.deepseekharness.app.util.SensitiveData;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Native admission or a closed helper plus atomically withdrawn permit can prove no remote send. */
final class AdbVirtualScreenStart {
  interface Launch {
    String run(Boundary boundary) throws Exception;
  }

  interface TypedLaunch {
    HelperResult run(Boundary boundary) throws Exception;
  }

  /** Facts from native process ownership, never parsed from helper/remote text. */
  static final class HelperResult {
    final String output;
    final boolean localExitConfirmed, outputComplete;

    HelperResult(String output, boolean localExitConfirmed, boolean outputComplete) {
      this.output = output == null ? "" : output;
      this.localExitConfirmed = localExitConfirmed;
      this.outputComplete = outputComplete;
    }
  }

  static final class Boundary {
    private final BooleanSupplier cancelled;
    private boolean helperAttempted, cancelledBeforeHelper;

    private Boundary(BooleanSupplier cancelled) {
      this.cancelled = cancelled;
    }

    boolean enter() {
      if (helperAttempted || cancelledBeforeHelper) return false;
      if (cancelled.getAsBoolean()) {
        cancelledBeforeHelper = true;
        return false;
      }
      helperAttempted = true;
      return true;
    }
  }

  static DeviceTransportResult execute(
      String ticket, BooleanSupplier cancelled, Supplier<String> cancelledOutput, Launch launch) {
    return executeTyped(
        ticket,
        cancelled,
        cancelledOutput,
        boundary -> new HelperResult(launch.run(boundary), false, false),
        () -> false);
  }

  static DeviceTransportResult executeTyped(
      String ticket,
      BooleanSupplier cancelled,
      Supplier<String> cancelledOutput,
      TypedLaunch launch,
      BooleanSupplier withdrawUncommitted) {
    if (ticket == null || !ticket.matches("[a-f0-9]{48}"))
      return DeviceTransportResult.notSent(
          "adb",
          "VSCREEN_ADB_INVALID_TICKET",
          "[POLICY_BLOCKED] Invalid native virtual-screen ticket\n[EXIT=126]");
    if (cancelled.getAsBoolean())
      return DeviceTransportResult.notSent(
          "adb", "VSCREEN_ADB_CANCELLED_BEFORE_START", cancelledOutput.get());
    Boundary boundary = new Boundary(cancelled);
    try {
      HelperResult helper = launch.run(boundary);
      String output = helper == null ? "" : helper.output;
      if (boundary.cancelledBeforeHelper && !boundary.helperAttempted)
        return DeviceTransportResult.notSent("adb", "VSCREEN_ADB_CANCELLED_BEFORE_START", output);
      // The permit is consumed by /device/vscreen/commit immediately before the
      // sole remote send. A lost commit response, missing ticket or committed
      // attempt cannot pass this atomic native withdrawal, whatever stdout says.
      if (boundary.helperAttempted
          && helper != null
          && helper.localExitConfirmed
          && helper.outputComplete
          && withdrawUncommitted.getAsBoolean())
        return DeviceTransportResult.notSent("adb", "VSCREEN_ADB_NOT_COMMITTED", output);
      return unknown(output);
    } catch (AdbEnvironmentTask.Busy busy) {
      String output = "ENVIRONMENT_BUSY: " + busy.getMessage();
      return boundary.helperAttempted
          ? unknown(output)
          : DeviceTransportResult.notSent("adb", "VSCREEN_ADB_ENVIRONMENT_BUSY", output);
    } catch (Exception failure) {
      if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
      return unknown("ADB_ERROR: " + SensitiveData.redact(String.valueOf(failure)));
    }
  }

  private static DeviceTransportResult unknown(String output) {
    // execOwned's legacy text does not establish commit, output completeness or helper/guest exit.
    return DeviceTransportResult.unknown(
        "adb", "VSCREEN_ADB_START_UNCONFIRMED", output, false, DeviceTransportIdentity.unknown());
  }
}
