package com.deepseekharness.app.util;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginOutputTest {
  @Test
  public void resultSurvivesRuntimeExitTrailer() {
    String error = "{\"status\":\"error\",\"message\":\"certificate verify failed\"}";
    assertEquals(
        error,
        PluginOutput.resultJson(
            "PLUGIN_RESULT: " + error + "\r\n[proroot] child exited with code 1\n"));
  }

  @Test
  public void lastResultWinsWithoutMatchingEmbeddedText() {
    assertEquals(
        "new",
        PluginOutput.resultJson(
            "PLUGIN_RESULT: old\nlog PLUGIN_RESULT: spoof\nPLUGIN_RESULT: new\n"));
    assertEquals("", PluginOutput.resultJson("log PLUGIN_RESULT: spoof"));
  }

  @Test
  public void missingOrMalformedResultIsNotSuccess() {
    assertEquals("", PluginOutput.resultJson(null));
    assertEquals("", PluginOutput.resultJson("ERROR: failed"));
    assertEquals("broken", PluginOutput.resultJson("PLUGIN_RESULT: broken\n[proroot] exit 1"));
  }

  @Test
  public void discardRequiresOneStrictTypedResult() throws Exception {
    PluginOutput.requireDiscardSuccess(
        "notice\nPLUGIN_RESULT: {\"status\":\"ok\",\"message\":\"removed\"}\n[proroot] exited\n");
    for (String value :
        new String[] {
          "PLUGIN_RESULT: {\"status\":\"ok\",\"status\":\"error\",\"message\":\"removed\"}\n",
          "PLUGIN_RESULT: {\"status\":1,\"message\":\"removed\"}\n",
          "PLUGIN_RESULT: {\"status\":\"ok\"}\n",
          "PLUGIN_RESULT: {\"status\":\"ok\",\"message\":\"removed\"} trailing\n",
          "PLUGIN_RESULT: {\"status\":\"ok\",\"message\":\"removed\"}\nPLUGIN_RESULT: {\"status\":\"ok\",\"message\":\"removed\"}\n",
          "PLUGIN_RESULT: malformed\n"
        }) assertThrows(IOException.class, () -> PluginOutput.requireDiscardSuccess(value));
  }

  @Test
  public void discardRetainsActualScriptErrorMessage() {
    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                PluginOutput.requireDiscardSuccess(
                    "PLUGIN_RESULT: {\"status\":\"error\",\"message\":\"preview still retained\"}\n"));
    assertTrue(failure.getMessage().contains("preview still retained"));
  }
}
