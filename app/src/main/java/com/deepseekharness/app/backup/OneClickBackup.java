package com.deepseekharness.app.backup;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** 一键入口只编排界面意图；安全停止、归档和前台服务仍由原宿主作业负责。 */
public final class OneClickBackup extends AndroidViewModel {
  interface Exporter {
    NativeBackupJobs.State submit(Uri tree, BackupControl control) throws IOException;

    void cancel(String id);
  }

  private static final String PREFS = "dsha_one_click_backup", TREE = "destination_tree";
  private final Context context;
  private final java.util.concurrent.Executor executor;
  private final Exporter exporter;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final AtomicBoolean preparing = new AtomicBoolean();
  private volatile BackupControl preparation;
  private volatile NativeBackupJobs jobs;
  private volatile NativeBackupJobs.State job;
  private volatile String issue = "", destination = "";
  private volatile boolean directoryNeeded, directoryClaimed, cleared, cancelRequested;
  private final Observer<NativeBackupJobs.State> observer = this::changed;
  public final MutableLiveData<String> status = new MutableLiveData<>("");
  public final MutableLiveData<Boolean> busy = new MutableLiveData<>(false);
  public final MutableLiveData<Boolean> chooseDirectory = new MutableLiveData<>(false);

  public OneClickBackup(Application app) {
    this(app, work -> new Thread(work, "one-click-backup").start(), null);
  }

  OneClickBackup(Application app, java.util.concurrent.Executor executor, Exporter exporter) {
    super(app);
    context = app.getApplicationContext();
    this.executor = executor;
    this.exporter = exporter;
    if (exporter == null)
      worker(
          () -> {
            jobs = NativeBackupJobs.get(context);
            main.post(
                () -> {
                  if (!cleared) jobs.changes().observeForever(observer);
                });
          });
  }

  private void worker(Runnable action) {
    executor.execute(action);
  }

  public boolean start() {
    if (directoryNeeded || job != null && job.busy || !preparing.compareAndSet(false, true))
      return false;
    issue = "";
    destination = "";
    cancelRequested = false;
    preparation = new BackupControl(null);
    render();
    worker(
        () -> {
          try {
            BackupControl control = preparation;
            control.check();
            Uri tree = null;
            if (Build.VERSION.SDK_INT < 29) {
              String saved =
                  context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(TREE, "");
              if (!saved.isEmpty()) tree = Uri.parse(saved);
              boolean granted = false;
              if (tree != null)
                for (var grant : context.getContentResolver().getPersistedUriPermissions())
                  if (tree.equals(grant.getUri())
                      && grant.isReadPermission()
                      && grant.isWritePermission()) granted = true;
              if (!granted) {
                directoryNeeded = true;
                directoryClaimed = false;
                return;
              }
            }
            submit(tree, control);
          } catch (Exception error) {
            issue = BackupErrorCode.from(error);
          } finally {
            preparing.set(false);
            render();
          }
        });
    return true;
  }

  /** 只有本次点击可认领选目录；重建界面不会重新提交备份。 */
  public synchronized boolean claimDirectoryPicker() {
    if (!directoryNeeded || directoryClaimed) return false;
    directoryClaimed = true;
    return true;
  }

