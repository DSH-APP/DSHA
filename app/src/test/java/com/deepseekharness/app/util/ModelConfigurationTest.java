package com.deepseekharness.app.util;
import com.google.gson.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class ModelConfigurationTest {
    @Test public void customHeadersRoundTripAndRejectAmbiguousOrInjectedFields(){
        JsonObject configured=JsonParser.parseString("{\"x-opencode-session\":\"session-123\",\"User-Agent\":\"DSHA/1.0\"}").getAsJsonObject();
        assertEquals(configured,ModelConfiguration.headers(ModelConfiguration.headerRows(configured)));
        for(String invalid:new String[]{
                "[{\"name\":\"X-Session\",\"value\":\"a\"},{\"name\":\"x-session\",\"value\":\"b\"}]",
                "[{\"name\":\"bad name\",\"value\":\"x\"}]",
                "[{\"name\":\"X-Test\",\"value\":\"ok\\r\\nInjected: yes\"}]",
                "[{\"name\":\"Content-Length\",\"value\":\"99\"}]"})
            assertThrows(IllegalArgumentException.class,()->ModelConfiguration.headers(JsonParser.parseString(invalid).getAsJsonArray()));
        assertTrue(ModelConfiguration.headers(JsonParser.parseString("[{\"name\":\"\",\"value\":\"\"}]").getAsJsonArray()).isEmpty());
    }
    @Test public void editPreservesUnknownFieldsAndUsesLeafPaths(){
        JsonObject original=JsonParser.parseString("{\"baseURL\":\"https://old.example/v1\",\"headers\":{\"custom\":\"retained\"},\"models\":[{\"id\":\"x\",\"compat\":{\"supportsStore\":false}}]}").getAsJsonObject();
        JsonObject edited=original.deepCopy();edited.addProperty("baseURL","https://new.example/v1");
        JsonArray ops=ModelConfiguration.diff(ModelConfiguration.path("providers","my-provider"),original,edited);
        assertEquals(1,ops.size());assertEquals("[\"providers\",\"my-provider\",\"baseURL\"]",ops.get(0).getAsJsonObject().get("path").toString());
        assertEquals(original.get("models"),edited.get("models"));assertEquals(original.get("headers"),edited.get("headers"));
    }
    @Test public void secretAbsentFromFormDoesNotDeleteStoredReference(){
        JsonObject profile=JsonParser.parseString("{\"apiKeyEnv\":\"EXISTING_KEY\"}").getAsJsonObject();assertTrue(ModelConfiguration.diff(new JsonArray(),profile,profile.deepCopy()).isEmpty());
    }
    @Test public void clearingOverrideUsesOfficialUnsetOperation(){
        JsonObject original=JsonParser.parseString("{\"baseURL\":\"https://example.com\",\"apiKeyEnv\":\"EXISTING\"}").getAsJsonObject();JsonObject edited=original.deepCopy();edited.remove("baseURL");
        JsonObject op=ModelConfiguration.diff(new JsonArray(),original,edited).get(0).getAsJsonObject();assertEquals("unset",op.get("op").getAsString());assertFalse(op.has("value"));assertEquals("EXISTING",edited.get("apiKeyEnv").getAsString());
    }
    @Test public void customRoutesAndEndpointsAreValidated(){
        ModelConfiguration.validateRoute("my-gateway");ModelConfiguration.validateUrl("http://127.0.0.1:8000/v1",true);
        for(String bad:new String[]{"2route","UPPER","../route","has space"})assertThrows(IllegalArgumentException.class,()->ModelConfiguration.validateRoute(bad));
        for(String bad:new String[]{"","javascript:alert(1)","https://user:password@example.com","https://example.com/#secret"})assertThrows(IllegalArgumentException.class,()->ModelConfiguration.validateUrl(bad,true));
    }
    @Test public void modelMetadataSurvivesAndDuplicatesAreRefused(){
        String json="[{\"id\":\"vision\",\"input\":[\"text\",\"image\"],\"contextWindow\":128000,\"reasoningEfforts\":false}]";
        assertEquals(JsonParser.parseString(json),ModelConfiguration.validateModels(json,true));
        for(String bad:new String[]{"[]","{}","[{\"id\":\"x\"},{\"id\":\"x\"}]","[{\"id\":\"x\",\"maxTokens\":-1}]"})assertThrows(IllegalArgumentException.class,()->ModelConfiguration.validateModels(bad,true));
    }
    @Test public void protocolChoicesComeFromSchemaNotHardcodedIds(){
        JsonObject schema=JsonParser.parseString("{\"uid\":1,\"refs\":{\"1\":{\"dict\":{\"providers\":2}},\"2\":{\"type\":\"dict\",\"inner\":3},\"3\":{\"dict\":{\"api\":4}},\"4\":{\"type\":\"union\",\"list\":[5]},\"5\":{\"type\":\"const\",\"value\":\"new-protocol\"}}}").getAsJsonObject();
        assertEquals(java.util.List.of("new-protocol"),ModelConfiguration.choices(schema,ModelConfiguration.path("providers","arbitrary-route","api")));
    }
    @Test public void discoveredModelsMergeWithoutOverwritingExistingMetadata(){
        JsonArray current=JsonParser.parseString("[{\"id\":\"kept\",\"name\":\"My label\",\"contextWindow\":12345,\"compat\":{\"supportsStore\":false}}]").getAsJsonArray();
        JsonArray found=JsonParser.parseString("[{\"id\":\"kept\",\"name\":\"Remote label\",\"contextWindow\":99999},{\"id\":\"new-model\",\"name\":\"New\",\"contextWindow\":128000,\"maxTokens\":8192,\"inputModalities\":[\"text\",\"image\"]}]").getAsJsonArray();
        JsonArray merged=ModelConfiguration.mergeDiscoveredModels(current,found,java.util.Set.of("kept","new-model"));
        assertEquals(2,merged.size());assertEquals(current.get(0),merged.get(0));
        JsonObject added=merged.get(1).getAsJsonObject();assertEquals("new-model",added.get("id").getAsString());assertEquals(128000,added.get("contextWindow").getAsInt());assertEquals("[\"text\",\"image\"]",added.get("input").toString());
    }
    @Test public void discoveredModelsRespectSelectionDeduplicateAndIgnoreInvalidMetadata(){
        JsonArray found=JsonParser.parseString("[{\"id\":\"first\",\"contextWindow\":0},{\"id\":\"first\",\"maxTokens\":99},{\"id\":\"skip\"},{\"id\":\" bad \"},{\"id\":\"safe\",\"inputModalities\":[\"audio\"]}]").getAsJsonArray();
        JsonArray merged=ModelConfiguration.mergeDiscoveredModels(new JsonArray(),found,java.util.Set.of("first","safe"," bad "));
        assertEquals(2,merged.size());assertEquals("first",merged.get(0).getAsJsonObject().get("id").getAsString());assertFalse(merged.get(0).getAsJsonObject().has("contextWindow"));
        assertEquals("safe",merged.get(1).getAsJsonObject().get("id").getAsString());assertFalse(merged.get(1).getAsJsonObject().has("input"));
    }
    @Test public void absentOrUnselectedDiscoveryDoesNotClearTheDraft(){
        JsonArray current=JsonParser.parseString("[{\"id\":\"keep\",\"contextWindow\":64000}]").getAsJsonArray();
        assertEquals(current,ModelConfiguration.mergeDiscoveredModels(current,null,java.util.Set.of("anything")));
        JsonArray found=JsonParser.parseString("[{\"id\":\"new\"}]").getAsJsonArray();assertEquals(current,ModelConfiguration.mergeDiscoveredModels(current,found,java.util.Set.of()));
    }
}
