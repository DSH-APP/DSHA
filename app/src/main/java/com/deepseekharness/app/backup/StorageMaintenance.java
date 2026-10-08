package com.deepseekharness.app.backup;

import android.content.Context;
import com.deepseekharness.app.util.RegenerableCachePaths;
import com.deepseekharness.app.util.ScratchPaths;
import java.io.*;
import java.util.*;

/**
 * 只统计实际目录，不跟随链接；清理范围是可再生缓存、经过原规则核验的旧受管副本，以及 AI/脚本的临时文件目录。
 *
 * <p>删除边界是闭集：{@link RegenerableCachePaths} 的字面量清单 + {@link ScratchPaths} 声明的
 * 临时目录与「本 App 命名的临时文件」。任何目标在删除前都必须先解析绝对路径并逐级核验父链；
 * 核验失败只跳过并记录，不改用别的路径去猜。
 */
public final class StorageMaintenance {
  private StorageMaintenance() {}

  private static final List<String> CACHE =
      List.of("WebView", "GPUCache", "Code Cache", "GrShaderCache", "DawnCache");
  private static final List<String> HISTORICAL =
      List.of(
          "runtime-startup",
          "functional-audit",
          "adb-flow-audit",
          "install-audit",
          "layout-audit",
          "plugin-sort-audit",
          "log-panel-audit");

  public static final class Size {
    public long bytes;
    public int unreadable;
  }

  /** 逐条清理结果：删了什么、本来就没有什么、跳过什么、失败什么都留档。 */
  public static final class Report {
    public final long bytes;
    public final int deleted, absent, skipped, failed;
    public final String detail;

    Report(long bytes, int deleted, int absent, int skipped, int failed, String detail) {
      this.bytes = bytes;
      this.deleted = deleted;
      this.absent = absent;
      this.skipped = skipped;
      this.failed = failed;
      this.detail = detail;
    }
  }

  private static final class Ledger implements ScratchCleanup.Sink {
    long bytes;
    int deleted, absent, skipped, failed;
    final List<String> notes = new ArrayList<>();

    @Override
    public void record(String action, String target, long freed, String reason) {
      switch (action) {
        case "deleted" -> deleted(target, freed);
        case "absent" -> absent(target);
        case "failed" -> failed(target, reason);
        default -> skipped(target, reason);
      }
    }

    void absent(String target) {
      absent++;
      notes.add("absent:" + target);
    }

    void deleted(String target, long freed) {
      deleted++;
      bytes += freed;
      notes.add("deleted:" + target + ":" + freed);
    }

    void skipped(String target, String reason) {
      skipped++;
      notes.add("skipped:" + target + ":" + reason);
    }

    void failed(String target, String reason) {
      failed++;
      notes.add("failed:" + target + ":" + reason);
    }

    String detail() {
      return "deleted="
          + deleted
          + " absent="
          + absent
          + " skipped="
          + skipped
          + " failed="
          + failed
          + " bytes="
          + bytes
          + " "
          + String.join(" ", notes);
    }
  }

  public static Map<String, Size> inspect(Context context) throws IOException {
    var fs = new AndroidBackupFileSystem();
    File files = context.getFilesDir().getCanonicalFile();
    Map<String, Size> result = new LinkedHashMap<>();
    for (String name :
        List.of(
            "linux",
            "host-runtime-operations",
            "host-environment-operations",
            "runtime-updates",
            "host-backup-operations",
            "mozilla",
            "user-data-v5")) result.put(name, size(fs, new File(files, name)));
    File cache = context.getCacheDir().getCanonicalFile();
    Size ordinaryCache = size(fs, cache);
    result.put("cache", ordinaryCache);
    Size legacy = new Size();
    for (String name : HISTORICAL)
      for (File parent : List.of(files, cache)) {
        Size value = size(fs, new File(parent, name));
        legacy.bytes += value.bytes;
        legacy.unreadable += value.unreadable;
        if (parent.equals(cache)) {
          ordinaryCache.bytes = Math.max(0, ordinaryCache.bytes - value.bytes);
          ordinaryCache.unreadable = Math.max(0, ordinaryCache.unreadable - value.unreadable);
        }
      }
    result.put("historical-audit-fixtures", legacy);
    Size linux = result.get("linux");
    Size scratch = scratchSize(fs, context);
    if (scratch != null) {
      result.put("scratch", scratch);
      // 临时目录在 linux 根子树里，减掉一次避免同一批字节被统计两遍。
      linux.bytes = Math.max(0, linux.bytes - scratch.bytes);
    }
    return result;
  }

  /** 环境未就绪时统计不到临时目录：保持其余分类可用，不把维护态当故障。 */
  private static Size scratchSize(BackupFileSystem fs, Context context) {
    try {
      File rootfs =
          com.deepseekharness.app.core.HarnessController.get(context)
              .proot()
              .getRootfsDir()
              .getCanonicalFile();
      return size(fs, ScratchPaths.resolve(rootfs, ScratchPaths.ROOTFS_DIRECTORY));
    } catch (Exception unavailable) {
      return null;
    }
  }

  private static Size size(BackupFileSystem fs, File file) {
    Size total = new Size();
    count(fs, file, 0, total);
    return total;
  }