  public void directoryResult(Uri tree) {
    if (!directoryNeeded) return;
    directoryNeeded = false;
    directoryClaimed = false;
    if (tree == null) {
      issue = "CANCELLED";
      render();
      return;
    }
    if (!preparing.compareAndSet(false, true)) return;
    preparation = new BackupControl(null);
    render();
    worker(
        () -> {
          try {
            var control = preparation;
            control.check();
            if (!"content".equals(tree.getScheme())
                || !androidx.core.provider.DocumentsContractCompat.isTreeUri(tree)
                || (context.getPackageName() + ".documents").equals(tree.getAuthority()))
              throw new IOException("SAF_TREE_REQUIRED");
            context
                .getContentResolver()
                .takePersistableUriPermission(
                    tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (!context
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(TREE, tree.toString())
                .commit()) throw new IOException("SAF_PERMISSION_NOT_RETAINED");
            submit(tree, control);
          } catch (Exception error) {
            issue = BackupErrorCode.from(error);
          } finally {
            preparing.set(false);
            render();
          }
        });
  }

  private void submit(Uri tree, BackupControl control) throws IOException {
    control.check();
    if (exporter != null) {
      job = exporter.submit(tree, control);
      return;
    }
    var fs = new AndroidBackupFileSystem();
    var selection =
        selection(
            fs,
            com.deepseekharness.app.util.GuestPaths.rootfs(
                context.getFilesDir().getCanonicalFile()),
            android.os.Environment.getExternalStorageDirectory().getCanonicalFile(),
            new ConfigStore(context).getWorkdir());
    control.check();
    NativeBackupJobs owner = NativeBackupJobs.get(context);
    jobs = owner;
    synchronized (owner) {
      if (!(tree == null
          ? owner.exportToDownloads(selection, false)
          : owner.exportToDirectory(selection, tree)))
        throw new IOException("BACKUP_TASK_BUSY_OR_PENDING");
      NativeBackupJobs.State started = owner.state();
      job = started;
      if (control.isCancelled()) owner.cancelIfCurrent(started.id);
    }
  }

  static NativeDataLocations.Selection selection(
      BackupFileSystem fs, File rootfs, File publicRoot, String workdir) throws IOException {
    var selected = new NativeDataLocations.Selection();
    String guest = workdir.startsWith("/") ? workdir : "/root/" + workdir;
    if (guest.matches("^/(?:sdcard|storage|mnt)(?:/.*)?$")) return selected;
    if (guest.equals("/root/.dsh") || guest.startsWith("/root/.dsh/")) return selected;
    if (guest.matches("^/(?:root|usr|etc)?/?$")) throw new IOException("PROJECT_SCOPE_TOO_BROAD");
    var resolver = new GuestDataResolver(fs, rootfs, publicRoot, List.of(publicRoot));
    File actual = resolver.resolve(resolver.guest(guest)).file;
    if (GuestDataResolver.within(rootfs, actual)) {
      String type = fs.stat(actual).type;
      if (type.equals("DIRECTORY")) selected.guestProjects.add(guest);
      else if (!type.equals("MISSING")) throw new IOException("WORKSPACE_SOURCE_TYPE");
    }
    return selected;
  }

  public void cancel() {
    cancelRequested = true;
    directoryNeeded = false;
    directoryClaimed = false;
    var control = preparation;
    if (control != null) control.cancel();
    var selected = job;
    String selectedId = selected == null ? "" : selected.id;
    render();
    worker(
        () -> {
          var owner = jobs;
          if (exporter != null) exporter.cancel(selectedId);
          else if (owner != null) owner.cancelIfCurrent(selectedId);
        });
  }

  private void changed(NativeBackupJobs.State next) {
    if (job == null || !job.id.equals(next.id)) destination = "";
    if (next.busy) issue = "";
    else cancelRequested = false;
    job = next;
    render();
    if (!next.busy && !next.id.isEmpty())
      worker(
          () -> {
            try {
              var fs = new AndroidBackupFileSystem();
              File op =
                  HostOperationArchive.locate(
                      fs,
                      HostOperationArchive.root(context.getFilesDir().getCanonicalFile()),
                      next.id);
              File record = fs.child(op, "exported.json");
              if (fs.stat(record).type.equals("FILE")) {
                Map<String, Object> value = BackupJson.read(fs.small(record, 16384), 16384);
                String name = BackupJson.string(value, "name"),
                    uri = BackupJson.string(value, "uri");
                if (next == job && !preparing.get())
                  destination =
                      uri.startsWith("content://media/")
                          ? "Download/DSHA/" + name
                          : uri + "\n" + name;
              }
            } catch (IOException ignored) {
            }
            render();
          });
  }

  private void render() {
    NativeBackupJobs.State snapshot = job;
    boolean active = preparing.get() || snapshot != null && snapshot.busy;
    String text =
        issue.isEmpty()
            ? snapshot == null ? "" : describe(snapshot)
            : String.format(
                java.util.Locale.ROOT,
                UiText.choose(
                    "备份未完成：%s。未自动重新启动 DSH。",
                    "Backup did not finish: %s. DSH was not automatically restarted."),
                issue);
    if (preparing.get()) text = UiText.choose("正在准备一键备份…", "Preparing one-click backup…");
    if (cancelRequested && active)
      text =
          UiText.choose(
              "已请求取消，等待当前安全步骤结束。DSH 不会自动重新启动。",
              "Cancellation requested; waiting for the current safe step to finish. DSH will not restart automatically.");
    if (directoryNeeded)
      text =
          UiText.choose(
              "首次备份请选择保存目录，之后会复用此授权。", "Choose a backup folder once; its grant will be reused.");
    if (!destination.isEmpty())
      text = String.format(java.util.Locale.ROOT, "%s\n%s", text, destination);
    status.postValue(text);
    busy.postValue(active);
    chooseDirectory.postValue(directoryNeeded);
  }

  static String describe(NativeBackupJobs.State value) {
    if (value.busy)
      return String.format(
          java.util.Locale.ROOT,
          UiText.choose("正在安全备份：%s\n%s 项 · %s", "Safely backing up: %s\n%s entries · %s"),
          value.stage,
          value.entries,
          com.deepseekharness.app.util.Fmt.bytes(value.bytes));
    if (!value.error.isEmpty())
      return String.format(
          java.util.Locale.ROOT,
          UiText.choose(
              "备份未完成：%s。原数据和已有备份保留。",
              "Backup did not finish: %s. Original data and existing backups were retained."),
          value.error);
    return switch (value.result) {
      case "COMPLETE" ->
          UiText.choose(
              "备份完成，目标文件已读回校验。可手动启动 DSH。",
              "Backup complete; the destination was read back and verified. Start DSH manually when ready.");
      case "DATA_SAVED_PLUGIN_WARNINGS" ->
          UiText.choose(
              "备份已保存，但插件依赖有缺失或未确认项。请查看备份详情。",
              "Backup saved with missing or unverified plugin dependencies. Review backup details.");
      case "WRITTEN_UNVERIFIED" ->
          UiText.choose(
              "文件已写入，但目标未完成读回校验，不能视为已验证备份。",
              "The file was written, but destination readback was not verified; this is not a verified backup.");
      case "PARTIAL_RESCUE" ->
          UiText.choose(
              "仅保存部分数据，不能视为完整备份。",
              "Only part of the data was saved; this is not a complete backup.");
      case "BEST_EFFORT_RESCUE" ->
          UiText.choose(
              "尽力导出，未承诺应用级一致性。",
              "Best-effort export without an application-consistency guarantee.");
      default ->
          value.stage.equals("CANCELLED")
              ? UiText.choose(
                  "备份已取消，未自动重新启动 DSH。", "Backup cancelled; DSH was not automatically restarted.")
              : "";
    };
  }

  @Override
  protected void onCleared() {
    cleared = true;
    var owner = jobs;
    if (owner != null) owner.changes().removeObserver(observer);
  }
}
