package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.util.ManagedRuntimeLayout;
import com.deepseekharness.app.util.WebLaunchCommand;
import java.io.File;
import java.nio.file.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Production command publication with only Android descriptor syscalls adapted to host NOFOLLOW. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = android.app.Application.class,
    shadows = RuntimeToolsRegistryAndroidTest.HostFileSystem.class)
public final class RuntimePackageCommandsAndroidTest {
  private Path data, root;
  private Context context;
  private static final String ID = "a".repeat(64);
  private static final String OLD_PNPM =
      "#!/bin/sh\nexec /usr/local/bin/node /usr/local/lib/dsha-pnpm/bin/pnpm.cjs \"$@\"\n";
  private static final java.util.Set<String> dormantLinks = new java.util.HashSet<>();
  private String packagePath;

  public static final class OwnedApplication extends android.app.Application
      implements RuntimeHostPorts.Owner {
    private final RuntimeHostPorts ports = new RuntimeHostPorts();

    @Override
    public void onCreate() {
      super.onCreate();
      ports.install(
          new RuntimeHostPorts.Provider() {
            public RuntimeHostPorts.Settings snapshot() {
              return new RuntimeHostPorts.Settings("auto", false, true, false);
            }

            public void stage(String value) {}

            public void record(String kind, String value) {}

            public void failure(Throwable value) {}
          });
    }

    public RuntimeHostPorts runtimeHostPorts() {
      return ports;
    }
  }

  /** Only pre-existing guest-relative builtin links are adapted on Windows. */
  @org.robolectric.annotation.Implements(
      value = com.deepseekharness.app.backup.AndroidBackupFileSystem.class,
      isInAndroidSdk = false)
  public static class PackageFileSystem extends RuntimeToolsRegistryAndroidTest.HostFileSystem {
    @org.robolectric.annotation.Implementation
    public com.deepseekharness.app.backup.BackupFileSystem.Node stat(File file)
        throws java.io.IOException {
      if (dormantLinks.contains(file.getAbsolutePath()))
        return new com.deepseekharness.app.backup.BackupFileSystem.Node(
            "LINK", file.getAbsolutePath(), 0, 0, 1, 0777);
      return super.stat(file);
    }

    @org.robolectric.annotation.Implementation
    public String readLink(File file) throws java.io.IOException {
      if (dormantLinks.contains(file.getAbsolutePath()))
        return "../../usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules";
      return super.readLink(file);
    }
  }

