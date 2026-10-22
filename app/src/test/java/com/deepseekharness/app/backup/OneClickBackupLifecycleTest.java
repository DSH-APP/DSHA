package com.deepseekharness.app.backup;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Looper;
import androidx.lifecycle.*;
import com.deepseekharness.app.BuildConfig;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;

/** 真实 ViewModel/LiveData 和排队入口；仅作业提交端受控，不启动 guest 或停止设备。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public final class OneClickBackupLifecycleTest {
  static final class Queue implements Executor {
    final List<Runnable> work = new ArrayList<>();

    public void execute(Runnable r) {
      work.add(r);
    }

    void next() throws Exception {
      Runnable r = work.remove(0);
      var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      Thread t =
          new Thread(
              () -> {
                try {
                  r.run();
                } catch (Throwable e) {
                  failure.set(e);
                }
              },
              "controlled-backup-worker");
      t.start();
      t.join(3000);
      assertFalse(t.isAlive());
      if (failure.get() != null) throw new AssertionError(failure.get());
      org.robolectric.shadows.ShadowLooper main = Shadow.extract(Looper.getMainLooper());
      main.idle();
    }
  }

  static final class Exporter implements OneClickBackup.Exporter {
    int submitted, cancelled;
    Uri target;
    boolean fail;

    public NativeBackupJobs.State submit(Uri tree, BackupControl control) throws IOException {
      assertNotSame(Looper.getMainLooper().getThread(), Thread.currentThread());
      control.check();
      submitted++;
      target = tree;
      if (fail) throw new IOException("SAF_PERMISSION_REVOKED");
      return new NativeBackupJobs.State("test-job", "PREPARING", "", "", "", 0, 0, true);
    }

    public void cancel(String id) {
      cancelled++;
    }
  }

  Application app;
  Queue queue;
  Exporter exporter;
  OneClickBackup model;

  @Before
  public void setup() {
    app = RuntimeEnvironment.getApplication();
    queue = new Queue();
    exporter = new Exporter();
    model = new OneClickBackup(app, queue, exporter);
  }

  @After
  public void close() {
    model.onCleared();
  }

  @Test
  public void doubleTapAndBusyJobNeverSubmitTwice() throws Exception {
    assertTrue(model.start());
    assertFalse(model.start());
    assertEquals(1, queue.work.size());
    assertEquals(0, exporter.submitted);
    queue.next();
    assertEquals(1, exporter.submitted);
    assertFalse(model.start());
    assertTrue(queue.work.isEmpty());
  }

  @Test
  public void constructorAndRepeatedLiveDataReadsNeverReplayAJob() {
    for (int i = 0; i < 5; i++) {
      model.status.getValue();
      model.busy.getValue();
      model.chooseDirectory.getValue();
    }
    assertEquals(0, exporter.submitted);
    assertTrue(queue.work.isEmpty());
  }

  @Test
  @Config(sdk = BuildConfig.LOW_ANDROID ? 23 : 34)
  public void destinationRequestAndViewRecreationDoNotSubmitTwice() throws Exception {
    assertEquals(BuildConfig.LOW_ANDROID ? 23 : 34, Build.VERSION.SDK_INT);
    assertTrue(model.start());
    queue.next();
    if (BuildConfig.LOW_ANDROID) {
      assertEquals(0, exporter.submitted);
      assertTrue(model.chooseDirectory.getValue());
      assertTrue(model.claimDirectoryPicker());
    } else {
      assertEquals(1, exporter.submitted);
      assertNull(exporter.target);
      assertFalse(model.chooseDirectory.getValue());
    }
    assertFalse(model.claimDirectoryPicker());
    ViewModelStore store = new ViewModelStore();
    var factory =
        new ViewModelProvider.Factory() {
          @Override
          public <T extends ViewModel> T create(Class<T> type) {
            return type.cast(model);
          }
        };
    var first = new ViewModelProvider(store, factory).get(OneClickBackup.class);
    var recreated = new ViewModelProvider(store, factory).get(OneClickBackup.class);
    assertSame(first, recreated);
    assertFalse(recreated.claimDirectoryPicker());
    assertFalse(recreated.start());
    assertEquals(BuildConfig.LOW_ANDROID ? 0 : 1, exporter.submitted);
    assertTrue(queue.work.isEmpty());
    store.clear();
  }

  @Test
  @Config(sdk = BuildConfig.LOW_ANDROID ? 23 : 34)
  public void cancellingDestinationPreparationDoesNotCreateOrReplayAnExport() throws Exception {
    assertEquals(BuildConfig.LOW_ANDROID ? 23 : 34, Build.VERSION.SDK_INT);
    model.start();
    if (BuildConfig.LOW_ANDROID) {
      queue.next();
      assertTrue(model.claimDirectoryPicker());
      model.directoryResult(null);
    } else {
      assertFalse(model.claimDirectoryPicker());
      model.cancel();
      queue.next();
      queue.next();
    }
    org.robolectric.shadows.ShadowLooper main = Shadow.extract(Looper.getMainLooper());
    main.idle();
    assertFalse(model.chooseDirectory.getValue());
    assertTrue(queue.work.isEmpty());
    assertEquals(0, exporter.submitted);
    assertFalse(model.claimDirectoryPicker());
    assertTrue(model.status.getValue().contains("CANCELLED"));
    var reopened = new OneClickBackup(app, queue, exporter);
    assertTrue(queue.work.isEmpty());
    assertEquals(0, exporter.submitted);
    reopened.onCleared();
  }

  @Test
  public void cancellingQueuedPreparationCannotStartAJob() throws Exception {
    model.start();
    model.cancel();
    queue.next();
    queue.next();
    assertEquals(0, exporter.submitted);
    assertEquals(1, exporter.cancelled);
    assertTrue(model.status.getValue().contains("CANCELLED"));
  }

  @Test
  public void destinationFailureIsExplicitAndNeverFallsBackToPrivateSuccessOrRetry()
      throws Exception {
    exporter.fail = true;
    model.start();
    queue.next();
    assertEquals(1, exporter.submitted);
    assertTrue(queue.work.isEmpty());
    assertTrue(model.status.getValue().contains("SAF_PERMISSION_REVOKED"));
    assertFalse(model.busy.getValue());
    assertFalse(model.chooseDirectory.getValue());
  }

  @Test
  @Config(sdk = BuildConfig.LOW_ANDROID ? 23 : 34)
  public void savedDirectoryGrantIsReusedOnlyByTheLegacyDestinationRoute() throws Exception {
    assertEquals(BuildConfig.LOW_ANDROID ? 23 : 34, Build.VERSION.SDK_INT);
    Uri tree = Uri.parse("content://test.dsha.directory/tree/backup");
    app.getContentResolver()
        .takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    app.getSharedPreferences("dsha_one_click_backup", Context.MODE_PRIVATE)
        .edit()
        .putString("destination_tree", tree.toString())
        .commit();
    model.start();
    queue.next();
    assertEquals(1, exporter.submitted);
    assertEquals(BuildConfig.LOW_ANDROID ? tree : null, exporter.target);
    assertFalse(model.chooseDirectory.getValue());
  }

  @Test
  @Config(sdk = BuildConfig.LOW_ANDROID ? 23 : 34)
  public void missingDirectoryGrantRequiresSelectionOnlyOnTheLegacyRoute() throws Exception {
    assertEquals(BuildConfig.LOW_ANDROID ? 23 : 34, Build.VERSION.SDK_INT);
    app.getSharedPreferences("dsha_one_click_backup", Context.MODE_PRIVATE)
        .edit()
        .putString("destination_tree", "content://lost/tree/backup")
        .commit();
    model.start();
    queue.next();
    if (BuildConfig.LOW_ANDROID) {
      assertEquals(0, exporter.submitted);
      assertTrue(model.chooseDirectory.getValue());
    } else {
      assertEquals(1, exporter.submitted);
      assertNull(exporter.target);
      assertFalse(model.chooseDirectory.getValue());
      assertFalse(model.claimDirectoryPicker());
    }
  }

  @Test
  public void delayedOldCancelCannotCancelTheNewNativeJob() throws Exception {
    var constructor = NativeBackupJobs.class.getDeclaredConstructor(Context.class);
    constructor.setAccessible(true);
    var nativeJobs = constructor.newInstance(app);
    var state = NativeBackupJobs.class.getDeclaredField("state");
    state.setAccessible(true);
    var control = NativeBackupJobs.class.getDeclaredField("control");
    control.setAccessible(true);
    BackupControl current = new BackupControl(null);
    state.set(
        nativeJobs, new NativeBackupJobs.State("new-job", "PREPARING", "", "", "", 0, 0, true));
    control.set(nativeJobs, current);
    nativeJobs.cancelIfCurrent("old-job");
    assertFalse(current.isCancelled());
    nativeJobs.cancelIfCurrent("");
    assertFalse(current.isCancelled());
    nativeJobs.cancelIfCurrent("new-job");
    assertTrue(current.isCancelled());
  }
}
