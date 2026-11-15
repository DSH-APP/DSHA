package com.deepseekharness.app.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 运行环境部署 / 运行时更新的显示阶段模型（首次引导与部署胶囊共用）。
 *
 * <p>只把应用级任务快照的进度文案映射成固定阶段，不推导百分比，也不决定是否开始、重试任务；
 * 现有任务只上报阶段文案，没有可信的进度比例。阶段文案取自 ColdBundleExtractor、
 * ColdBundleTransaction、EnvironmentMaintenance 与 ManagedRuntimeAssets 的原文。
 * 识别不了的进度行保持上一阶段，原文照常显示。
 */
public final class DeployStages {
  private DeployStages() {}

  /** 显示用途；同一个 UPDATE 任务在首次安装时实际执行冷部署，因此由进度文案再校正。 */
  public enum Mode {
    INSTALL,
    UPDATE,
    REBUILD,
    GENERIC
  }

  public enum Phase {
    /** 没有可显示的环境任务。 */
    IDLE,
    RUNNING,
    SUCCEEDED,
    /** 失败、中断或取消；原环境与数据按维护规则保留。 */
    FAILED,
    /** 任务步骤已结束，但环境就绪检查未通过：不得自动跳转。 */
    UNREADY,
    /** 磁盘上有未完成的维护事务，须先“恢复中断维护”。 */
    PENDING
  }

  public enum Step {
    TODO,
    CURRENT,
    DONE,
    FAILED
  }

  /** 一个显示阶段：标题与它对应的进度文案前缀（中文原文；英文为空表示走译文目录）。 */
  public static final class Stage {
    public final String zh, en;
    private final String[][] matchers;

    Stage(String zh, String en, String[]... matchers) {
      this.zh = zh;
      this.en = en;
      this.matchers = matchers;
    }

    public String title() {
      return UiText.choose(zh, en);
    }

    boolean matches(String detail) {
      for (String[] matcher : matchers)
        for (String prefix : candidates(matcher)) {
          if (!prefix.isEmpty() && detail.startsWith(prefix)) return true;
        }
      return false;
    }
  }

  /** 目录文案：生产代码用 UiText.text 输出。 */
  private static String[] t(String zh) {
    return new String[] {zh, null};
  }

  /** 双语整句：生产代码用 UiText.choose 输出。 */
  private static String[] c(String zh, String en) {
    return new String[] {zh, en};
  }

  /** 当前语言、中文原文与英文三种写法都可识别；模板只取 %s 之前的固定部分。 */
  private static String[] candidates(String[] matcher) {
    String zh = matcher[0], en = matcher[1];
    String current = en == null ? UiText.text(zh) : UiText.choose(zh, en);
    return new String[] {prefix(zh), prefix(current), en == null ? "" : prefix(en)};
  }

  private static String prefix(String template) {
    if (template == null) return "";
    int argument = template.indexOf("%s");
    return (argument < 0 ? template : template.substring(0, argument)).trim();
  }

  private static final String[] PREPARING = t("正在准备…");
  private static final String[] STOPPING = t("正在停止 Web，等待运行队列完成…");
  private static final String[] EXTRACT_PREPARE = t("准备解压");
  private static final String[] EXTRACT_UBUNTU = t("解压 Ubuntu 与 Node");
  private static final String[] EXTRACT_DSH = t("解压 dsh 与内置依赖");
  private static final String[] EXTRACT_PYTHON = t("安装 Python 与 pnpm");
  private static final String[] EXTRACT_TOOLS = t("准备应用工具");
  private static final String[] EXTRACT_CURL = t("安装离线 curl、git 与证书");
  private static final String[] EXTRACT_DONE = t("解压与离线安装完成");
  private static final String[] COLD_CHECK =
      c("核对完整安装文件…", "Checking complete installation files…");
  private static final String[] COLD_PUBLISH =
      c("确认安装文件并提交…", "Confirming and publishing installation files…");
  private static final String[] COLD_PUBLISHED = c("安装文件已提交", "Installation files published");
  private static final String[] STARTUP_CHECK = c("检查启动配置…", "Checking startup configuration…");
  private static final String[] NATIVE_CHECK = c("正在核验原生模块…", "Checking native modules…");
  private static final String[] LOCK_CHECK = c("正在核验文件锁…", "Checking file locking…");
  private static final String[] UPDATE_EXTRACT = t("正在解压新版 dsh（保留现有 Ubuntu 与个人目录）…");
  private static final String[] UPDATE_PLUGINS = t("正在准备新版内置插件和界面适配…");
  private static final String[] UPDATE_SWITCH = t("新版运行时准备完成，正在保留并切换受管文件…");
  private static final String[] REBUILD_PROTECT =
      c(
          "正在由 Android 保护应用数据与个人目录；不执行旧环境…",
          "Android is protecting application data and personal folders without executing the old environment…");
  private static final String[] REBUILD_EXTRACT = t("正在解压内置环境… %s");
  private static final String[] REBUILD_RESTORE =
      c("正在恢复本机数据并逐文件校验…", "Restoring local data and verifying every file…");

