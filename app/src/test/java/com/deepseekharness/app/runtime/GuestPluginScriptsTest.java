package com.deepseekharness.app.runtime;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import com.deepseekharness.app.util.PluginDownloadSource;
import org.junit.Test;
import static org.junit.Assert.*;

public class GuestPluginScriptsTest {
  private static GuestPluginScripts.Preparation preparation(
      List<String> calls, boolean ready, boolean python) {
    return new GuestPluginScripts.Preparation() {
      public boolean environmentReady() {
        calls.add("ready");
        return ready;
      }

      public boolean rootPresent() {
        return ready;
      }

      public boolean pythonReady() {
        calls.add("python");
        return python;
      }

      public void pnpm() {
        calls.add("pnpm");
      }

      public void assets() {
        calls.add("assets");
      }
    };
  }

  @Test
  public void failedPreparationNeverStartsProfileWritingHelper() throws Exception {
    List<String> calls = new ArrayList<>();
    GuestPluginScripts first =
        new GuestPluginScripts(
            preparation(calls, false, true),
            (command, timeout) -> {
              fail("must not start");
              return null;
            });
    assertThrows(GuestPreparationFailure.class, () -> first.builtin(""));
    assertEquals(List.of("ready"), calls);
    calls.clear();
    GuestPluginScripts selected =
        new GuestPluginScripts(
            preparation(calls, true, false),
            (command, timeout) -> {
              fail();
              return null;
            });
    assertThrows(
        GuestPreparationFailure.class,
        () -> selected.manager("list", "", PluginDownloadSource.AUTO));
    assertEquals(List.of("ready", "python"), calls);
  }

  @Test
  public void commandSelectionAndPreparationPreserveFixedProotAndActualTask() throws Exception {
    List<String> calls = new ArrayList<>();
    Thread caller = Thread.currentThread();
    var queueField = GuestPluginScripts.class.getDeclaredField("SCRIPT_QUEUE");
    queueField.setAccessible(true);
    Object queue = queueField.get(null);
    var monitorField = GuestScriptQueue.class.getDeclaredField("lock");
    monitorField.setAccessible(true);
    Object monitor = monitorField.get(queue);
    GuestPluginScripts scripts =
        new GuestPluginScripts(
            preparation(calls, true, true),
            (command, timeout) -> {
              assertSame(caller, Thread.currentThread());
              assertFalse(
                  "The command callback must run without the queue monitor",
                  Thread.holdsLock(monitor));
              calls.add(command);
              assertTrue(timeout >= 900_000);
              return null;
            });
    scripts.manager("import 'local archive.tar.gz'", "a".repeat(32), PluginDownloadSource.AUTO);
    assertEquals(List.of("ready", "python", "pnpm", "assets"), calls.subList(0, 4));
    assertTrue(
        calls
            .get(4)
            .startsWith(
                "DSHA_PLUGIN_TASK="
                    + "a".repeat(32)
                    + " DSHA_PLUGIN_DOWNLOAD_SOURCE=auto python3 /root/.dsh/plugin-manager.py"));
    assertFalse(calls.get(4).contains("--ignore-scripts"));
    assertFalse(calls.get(4).contains("--frozen-lockfile"));
  }

  @Test
  public void migrationOnlyUsesKnownVerbAndQuotesStartupIdentity() {
    assertThrows(
        IllegalArgumentException.class, () -> GuestPluginScripts.migrationCommand("$(bad)", "id"));
    assertTrue(GuestPluginScripts.migrationCommand("prepare", "a'b").contains("'a'\\''b'"));
  }

  @Test
  public void verifiedReuseSkipsGuestOnlyForThePreparedStartupId() throws Exception {
    String generation = java.util.UUID.randomUUID().toString();
    List<String> calls = new ArrayList<>();
    var preparation =
        new GuestPluginScripts.Preparation() {
          public boolean environmentReady() {
            return true;
          }

          public boolean rootPresent() {
            return true;
          }

          public boolean pythonReady() {
            return true;
          }

          public void pnpm() {}

          public void assets() {
            calls.add("assets");
          }

          public String reusableGeneration() {
            return generation;
          }
        };
    GuestPluginScripts scripts =
        new GuestPluginScripts(
            preparation,
            (command, timeout) -> {
              calls.add(command);
              return null;
            });
    var prepared = scripts.migration("prepare", "startup");
    assertEquals(0, prepared.exitCode);
    assertTrue(
        com.deepseekharness.app.util.Rc1MigrationResult.allowsStart(
            com.deepseekharness.app.util.Rc1MigrationResult.parse(prepared.output)));
    assertTrue(scripts.migration("finalize", "startup").output.contains("committed"));
    assertTrue(calls.isEmpty());
    scripts.migration("finalize", "another-startup");
    assertEquals("assets", calls.get(0));
    assertTrue(calls.get(1).contains(" finalize "));
  }
}
