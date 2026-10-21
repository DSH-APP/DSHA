package com.deepseekharness.app.runtime;

import android.content.Context;
import com.deepseekharness.app.util.Compat;
import com.deepseekharness.app.util.InstallProbe;
import com.deepseekharness.app.util.InstallProcess;
import com.deepseekharness.app.util.InstallTask;
import com.deepseekharness.app.util.InstallStage;
import com.deepseekharness.app.util.InstallFailure;
import static com.deepseekharness.app.util.InstallStage.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** 安装六步编排：先一次探测，仅在明确请求修复且检查失败时写入，完成后复验。 */
public final class InstallPipeline {
  /** 操作边界可替换，独立测试能验证不发生多余修复、清环境或取消后的写入。 */
  interface Environment {
    boolean ready();

    boolean canExtractFresh();

    void extract(Consumer<Long> progress) throws Exception;

    void repairTools(InstallProbe.Results checked, InstallTask task) throws Exception;

    boolean repairPnpm();

    void repairGroups();

    void repairPatches() throws Exception;

    int execute(
        String script, long timeout, boolean cancellable, InstallTask task, Consumer<String> output)
        throws Exception;
  }

  private final Environment environment;

  public InstallPipeline(Context context) {
    this(new LocalEnvironment(context));
  }

  InstallPipeline(Environment environment) {
    this.environment = environment;
  }

  public void run(InstallTask task, boolean repair, int selected) throws Exception {
    task.stage(
        ENVIRONMENT_ID,
        com.deepseekharness.app.util.UiText.text("检查第 1 步：Linux 解压标记、版本与 bash"),
        true);
    boolean ready = environment.ready();
    if (!ready && repair && (selected == 0 || selected == ENVIRONMENT_ID)) {
      // 版本迁移会删除旧目录；只准首次空环境进入，绝不重置已有数据。
      if (environment.canExtractFresh()) {
        task.stage(
            ENVIRONMENT_ID,
            com.deepseekharness.app.util.UiText.text("安装第 1 步：首次解压离线环境（当前解压完成后可停止）"),
            false);
        long[] last = {0};
        environment.extract(
            done -> {
              if (done - last[0] >= 8 * 1024 * 1024) {
                task.append(
                    com.deepseekharness.app.util.UiText.format(
                        "已读取离线包 %s MiB", done / (1024 * 1024)));
                last[0] = done;
              }
            });
        ready = environment.ready();
      }
    }
    task.result(
        ENVIRONMENT_ID,
        ready,
        ready
            ? com.deepseekharness.app.util.UiText.text("标记、版本和 bash 在位")
            : com.deepseekharness.app.util.UiText.text("环境未就绪；保留现有文件，不自动重解压或清除，请先备份后恢复环境"));
    task.checkCancelled();
    if (!ready) {
      for (int step = TOOLS_ID; step <= PATCHES_ID; step++)
        if (selected == 0 || selected == step)
          task.result(
              step, false, com.deepseekharness.app.util.UiText.text("无法验证：Linux 环境未就绪，请先处理第 1 项"));
      task.finish(
          InstallTask.Outcome.FAILED,
          com.deepseekharness.app.util.UiText.text("检查完成：Linux 环境未就绪，其余组件无法验证；现有文件已保留"));
      return;
    }
    if (selected == ENVIRONMENT_ID) {
      task.finish(
          InstallTask.Outcome.SUCCEEDED, com.deepseekharness.app.util.UiText.text("第 1 步检查通过"));
      return;
    }
    InstallProbe.Results checked = probe(task, selected);
    boolean failed = false;
    int repaired = 0;
    for (InstallStage stage : InstallStage.ordered()) {
      int step = stage.id();
      if (stage == InstallStage.ENVIRONMENT) continue;
      if (selected != 0 && selected != step) continue;
      task.checkCancelled();
      if (checked.ok(step)) continue;
      if (!repair) {
        failed = true;
        continue;
      }
      try {
        switch (step) {
          case TOOLS_ID:
            environment.repairTools(checked, task);
            break;
          case PNPM_ID:
            task.stage(
                PNPM_ID, com.deepseekharness.app.util.UiText.text("修复第 4 步：补齐离线 pnpm"), false);
            if (!environment.repairPnpm())
              throw new IOException(
                  com.deepseekharness.app.util.UiText.text("离线 pnpm 修复失败，请检查可用空间"));
            task.append(com.deepseekharness.app.util.UiText.text("离线 pnpm 文件已核对，开始复验"));
            break;
          case PATCHES_ID:
            if (!checked.ok("dns") || !checked.ok("session") || !checked.ok("settings")) {
              task.stage(
                  PATCHES_ID,
                  com.deepseekharness.app.util.UiText.text("修复第 6 步：DNS、会话写入与局域网设置补丁"),
                  false);
              environment.repairPatches();
            }
            task.checkCancelled();
            if (!checked.ok("groups")) {
              task.stage(
                  PATCHES_ID,
                  com.deepseekharness.app.util.UiText.text("修复第 6 步：补齐 Android 用户组"),
                  false);
              environment.repairGroups();
              task.append(com.deepseekharness.app.util.UiText.text("Android 用户组已核对，开始复验"));
            }
            break;
          default:
            task.result(
                step,
                false,
                com.deepseekharness.app.util.UiText.text("内置组件无法运行；本页不覆盖用户升级的 Node/dsh，请备份后修复该组件"));
            failed = true;
            continue;
        }
        task.checkCancelled();
        InstallProbe.Results verified = probe(task, step);
        if (!verified.ok(step)) failed = true;
        else repaired++;
      } catch (InstallTask.Cancelled cancelled) {
        throw cancelled;
      } catch (Exception error) {
        if (InstallProcess.cleanupFailure(error) != null) throw error;
        if (isCancellation(error)) throw new InstallTask.Cancelled();
        task.result(step, false, InstallFailure.from(stage, error).display());
        failed = true;
      }
    }
    task.checkCancelled();
    task.finish(
        failed ? InstallTask.Outcome.FAILED : InstallTask.Outcome.SUCCEEDED,
        failed
            ? com.deepseekharness.app.util.UiText.text("检查完成，仍有步骤未通过；失败项与详细输出已保留")
            : repaired > 0
                ? com.deepseekharness.app.util.UiText.format("已按需修复 %s 步，并通过复验", repaired)
                : com.deepseekharness.app.util.UiText.text("检查全部通过，无需修复"));
  }

