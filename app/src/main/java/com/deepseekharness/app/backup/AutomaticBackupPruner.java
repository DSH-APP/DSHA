package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** Prunes only old, complete, verified automatic copies; all other originals remain untouched. */
public final class AutomaticBackupPruner {
  private AutomaticBackupPruner() {}

  public static void prune(
      BackupFileSystem fs, List<VerifiedBackupCopy> copies, BackupControl control)
      throws IOException {
    List<VerifiedBackupCopy> automatic = new ArrayList<>();
    for (VerifiedBackupCopy copy : copies)
      if (Boolean.TRUE.equals(copy.metadata.get("automatic"))
          && Set.of("COMPLETE", "DATA_SAVED_PLUGIN_WARNINGS").contains(copy.result(true))) {
        copy.verify(fs, control);
        automatic.add(copy);
      }
    automatic.sort((a, b) -> Long.compare(b.created, a.created));
    for (int index = 3; index < automatic.size(); index++) {
      VerifiedBackupCopy copy = automatic.get(index);
      File directory = copy.artifact.getParentFile(), parent = directory.getParentFile();
      Map<String, Object> record =
          BackupJson.read(fs.small(fs.child(directory, "operation.json"), 16384), 16384);
      if (Boolean.TRUE.equals(record.get("busy")) || !"FINISHED".equals(record.get("stage")))
        continue;
      Set<String> allowed =
          Set.of("portable.dshbak", "verified.json", "operation.json", "source-checks");
      if (!fs.list(directory).stream().allMatch(allowed::contains)) continue;
      copy.verify(fs, control);
      fs.removeOwned(parent, copy.id);
    }
  }
}
