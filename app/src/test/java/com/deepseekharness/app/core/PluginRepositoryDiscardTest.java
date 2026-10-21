package com.deepseekharness.app.core;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import com.deepseekharness.app.util.BoundedProcessRunner;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
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

  private static BoundedProcessRunner.Result result(String output, int code, int maxBytes)
      throws Exception {
    return BoundedProcessRunner.collect(new Child(output, code), 1000, maxBytes, Process::destroy);
  }

  private static final class Child extends Process {
    private final InputStream output;
    private final int code;

    Child(String output, int code) {
      this.output = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
      this.code = code;
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
      return code;
    }

    @Override
    public void destroy() {}
  }
}