  @Before
  public void prepare() throws Exception {
    data = Files.createTempDirectory("dsha-package-commands-");
    Path files = Files.createDirectories(data.resolve("files"));
    root = Files.createDirectories(files.resolve("linux/ubuntu"));
    packagePath = data.resolve("old-app.apk").toString();
    Files.writeString(Path.of(packagePath), "old APK identity");
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
          public String getPackageCodePath() {
            return packagePath;
          }
        };
    put("usr/local/lib/dsha-pnpm/bin/pnpm.cjs", "// fixture entry\n");
    put("usr/local/lib/dsha-pnpm/dist/pnpm.cjs", "// fixture distribution\n");
    put(
        "usr/local/lib/dsha-pnpm/package.json",
        new JSONObject()
            .put("name", "pnpm")
            .put("version", BuildConfig.BUNDLED_PNPM_VERSION)
            .toString());
    put("root/.dsha-pnpm-version", BuildConfig.BUNDLED_PNPM_VERSION + "\n");
    put("usr/local/lib/node_modules/npm/bin/npm-cli.js", "// fixture npm\n");
    put("usr/local/lib/node_modules/npm/bin/npx-cli.js", "// fixture npx\n");
  }

  private void put(String relative, String text) throws Exception {
    Path p = root.resolve(relative);
    Files.createDirectories(p.getParent());
    Files.writeString(p, text);
  }

  @After
  public void close() throws Exception {
    RuntimeTools.invalidate();
    dormantLinks.clear();
    try (var paths = Files.walk(data)) {
      for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
    }
  }

  private static String sha256Hex(byte[] data) throws Exception {
    return String.format(
        "%064x",
        new java.math.BigInteger(
            1, java.security.MessageDigest.getInstance("SHA-256").digest(data)));
  }

  private static void putTarHeader(byte[] header, int at, String value) {
    byte[] data = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    System.arraycopy(data, 0, header, at, data.length);
  }

  /** Single-member gzip ustar fixture, so the test does not need an extra tar dependency. */
  private static byte[] ghArchive(String member, byte[] body) throws Exception {
    byte[] header = new byte[512];
    putTarHeader(header, 0, member);
    putTarHeader(header, 100, "0000755");
    putTarHeader(header, 124, String.format("%011o", body.length));
    header[156] = (byte) '0';
    java.util.Arrays.fill(header, 148, 156, (byte) ' ');
    int sum = 0;
    for (byte value : header) sum += value & 255;
    putTarHeader(header, 148, String.format("%06o", sum));
    var raw = new java.io.ByteArrayOutputStream();
    raw.write(header);
    raw.write(body);
    raw.write(new byte[(512 - body.length % 512) % 512]);
    raw.write(new byte[1024]);
    var compressed = new java.io.ByteArrayOutputStream();
    try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) {
      gzip.write(raw.toByteArray());
    }
    return compressed.toByteArray();
  }

  private android.content.res.AssetManager packageAssets() throws Exception {
    return packageAssets(false, "gh-cli.tar.gz");
  }

  private android.content.res.AssetManager packageAssets(boolean corruptGhDigest) throws Exception {
    return packageAssets(corruptGhDigest, "gh-cli.tar.gz");
  }

  /** gh 在 APK 内的打包名：build330 起为 gh-cli.bin，旧包与夹具仍是源名。 */
  private android.content.res.AssetManager packageAssets(boolean corruptGhDigest, String ghEntry)
      throws Exception {
    Path archive = Files.createTempFile("dsha-package-assets-", ".apk");
    archive.toFile().deleteOnExit();
    byte[] ghBinary =
        "#!/bin/sh\necho fixture gh\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    byte[] ghTarball = ghArchive("gh_2.103.3_linux_arm64/bin/gh", ghBinary);
    String ghDigest = corruptGhDigest ? "0".repeat(64) : sha256Hex(ghBinary);
    var resources = new java.util.LinkedHashMap<String, String>();
    resources.put("runtime-descriptor.json", "{\"runtimeId\":\"" + ID + "\"}");
    resources.put(
        "managed-runtime-inputs.json",
        "{\"schema\":1,\"installs\":["
            + "{\"asset\":\"gh-wrapper.sh\",\"target\":\"root/dsh-bin/gh\",\"executable\":true},"
            + "{\"asset\":\"gh-proxy.mjs\",\"target\":\"usr/local/share/dsha/gh-proxy.mjs\",\"executable\":false},"
            + "{\"asset\":\"gh-cli.tar.gz\",\"target\":\"opt/gh/bin/gh\",\"executable\":true,"
            + "\"extract\":{\"member\":\"gh_2.103.3_linux_arm64/bin/gh\",\"sha256\":\""
            + ghDigest
            + "\"}}"
            + "]}");
    resources.put(
        "runtime-patches.json",
        new JSONObject()
            .put("schema", 1)
            .put("dshVersion", com.deepseekharness.app.util.Constants.DSH_VERSION)
            .put("active", new org.json.JSONArray())
            .toString());
    resources.put("builtin-plugins.json", "{}");
    resources.put("runtime-tools.json", "{}");
    resources.put("bridge-token-compat.cjs", "// isolated command fixture\n");
    resources.put("gh-wrapper.sh", "#!/bin/bash\nexec /opt/gh/bin/gh \"$@\"\n");
    resources.put("gh-proxy.mjs", "// isolated proxy fixture\n");
    try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(archive))) {
      for (var item : resources.entrySet()) {
        zip.putNextEntry(new java.util.zip.ZipEntry("assets/" + item.getKey()));
        zip.write(item.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.putNextEntry(new java.util.zip.ZipEntry("assets/" + ghEntry));
      zip.write(ghTarball);
      zip.closeEntry();
    }
    var constructor = android.content.res.AssetManager.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    var assets = constructor.newInstance();
    var add = assets.getClass().getDeclaredMethod("addAssetPath", String.class);
    add.setAccessible(true);
    assertTrue((int) add.invoke(assets, archive.toString()) > 0);
    Context original = context;
    context =
        new ContextWrapper(original) {
          @Override
          public android.content.res.AssetManager getAssets() {
            return assets;
          }
        };
    Files.createDirectories(
        root.resolve("usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules"));
    for (String name : com.deepseekharness.app.util.BuiltinPlugins.DEFAULT_BUILTINS) {
      Path directory =
          root.resolve(com.deepseekharness.app.util.BuiltinPlugins.entityDir(name).substring(1));
      Files.createDirectories(directory);
      dormantLinks.add(directory.resolve("node_modules").toFile().getAbsolutePath());
    }
    Path integration = root.resolve("root/dsha-app-integration");
    Files.createDirectories(integration);
    dormantLinks.add(integration.resolve("node_modules").toFile().getAbsolutePath());
    return assets;
  }

  @Test
  @Config(application = OwnedApplication.class, shadows = PackageFileSystem.class)
  public void sameRuntimeNewApkRefreshesThePreviouslyCachedWrapper() throws Exception {
    try (var assets = packageAssets()) {
      Files.writeString(
          root.getParent().resolve(".runtime-descriptor.json"), "{\"runtimeId\":\"" + ID + "\"}");
      put("root/.dsh/settings.yaml", "permissionMode: workspace-write\n");
      RuntimeTools.prepare(context, root.toFile());
      Path wrapper = root.resolve("root/dsh-bin/pnpm");
      assertTrue(Files.readString(wrapper).contains("package_import_method"));
      assertTrue(Files.isExecutable(root.resolve("root/dsh-bin/gh")));
      assertEquals(
          "// isolated proxy fixture\n",
          Files.readString(root.resolve("usr/local/share/dsha/gh-proxy.mjs")));
      // Model the prior APK's valid cached stamp after its old publication.
      Files.writeString(wrapper, OLD_PNPM);
      var stamp = RuntimeTools.class.getDeclaredMethod("stamp", File.class);
      stamp.setAccessible(true);
      var cached = RuntimeTools.class.getDeclaredField("preparedStamp");
      cached.setAccessible(true);
      cached.set(null, stamp.invoke(null, root.toFile()));
      RuntimeTools.prepare(context, root.toFile());
      assertEquals(OLD_PNPM, Files.readString(wrapper));
      packagePath = data.resolve("new-app.apk").toString();
      Files.writeString(Path.of(packagePath), "new APK identity");
      RuntimeTools.prepare(context, root.toFile());
      assertTrue(Files.readString(wrapper).contains("package_import_method"));
      assertEquals(
          "permissionMode: workspace-write\n",
          Files.readString(root.resolve("root/.dsh/settings.yaml")));
    }
  }

  @Test
  @Config(application = OwnedApplication.class, shadows = PackageFileSystem.class)
  public void sameApkStampDetectsAnOldWrapperAndStageAlsoPublishesCopyDefault() throws Exception {
    try (var assets = packageAssets()) {
      Files.delete(root.resolve("usr/local/lib/node_modules/npm/bin/npm-cli.js"));
      Files.delete(root.resolve("usr/local/lib/node_modules/npm/bin/npx-cli.js"));
      RuntimeTools.stage(context, root.toFile());
      Path wrapper = root.resolve("root/dsh-bin/pnpm");
      assertTrue(Files.readString(wrapper).contains("package_import_method"));
      Files.writeString(wrapper, OLD_PNPM);
      RuntimeTools.stage(context, root.toFile());
      assertTrue(Files.readString(wrapper).contains("package_import_method"));
      Path dsh = root.resolve("root/dsh-bin/dsh");
      Files.delete(dsh);
      RuntimeTools.stage(context, root.toFile());
      assertEquals(com.deepseekharness.app.util.DshEntrypoint.wrapper(), Files.readString(dsh));
    }
  }

  @Test
  public void missingCommandsArePublishedBeforeTheWebHostCanSpawnChildren() throws Exception {
    RuntimeTools.preparePackageCommands(context, root.toFile(), true);
    JSONObject wrappers = new JSONObject();
    for (String name : new String[] {"dsh", "npm", "npx", "pnpm"}) {
      String content = Files.readString(root.resolve("root/dsh-bin/" + name));
      assertTrue(content.startsWith("#!/bin/sh\n"));
      assertTrue(content.contains("\nexec /usr/local/bin/node "));
      assertTrue(content.endsWith(" \"$@\"\n"));
      wrappers.put(name, content);
    }
    assertTrue(wrappers.getString("pnpm").contains("/usr/local/lib/dsha-pnpm/bin/pnpm.cjs"));
    assertEquals(com.deepseekharness.app.util.DshEntrypoint.wrapper(), wrappers.getString("dsh"));
    assertTrue(
        wrappers
            .getString("pnpm")
            .contains(
                "${npm_config_package_import_method-${NPM_CONFIG_PACKAGE_IMPORT_METHOD-copy}}"));
    assertFalse(wrappers.getString("npm").contains("package_import_method"));
    assertFalse(wrappers.getString("npx").contains("package_import_method"));
    String output = System.getProperty("dsha.packageCommandReceipt");
    if (output != null)
      Files.writeString(
          Path.of(output),
          new JSONObject()
              .put("wrappers", wrappers)
              .put("webCommand", WebLaunchCommand.build(true, "zh", "web", 0, 42))
              .put("pnpmVersion", BuildConfig.BUNDLED_PNPM_VERSION)
              .toString(2));
  }

  @Test
  public void staleNonemptyWrappersAreRepairedWithoutTouchingUserGlobalTools() throws Exception {
    for (String name : new String[] {"dsh", "npm", "npx", "pnpm"})
      put("root/dsh-bin/" + name, "stale entry\n");
    put("usr/local/bin/pnpm", "user-owned tool\n");
    put("usr/local/bin/dsh", "copied legacy global entry\n");
    RuntimeTools.preparePackageCommands(context, root.toFile(), true);
    for (String name : new String[] {"dsh", "npm", "npx", "pnpm"})
      assertFalse(Files.readString(root.resolve("root/dsh-bin/" + name)).contains("stale entry"));
    assertEquals("user-owned tool\n", Files.readString(root.resolve("usr/local/bin/pnpm")));
    assertEquals(
        "copied legacy global entry\n", Files.readString(root.resolve("usr/local/bin/dsh")));
  }

  @Test
  public void specialWrapperTargetsRemainRejectedAndAreNotRemoved() throws Exception {
    Path directory = Files.createDirectories(root.resolve("root/dsh-bin/pnpm"));
    Files.writeString(directory.resolve("user-file"), "keep");
    assertThrows(
        java.io.IOException.class,
        () -> RuntimeTools.preparePackageCommands(context, root.toFile(), true));
    assertEquals("keep", Files.readString(directory.resolve("user-file")));
  }

  @Test
  public void dshWrapperDoesNotOverwriteASpecialTarget() throws Exception {
    Path directory = Files.createDirectories(root.resolve("root/dsh-bin/dsh"));
    Files.writeString(directory.resolve("user-file"), "keep");
    assertThrows(
        java.io.IOException.class,
        () -> RuntimeTools.preparePackageCommands(context, root.toFile(), true));
    assertEquals("keep", Files.readString(directory.resolve("user-file")));
  }

  @Test
  public void runtimeSwitchCarriesTheBundledManagerAndItsCommandTogether() {
    assertTrue(ManagedRuntimeLayout.paths().contains("root/dsh-bin/dsh"));
    assertTrue(ManagedRuntimeLayout.paths().contains("usr/local/lib/dsha-pnpm"));
    assertTrue(ManagedRuntimeLayout.paths().contains("root/.dsha-pnpm-version"));
    assertTrue(ManagedRuntimeLayout.paths().contains("root/dsh-bin/pnpm"));
    assertTrue(ManagedRuntimeLayout.paths().contains("opt/gh/bin/gh"));
    assertFalse(ManagedRuntimeLayout.paths().contains("usr/local/bin/pnpm"));
  }

  @Test
  @Config(application = OwnedApplication.class, shadows = PackageFileSystem.class)
  public void managedReleaseArchiveInstallsOnlyThePinnedMember() throws Exception {
    try (var assets = packageAssets()) {
      Files.writeString(
          root.getParent().resolve(".runtime-descriptor.json"), "{\"runtimeId\":\"" + ID + "\"}");
      RuntimeTools.prepare(context, root.toFile());
      Path binary = root.resolve("opt/gh/bin/gh");
      assertTrue(Files.isExecutable(binary));
      assertEquals("#!/bin/sh\necho fixture gh\n", Files.readString(binary));
      Files.writeString(binary, "tampered");
      RuntimeTools.stage(context, root.toFile());
      assertEquals("#!/bin/sh\necho fixture gh\n", Files.readString(binary));
    }
  }

  @Test
  @Config(application = OwnedApplication.class, shadows = PackageFileSystem.class)
  public void managedReleaseArchiveReadsThePackagedBinName() throws Exception {
    // build330 的 APK 只有 assets/gh-cli.bin：AGP 不会 gunzip 非 .gz 成员，安装必须成功。
    try (var assets = packageAssets(false, "gh-cli.bin")) {
      Files.writeString(
          root.getParent().resolve(".runtime-descriptor.json"), "{\"runtimeId\":\"" + ID + "\"}");
      RuntimeTools.prepare(context, root.toFile());
      Path binary = root.resolve("opt/gh/bin/gh");
      assertTrue(Files.isExecutable(binary));
      assertEquals("#!/bin/sh\necho fixture gh\n", Files.readString(binary));
    }
  }

  @Test
  @Config(application = OwnedApplication.class, shadows = PackageFileSystem.class)
  public void managedReleaseArchiveRejectsAMismatchedMemberDigest() throws Exception {
    try (var assets = packageAssets(true)) {
      Files.writeString(
          root.getParent().resolve(".runtime-descriptor.json"), "{\"runtimeId\":\"" + ID + "\"}");
      java.io.IOException failure =
          assertThrows(
              java.io.IOException.class, () -> RuntimeTools.prepare(context, root.toFile()));
      boolean matched = false;
      for (Throwable cause = failure; cause != null; cause = cause.getCause())
        if (String.valueOf(cause.getMessage()).contains("MANAGED_INPUT_EXTRACT_DIGEST")) {
          matched = true;
          break;
        }
      assertTrue("unexpected failure: " + failure, matched);
      assertFalse(Files.exists(root.resolve("opt/gh/bin/gh")));
    }
  }
}
