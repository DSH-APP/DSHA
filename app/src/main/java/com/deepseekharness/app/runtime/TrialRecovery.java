package com.deepseekharness.app.runtime;

import com.deepseekharness.app.backup.BackupFileSystem;
import com.deepseekharness.app.backup.RuntimeTrialRecords;
import com.deepseekharness.app.util.Ids;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** The actual pending-trial recovery algorithm, with platform stop/confirmation supplied explicitly. */
final class TrialRecovery {
  interface Stopper {
    void stopAndConfirm(File payload) throws IOException;
  }

  private TrialRecovery() {}

  static void recover(BackupFileSystem fs, File home, Stopper stopper) throws IOException {
    if (fs.stat(home).type.equals("MISSING")) return;
    List<String> entries = fs.list(home);
    if (entries.size() > 64) throw new IOException("TRIAL_RETENTION_LIMIT");
    for (String id : entries) {
      if (!id.matches(Ids.UUID_PATTERN)) throw new IOException("TRIAL_DIRECTORY");
      File entry = fs.child(home, id), closed = fs.child(entry, "closed");
      if (!fs.stat(closed).type.equals("MISSING")) {
        if (!id.equals(new String(fs.small(closed, 128), StandardCharsets.US_ASCII)))
          throw new IOException("TRIAL_MARKER");
        if (!fs.stat(fs.child(entry, "payload")).type.equals("MISSING"))
          fs.removeOwned(entry, "payload");
        continue;
      }
      File payload = fs.child(entry, "payload");
      if (!fs.stat(fs.child(entry, "launched")).type.equals("MISSING")) {
        if (fs.stat(fs.child(payload, ".dsha-web.pid")).type.equals("MISSING")
            && fs.stat(fs.child(payload, ".dsha-web.pid.stale")).type.equals("MISSING"))
          throw new IOException("TRIAL_PROCESS_UNCONFIRMED");
        stopper.stopAndConfirm(payload);
      }
      TrialSupport.write(fs, entry, "closed", id.getBytes(StandardCharsets.US_ASCII));
      fs.removeOwned(entry, "payload");
    }
    RuntimeTrialRecords.pruneClosed(fs, home);
  }
}