  private static final List<Stage> INSTALL =
      Collections.unmodifiableList(
          Arrays.asList(
              new Stage("准备部署", "Prepare deployment", PREPARING, STOPPING, EXTRACT_PREPARE),
              new Stage("解压 Ubuntu 与 Node", "Extract Ubuntu and Node", EXTRACT_UBUNTU),
              new Stage("解压 dsh 与内置依赖", "Extract dsh and bundled dependencies", EXTRACT_DSH),
              new Stage("安装 Python 与 pnpm", "Install Python and pnpm", EXTRACT_PYTHON),
              new Stage("准备应用工具", "Prepare app tools", EXTRACT_TOOLS),
              new Stage(
                  "安装离线 curl、git 与证书", "Install offline curl, git and certificates", EXTRACT_CURL),
              new Stage(
                  "核对并提交安装文件",
                  "Verify and publish installation files",
                  EXTRACT_DONE,
                  COLD_CHECK,
                  COLD_PUBLISH,
                  COLD_PUBLISHED),
              new Stage(
                  "检查启动配置与隔离试运行",
                  "Startup check and isolated trial",
                  STARTUP_CHECK,
                  NATIVE_CHECK,
                  LOCK_CHECK)));

  private static final List<Stage> UPDATE =
      Collections.unmodifiableList(
          Arrays.asList(
              new Stage("停止 Web 与终端任务", "Stop Web and terminal tasks", PREPARING, STOPPING),
              new Stage("解压新版 dsh", "Extract the new dsh", UPDATE_EXTRACT),
              new Stage("准备内置插件与界面适配", "Prepare built-in plugins and UI adapters", UPDATE_PLUGINS),
              new Stage("保留并切换受管文件", "Retain and switch managed files", UPDATE_SWITCH),
              new Stage(
                  "隔离试运行与就绪检查", "Isolated trial and readiness check", NATIVE_CHECK, LOCK_CHECK)));

  private static final List<Stage> REBUILD =
      Collections.unmodifiableList(
          Arrays.asList(
              new Stage("停止 Web 与终端任务", "Stop Web and terminal tasks", PREPARING, STOPPING),
              new Stage("保护应用数据与个人目录", "Protect app data and personal folders", REBUILD_PROTECT),
              new Stage(
                  "解压内置环境",
                  "Extract the bundled environment",
                  REBUILD_EXTRACT,
                  EXTRACT_PREPARE,
                  EXTRACT_UBUNTU,
                  EXTRACT_DSH,
                  EXTRACT_PYTHON,
                  EXTRACT_TOOLS,
                  EXTRACT_CURL,
                  EXTRACT_DONE),
              new Stage(
                  "恢复本机数据并逐文件校验", "Restore local data and verify every file", REBUILD_RESTORE),
              new Stage(
                  "隔离试运行与就绪检查", "Isolated trial and readiness check", NATIVE_CHECK, LOCK_CHECK)));

  public static List<Stage> stages(Mode mode) {
    if (mode == null) return Collections.emptyList();
    switch (mode) {
      case INSTALL:
        return INSTALL;
      case UPDATE:
        return UPDATE;
      case REBUILD:
        return REBUILD;
      default:
        return Collections.emptyList();
    }
  }

