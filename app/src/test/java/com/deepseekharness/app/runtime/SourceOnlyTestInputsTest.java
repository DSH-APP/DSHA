package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.AssumptionViolatedException;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** 夹具门禁自身不依赖离线包，必须在两种测试模式下执行。 */
public final class SourceOnlyTestInputsTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private String previous;

  @Before
  public void rememberMode() {
    previous = System.getProperty(SourceOnlyTestInputs.PROPERTY);
    System.clearProperty(SourceOnlyTestInputs.PROPERTY);
  }

  @After
  public void restoreMode() {
    if (previous == null) System.clearProperty(SourceOnlyTestInputs.PROPERTY);
    else System.setProperty(SourceOnlyTestInputs.PROPERTY, previous);
  }

  private Path missing() {
    return temporary.getRoot().toPath().resolve("missing-fixture");
  }

  @Test
  public void missingInputFailsByDefault() {
    assertThrows(
        AssertionError.class,
        () -> SourceOnlyTestInputs.requireRegularFile(missing(), "prepare fixture"));
  }

  @Test
  public void explicitFullModeRequiresInput() {
    System.setProperty(SourceOnlyTestInputs.PROPERTY, "false");
    assertThrows(
        AssertionError.class,
        () -> SourceOnlyTestInputs.requireRegularFile(missing(), "prepare fixture"));
  }

  @Test
  public void onlyExplicitSourceModeMaySkipMissingInput() {
    System.setProperty(SourceOnlyTestInputs.PROPERTY, "true");
    AssumptionViolatedException skipped =
        assertThrows(
            AssumptionViolatedException.class,
            () -> SourceOnlyTestInputs.requireRegularFile(missing(), "prepare fixture"));
    assertTrue(skipped.getMessage().contains("source-only"));
    assertTrue(skipped.getMessage().contains("prepare fixture"));
  }

  @Test
  public void otherPropertyValuesCannotEnableSkipping() {
    for (String value : new String[] {"", "TRUE", "1", "yes", " true "}) {
      System.setProperty(SourceOnlyTestInputs.PROPERTY, value);
      assertThrows(
          AssertionError.class,
          () -> SourceOnlyTestInputs.requireRegularFile(missing(), "prepare fixture"));
    }
  }

  @Test
  public void presentInputReachesConsumerValidationInBothModes() throws Exception {
    Path input = temporary.newFile("fixture").toPath();
    // 存在的空文件或损坏内容不能变成 skip，摘要和结构仍由真实消费者检查。
    for (String mode : new String[] {"false", "true"}) {
      System.setProperty(SourceOnlyTestInputs.PROPERTY, mode);
      for (String content : new String[] {"", "corrupt or stale fixture"}) {
        Files.writeString(input, content);
        SourceOnlyTestInputs.requireRegularFile(input, "prepare fixture");
        assertEquals(content, Files.readString(input));
      }
    }
  }

  @Test
  public void directoryIsNotAReasonToSkipInEitherMode() throws Exception {
    Path input = temporary.newFolder("fixture").toPath();
    for (String mode : new String[] {"false", "true"}) {
      System.setProperty(SourceOnlyTestInputs.PROPERTY, mode);
      assertThrows(
          AssertionError.class,
          () -> SourceOnlyTestInputs.requireRegularFile(input, "prepare fixture"));
    }
  }
}
