package com.deepseekharness.app.runtime;

import static org.junit.Assert.*;

import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.backup.BackupLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/** 与 RuntimeTools.assetObject 使用同一生产解析器和限额，避免只验证 Python JSON。 */
public final class RuntimePatchMetadataTest {
  private static final int ASSET_LIMIT = 2 * 1024 * 1024;

  @Test
  public void everyActiveRecipePassesTheNativeMetadataReader() throws Exception {
    Path assets = Path.of(System.getProperty("dsha.assetsDir", "app/src/main/assets"));
    Map<String, Object> registry =
        BackupJson.read(Files.readAllBytes(assets.resolve("runtime-patches.json")), ASSET_LIMIT);
    int checked = 0;
    for (Object value : (List<?>) registry.get("active")) {
      String asset = (String) ((Map<?, ?>) value).get("asset");
      try {
        Map<String, Object> recipe =
            BackupJson.read(Files.readAllBytes(assets.resolve(asset)), ASSET_LIMIT);
        assertTrue(asset, recipe.get("patches") instanceof List);
      } catch (IOException failure) {
        throw new AssertionError("Native recipe admission failed: " + asset, failure);
      }
      checked++;
    }
    assertTrue(checked > 0);
  }

  @Test
  public void oversizedStringStillFailsEvenWhenWholeFileFits() throws Exception {
    byte[] bytes =
        ("{\"after\":\"" + "x".repeat(BackupLimits.RECORD + 1) + "\"}")
            .getBytes(StandardCharsets.UTF_8);
    assertTrue(bytes.length < ASSET_LIMIT);
    IOException error = assertThrows(IOException.class, () -> BackupJson.read(bytes, ASSET_LIMIT));
    assertEquals("METADATA_LIMIT", error.getMessage());
  }

  @Test
  public void duplicateKeysRemainRejected() {
    byte[] bytes = "{\"after\":\"one\",\"after\":\"two\"}".getBytes(StandardCharsets.UTF_8);
    IOException error = assertThrows(IOException.class, () -> BackupJson.read(bytes, ASSET_LIMIT));
    assertEquals("DUPLICATE_METADATA", error.getMessage());
  }
}
