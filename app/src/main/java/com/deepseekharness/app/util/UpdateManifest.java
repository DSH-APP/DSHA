package com.deepseekharness.app.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A feed for another application or signing identity has no candidates for this installation. */
public final class UpdateManifest {
  private UpdateManifest() {}

  public static List<UpdatePolicy.Release> parse(
      byte[] raw, String installedPackage, List<String> installedSigners)
      throws UpdateFailures.InvalidManifest {
    if (raw == null || raw.length > 1024 * 1024) throw new UpdateFailures.InvalidManifest();
    try (JsonReader reader =
        new JsonReader(new StringReader(new String(raw, StandardCharsets.UTF_8)))) {
      reader.setStrictness(Strictness.STRICT);
      JsonElement root = JsonParser.parseReader(reader);
      if (reader.peek() != JsonToken.END_DOCUMENT || !root.isJsonObject())
        throw new UpdateFailures.InvalidManifest();
      JsonObject feed = root.getAsJsonObject();
      if (integer(feed, "schemaVersion") != 1) throw new UpdateFailures.InvalidManifest();
      if (!UpdatePolicy.feedIdentityMatches(
          installedPackage,
          installedSigners,
          string(feed, "packageName"),
          string(feed, "certificateSha256"))) return Collections.emptyList();
      JsonElement releases = feed.get("releases");
      if (releases == null || releases.isJsonNull()) return Collections.emptyList();
      if (!releases.isJsonArray()) throw new UpdateFailures.InvalidManifest();
      ArrayList<UpdatePolicy.Release> options = new ArrayList<>();
      for (JsonElement entry : releases.getAsJsonArray()) {
        if (!entry.isJsonObject()) continue;
        JsonObject release = entry.getAsJsonObject();
        JsonElement artifacts = release.get("artifacts");
        if (artifacts == null || !artifacts.isJsonArray()) continue;
        for (JsonElement artifact : artifacts.getAsJsonArray()) {
          if (!artifact.isJsonObject()) continue;
          JsonObject apk = artifact.getAsJsonObject();
          try {
            UpdatePolicy.Release option =
                new UpdatePolicy.Release(
                    integer(release, "versionCode"),
                    string(release, "version"),
                    string(release, "channel"),
                    string(apk, "flavor"),
                    integer(apk, "minSdk"),
                    string(apk, "abi"),
                    string(apk, "url"),
                    string(apk, "sha256"),
                    number(apk, "bytes"),
                    string(release, "notes"),
                    string(release, "pageUrl"));
            if (option.valid()) options.add(option);
          } catch (IllegalArgumentException ignored) {
            // Incomplete/non-applicable artifacts do not turn an otherwise valid feed into an
            // error.
          }
        }
      }
      return options;
    } catch (UpdateFailures.InvalidManifest failure) {
      throw failure;
    } catch (Exception failure) {
      throw new UpdateFailures.InvalidManifest(failure);
    }
  }

  private static String string(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
        ? value.getAsString()
        : null;
  }

  private static long number(JsonObject object, String key) {
    JsonElement value = object.get(key);
    if (value == null
        || !value.isJsonPrimitive()
        || !value.getAsJsonPrimitive().isNumber()
        || !value.getAsString().matches("-?[0-9]{1,19}"))
      throw new IllegalArgumentException("UPDATE_MANIFEST_NUMBER");
    return Long.parseLong(value.getAsString());
  }

  private static int integer(JsonObject object, String key) {
    long value = number(object, key);
    if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
      throw new IllegalArgumentException("UPDATE_MANIFEST_NUMBER");
    return (int) value;
  }
}
