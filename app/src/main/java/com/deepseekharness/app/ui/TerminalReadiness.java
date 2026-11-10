package com.deepseekharness.app.ui;

import android.content.Context;
import android.os.SystemClock;
import com.deepseekharness.app.core.BackupTask;
import com.deepseekharness.app.util.BackupTaskState;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.ReadinessCache;
import java.io.File;

/**
 * 终端热路径（逐码点输入、切换 / 关闭会话）的就绪结论。
 *
 * <p>{@link ProotBootstrap#isEnvironmentReady()} 每次都解析并哈希运行时描述符；粘贴长文本时每个码点都会走一遍，
 * 主线程累计超过 5 秒即 ANR。这里只复用最近一次全量结论：数据维护进行中一律重新全量检查，
 * 维护任务代次 / 状态、就绪依赖文件的 mtime/size 任一变化或超过有效期也会重算，不放宽门禁本身。
 * 安装、维护、启动门禁仍直接调用全量检查。
 */
final class TerminalReadiness {
  static final long TTL_MS = 10_000;

  private final ProotBootstrap proot;
  private final BackupTask task;
  private final File files;
  private final ReadinessCache cache;

  TerminalReadiness(Context context, ProotBootstrap proot) {
    this.proot = proot;
    this.task = BackupTask.get(context);
    this.files = context.getApplicationContext().getFilesDir();
    cache =
        new ReadinessCache(
            proot::isEnvironmentReady,
            this::fingerprint,
            // 维护进行中返回奇数代次：缓存不可用，每次都全量检查。
            () -> task.maintenanceBusy() ? 1 : 0,
            SystemClock::elapsedRealtime,
            TTL_MS);
  }

  boolean ready() {
    return cache.cached();
  }

  void invalidate() {
    cache.invalidate();
  }

  /** 只 stat，不读内容、不算哈希；任务快照为内存状态。 */
  private String fingerprint() {
    BackupTaskState.Snapshot snapshot = task.snapshot();
    File rootfs = proot.getRootfsDir();
    StringBuilder key = new StringBuilder(192);
    key.append(snapshot.id).append('/').append(snapshot.status).append(';');
    for (File file :
        new File[] {
          new File(files, "linux/.offline-extracted"),
          new File(files, "linux/.runtime-descriptor.json"),
          new File(files, "runtime-health"),
          new File(rootfs, "usr/bin/bash"),
          new File(rootfs, "usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js")
        }) {
      // 缺失文件 lastModified 为 0，与存在时可区分。
      key.append(file.lastModified()).append(':').append(file.length()).append(';');
    }
    return key.toString();
  }
}
