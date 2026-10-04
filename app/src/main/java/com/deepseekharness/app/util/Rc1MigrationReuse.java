package com.deepseekharness.app.util;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A completed host checkpoint may avoid guest work only for freshly verified small inputs. */
public final class Rc1MigrationReuse {
  private static final String HOME = "/root/.dsh";
  private static final Set<String> INPUTS =
      Set.of("settings.yaml", "settings.yaml.imported", "restore");

  private Rc1MigrationReuse() {}

  /**
   * The caller supplies the selected data root's actual device/inode and hashes freshly read through
   * a no-follow filesystem boundary. An empty input map means all three paths were checked absent;
   * null means unavailable. Links, unsupported mappings and unstable reads must not reach this gate.
   * APK versions and user archive metadata cannot substitute for these private host records.
   */
  public static boolean matches(
      Map<String, Object> current,
      Map<String, Object> prepared,
      Map<String, Object> receipt,
      String device,
      String inode,
      Map<String, String> inputs) {
    if (current == null || prepared == null || receipt == null || !validInputs(inputs))
      return false;
    if (!versionTwo(current) || !versionTwo(prepared) || !versionTwo(receipt)) return false;
    Object generation = current.get("generation");
    if (!(generation instanceof String)
        || !Ids.uuid((String) generation)
        || !generation.equals(prepared.get("generation"))
        || !generation.equals(receipt.get("generation"))) return false;
    if (!HOME.equals(current.get("dshHome"))
        || !HOME.equals(prepared.get("dshHome"))
        || !"prepared".equals(prepared.get("status"))
        || !Boolean.TRUE.equals(prepared.get("protectionComplete"))
        || !"committed".equals(receipt.get("status"))
        || !Boolean.TRUE.equals(receipt.get("protectionComplete"))
        || !Boolean.TRUE.equals(receipt.get("sourcePreserved"))
        || !Boolean.TRUE.equals(receipt.get("settingsImported"))) return false;
    Object runtime = prepared.get("dshVersion");
    if (!(runtime instanceof String)
        || ((String) runtime).isEmpty()
        || !runtime.equals(receipt.get("dshVersion"))) return false;
    if (!identity(current.get("dataRoot"), device, inode)
        || !identity(prepared.get("dataRoot"), device, inode)) return false;
    return sameInputs(current.get("inputs"), inputs) && sameInputs(prepared.get("inputs"), inputs);
  }

  private static boolean versionTwo(Map<String, Object> value) {
    Object version = value.get("version");
    return Long.valueOf(2).equals(version) || Integer.valueOf(2).equals(version);
  }

  private static boolean identity(Object value, String device, String inode) {
    if (!decimal(device) || !decimal(inode) || !(value instanceof Map)) return false;
    Map<?, ?> root = (Map<?, ?>) value;
    return HOME.equals(root.get("path"))
        && device.equals(root.get("device"))
        && inode.equals(root.get("inode"));
  }

  private static boolean decimal(String value) {
    return value != null && value.matches("[0-9]{1,20}");
  }

  private static boolean validInputs(Object value) {
    if (!(value instanceof Map)) return false;
    Map<?, ?> inputs = (Map<?, ?>) value;
    for (Map.Entry<?, ?> entry : inputs.entrySet()) {
      if (!(entry.getKey() instanceof String)
          || !INPUTS.contains(entry.getKey())
          || !(entry.getValue() instanceof String)
          || !((String) entry.getValue()).matches("[a-f0-9]{64}")) return false;
    }
    return true;
  }

  private static boolean sameInputs(Object value, Map<String, String> inputs) {
    if (!validInputs(value)) return false;
    Map<?, ?> old = (Map<?, ?>) value;
    if (!Objects.equals(old.get("restore"), inputs.get("restore"))) return false;
    // The upstream importer may rename a single settings file. When both inputs exist, neither
    // can mask changes to the other by being preferred during normalization.
    boolean oldBoth = old.containsKey("settings.yaml") && old.containsKey("settings.yaml.imported");
    boolean nowBoth =
        inputs.containsKey("settings.yaml") && inputs.containsKey("settings.yaml.imported");
    if (oldBoth || nowBoth) return old.equals(inputs);
    Object oldSettings =
        old.containsKey("settings.yaml")
            ? old.get("settings.yaml")
            : old.get("settings.yaml.imported");
    Object settings =
        inputs.containsKey("settings.yaml")
            ? inputs.get("settings.yaml")
            : inputs.get("settings.yaml.imported");
    return Objects.equals(oldSettings, settings);
  }
}
