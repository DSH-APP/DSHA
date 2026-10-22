package com.deepseekharness.app.core;

import android.content.SharedPreferences;
import com.deepseekharness.app.util.ScreenConfirmationPolicy;
import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class DeviceGrantsTest {
  @org.junit.Before
  public void isolateProcessStateForIndependentPreferenceFixtures() throws Exception {
    for (String name : new String[] {"globalChanging", "globalUnavailable"}) {
      var field = DeviceGrants.class.getDeclaredField(name);
      field.setAccessible(true);
      field.setBoolean(null, false);
    }
    var save = DeviceGrants.class.getDeclaredField("lastSave");
    save.setAccessible(true);
    save.set(null, com.deepseekharness.app.util.NativeConsentPolicy.SaveState.NONE);
    for (String name : new String[] {"mutationGeneration", "observedRevision"}) {
      var field = DeviceGrants.class.getDeclaredField(name);
      field.setAccessible(true);
      ((java.util.concurrent.atomic.AtomicLong) field.get(null)).set(0);
    }
    var unsafe = DeviceGrants.class.getDeclaredField("unsafeRead");
    unsafe.setAccessible(true);
    ((java.util.concurrent.atomic.AtomicBoolean) unsafe.get(null)).set(false);
  }

  @Test
  public void globalOnlyBypassesAskWithoutGrantingSmsAndRevokesBeforePublication() {
    var values = new HashMap<String, Object>();
    var prefs = preferences(values);
    values.put("sms_read", false);
    var individual =
        new ScreenConfirmationPolicy(
            ScreenConfirmationPolicy.Mode.CUSTOM,
            Map.of("com.bank.pay", ScreenConfirmationPolicy.Rule.ASK),
            7);
    values.put("screen_confirmation_v1", individual.encode());
    var count = new AtomicInteger();
    var grants =
        new DeviceGrants(
            prefs,
            () -> {
              assertFalse(new DeviceGrants(prefs, () -> {}).consentPolicy().globalAllow());
              count.incrementAndGet();
            });
    assertFalse(grants.consentPolicy().globalAllow());
    assertTrue(grants.setGlobalAllowed(true));
    long first = grants.consentPolicy().revision();
    assertFalse(grants.smsReadAllowed());
    assertTrue(grants.effectiveScreenPolicy().allows("com.bank.pay"));
    assertFalse(grants.smsReadConfigured());
    assertEquals(individual.encode(), values.get("screen_confirmation_v1"));
    assertFalse(values.containsKey("confirmShell"));
    assertTrue(grants.setGlobalAllowed(false));
    assertFalse(grants.smsReadAllowed());
    assertFalse(grants.effectiveScreenPolicy().allows("com.bank.pay"));
    assertTrue(grants.setGlobalAllowed(true));
    assertFalse(grants.consentPolicy().currentGlobal(first));
    assertEquals(3, count.get());
    values.put(
        "native_global_consent_v1",
        new com.deepseekharness.app.util.NativeConsentPolicy(true, first).encode());
    assertEquals(
        com.deepseekharness.app.util.NativeConsentPolicy.ReadState.RECONFIRM_REQUIRED,
        grants.consentSnapshot().readState());
    assertFalse(grants.consentPolicy().currentGlobal(first));
    assertTrue(grants.setGlobalAllowed(false));
  }

  @Test
  public void malformedStatusDoesNotBecomeOffOrRestartAnOldRevision() {
    var values = new HashMap<String, Object>();
    var grants = new DeviceGrants(preferences(values), () -> {});
    assertEquals(
        com.deepseekharness.app.util.NativeConsentPolicy.ReadState.UNCONFIGURED,
        grants.consentSnapshot().readState());
    assertTrue(grants.setGlobalAllowed(true));
    long old = grants.consentPolicy().revision();
    String original = (String) values.get("native_global_consent_v1");
    values.put("native_global_consent_v1", "malformed");
    assertEquals(
        com.deepseekharness.app.util.NativeConsentPolicy.ReadState.MALFORMED,
        grants.consentSnapshot().readState());
    assertFalse(grants.consentPolicy().currentGlobal(old));
    values.put(
        "native_global_consent_v1", original); // fixture repair is not a production recovery path
    assertEquals(
        com.deepseekharness.app.util.NativeConsentPolicy.ReadState.RECONFIRM_REQUIRED,
        grants.consentSnapshot().readState());
    assertFalse(grants.consentPolicy().currentGlobal(old));
    values.remove("native_global_consent_v1");
    assertEquals(
        com.deepseekharness.app.util.NativeConsentPolicy.ReadState.MALFORMED,
        grants.consentSnapshot().readState());
    assertFalse(grants.setGlobalAllowed(true));
    assertFalse(values.containsKey("native_global_consent_v1"));
    values.put("native_global_consent_v1", "malformed");
    assertFalse(grants.setGlobalAllowed(true));
    assertEquals("malformed", values.get("native_global_consent_v1"));
    values.put("native_global_consent_v1", original);
    assertTrue(grants.setGlobalAllowed(false));
    assertTrue(grants.consentPolicy().revision() > old);
  }

  @Test
  public void readFailureIsVisibleAndDoesNotOverwriteTheRecordOrRestoreOn() {
    var values = new HashMap<String, Object>();
    var unreadable = new java.util.concurrent.atomic.AtomicBoolean();
    var grants =
        new DeviceGrants(
            preferences(values, unreadable, new java.util.concurrent.atomic.AtomicBoolean()),
            () -> {});
    assertTrue(grants.setGlobalAllowed(true));
    String original = (String) values.get("native_global_consent_v1");
    unreadable.set(true);
    assertEquals(
        com.deepseekharness.app.util.NativeConsentPolicy.ReadState.READ_UNAVAILABLE,
        grants.consentSnapshot().readState());
    assertEquals(original, values.get("native_global_consent_v1"));
    unreadable.set(false);
    assertEquals(
        com.deepseekharness.app.util.NativeConsentPolicy.ReadState.RECONFIRM_REQUIRED,
        grants.consentSnapshot().readState());
    assertFalse(grants.consentPolicy().globalAllow());
    assertTrue(grants.setGlobalAllowed(false)); // explicit retry, never automatic ON
  }

  @Test
  public void failedCommitStaysVisibleAcrossReadsAndExplicitRetryUsesANewRevision() {
    var values = new HashMap<String, Object>();
    var failure = new java.util.concurrent.atomic.AtomicBoolean();
    var prefs = preferences(values, new java.util.concurrent.atomic.AtomicBoolean(), failure);
    var grants = new DeviceGrants(prefs, () -> {});
    assertTrue(grants.setGlobalAllowed(true));
    failure.set(true);
    assertFalse(grants.setGlobalAllowed(false));
    var failed = grants.consentSnapshot();
    assertEquals(
        com.deepseekharness.app.util.NativeConsentPolicy.ReadState.SAVE_FAILED, failed.readState());
    assertEquals(
        com.deepseekharness.app.util.NativeConsentPolicy.SaveState.COMMIT_FAILED,
        failed.saveState());
    assertEquals(failed, new DeviceGrants(prefs, () -> {}).consentSnapshot());
    assertFalse(grants.consentPolicy().globalAllow());
    failure.set(false);
    assertTrue(grants.setGlobalAllowed(false));
    assertTrue(grants.consentPolicy().revision() > failed.revision());
    assertEquals(
        com.deepseekharness.app.util.NativeConsentPolicy.SaveState.CACHE_VERIFIED,
        grants.consentSnapshot().saveState());
  }

  @Test
  public void smsRevokesBeforePublishingAndHasNoImportedAuthority() {
    var values = new HashMap<String, Object>();
    var prefs = preferences(values);
    var calls = new AtomicInteger();
    var grants =
        new DeviceGrants(
            prefs,
            () -> {},
            () -> {
              assertEquals(calls.get() > 0, new DeviceGrants(prefs, () -> {}).smsReadAllowed());
              calls.incrementAndGet();
            });
    assertFalse(grants.smsReadAllowed());
    assertTrue(grants.setSmsReadAllowed(true));
    assertTrue(grants.setSmsReadAllowed(false));
    assertEquals(2, calls.get());
    assertFalse(grants.smsReadAllowed());
  }

  private static SharedPreferences preferences(Map<String, Object> values) {
    return preferences(
        values,
        new java.util.concurrent.atomic.AtomicBoolean(),
        new java.util.concurrent.atomic.AtomicBoolean());
  }

  private static SharedPreferences preferences(
      Map<String, Object> values,
      java.util.concurrent.atomic.AtomicBoolean unreadable,
      java.util.concurrent.atomic.AtomicBoolean commitFailure) {
    return (SharedPreferences)
        Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(),
            new Class<?>[] {SharedPreferences.class},
            (proxy, method, args) -> {
              switch (method.getName()) {
                case "getString":
                case "getBoolean":
                  if (unreadable.get()) throw new IllegalStateException("FIXTURE_READ_UNAVAILABLE");
                  return values.getOrDefault(args[0], args[1]);
                case "edit":
                  Map<String, Object> pending = new HashMap<>();
                  return Proxy.newProxyInstance(
                      SharedPreferences.Editor.class.getClassLoader(),
                      new Class<?>[] {SharedPreferences.Editor.class},
                      (editor, call, input) -> {
                        if (call.getName().startsWith("put")) {
                          pending.put((String) input[0], input[1]);
                          return editor;
                        }
                        if (call.getName().equals("commit")) {
                          values.putAll(
                              pending); // Android may update the cache even when commit reports
                          // failure.
                          return !commitFailure.get();
                        }
                        throw new UnsupportedOperationException(call.getName());
                      });
                default:
                  throw new UnsupportedOperationException(method.getName());
              }
            });
  }

  @Test
  public void modeAndExactRulesPersistAcrossOwnersAndInvalidateBeforePublication() {
    var values = new HashMap<String, Object>();
    var prefs = preferences(values);
    AtomicInteger invalidations = new AtomicInteger();
    var grants =
        new DeviceGrants(
            prefs,
            () -> {
              assertEquals(
                  invalidations.get(), new DeviceGrants(prefs, () -> {}).screenPolicy().revision);
              invalidations.incrementAndGet();
            });
    assertEquals(ScreenConfirmationPolicy.Mode.MANUAL, grants.screenPolicy().mode);
    assertTrue(
        grants.setScreenPolicy(
            ScreenConfirmationPolicy.Mode.CUSTOM,
            Map.of("com.bank.pay", ScreenConfirmationPolicy.Rule.ALLOW)));
    assertTrue(new DeviceGrants(prefs, () -> {}).screenPolicy().allows("com.bank.pay"));
    assertTrue(
        grants.setScreenPolicy(
            ScreenConfirmationPolicy.Mode.ALLOW_ALL, grants.screenPolicy().rules));
    assertEquals(2, invalidations.get());
    assertTrue(grants.setScreenPolicy(ScreenConfirmationPolicy.Mode.MANUAL, Map.of()));
    assertFalse(grants.screenPolicy().allows("com.bank.pay"));
    assertEquals(3, grants.screenPolicy().revision);
  }
}
