package com.deepseekharness.app.core;

import com.deepseekharness.app.backup.AndroidBackupFileSystem;
import com.deepseekharness.app.backup.PluginInstallJournals;
import com.deepseekharness.app.backup.PluginJournalQuarantine;
import com.deepseekharness.app.backup.UserDataLayout;
import com.deepseekharness.app.util.UiText;
import java.io.File;
import java.io.IOException;
import java.util.List;

/** 宿主侧编排插件日志隔离：计划只读，执行必须在维护屏障内且摘要与用户确认的一致。 */
public final class PluginJournalIsolation {
  private PluginJournalIsolation() {}

  /** 用户确认前展示的计划；摘要为空表示无可隔离记录。 */
  public static final class Offer {
    public final List<PluginInstallJournals.Problem> problems;
    public final String digest;

    Offer(List<PluginInstallJournals.Problem> problems) {
      this.problems = problems;
      this.digest = PluginJournalQuarantine.digest(problems);
    }
  }

  /** 只读计算；数据根本身无法确定（如 DATA_LOCATION_*）时抛出，由调用方按诊断处理。 */
  public static Offer offer(File files) throws IOException {
    var fs = new AndroidBackupFileSystem();
    File home = new UserDataLayout(fs, files.getCanonicalFile()).current();
    return new Offer(PluginInstallJournals.problems(fs, home));
  }

  /** 后台安全读取：无可隔离记录或无法计算时返回 null，不抛出。 */
  public static Offer offerOrNull(File files) {
    try {
      Offer offer = offer(files);
      return offer.problems.isEmpty() ? null : offer;
    } catch (IOException | RuntimeException unavailable) {
      return null;
    }
  }

  public static String isolate(HarnessController controller, String expectedDigest)
      throws IOException {
    if (!MaintenanceCoordinator.isOwner() || RuntimeTasks.hasOtherTasks())
      throw new IOException("PLUGIN_QUARANTINE_REQUIRES_MAINTENANCE");
    File files = controller.context().getFilesDir().getCanonicalFile();
    var fs = new AndroidBackupFileSystem();
    File home = new UserDataLayout(fs, files).current();
    var result = PluginJournalQuarantine.apply(fs, home, expectedDigest);
    PluginRepository.get(controller.context()).invalidateInstalledState();
    return UiText.format(
        "已把 %s 条无法读取的插件日志原样移入隔离区，未删除任何文件：%s。请再次执行「恢复中断维护」或重新启动。",
        result.moved, PluginJournalQuarantine.DIRECTORY + "/" + result.batch.getName());
  }
}
