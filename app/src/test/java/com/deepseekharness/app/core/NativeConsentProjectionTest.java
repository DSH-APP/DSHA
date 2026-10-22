package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import android.content.SharedPreferences;
import com.deepseekharness.app.util.NativeConsentPolicy;
import com.deepseekharness.app.util.NativeConsentProjection;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

/** Actual DeviceGrants failures feed the same production payload mapper used by /app/native-policy. */
public final class NativeConsentProjectionTest {
  private static final String KEY = "native_global_consent_v1";
  private static final String INSTALL = "11111111-2222-3333-4444-555555555555";
  private static final String BRIDGE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  private static final String RUNTIME = "22222222-3333-4444-5555-666666666666:7";

  private static Map<String, Object> payload(NativeConsentPolicy.Snapshot state) {
    return NativeConsentProjection.payload(state, true, INSTALL, BRIDGE, 7, RUNTIME);
  }

  private static void resetFixtureProcess() throws Exception {
    for (String name : new String[] {"globalChanging", "globalUnavailable"}) {
      var field = DeviceGrants.class.getDeclaredField(name);
      field.setAccessible(true);
      field.setBoolean(null, false);
    }
    var save = DeviceGrants.class.getDeclaredField("lastSave");
    save.setAccessible(true);
    save.set(null, NativeConsentPolicy.SaveState.NONE);
    for (String name : new String[] {"mutationGeneration", "observedRevision"}) {
      var field = DeviceGrants.class.getDeclaredField(name);
      field.setAccessible(true);
      ((AtomicLong) field.get(null)).set(0);
    }
    var unsafe = DeviceGrants.class.getDeclaredField("unsafeRead");
    unsafe.setAccessible(true);
    ((AtomicBoolean) unsafe.get(null)).set(false);
  }

