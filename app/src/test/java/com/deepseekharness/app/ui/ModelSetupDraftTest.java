package com.deepseekharness.app.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import static org.junit.Assert.*;

/** 锁定模型发现对话框的旋转恢复语义。 */
public final class ModelSetupDraftTest {
  @Test
  public void discoveryRemainsPendingAcrossRecreationUntilUserConsumesIt() {
    ModelSetupActivity.Draft retained = new ModelSetupActivity.Draft();
    retained.entry = new JsonObject();

    assertTrue(retained.shouldShowDiscovery(7));
    // Activity 旋转复用同一个 ViewModel；只显示对话框不能提前消费结果。
    assertTrue(retained.shouldShowDiscovery(7));

    retained.consumeDiscovery(7);
    assertFalse(retained.shouldShowDiscovery(7));
    assertTrue(retained.shouldShowDiscovery(8));
  }

  @Test
  public void discoveryIsNotShownAfterEditorWasClosed() {
    ModelSetupActivity.Draft retained = new ModelSetupActivity.Draft();
    retained.entry = new JsonObject();
    assertTrue(retained.shouldShowDiscovery(1));
    retained.clear();
    assertFalse(retained.shouldShowDiscovery(1));
  }

  @Test
  public void clearingCatalogOnlyChangesDraftModels() {
    ModelSetupActivity.Draft retained = new ModelSetupActivity.Draft();
    retained.value =
        JsonParser.parseString("{\"models\":[{\"id\":\"keep\"}]}").getAsJsonObject();
    retained.modelList = retained.value.getAsJsonArray("models").deepCopy();
    JsonArray previous = retained.modelList;

    retained.clearModels();

    assertTrue(retained.modelList.isEmpty());
    assertEquals(
        "keep",
        retained.value.getAsJsonArray("models").get(0).getAsJsonObject().get("id").getAsString());
    assertEquals(1, previous.size());
  }
}
