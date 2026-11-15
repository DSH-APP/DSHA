package com.deepseekharness.app.core;

import static org.junit.Assert.*;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import com.deepseekharness.app.DshaApp;
import com.deepseekharness.app.plugins.PluginBackendAdapter;
import com.deepseekharness.app.plugins.PluginOperationQueue.Phase;
import com.deepseekharness.app.plugins.PluginSourceIdentity;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.BoundedProcessRunner;
import com.deepseekharness.app.util.EnvironmentTaskGate;
import com.deepseekharness.app.util.PluginDownloadSource;
import com.deepseekharness.app.util.PluginSource;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
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
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;

/** 使用真实 Repository、Registry、队列、LiveData 与任务锁，只隔离来源/guest/停止边界。 */
@RunWith(PluginRepositoryQueryLifecycleTest.NoApkRunner.class)
@Config(
    sdk = 34,
    manifest = Config.NONE,
    application = PluginRepositoryQueryLifecycleTest.App.class,
    shadows = {
      PluginRepositoryQueryLifecycleTest.Controller.class,
      PluginRepositoryQueryLifecycleTest.Boot.class,
      PluginRepositoryQueryLifecycleTest.Maintenance.class,
      PluginRepositoryQueryLifecycleTest.SourceResolver.class,
      PluginRepositoryQueryLifecycleTest.SignalAudit.class
    })
