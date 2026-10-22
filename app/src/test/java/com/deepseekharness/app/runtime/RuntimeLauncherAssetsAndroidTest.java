package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Real asset publication and host NOFOLLOW checks, without starting a guest process. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = android.app.Application.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = RuntimeLauncherAssetsAndroidTest.CountingFileSystem.class)
public final class RuntimeLauncherAssetsAndroidTest {
  private static final String BRIDGE = "// signed bridge fixture\n";
  private static final String CONSENT = "// signed consent fixture\n";
  private static final String PROOFS = "{\"schema\":1,\"packages\":[]}\n";
  private static int preloadReads;
  private static final java.util.Set<Path> modeledLinks = new java.util.HashSet<>();
  private Path temporary, files, root;
  private Context context;
  private AssetManager assets;
  private RuntimeHostPorts ports;

  @Implements(value = AndroidBackupFileSystem.class, isInAndroidSdk = false)
  public static final class CountingFileSystem
      extends RuntimeToolsRegistryAndroidTest.HostFileSystem {
    @Implementation
    public BackupFileSystem.Node stat(File file) throws IOException {
      if (modeledLinks.contains(file.toPath()))
        return new BackupFileSystem.Node("LINK", file.getAbsolutePath(), 0, 0, 1, 0777);
      return super.stat(file);
    }

    @Implementation
    public java.io.InputStream read(File file, BackupFileSystem.Node node) throws IOException {
      if (modeledLinks.contains(file.toPath()))
        throw new AssertionError("Launcher must never follow a LINK fact");
      if (file.getPath().contains("runtime-launcher-assets")
          && (file.getName().equals("bridge-token-compat.cjs")
              || file.getName().equals("native-consent.cjs"))) preloadReads++;
      return super.read(file, node);
    }
  }

  @Before
  public void prepare() throws Exception {
    temporary = Files.createTempDirectory("dsha-launcher-assets-");
    Path data = Files.createDirectories(temporary.resolve("data"));
    files = Files.createDirectories(data.resolve("files"));
    root = Files.createDirectories(files.resolve("linux/ubuntu"));
    Path archive = temporary.resolve("fixture.apk");
    try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
      for (var item :
          Map.of(
                  "bridge-token-compat.cjs", BRIDGE,
                  "native-consent.cjs", CONSENT,
                  "managed-package-proofs.json", PROOFS)
              .entrySet()) {
        zip.putNextEntry(new java.util.zip.ZipEntry("assets/" + item.getKey()));
        zip.write(item.getValue().getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
    }
    var constructor = AssetManager.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    assets = constructor.newInstance();
    var add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
    add.setAccessible(true);
    assertTrue((int) add.invoke(assets, archive.toString()) > 0);
    context =
        new ContextWrapper(RuntimeEnvironment.getApplication()) {
          @Override
          public android.content.pm.ApplicationInfo getApplicationInfo() {
            var info = new android.content.pm.ApplicationInfo(super.getApplicationInfo());
            info.dataDir = data.toString();
            return info;
          }

          @Override
          public File getFilesDir() {
            return files.toFile();
          }

          @Override
          public AssetManager getAssets() {
            return assets;
          }
        };
    ports = new RuntimeHostPorts();
    ports.install(
        new RuntimeHostPorts.Provider() {
          public RuntimeHostPorts.Settings snapshot() {
            return new RuntimeHostPorts.Settings("ipv4", false, true, false);
          }

          public void stage(String value) {}

          public void record(String kind, String detail) {}

          public void failure(Throwable error) {}
        });
    preloadReads = 0;
    modeledLinks.clear();
  }

  @After
  public void close() throws Exception {
    modeledLinks.clear();
    assets.close();
    try (var paths = Files.walk(temporary)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        try {
          Files.delete(path);
        } catch (java.nio.file.FileSystemException heldByAndroidResourceCache) {
          if (!System.getProperty("os.name").startsWith("Windows"))
            throw heldByAndroidResourceCache;
          path.toFile().deleteOnExit();
        }
      }
    }
  }

  private RuntimeLauncher launcher() {
    File directory = root.toFile();
    return new RuntimeLauncher(context, directory, directory, directory, directory, ports);
  }

  private LaunchSpec spec() {
    return LaunchSpec.runtime(RuntimeLauncherTest.fake())
        .pipedInput(true)
        .environment(Map.of("DSHA_MANAGED_PACKAGE_PROOFS_SHA256", "caller-must-not-win"))
        .build();
  }

  private Path boundPreloads(ProcessBuilder builder) {
    String suffix = ":" + RuntimeLauncherAssets.GUEST;
    return builder.command().stream()
        .filter(value -> value.endsWith(suffix))
        .findFirst()
        .map(value -> Path.of(value.substring(0, value.length() - suffix.length())))
        .orElseThrow();
  }

