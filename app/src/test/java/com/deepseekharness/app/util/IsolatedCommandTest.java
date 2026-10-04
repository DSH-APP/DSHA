package com.deepseekharness.app.util;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class IsolatedCommandTest {
  @Test
  public void statusPreservesSuccessAndFailureIncludingEchoNewline() {
    assertEquals(0, IsolatedCommand.status("0\n"));
    assertEquals(2, IsolatedCommand.status("2\n"));
    assertEquals(125, IsolatedCommand.status("125\n"));
    assertEquals(255, IsolatedCommand.status("255\n"));
    assertEquals(0, IsolatedCommand.status("0"));
  }

  @Test
  public void missingOrCorruptStatusIsNeverSuccess() {
    for (String value :
        new String[] {null, "", "\n", "256\n", "-1", "0\n2", "0\n\n", " 0", "done", "0;exit"})
      assertEquals(-1, IsolatedCommand.status(value));
  }

  @Test
  public void scriptPreservesArgumentBoundariesAndRefusesFailedStatusPublication() {
    String script =
        IsolatedCommand.script(
            Arrays.asList("/path/tool", "a'b", "$(touch sentinel)"), "/tmp/status'file");
    assertTrue(script.contains("'a'\\''b' '$(touch sentinel)'"));
    assertTrue(script.startsWith("IFS= read -r DSHA_START || exit 125\n"));
    assertTrue(script.contains("echo \"$result\" > '/tmp/status'\\''file' || exit 125\n"));
    assertFalse(script.contains("printf"));
    assertTrue(script.endsWith("kill -STOP $$\nexit 125\n"));
  }
}
