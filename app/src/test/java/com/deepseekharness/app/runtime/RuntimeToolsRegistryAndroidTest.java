package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import com.deepseekharness.app.util.Constants;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import com.deepseekharness.app.backup.*;

/** Real Android AssetManager -> production RuntimeTools admission, no guest or user data. */
@RunWith(RobolectricTestRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = android.app.Application.class,
    instrumentedPackages = {"com.deepseekharness.app.backup"},
    shadows = {RuntimeToolsRegistryAndroidTest.HostFileSystem.class})
public final class RuntimeToolsRegistryAndroidTest {
  private Path root;
  private Path source;
  private Path temporary;
  private final java.util.List<AssetManager> opened = new java.util.ArrayList<>();

  @Before
  public void setUp() throws Exception {
    root = Path.of("").toAbsolutePath();
    if (root.getFileName().toString().equals("app")) root = root.getParent();
    source =
        Path.of(
            System.getProperty("dsha.assetsDir", root.resolve("app/src/main/assets").toString()));
    temporary = Files.createTempDirectory("dsha-native-registry-");
  }

  @After
  public void close() throws Exception {
    for (AssetManager manager : opened) manager.close();
    try (var paths = Files.walk(temporary)) {
      for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
        try {
          Files.delete(path);
        } catch (java.nio.file.FileSystemException heldByAndroidResourceCache) {
          if (!System.getProperty("os.name").startsWith("Windows"))
            throw heldByAndroidResourceCache;
          // Robolectric retains mapped APK ZIPs until its resource cache/JVM closes.
          path.toFile().deleteOnExit();
        }
      }
    }
  }

  private JSONObject json(String name) throws Exception {
    return new JSONObject(Files.readString(source.resolve(name), StandardCharsets.UTF_8));
  }

  private Context apkAssets(Map<String, JSONObject> overrides) throws Exception {
    Map<String, byte[]> content = new HashMap<>();
    for (String name : new String[] {"runtime-patches.json", "runtime-descriptor.json"})
      content.put(name, Files.readAllBytes(source.resolve(name)));
    var active = json("runtime-patches.json").getJSONArray("active");
    for (int i = 0; i < active.length(); i++) {
      String name = active.getJSONObject(i).getString("asset");
      content.put(name, Files.readAllBytes(source.resolve(name)));
      var patches = json(name).getJSONArray("patches");
      for (int j = 0; j < patches.length(); j++)
        if (patches.getJSONObject(j).has("prependAsset")) {
          String helper = patches.getJSONObject(j).getString("prependAsset");
          content.put(helper, Files.readAllBytes(source.resolve(helper)));
        }
    }
    for (String name :
        new String[] {
          "pdf-compat-patch.json",
          "client-combo-patch.json",
          "web-integration/es-compat.js",
          "web-integration/startup.js",
          "client-combo-cache/package.json",
          "client-combo-cache/index.js",
          "lan-settings-upstream-contract.json"
        }) content.put(name, Files.readAllBytes(source.resolve(name)));
    for (var entry : overrides.entrySet())
      content.put(entry.getKey(), entry.getValue().toString().getBytes(StandardCharsets.UTF_8));
    Path archive = Files.createTempFile(temporary, "resources-", ".apk");
    try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
      for (var entry : content.entrySet()) {
        zip.putNextEntry(new ZipEntry("assets/" + entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    var constructor = AssetManager.class.getDeclaredConstructor();
    constructor.setAccessible(true);
    AssetManager manager = constructor.newInstance();
    opened.add(manager);
    var add = AssetManager.class.getDeclaredMethod("addAssetPath", String.class);
    add.setAccessible(true);
    assertTrue(
        "Android AssetManager must admit the resource ZIP",
        (int) add.invoke(manager, archive.toString()) > 0);
    Path data = temporary.resolve("app-data");
    Path files = Files.createDirectories(data.resolve("files"));
    return new ContextWrapper(RuntimeEnvironment.getApplication()) {
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
        return manager;
      }
    };
  }

  @Test
  public void actualApkResourcesAgreeWithNativeConstantDescriptorAndOfficialLock()
      throws Exception {
    Context context = apkAssets(Map.of());
    JSONObject registry = RuntimeTools.readPatchRegistry(context);
    String version = registry.getString("dshVersion");
    assertEquals("0.2.1-rc.2", version);
    assertEquals(version, Constants.DSH_VERSION);
    assertEquals("dsh-v" + version, Constants.DSH_RUNTIME_ID);
    try (var stream = context.getAssets().open("runtime-descriptor.json")) {
      assertEquals(
          version,
          new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8))
              .getString("dshVersion"));
    }
    JSONObject manifest =
        new JSONObject(Files.readString(root.resolve("tools/dsh-runtime/package.json")));
    JSONObject lock =
        new JSONObject(Files.readString(root.resolve("tools/dsh-runtime/package-lock.json")));
    assertEquals(version, manifest.getJSONObject("dependencies").getString("@deepseek-ai/dsh"));
    assertEquals(
        version,
        lock.getJSONObject("packages")
            .getJSONObject("node_modules/@deepseek-ai/dsh")
            .getString("version"));
    assertTrue(registry.getJSONArray("active").length() > 30);
  }

  @Test
  public void actualProductionAssetAdmissionAcceptsEveryCurrentRecipe() throws Exception {
    Context context = apkAssets(Map.of());
    JSONObject registry = RuntimeTools.readPatchRegistry(context);
    var groups = RuntimeTools.readPatchChains(context, registry, Constants.DSH_VERSION);
    assertEquals(38, groups.size());
    assertEquals(48, groups.values().stream().mapToInt(java.util.List::size).sum());
    assertTrue(
        groups.get("@deepseek-ai/dsh-config-editor/lib/index.js").stream()
            .anyMatch(
                recipe ->
                    recipe.id().equals("config-editor-lock-order-patch.json")
                        && recipe.markers().contains("DSHA_CONFIG_EDITOR_LOCK_ORDER_V1")));
    assertTrue(
        groups.get("@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js").stream()
            .anyMatch(
                recipe ->
                    recipe.id().equals("plugin-manager-pending-patch.json")
                        && recipe.markers().contains("DSHA_PLUGIN_PENDING_V2")
                        && recipe.markers().contains("DSHA_PLUGIN_PENDING_VIEW_V2")
                        && recipe.markers().contains("DSHA_PLUGIN_REFRESH_REUSE_V1")
                        && recipe.markers().contains("DSHA_PLUGIN_REFRESH_BARRIER_V1")));
    assertTrue(
        groups.get("@deepseek-ai/dsh-client-ui-agent-preset/lib/client.js").stream()
            .anyMatch(
                recipe ->
                    recipe.id().equals("agent-preset-pending-patch.json")
                        && recipe.markers().contains("DSHA_PRESET_PENDING_V2")
                        && recipe.markers().contains("DSHA_PRESET_POST_WRITE_READ_V1")
                        && recipe.markers().contains("DSHA_PRESET_PENDING_VIEW_V2")));
    assertTrue(
        groups.get("@deepseek-ai/dsh-client-ui-workspace/lib/client.js").stream()
            .anyMatch(
                recipe ->
                    recipe.id().equals("session-interaction-patch.json")
                        && recipe.markers().contains("DSHA_SESSION_SINGLE_ACTIVATE_V4")));
    var inventory = groups.get("@deepseek-ai/dsh-host-plugin-inventory/lib/index.js");
    assertNotNull(inventory);
    assertEquals(2, inventory.size());
    assertEquals("plugin-inventory-entries-patch.json", inventory.get(0).id());
    assertEquals(
        java.util.List.of("DSHA_PLUGIN_ENTRIES_PROJECTION_V1"), inventory.get(0).markers());
    assertTrue(
        groups.get("@deepseek-ai/dsh-plugin-manager/lib/index.js").stream()
            .anyMatch(
                recipe ->
                    recipe.id().equals("plugin-manager-entries-patch.json")
                        && recipe
                            .markers()
                            .equals(java.util.List.of("DSHA_PLUGIN_LIST_ENTRIES_ONLY_V1"))));
    assertEquals("plugin-inventory-management-patch.json", inventory.get(1).id());
    assertEquals(
        java.util.List.of("DSHA_PLUGIN_MANAGEMENT_PROJECTION_V1"), inventory.get(1).markers());
    var hostCodec = groups.get("@deepseek-ai/dsh-host-plugin-inventory/lib/typert.host.js");
    assertNotNull(hostCodec);
    assertEquals(1, hostCodec.size());
    assertEquals("plugin-inventory-management-host-codec-patch.json", hostCodec.get(0).id());
    assertEquals(
        java.util.List.of("DSHA_PLUGIN_MANAGEMENT_HOST_CODEC_V1"), hostCodec.get(0).markers());
    var remoteCodec =
        groups.get("@deepseek-ai/dsh-host-plugin-inventory/lib/typert.remote-client.js");
    assertNotNull(remoteCodec);
    assertEquals(1, remoteCodec.size());
    assertEquals("plugin-inventory-management-remote-codec-patch.json", remoteCodec.get(0).id());
    assertEquals(
        java.util.List.of("DSHA_PLUGIN_MANAGEMENT_REMOTE_CODEC_V1"), remoteCodec.get(0).markers());
    var apiCodec = groups.get("@deepseek-ai/dsh-api-remotes/lib/client.js");
    assertNotNull(apiCodec);
    assertEquals(1, apiCodec.size());
    assertEquals("plugin-inventory-management-api-codec-patch.json", apiCodec.get(0).id());
    assertEquals(
        java.util.List.of("DSHA_PLUGIN_MANAGEMENT_API_CODEC_V1"), apiCodec.get(0).markers());
    assertTrue(
        groups.get("@deepseek-ai/dsh-client-ui-plugin-manager/lib/client.js").stream()
            .anyMatch(
                recipe ->
                    recipe.id().equals("plugin-manager-management-probe-patch.json")
                        && recipe
                            .markers()
                            .equals(java.util.List.of("DSHA_PLUGIN_MANAGEMENT_PROBE_V1"))));
    var manager = groups.get("@deepseek-ai/dsh-plugin-manager/lib/index.js");
    var workers = manager.get(manager.size() - 1);
    assertEquals("plugin-bundle-proof-workers-patch.json", workers.id());
    assertEquals(java.util.List.of("DSHA_PLUGIN_BUNDLE_READ_WORKERS_V2"), workers.markers());
    var oauth = groups.get("@deepseek-ai/dsh-client-ui-settings-account/lib/client.js");
    assertNotNull(oauth);
    assertEquals(1, oauth.size());
    assertEquals("deepseek-native-oauth-patch.json", oauth.get(0).id());
    assertEquals(java.util.List.of("DSHA_NATIVE_OAUTH_NAVIGATION_V1"), oauth.get(0).markers());
    var directory = groups.get("@deepseek-ai/dsh-api-workspace-controller/lib/index.js");
    assertNotNull(directory);
    assertEquals(1, directory.size());
    assertEquals("directory-picker-service-patch.json", directory.get(0).id());
    assertEquals(
        java.util.List.of("DSHA_DIRECTORY_PICKER_INDEPENDENT_LIFETIME_V1"),
        directory.get(0).markers());
    var upload = groups.get("@deepseek-ai/dsh-client-file-upload/lib/index.js");
    assertNotNull(upload);
    assertEquals(1, upload.size());
    assertEquals("file-upload-resolver-lifecycle-patch.json", upload.get(0).id());
    assertEquals(
        java.util.List.of("DSHA_FILE_UPLOAD_RESOLVER_LIFETIME_V1"), upload.get(0).markers());
  }

  @Test
  public void staleRegistryCannotBypassNativeVersionAdmission() throws Exception {
    JSONObject registry = json("runtime-patches.json").put("dshVersion", "0.2.0-rc.2");
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                RuntimeTools.readPatchRegistry(
                    apkAssets(Map.of("runtime-patches.json", registry))));
    assertEquals("PATCH_REGISTRY_VERSION: APK=0.2.1-rc.2 registry=0.2.0-rc.2", error.getMessage());
  }

  @Test
  public void schemaMismatchIsStillRejectedByRealAssetConsumer() throws Exception {
    JSONObject registry = json("runtime-patches.json").put("schema", 2);
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                RuntimeTools.readPatchRegistry(
                    apkAssets(Map.of("runtime-patches.json", registry))));
    assertEquals("PATCH_REGISTRY_SCHEMA", error.getMessage());
  }

  @Test
  public void staleRecipeRemainsRejectedAfterCurrentRegistryPasses() throws Exception {
    JSONObject registry = json("runtime-patches.json");
    String first = registry.getJSONArray("active").getJSONObject(0).getString("asset");
    JSONObject stale = json(first).put("dshVersion", "0.2.0-rc.2");
    Context context = apkAssets(Map.of(first, stale));
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                RuntimeTools.readPatchChains(
                    context, RuntimeTools.readPatchRegistry(context), Constants.DSH_VERSION));
    assertEquals(
        "PATCH_REGISTRY_VERSION: recipe=" + first + " expected=0.2.1-rc.2 actual=0.2.0-rc.2",
        error.getMessage());
  }

  @Test
  public void currentVersionDoesNotRelaxModulePathAdmission() throws Exception {
    JSONObject registry = json("runtime-patches.json");
    registry.getJSONArray("active").getJSONObject(0).put("module", "../outside.js");
    Context context = apkAssets(Map.of("runtime-patches.json", registry));
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                RuntimeTools.readPatchChains(
                    context, RuntimeTools.readPatchRegistry(context), Constants.DSH_VERSION));
    assertEquals("PATCH_MODULE_PATH", error.getMessage());
  }

  /** Only adapts Linux descriptor syscalls to real NOFOLLOW host files on Windows.
   * Production patch methods, AssetManager, path authority and atomic flow are unchanged. */
  @Implements(value = AndroidBackupFileSystem.class, isInAndroidSdk = false)
  public static class HostFileSystem {
    private final JvmBackupFileSystem fs = new JvmBackupFileSystem();

    @Implementation
    public BackupFileSystem.Node stat(File f) throws IOException {
      return fs.stat(f);
    }

    @Implementation
    public String readLink(File f) throws IOException {
      return fs.readLink(f);
    }

    @Implementation
    public java.util.List<String> list(File f) throws IOException {
      return fs.list(f);
    }

    @Implementation
    public java.io.InputStream read(File f, BackupFileSystem.Node n) throws IOException {
      return fs.read(f, n);
    }

    @Implementation
    public java.io.OutputStream create(File f) throws IOException {
      return fs.create(f);
    }

    @Implementation
    public void directory(File f) throws IOException {
      fs.directory(f);
    }

    @Implementation
    public void move(File f, File t) throws IOException {
      fs.move(f, t);
    }

    @Implementation
    public void delete(File f) throws IOException {
      fs.delete(f);
    }

    @Implementation
    public void mode(File f, int m) throws IOException {
      fs.mode(f, m);
    }

    @Implementation
    public void syncDirectory(File f) throws IOException {
      fs.syncDirectory(f);
    }

    @Implementation
    public void symlink(String t, File f) throws IOException {
      fs.symlink(t, f);
    }
  }

  private static final String DSH = "usr/local/lib/node_modules/@deepseek-ai/dsh/";
  private static final String LAN = "@deepseek-ai/dsh-client-ui-settings/lib/client.js";

  private static String sha256(Path file) throws Exception {
    return java.util.HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
  }

  private static Map<String, String> strings(JSONObject object) throws Exception {
    Map<String, String> values = new java.util.TreeMap<>();
    var names = object.keys();
    while (names.hasNext()) {
      String name = names.next();
      values.put(name, object.getString(name));
    }
    return values;
  }

  private record ManagedFixture(Path directory, JSONObject proof) {}

  private ManagedFixture currentManagedRuntime() throws Exception {
    JSONObject selected =
        new JSONObject(Files.readString(root.resolve("app/build/test-runtimes/current.json")));
    assertEquals(Constants.DSH_VERSION, selected.getString("dshVersion"));
    assertTrue(
        "Current runtime archive must match the fixture", selected.getBoolean("archiveCurrent"));
    Path managed = Path.of(selected.getString("managed"));
    assertTrue("Current managed fixture must exist", Files.isDirectory(managed));
    JSONObject proof = new JSONObject(Files.readString(managed.resolve("dsha-test-runtime.json")));
    assertEquals(2, proof.getInt("version"));
    assertEquals("managed", proof.getString("kind"));
    assertEquals(Constants.DSH_VERSION, proof.getString("dshVersion"));
    assertTrue("Managed fixture cannot use a stale archive", proof.getBoolean("archiveCurrent"));
    String lock = sha256(root.resolve("tools/dsh-runtime/package-lock.json"));
    assertEquals(
        "Fixture must use the current dependency lock", lock, proof.getString("lockSha256"));
    assertEquals(lock, sha256(managed.resolve("package-lock.json")));
    JSONObject archive = json("dsh-runtime.inputs.json");
    assertEquals(archive.getString("archive_sha256"), proof.getString("archiveSha256"));
    assertEquals(
        "Fixture must use the complete current archive recipe",
        strings(archive.getJSONObject("inputs")),
        strings(proof.getJSONObject("archiveRecipeInputs")));
    for (var input : strings(proof.getJSONObject("overlayInputs")).entrySet()) {
      Path file = root.resolve(input.getKey()).normalize();
      assertTrue("Fixture input must stay below source root", file.startsWith(root));
      assertEquals("Stale managed recipe input: " + input.getKey(), input.getValue(), sha256(file));
    }
    return new ManagedFixture(managed, proof);
  }

  private File actualManagedModuleClosure(Context context) throws Exception {
    var chains =
        RuntimeTools.readPatchChains(
            context, RuntimeTools.readPatchRegistry(context), Constants.DSH_VERSION);
    var modules = new java.util.LinkedHashSet<String>(chains.keySet());
    var pdf = json("pdf-compat-patch.json");
    modules.add(pdf.getString("module"));
    modules.add(pdf.getString("resourceModule"));
    modules.add("@deepseek-ai/dsh-client-modules/lib/index.js");
    modules.add("@deepseek-ai/dsh-web-frontend/dist/index.html");
    modules.add(LAN);
    ManagedFixture fixtureProof = currentManagedRuntime();
    Path managed = fixtureProof.directory();
    JSONObject moduleHashes = fixtureProof.proof().getJSONObject("moduleHashes");
    Path guest = context.getFilesDir().toPath().resolve("linux/ubuntu");
    Path dsh = guest.resolve(DSH);
    Files.createDirectories(dsh);
    Path packageFile = managed.resolve("node_modules/@deepseek-ai/dsh/package.json");
    assertEquals(
        moduleHashes.getString("node_modules/@deepseek-ai/dsh/package.json"), sha256(packageFile));
    assertEquals(
        "@deepseek-ai/dsh", new JSONObject(Files.readString(packageFile)).getString("name"));
    Files.copy(packageFile, dsh.resolve("package.json"));
    for (String name : modules) {
      Path fixture = managed.resolve("node_modules").resolve(name);
      assertEquals(
          "Changed managed module: " + name,
          moduleHashes.getString("node_modules/" + name),
          sha256(fixture));
      if (chains.containsKey(name)) {
        String prepared = Files.readString(fixture);
        assertEquals(
            "Managed fixture must already contain the complete current chain: " + name,
            prepared,
            com.deepseekharness.app.util.ManagedPatchChain.apply(
                prepared,
                chains.get(name),
                () -> {
                  throw new AssertionError("Stale managed fixture needs canonical repair: " + name);
                }));
      }
      Path target = dsh.resolve("node_modules").resolve(name);
      Files.createDirectories(target.getParent());
      Files.copy(fixture, target);
    }
    assertTrue(modules.size() >= 31);
    return guest.toFile();
  }

  private static void productionChain(Context context, File guest) throws Exception {
    var method = RuntimeTools.class.getDeclaredMethod("applyPatchChain", Context.class, File.class);
    method.setAccessible(true);
    try {
      method.invoke(null, context, guest);
    } catch (InvocationTargetException error) {
      if (error.getCause() instanceof Exception cause) throw cause;
      throw error;
    }
  }

  private static Map<String, String> digestTree(File guest) throws Exception {
    Map<String, String> result = new java.util.TreeMap<>();
    try (var paths = Files.walk(guest.toPath())) {
      for (Path path : paths.filter(Files::isRegularFile).toList())
        result.put(
            guest.toPath().relativize(path).toString(),
            java.util.Base64.getEncoder()
                .encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(path))));
    }
    return result;
  }

  @Test
  public void completeProductionChainAppliesAllSpecializedStagesAndIsRepeatable() throws Exception {
    Context context = apkAssets(Map.of());
    File guest = actualManagedModuleClosure(context);
    Path modules = guest.toPath().resolve(DSH + "node_modules");
    byte[] lan = Files.readAllBytes(modules.resolve(LAN));
    productionChain(context, guest);
    var pdf = json("pdf-compat-patch.json");
    assertTrue(
        Files.readString(
                modules.resolve("@deepseek-ai/dsh-client-ui-settings-account/lib/client.js"))
            .contains("DSHA_NATIVE_OAUTH_NAVIGATION_V1"));
    assertTrue(
        Files.readString(modules.resolve("@deepseek-ai/dsh-api-workspace-controller/lib/index.js"))
            .contains("DSHA_DIRECTORY_PICKER_INDEPENDENT_LIFETIME_V1"));
    assertTrue(
        Files.readString(modules.resolve("@deepseek-ai/dsh-client-file-upload/lib/index.js"))
            .contains("DSHA_FILE_UPLOAD_RESOLVER_LIFETIME_V1"));
    assertTrue(
        Files.readString(modules.resolve(pdf.getString("module"))).contains("DSHA_PDF_COMPAT_V1"));
    assertTrue(
        Files.readString(modules.resolve(pdf.getString("resourceModule")))
            .contains(pdf.getString("resourceAfter")));
    assertTrue(
        Files.readString(modules.resolve("@deepseek-ai/dsh-web-frontend/dist/index.html"))
            .contains("DSHA_BROWSER_COMPAT_BEGIN"));
    assertTrue(
        Files.readString(modules.resolve("@deepseek-ai/dsh-client-modules/lib/index.js"))
            .contains("DSHA_COMBO_CACHE_V1"));
    assertTrue(Files.isRegularFile(modules.resolve("dsha-client-combo-cache/index.js")));
    assertArrayEquals(lan, Files.readAllBytes(modules.resolve(LAN)));
    var first = digestTree(guest);
    productionChain(context, guest);
    assertEquals(first, digestTree(guest));
    assertArrayEquals(lan, Files.readAllBytes(modules.resolve(LAN)));
  }

  @Test
  public void lanRetirementRejectsModuleDriftAndPreservesOriginalBytes() throws Exception {
    Context context = apkAssets(Map.of());
    File guest = actualManagedModuleClosure(context);
    Path module = guest.toPath().resolve(DSH + "node_modules/" + LAN);
    byte[] changed = (Files.readString(module) + "\n// drift").getBytes(StandardCharsets.UTF_8);
    Files.write(module, changed);
    IOException error = assertThrows(IOException.class, () -> productionChain(context, guest));
    assertEquals("LAN_SETTINGS_SOURCE_MISMATCH", error.getMessage());
    assertArrayEquals(changed, Files.readAllBytes(module));
  }

  @Test
  public void lanRetirementRejectsUnknownVersionAndContractDrift() throws Exception {
    Context context = apkAssets(Map.of());
    File guest = actualManagedModuleClosure(context);
    Path pkg = guest.toPath().resolve(DSH + "package.json");
    Files.writeString(
        pkg, new JSONObject(Files.readString(pkg)).put("version", "0.2.2").toString());
    IOException version =
        assertThrows(
            IOException.class, () -> RuntimeTools.patchLanSettingsPersistence(context, guest));
    assertEquals("LAN_SETTINGS_VERSION", version.getMessage());
    Files.writeString(
        pkg,
        new JSONObject(Files.readString(pkg)).put("version", Constants.DSH_VERSION).toString());
    Context bad =
        apkAssets(
            Map.of(
                "lan-settings-upstream-contract.json",
                json("lan-settings-upstream-contract.json").put("schema", 2)));
    IOException contract =
        assertThrows(IOException.class, () -> RuntimeTools.patchLanSettingsPersistence(bad, guest));
    assertEquals("LAN_SETTINGS_CONTRACT", contract.getMessage());
  }

  @Test
  public void oldLanExactReplacementAndIdempotenceRemainStrict() throws Exception {
    Context context = apkAssets(Map.of());
    File guest = actualManagedModuleClosure(context);
    Path pkg = guest.toPath().resolve(DSH + "package.json");
    Files.writeString(
        pkg, new JSONObject(Files.readString(pkg)).put("version", "0.2.0-rc.2").toString());
    Path module = guest.toPath().resolve(DSH + "node_modules/" + LAN);
    Files.writeString(
        module, "const persistence = ctx.remote.$host.isLoopback ? \"host\" : \"memory\";");
    RuntimeTools.patchLanSettingsPersistence(context, guest);
    String patched = Files.readString(module);
    assertTrue(patched.contains("LAN 代理场景强制 host 持久化"));
    RuntimeTools.patchLanSettingsPersistence(context, guest);
    assertEquals(patched, Files.readString(module));
    Files.writeString(module, "unknown legacy implementation");
    IOException error =
        assertThrows(
            IOException.class, () -> RuntimeTools.patchLanSettingsPersistence(context, guest));
    assertEquals("LAN_SETTINGS_PATCH_MISMATCH", error.getMessage());
  }
}
