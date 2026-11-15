package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import com.deepseekharness.app.backup.*;
import com.deepseekharness.app.util.BuiltinPluginRegistry;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

/** Real APK AssetManager -> production installer -> managed transaction candidate entities. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = android.app.Application.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = RuntimeToolsRegistryAndroidTest.HostFileSystem.class)
public final class RuntimeBuiltinDeploymentAndroidTest {
  private Path source, owned, files;
  private AssetManager assets;
  private Context context;
  private JSONObject manifest;

  @Before
  public void prepare() throws Exception {
    Path repository = Path.of("").toAbsolutePath();
    if (repository.getFileName().toString().equals("app")) repository = repository.getParent();
    source =
        Path.of(
            System.getProperty(
                "dsha.assetsDir", repository.resolve("app/src/main/assets").toString()));
    owned = Files.createTempDirectory("dsha-builtin-delivery-");
    Path data = Files.createDirectories(owned.resolve("app-data"));
    files = Files.createDirectories(data.resolve("files"));
    manifest = new JSONObject(Files.readString(source.resolve("managed-runtime-inputs.json")));
    Map<String, byte[]> content = new LinkedHashMap<>();
    for (String name :
        List.of(
            "managed-runtime-inputs.json",
            "builtin-plugins.json",
            "runtime-tools.json",
            "bridge-token-compat.cjs")) content.put(name, Files.readAllBytes(source.resolve(name)));
    JSONArray installs = manifest.getJSONArray("installs");
    for (int index = 0; index < installs.length(); index++) {
      String name = installs.getJSONObject(index).getString("asset");
      content.put(name, Files.readAllBytes(source.resolve(name)));
    }
    Path apk = owned.resolve("signed-resource-fixture.apk");
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(apk))) {
      for (var item : content.entrySet()) {
        output.putNextEntry(new ZipEntry("assets/" + item.getKey()));
        output.write(item.getValue());
        output.closeEntry();
      }
    }
    var constructor = AssetManager.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    assets = constructor.newInstance();
    var add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
    add.setAccessible(true);
    assertTrue((int) add.invoke(assets, apk.toString()) > 0);
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
  }

  @After
  public void close() throws Exception {
    RuntimeTools.invalidate();
    if (assets != null) assets.close();
    if (owned == null) return;
    try (var paths = Files.walk(owned)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
        try {
          Files.delete(path);
        } catch (FileSystemException heldByAndroidCache) {
          if (!System.getProperty("os.name").startsWith("Windows")) throw heldByAndroidCache;
          path.toFile().deleteOnExit();
        }
    }
  }

  private void install(Path root) throws Exception {
    Files.createDirectories(root);
    // This is exactly the private copier called by RuntimeTools.stage and normal overlay repair.
    var method =
        RuntimeTools.class.getDeclaredMethod("installManagedAssets", Context.class, File.class);
    method.setAccessible(true);
    try {
      method.invoke(null, context, root.toFile());
    } catch (InvocationTargetException error) {
      throw (Exception) error.getCause();
    }
  }

  private void helper(Path entity, String plugin, String file) throws Exception {
    assertArrayEquals(
        Files.readAllBytes(source.resolve("builtin-plugins/" + plugin + "/lib/" + file)),
        Files.readAllBytes(entity.resolve("lib/" + file)));
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var input = Files.newInputStream(file)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) > 0) digest.update(buffer, 0, read);
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  @Test
  public void actualInstallerCopiesEverySignedRowAndBothImportedHelperModules() throws Exception {
    Path root = files.resolve("linux/ubuntu");
    install(root);
    JSONArray installs = manifest.getJSONArray("installs");
    for (int index = 0; index < installs.length(); index++) {
      JSONObject row = installs.getJSONObject(index);
      String asset = row.getString("asset");
      Path target = root.resolve(row.getString("target"));
      JSONObject extract = row.optJSONObject("extract");
      if (extract != null) {
        // 单成员提取行发布的是成员字节，不是归档字节。
        assertEquals(asset, extract.getString("sha256"), sha256(target));
        continue;
      }
      byte[] expected = Files.readAllBytes(source.resolve(asset));
      // The production installer deliberately normalizes shell/Python line endings.
      // All JS, including the two imported helpers below, still requires exact bytes.
      if (asset.endsWith(".sh") || asset.endsWith(".py"))
        expected =
            new String(expected, StandardCharsets.UTF_8)
                .replace("\r\n", "\n")
                .getBytes(StandardCharsets.UTF_8);
      assertArrayEquals(asset, expected, Files.readAllBytes(target));
    }
    helper(root.resolve("root/dsha-status-overlay"), "dsh-status-overlay", "session-adapter.js");
    helper(
        root.resolve("root/dsha-computer-use-android"),
        "dsh-computer-use-android",
        "task-binding.js");
    var entriesMethod = RuntimeTrial.class.getDeclaredMethod("builtinEntries");
    entriesMethod.setAccessible(true);
    JSONArray entries = new JSONObject((String) entriesMethod.invoke(null)).getJSONArray("entries");
    assertEquals(BuiltinPluginRegistry.SIGNED.size(), entries.length());
    for (int index = 0; index < entries.length(); index++)
      assertTrue(Files.isRegularFile(root.resolve(entries.getString(index).substring(1))));
  }

  @Test
  public void managedCandidateMovesCompleteEntitiesAndPreservesCurrentUserFiles() throws Exception {
    Path current = Files.createDirectories(files.resolve("linux/ubuntu/root/.dsh"));
    Path user = current.resolve("settings.yaml");
    Files.writeString(user, "fixture-user-configuration");
    // Exercise the real transaction on its NOFOLLOW host port. Android's descriptor
    // tree optimization requires Linux /proc handles and cannot run on Windows.
    var fs = new JvmBackupFileSystem();
    var transaction = ManagedRuntimeTransaction.create(fs, files.toFile());
    Path staged = transaction.stage().toPath().resolve("linux/ubuntu");
    install(staged);
    byte[] descriptorBytes = Files.readAllBytes(source.resolve("runtime-descriptor.json"));
    JSONObject inputs =
        new JSONObject(new String(descriptorBytes, StandardCharsets.UTF_8)).getJSONObject("inputs");
    for (String asset :
        List.of(
            "managed-runtime-inputs.json",
            "builtin-plugins/dsh-status-overlay/lib/session-adapter.js",
            "builtin-plugins/dsh-computer-use-android/lib/task-binding.js"))
      assertEquals(
          "The candidate must use the actual current signed recipe: " + asset,
          HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(Files.readAllBytes(source.resolve(asset)))),
          inputs.getString(asset));
    RuntimeDescriptor descriptor =
        new RuntimeDescriptor(BackupJson.read(descriptorBytes, 2 * 1024 * 1024));
    transaction.prepare(
        List.of(
            "linux/ubuntu/root/dsha-status-overlay", "linux/ubuntu/root/dsha-computer-use-android"),
        descriptor,
        null,
        null,
        new BackupControl(null));
    Path candidates = transaction.directory().toPath().resolve("candidate");
    helper(candidates.resolve("runtime-0"), "dsh-status-overlay", "session-adapter.js");
    helper(candidates.resolve("runtime-1"), "dsh-computer-use-android", "task-binding.js");
    assertEquals("fixture-user-configuration", Files.readString(user));
    assertFalse(Files.exists(files.resolve("linux/ubuntu/root/dsha-status-overlay")));
    assertFalse(Files.exists(files.resolve("linux/ubuntu/root/dsha-computer-use-android")));
  }
}
