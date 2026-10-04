package com.deepseekharness.app.recovery;

import com.deepseekharness.app.backup.*;
import java.io.*;
import java.util.*;

/** Prepared regeneration proof and read-only occupancy; never deletes sessions or unknown originals. */
final class RecoveryStoragePlan {
  static void prepared(BackupFileSystem fs, File staging, String runtimeId, String instanceId)
      throws IOException {
    File linux = fs.child(staging, "linux");
    if (!fs.stat(linux).type.equals("DIRECTORY")
        || !runtimeId.matches("[a-f0-9]{64}")
        || !instanceId.matches("[a-f0-9]{32}")) throw new IOException("RECOVERY_PREPARED_IDENTITY");
    fs.atomic(
        staging,
        "prepare-proof.json",
        BackupJson.write(
            Map.of(
                "schema",
                1L,
                "runtimeId",
                runtimeId,
                "instanceId",
                instanceId,
                "rootKey",
                fs.stat(linux).key,
                "digest",
                BackupTree.digest(fs, linux, new BackupControl(null))),
            8192));
  }

  static File reusable(
      BackupFileSystem fs, File files, File capsules, String runtimeId, Set<String> active)
      throws IOException {
    for (String name : fs.list(capsules)) {
      if (!name.matches(runtimeId + "\\.pending-[a-f0-9]{32}")) continue;
      File staging = fs.child(capsules, name);
      if (active.contains(staging.getAbsolutePath()) || !fs.stat(staging).type.equals("DIRECTORY"))
        continue;
      try {
        var proof = BackupJson.read(fs.small(fs.child(staging, "prepare-proof.json"), 8192), 8192);
        String id = name.substring(name.lastIndexOf('-') + 1);
        if (BackupJson.number(proof, "schema") != 1
            || !runtimeId.equals(proof.get("runtimeId"))
            || !id.equals(proof.get("instanceId"))) continue;
        if (!fs.stat(fs.child(files, "recovery-sessions/" + id + "/launched"))
            .type
            .equals("MISSING")) continue;
        File linux = fs.child(staging, "linux");
        if (!fs.stat(linux).type.equals("DIRECTORY")
            || !fs.stat(linux).key.equals(proof.get("rootKey"))) continue;
        if (proof.get("digest").equals(BackupTree.digest(fs, linux, new BackupControl(null))))
          return staging;
      } catch (InterruptedIOException cancelled) {
        throw cancelled;
      } catch (IOException invalid) {
        /* Original is retained; it is not a reusable candidate. */
      }
    }
    return null;
  }

  /** 已核验候选的证明先落盘，再同步保留原件与发布的两条目录边界。 */
  static void publish(
      BackupFileSystem fs, File capsules, File staging, String runtimeId, String instanceId)
      throws IOException {
    if (!runtimeId.matches("[a-f0-9]{64}")
        || !instanceId.matches("[a-f0-9]{32}")
        || !staging.getAbsoluteFile().getParentFile().equals(capsules.getAbsoluteFile())
        || !staging.getName().matches(runtimeId + "\\.pending-[a-f0-9]{32}")
        || !fs.stat(fs.child(capsules, staging.getName())).type.equals("DIRECTORY"))
      throw new IOException("RECOVERY_PUBLISH_IDENTITY");
    File capsule = fs.child(capsules, runtimeId);
    File retained = fs.child(capsules, runtimeId + ".retained-" + instanceId);
    if (!fs.stat(retained).type.equals("MISSING")) throw new IOException("RECOVERY_RETAIN_FAILED");
    var original = fs.stat(capsule);
    if (!original.type.equals("MISSING") && !original.type.equals("DIRECTORY"))
      throw new IOException("RECOVERY_PUBLISH_TARGET_TYPE");
    fs.atomic(staging, "verified.json", BackupJson.write(Map.of("runtimeId", runtimeId), 8192));
    if (original.type.equals("DIRECTORY")) {
      fs.move(capsule, retained);
      fs.syncDirectory(capsules);
    }
    fs.move(staging, capsule);
    fs.syncDirectory(capsules);
  }

  static Map<String, Object> usage(BackupFileSystem fs, File files) throws IOException {
    Map<String, Object> result = new LinkedHashMap<>();
    List<String> warnings = new ArrayList<>();
    long[] budget = {0};
    for (String name : List.of("recovery-capsules", "recovery-sessions")) {
      try {
        result.put(name, bytes(fs, new File(files, name), budget, 0));
      } catch (IOException error) {
        result.put(name, "unknown");
        warnings.add(name + ":" + error.getMessage());
      }
    }
    result.put("warnings", List.copyOf(warnings));
    result.put("measurement", "apparent-file-bytes-no-link-follow");
    result.put("automaticDeletion", false);
    return Collections.unmodifiableMap(result);
  }

  private static long bytes(BackupFileSystem fs, File file, long[] budget, int depth)
      throws IOException {
    if (++budget[0] > 100000 || depth > 128) throw new IOException("RECOVERY_STORAGE_SCAN_LIMIT");
    var node = fs.stat(file);
    if (node.type.equals("MISSING")) return 0;
    if (node.type.equals("FILE") || node.type.equals("LINK")) return node.size;
    if (!node.type.equals("DIRECTORY")) throw new IOException("RECOVERY_STORAGE_NODE_TYPE");
    long total = 0;
    for (String name : fs.list(file)) {
      long size = bytes(fs, fs.child(file, name), budget, depth + 1);
      if (size < 0 || Long.MAX_VALUE - total < size)
        throw new IOException("RECOVERY_STORAGE_SIZE_LIMIT");
      total += size;
    }
    return total;
  }

  private RecoveryStoragePlan() {}
}
