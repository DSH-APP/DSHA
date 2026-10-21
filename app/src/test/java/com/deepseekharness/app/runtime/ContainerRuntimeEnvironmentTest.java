package com.deepseekharness.app.runtime;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ContainerRuntimeEnvironmentTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();

  private void put(File directory, String name) throws Exception {
    Files.writeString(new File(directory, name).toPath(), "fixture");
  }

  @Test
  public void prootOwnsRequiredLoaderDependencyAndModeEnvironment() throws Exception {
    File nativeDir = temporary.newFolder(),
        base = temporary.newFolder(),
        lib = temporary.newFolder(),
        tmp = temporary.newFolder();
    for (String name :
        new String[] {"libproot_legacy.so", "libprootloader_legacy.so", "libprootloader32.so"})
      put(nativeDir, name);
    for (String name : new String[] {"libtalloc.so.2", "libandroid-shmem.so"}) put(lib, name);
    var runtime =
        new ContainerRuntime.Proot(
            null,
            new File(nativeDir, "libproot_legacy.so"),
            new RuntimeHostPorts.Settings("auto", false, true, true));
    var builder = new ProcessBuilder("fixture");
    runtime.applyEnv(builder, base, lib, tmp);
    assertEquals("1", builder.environment().get("PROOT_NO_SECCOMP"));
    assertEquals(
        new File(nativeDir, "libprootloader_legacy.so").getAbsolutePath(),
        builder.environment().get("PROOT_LOADER"));
    assertEquals(
        lib.getAbsolutePath() + ":" + nativeDir.getAbsolutePath(),
        builder.environment().get("LD_LIBRARY_PATH"));
    assertFalse(builder.environment().containsKey("DSH_CONFIRM"));
    Files.delete(new File(lib, "libtalloc.so.2").toPath());
    assertEquals(
        "PROOT_DEPENDENCIES_MISSING",
        assertThrows(IOException.class, () -> runtime.applyEnv(builder, base, lib, tmp))
            .getMessage());
  }

  @Test
  public void prorootAloneUsesIndependentProxyAndKeepsOriginalLinker() throws Exception {
    File directory = temporary.newFolder(),
        base = temporary.newFolder(),
        lib = temporary.newFolder(),
        tmp = temporary.newFolder();
    for (String name : ContainerRuntime.Proroot.LIBS) put(directory, name);
    var runtime = new ContainerRuntime.Proroot(null, directory, true);
    assertFalse(runtime.available());
    var builder = new ProcessBuilder("fixture");
    assertEquals(
        "PROROOT_COMPAT_PROXY_MISSING",
        assertThrows(IOException.class, () -> runtime.applyEnv(builder, base, lib, tmp))
            .getMessage());
    put(directory, "libdsha-proroot-compat.so");
    assertFalse(runtime.available());
    assertEquals(
        "PROROOT_COMPAT_ENTRY_MISSING",
        assertThrows(IOException.class, () -> runtime.applyEnv(builder, base, lib, tmp))
            .getMessage());
    put(directory, "libdsha-proroot-linker.so");
    assertTrue(runtime.available());
    runtime.applyEnv(builder, base, lib, tmp);
    assertEquals(
        new File(directory, "libproroot-runtime.so").getAbsolutePath(),
        builder.environment().get("PROROOT_LIB_PATH"));
    assertEquals(
        new File(directory, "libdsha-proroot-linker.so").getAbsolutePath(),
        builder.environment().get("PROROOT_LINKER_PATH"));
    assertEquals(
        new File(directory, "libproroot-stub-loader.so").getAbsolutePath(),
        builder.environment().get("PROROOT_STUB_LOADER"));
    assertFalse(builder.environment().containsKey("LD_PRELOAD"));
    var dynamic = new ContainerRuntime.Proroot(null, directory, false);
    dynamic.applyEnv(builder, base, lib, tmp);
    assertFalse(builder.environment().containsKey("PROROOT_STUB_LOADER"));
  }

  @Test
  public void bindsCannotBeMutatedByAnotherCaller() {
    assertThrows(UnsupportedOperationException.class, () -> ContainerRuntime.binds().clear());
    assertEquals("/dev", ContainerRuntime.binds().get(0).host());
  }
}
