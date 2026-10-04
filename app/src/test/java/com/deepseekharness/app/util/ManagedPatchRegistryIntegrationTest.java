package com.deepseekharness.app.util;

import com.google.gson.*;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** Exercise current locked module bytes with the production Java chain engine. */
public class ManagedPatchRegistryIntegrationTest {
  private static String text(Path file) throws Exception {
    return Files.readString(file).replace("\r\n", "\n");
  }

  private static JsonObject json(Path file) throws Exception {
    return JsonParser.parseString(text(file)).getAsJsonObject();
  }

  @Test
  public void everyActiveRecipePinsTheCurrentDescriptorAndLockedDshVersion() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    if (root.getFileName().toString().equals("app")) root = root.getParent();
    Path assets =
        Path.of(
            System.getProperty("dsha.assetsDir", root.resolve("app/src/main/assets").toString()));
    JsonObject registry = json(assets.resolve("runtime-patches.json"));
    String current = registry.get("dshVersion").getAsString();
    assertEquals(
        current, json(assets.resolve("runtime-descriptor.json")).get("dshVersion").getAsString());
    assertEquals(
        current,
        json(root.resolve("tools/dsh-runtime/package.json"))
            .getAsJsonObject("dependencies")
            .get("@deepseek-ai/dsh")
            .getAsString());
    Set<String> active = new HashSet<>();
    for (JsonElement value : registry.getAsJsonArray("active")) {
      String asset = value.getAsJsonObject().get("asset").getAsString();
      assertTrue(active.add(asset));
      JsonObject recipe = json(assets.resolve(asset));
      assertTrue(asset + " missing current-generation pin", recipe.has("dshVersion"));
      assertEquals(
          asset + " must not silently skip in production",
          current,
          recipe.get("dshVersion").getAsString());
    }
    for (JsonElement value : registry.getAsJsonArray("retired"))
      assertFalse(active.contains(value.getAsJsonObject().get("asset").getAsString()));
  }

  @Test
  public void everyActiveModuleRepairsAsAWholeAndRetiredUiNeverRuns() throws Exception {
    Path root = Path.of("").toAbsolutePath();
    if (root.getFileName().toString().equals("app")) root = root.getParent();
    Path assets = root.resolve("app/src/main/assets"),
        pointer = root.resolve("app/build/test-runtimes/current.json");
    org.junit.Assume.assumeTrue("current raw host runtime required", Files.isRegularFile(pointer));
    JsonObject selected = json(pointer), registry = json(assets.resolve("runtime-patches.json"));
    Path raw = Path.of(selected.get("raw").getAsString()).resolve("node_modules");
    assertEquals(
        registry.get("dshVersion").getAsString(),
        json(raw.resolve("@deepseek-ai/dsh/package.json")).get("version").getAsString());
    Map<String, List<ManagedPatchChain.Recipe>> groups = new LinkedHashMap<>();
    Set<String> active = new HashSet<>();
    for (JsonElement value : registry.getAsJsonArray("active")) {
      JsonObject entry = value.getAsJsonObject();
      String asset = entry.get("asset").getAsString();
      assertTrue(active.add(asset));
      JsonObject spec = json(assets.resolve(asset));
      assertEquals(
          asset, registry.get("dshVersion").getAsString(), spec.get("dshVersion").getAsString());
      assertEquals(
          asset, registry.get("dshVersion").getAsString(), spec.get("dshVersion").getAsString());
      String module =
          entry.has("module")
              ? entry.get("module").getAsString()
              : spec.get("module").getAsString();
      List<ManagedPatchChain.Patch> patches = new ArrayList<>();
      for (JsonElement part : spec.getAsJsonArray("patches")) {
        JsonObject patch = part.getAsJsonObject();
        String after = patch.get("after").getAsString();
        if (patch.has("prependAsset"))
          after = text(assets.resolve(patch.get("prependAsset").getAsString())) + "\n" + after;
        patches.add(new ManagedPatchChain.Patch(patch.get("before").getAsString(), after));
      }
      List<String> markers = new ArrayList<>();
      if (entry.has("markers"))
        for (JsonElement marker : entry.getAsJsonArray("markers"))
          markers.add(marker.getAsString());
      groups
          .computeIfAbsent(module, key -> new ArrayList<>())
          .add(new ManagedPatchChain.Recipe(asset, patches, markers));
    }
    for (JsonElement value : registry.getAsJsonArray("retired"))
      assertFalse(active.contains(value.getAsJsonObject().get("asset").getAsString()));
    for (var group : groups.entrySet()) {
      String canonical = text(raw.resolve(group.getKey()));
      String patched = ManagedPatchChain.apply(canonical, group.getValue(), () -> canonical);
      assertEquals(
          group.getKey(),
          patched,
          ManagedPatchChain.apply(
              patched,
              group.getValue(),
              () -> {
                throw new AssertionError("idempotent chain must not restore");
              }));
      assertEquals(
          group.getKey(),
          patched,
          ManagedPatchChain.apply("old incompatible module", group.getValue(), () -> canonical));
      var first = group.getValue().get(0).patches().get(0);
      var drift =
          new ManagedPatchChain.Recipe(
              "deliberate-current-anchor-drift",
              List.of(
                  new ManagedPatchChain.Patch(
                      first.before() + "__missing", first.after() + "__missing")),
              List.of());
      assertThrows(
          java.io.IOException.class,
          () -> ManagedPatchChain.apply(canonical, List.of(drift), () -> canonical));
    }
    assertTrue(groups.get("@deepseek-ai/dsh-client-ui-conversation/lib/client.js").size() > 1);
    assertFalse(active.contains("agent-preset-patch.json"));
    assertFalse(active.contains("models-navigation-patch.json"));
  }
}
