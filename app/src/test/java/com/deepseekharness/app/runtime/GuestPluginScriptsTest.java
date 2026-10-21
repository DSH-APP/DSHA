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

  @Test
  public void listUsesItsShortBudgetAndPreparationConsumesThatSameDeadline() throws Exception {
    var calls = new ArrayList<String>();
    var scripts =
        new GuestPluginScripts(
            preparation(calls, true, true),
            (command, timeout) -> {
              assertTrue(timeout > 0 && timeout <= GuestPluginScripts.QUERY_TIMEOUT_MS);
              return null;
            });
    scripts.manager("refresh", "", PluginDownloadSource.AUTO);
    var slow =
        new GuestPluginScripts(
            new GuestPluginScripts.Preparation() {
              public boolean environmentReady() {
                return true;
              }

              public boolean rootPresent() {
                return true;
              }

              public boolean pythonReady() {
                try {
                  Thread.sleep(70);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                return true;
              }

              public void pnpm() {
                fail("期限过后不继续准备");
              }

              public void assets() {
                fail("期限过后不继续准备");
              }
            },
            (command, timeout) -> {
              fail("准备耗尽预算后不能启动guest");
              return null;
            });
    var timeout =
        assertThrows(
            IOException.class, () -> slow.manager("list", "", PluginDownloadSource.AUTO, 30, true));
    assertEquals("PLUGIN_QUERY_TIMEOUT", timeout.getMessage());
  }

  @Test
  public void cancelledPreparationCannotLaunchAQueuedGuestOrConsumeAnotherCommand()
      throws Exception {
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    var scripts =
        new GuestPluginScripts(
            new GuestPluginScripts.Preparation() {
              public boolean environmentReady() {
                cancelled.set(true);
                return true;
              }

              public boolean rootPresent() {
                return true;
              }

              public boolean pythonReady() {
                fail();
                return false;
              }

              public void pnpm() {
                fail();
              }

              public void assets() {
                fail();
              }

              public boolean cancelled(String task) {
                return cancelled.get();
              }
            },
            (command, timeout) -> {
              fail("取消后不能启动guest");
              return null;
            });
    var failure =
        assertThrows(
            IOException.class,
            () -> scripts.manager("list", "a".repeat(32), PluginDownloadSource.AUTO));
    assertEquals("PLUGIN_QUERY_CANCELLED", failure.getMessage());
    var next =
        new GuestPluginScripts(
            preparation(new ArrayList<>(), true, true), (command, timeout) -> null);
    next.manager("enable-state 'official'", "", PluginDownloadSource.AUTO);
  }

  @Test
  public void managerInheritsEarlierSubmissionDeadlineAndRestoresItAfterTheCall() throws Exception {
    var scripts =
        new GuestPluginScripts(
            preparation(new ArrayList<>(), true, true),
            (command, timeout) -> {
              fail("提交期限已耗尽，不能重新取得30秒预算");
              return null;
            });
    var submission = new GuestScriptBudget(20, () -> false, "PLUGIN_QUERY");
    try (var scope = submission.enter()) {
      Thread.sleep(45);
      var failure =
          assertThrows(
              IOException.class, () -> scripts.manager("list", "", PluginDownloadSource.AUTO));
      assertEquals("PLUGIN_QUERY_TIMEOUT", failure.getMessage());
    }
    var next =
        new GuestPluginScripts(
            preparation(new ArrayList<>(), true, true),
            (command, timeout) -> {
              assertTrue(timeout > 0);
              return null;
            });
    next.manager("list", "", PluginDownloadSource.AUTO);
  }
}