  private static void count(BackupFileSystem fs, File file, int depth, Size total) {
    try {
      if (depth > 128) throw new IOException("STORAGE_DEPTH");
      var node = fs.stat(file);
      if (node.type.equals("FILE")) {
        total.bytes = Math.addExact(total.bytes, node.size);
        return;
      }
      if (!node.type.equals("DIRECTORY")) return;
      // 统计只取 lstat 元数据，不读取文件正文、不跟随链接；不重复为每级打开锚点描述符。
      File[] children = file.listFiles();
      if (children == null) {
        total.unreadable++;
        return;
      }
      for (File child : children) count(fs, child, depth + 1, total);
    } catch (IOException | ArithmeticException unavailable) {
      total.unreadable++;
    }
  }

  private static long removeCache(BackupFileSystem fs, File root, String relative, Ledger ledger)
      throws IOException {
    if (ScratchPaths.declaredDirectory(relative)) {
      // 声明目录：删除前解析绝对路径并逐级核验父链，链接或越界直接拒绝。
      ScratchPaths.resolve(root, relative);
    } else if (!RegenerableCachePaths.safeRelative(relative)) {
      throw new IOException("CACHE_PATH_FORBIDDEN");
    }
    File at = root;
    String[] parts = relative.split("/");
    for (int i = 0; i < parts.length - 1; i++) {
      at = new File(at, parts[i]);
      var node = fs.stat(at);
      if (node.type.equals("MISSING")) {
        ledger.absent(relative);
        return 0;
      }
      if (!node.type.equals("DIRECTORY")) throw new IOException("CACHE_PARENT_TYPE");
    }
    File target = new File(root, relative);
    if (fs.stat(target).type.equals("MISSING")) {
      ledger.absent(relative);
      return 0;
    }
    long before = size(fs, target).bytes;
    fs.removeOwned(root, relative);
    long freed = Math.max(0, before - size(fs, target).bytes);
    ledger.deleted(relative, freed);
    return freed;
  }

  /**
   * 执行一次可再生数据清理，并把「删了什么/本来就没有/跳过什么/失败什么」交给宿主诊断端口。
   *
   * <p>调用方拿到的 {@link Report#detail} 与传给诊断端口的文本是同一份，便于把 UI 状态和日志对齐；
   * backup 层不认识具体诊断实现（宿主端口由调用方注入），因此这里只接受一个回调。
   */
  public static Report clean(
      Context context, java.util.function.BiConsumer<String, String> diagnostics) throws Exception {
    var controller = com.deepseekharness.app.core.HarnessController.get(context);
    // 清理会删除可再生缓存并轮换已核验副本，必须使用数据维护入口：它会按出生身份
    // 安全停止 Web/终端并取得 RuntimeTasks 屏障，避免用户因为忘记手动停止而反复报错。
    return com.deepseekharness.app.core.MaintenanceCoordinator.exclusive(
        controller,
        () -> {
          if (NativeBackupJobs.get(context).state().busy || !AutomaticBackups.idleForOwner(context))
            throw new IOException("STOP_DSH_AND_TERMINALS_FIRST");
          var fs = new AndroidBackupFileSystem();
          var ledger = new Ledger();
          File cache = context.getCacheDir().getCanonicalFile();
          long before = size(fs, cache).bytes;
          for (String name : CACHE)
            try {
              fs.removeOwned(cache, name);
            } catch (IOException failure) {
              ledger.failed("app-cache/" + name, String.valueOf(failure.getMessage()));
            }
          long regenerated = 0;
          File rootfs = controller.proot().getRootfsDir().getCanonicalFile();
          for (String path : RegenerableCachePaths.rootfs())
            // 临时文件目录由 ScratchCleanup 按自己的白名单与核验规则处理，不在这里二次删除。
            if (!ScratchPaths.declaredDirectory(path))
              try {
                regenerated += removeCache(fs, rootfs, path, ledger);
              } catch (IOException failure) {
                ledger.skipped(path, String.valueOf(failure.getMessage()));
              }
          // AI 与脚本的临时文件目录 + 本 App 自己命名的 guest 临时文件：显式白名单，删除前逐个核验。
          regenerated += ScratchCleanup.run(fs, rootfs, ledger);
          // WebView 的 Cookie、Local Storage 和 IndexedDB 位于其它目录；这里只删可重建的渲染/HTTP 缓存。
          File data = context.getFilesDir().getCanonicalFile().getParentFile();
          for (String path : RegenerableCachePaths.webViewData())
            try {
              regenerated += removeCache(fs, data, path, ledger);
            } catch (IOException failure) {
              ledger.skipped(path, String.valueOf(failure.getMessage()));
            }
          File files = context.getFilesDir().getCanonicalFile();
          long old = size(fs, new File(files, "host-runtime-operations")).bytes;
          ManagedRuntimeTransaction.trimOlder(
              fs,
              files,
              controller.proot().installedRuntimeDescriptor(),
              controller.proot().runtimeHealth());
          long environments = size(fs, new File(files, EnvironmentRebuildTransaction.HOME)).bytes;
          EnvironmentRebuildTransaction.cleanupCompleted(fs, files, new BackupControl(null));
          // 可诊断优先：删了什么/跳过什么/失败什么交给宿主诊断端口留档。
          if (diagnostics != null) diagnostics.accept("STORAGE_CLEANUP", ledger.detail());
          return new Report(
              before
                  - size(fs, cache).bytes
                  + regenerated
                  + old
                  - size(fs, new File(files, "host-runtime-operations")).bytes
                  + environments
                  - size(fs, new File(files, EnvironmentRebuildTransaction.HOME)).bytes,
              ledger.deleted,
              ledger.absent,
              ledger.skipped,
              ledger.failed,
              ledger.detail());
        });
  }
}