  private void linkFact(Path link, Path target) throws Exception {
    try {
      Files.createSymbolicLink(link, target);
    } catch (java.nio.file.FileSystemException missingHostSymlinkPrivilege) {
      if (!System.getProperty("os.name").startsWith("Windows")) throw missingHostSymlinkPrivilege;
      // Windows CI may lack SeCreateSymbolicLinkPrivilege. The fallback is an explicit
      // NOFOLLOW provider LINK fact, testing rejection rather than claiming a real link.
      modeledLinks.add(link);
      Files.writeString(link, "modeled LINK fact\n");
    }
  }

  private boolean isLinkFact(Path path) {
    return Files.isSymbolicLink(path) || modeledLinks.contains(path);
  }

  @Test
  public void oneBuilderVerifiesPreloadContentsOnceAndKeepsApkProof() throws Exception {
    Path prepared = RuntimeLauncherAssets.prepare(context).toPath();
    preloadReads = 0;
    ProcessBuilder builder = launcher().builder("true", spec());
    assertEquals(prepared, boundPreloads(builder));
    assertEquals(Integer.getInteger("dsha.expectedPreloadReads", 2).intValue(), preloadReads);
    assertTrue(builder.environment().get("NODE_OPTIONS").contains(RuntimeLauncherAssets.GUEST));
    String expected =
        com.deepseekharness.app.backup.BackupArchive.hex(
            com.deepseekharness.app.backup.BackupArchive.sha()
                .digest(PROOFS.getBytes(StandardCharsets.UTF_8)));
    assertEquals(expected, builder.environment().get("DSHA_MANAGED_PACKAGE_PROOFS_SHA256"));
  }

  @Test
  public void nextBuilderRechecksChangedBytesAndPreservesTheOriginal() throws Exception {
    var launcher = launcher();
    Path before = boundPreloads(launcher.builder("true", spec()));
    Files.writeString(before.resolve("bridge-token-compat.cjs"), "changed bytes\n");
    Path after = boundPreloads(launcher.builder("true", spec()));
    assertNotEquals(before, after);
    assertEquals("changed bytes\n", Files.readString(before.resolve("bridge-token-compat.cjs")));
    assertEquals(BRIDGE, Files.readString(after.resolve("bridge-token-compat.cjs")));
    assertEquals(CONSENT, Files.readString(after.resolve("native-consent.cjs")));
  }

  @Test
  public void standaloneEnvironmentStillPublishesAndChecksAssets() throws Exception {
    ProcessBuilder builder = new ProcessBuilder("fixture");
    launcher().environment(builder, spec());
    assertTrue(builder.environment().get("NODE_OPTIONS").contains(RuntimeLauncherAssets.GUEST));
    Path current = RuntimeLauncherAssets.prepare(context).toPath();
    assertEquals(BRIDGE, Files.readString(current.resolve("bridge-token-compat.cjs")));
    Files.writeString(current.resolve("native-consent.cjs"), "damaged\n");
    launcher().environment(builder, spec());
    Path repaired = RuntimeLauncherAssets.prepare(context).toPath();
    assertNotEquals(current, repaired);
    assertEquals(CONSENT, Files.readString(repaired.resolve("native-consent.cjs")));
  }

  @Test
  public void terminalAndRecoveryStyleSeparateCallsKeepTheirOwnValidation() throws Exception {
    RuntimeLauncherAssets.prepare(context);
    var launcher = launcher();
    var argv = new ArrayList<String>();
    preloadReads = 0;
    launcher.bindPreloads(argv);
    launcher.environment(new ProcessBuilder("fixture"), spec());
    assertEquals(4, preloadReads);
    assertEquals(List.of("-b", argv.get(1)), argv);
    assertTrue(argv.get(1).endsWith(":" + RuntimeLauncherAssets.GUEST));
  }

  @Test
  public void symlinkPreloadIsNotReadOrBoundAndItsTargetIsPreserved() throws Exception {
    Path before = RuntimeLauncherAssets.prepare(context).toPath();
    Path original = before.resolve("bridge-token-compat.cjs");
    Path outside = Files.writeString(temporary.resolve("outside.cjs"), "outside\n");
    Files.delete(original);
    linkFact(original, outside);
    Path after = boundPreloads(launcher().builder("true", spec()));
    assertNotEquals(before, after);
    assertTrue(isLinkFact(original));
    assertEquals("outside\n", Files.readString(outside));
    assertEquals(BRIDGE, Files.readString(after.resolve("bridge-token-compat.cjs")));
  }

  @Test
  public void symlinkAssetParentFailsWithoutTouchingOutside() throws Exception {
    Path outside = Files.createDirectories(temporary.resolve("outside"));
    Path marker = Files.writeString(outside.resolve("keep"), "retained\n");
    linkFact(files.resolve("runtime-launcher-assets"), outside);
    IOException error = assertThrows(IOException.class, () -> launcher().builder("true", spec()));
    assertEquals("LAUNCHER_ASSET_ROOT", error.getMessage());
    assertEquals("retained\n", Files.readString(marker));
  }
}