  private InstallProbe.Results probe(InstallTask task, int selected) throws Exception {
    List<InstallProbe.Check> checks = InstallProbe.checks(selected);
    InstallProbe.Results result = new InstallProbe.Results(checks);
    task.stage(
        selected == 0 ? TOOLS_ID : selected,
        com.deepseekharness.app.util.UiText.text("启动一次容器探测，逐项检查实际运行结果"),
        true);
    InstallProbe.Check[] active = {null};
    int code;
    try {
      code =
          environment.execute(
              InstallProbe.script(checks),
              Math.max(30_000, checks.size() * 22_000L),
              true,
              task,
              line -> {
                InstallProbe.Check begin = result.beginning(line);
                if (begin != null) {
                  if (active[0] != null && active[0].step != begin.step)
                    task.result(
                        active[0].step, result.ok(active[0].step), result.detail(active[0].step));
                  active[0] = begin;
                  task.stage(
                      begin.step,
                      com.deepseekharness.app.util.UiText.format(
                          "检查第 %s 步：%s", begin.step, begin.label),
                      true);
                } else if (result.accept(line)) {
                  if (active[0] != null)
                    task.append(
                        result.ok(active[0].key)
                            ? com.deepseekharness.app.util.UiText.format("%s：通过", active[0].label)
                            : com.deepseekharness.app.util.UiText.format(
                                "%s：失败（退出码 %s）",
                                active[0].label, line.substring(line.lastIndexOf(':') + 1)));
                } else task.append(line);
              });
    } finally {
      // 进程提前退出、没有 BEGIN 或漏结果时，也要为每个选中组件发布结论。
      // 取消保留已完成结果，剩余步骤由 finish 标为未完成。
      if (!task.cancellationRequested())
        for (int step = TOOLS_ID; step <= PATCHES_ID; step++) {
          if (selected == 0 || selected == step)
            task.result(step, result.ok(step), result.detail(step));
        }
    }
    if (code != 0)
      throw new IOException(com.deepseekharness.app.util.UiText.format("容器探测退出码 %s，检查未完整执行", code));
    return result;
  }

  public static boolean isCancellation(Throwable error) {
    for (Throwable current = error; current != null; current = current.getCause())
      if (current instanceof InstallTask.Cancelled) return true;
    return false;
  }

  private static final class LocalEnvironment implements Environment {
    private final Context context;
    private final ProotBootstrap proot;
    private final BasicToolsInstaller tools;

    LocalEnvironment(Context context) {
      this.context = context;
      proot = new ProotBootstrap(context);
      tools = new BasicToolsInstaller(context, proot);
    }

    @Override
    public boolean ready() {
      return proot.isEnvironmentReady();
    }

    @Override
    public boolean canExtractFresh() {
      File root = proot.getRootfsDir();
      File[] contents = root.listFiles();
      return (!root.exists() || contents != null && contents.length == 0)
          && proot.hasOfflineBundle();
    }

    @Override
    public void extract(Consumer<Long> progress) throws Exception {
      proot.extractOfflineBundle((done, total) -> progress.accept(done));
    }

    @Override
    public void repairTools(InstallProbe.Results checked, InstallTask task) throws Exception {
      tools.repair(checked, task);
    }

    @Override
    public boolean repairPnpm() {
      return proot.ensureBundledPnpm();
    }

    @Override
    public void repairGroups() {
      proot.ensureAndroidGroups();
    }

    @Override
    public void repairPatches() throws IOException {
      RuntimeTools.invalidate();
      RuntimeTools.prepare(context, proot.getRootfsDir());
    }

    @Override
    public int execute(
        String script, long timeout, boolean cancellable, InstallTask task, Consumer<String> output)
        throws Exception {
      return InstallProcess.read(
          proot.execRootfsForInstall(script),
          timeout,
          cancellable,
          task::cancellationRequested,
          output,
          Compat::destroy);
    }
  }
}
