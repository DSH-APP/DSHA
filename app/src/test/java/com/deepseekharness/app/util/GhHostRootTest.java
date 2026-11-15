package com.deepseekharness.app.util;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeNoException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public class GhHostRootTest {
  @Test
  public void resolvesTheRealRootWhenAndroidUsesTheDataAliasView() throws Exception {
    Path base = Files.createTempDirectory("dsha-gh-host-root-");
    Path real = base.resolve("data/data/io.example.app/files/linux/ubuntu");
    Files.createDirectories(real);
    Path alias = base.resolve("data/user/0/io.example.app/files/linux/ubuntu");
    Files.createDirectories(alias.getParent());
    try {
      Files.createSymbolicLink(alias, real);
    } catch (UnsupportedOperationException | java.io.IOException error) {
      assumeNoException("文件系统不支持符号链接，跳过别名视图用例", error);
    }
    assertEquals(real.toRealPath().toString(), GhHostRoot.resolve(alias.toFile()));
    assertEquals(real.toRealPath().toString(), GhHostRoot.resolve(real.toFile()));
  }

  @Test
  public void nullRootHasNoHostPath() {
    assertEquals("", GhHostRoot.resolve(null));
  }
}
