package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.Ids;
import com.deepseekharness.app.util.Rc1MigrationReuse;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** 只用本次 NOFOLLOW 小型读取复用已提交的私有迁移，不遍历会话。 */
final class Rc1MigrationCache {
  static String generation(BackupFileSystem fs, File files, File home) throws IOException {
    String prefix = files.getAbsolutePath() + File.separator;
    if (!home.getAbsolutePath().startsWith(prefix))
      throw new IOException("MIGRATION_REUSE_HOME_AUTHORITY");
    // 即使三个配置输入都缺失，仍核对所选根的全部父级。
    home =
        fs.child(
            files,
            home.getAbsolutePath().substring(prefix.length()).replace(File.separatorChar, '/'));
    var root = fs.stat(home);
    if (!root.type.equals("DIRECTORY")) return null;
    String[] identity = root.key.split(":", -1);
    if (identity.length != 2 || !identity[0].equals(Long.toString(root.device))) return null;
    File state = fs.child(files, "rc1-migration-state");
    Map<String, Object> current = json(fs, state, "current.json");
    Object id = current.get("generation");
    if (!(id instanceof String) || !Ids.uuid((String) id)) return null;
    String folder = "generations/" + id + "/";
    Map<String, Object> prepared = json(fs, state, folder + "reuse.json"),
        receipt = json(fs, state, folder + "receipt.json");
    if (!Constants.DSH_VERSION.equals(prepared.get("dshVersion"))) return null;
    Map<String, String> inputs = new LinkedHashMap<>();
    for (String name :
        new String[] {"settings.yaml", "settings.yaml.imported", ".dsha-rc1-restore-generation"}) {
      File file = fs.child(home, name);
      var before = fs.stat(file);
      if (before.type.equals("MISSING")) continue;
      if (!before.type.equals("FILE")) return null;
      int maximum = name.startsWith(".dsha-") ? 4096 : 4 * 1024 * 1024;
      byte[] bytes = fs.small(file, maximum);
      if (!before.same(fs.stat(file))) return null;
      inputs.put(name.startsWith(".dsha-") ? "restore" : name, hash(bytes));
    }
    if (!root.same(fs.stat(home))) return null;
    // 再次读取所有权威记录及输入；读取中发生代次替换时
    // 不能借不同代次的记录放行缓存。
    if (!current.equals(json(fs, state, "current.json"))
        || !prepared.equals(json(fs, state, folder + "reuse.json"))
        || !receipt.equals(json(fs, state, folder + "receipt.json"))) return null;
    return Rc1MigrationReuse.matches(current, prepared, receipt, identity[0], identity[1], inputs)
        ? (String) id
        : null;
  }

  private static Map<String, Object> json(BackupFileSystem fs, File state, String name)
      throws IOException {
    File file = fs.child(state, name);
    var before = fs.stat(file);
    if (!before.type.equals("FILE")) throw new IOException("MIGRATION_REUSE_RECORD_TYPE");
    byte[] bytes = fs.small(file, 64 * 1024);
    if (!before.same(fs.stat(file))) throw new IOException("MIGRATION_REUSE_RECORD_CHANGED");
    return BackupJson.read(bytes, 64 * 1024);
  }

  static String output(String generation, boolean finalized) throws IOException {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", finalized ? "committed" : "already");
    result.put("generation", generation);
    result.put("protectionComplete", true);
    result.put("cached", true);
    if (finalized) {
      result.put("sourcePreserved", true);
      result.put("settingsImported", true);
    }
    return "DSHA_RC1_MIGRATION="
        + new String(BackupJson.write(result, 4096), StandardCharsets.UTF_8)
        + "\n";
  }

  private static String hash(byte[] bytes) throws IOException {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder value = new StringBuilder();
      for (byte b : digest) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
      return value.toString();
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IOException(error);
    }
  }

  private Rc1MigrationCache() {}
}