  private static SharedPreferences preferences(
      Map<String, Object> values, AtomicBoolean unreadable, AtomicBoolean failedCommit) {
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getString")) {
                if (unreadable.get()) throw new IllegalStateException("FIXTURE_READ_UNAVAILABLE");
                return values.getOrDefault(args[0], args[1]);
              }
              if (method.getName().equals("edit")) {
                Map<String, Object> pending = new HashMap<>();
                return Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[] {SharedPreferences.Editor.class},
                    (editor, call, input) -> {
                      if (call.getName().equals("putString")) {
                        pending.put((String) input[0], input[1]);
                        return editor;
                      }
                      if (call.getName().equals("commit")) {
                        values.putAll(pending);
                        return !failedCommit.get();
                      }
                      throw new UnsupportedOperationException(call.getName());
                    });
              }
              throw new UnsupportedOperationException(method.getName());
            });
  }

  private static Map<String, Object> capture(DeviceGrants grants) {
    var state = grants.consentSnapshot();
    return Map.of(
        "payload",
        payload(state),
        "readState",
        state.readState().name(),
        "saveState",
        state.saveState().name());
  }

  /** Emits real production projections; a guest test may replace transport, never these policy values. */
  private static Map<String, Object> actualCases() throws Exception {
    Map<String, Object> cases = new LinkedHashMap<>();
    for (String scenario :
        List.of(
            "unconfigured",
            "on",
            "off",
            "read_unavailable",
            "malformed",
            "wrong_type",
            "changing",
            "save_failed",
            "reconfirm",
            "stale_revision",
            "missing")) {
      resetFixtureProcess();
      Map<String, Object> values = new HashMap<>();
      var unreadable = new AtomicBoolean();
      var failedCommit = new AtomicBoolean();
      DeviceGrants[] holder = new DeviceGrants[1];
      var captureChanging = new AtomicBoolean();
      holder[0] =
          new DeviceGrants(
              preferences(values, unreadable, failedCommit),
              () -> {
                if (captureChanging.get()) cases.put("changing", capture(holder[0]));
              });
      var grants = holder[0];
      if (!scenario.equals("unconfigured")) assertTrue(grants.setGlobalAllowed(true));
      String first = (String) values.get(KEY);
      switch (scenario) {
        case "off" -> assertTrue(grants.setGlobalAllowed(false));
        case "read_unavailable" -> unreadable.set(true);
        case "malformed" -> values.put(KEY, "malformed");
        case "wrong_type" -> values.put(KEY, 123);
        case "changing" -> {
          captureChanging.set(true);
          assertTrue(grants.setGlobalAllowed(false));
        }
        case "save_failed" -> {
          failedCommit.set(true);
          assertFalse(grants.setGlobalAllowed(false));
        }
        case "reconfirm" -> {
          unreadable.set(true);
          grants.consentSnapshot();
          unreadable.set(false);
        }
        case "stale_revision" -> {
          assertTrue(grants.setGlobalAllowed(false));
          values.put(KEY, first);
        }
        case "missing" -> values.remove(KEY);
      }
      if (!scenario.equals("changing")) cases.put(scenario, capture(grants));
    }
    resetFixtureProcess();
    return cases;
  }

  @Test
  public void validOnOffAndAbsentDefaultKeepTheirDefinedMeaning() {
    for (var state :
        List.of(
            NativeConsentPolicy.ReadState.ON,
            NativeConsentPolicy.ReadState.OFF,
            NativeConsentPolicy.ReadState.UNCONFIGURED)) {
      var snapshot = new NativeConsentPolicy.Snapshot(state, NativeConsentPolicy.SaveState.NONE, 0);
      var value = payload(snapshot);
      assertEquals(true, value.get("available"));
      assertEquals(state == NativeConsentPolicy.ReadState.ON, value.get("globalAllow"));
      assertEquals(0L, value.get("revision"));
      assertEquals(
          false,
          NativeConsentProjection.payload(snapshot, false, INSTALL, BRIDGE, 7, RUNTIME)
              .get("available"));
    }
  }

  @Test
  public void unsafeReadSaveOrHighWaterStateNeverLooksLikeValidOff() {
    for (var state : NativeConsentPolicy.ReadState.values()) {
      if (List.of(
              NativeConsentPolicy.ReadState.ON,
              NativeConsentPolicy.ReadState.OFF,
              NativeConsentPolicy.ReadState.UNCONFIGURED)
          .contains(state)) continue;
      var value =
          payload(new NativeConsentPolicy.Snapshot(state, NativeConsentPolicy.SaveState.NONE, 987));
      assertEquals(false, value.get("available"));
      assertEquals(false, value.get("globalAllow"));
      assertEquals(NativeConsentPolicy.MAX_REVISION, value.get("revision"));
    }
    var invalidSave =
        new NativeConsentPolicy.Snapshot(
            NativeConsentPolicy.ReadState.ON, NativeConsentPolicy.SaveState.COMMIT_FAILED, 987);
    assertEquals(false, payload(invalidSave).get("available"));
    assertEquals(false, payload(null).get("available"));
  }

  @Test
  @SuppressWarnings("unchecked")
  public void actualNativeFailuresProduceUnavailableWireFixtures() throws Exception {
    var cases = actualCases();
    assertEquals(11, cases.size());
    for (var entry : cases.entrySet()) {
      var fixture = (Map<String, Object>) entry.getValue();
      var value = (Map<String, Object>) fixture.get("payload");
      boolean known = List.of("on", "off", "unconfigured").contains(entry.getKey());
      assertEquals(entry.getKey(), known, value.get("available"));
      assertEquals(entry.getKey(), entry.getKey().equals("on"), value.get("globalAllow"));
    }
    String destination = System.getProperty("dsha.nativeConsentFixture");
    if (destination != null)
      java.nio.file.Files.write(
          java.nio.file.Path.of(destination),
          com.deepseekharness.app.backup.BackupJson.write(
              Map.of(
                  "schema",
                  1,
                  "cases",
                  cases,
                  "producer",
                  "DeviceGrants.consentSnapshot -> NativeConsentProjection.payload",
                  "context",
                  Map.of(
                      "applicationId",
                      "com.dsh.client",
                      "installInstanceId",
                      INSTALL,
                      "bridgeRunId",
                      BRIDGE,
                      "generation",
                      3,
                      "webGeneration",
                      7,
                      "runtimeInstanceId",
                      RUNTIME)),
              64 * 1024));
  }
}
