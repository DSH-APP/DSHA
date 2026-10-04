package com.deepseekharness.app.util;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrivilegedContextFailureTest {
  @Test
  public void sdkAndRootCauseAreBoundedAndSecretsAreRedacted() {
    String value =
        PrivilegedContextFailure.describe(
            35,
            new IOException(
                "wrapper",
                new SecurityException(
                    "Authorization: Bearer sk-secret-test-token\n" + "x".repeat(1000))));
    assertTrue(value.contains("sdk=35"));
    assertTrue(value.contains("SecurityException"));
    assertFalse(value.contains("sk-secret-test-token"));
    assertFalse(value.contains("\n"));
    assertTrue(value.length() < 600);
  }
}
