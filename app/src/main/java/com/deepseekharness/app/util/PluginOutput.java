package com.deepseekharness.app.util;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashSet;
import java.util.Set;

/** 运行时可能在插件 JSON 后追加退出信息；只识别独立的结果行。 */
public final class PluginOutput {
  private PluginOutput() {}

  public static String resultJson(String output) {
    if (output == null) return "";
    String[] lines = output.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").split("\\r?\\n");
    for (int i = lines.length - 1; i >= 0; i--) {
      String line = lines[i].trim();
      if (line.startsWith("PLUGIN_RESULT: "))
        return line.substring("PLUGIN_RESULT: ".length()).trim();
    }
    return "";
  }

  /** A discard is complete only with one strict, unambiguous final result object. */
  public static void requireDiscardSuccess(String output) throws IOException {
    if (output == null) throw new IOException("PLUGIN_DISCARD_RESULT_MISSING");
    String[] lines = output.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "").split("\\r?\\n");
    String json = null;
    for (String raw : lines) {
      String line = raw.trim();
      if (!line.startsWith("PLUGIN_RESULT: ")) continue;
      if (json != null) throw new IOException("PLUGIN_DISCARD_RESULT_DUPLICATE");
      json = line.substring("PLUGIN_RESULT: ".length()).trim();
    }
    if (json == null || json.isEmpty() || json.length() > 65536)
      throw new IOException("PLUGIN_DISCARD_RESULT_MISSING_OR_OVERSIZE");
    String status = null, message = null;
    try (JsonReader reader = new JsonReader(new StringReader(json))) {
      reader.setStrictness(Strictness.STRICT);
      reader.beginObject();
      Set<String> names = new HashSet<>();
      while (reader.hasNext()) {
        String name = reader.nextName();
        if (!names.add(name)) throw new IOException("PLUGIN_DISCARD_RESULT_DUPLICATE_FIELD");
        if (name.equals("status") || name.equals("message")) {
          if (reader.peek() != JsonToken.STRING)
            throw new IOException("PLUGIN_DISCARD_RESULT_FIELD_TYPE");
          String value = reader.nextString();
          if (name.equals("status")) status = value;
          else message = value;
        } else reader.skipValue();
      }
      reader.endObject();
      if (reader.peek() != JsonToken.END_DOCUMENT)
        throw new IOException("PLUGIN_DISCARD_RESULT_TRAILING_JSON");
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof IOException
          && String.valueOf(failure.getMessage()).startsWith("PLUGIN_DISCARD_RESULT_"))
        throw (IOException) failure;
      throw new IOException("PLUGIN_DISCARD_RESULT_INVALID", failure);
    }
    if (status == null || message == null)
      throw new IOException("PLUGIN_DISCARD_RESULT_FIELDS_MISSING");
    if (!status.equals("ok")) throw new IOException(SensitiveData.redact(message));
  }
}
