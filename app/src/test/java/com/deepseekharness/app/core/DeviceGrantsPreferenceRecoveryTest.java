package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import com.deepseekharness.app.util.NativeConsentPolicy;
import com.deepseekharness.app.util.ScreenConfirmationPolicy;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Actual Android preference reads and edits, including the legacy Boolean/wrong-schema boundary. */
@RunWith(DeviceGrantsPreferenceRecoveryTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class DeviceGrantsPreferenceRecoveryTest {
  private static final String KEY = "native_global_consent_v1";

  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  @Before
  public void freshProcess() throws Exception {
    new DeviceGrantsTest().isolateProcessStateForIndependentPreferenceFixtures();
  }

  private static SharedPreferences preferences() {
    return RuntimeEnvironment.getApplication()
        .getSharedPreferences("consent-recovery-" + UUID.randomUUID(), Context.MODE_PRIVATE);
  }

  private static SharedPreferences reopenFromDisk(String name) throws Exception {
    var file =
        new java.io.File(
            RuntimeEnvironment.getApplication().getApplicationInfo().dataDir,
            "shared_prefs/" + name + ".xml");
    var constructor =
        Class.forName("android.app.SharedPreferencesImpl")
            .getDeclaredConstructor(java.io.File.class, int.class);
    constructor.setAccessible(true);
    return (SharedPreferences) constructor.newInstance(file, Context.MODE_PRIVATE);
  }

  @Test
  public void savedPolicyRemainsReadableAfterAnActualXmlDiskReload() throws Exception {
    String name = "consent-disk-" + UUID.randomUUID();
    var preferences =
        RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE);
    var grants = new DeviceGrants(preferences, () -> {});
    assertTrue(grants.setGlobalAllowed(true));
    long revision = grants.consentPolicy().revision();
    freshProcess();
    var reopened = new DeviceGrants(reopenFromDisk(name), () -> {});
    assertEquals(NativeConsentPolicy.ReadState.ON, reopened.consentSnapshot().readState());
    assertTrue(reopened.consentPolicy().currentGlobal(revision));
    assertTrue(reopened.setGlobalAllowed(false));
    freshProcess();
    var off = new DeviceGrants(reopenFromDisk(name), () -> {}).consentSnapshot();
    assertEquals(NativeConsentPolicy.ReadState.OFF, off.readState());
    assertTrue(off.revision() > revision);
    assertFalse(off.policy().globalAllow());
  }

  private static void screenRoundTrip(
      ScreenConfirmationPolicy.Mode mode,
      java.util.Map<String, ScreenConfirmationPolicy.Rule> rules)
      throws Exception {
    String name = "screen-policy-xml-" + UUID.randomUUID();
    var preferences =
        RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE);
    var invalidations = new AtomicInteger();
    var grants = new DeviceGrants(preferences, invalidations::incrementAndGet);
    assertTrue(grants.setScreenPolicy(mode, rules));
    assertEquals(1, invalidations.get());
    var disk = reopenFromDisk(name);
    assertFalse(disk.getString("screen_confirmation_v1", "").endsWith("\n"));
    var restored = new DeviceGrants(disk, () -> {}).screenPolicy();
    assertEquals(mode, restored.mode);
    assertEquals(rules, restored.rules);
    assertEquals(1, restored.revision);
    assertEquals(
        mode == ScreenConfirmationPolicy.Mode.ALLOW_ALL
            || mode == ScreenConfirmationPolicy.Mode.CUSTOM
                && rules.get("com.bank.pay") == ScreenConfirmationPolicy.Rule.ALLOW,
        restored.allows("com.bank.pay"));
  }

  @Test
  public void allowAllWithoutRulesSurvivesActualScreenPolicyXmlReload() throws Exception {
    screenRoundTrip(ScreenConfirmationPolicy.Mode.ALLOW_ALL, java.util.Map.of());
  }

  @Test
  public void customMultipleRulesSurviveActualScreenPolicyXmlReload() throws Exception {
    screenRoundTrip(
        ScreenConfirmationPolicy.Mode.CUSTOM,
        java.util.Map.of(
            "app.normal", ScreenConfirmationPolicy.Rule.ASK,
            "com.bank.pay", ScreenConfirmationPolicy.Rule.ALLOW,
            "org.browser", ScreenConfirmationPolicy.Rule.ASK));
  }

  @Test
  public void manualSurvivesActualScreenPolicyXmlReload() throws Exception {
    screenRoundTrip(ScreenConfirmationPolicy.Mode.MANUAL, java.util.Map.of());
  }

  @Test
  public void legacyScreenPolicyPollutedByXmlIndentationStaysManual() throws Exception {
    for (String original :
        new String[] {"1\nALLOW_ALL\n7\n", "1\nCUSTOM\n8\napp.normal=ASK\ncom.bank.pay=ALLOW\n"}) {
      String name = "screen-policy-legacy-" + UUID.randomUUID();
      var preferences =
          RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE);
      assertTrue(preferences.edit().putString("screen_confirmation_v1", original).commit());
      var disk = reopenFromDisk(name);
      assertFalse(original.equals(disk.getString("screen_confirmation_v1", "")));
      var restored = new DeviceGrants(disk, () -> {}).screenPolicy();
      assertEquals(ScreenConfirmationPolicy.Mode.MANUAL, restored.mode);
      assertEquals(0, restored.revision);
      assertTrue(restored.rules.isEmpty());
      assertFalse(restored.allows("com.bank.pay"));
    }
  }

  @Test
  public void serializerIndentedLegacyRecordNeedsExplicitOffRecoveryAndPreservesDiskEvidence()
      throws Exception {
    String name = "consent-legacy-xml-" + UUID.randomUUID();
    String original = "1\ntrue\n7\n    ";
    var preferences =
        RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE);
    preferences.edit().putString(KEY, original).commit();
    var grants = new DeviceGrants(preferences, () -> {});
    assertEquals(NativeConsentPolicy.ReadState.MALFORMED, grants.consentSnapshot().readState());
    assertFalse(grants.consentPolicy().globalAllow());
    var candidate = grants.prepareConsentRecovery();
    assertEquals(original, preferences.getString(KEY, null));
    assertTrue(grants.rebuildConsentDisabled(candidate));
    freshProcess();
    var disk = reopenFromDisk(name);
    assertPreserved(disk, original);
    assertEquals(
        NativeConsentPolicy.ReadState.OFF,
        new DeviceGrants(disk, () -> {}).consentSnapshot().readState());
  }

  private static long quarantines(SharedPreferences preferences) {
    return preferences.getAll().keySet().stream()
        .filter(
            key ->
                key.startsWith("native_global_consent_quarantine_v1_") && key.endsWith("_record"))
        .count();
  }

  private static void assertPreserved(SharedPreferences preferences, Object original) {
    Object encoded =
        original instanceof String text
            ? "utf8-base64:"
                + java.util.Base64.getEncoder()
                    .encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            : original;
    assertTrue(
        preferences.getAll().entrySet().stream()
            .anyMatch(
                entry ->
                    entry.getKey().startsWith("native_global_consent_quarantine_v1_")
                        && entry.getKey().endsWith("_value")
                        && encoded.equals(entry.getValue())));
  }

  @Test
  public void missingGlobalKeyInRealLegacyPreferencesShowsTheOffSwitch() {
    var preferences = preferences();
    preferences.edit().putBoolean("sms_read", true).commit();
    var grants = new DeviceGrants(preferences, () -> {});
    var snapshot = grants.consentSnapshot();
    assertEquals(NativeConsentPolicy.ReadState.UNCONFIGURED, snapshot.readState());
    assertEquals(0, snapshot.revision());
    assertTrue(snapshot.hasSwitchValue());
    assertFalse(snapshot.canRebuild());
    assertFalse(snapshot.policy().globalAllow());
    assertTrue(grants.smsReadAllowed());
    assertNull(grants.prepareConsentRecovery());
    assertFalse(preferences.contains(KEY));
  }

  @Test
  public void repeatedHealthyChoiceDoesNotRewriteOrAdvanceItsRevision() throws Exception {
    for (boolean enabled : new boolean[] {false, true}) {
      freshProcess();
      var preferences = preferences();
      String original = new NativeConsentPolicy(enabled, 17).encode();
      assertTrue(preferences.edit().putString(KEY, original).commit());
      var invalidations = new AtomicInteger();
      var changes = new AtomicInteger();
      SharedPreferences.OnSharedPreferenceChangeListener listener =
          (store, key) -> changes.incrementAndGet();
      preferences.registerOnSharedPreferenceChangeListener(listener);
      var grants = new DeviceGrants(preferences, invalidations::incrementAndGet);
      for (int i = 0; i < 5; i++) assertTrue(grants.setGlobalAllowed(enabled));
      assertEquals(original, preferences.getString(KEY, null));
      assertEquals(17, grants.consentPolicy().revision());
      assertEquals(0, invalidations.get());
      assertEquals(0, changes.get());
      preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }
  }

  @Test
  public void repeatedOwnersAndStatusReadsNeverPersistThePolicy() {
    var preferences = preferences();
    String original = new NativeConsentPolicy(true, 17).encode();
    assertTrue(preferences.edit().putString(KEY, original).commit());
    var before = preferences.getAll();
    var changes = new AtomicInteger();
    SharedPreferences.OnSharedPreferenceChangeListener listener =
        (store, key) -> changes.incrementAndGet();
    preferences.registerOnSharedPreferenceChangeListener(listener);
    for (int i = 0; i < 20; i++) {
      var grants =
          new DeviceGrants(
              preferences,
              () -> {
                throw new AssertionError("read invalidated policy");
              });
      assertEquals(NativeConsentPolicy.ReadState.ON, grants.consentSnapshot().readState());
      assertTrue(grants.consentPolicy().globalAllow());
      grants.effectiveScreenPolicy();
    }
    assertEquals(before, preferences.getAll());
    assertEquals(0, changes.get());
    preferences.unregisterOnSharedPreferenceChangeListener(listener);
  }

  @Test
  public void sameChoiceAfterAnUnsafeReadStillRequiresANewFence() {
    var preferences = preferences();
    var invalidations = new AtomicInteger();
    var grants = new DeviceGrants(preferences, invalidations::incrementAndGet);
    assertTrue(grants.setGlobalAllowed(true));
    long previous = grants.consentPolicy().revision();
    String original = preferences.getString(KEY, null);
    preferences.edit().putString(KEY, "invalid").commit();
    assertFalse(grants.consentPolicy().globalAllow());
    preferences.edit().putString(KEY, original).commit();
    assertEquals(
        NativeConsentPolicy.ReadState.RECONFIRM_REQUIRED, grants.consentSnapshot().readState());
    assertFalse(grants.consentPolicy().globalAllow());
    assertTrue(grants.setGlobalAllowed(true));
    assertTrue(grants.consentPolicy().revision() > previous);
    assertFalse(grants.consentPolicy().currentGlobal(previous));
    assertEquals(2, invalidations.get());
  }

  @Test
  public void corruptBooleanRequiresExplicitRecoveryAndRetainsItsOriginalType() {
    var preferences = preferences();
    preferences.edit().putBoolean(KEY, true).putBoolean("sms_read", true).commit();
    var invalidations = new AtomicInteger();
    var grants =
        new DeviceGrants(
            preferences,
            () -> {
              assertTrue(preferences.getBoolean(KEY, false));
              assertEquals(1, quarantines(preferences));
              assertFalse(new DeviceGrants(preferences, () -> {}).consentPolicy().globalAllow());
              invalidations.incrementAndGet();
            });
    var broken = grants.consentSnapshot();
    assertEquals(NativeConsentPolicy.ReadState.MALFORMED, broken.readState());
    assertFalse(broken.hasSwitchValue());
    assertTrue(broken.canRebuild());
    assertFalse(grants.setGlobalAllowed(true));
    var candidate = grants.prepareConsentRecovery();
    assertNotNull(candidate);
    assertEquals(0, quarantines(preferences));
    assertTrue(preferences.getBoolean(KEY, false));
    assertTrue(grants.rebuildConsentDisabled(candidate));
    assertPreserved(preferences, true);
    assertTrue(preferences.getBoolean("sms_read", false));
    assertEquals(1, invalidations.get());
    var recovered = new DeviceGrants(preferences, () -> {}).consentSnapshot();
    assertEquals(NativeConsentPolicy.ReadState.OFF, recovered.readState());
    assertTrue(recovered.hasSwitchValue());
    assertFalse(recovered.policy().globalAllow());
    assertTrue(recovered.revision() > broken.revision());
    assertFalse(grants.rebuildConsentDisabled(candidate));
    assertEquals(1, quarantines(preferences));
  }

  @Test
  public void malformedStringRecoveryAdvancesPastAnyPreviouslyObservedGrant() {
    var preferences = preferences();
    var grants = new DeviceGrants(preferences, () -> {});
    assertTrue(grants.setGlobalAllowed(true));
    long old = grants.consentPolicy().revision();
    preferences.edit().putString(KEY, "bad legacy schema").commit();
    assertFalse(grants.consentPolicy().globalAllow());
    assertTrue(grants.rebuildConsentDisabled(grants.prepareConsentRecovery()));
    assertPreserved(preferences, "bad legacy schema");
    assertTrue(grants.consentPolicy().revision() > old);
    assertFalse(grants.consentPolicy().currentGlobal(old));
    assertFalse(grants.consentPolicy().globalAllow());
    assertTrue(grants.setGlobalAllowed(true)); // a separate explicit choice, never recovery itself
    assertFalse(grants.consentPolicy().currentGlobal(old));
  }

  @Test
  public void changedRecordCannotBeReplacedByAnOlderConfirmation() {
    var preferences = preferences();
    preferences.edit().putBoolean(KEY, true).commit();
    var grants = new DeviceGrants(preferences, () -> {});
    var candidate = grants.prepareConsentRecovery();
    preferences.edit().putBoolean(KEY, false).commit();
    assertFalse(grants.rebuildConsentDisabled(candidate));
    assertFalse(preferences.getBoolean(KEY, true));
    assertEquals(0, quarantines(preferences));
    assertFalse(grants.consentPolicy().globalAllow());
  }

  @Test
  public void aNewPolicyMutationInvalidatesTheOpenRecoveryConfirmation() {
    var preferences = preferences();
    preferences.edit().putBoolean(KEY, true).commit();
    var grants = new DeviceGrants(preferences, () -> {});
    var candidate = grants.prepareConsentRecovery();
    preferences.edit().putString(KEY, new NativeConsentPolicy(false, 7).encode()).commit();
    assertTrue(grants.setGlobalAllowed(false));
    String current = preferences.getString(KEY, null);
    assertFalse(grants.rebuildConsentDisabled(candidate));
    assertEquals(current, preferences.getString(KEY, null));
    assertEquals(0, quarantines(preferences));
  }

  @Test
  public void failedQuarantineNeverReplacesTheOriginalOrEnablesBypass() {
    assertFailedCommit(1);
  }

  @Test
  public void failedReplacementCommitKeepsThePreservedOriginalAndRemainsUnavailable() {
    assertFailedCommit(2);
  }

  private static void assertFailedCommit(int failedCommit) {
    var actual = preferences();
    actual.edit().putBoolean(KEY, true).commit();
    var count = new AtomicInteger();
    SharedPreferences wrapped =
        (SharedPreferences)
            Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class},
                (proxy, method, args) -> {
                  try {
                    Object result = method.invoke(actual, args);
                    if (!method.getName().equals("edit")) return result;
                    SharedPreferences.Editor editor = (SharedPreferences.Editor) result;
                    return Proxy.newProxyInstance(
                        SharedPreferences.Editor.class.getClassLoader(),
                        new Class<?>[] {SharedPreferences.Editor.class},
                        (unused, operation, values) -> {
                          try {
                            Object answer = operation.invoke(editor, values);
                            if (operation.getName().equals("commit")
                                && count.incrementAndGet() == failedCommit) return false;
                            return answer instanceof SharedPreferences.Editor ? unused : answer;
                          } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                          }
                        });
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    var grants = new DeviceGrants(wrapped, () -> {});
    assertFalse(grants.rebuildConsentDisabled(grants.prepareConsentRecovery()));
    if (failedCommit == 1) assertTrue(actual.getBoolean(KEY, false));
    assertFalse(grants.consentPolicy().globalAllow());
    assertPreserved(actual, true);
  }

  @Test
  public void invalidationFailureDoesNotReplaceTheOriginal() {
    var preferences = preferences();
    preferences.edit().putBoolean(KEY, true).commit();
    var grants =
        new DeviceGrants(
            preferences,
            () -> {
              throw new IllegalStateException("invalidation failed");
            });
    assertFalse(grants.rebuildConsentDisabled(grants.prepareConsentRecovery()));
    assertTrue(preferences.getBoolean(KEY, false));
    assertPreserved(preferences, true);
    assertFalse(grants.consentPolicy().globalAllow());
  }

  @Test
  public void unreadableOriginalCannotBeOverwrittenByRecovery() {
    var actual = preferences();
    actual.edit().putBoolean(KEY, true).commit();
    SharedPreferences unreadable =
        (SharedPreferences)
            Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(),
                new Class<?>[] {SharedPreferences.class},
                (proxy, method, args) -> {
                  if (method.getName().equals("getAll") || method.getName().equals("getString"))
                    throw new IllegalStateException("unavailable");
                  try {
                    return method.invoke(actual, args);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                });
    var grants = new DeviceGrants(unreadable, () -> {});
    assertEquals(
        NativeConsentPolicy.ReadState.READ_UNAVAILABLE, grants.consentSnapshot().readState());
    assertTrue(grants.consentSnapshot().canRebuild());
    assertNull(grants.prepareConsentRecovery());
    assertFalse(grants.rebuildConsentDisabled(null));
    assertTrue(actual.getBoolean(KEY, false));
    assertEquals(0, quarantines(actual));
    assertFalse(grants.consentPolicy().globalAllow());
  }
}