  /** 进度行对应的阶段序号；识别不了时返回 -1，调用方应保持已到达的阶段。 */
  public static int match(Mode mode, String detail) {
    if (detail == null || detail.isEmpty()) return -1;
    List<Stage> list = stages(mode);
    // 从后往前：后段阶段可能复用前段前缀时取较晚者。
    for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).matches(detail)) return i;
    return -1;
  }

  /**
   * 用进度文案校正显示用途。一旦看到数据保护 / 数据恢复即为重建；看到新版 dsh 解压即为原位更新；
   * UPDATE 任务出现冷部署的解压阶段时说明首次安装走了冷部署。重建一经确认不再回退。
   */
  public static Mode infer(Mode current, String detail) {
    if (detail == null || detail.isEmpty() || current == Mode.GENERIC) return current;
    if (matchesAny(detail, REBUILD_PROTECT, REBUILD_RESTORE, REBUILD_EXTRACT)) return Mode.REBUILD;
    if (current == Mode.REBUILD) return current;
    if (matchesAny(detail, UPDATE_EXTRACT, UPDATE_PLUGINS, UPDATE_SWITCH)) return Mode.UPDATE;
    if (matchesAny(
        detail,
        EXTRACT_UBUNTU,
        EXTRACT_DSH,
        EXTRACT_PYTHON,
        EXTRACT_TOOLS,
        EXTRACT_CURL,
        EXTRACT_DONE,
        COLD_CHECK,
        COLD_PUBLISH,
        COLD_PUBLISHED)) return Mode.INSTALL;
    return current;
  }

  private static boolean matchesAny(String detail, String[]... matchers) {
    for (String[] matcher : matchers)
      for (String prefix : candidates(matcher))
        if (!prefix.isEmpty() && detail.startsWith(prefix)) return true;
    return false;
  }

  /** 应用级任务是否属于运行环境维护（胶囊只显示这些；备份、恢复备份等沿用原有入口）。 */
  public static boolean environmentKind(String kind) {
    String id = BackupTaskKinds.normalize(kind);
    return BackupTaskKinds.UPDATE.equals(id)
        || BackupTaskKinds.REBUILD.equals(id)
        || BackupTaskKinds.RECOVER.equals(id)
        || BackupTaskKinds.ROLLBACK.equals(id);
  }

  /** 任务种类给出的初始显示用途；UPDATE 是否实际为首次部署由调用方按环境是否已安装给出。 */
  public static Mode modeFor(String kind, boolean environmentInstalled) {
    String id = BackupTaskKinds.normalize(kind);
    if (BackupTaskKinds.REBUILD.equals(id))
      return environmentInstalled ? Mode.REBUILD : Mode.INSTALL;
    if (BackupTaskKinds.UPDATE.equals(id)) return environmentInstalled ? Mode.UPDATE : Mode.INSTALL;
    return Mode.GENERIC;
  }

  /**
   * 阶段判定。busy 必须来自 BackupTask.maintenanceBusy() 与快照本身；pending 来自磁盘维护门禁；
   * ready 来自就绪检查。成功文案不是就绪证明：SUCCEEDED 但未就绪时为 UNREADY。
   */
  public static Phase phase(
      BackupTaskState.Status status, boolean busy, boolean pending, boolean ready) {
    if (busy) return Phase.RUNNING;
    if (pending) return Phase.PENDING;
    if (status == null) return Phase.IDLE;
    switch (status) {
      case SUCCEEDED:
        return ready ? Phase.SUCCEEDED : Phase.UNREADY;
      case FAILED:
      case INTERRUPTED:
      case CANCELLED:
        return Phase.FAILED;
      case RUNNING:
      case PREVIEW:
        // 快照仍写着运行，但应用级维护锁已经释放：进程被回收后的旧记录，按中断处理。
        return Phase.FAILED;
      default:
        return Phase.IDLE;
    }
  }

  /** 一次渲染所需的全部显示数据；不含任何操作入口。 */
  public static final class View {
    public final Mode mode;
    public final Phase phase;

    /** 当前（或失败时最后到达的）阶段序号；没有阶段表时为 -1。 */
    public final int current;

    public final List<Stage> stages;
    public final Step[] steps;

    /** 原始进度 / 结果文案（已在任务层脱敏），显示前由调用方按需渲染。 */
    public final String detail;

    public final String lastStage;
    public final long taskId;

    View(Mode mode, Phase phase, int current, String detail, String lastStage, long taskId) {
      this.mode = mode == null ? Mode.GENERIC : mode;
      this.phase = phase == null ? Phase.IDLE : phase;
      this.stages = stages(this.mode);
      this.current = stages.isEmpty() ? -1 : Math.max(0, Math.min(current, stages.size() - 1));
      this.detail = detail == null ? "" : detail;
      this.lastStage = lastStage == null ? "" : lastStage;
      this.taskId = taskId;
      steps = new Step[stages.size()];
      for (int i = 0; i < steps.length; i++) steps[i] = step(this.phase, this.current, i);
    }

    public boolean visible() {
      return phase != Phase.IDLE;
    }

    public int total() {
      return stages.size();
    }

    /** 已完成的阶段数（无障碍进度值）。 */
    public int completed() {
      int count = 0;
      for (Step step : steps) if (step == Step.DONE) count++;
      return count;
    }

    /** 胶囊主标签。 */
    public String label() {
      return DeployStages.label(mode, phase);
    }

    /** 胶囊副标签：运行中为当前阶段短名，其余状态为空。 */
    public String sub() {
      if (phase == Phase.RUNNING && current >= 0) return stages.get(current).title();
      return "";
    }

    /** “第 n / 共 m 步”的数字部分；不翻译。 */
    public String counter() {
      if (phase != Phase.RUNNING || current < 0) return "";
      return (current + 1) + " / " + stages.size();
    }

    public String title() {
      return DeployStages.title(mode);
    }
  }

  static Step step(Phase phase, int current, int index) {
    switch (phase) {
      case SUCCEEDED:
      case UNREADY:
        return Step.DONE;
      case RUNNING:
        return index < current ? Step.DONE : index == current ? Step.CURRENT : Step.TODO;
      case FAILED:
      case PENDING:
        return index < current ? Step.DONE : index == current ? Step.FAILED : Step.TODO;
      default:
        return Step.TODO;
    }
  }

  /** reached 为调用方记住的已到达阶段（单调不减）；detail 识别出更晚阶段时以其为准。 */
  public static View view(
      Mode mode, Phase phase, int reached, String detail, String lastStage, long taskId) {
    Mode resolved = infer(infer(mode, lastStage), detail);
    int matched = Math.max(match(resolved, detail), match(resolved, lastStage));
    return new View(resolved, phase, Math.max(reached, matched), detail, lastStage, taskId);
  }

  public static View idle() {
    return new View(Mode.GENERIC, Phase.IDLE, -1, "", "", 0);
  }

  public static String title(Mode mode) {
    switch (mode == null ? Mode.GENERIC : mode) {
      case INSTALL:
        return UiText.choose("运行环境部署", "Runtime deployment");
      case UPDATE:
        return UiText.choose("运行时更新", "Runtime update");
      case REBUILD:
        return UiText.choose("运行环境重建", "Runtime rebuild");
      default:
        return UiText.choose("环境维护", "Environment maintenance");
    }
  }

  public static String label(Mode mode, Phase phase) {
    Mode m = mode == null ? Mode.GENERIC : mode;
    switch (phase == null ? Phase.IDLE : phase) {
      case RUNNING:
        switch (m) {
          case INSTALL:
            return UiText.choose("部署中", "Deploying");
          case UPDATE:
            return UiText.choose("更新中", "Updating");
          case REBUILD:
            return UiText.choose("重建中", "Rebuilding");
          default:
            return UiText.choose("维护中", "Maintaining");
        }
      case SUCCEEDED:
        switch (m) {
          case INSTALL:
            return UiText.choose("环境已就绪", "Environment ready");
          case UPDATE:
            return UiText.choose("运行时已更新", "Runtime updated");
          case REBUILD:
            return UiText.choose("环境已重建", "Environment rebuilt");
          default:
            return UiText.choose("维护已完成", "Maintenance done");
        }
      case FAILED:
        switch (m) {
          case INSTALL:
            return UiText.choose("部署未完成", "Deployment incomplete");
          case UPDATE:
            return UiText.choose("更新未完成", "Update incomplete");
          case REBUILD:
            return UiText.choose("重建未完成", "Rebuild incomplete");
          default:
            return UiText.choose("维护未完成", "Maintenance incomplete");
        }
      case UNREADY:
        return UiText.choose("环境检查未通过", "Readiness check failed");
      case PENDING:
        return UiText.choose("上次维护未完成", "Previous maintenance incomplete");
      default:
        return "";
    }
  }
}
