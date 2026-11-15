package com.deepseekharness.app.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import org.junit.Test;

public final class RecoveryOverlayInputsTest {
  @Test
  public void recipesFollowTheIndependentlyLockedVersion() throws IOException {
    assertArrayEquals(
        new String[] {
          "web-integration/es-compat.js",
          "web-integration/startup.js",
          "web-integration/language.js",
          "recovery-recipes/0.2.1-rc.5/pdf-compat-patch.json",
          "recovery-recipes/0.2.1-rc.5/language-patch.json"
        },
        RecoveryOverlayInputs.forVersion("0.2.1-rc.5"));
  }

  @Test
  public void neverFallsBackToTheFormalTopLevelRecipes() throws IOException {
    var names = Arrays.asList(RecoveryOverlayInputs.forVersion("0.2.1-rc.3"));
    assertFalse(names.contains("pdf-compat-patch.json"));
    assertFalse(names.contains("language-patch.json"));
  }

  @Test
  public void rejectsVersionsThatCouldLeaveTheRecipeDirectory() {
    for (String bad :
        new String[] {null, "", "0.2", "../0.2.1", "0.2.1/../../x", "0.2.1-rc/5", "0.2.1-rc 5"}) {
      try {
        RecoveryOverlayInputs.forVersion(bad);
        fail("accepted " + bad);
      } catch (IOException expected) {
        assertEquals("RECOVERY_RECIPE_VERSION", expected.getMessage());
      }
    }
  }

  @Test
  public void onlyTheLanguageHelperGetsTheLargeBudget() {
    assertEquals(8 * 1024 * 1024, RecoveryOverlayInputs.limit("web-integration/language.js"));
    assertEquals(
        1024 * 1024,
        RecoveryOverlayInputs.limit("recovery-recipes/0.2.1-rc.5/language-patch.json"));
  }
}
