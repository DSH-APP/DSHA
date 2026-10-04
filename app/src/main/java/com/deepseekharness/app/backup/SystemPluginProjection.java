package com.deepseekharness.app.backup;

import com.deepseekharness.app.util.BuiltinPlugins;
import java.io.*;
import java.util.*;

/** Common metadata/marker projection; callers own profile creation and original declarations. */
final class SystemPluginProjection {
  private SystemPluginProjection() {}

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Map<String, Object> owner, String key)
      throws IOException {
    Object value = owner.get(key);
    if (value == null) {
      Map<String, Object> created = new LinkedHashMap<>();
      owner.put(key, created);
      return created;
    }
    if (!(value instanceof Map)) throw new IOException("PLUGIN_SYSTEM_STATE_FORMAT");
    return (Map<String, Object>) value;
  }

  static long apply(
      BackupFileSystem fs,
      File owner,
      Map<String, Object> metadata,
      Map<String, Object> entries,
      BackupControl control)
      throws IOException {
    if (entries.size() > BuiltinPlugins.SIGNED_BUILTINS.size())
      throw new IOException("PLUGIN_SYSTEM_STATE_LIMIT");
    Map<String, Object> dependencies = object(metadata, "dependencies"),
        dsh = object(metadata, "dsh"),
        profile = object(dsh, "profile");
    Object current = profile.get("bundles");
    if (current != null && !(current instanceof List))
      throw new IOException("PLUGIN_SYSTEM_STATE_FORMAT");
    List<Object> bundles = new ArrayList<>();
    if (current instanceof List) bundles.addAll((List<?>) current);
    long applied = 0;
    for (var row : entries.entrySet()) {
      control.check();
      String name = row.getKey();
      if (!BuiltinPlugins.SIGNED_BUILTINS.contains(name) || !(row.getValue() instanceof Map))
        throw new IOException("PLUGIN_SYSTEM_STATE_NAME");
      Map<?, ?> state = (Map<?, ?>) row.getValue();
      if (!(state.get("enabled") instanceof Boolean) || !(state.get("disabled") instanceof Boolean))
        throw new IOException("PLUGIN_SYSTEM_STATE_FORMAT");
      boolean disabled = Boolean.TRUE.equals(state.get("disabled")),
          enabled = Boolean.TRUE.equals(state.get("enabled")) && !disabled;
      bundles.removeIf(name::equals);
      if (enabled) bundles.add(name);
      dependencies.put(name, "link:" + BuiltinPlugins.entityDir(name));
      String relative = "node_modules/" + name;
      File payload = new File(owner, relative);
      if (!fs.stat(payload).type.equals("MISSING")) fs.removeOwned(owner, relative);
      File marker = new File(owner, relative + ".disabled");
      String type = fs.stat(marker).type;
      if (disabled) {
        if (type.equals("MISSING")) {
          fs.parents(owner, relative + ".disabled");
          try (OutputStream output = fs.create(marker)) {}
        } else if (!type.equals("FILE")) throw new IOException("PLUGIN_SYSTEM_STATE_MARKER");
      } else if (type.equals("FILE")) fs.delete(marker);
      else if (!type.equals("MISSING")) throw new IOException("PLUGIN_SYSTEM_STATE_MARKER");
      applied++;
    }
    profile.put("bundles", bundles);
    profile.put("patchReload", "startup");
    return applied;
  }
}
