package com.deepseekharness.app.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNoException;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;

/** T123：proot 运行根自我镜像绑定，保证 /proc/self/maps 里的宿主路径在 guest 内可回读。 */
public class ContainerRuntimeSelfMirrorTest {
  private static ContainerRuntime.Proot proot() {
    return new ContainerRuntime.Proot(
        null,
        new File("/nonexistent/libproot.so"),
        new RuntimeHostPorts.Settings("auto", false, true, true));
  }

  @Test
  public void bindsCanonicalRootfsWhenAndroidPassesTheDataAlias() throws Exception {
    Path base = Files.createTempDirectory("dsha-proot-selfmirror-");
    Path real =
        Files.createDirectories(base.resolve("data/data/io.example.app/files/linux/ubuntu"));
    Path alias = base.resolve("data/user/0/io.example.app/files/linux/ubuntu");
    Files.createDirectories(alias.getParent());
    try {
      Files.createSymbolicLink(alias, real);
    } catch (UnsupportedOperationException | java.io.IOException error) {
      assumeNoException("文件系统不支持符号链接，跳过别名用例", error);
    }

    List<String> argv = proot().baseArgv(alias.toFile(), true, null);
    String canonical = real.toRealPath().toString();
    String binding = canonical + ":" + canonical;
    int index = argv.indexOf(binding);
    assertTrue("缺少运行根自镜像: " + argv, index > 0);
    assertEquals("-b", argv.get(index - 1));
    assertEquals("自镜像只应出现一次: " + argv, 1, occurrences(argv, binding));
  }

  @Test
  public void keepsLink2SymlinkDirectoryBindingAlongsideTheSelfMirror() throws Exception {
    Path rootfs = Files.createTempDirectory("dsha-proot-selfmirror-l2s-");
    List<String> argv = proot().baseArgv(rootfs.toFile(), false, null);
    String canonical = rootfs.toRealPath().toString();
    assertTrue("缺少自镜像: " + argv, argv.contains(canonical + ":" + canonical));
    String l2s = canonical + "/.l2s";
    assertTrue("缺少 .l2s 绑定: " + argv, argv.contains(l2s + ":" + l2s));
  }

  private static int occurrences(List<String> values, String target) {
    int found = 0;
    for (String value : values) if (value.equals(target)) found++;
    return found;
  }
}