@LooperMode(LooperMode.Mode.PAUSED)
public final class PluginRepositoryQueryLifecycleTest {
  private static final String ALPHA = "example-alpha", BETA = "example-beta";

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
    static StartupDiagnostics diagnostics;
    static volatile String auth;
    static volatile boolean starting;
    static final AtomicInteger starts = new AtomicInteger();

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
      return auth;
    }

    @Implementation
    protected com.deepseekharness.app.util.PreviewPageSession.Identity getReadyWebPageIdentity() {
      return auth.isEmpty() || starting
          ? null
          : new com.deepseekharness.app.util.PreviewPageSession.Identity(
              Boot.generation, "fixture-instance", auth);
    }

    @Implementation
    protected StartupDiagnostics startupDiagnostics() {
      return diagnostics;
    }

    @Implementation
    protected boolean startWeb(java.util.function.Consumer<String> status) {
      assertFalse("恢复 Web 前必须交还环境租约", EnvironmentTaskGate.isBusy());
      assertFalse("恢复 Web 前必须交还运行锁", RuntimeTasks.isBusy());
      starts.incrementAndGet();
      starting = true;
      return true;
    }

    @Implementation
    protected long getWebGeneration() {
      return Boot.generation;
    }

    @Implementation
    protected boolean isStarting() {
      return starting;
    }

    @Implementation
    protected boolean isStopping() {
      return false;
    }
  }

  @Implements(value = MaintenanceCoordinator.class, isInAndroidSdk = false)
  public static final class Maintenance {
    static int commits;

    @Implementation
    protected static boolean pending(HarnessController ignored) {
      return false;
    }

    @Implementation
    protected static boolean isExclusive() {
      return false;
    }

    @Implementation
    protected static <T> T exclusive(
        HarnessController ignored, MaintenanceCoordinator.Operation<T> operation) throws Exception {
      assertNotSame(Looper.getMainLooper(), Looper.myLooper());
      assertTrue(EnvironmentTaskGate.ownsCurrentThread());
      // 保留真实 RuntimeTasks 维护准入，检测 detached token 未交还的死锁。
      try (var fence = RuntimeTasks.tryEnterMaintenance()) {
        assertNotNull("提交必须交还 detached token，允许同步维护", fence);
        HarnessSessionState session =
            DshaApp.from(RuntimeEnvironment.getApplication()).harnessSession();
        synchronized (session.lifecycle) {
          Boot.generation++;
          session.startProtection.cancelAll();
          Controller.auth = "";
          Controller.starting = false;
        }
        commits++;
        return operation.run();
      }
    }
  }

  interface SourceObservation {
    void captured(PluginSourceIdentity identity) throws Exception;
  }

  @Implements(value = PluginBackendAdapter.class, isInAndroidSdk = false)
  public static final class SourceResolver {
    static final AtomicInteger resolutions = new AtomicInteger();
    static volatile SourceObservation beforeReturn;

    @Implementation
    protected PluginBackendAdapter.ResolvedSource resolveSource() throws Exception {
      assertNotSame("来源磁盘检查应在后台", Looper.getMainLooper(), Looper.myLooper());
      resolutions.incrementAndGet();
      PluginSourceIdentity observed = identity();
      SourceObservation hook = beforeReturn;
      if (hook != null) hook.captured(observed);
      Constructor<PluginBackendAdapter.ResolvedSource> constructor =
          PluginBackendAdapter.ResolvedSource.class.getDeclaredConstructor(
              PluginSourceIdentity.class, List.class);
      constructor.setAccessible(true);
      return constructor.newInstance(observed, List.of());
    }

    static PluginSourceIdentity identity() {
      return new PluginSourceIdentity(
          new File(Boot.root, "root/.dsh"),
          "data:" + Boot.root.getAbsolutePath(),
          1,
          Boot.root,
          "root:" + Boot.root.getAbsolutePath(),
          1,
          "fixture-runtime-marker",
          Boot.generation);
    }
  }

  interface Guest {
    BoundedProcessRunner.Result execute(String command, String taskId) throws Exception;
  }

  @Implements(value = ProotBootstrap.class, isInAndroidSdk = false)
  public static final class Boot {
    static volatile File root;
    static volatile long generation;
    static volatile Guest guest;
    static final List<String> calls = new CopyOnWriteArrayList<>();
    static final List<String> taskIds = new CopyOnWriteArrayList<>();
    static final byte[] archive = "fixture plugin archive".getBytes(StandardCharsets.UTF_8);

    @Implementation
    protected File getRootfsDir() {
      return root;
    }

    @Implementation
    protected boolean isEnvironmentReady() {
      assertNotSame("环境检查应在后台", Looper.getMainLooper(), Looper.myLooper());
      return true;
    }

    @Implementation
    protected BoundedProcessRunner.Result runPluginManagerResult(
        String args, String taskId, PluginDownloadSource source) throws Exception {
      assertNotSame("guest 不得在主线程执行", Looper.getMainLooper(), Looper.myLooper());
      assertTrue(EnvironmentTaskGate.ownsCurrentThread());
      calls.add(args);
      taskIds.add(taskId);
      return guest.execute(args, taskId);
    }

    @Implementation
    protected boolean pullFileFromContainer(String from, File to) throws IOException {
      Files.write(to.toPath(), archive);
      return true;
    }
  }

  /** 委托真实信号实现，只记录调用线程；不替代文件读写或取消结果。 */
  @Implements(value = PluginTask.class, isInAndroidSdk = false)
  public static final class SignalAudit {
    @RealObject private PluginTask task;
    static final AtomicBoolean mainIo = new AtomicBoolean();
    static CountDownLatch read, cancelled, closed;

    private static void observe() {
      if (Looper.myLooper() == Looper.getMainLooper()) mainIo.set(true);
    }

    @Implementation
    protected JSONObject read() {
      observe();
      JSONObject result = Shadow.directlyOn(task, PluginTask.class, "read");
      read.countDown();
      return result;
    }

    @Implementation
    protected void cancel() throws IOException {
      observe();
      Shadow.directlyOn(task, PluginTask.class, "cancel");
      cancelled.countDown();
    }

    @Implementation
    protected void close() {
      observe();
      Shadow.directlyOn(task, PluginTask.class, "close");
      closed.countDown();
    }
  }

  private PluginRepository repository;
  private final List<Runnable> queue = new ArrayList<>();
  private final List<Worker> workers = new ArrayList<>();
  private final List<CountDownLatch> releases = new ArrayList<>();
  private org.robolectric.shadows.ShadowLooper main;

  @Before
  public void prepare() throws Exception {
    Controller.value = Shadow.newInstanceOf(HarnessController.class);
    Controller.boot = Shadow.newInstanceOf(ProotBootstrap.class);
    Controller.diagnostics = new StartupDiagnostics(RuntimeEnvironment.getApplication());
    Controller.auth = "";
    Controller.starting = false;
    Controller.starts.set(0);
    assertFalse(Controller.diagnostics.snapshot().safe);
    Boot.root = root("fixture-root");
    Boot.generation = 11;
    Boot.calls.clear();
    Boot.taskIds.clear();
    Boot.guest =
        (command, id) -> {
          throw new IOException("未安排 guest 回执：" + command);
        };
    SourceResolver.resolutions.set(0);
    SourceResolver.beforeReturn = null;
    Maintenance.commits = 0;
    SignalAudit.mainIo.set(false);
    SignalAudit.read = new CountDownLatch(1);
    SignalAudit.cancelled = new CountDownLatch(1);
    SignalAudit.closed = new CountDownLatch(1);
    repository = new PluginRepository(RuntimeEnvironment.getApplication(), queue::add);
    main = Shadow.extract(Looper.getMainLooper());
    assertFalse(EnvironmentTaskGate.isBusy());
    assertFalse(EnvironmentTaskGate.hasSharedUse());
    assertFalse(RuntimeTasks.isBusy());
  }

  @After
  public void release() throws Exception {
    for (CountDownLatch latch : releases) latch.countDown();
    for (Worker worker : workers) worker.await();
    main.idle();
    assertFalse("任务完成后不得遗留全局占用", EnvironmentTaskGate.isBusy());
    assertFalse("任务完成后不得遗留共享占用", EnvironmentTaskGate.hasSharedUse());
    assertFalse("任务完成后不得遗留工作锁", RuntimeTasks.isBusy());
    assertFalse("信号读、取消写入和清理不得在主线程", SignalAudit.mainIo.get());
  }

  private static File root(String name) throws IOException {
    File root = new File(RuntimeEnvironment.getApplication().getFilesDir(), name);
    Files.createDirectories(new File(root, "root/.dsh").toPath());
    return root;
  }

  private static JSONObject item(String name, boolean enabled) throws Exception {
    return new JSONObject()
        .put("name", name)
        .put("enabled", enabled)
        .put("available", true)
        .put("exportable", true)
        .put("deletable", true)
        .put("version", "1.0.0");
  }

  private static String list(boolean alpha, boolean beta) throws Exception {
    return new JSONObject()
        .put("status", "ok")
        .put("message", "listed")
        .put("items", new JSONArray().put(item(ALPHA, alpha)).put(item(BETA, beta)))
        .toString();
  }

  private static String intent(String name, boolean enabled) throws Exception {
    return new JSONObject()
        .put("status", "ok")
        .put("message", "intent confirmed; restart Web")
        .put("name", name)
        .put("enabled", enabled)
        .put("intentVerified", true)
        .toString();
  }

  private static BoundedProcessRunner.Result result(String json) {
    return BoundedProcessRunner.localCompletion("PLUGIN_RESULT: " + json + "\n");
  }

  private void reply(String json) {
    Boot.guest = (command, id) -> result(json);
  }

  private static final class Worker {
    final AtomicReference<Throwable> failure = new AtomicReference<>();
    final CountDownLatch started = new CountDownLatch(1);
    final Thread thread;

    Worker(Runnable run) {
      thread =
          new Thread(
              () -> {
                try {
                  started.countDown();
                  run.run();
                } catch (Throwable error) {
                  failure.set(error);
                }
              },
              "plugin-test-worker");
      thread.setDaemon(true);
      thread.start();
    }

    void await() throws Exception {
      thread.join(5000);
      assertFalse("后台任务应完成", thread.isAlive());
      if (failure.get() != null) throw new AssertionError("后台任务失败", failure.get());
    }
  }

  private Worker startWorker() {
    assertFalse("必须存在已接收的后台工作", queue.isEmpty());
    Worker worker = new Worker(queue.remove(0));
    workers.add(worker);
    return worker;
  }

  private void finish(Worker worker) throws Exception {
    worker.await();
    main.idle();
  }

  private void runWorker() throws Exception {
    finish(startWorker());
  }

  private void seed() throws Exception {
    reply(list(false, false));
    repository.refresh();
    runWorker();
    assertTrue(repository.hasKnownItems());
    assertEquals(2, repository.state().getValue().items.size());
    assertSame(repository.registry().snapshot().items, repository.state().getValue().items);
    Boot.calls.clear();
    Boot.taskIds.clear();
    SourceResolver.resolutions.set(0);
  }

  private CountDownLatch blockGuest(Guest afterRelease) {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    releases.add(release);
    Boot.guest =
        (command, id) -> {
          entered.countDown();
          if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("测试释放超时");
          return afterRelease.execute(command, id);
        };
    return entered;
  }

  private void unblock() {
    releases.get(releases.size() - 1).countDown();
  }

  private void assertUnknown() {
    assertFalse(repository.hasKnownItems());
    assertFalse(repository.state().getValue().catalog.known);
    assertTrue(repository.state().getValue().catalog.stale);
    assertFalse(repository.isBusy());
  }

  @Test
  public void repeatedReadsCoalesceAndCancelledFirstReadNeverBecomesConfirmedZero()
      throws Exception {
    repository.refresh();
    repository.refresh();
    repository.refresh();
    assertEquals(1, queue.size());
    assertFalse(EnvironmentTaskGate.isBusy());
    assertFalse(RuntimeTasks.isBusy());
    assertEquals(0, SourceResolver.resolutions.get());
    repository.cancelTask();
    runWorker();
    assertTrue(Boot.calls.isEmpty());
    assertUnknown();
    assertTrue(repository.state().getValue().message.contains("取消"));
    assertTrue(queue.isEmpty());
  }

  @Test
  public void firstReadFailureNeverPublishesAConfirmedEmptyCatalog() throws Exception {
    reply("{\"status\":\"error\",\"message\":\"synthetic listing failure\"}");
    repository.refresh();
    runWorker();
    assertUnknown();
    assertEquals(List.of("refresh"), Boot.calls);
    assertTrue(repository.state().getValue().catalog.error.contains("synthetic listing failure"));
    assertTrue(queue.isEmpty());
  }

  @Test
  public void failedReadKeepsPreviousListAndDoesNotStartASecondList() throws Exception {
    seed();
    reply("{\"status\":\"error\",\"message\":\"synthetic listing failure\"}");
    var previous = repository.registry().snapshot().items;
    repository.refresh();
    runWorker();
    assertSame(previous, repository.state().getValue().items);
    assertTrue(repository.hasKnownItems());
    assertTrue(repository.state().getValue().catalog.stale);
    assertEquals(List.of("refresh"), Boot.calls);
    assertTrue(repository.state().getValue().message.contains("synthetic listing failure"));
    assertTrue(queue.isEmpty());
  }

  @Test
  public void executorWaitConsumesQueryDeadlineAndCannotLaunchAfterItExpires() throws Exception {
    repository = new PluginRepository(RuntimeEnvironment.getApplication(), queue::add, 20);
    repository.refresh();
    assertFalse(EnvironmentTaskGate.isBusy());
    assertFalse(RuntimeTasks.isBusy());
    Thread.sleep(60);
    runWorker();
    assertTrue(Boot.calls.isEmpty());
    assertEquals(0, SourceResolver.resolutions.get());
    assertTrue(queue.isEmpty());
    assertUnknown();
    assertTrue(repository.state().getValue().message.contains("读取已超时"));
  }

  @Test
  public void unknownGuestReadKeepsPriorCatalogWithoutReplaying() throws Exception {
    seed();
    var previous = repository.registry().snapshot().items;
    Boot.guest =
        (command, id) -> {
          throw new IOException("PLUGIN_QUERY_EXECUTION_UNKNOWN");
        };
    repository.refresh();
    runWorker();
    assertSame(previous, repository.state().getValue().items);
    assertTrue(repository.hasKnownItems());
    assertTrue(repository.state().getValue().catalog.stale);
    assertTrue(repository.state().getValue().catalog.error.contains("UNKNOWN"));
    assertEquals(List.of("refresh"), Boot.calls);
    assertTrue(queue.isEmpty());
  }

  @Test
  public void failedVersionCheckKeepsItsOperationResultWithoutStartingAList() throws Exception {
    seed();
    reply("{\"status\":\"error\",\"message\":\"synthetic version failure\"}");
    String id = repository.checkUpdates(null);
    assertEquals(Phase.QUEUED, repository.operation(id).phase);
    runWorker();
    assertEquals(Phase.FAILED, repository.operation(id).phase);
    assertEquals(List.of("check-updates"), Boot.calls);
    assertTrue(repository.operation(id).message.contains("synthetic version failure"));
    assertTrue(queue.isEmpty());
  }

  @Test
  public void differentPluginsExecuteFifoAndQueuedWorkOwnsNoLeaseOrRuntimeToken() throws Exception {
    seed();
    var alpha = repository.state().getValue().items.get(0);
    var beta = repository.state().getValue().items.get(1);
    String first = repository.setEnabled(alpha, true);
    String second = repository.setEnabled(beta, true);
    assertNotEquals(first, second);
    assertEquals(1, queue.size());
    assertEquals(Phase.QUEUED, repository.operation(first).phase);
    assertEquals(Phase.QUEUED, repository.operation(second).phase);
    assertFalse(EnvironmentTaskGate.isBusy());
    assertFalse(RuntimeTasks.isBusy());
    assertEquals(0, SourceResolver.resolutions.get());
    try (var unrelated = EnvironmentTaskGate.tryAcquire("等待期间其它环境请求")) {
      assertNotNull(unrelated);
    }
    reply(intent(ALPHA, true));
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(first).phase);
    assertEquals(Phase.QUEUED, repository.operation(second).phase);
    assertEquals(List.of("enable-state '" + ALPHA + "'"), Boot.calls);
    assertFalse(EnvironmentTaskGate.isBusy());
    assertFalse(RuntimeTasks.isBusy());
    reply(intent(BETA, true));
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(second).phase);
    assertEquals(
        List.of("enable-state '" + ALPHA + "'", "enable-state '" + BETA + "'"), Boot.calls);
    assertTrue(repository.state().getValue().items.stream().allMatch(row -> row.enabled));
    assertTrue(queue.isEmpty());
  }

  @Test
  public void latestQueuedToggleSupersedesOnlyWaitingWorkAndCannotChangeRunningIntent()
      throws Exception {
    seed();
    var alpha = repository.state().getValue().items.get(0);
    CountDownLatch entered = blockGuest((command, task) -> result(intent(ALPHA, true)));
    String running = repository.setEnabled(alpha, true);
    Worker worker = startWorker();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    String replaced = repository.setEnabled(alpha, true);
    String latest = repository.setEnabled(alpha, false);
    assertEquals(Phase.RUNNING, repository.operation(running).phase);
    assertEquals(Boolean.TRUE, repository.operation(running).desired);
    assertEquals(Phase.SUPERSEDED, repository.operation(replaced).phase);
    assertEquals(Phase.QUEUED, repository.operation(latest).phase);
    assertEquals(Boolean.FALSE, repository.operation(latest).desired);
    assertFalse(repository.state().getValue().items.get(0).enabled);
    assertEquals(1, Boot.calls.size());
    unblock();
    finish(worker);
    assertTrue(repository.state().getValue().items.get(0).enabled);
    assertEquals(Phase.SUCCEEDED, repository.operation(running).phase);
    reply(intent(ALPHA, false));
    runWorker();
    assertFalse(repository.state().getValue().items.get(0).enabled);
    assertEquals(Phase.SUCCEEDED, repository.operation(latest).phase);
    assertEquals(
        List.of("enable-state '" + ALPHA + "'", "disable-state '" + ALPHA + "'"), Boot.calls);
    assertEquals(Phase.SUPERSEDED, repository.operation(replaced).phase);
    assertTrue(queue.isEmpty());
  }

  @Test
  public void cancelledQueuedMutationNeverAcquiresExecutionResourcesOrRunsGuest() throws Exception {
    seed();
    String id = repository.setEnabled(repository.state().getValue().items.get(0), true);
    assertTrue(repository.cancelOperation(id));
    assertFalse(repository.cancelOperation(id));
    runWorker();
    assertEquals(Phase.CANCELLED, repository.operation(id).phase);
    assertTrue(Boot.calls.isEmpty());
    assertEquals(0, SourceResolver.resolutions.get());
    assertFalse(repository.state().getValue().items.get(0).enabled);
    assertFalse(repository.isBusy());
    assertFalse(repository.state().getValue().busy);
    assertFalse(repository.state().getValue().cancellable);
  }

  @Test
  public void cancelledOnlyOperationCannotLoseEnqueueWhileEmptyWorkerWaitsForMonitor()
      throws Exception {
    seed();
    var alpha = repository.state().getValue().items.get(0);
    var beta = repository.state().getValue().items.get(1);
    String cancelled = repository.setEnabled(alpha, true);
    assertTrue(repository.cancelOperation(cancelled));
    Worker emptyWorker;
    String next;
    synchronized (repository) {
      emptyWorker = startWorker();
      assertTrue(emptyWorker.started.await(2, TimeUnit.SECONDS));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (emptyWorker.thread.getState() != Thread.State.BLOCKED
          && emptyWorker.thread.isAlive()
          && System.nanoTime() < deadline) Thread.sleep(1);
      assertEquals(Thread.State.BLOCKED, emptyWorker.thread.getState());
      assertTrue(
          java.util.Arrays.stream(emptyWorker.thread.getStackTrace())
              .anyMatch(
                  frame ->
                      frame.getClassName().equals(PluginRepository.class.getName())
                          && frame.getMethodName().equals("drain")));
      assertTrue(Boot.calls.isEmpty());
      assertEquals(0, SourceResolver.resolutions.get());
      next = repository.setEnabled(beta, true);
      assertEquals(Phase.QUEUED, repository.operation(next).phase);
      assertTrue("旧 worker 尚持有 working，不能另起消费者", queue.isEmpty());
    }
    finish(emptyWorker);
    assertEquals("空 worker 释放 working 后必须重新唤醒队列", 1, queue.size());
    reply(intent(BETA, true));
    runWorker();
    assertEquals(Phase.CANCELLED, repository.operation(cancelled).phase);
    assertEquals(Phase.SUCCEEDED, repository.operation(next).phase);
    assertEquals(List.of("enable-state '" + BETA + "'"), Boot.calls);
    assertFalse(repository.isBusy());
    assertTrue(queue.isEmpty());
  }

  @Test
  public void cancellationAfterGuestDispatchCannotDowngradeUnknownOutcomeToCancelled()
      throws Exception {
    seed();
    CountDownLatch entered =
        blockGuest(
            (command, task) -> {
              assertTrue(
                  new File(Boot.root, "root/.dsha-plugin-task-" + task + ".cancel").isFile());
              throw new IOException("PLUGIN_DELETE_EXECUTION_UNKNOWN");
            });
    String id = repository.delete(repository.state().getValue().items.get(0));
    Worker worker = startWorker();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    assertEquals(Phase.RUNNING, repository.operation(id).phase);
    assertTrue(repository.cancelOperation(id));
    assertTrue(SignalAudit.cancelled.await(2, TimeUnit.SECONDS));
    unblock();
    finish(worker);
    assertEquals(Phase.UNCONFIRMED, repository.operation(id).phase);
    assertTrue(repository.operation(id).message.contains("EXECUTION_UNKNOWN"));
    assertEquals(2, repository.state().getValue().items.size());
    assertEquals(ALPHA, repository.state().getValue().items.get(0).name);
    assertFalse(repository.state().getValue().items.get(0).enabled);
    assertEquals(List.of("delete '" + ALPHA + "' '1.0.0'"), Boot.calls);
    assertEquals(List.of(id.replace("-", "")), Boot.taskIds);
    assertFalse(repository.cancelOperation(id));
    assertTrue(queue.isEmpty());
  }

  @Test
  public void runningToggleRejectsCancellationAndStillPublishesVerifiedIntent() throws Exception {
    seed();
    CountDownLatch entered = blockGuest((command, task) -> result(intent(ALPHA, true)));
    String id = repository.setEnabled(repository.state().getValue().items.get(0), true);
    Worker worker = startWorker();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    assertEquals(Phase.RUNNING, repository.operation(id).phase);
    assertFalse(repository.cancelOperation(id));
    assertFalse(
        new File(Boot.root, "root/.dsha-plugin-task-" + id.replace("-", "") + ".cancel").exists());
    unblock();
    finish(worker);
    assertEquals(Phase.SUCCEEDED, repository.operation(id).phase);
    assertTrue(repository.state().getValue().items.get(0).enabled);
    assertEquals(1, SignalAudit.cancelled.getCount());
    assertEquals(List.of("enable-state '" + ALPHA + "'"), Boot.calls);
    assertTrue(queue.isEmpty());
  }

  @Test
  public void confirmedIntentSurvivesIndependentCoalescedRefreshFailure() throws Exception {
    seed();
    var alpha = repository.state().getValue().items.get(0);
    reply(intent(ALPHA, true));
    String id = repository.setEnabled(alpha, true);
    repository.refresh();
    repository.refresh();
    runWorker();
    assertTrue(repository.state().getValue().items.get(0).enabled);
    assertTrue(repository.state().getValue().items.get(0).restartRequired);
    assertEquals("unknown", repository.state().getValue().items.get(0).runtimeState);
    assertEquals(Phase.SUCCEEDED, repository.operation(id).phase);
    String confirmedMessage = repository.operation(id).message;
    assertEquals(1, queue.size());
    reply("{\"status\":\"error\",\"message\":\"follow-up refresh failed\"}");
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(id).phase);
    assertEquals(confirmedMessage, repository.operation(id).message);
    assertTrue(repository.state().getValue().items.get(0).enabled);
    assertTrue(repository.state().getValue().catalog.stale);
    assertEquals(List.of("enable-state '" + ALPHA + "'", "refresh"), Boot.calls);
    assertTrue(queue.isEmpty());
  }

  @Test
  public void confirmedIntentSurvivesSourceReadFailureAfterReceipt() throws Exception {
    seed();
    AtomicBoolean replied = new AtomicBoolean();
    Boot.guest =
        (command, task) -> {
          replied.set(true);
          return result(intent(ALPHA, true));
        };
    SourceResolver.beforeReturn =
        identity -> {
          if (replied.get()) throw new IOException("post-receipt source unavailable");
        };
    String id = repository.setEnabled(repository.state().getValue().items.get(0), true);
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(id).phase);
    assertTrue(repository.state().getValue().items.get(0).enabled);
    assertTrue(repository.state().getValue().catalog.stale);
    assertTrue(repository.state().getValue().catalog.error.contains("post-receipt"));
    assertEquals(List.of("enable-state '" + ALPHA + "'"), Boot.calls);
    assertTrue(queue.isEmpty());
  }

  @Test
  public void temporaryDeletionProvidesGuidanceWithoutQueueingAPackageMutation() throws Exception {
    seed();
    var temporary =
        new PluginRepository.Item(
            new JSONObject()
                .put("name", "temporary-plugin")
                .put("dynamic", true)
                .put("deletable", true)
                .put("sessionId", "session")
                .put("pluginId", "temporary"));
    assertEquals("", repository.delete(temporary));
    org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
    assertTrue(repository.state().getValue().message.contains("Cordis"));
    assertTrue(Boot.calls.isEmpty());
    assertTrue(queue.isEmpty());
  }

  @Test
  public void confirmedDeletionSurvivesSourceReadFailureAfterReceipt() throws Exception {
    seed();
    AtomicBoolean replied = new AtomicBoolean();
    Boot.guest =
        (command, task) -> {
          replied.set(true);
          return result(
              new JSONObject()
                  .put("status", "ok")
                  .put("message", "removed")
                  .put("name", ALPHA)
                  .put("removed", true)
                  .put("deletionVerified", true)
                  .toString());
        };
    SourceResolver.beforeReturn =
        identity -> {
          if (replied.get()) throw new IOException("post-receipt source unavailable");
        };
    String id = repository.delete(repository.state().getValue().items.get(0));
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(id).phase);
    assertEquals(1, repository.state().getValue().items.size());
    assertEquals(BETA, repository.state().getValue().items.get(0).name);
    assertTrue(repository.state().getValue().catalog.stale);
    assertEquals(1, Boot.calls.size());
    assertTrue(queue.isEmpty());
  }

  @Test
  public void confirmedIntentAcrossOnlyWebGenerationKeepsConfigurationButRetiresRuntime()
      throws Exception {
    seed();
    long previous = Boot.generation;
    var catalog = repository.registry().snapshot();
    repository
        .registry()
        .updateRuntimeItems(
            catalog.source,
            List.of(catalog.items.get(0).withRuntime("loaded", previous), catalog.items.get(1)));
    repository.publishCatalog("");
    Boot.guest =
        (command, task) -> {
          Boot.generation++;
          return result(intent(ALPHA, true));
        };
    String id = repository.setEnabled(repository.state().getValue().items.get(0), true);
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(id).phase);
    assertTrue(repository.state().getValue().items.get(0).enabled);
    assertEquals("unknown", repository.state().getValue().items.get(0).runtimeState);
    assertEquals(previous + 1, repository.state().getValue().catalog.source.webGeneration);
    assertEquals(1, Boot.calls.size());
    assertTrue(queue.isEmpty());
  }

  @Test
  public void confirmedIntentCannotContaminateReplacementUserDataRoot() throws Exception {
    seed();
    Boot.guest =
        (command, task) -> {
          Boot.root = root("replacement-after-receipt");
          repository.registry().updateSource(SourceResolver.identity());
          return result(intent(ALPHA, true));
        };
    String id = repository.setEnabled(repository.state().getValue().items.get(0), true);
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(id).phase);
    assertTrue(repository.state().getValue().items.isEmpty());
    assertFalse(repository.state().getValue().catalog.known);
    assertTrue(repository.state().getValue().catalog.stale);
    assertEquals(1, Boot.calls.size());
    assertTrue(queue.isEmpty());
  }

  @Test
  public void malformedIntentReceiptsNeverPublishSuccessOrReplay() throws Exception {
    seed();
    var alpha = repository.state().getValue().items.get(0);
    String valid = intent(ALPHA, true);
    List<JSONObject> wrong =
        List.of(
            new JSONObject(valid).put("name", "different-plugin"),
            new JSONObject(valid).put("enabled", false),
            new JSONObject(valid).put("enabled", "true"),
            new JSONObject(valid).put("intentVerified", "true"),
            new JSONObject(valid).put("intentVerified", false),
            new JSONObject(valid).put("enabled", JSONObject.NULL),
            new JSONObject(valid).put("status", "partial"));
    for (JSONObject receipt : wrong) {
      Boot.calls.clear();
      reply(receipt.toString());
      String id = repository.setEnabled(alpha, true);
      runWorker();
      assertEquals(receipt.toString(), Phase.UNCONFIRMED, repository.operation(id).phase);
      assertFalse(repository.state().getValue().items.get(0).enabled);
      assertFalse(repository.state().getValue().items.get(0).restartRequired);
      assertEquals(List.of("enable-state '" + ALPHA + "'"), Boot.calls);
      assertTrue(queue.isEmpty());
    }
  }

  @Test
  public void unknownMutationOutcomeStaysUnconfirmedWithoutReplaying() throws Exception {
    seed();
    Boot.guest =
        (command, id) -> {
          throw new IOException("PLUGIN_INTENT_EXECUTION_UNKNOWN");
        };
    String id = repository.setEnabled(repository.state().getValue().items.get(0), true);
    runWorker();
    assertEquals(Phase.UNCONFIRMED, repository.operation(id).phase);
    assertFalse(repository.state().getValue().items.get(0).enabled);
    assertEquals(List.of("enable-state '" + ALPHA + "'"), Boot.calls);
    assertTrue(queue.isEmpty());
  }

  @Test
  public void blockedGuestKeepsMainHeartbeatAndSignalFileIoOffMain() throws Exception {
    CountDownLatch entered =
        blockGuest(
            (command, id) -> {
              assertTrue(new File(Boot.root, "root/.dsha-plugin-task-" + id + ".cancel").isFile());
              throw new IOException("PLUGIN_QUERY_CANCELLED");
            });
    repository.refresh();
    Worker worker = startWorker();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    assertTrue(SignalAudit.read.await(2, TimeUnit.SECONDS));
    assertEquals(1, EnvironmentTaskGate.sharedCount());
    assertFalse(EnvironmentTaskGate.isBusy());
    assertTrue(RuntimeTasks.isBusy());
    AtomicInteger heartbeat = new AtomicInteger();
    new android.os.Handler(Looper.getMainLooper()).post(heartbeat::incrementAndGet);
    main.idle();
    assertEquals(1, heartbeat.get());
    try (RuntimeTasks other = RuntimeTasks.begin("并发响应验证")) {
      assertNotNull(other);
    }
    repository.cancelTask();
    assertTrue(SignalAudit.cancelled.await(2, TimeUnit.SECONDS));
    assertTrue(repository.isBusy());
    unblock();
    finish(worker);
    assertTrue(SignalAudit.closed.await(2, TimeUnit.SECONDS));
    assertUnknown();
    assertEquals(List.of("refresh"), Boot.calls);
    assertTrue(queue.isEmpty());
  }

  @Test
  public void changedDataSourceInvalidatesOldQueuedMutationAndFreshReadCanRecover()
      throws Exception {
    seed();
    String id = repository.setEnabled(repository.state().getValue().items.get(0), true);
    Boot.root = root("replacement-root");
    runWorker();
    assertEquals(Phase.INVALIDATED, repository.operation(id).phase);
    assertTrue(Boot.calls.isEmpty());
    reply(list(false, true));
    repository.refresh();
    runWorker();
    assertEquals(SourceResolver.identity(), repository.state().getValue().catalog.source);
    assertTrue(repository.hasKnownItems());
    assertFalse(repository.state().getValue().items.get(0).enabled);
    assertTrue(repository.state().getValue().items.get(1).enabled);
    assertEquals(Phase.INVALIDATED, repository.operation(id).phase);
    assertEquals(List.of("refresh"), Boot.calls);
  }

  @Test
  public void inFlightOldQueryCannotOverwriteAlreadyPublishedNewSourceCatalog() throws Exception {
    seed();
    CountDownLatch entered = blockGuest((command, id) -> result(list(true, false)));
    repository.refresh();
    Worker worker = startWorker();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    Boot.root = root("new-query-root");
    var current = SourceResolver.identity();
    repository.registry().updateSource(current);
    var fresh = repository.registry().beginQuery(current);
    assertTrue(
        repository
            .registry()
            .completeQuery(
                fresh,
                List.of(new PluginRepository.Item(item("new-source-plugin", false))),
                List.of(),
                false));
    unblock();
    finish(worker);
    assertEquals(current, repository.state().getValue().catalog.source);
    assertTrue(repository.state().getValue().catalog.known);
    assertEquals("new-source-plugin", repository.state().getValue().items.get(0).name);
    assertEquals(1, repository.state().getValue().items.size());
    assertEquals(List.of("refresh"), Boot.calls);
    assertTrue(queue.isEmpty());
  }

  @Test
  public void delayedOldSourceResolutionCannotRollBackTheCurrentCatalog() throws Exception {
    seed();
    CountDownLatch captured = new CountDownLatch(1), release = new CountDownLatch(1);
    releases.add(release);
    PluginSourceIdentity old = SourceResolver.identity();
    SourceResolver.beforeReturn =
        observed -> {
          assertEquals(old, observed);
          captured.countDown();
          if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("测试释放超时");
        };
    repository.refresh();
    Worker worker = startWorker();
    assertTrue(captured.await(2, TimeUnit.SECONDS));
    Boot.root = root("current-source-root");
    PluginSourceIdentity current = SourceResolver.identity();
    repository.registry().updateSource(current);
    var fresh = repository.registry().beginQuery(current);
    assertTrue(
        repository
            .registry()
            .completeQuery(
                fresh,
                List.of(new PluginRepository.Item(item("current-source-plugin", false))),
                List.of(),
                false));
    release.countDown();
    finish(worker);
    assertEquals(current, repository.state().getValue().catalog.source);
    assertTrue(repository.state().getValue().catalog.known);
    assertEquals("current-source-plugin", repository.state().getValue().items.get(0).name);
    assertEquals(1, repository.state().getValue().items.size());
    assertTrue(Boot.calls.isEmpty());
    assertTrue(queue.isEmpty());
  }

  @Test
  public void webGenerationChangeRejectsOldQueryProjection() throws Exception {
    seed();
    CountDownLatch entered = blockGuest((command, id) -> result(list(true, true)));
    repository.refresh();
    Worker worker = startWorker();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    Boot.generation++;
    unblock();
    finish(worker);
    assertTrue(repository.hasKnownItems());
    assertTrue(repository.state().getValue().catalog.known);
    assertTrue(repository.state().getValue().catalog.stale);
    assertFalse(repository.isBusy());
    assertEquals(2, repository.state().getValue().items.size());
    assertEquals(ALPHA, repository.state().getValue().items.get(0).name);
    assertEquals(BETA, repository.state().getValue().items.get(1).name);
    for (PluginRepository.Item item : repository.state().getValue().items) {
      assertFalse("旧查询返回的启用状态不能覆盖已确认目录", item.enabled);
      assertEquals("unknown", item.runtimeState);
      assertEquals(-1, item.runtimeGeneration);
    }
    assertEquals(SourceResolver.identity(), repository.state().getValue().catalog.source);
    assertEquals(List.of("refresh"), Boot.calls);
    assertTrue(queue.isEmpty());
  }

  @Test
  public void invalidationArrivingDuringQueryRemainsStaleUntilAnotherCompleteRead()
      throws Exception {
    seed();
    CountDownLatch entered = blockGuest((command, id) -> result(list(true, false)));
    repository.refresh();
    Worker worker = startWorker();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    repository.invalidateInstalledState();
    long invalidated = repository.registry().snapshot().invalidationSeq;
    unblock();
    finish(worker);
    assertTrue(repository.state().getValue().catalog.known);
    assertTrue(repository.state().getValue().items.get(0).enabled);
    assertEquals(invalidated, repository.state().getValue().catalog.invalidationSeq);
    assertTrue(repository.state().getValue().catalog.stale);
    reply(list(true, true));
    repository.refresh();
    runWorker();
    assertFalse(repository.state().getValue().catalog.stale);
    assertTrue(repository.state().getValue().items.get(1).enabled);
    assertEquals(List.of("refresh", "refresh"), Boot.calls);
  }

  @Test
  public void busyReadOnlyTasksCoalesceRuntimeProjectionAndReplayOnlyTheRead() throws Exception {
    seed();
    var catalog = repository.registry().snapshot();
    repository
        .registry()
        .updateRuntimeItems(
            catalog.source,
            List.of(
                catalog.items.get(0).withRuntime("loaded", Boot.generation), catalog.items.get(1)));
    repository.publishCatalog("");
    CountDownLatch entered =
        blockGuest(
            (command, task) ->
                result(
                    "{\"status\":\"ok\",\"message\":\"capabilities read\",\"capabilities\":{}}"));
    repository.inspectCapabilities(repository.state().getValue().items.get(0), ignored -> {});
    Worker first = startWorker();
    assertTrue(entered.await(2, TimeUnit.SECONDS));
    // 合并投影与到期投影都在长任务内到达，不能继续显示为当前已确认。
    repository.refreshRuntimeProjection();
    repository.refreshRuntimeProjection();
    main.idle();
    assertTrue(repository.state().getValue().items.get(0).runtimeStale);
    repository.inspectCapabilities(repository.state().getValue().items.get(1), ignored -> {});
    unblock();
    finish(first);
    assertEquals(1, queue.size());
    runWorker(); // 后继任务再次 busy，保留同一个待投影请求。
    assertEquals(1, queue.size());
    int reads = SourceResolver.resolutions.get();
    runWorker();
    assertEquals(reads + 2, SourceResolver.resolutions.get());
    assertEquals(
        List.of("capabilities '" + ALPHA + "'", "capabilities '" + BETA + "'"), Boot.calls);
    assertTrue(repository.state().getValue().items.get(0).runtimeStale);
    assertTrue(queue.isEmpty());
  }

  @Test
  public void exportAndCapabilitiesDoNotInvalidateOrUnconditionallyScanCatalog() throws Exception {
    seed();
    AtomicInteger invalidations = new AtomicInteger();
    repository.setListener(
        new PluginRepository.Listener() {
          @Override
          public void invalidated() {
            invalidations.incrementAndGet();
          }

          @Override
          public void sourceResolved(PluginBackendAdapter.ResolvedSource source) {}

          @Override
          public void queryFinished() {}
        });
    long revision = repository.installedRevision();
    var previous = repository.registry().snapshot().items;
    AtomicReference<String> details = new AtomicReference<>();
    reply(
        "{\"status\":\"ok\",\"message\":\"capabilities"
            + " read\",\"capabilities\":{\"clientInject\":[\"composer\"]}}");
    String capabilities =
        repository.inspectCapabilities(repository.state().getValue().items.get(0), details::set);
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(capabilities).phase);
    assertTrue(details.get().contains("composer"));
    reply("{\"status\":\"ok\",\"message\":\"exported\"}");
    File destination =
        new File(RuntimeEnvironment.getApplication().getCacheDir(), "exported-plugin.tar.gz");
    String exported = repository.exportArchives(List.of(ALPHA), Uri.fromFile(destination));
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(exported).phase);
    assertArrayEquals(Boot.archive, Files.readAllBytes(destination.toPath()));
    assertEquals(2, Boot.calls.size());
    assertEquals("capabilities '" + ALPHA + "'", Boot.calls.get(0));
    assertTrue(Boot.calls.get(1).startsWith("export "));
    assertEquals(0, invalidations.get());
    assertEquals(revision, repository.installedRevision());
    assertSame(previous, repository.state().getValue().items);
    assertTrue(queue.isEmpty());
  }

  private static String preview(String name, String id, String confirmation) throws Exception {
    return new JSONObject()
        .put("status", "ok")
        .put("message", "inspected")
        .put(
            "preview",
            new JSONObject()
                .put("previewId", id)
                .put("confirmationSha256", confirmation)
                .put("items", new JSONArray().put(item(name, false))))
        .toString();
  }

  @Test
  public void installedCandidateInvalidatesQueuedOldDeleteUntilCompleteQuery() throws Exception {
    assertCandidateInvalidatesQueuedDelete(false);
  }

  @Test
  public void updatedCandidateInvalidatesQueuedOldDeleteUntilCompleteQuery() throws Exception {
    assertCandidateInvalidatesQueuedDelete(true);
  }

  private void assertCandidateInvalidatesQueuedDelete(boolean update) throws Exception {
    JSONObject catalog = new JSONObject(list(false, false));
    catalog
        .getJSONArray("items")
        .getJSONObject(0)
        .put("updateAvailable", true)
        .put("latestVersion", "2.0.0");
    reply(catalog.toString());
    repository.refresh();
    runWorker();
    Boot.calls.clear();
    Boot.taskIds.clear();
    var oldAlpha = repository.state().getValue().items.get(0);
    var beta = repository.state().getValue().items.get(1);
    String previewId = "e".repeat(32), confirmation = "f".repeat(64);
    JSONObject candidate = new JSONObject(preview(ALPHA, previewId, confirmation));
    candidate
        .getJSONObject("preview")
        .getJSONArray("items")
        .getJSONObject(0)
        .put("version", "2.0.0");
    Boot.guest =
        (command, task) -> {
          if ((update && command.equals("prepare-update '" + ALPHA + "'"))
              || (!update && command.startsWith("inspect "))) return result(candidate.toString());
          if (command.equals("install-preview '" + previewId + "' '" + confirmation + "'"))
            return result("{\"status\":\"ok\",\"message\":\"installed replacement\"}");
          if (command.equals("delete '" + BETA + "' '1.0.0'")) return result(deleted(BETA));
          throw new IOException("旧目标不得派发删除：" + command);
        };
    String installation =
        update
            ? repository.prepareUpdate(oldAlpha)
            : repository.install(PluginSource.parse("npm:" + ALPHA));
    String oldDeletion = repository.delete(oldAlpha);
    String unrelatedDeletion = repository.delete(beta);
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(installation).phase);
    assertEquals(Phase.QUEUED, repository.operation(oldDeletion).phase);
    assertEquals("1.0.0", repository.state().getValue().items.get(0).version);
    assertEquals(2, Boot.calls.size());
    runWorker();
    assertEquals(Phase.INVALIDATED, repository.operation(oldDeletion).phase);
    assertEquals("旧 DELETE 必须在派发前失效", 2, Boot.calls.size());
    runWorker();
    assertEquals("不能污染候选未涉及的插件", Phase.SUCCEEDED, repository.operation(unrelatedDeletion).phase);
    assertEquals("delete '" + BETA + "' '1.0.0'", Boot.calls.get(2));

    reply("{\"status\":\"error\",\"message\":\"follow-up refresh failed\"}");
    repository.refresh();
    runWorker();
    String stillOld = repository.delete(oldAlpha);
    runWorker();
    assertEquals("失败查询不能清除目标变更标记", Phase.INVALIDATED, repository.operation(stillOld).phase);
    assertEquals(4, Boot.calls.size());
    assertEquals("refresh", Boot.calls.get(3));

    reply(
        new JSONObject()
            .put("status", "ok")
            .put("message", "listed replacement")
            .put("items", new JSONArray().put(item(ALPHA, true).put("version", "2.0.0")))
            .toString());
    repository.refresh();
    runWorker();
    assertFalse(repository.state().getValue().catalog.stale);
    var current = repository.state().getValue().items.get(0);
    assertEquals("2.0.0", current.version);
    reply(deleted(ALPHA));
    String freshDeletion = repository.delete(current);
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(freshDeletion).phase);
    assertEquals("delete '" + ALPHA + "' '2.0.0'", Boot.calls.get(5));
    assertEquals(6, Boot.calls.size());
    assertFalse(Boot.calls.contains("delete '" + ALPHA + "' '1.0.0'"));
    assertTrue(repository.state().getValue().items.isEmpty());
    assertEquals(Phase.INVALIDATED, repository.operation(oldDeletion).phase);
    assertTrue(queue.isEmpty());
  }

  private static String deleted(String name) throws Exception {
    return new JSONObject()
        .put("status", "ok")
        .put("message", "deleted")
        .put("name", name)
        .put("removed", true)
        .put("deletionVerified", true)
        .toString();
  }

  @Test
  public void twoInstallsResumePreviouslyRunningWebOnlyOnceAfterQueueDrains() throws Exception {
    assertInstallBatchResume(false);
  }

  @Test
  public void repeatedUserStopDuringSecondInstallSuppressesDeferredWebResume() throws Exception {
    assertInstallBatchResume(true);
  }

  private void assertInstallBatchResume(boolean userStops) throws Exception {
    Controller.auth = "http://127.0.0.1:3080/?token=fixture";
    HarnessSessionState session =
        DshaApp.from(RuntimeEnvironment.getApplication()).harnessSession();
    long generation = Boot.generation;
    long revision = session.startProtection.cancellationRevision();
    String firstPreview = "a".repeat(32), secondPreview = "b".repeat(32);
    String firstSha = "c".repeat(64), secondSha = "d".repeat(64);
    CountDownLatch secondCommit = new CountDownLatch(1), releaseSecond = new CountDownLatch(1);
    releases.add(releaseSecond);
    Boot.guest =
        (command, task) -> {
          assertEquals("任一安装尚未结束时不得启动 Web", 0, Controller.starts.get());
          if (command.startsWith("inspect ")) {
            boolean first = command.contains(ALPHA);
            return result(
                preview(
                    first ? ALPHA : BETA,
                    first ? firstPreview : secondPreview,
                    first ? firstSha : secondSha));
          }
          if (command.equals("install-preview '" + firstPreview + "' '" + firstSha + "'")) {
            assertEquals(generation + 1, Boot.generation);
            assertEquals(revision + 1, session.startProtection.cancellationRevision());
            return result("{\"status\":\"ok\",\"message\":\"installed first\"}");
          }
          if (command.equals("install-preview '" + secondPreview + "' '" + secondSha + "'")) {
            secondCommit.countDown();
            if (!releaseSecond.await(5, TimeUnit.SECONDS)) throw new IOException("测试释放超时");
            return result("{\"status\":\"ok\",\"message\":\"installed second\"}");
          }
          throw new IOException("未知候选命令：" + command);
        };
    String first = repository.install(PluginSource.parse("npm:" + ALPHA));
    String second = repository.install(PluginSource.parse("npm:" + BETA));
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(first).phase);
    assertEquals(Phase.QUEUED, repository.operation(second).phase);
    assertEquals(0, Controller.starts.get());
    assertFalse(Controller.starting);
    assertEquals("", Controller.auth);
    assertEquals(1, queue.size());
    Worker worker = startWorker();
    assertTrue(secondCommit.await(2, TimeUnit.SECONDS));
    assertTrue(
        List.of(Phase.RUNNING, Phase.COMMITTING).contains(repository.operation(second).phase));
    assertEquals(generation + 2, Boot.generation);
    assertEquals(revision + 2, session.startProtection.cancellationRevision());
    if (userStops) {
      synchronized (session.lifecycle) {
        // 已停止时重复 Stop 不必换代，但每次都必须撤销此前的恢复意图。
        session.startProtection.cancelAll();
        session.startProtection.cancelAll();
      }
      assertEquals(generation + 2, Boot.generation);
      assertEquals(revision + 4, session.startProtection.cancellationRevision());
    }
    assertEquals(0, Controller.starts.get());
    releaseSecond.countDown();
    finish(worker);
    assertEquals(Phase.SUCCEEDED, repository.operation(second).phase);
    assertEquals(2, Maintenance.commits);
    assertEquals(4, Boot.calls.size());
    assertEquals(
        List.of(
            first.replace("-", ""),
            first.replace("-", ""),
            second.replace("-", ""),
            second.replace("-", "")),
        Boot.taskIds);
    assertTrue(queue.isEmpty());
    assertEquals(userStops ? 0 : 1, Controller.starts.get());
    assertEquals(!userStops, Controller.starting);
    main.idle();
    assertEquals("恢复回调不能重复触发", userStops ? 0 : 1, Controller.starts.get());
  }

  @Test
  public void inspectAndInstallPreviewKeepOneOperationAndNeverBorrowTheNextCandidate()
      throws Exception {
    String firstPreview = "a".repeat(32), secondPreview = "b".repeat(32);
    String firstSha = "c".repeat(64), secondSha = "d".repeat(64);
    Boot.guest =
        (command, task) -> {
          if (command.startsWith("inspect ")) {
            boolean first = command.contains(ALPHA);
            return result(
                preview(
                    first ? ALPHA : BETA,
                    first ? firstPreview : secondPreview,
                    first ? firstSha : secondSha));
          }
          if (command.equals("install-preview '" + firstPreview + "' '" + firstSha + "'")
              || command.equals("install-preview '" + secondPreview + "' '" + secondSha + "'")) {
            return result("{\"status\":\"ok\",\"message\":\"installed\"}");
          }
          throw new IOException("未知或串用候选：" + command);
        };
    String first = repository.install(PluginSource.parse("npm:" + ALPHA));
    String second = repository.install(PluginSource.parse("npm:" + BETA));
    assertNotEquals(first, second);
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(first).phase);
    assertEquals(Phase.QUEUED, repository.operation(second).phase);
    assertEquals(firstPreview, repository.operation(first).transactionId);
    assertEquals(first.replace("-", ""), repository.operation(first).backendTaskId);
    assertEquals(2, Boot.calls.size());
    assertEquals(List.of(first.replace("-", ""), first.replace("-", "")), Boot.taskIds);
    assertTrue(repository.installationSucceeded());
    assertNull(repository.preview().getValue());
    runWorker();
    assertEquals(Phase.SUCCEEDED, repository.operation(second).phase);
    assertEquals(secondPreview, repository.operation(second).transactionId);
    assertEquals(
        List.of(
            first.replace("-", ""),
            first.replace("-", ""),
            second.replace("-", ""),
            second.replace("-", "")),
        Boot.taskIds);
    assertEquals("install-preview '" + firstPreview + "' '" + firstSha + "'", Boot.calls.get(1));
    assertEquals("install-preview '" + secondPreview + "' '" + secondSha + "'", Boot.calls.get(3));
    assertEquals(2, Maintenance.commits);
    assertTrue(repository.installedDescription().contains(BETA));
    assertNull(repository.preview().getValue());
    assertTrue(queue.isEmpty());
  }
}
