package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import org.junit.Assume;

/** 仅显式源码模式可跳过缺失的外部夹具；已有输入仍由调用方完整校验。 */
public final class SourceOnlyTestInputs {
  static final String PROPERTY = "dsha.sourceOnlyTests";

  private SourceOnlyTestInputs() {}

  public static void requireRegularFile(Path input, String preparation) {
    if ("true".equals(System.getProperty(PROPERTY))
        && Files.notExists(input, LinkOption.NOFOLLOW_LINKS)) {
      Assume.assumeTrue("source-only: 缺少真实输入 " + input + "；" + preparation, false);
    }
    assertTrue("测试输入缺失或不是普通文件: " + input + "；" + preparation, Files.isRegularFile(input));
  }
}
