package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.content.Context;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.CredentialRead;
import java.io.IOException;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Real pipeline and credential states; private synthetic preference records only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class LegacyNativeCredentialFallbackTest {
  private static final String KEY = "owned-synthetic-legacy-key-519";

  private static final class Case implements StartupPipeline.Runtime, StartupPipeline.Diagnostics {
    final StartupPipelineTest.Fixture delegate = new StartupPipelineTest.Fixture();
    final CredentialRead read;
    final List<String> warnings = new ArrayList<>();

    Case(CredentialRead read) {
      this.read = read;
    }

    public void credentials() {
      read.requireValue();
    }

    public void stopLan() {
      delegate.stopLan();
    }

    public String stopWeb() throws IOException {
      return delegate.stopWeb();
    }

    public void tools() throws Exception {
      delegate.tools();
    }

    public boolean ready() {
      return delegate.ready();
    }

    public boolean offlineBundle() {
      return delegate.offlineBundle();
    }

    public void extract() throws Exception {
      delegate.extract();
    }

    public String migration(String id) throws Exception {
      return delegate.migration(id);
    }

    public String register() throws Exception {
      return delegate.register();
    }

    public String legacyPlugins() throws Exception {
      return delegate.legacyPlugins();
    }

    public BoundedProcessRunner.Result materializeCasLinks() throws Exception {
      return delegate.materializeCasLinks();
    }

    public String recordId() {
      return delegate.recordId();
    }

    public void message(long generation, String value) {
      warnings.add(value);
    }

    public void issue(long generation, String plugin, String value) {
      delegate.issue(generation, plugin, value);
    }
  }

  @Test
  public void availableKeyIsTheOnlyInjectedStateAndDoesNotProduceWarning() {
    List<String> warnings = new ArrayList<>();
    var read = CredentialRead.available(KEY);
    assertEquals(
        Map.of("DEEPSEEK_API_KEY", KEY),
        StartupPipeline.legacyCredentialEnvironment(read, warnings::add));
    assertTrue(warnings.isEmpty());
    assertEquals(CredentialRead.State.AVAILABLE, read.state);
    assertEquals(KEY, read.requireValue());
  }

  @Test
  public void notConfiguredOmitsEnvironmentWithoutInventingAnError() {
    List<String> warnings = new ArrayList<>();
    var read = CredentialRead.missing();
    assertTrue(StartupPipeline.legacyCredentialEnvironment(read, warnings::add).isEmpty());
    assertTrue(warnings.isEmpty());
    assertEquals(CredentialRead.State.NOT_CONFIGURED, read.state);
  }

  private void unavailableEnvironment(CredentialRead.Reason... reasons) {
    for (var reason : reasons) {
      List<String> warnings = new ArrayList<>();
      var read = CredentialRead.failed(reason);
      assertTrue(StartupPipeline.legacyCredentialEnvironment(read, warnings::add).isEmpty());
      assertEquals(1, warnings.size());
      assertTrue(warnings.get(0).contains(read.state.name()));
      assertTrue(warnings.get(0).contains(reason.name()));
      assertFalse(warnings.get(0).contains(KEY));
      assertFalse(read.usable());
      assertSame(read, assertThrows(CredentialRead.Unavailable.class, read::requireValue).result);
    }
  }

  @Test
  public void temporaryStatesAreWarnedAndRemainUnavailable() {
    unavailableEnvironment(
        CredentialRead.Reason.DEVICE_LOCKED,
        CredentialRead.Reason.AUTHENTICATION_REQUIRED,
        CredentialRead.Reason.TRANSIENT_STORE);
  }

  @Test
  public void attentionStatesAreWarnedAndRemainUnavailable() {
    unavailableEnvironment(
        CredentialRead.Reason.KEY_MISSING,
        CredentialRead.Reason.KEY_INVALIDATED,
        CredentialRead.Reason.UNREADABLE);
  }

  @Test
  public void allUnavailableStatesContinueTheRealOrdinaryPipelineWithExplicitDiagnostics()
      throws Exception {
    for (var reason : CredentialRead.Reason.values())
      if (reason != CredentialRead.Reason.NONE) {
        Case c = new Case(CredentialRead.failed(reason));
        assertEquals("web", new StartupPipeline(c, c).prepare(1, false, c.delegate));
        assertTrue(c.delegate.calls.contains("stop"));
        assertTrue(c.delegate.calls.contains("unlock"));
        assertTrue(
            c.warnings.stream()
                .anyMatch(
                    s ->
                        s.contains(
                            "LEGACY_NATIVE_KEY_" + c.read.state.name() + "/" + reason.name())));
        assertTrue(c.warnings.stream().noneMatch(s -> s.contains(KEY)));
        assertThrows(CredentialRead.Unavailable.class, c.read::requireValue);
      }
  }

  @Test
  public void safeWebConfigurationAlsoOpensWithoutChangingTheUnavailableRecord() throws Exception {
    Case c = new Case(CredentialRead.failed(CredentialRead.Reason.KEY_INVALIDATED));
    assertEquals(
        "dsha-recovery-0123456789abcdef", new StartupPipeline(c, c).prepare(2, true, c.delegate));
    assertFalse(c.delegate.calls.contains("register"));
    assertTrue(c.delegate.calls.contains("unlock"));
    assertTrue(c.warnings.stream().anyMatch(s -> s.contains("KEY_INVALIDATED")));
    assertThrows(CredentialRead.Unavailable.class, c.read::requireValue);
  }

  @Test
  public void availableAndMissingPipelineHaveNoLegacyCredentialWarning() throws Exception {
    for (var read : List.of(CredentialRead.available(KEY), CredentialRead.missing())) {
      Case c = new Case(read);
      assertEquals("web", new StartupPipeline(c, c).prepare(3, false, c.delegate));
      assertTrue(c.warnings.stream().noneMatch(s -> s.startsWith("LEGACY_NATIVE_KEY_")));
    }
  }

  @Test
  public void nonCredentialFailureStillBlocksBeforeAnyStopOrPublication() throws Exception {
    var f = new StartupPipelineTest.Fixture();
    f.failure = "credentials";
    assertThrows(IOException.class, () -> new StartupPipeline(f, f).prepare(1, false, f));
    assertEquals(List.of("credentials"), f.calls);
  }

  @Test
  public void actualConfigStoreUnreadableOpaqueRecordsRemainByteForByteAndDistinctFromMissing()
      throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    var prefs = context.getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE);
    ConfigStore config = new ConfigStore(context);
    for (Object stored : List.of(23, "opaque-unreadable-synthetic-native-cipher")) {
      var editor = prefs.edit().clear();
      if (stored instanceof Integer) editor.putInt(Constants.KEY_API_KEY, (Integer) stored);
      else editor.putString(Constants.KEY_API_KEY, (String) stored);
      assertTrue(editor.commit());
      var before = new HashMap<>(prefs.getAll());
      var read = config.readApiKey();
      assertFalse(read.usable());
      assertNotEquals(CredentialRead.State.NOT_CONFIGURED, read.state);
      Case c = new Case(read);
      assertEquals("web", new StartupPipeline(c, c).prepare(4, false, c.delegate));
      List<String> warnings = new ArrayList<>();
      assertTrue(
          StartupPipeline.legacyCredentialEnvironment(config.readApiKey(), warnings::add)
              .isEmpty());
      assertEquals(before, prefs.getAll());
      assertNotEquals(CredentialRead.State.NOT_CONFIGURED, config.readApiKey().state);
      assertFalse(warnings.isEmpty());
      assertThrows(CredentialRead.Unavailable.class, config::getApiKey);
    }
  }
}
