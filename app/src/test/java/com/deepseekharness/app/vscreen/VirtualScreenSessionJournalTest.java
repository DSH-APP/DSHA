package com.deepseekharness.app.vscreen;

import static org.junit.Assert.*;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class VirtualScreenSessionJournalTest {
  @Test
  public void durableAttemptAndLateOlderWriteCannotEraseCleanup() throws Exception {
    var root = Files.createTempDirectory("vscreen-journal-");
    try {
      var journal = new VirtualScreenSessionJournal(root.toFile());
      journal.save(3, new JSONObject().put("state", "START_UNKNOWN").put("boot", "test-boot"));
      journal.save(2, new JSONObject().put("state", "STOPPED"));
      assertEquals("START_UNKNOWN", journal.load().getString("state"));
      assertEquals(
          "START_UNKNOWN",
          new VirtualScreenSessionJournal(root.toFile()).load().getString("state"));
    } finally {
      try (var files = Files.list(root)) {
        for (var file : files.toList()) Files.delete(file);
      }
      Files.delete(root);
    }
  }

  @Test
  public void foreignOrUnboundedJournalCannotBecomeStopped() throws Exception {
    var root = Files.createTempDirectory("vscreen-journal-");
    try {
      var journal = new VirtualScreenSessionJournal(root.toFile());
      var path = root.resolve("vscreen-session-v1.json");
      Files.writeString(path, "{\"schema\":1,\"applicationId\":\"foreign\",\"state\":\"STOPPED\"}");
      assertThrows(Exception.class, journal::load);
      Files.writeString(path, "x".repeat(8193));
      assertThrows(Exception.class, journal::load);
    } finally {
      try (var files = Files.list(root)) {
        for (var file : files.toList()) Files.delete(file);
      }
      Files.delete(root);
    }
  }
}
