package com.deepseekharness.app.core;

import static org.junit.Assert.*;
import android.content.Context;
import android.os.Looper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.PluginDownloadSource;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;

/** 实际Repository/LiveData/任务凭据与主Looper；仅guest执行口和维护磁盘口隔离。 */
@RunWith(PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = PluginRepositoryQueryLifecycleTest.App.class,
    shadows = {
      PluginRepositoryQueryLifecycleTest.Controller.class,
      PluginRepositoryQueryLifecycleTest.Boot.class,
      PluginRepositoryQueryLifecycleTest.Maintenance.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class PluginRepositoryQueryLifecycleTest {
  public static final class NoApkRunner extends RobolectricTestRunner {
    public NoApkRunner(Class<?> type) throws org.junit.runners.model.InitializationError {
      super(type);
    }

    @Override
    protected java.util.Properties getBuildSystemApiProperties() {
      return new java.util.Properties();
    }
  }

  public static final class App extends DshaApp {
    @Override
    public void onCreate() {
      RuntimeTasks.initialize(this);
    }
  }

  @Implements(value = HarnessController.class, isInAndroidSdk = false)
  public static final class Controller {
    static HarnessController value;
    static ProotBootstrap boot;

    @Implementation
    protected static HarnessController get(Context context) {
      return value;
    }

    @Implementation
    protected ProotBootstrap proot() {
      return boot;
    }

    @Implementation
    protected String getWebAuthUrl() {
      return "";
    }
  }

  @Implements(value = MaintenanceCoordinator.class, isInAndroidSdk = false)
  public static final class Maintenance {
    @Implementation
    protected static boolean pending(HarnessController ignored) {
      return false;
    }

    @Implementation
    protected static boolean isExclusive() {
      return false;
    }
  }

  @Implements(value = ProotBootstrap.class, isInAndroidSdk = false)
  public static final class Boot {
    static File root;
    static final List<String> calls = new java.util.concurrent.CopyOnWriteArrayList<>();
    static String output;
    static CountDownLatch entered, release;

    @Implementation
    protected File getRootfsDir() {
      return root;
    }

    @Implementation
    protected boolean isEnvironmentReady() {
      return true;
    }

    @Implementation
    protected BoundedProcessRunner.Result runPluginManagerResult(
        String args, String taskId, PluginDownloadSource source) throws Exception {
      calls.add(args);
      if (entered != null) entered.countDown();
      if (release != null && !release.await(2, TimeUnit.SECONDS))
        throw new AssertionError("测试释放超时");
      return BoundedProcessRunner.localCompletion("PLUGIN_RESULT: " + output + "\n");
    }
  }

  private PluginRepository repository;
  private List<Runnable> queue;
  private org.robolectric.shadows.ShadowLooper main;

  @Before
  public void prepare() throws Exception {
    Controller.value = org.robolectric.shadow.api.Shadow.newInstanceOf(HarnessController.class);
    Controller.boot = org.robolectric.shadow.api.Shadow.newInstanceOf(ProotBootstrap.class);
    Boot.root = new File(RuntimeEnvironment.getApplication().getFilesDir(), "fixture-root");
    assertTrue(new File(Boot.root, "root").isDirectory() || new File(Boot.root, "root").mkdirs());
    Boot.calls.clear();
    Boot.entered = Boot.release = null;
    queue = new ArrayList<>();
    repository = new PluginRepository(RuntimeEnvironment.getApplication(), queue::add);
    main = org.robolectric.shadow.api.Shadow.extract(Looper.getMainLooper());
  }

  @After
  public void release() {
    if (Boot.release != null) Boot.release.countDown();
    main.idle();
    assertFalse("任务完成后不得遗留全局占用", EnvironmentTaskGate.isBusy());
  }

  private String list(boolean enabled) {
    return "{\"status\":\"ok\",\"message\":\"listed\",\"items\":[{\"name\":\"@deepseek-ai/dsh-voice-input\",\"enabled\":"
        + enabled
        + ",\"available\":true,\"version\":\"1.0.0\"}]}";
  }

  private void runWorker() throws Exception {
    Thread worker = new Thread(queue.remove(0));
    worker.start();
    worker.join(3000);
    assertFalse(worker.isAlive());
    main.idle();
  }

  private void seed() throws Exception {
    Boot.output = list(false);
    repository.refresh();
    runWorker();
  }

  @Test
  public void repeatedReadsCoalesceAndCancelledFirstReadNeverBecomesConfirmedZero()
      throws Exception {
    repository.refresh();
    repository.refresh();
    repository.refresh();
    assertEquals(1, queue.size());
    assertTrue(repository.state().getValue().cancellable);
    assertFalse(repository.hasKnownItems());
    repository.cancelTask();
    runWorker();
    assertTrue(Boot.calls.isEmpty());
    assertFalse(repository.isBusy());
    assertFalse(repository.hasKnownItems());
    assertTrue(repository.state().getValue().message.contains("取消"));
    assertTrue(queue.isEmpty());
  }

  @Test
  public void failedReadKeepsPreviousListAndDoesNotStartASecondList() throws Exception {
    seed();
    Boot.calls.clear();
    Boot.output = "{\"status\":\"error\",\"message\":\"synthetic listing failure\"}";
    var previous = repository.state().getValue().items;
    repository.refresh();
    runWorker();
    assertSame(previous, repository.state().getValue().items);
    assertTrue(repository.hasKnownItems());
    assertEquals(List.of("refresh"), Boot.calls);
    assertTrue(repository.state().getValue().message.contains("synthetic listing failure"));
    assertTrue(queue.isEmpty());
  }

  @Test
  public void executorWaitConsumesQueryDeadlineAndCannotLaunchAfterItExpires() throws Exception {
    repository = new PluginRepository(RuntimeEnvironment.getApplication(), queue::add, 20);
    repository.refresh();
    Thread.sleep(45);
    runWorker();
    assertTrue(Boot.calls.isEmpty());
    assertTrue(queue.isEmpty());
    assertFalse(repository.isBusy());
    assertFalse(repository.hasKnownItems());
    assertTrue(repository.state().getValue().message.contains("读取已超时"));
  }

  @Test
  public void failedVersionCheckDoesNotQueueOrHideItsErrorWithAList() throws Exception {
    seed();
    Boot.calls.clear();
    Boot.output = "{\"status\":\"error\",\"message\":\"synthetic version failure\"}";
    repository.checkUpdates(null);
    runWorker();
    assertEquals(List.of("check-updates"), Boot.calls);
    assertTrue(queue.isEmpty());
    assertTrue(repository.state().getValue().message.contains("synthetic version failure"));
    assertFalse(repository.isBusy());
  }

  @Test
  public void verifiedMutationPublishesIntentBeforeAnIndependentCoalescedRefresh()
      throws Exception {
    seed();
    Boot.calls.clear();
    var item = repository.state().getValue().items.get(0);
    Boot.output =
        "{\"status\":\"ok\",\"message\":\"enabled; restart Web\",\"name\":\""
            + item.name
            + "\",\"enabled\":true,\"intentVerified\":true}";
    repository.setEnabled(item, true);
    repository.refresh();
    repository.refresh();
    runWorker();
    assertTrue(repository.state().getValue().items.get(0).enabled);
    assertEquals("queued", repository.state().getValue().items.get(0).loadState);
    assertEquals(List.of("enable-state '" + item.name + "'"), Boot.calls);
    assertEquals(1, queue.size());
    Boot.output = list(true);
    runWorker();
    assertEquals(2, Boot.calls.size());
    assertTrue(queue.isEmpty());
  }

  @Test
  public void wrongOrUnknownMutationReceiptNeverPublishesSuccessOrReplays() throws Exception {
    seed();
    Boot.calls.clear();
    var item = repository.state().getValue().items.get(0);
    Boot.output =
        "{\"status\":\"ok\",\"message\":\"unconfirmed\",\"name\":\"wrong\",\"enabled\":true,\"intentVerified\":true}";
    repository.setEnabled(item, true);
    runWorker();
    assertFalse(repository.state().getValue().items.get(0).enabled);
    assertTrue(repository.state().getValue().message.contains("未确认"));
    assertEquals(1, Boot.calls.size());
    assertTrue(queue.isEmpty());
  }

  @Test
  public void blockedGuestDoesNotBlockMainLooperOrAnotherRuntimeRequestToken() throws Exception {
    Boot.output = list(false);
    Boot.entered = new CountDownLatch(1);
    Boot.release = new CountDownLatch(1);
    repository.refresh();
    Thread worker = new Thread(queue.remove(0));
    worker.start();
    assertTrue(Boot.entered.await(1, TimeUnit.SECONDS));
    AtomicInteger heartbeat = new AtomicInteger();
    new android.os.Handler(Looper.getMainLooper()).post(heartbeat::incrementAndGet);
    main.idle();
    assertEquals(1, heartbeat.get());
    try (RuntimeTasks other = RuntimeTasks.begin("并发响应验证")) {
      assertNotNull(other);
    }
    assertTrue(repository.isBusy());
    assertTrue(repository.state().getValue().cancellable);
    Boot.release.countDown();
    worker.join(3000);
    assertFalse(worker.isAlive());
    main.idle();
    assertFalse(repository.isBusy());
  }
}
