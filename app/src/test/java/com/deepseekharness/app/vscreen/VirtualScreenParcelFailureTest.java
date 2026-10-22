package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import android.os.RemoteException;
import com.deepseekharness.app.util.VirtualScreenBootstrap;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenFailures;
import java.lang.reflect.InvocationTargetException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Android Parcel 追加的远端栈不能取代真正的外层语义；原始结构图仍保留该附件。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenParcelFailureTest {
  private static RemoteException remoteStack() {
    var trace =
        new RemoteException(
            "Remote stack trace:\n at com.android.server.display.DisplayManagerService.createVirtualDisplayInternal(DisplayManagerService.java:1251)");
    trace.setStackTrace(new StackTraceElement[0]);
    return trace;
  }

  private static Throwable parcel(Throwable actual) {
    actual.initCause(remoteStack());
    return actual;
  }

  private static JSONObject response(Throwable error, boolean displayCall) throws Exception {
    var method =
        VirtualScreenCore.class.getDeclaredMethod("failure", Throwable.class, boolean.class);
    method.setAccessible(true);
    return (JSONObject) method.invoke(null, error, displayCall);
  }

  @Test
  public void parcelSecurityKeepsPermissionMeaningAndFullOriginalGraph() throws Exception {
    Throwable actual =
        parcel(new SecurityException("controlled OS permission detail " + "x".repeat(400)));
    actual.addSuppressed(new IllegalStateException("controlled cleanup failure"));
    assertEquals(VirtualScreenFailures.PERMISSION_DENIED, VirtualScreenFailures.code(actual));
    assertTrue(
        VirtualScreenFailures.cause(actual)
            .startsWith("SecurityException: controlled OS permission detail"));
    var result = response(actual, true);
    var graph = result.getJSONObject("failure");
    assertEquals(SecurityException.class.getName(), graph.getString("exceptionClass"));
    assertEquals(actual.getMessage(), graph.getString("summary"));
    assertEquals(
        RemoteException.class.getName(), graph.getJSONObject("cause").getString("exceptionClass"));
    assertTrue(graph.getJSONObject("cause").getString("summary").startsWith("Remote stack trace:"));
    assertEquals(1, graph.getJSONArray("suppressed").length());
    var metadata = VirtualScreenManager.failureCaptureMetadata(graph);
    assertEquals(false, metadata.get("messageOmitted"));
    assertFalse(metadata.containsKey("capture"));
  }

  @Test
  public void parcelArgumentFailureDoesNotBecomeTransportUnavailable() throws Exception {
    Throwable actual = parcel(new IllegalArgumentException("controlled display argument"));
    assertEquals(VirtualScreenFailures.INVALID_REQUEST, VirtualScreenFailures.code(actual));
    assertEquals(
        "IllegalArgumentException: controlled display argument",
        VirtualScreenFailures.cause(actual));
    assertEquals(
        "CORE_BOOTSTRAP_SDK33_INVALID_DISPLAY_REQUEST",
        VirtualScreenBootstrap.failureCode("BOOTSTRAP", 33, new InvocationTargetException(actual)));
  }

  @Test
  public void genuineRemoteExceptionRetainsTheServiceUnavailableCategory() {
    var actual = new RemoteException("actual binder service failure");
    assertEquals(
        VirtualScreenFailures.DISPLAY_SERVICE_UNAVAILABLE, VirtualScreenFailures.code(actual));
    assertEquals(
        "RemoteException: actual binder service failure", VirtualScreenFailures.cause(actual));
    assertEquals(
        VirtualScreenFailures.DISPLAY_SERVICE_UNAVAILABLE,
        VirtualScreenFailures.code(remoteStack()));
  }

  @Test
  public void reflectionWrapperStillFindsRealNativeApiCause() {
    Throwable actual =
        new InvocationTargetException(
            new NoSuchMethodException("android.view.InputEvent.setDisplayId(int)"));
    assertEquals(VirtualScreenFailures.NATIVE_API_UNAVAILABLE, VirtualScreenFailures.code(actual));
    assertEquals(
        "NoSuchMethodException: android.view.InputEvent.setDisplayId(int)",
        VirtualScreenFailures.cause(actual));
  }

  @Test
  public void genericFailureOmitsEvenUppercaseMessageAndKeepsItsStructure() throws Exception {
    Throwable actual = parcel(new SecurityException("SYNTHETIC_BUSINESS_CANARY"));
    var result = response(actual, false);
    assertEquals("NATIVE_OPERATION", result.getString("failureOrigin"));
    assertFalse(result.toString().contains("SYNTHETIC_BUSINESS_CANARY"));
    assertEquals("EXCEPTION_MESSAGE_OMITTED", result.getJSONObject("failure").getString("summary"));
    assertEquals(
        RemoteException.class.getName(),
        result.getJSONObject("failure").getJSONObject("cause").getString("exceptionClass"));
    var metadata = VirtualScreenManager.failureCaptureMetadata(result.getJSONObject("failure"));
    assertEquals(true, metadata.get("messageOmitted"));
    assertEquals("PARTIAL", metadata.get("capture"));
  }

  @Test
  public void nestedOmissionsAndTruncationPropagateToOuterEventMetadata() throws Exception {
    var graph =
        response(new IllegalStateException("controlled platform message"), true)
            .getJSONObject("failure");
    graph.put(
        "cause", new JSONObject().put("messageOmissionReason", "SCREEN_BUSINESS_CONTENT_EXCLUDED"));
    var metadata = VirtualScreenManager.failureCaptureMetadata(graph);
    assertEquals(true, metadata.get("messageOmitted"));
    assertEquals("PARTIAL", metadata.get("capture"));
    graph.put("cause", JSONObject.NULL);
    graph.put("suppressed", new org.json.JSONArray().put(new JSONObject().put("truncated", true)));
    metadata = VirtualScreenManager.failureCaptureMetadata(graph);
    assertEquals(false, metadata.get("messageOmitted"));
    assertEquals("PARTIAL", metadata.get("capture"));
    assertEquals("PARTIAL", VirtualScreenManager.failureCaptureMetadata(null).get("capture"));
  }

  @Test
  public void hostAdapterSameTypeCannotSelectTrustedDisplayMessageProvenance() throws Exception {
    var birth =
        new VirtualScreenCoreIdentity(
            123,
            456,
            2000,
            "11111111-2222-3333-4444-555555555555",
            "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    try (var core =
        VirtualScreenCore.bindControlForHost(
            birth,
            (width, height) -> {
              throw new SecurityException("SYNTHETIC_BUSINESS_CANARY");
            },
            () -> "com.example.app",
            () -> 100L,
            () -> {})) {
      var result =
          new VirtualScreenTransport()
              .request(core.handshake(), "/vscreen/create", "width=1008&height=1792");
      assertEquals(VirtualScreenFailures.PERMISSION_DENIED, result.getString("error"));
      assertEquals("NATIVE_OPERATION", result.getString("failureOrigin"));
      assertFalse(result.toString().contains("SYNTHETIC_BUSINESS_CANARY"));
    }
  }
}
