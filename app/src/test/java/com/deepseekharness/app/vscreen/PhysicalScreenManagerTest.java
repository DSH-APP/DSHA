package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import android.content.Context;
import com.deepseekharness.app.device.DeviceTransportIdentity;
import com.deepseekharness.app.device.DeviceTransportResult;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 实体屏特权管理器：受管启动、一次性凭据、UNKNOWN 不重放与 benchmark 统计。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class PhysicalScreenManagerTest {
  private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final String INSTANCE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  private static final String GENERATION = "gen-1";

  private static final class FakeChannel implements VirtualScreenChannel {
    int starts;
    String command = "";

    public String id() {
      return "root";
    }

    public boolean available(Context context) {
      return true;
    }

    public String start(Context context, String command) {
      return startTyped(context, command).output;
    }

    public DeviceTransportResult startTyped(Context context, String command) {
      starts++;
      this.command = command;
      String output =
          "DSHA_VSCREEN_STARTED 1 4433 "
              + TOKEN
              + " 123:456:2000:11111111-2222-3333-4444-555555555555:"
              + INSTANCE
              + "\n[EXIT=0]";
      return DeviceTransportResult.fromLegacy("root", output, DeviceTransportIdentity.unknown());
    }
  }

  private static final class FakeTransport implements PhysicalScreenManager.Transport {
    final List<String> routes = new ArrayList<>();
    final List<String> queries = new ArrayList<>();
    long frameSeq = 1;
    String observation = uuid();
    boolean omitObservationOnTap;
    boolean failTapUnknown;
    boolean failRead;
    int tapCalls;

    public JSONObject request(VirtualScreenHandshake endpoint, String route, String query) {
      routes.add(route);
      queries.add(query);
      if (failRead) return failure("VSCREEN_TRANSPORT_UNAVAILABLE", false);
      if (route.equals("/priv/info")) {
        JSONObject info = receipt("/priv/info");
        info.remove("observationId");
        info.remove("observation");
        return info;
      }
      if (route.equals("/priv/frame")) return receipt("/priv/frame");
      if (route.equals("/priv/tap")) {
        tapCalls++;
        if (failTapUnknown) {
          JSONObject value = failure("INPUT_RESULT_UNKNOWN", true);
          return value;
        }
        frameSeq++;
        if (!omitObservationOnTap) observation = uuid();
        JSONObject value = receipt("/priv/tap");
        if (omitObservationOnTap) {
          value.remove("observationId");
          value.remove("observation");
        }
        return value;
      }
      if (route.equals("/priv/close")) {
        try {
          return new JSONObject().put("ok", true);
        } catch (Exception impossible) {
          throw new IllegalStateException(impossible);
        }
      }
      return failure("UNKNOWN_ROUTE", false);
    }

    private JSONObject receipt(String route) {
      try {
        JSONObject observationJson =
            new JSONObject()
                .put("kind", "dsha-priv-frame-v1")
                .put("observationId", observation)
                .put("frameSeq", frameSeq)
                .put("generation", GENERATION)
                .put("coreInstance", INSTANCE)
                .put("width", 100)
                .put("height", 200);
        JSONObject value =
            new JSONObject()
                .put("ok", true)
                .put("state", "COMPLETED")
                .put("active", true)
                .put("physical", true)
                .put("displayId", 0)
                .put("coreInstance", INSTANCE)
                .put("generation", GENERATION)
                .put("frameSeq", frameSeq)
                .put("observationId", observation)
                .put("observation", observationJson)
                .put("width", 100)
                .put("height", 200)
                .put("screenWidth", 1000)
                .put("screenHeight", 2000)
                .put("package", "com.game")
                .put("packageVerified", true)
                .put(
                    "frameConfig",
                    new JSONObject().put("maxWidth", 1280).put("jpegQuality", 60).put("maxFps", 6))
                .put("accessibilityRequired", false);
        if (route.equals("/priv/tap")) value.put("inputMs", 7).put("frameWaitMs", 11);
        return value;
      } catch (Exception impossible) {
        throw new IllegalStateException(impossible);
      }
    }

    private static JSONObject failure(String code, boolean unknown) {
      try {
        return new JSONObject()
            .put("ok", false)
            .put("error", code)
            .put("executionUnknown", unknown);
      } catch (Exception impossible) {
        throw new IllegalStateException(impossible);
      }
    }
  }

  private static String uuid() {
    return UUID.randomUUID().toString();
  }

  private PhysicalScreenManager manager(FakeChannel channel, FakeTransport transport) {
    return new PhysicalScreenManager(
        RuntimeEnvironment.getApplication(),
        context -> channel,
        transport,
        android.os.SystemClock::elapsedRealtime);
  }

  private static String param(String query, String key) {
    for (String pair : query.split("&")) {
      String[] item = pair.split("=", 2);
      if (item.length == 2 && item[0].equals(key))
        return java.net.URLDecoder.decode(item[1], java.nio.charset.StandardCharsets.UTF_8);
    }
    return "";
  }

  @Test
  public void startUsesTheManagedLauncherAndBindsTheCoreInstance() {
    FakeChannel channel = new FakeChannel();
    FakeTransport transport = new FakeTransport();
    PhysicalScreenManager manager = manager(channel, transport);
    JSONObject info = manager.info();
    assertTrue(info.optBoolean("ok"));
    assertEquals(1, channel.starts);
    assertTrue(channel.command.contains("--launch --physical --port 0 --package"));
    assertEquals("root", info.optString("channel"));
    assertEquals(INSTANCE, info.optString("coreInstance"));
    assertEquals(GENERATION, info.optString("generation"));
    assertFalse(info.optBoolean("accessibilityRequired", true));
    // 启动探针 + 真正的 info 各一次；没有重放启动。
    assertEquals(List.of("/priv/info", "/priv/info"), transport.routes);
  }

  @Test
  public void actionRequiresAnObservationAndConsumesItOnce() {
    FakeChannel channel = new FakeChannel();
    FakeTransport transport = new FakeTransport();
    PhysicalScreenManager manager = manager(channel, transport);
    JSONObject blind = manager.tap(10, 20, 0);
    assertFalse(blind.optBoolean("ok"));
    assertEquals("PRIV_FRAME_NOT_OBSERVED", blind.optString("error"));
    assertEquals("NOT_SENT", blind.optString("state"));
    assertEquals(0, transport.tapCalls);

    assertTrue(manager.frame().optBoolean("ok"));
    assertTrue(manager.tap(10, 20, 0).optBoolean("ok"));
    assertEquals(1, transport.tapCalls);
    // 回执未签发新观察：旧凭据已消费，下一步必须重新取帧，绝不重放。
    transport.omitObservationOnTap = true;
    assertTrue(manager.frame().optBoolean("ok"));
    assertTrue(manager.tap(10, 20, 0).optBoolean("ok"));
    JSONObject reused = manager.tap(10, 20, 0);
    assertFalse(reused.optBoolean("ok"));
    assertEquals("PRIV_FRAME_NOT_OBSERVED", reused.optString("error"));
    assertEquals(2, transport.tapCalls);
  }

  @Test
  public void unknownTapIsReportedUnknownAndNeverRetried() {
    FakeChannel channel = new FakeChannel();
    FakeTransport transport = new FakeTransport();
    PhysicalScreenManager manager = manager(channel, transport);
    assertTrue(manager.frame().optBoolean("ok"));
    transport.failTapUnknown = true;
    JSONObject unknown = manager.tap(10, 20, 250);
    assertEquals("EXECUTION_UNKNOWN", unknown.optString("state"));
    assertTrue(unknown.optBoolean("executionUnknown"));
    assertFalse(unknown.optBoolean("retryable", true));
    assertEquals("INPUT_RESULT_UNKNOWN", unknown.optString("coreError"));
    JSONObject next = manager.tap(10, 20, 250);
    assertEquals("NOT_SENT", next.optString("state"));
    assertEquals(1, transport.tapCalls);
  }

  @Test
  public void tapBindsTheObservedTargetAndGeneration() {
    FakeChannel channel = new FakeChannel();
    FakeTransport transport = new FakeTransport();
    PhysicalScreenManager manager = manager(channel, transport);
    assertTrue(manager.frame().optBoolean("ok"));
    assertTrue(manager.tap(10, 20, 0).optBoolean("ok"));
    String tapQuery = transport.queries.get(transport.queries.size() - 1);
    assertEquals("com.game", param(tapQuery, "expectedPackage"));
    assertEquals(GENERATION, param(tapQuery, "generation"));
    assertEquals("10.0", param(tapQuery, "x"));
    assertFalse(param(tapQuery, "observationId").isEmpty());
  }

  @Test
  public void benchmarkRepeatsActionsAndReportsEveryPercentile() {
    FakeChannel channel = new FakeChannel();
    FakeTransport transport = new FakeTransport();
    PhysicalScreenManager manager = manager(channel, transport);
    JSONObject report = manager.benchmark(3, 10, 20, 0);
    assertTrue(report.optBoolean("ok"));
    JSONObject benchmark = report.optJSONObject("benchmark");
    assertNotNull(benchmark);
    assertEquals(3, benchmark.optInt("completedSamples"));
    assertEquals(3, benchmark.optJSONArray("rows").length());
    assertEquals(3, benchmark.optJSONObject("roundTripMs").optInt("count"));
    assertEquals(3, benchmark.optJSONObject("inputMs").optInt("count"));
    assertEquals(3, benchmark.optJSONObject("frameWaitMs").optInt("count"));
    assertTrue(benchmark.optJSONObject("roundTripMs").optLong("p95", -1) >= 0);
    assertFalse(benchmark.optBoolean("accessibilityRequired", true));
    assertEquals(3, transport.tapCalls);
    for (int i = 2; i < transport.routes.size(); i++)
      assertEquals("/priv/tap", transport.routes.get(i));
  }

  @Test
  public void unavailableTransportOnReadDropsTheEndpointForTheNextRequest() {
    FakeChannel channel = new FakeChannel();
    FakeTransport transport = new FakeTransport();
    PhysicalScreenManager manager = manager(channel, transport);
    assertTrue(manager.frame().optBoolean("ok"));
    transport.failRead = true;
    JSONObject failed = manager.frame();
    assertFalse(failed.optBoolean("ok"));
    assertEquals("PRIV_CORE_UNREACHABLE", failed.optString("error"));
    assertEquals("NOT_SENT", failed.optString("state"));
    transport.failRead = false;
    assertTrue(manager.frame().optBoolean("ok"));
    assertEquals(2, channel.starts);
  }

  @Test
  public void closeClearsTheConnectionWithoutReplayingAnything() {
    FakeChannel channel = new FakeChannel();
    FakeTransport transport = new FakeTransport();
    PhysicalScreenManager manager = manager(channel, transport);
    assertTrue(manager.frame().optBoolean("ok"));
    JSONObject closed = manager.close();
    assertTrue(closed.optBoolean("ok"));
    assertEquals(0, transport.tapCalls);
    assertFalse(manager.running());
  }
}
