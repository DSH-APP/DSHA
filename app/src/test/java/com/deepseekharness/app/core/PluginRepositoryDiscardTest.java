package com.deepseekharness.app.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.deepseekharness.app.util.BoundedProcessRunner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

@RunWith(PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public class PluginRepositoryDiscardTest {
  private static final String OK =
      "PLUGIN_RESULT: {\"status\":\"ok\",\"message\":\"preview removed\"}\n";

  @Test
  public void listProjectionDoesNotTurnScopePrefixOrRuntimeLocationIntoBuiltinIdentity()
      throws Exception {
    for (String name :
        new String[] {"@deepseek-ai/dsh-ai-created", "dsh-ai-created", "@author/dsh-custom"}) {
      PluginRepository.Item item =
          new PluginRepository.Item(
              new org.json.JSONObject()
                  .put("name", name)
                  .put("builtin", true)
                  .put("official", true)
                  .put("runtimeLocated", true));
      assertFalse(item.builtin);
      assertFalse(item.official);
      assertFalse(item.runtimeProvided);
      assertEquals("user", item.origin);
    }
    PluginRepository.Item managed =
        new PluginRepository.Item(
            new org.json.JSONObject()
                .put("name", "@deepseek-ai/dsh-experimental-voice-input-bundle")
                .put("builtin", true)
                .put("runtimeProvided", true));
    assertFalse(managed.builtin);
    assertTrue(managed.runtimeProvided);
    assertEquals("dsh-managed", managed.origin);
    PluginRepository.Item signed =
        new PluginRepository.Item(
            new org.json.JSONObject().put("name", "dsh-web-mobile").put("builtin", true));
    assertTrue(signed.builtin);
    assertEquals("dsha-signed", signed.origin);
  }

  @Test
  public void metadataRecoveryStateChangesTheCardAndSurvivesIntentReceipt() throws Exception {
    var json = new org.json.JSONObject().put("name", "damaged-plugin").put("deletable", true);
    PluginRepository.Item before = new PluginRepository.Item(json);
    PluginRepository.Item damaged =
        new PluginRepository.Item(json.put("metadataError", "PLUGIN_METADATA_UNREADABLE"));
    assertEquals(before.stableKey, damaged.stableKey);
    assertFalse(before.contentKey.equals(damaged.contentKey));
    assertEquals("PLUGIN_METADATA_UNREADABLE", damaged.withIntent(false).metadataError);
    assertTrue(damaged.withIntent(false).deletable);
  }

  @Test
  public void onlyCompletedOkResultConfirmsPreviewRemoval() throws Exception {
    PluginRepository.requireDiscardResult(result(OK, 0, 1024));
    IOException exited =
        assertThrows(
            IOException.class, () -> PluginRepository.requireDiscardResult(result(OK, 7, 1024)));
    assertTrue(exited.getMessage().contains("PLUGIN_DISCARD_PREVIEW_EXIT_7"));
    IOException rejected =
        assertThrows(
            IOException.class,
            () ->
                PluginRepository.requireDiscardResult(
                    result(
                        "PLUGIN_RESULT: {\"status\":\"error\",\"message\":\"still retained\"}\n",
                        0,
                        1024)));
    assertTrue(rejected.getMessage().contains("still retained"));
  }

  @Test
  public void missingOrTruncatedResultCannotClaimCancellation() throws Exception {
    assertThrows(
        IOException.class,
        () -> PluginRepository.requireDiscardResult(result("no marker\n", 0, 1024)));
    IOException truncated =
        assertThrows(
            IOException.class, () -> PluginRepository.requireDiscardResult(result(OK, 0, 8)));
    assertTrue(truncated.getMessage().contains("PLUGIN_DISCARD_PREVIEW_OUTPUT_TRUNCATED"));
  }

  @Test
  public void anOkLineBeforeTimeoutDoesNotConfirmDiscard() throws Exception {
    Child child = new Child(OK, 0, true);
    BoundedProcessRunner.Result execution =
        BoundedProcessRunner.collect(child, 100, 1024, Process::destroy);
    assertTrue(execution.timedOut);
    assertEquals(OK, execution.output);
    IOException failure =
        assertThrows(IOException.class, () -> PluginRepository.requireDiscardResult(execution));
    assertTrue(failure.getMessage().contains("PLUGIN_DISCARD_PREVIEW_TIMEOUT"));
  }

  @Test
  public void duplicateOrMalformedSuccessReceiptsCannotClaimPreviewWasRemoved() throws Exception {
    for (String output :
        new String[] {
          OK + OK,
          "PLUGIN_RESULT: {\"status\":\"ok\",\"status\":\"error\",\"message\":\"ambiguous\"}\n",
          "PLUGIN_RESULT: {\"status\":\"ok\"}\n",
          "PLUGIN_RESULT: {\"status\":true,\"message\":\"not a status\"}\n",
          "PLUGIN_RESULT: {\"status\":\"ok\",\"message\":false}\n",
          "PLUGIN_RESULT: {\"status\":\"ok\",\"message\":\"removed\"} {}\n",
          "PLUGIN_RESULT: {\"status\":\"partial\",\"message\":\"still retained\"}\n"
        }) {
      assertThrows(
          output,
          IOException.class,
          () -> PluginRepository.requireDiscardResult(result(output, 0, 4096)));
    }
    assertThrows(IOException.class, () -> PluginRepository.requireDiscardResult(null));
  }

  @Test
  public void deletionRequiresExactVerifiedTargetAndBooleanReceipt() throws Exception {
    org.json.JSONObject good =
        new org.json.JSONObject()
            .put("status", "ok")
            .put("name", "example-plugin")
            .put("removed", true)
            .put("deletionVerified", true);
    PluginRepository.requireDeletionReceipt(good, "example-plugin");
    assertThrows(IOException.class, () -> PluginRepository.requireDeletionReceipt(good, "other"));
    for (String key : new String[] {"removed", "deletionVerified"}) {
      org.json.JSONObject malformed = new org.json.JSONObject(good.toString()).put(key, "true");
      assertThrows(
          IOException.class,
          () -> PluginRepository.requireDeletionReceipt(malformed, "example-plugin"));
    }
  }

  @Test
  public void stableKeysKeepSourceAndVersionChangesButSeparateProfilesAndSessions()
      throws Exception {
    org.json.JSONObject base =
        new org.json.JSONObject().put("name", "same-name").put("version", "1");
    PluginRepository.Item installed = new PluginRepository.Item(base);
    PluginRepository.Item newer =
        new PluginRepository.Item(
            new org.json.JSONObject(base.toString())
                .put("version", "2")
                .put("source", "new display source")
                .put("location", "new location"));
    assertEquals(installed.stableKey, newer.stableKey);
    assertFalse(
        installed.stableKey.equals(
            new PluginRepository.Item(
                    new org.json.JSONObject(base.toString()).put("profile", "other"))
                .stableKey));
    PluginRepository.Item first =
        new PluginRepository.Item(
            new org.json.JSONObject(base.toString())
                .put("dynamic", true)
                .put("sessionId", "prefix-one-same-tail")
                .put("pluginId", "p")
                .put("runtimeGeneration", 11));
    PluginRepository.Item second =
        new PluginRepository.Item(
            new org.json.JSONObject(base.toString())
                .put("dynamic", true)
                .put("sessionId", "prefix-two-same-tail")
                .put("pluginId", "p")
                .put("runtimeGeneration", 11));
    assertFalse(installed.stableKey.equals(first.stableKey));
    assertFalse(first.stableKey.equals(second.stableKey));
  }

  @Test
  public void intentNeverClaimsNewRuntimeAndContentComparisonIgnoresJsonKeyOrder()
      throws Exception {
    PluginRepository.Item before =
        new PluginRepository.Item(
            new org.json.JSONObject()
                .put("name", "example-plugin")
                .put("enabled", true)
                .put("runtimeState", "active")
                .put("runtimeGeneration", 11));
    PluginRepository.Item after = before.withIntent(false);
    assertFalse(after.enabled);
    assertTrue(after.restartRequired);
    assertEquals("active", after.runtimeState);
    assertEquals(11, after.runtimeGeneration);
    PluginRepository.Item reordered =
        new PluginRepository.Item(
            new org.json.JSONObject()
                .put("runtimeGeneration", 11)
                .put("runtimeState", "active")
                .put("enabled", true)
                .put("name", "example-plugin"));
    assertEquals(before.contentKey, reordered.contentKey);
  }

  private static BoundedProcessRunner.Result result(String output, int code, int maxBytes)
      throws Exception {
    return BoundedProcessRunner.collect(new Child(output, code), 1000, maxBytes, Process::destroy);
  }

  private static final class Child extends Process {
    private final InputStream output;
    private final int code;
    private boolean running;

    Child(String output, int code) {
      this(output, code, false);
    }

    Child(String output, int code, boolean running) {
      this.output = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
      this.code = code;
      this.running = running;
    }

    @Override
    public OutputStream getOutputStream() {
      return new ByteArrayOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return output;
    }

    @Override
    public InputStream getErrorStream() {
      return new ByteArrayInputStream(new byte[0]);
    }

    @Override
    public int waitFor() {
      throw new AssertionError("No unbounded wait");
    }

    @Override
    public int exitValue() {
      if (running) throw new IllegalThreadStateException("仍未退出");
      return code;
    }

    @Override
    public void destroy() {
      running = false;
    }
  }
}
