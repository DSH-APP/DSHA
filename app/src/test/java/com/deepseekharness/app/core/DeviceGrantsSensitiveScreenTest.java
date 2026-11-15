package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import com.deepseekharness.app.util.SensitiveScreenPolicy;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/** 敏感界面策略的本地存储与授权修订号（T119）。 */
public final class DeviceGrantsSensitiveScreenTest {
  private static SharedPreferences preferences(Map<String, Object> values) {
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getString":
                  return values.getOrDefault(args[0], args[1]);
                case "getBoolean":
                  return values.getOrDefault(args[0], args[1]);
                case "edit":
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
                          return true;
                        }
                        throw new UnsupportedOperationException(call.getName());
                      });
                default:
                  throw new UnsupportedOperationException(method.getName());
              }
            });
  }

  @Test
  public void sensitiveModePersistsAndFencesBeforePublication() {
    var values = new HashMap<String, Object>();
    var invalidations = new AtomicInteger();
    var grants = new DeviceGrants(preferences(values), invalidations::incrementAndGet);
    long before = grants.screenAuthorityRevision();
    assertTrue(
        grants.setSensitiveScreenMode(
            SensitiveScreenPolicy.Category.PAYMENT, SensitiveScreenPolicy.Mode.HANDOFF));
    assertEquals(1, invalidations.get());
    assertEquals(
        SensitiveScreenPolicy.Mode.HANDOFF,
        grants.sensitiveScreenPolicy().mode(SensitiveScreenPolicy.Category.PAYMENT));
    assertTrue(grants.screenAuthorityRevision() > before);
    assertTrue(String.valueOf(values.get("sensitive_screen_v1")).contains("PAYMENT=HANDOFF"));
    // 其它类别仍是默认询问。
    assertEquals(
        SensitiveScreenPolicy.Mode.ASK,
        grants.sensitiveScreenPolicy().mode(SensitiveScreenPolicy.Category.OTP));
    // OFF 也能持久化；它只关闭整屏分档，密码文本硬阻断由输入层独立执行。
    assertTrue(
        grants.setSensitiveScreenMode(
            SensitiveScreenPolicy.Category.SYSTEM_SECURITY, SensitiveScreenPolicy.Mode.OFF));
    assertEquals(
        SensitiveScreenPolicy.Mode.OFF,
        grants.sensitiveScreenPolicy().mode(SensitiveScreenPolicy.Category.SYSTEM_SECURITY));
  }

  @Test
  public void appListsRoundTripAndInvalidInputIsNotPublished() {
    var values = new HashMap<String, Object>();
    var grants = new DeviceGrants(preferences(values), () -> {});
    Set<String> trust = new LinkedHashSet<>(Set.of("com.example.trusted"));
    Set<String> handoff = new LinkedHashSet<>(Set.of("com.example.bank"));
    assertTrue(grants.setSensitiveScreenApps(trust, handoff));
    assertEquals(trust, grants.sensitiveScreenPolicy().trustApps());
    assertEquals(handoff, grants.sensitiveScreenPolicy().handoffApps());
    Object saved = values.get("sensitive_screen_v1");

    assertFalse(
        "same package in both lists must not be published",
        grants.setSensitiveScreenApps(Set.of("com.example.app"), Set.of("com.example.app")));
    assertEquals(saved, values.get("sensitive_screen_v1"));
    assertFalse(grants.setSensitiveScreenApps(Set.of("Not A Package"), Set.of()));
    assertEquals(saved, values.get("sensitive_screen_v1"));
  }

  @Test
  public void malformedRecordFallsBackToAskWithoutRevivingAnOldRevision() {
    var values = new HashMap<String, Object>();
    var grants = new DeviceGrants(preferences(values), () -> {});
    for (int i = 0; i < 4; i++)
      assertTrue(
          grants.setSensitiveScreenMode(
              SensitiveScreenPolicy.Category.OTP, SensitiveScreenPolicy.Mode.HANDOFF));
    long before = grants.screenAuthorityRevision();
    values.put("sensitive_screen_v1", "garbage-without-schema");
    var corrupted = new DeviceGrants(preferences(values), () -> {});
    assertEquals(
        SensitiveScreenPolicy.Mode.ASK,
        corrupted.sensitiveScreenPolicy().mode(SensitiveScreenPolicy.Category.OTP));
    assertTrue(
        "the in-process revision floor must not move backwards",
        corrupted.screenAuthorityRevision() >= before);
  }

  @Test
  public void resetReturnsToDefaultsButKeepsRevisionIncreasing() {
    var values = new HashMap<String, Object>();
    var grants = new DeviceGrants(preferences(values), () -> {});
    assertTrue(
        grants.setSensitiveScreenMode(
            SensitiveScreenPolicy.Category.SYSTEM_SECURITY, SensitiveScreenPolicy.Mode.HANDOFF));
    assertTrue(grants.setSensitiveScreenApps(Set.of("com.example.trusted"), Set.of()));
    long configured = grants.screenAuthorityRevision();
    assertTrue(grants.resetSensitiveScreenPolicy());
    var reset = grants.sensitiveScreenPolicy();
    for (var category : SensitiveScreenPolicy.Category.values())
      assertEquals(SensitiveScreenPolicy.Mode.ASK, reset.mode(category));
    assertTrue(reset.trustApps().isEmpty());
    assertTrue(reset.handoffApps().isEmpty());
    assertTrue(grants.screenAuthorityRevision() > configured);
  }
}
