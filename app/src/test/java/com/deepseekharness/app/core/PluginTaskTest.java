package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

/** 真实临时文件与 PluginTask；线程调度归属另由 Repository 回归检查。 */
@RunWith(PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class PluginTaskTest {
  private static final String OPERATION = "12345678-1234-1234-1234-123456789abc";
  private static final String ID = OPERATION.replace("-", "");
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();
  private File root;

  @Before
  public void prepare() throws Exception {
    root = temporary.newFolder("rootfs");
    assertTrue(new File(root, "root").mkdir());
  }

  private File signal(String suffix) {
    return new File(root, "root/.dsha-plugin-task-" + ID + suffix);
  }

  private static void write(File file, String value) throws IOException {
    Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void operationIdentitySelectsItsOwnGuestProgressAndCancelFiles() throws Exception {
    try (PluginTask task = new PluginTask(root, OPERATION)) {
      assertEquals(ID, task.id);
      write(
          signal(".json"),
          "{\"stage\":\"download\",\"message\":\"receiving\",\"current\":7,\"total\":11,\"cancellable\":true}");
      JSONObject progress = task.read();
      assertEquals("download", progress.getString("stage"));
      assertEquals(7, progress.getLong("current"));
      assertEquals(11, progress.getLong("total"));
      assertTrue(progress.getBoolean("cancellable"));
      assertFalse(task.requested());
      task.check();
      task.cancel();
      task.cancel();
      assertTrue(signal(".cancel").isFile());
      assertTrue(task.requested());
      assertThrows(IOException.class, task::check);
    }
    assertFalse(signal(".json").exists());
    assertFalse(signal(".cancel").exists());
  }

  @Test
  public void missingMalformedAndOversizeProgressRemainUnknown() throws Exception {
    try (PluginTask task = new PluginTask(root, OPERATION)) {
      assertEquals(0, task.read().length());
      write(signal(".json"), "{\"stage\":");
      assertEquals(0, task.read().length());
      write(signal(".json"), new JSONObject().put("message", "x".repeat(16384)).toString());
      assertEquals(0, task.read().length());
      write(signal(".json"), "{\"stage\":\"commit\",\"cancellable\":false}");
      assertEquals("commit", task.read().getString("stage"));
      assertFalse(task.read().getBoolean("cancellable"));
      assertFalse(task.requested());
    }
  }

  @Test
  public void failedCancelSignalDoesNotEraseTheInMemoryCancellationIntent() throws Exception {
    assertTrue(signal(".cancel").mkdir());
    try (PluginTask task = new PluginTask(root, OPERATION)) {
      assertThrows(IOException.class, task::cancel);
      assertTrue(task.requested());
      assertThrows(IOException.class, task::check);
      assertTrue(signal(".cancel").isDirectory());
    }
  }

  @Test
  public void closingCleansOnlyThisOperationAndLateCancelCannotRecreateSignals() throws Exception {
    PluginTask task = new PluginTask(root, OPERATION);
    File unrelated = new File(root, "root/.dsha-plugin-task-" + "f".repeat(32) + ".json");
    write(unrelated, "{\"stage\":\"other-operation\"}");
    write(signal(".json"), "{\"stage\":\"download\"}");
    write(signal(".json.tmp"), "unfinished progress write");
    task.cancel();
    task.close();
    task.close();
    task.cancel();
    assertFalse(signal(".json").exists());
    assertFalse(signal(".json.tmp").exists());
    assertFalse(signal(".cancel").exists());
    assertEquals(0, task.read().length());
    assertTrue(task.requested());
    assertEquals("{\"stage\":\"other-operation\"}", Files.readString(unrelated.toPath()));
  }

  @Test
  public void concurrentCloseAndCancelNeverResurrectTheSignal() throws Exception {
    PluginTask task = new PluginTask(root, OPERATION);
    write(signal(".json"), "{\"stage\":\"download\"}");
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread cancel =
        new Thread(
            () -> {
              try {
                if (!start.await(2, TimeUnit.SECONDS)) throw new IOException("测试开始超时");
                task.cancel();
              } catch (Throwable error) {
                failure.set(error);
              }
            },
            "plugin-test-cancel");
    cancel.setDaemon(true);
    cancel.start();
    try {
      start.countDown();
      task.close();
      cancel.join(3000);
      assertFalse(cancel.isAlive());
      if (failure.get() != null) throw new AssertionError(failure.get());
      assertFalse(signal(".cancel").exists());
      assertFalse(signal(".json").exists());
      task.cancel();
      assertFalse(signal(".cancel").exists());
    } finally {
      start.countDown();
      cancel.join(3000);
      task.close();
    }
  }

  @Test
  public void invalidOperationIdentityCannotAddressAnotherFile() {
    for (String invalid :
        new String[] {"", "a".repeat(31), "a".repeat(33), "../outside", "/tmp/other"}) {
      assertThrows(IllegalArgumentException.class, () -> new PluginTask(root, invalid));
    }
    assertArrayEquals(new String[0], new File(root, "root").list());
  }
}
