package com.deepseekharness.app.util;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.SocketException;
import java.util.List;
import org.junit.Test;

public class VirtualScreenErrorsTest {
  /** 与 android.os.RemoteException 同名的本地替身：映射只看类名，不依赖 Android。 */
  private static final class RemoteException extends Exception {}

  @Test
  public void mapsCreationFailureToAnActionableCode() {
    // 明知调用方持有权限却仍被系统拒绝 —— 只能换 Root 通道或改用无障碍通道。
    assertEquals("VSCREEN_TRUSTED_DISPLAY_DENIED", VirtualScreenErrors.createFailure(true));
    // 没有请求 TRUSTED 仍然失败 —— 与权限无关的创建失败。
    assertEquals("VSCREEN_DISPLAY_CREATE_FAILED", VirtualScreenErrors.createFailure(false));
  }

  @Test
  public void classifiesTheExceptionChainWithoutLeakingClassNames() {
    assertEquals(
        "VSCREEN_PERMISSION_DENIED",
        VirtualScreenErrors.stable(new RuntimeException(new SecurityException("denied"))));
    assertEquals(
        "VSCREEN_DISPLAY_SERVICE_UNAVAILABLE",
        VirtualScreenErrors.stable(new RuntimeException(new RemoteException())));
    assertEquals("VSCREEN_DISPLAY_IO", VirtualScreenErrors.stable(new SocketException("reset")));
    assertEquals(
        "VSCREEN_INVALID_DISPLAY_REQUEST",
        VirtualScreenErrors.stable(new IllegalArgumentException("bad size")));
    assertEquals(
        "VSCREEN_DISPLAY_STATE", VirtualScreenErrors.stable(new IllegalStateException("gone")));
    assertEquals(
        "VSCREEN_OPERATION_FAILED", VirtualScreenErrors.stable(new RuntimeException("opaque")));
  }

  @Test
  public void everyCodeIsStableAndCarriesNoClassOrThrowableName() {
    List<Throwable> chain =
        List.of(
            new SecurityException("a"),
            new RuntimeException(new SecurityException("b")),
            new IOException("c"),
            new SocketException("d"),
            new IllegalArgumentException("e"),
            new IllegalStateException("f"),
            new RuntimeException(new RemoteException()),
            new RuntimeException(new RuntimeException("deep")),
            new Error("g"));
    for (Throwable error : chain) {
      String code = VirtualScreenErrors.stable(error);
      assertTrue(code, code.startsWith("VSCREEN_"));
      assertFalse(code, code.contains("Exception"));
      assertFalse(code, code.contains("Error"));
      assertFalse(code, code.contains("Throwable"));
      assertEquals(code, code, code.toUpperCase(java.util.Locale.ROOT));
    }
  }
}
