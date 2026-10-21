package com.deepseekharness.app.screen;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.view.Display;
import com.deepseekharness.app.util.ScreenWindowRegion;
import com.deepseekharness.app.util.ScreenPixelFingerprint;
import com.deepseekharness.app.util.ScreenCaptureDelivery;
import java.util.concurrent.TimeUnit;

/** 截图读取、像素指纹及系统缓冲区所有权；不登记“已观察”。 */
public final class ScreenScreenshotCapture {
  /** The existing API code plus the actual callback cause; a system status alone has no cause. */
  static final class CaptureFailure extends IllegalStateException {
    CaptureFailure(String code, Throwable cause) {
      super(code, cause);
    }
  }

  private static final java.util.concurrent.Executor CALLBACKS =
      new java.util.concurrent.ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new java.util.concurrent.ArrayBlockingQueue<>(8),
          r -> {
            Thread thread = new Thread(r, "screen-capture-callback");
            thread.setDaemon(true);
            return thread;
          },
          new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

  public static final class Shot implements AutoCloseable {
    public final Bitmap bitmap;
    public final int rotation;
    public final String fingerprint;
    public final ScreenWindowRegion region;

    private Shot(Bitmap bitmap, int rotation, ScreenWindowRegion region) throws Exception {
      this.bitmap = bitmap;
      this.rotation = rotation;
      this.region = region;
      if (region != null && !region.within(bitmap.getWidth(), bitmap.getHeight()))
        throw new IllegalStateException("SCREEN_WINDOW_GEOMETRY_UNAVAILABLE");
      fingerprint =
          region == null
              ? null
              : ScreenPixelFingerprint.hash(
                  bitmap.getWidth(),
                  bitmap.getHeight(),
                  region.content(),
                  (row, left, y, width) -> bitmap.getPixels(row, 0, width, left, y, width, 1));
    }

    @Override
    public void close() {
      bitmap.recycle();
    }
  }

  @android.annotation.TargetApi(30)
  public Shot capture(ScreenRequestContext context) throws Exception {
    if (android.os.Build.VERSION.SDK_INT < 30)
      throw new IllegalStateException("SCREENSHOT_UNSUPPORTED");
    final ScreenWindowRegion region = context.currentRegionIfKnown();
    int displayId = context.target().displayId;
    DisplayManager displays = context.service().getSystemService(DisplayManager.class);
    Display display = displays == null ? null : displays.getDisplay(displayId);
    if (display == null) throw new IllegalStateException("SCREEN_DISPLAY_UNAVAILABLE");
    int rotation = display.getRotation();
    ScreenCaptureDelivery<Shot> pending =
        new ScreenCaptureDelivery<>(
            context::current,
            Shot::close,
            failure ->
                MainScreenActions.recordFailure(
                    context,
                    MainScreenActions.FailureStage.LATE_CAPTURE,
                    failure instanceof Exception error
                        ? safeCode(error)
                        : "SCREEN_OPERATION_UNAVAILABLE",
                    failure,
                    true));
    context
        .service()
        .takeScreenshot(
            displayId,
            CALLBACKS,
            new AccessibilityService.TakeScreenshotCallback() {
              @Override
              public void onSuccess(AccessibilityService.ScreenshotResult result) {
                Bitmap hardware = null, software = null;
                Shot shot = null;
                try {
                  if (!context.current()) throw new IllegalStateException("SCREENSHOT_RUN_CHANGED");
                  hardware =
                      Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                  if (hardware == null) throw new IllegalStateException("SCREENSHOT_DECODE_FAILED");
                  if ((long) hardware.getWidth() * hardware.getHeight() > 16 * 1024 * 1024)
                    throw new IllegalStateException("SCREENSHOT_TOO_LARGE");
                  software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                  if (software == null) throw new IllegalStateException("SCREENSHOT_DECODE_FAILED");
                  shot = new Shot(software, rotation, region);
                  software = null;
                  pending.complete(shot);
                  shot = null;
                } catch (Exception failure) {
                  pending.fail(safeCode(failure), failure);
                } finally {
                  if (shot != null) shot.close();
                  if (software != null) software.recycle();
                  if (hardware != null) hardware.recycle();
                  result.getHardwareBuffer().close();
                }
              }

              @Override
              public void onFailure(int code) {
                pending.fail("SCREENSHOT_SYSTEM_" + code);
              }
            });
    Shot result;
    try {
      long remaining =
          Math.min(10_000L, context.request().deadlineElapsedRealtimeMillis - context.nowMillis());
      result = pending.await(remaining);
      if (result == null) throw new CaptureFailure(pending.error(), pending.takeFailure());
    } finally {
      pending.close();
    }
    try {
      if (!java.util.Objects.equals(region, context.currentRegionIfKnown()))
        throw new IllegalStateException("SCREEN_WINDOW_GEOMETRY_CHANGED");
      Display latest = displays.getDisplay(displayId);
      if (latest == null || latest.getRotation() != rotation)
        throw new IllegalStateException("SCREEN_GEOMETRY_CHANGED");
      return result;
    } catch (Exception rejected) {
      result.close();
      throw rejected;
    }
  }

  static String safeCode(Exception error) {
    String message = error.getMessage();
    return message != null && message.matches("[A-Z][A-Z0-9_]{0,80}")
        ? message
        : "SCREEN_OPERATION_UNAVAILABLE";
  }
}
