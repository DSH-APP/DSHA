package com.deepseekharness.app.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 启动恢复页的状态语义（纯逻辑）：一种颜色只表达一种结论，每次启动只给一个结论。
 *
 * <p>good=已成功/正常运行；info=正在进行；idle=未运行但不是故障；warn=需要处理但不是失败；bad=明确失败/损坏。
 * 失败原因在写入时已按当时界面语言渲染，因此分类按全部受支持语言的同一模板匹配，并以中文日志中的
 * 固定前缀（插件、端口、配置检查行）为主证据；无法确认的原因归入「未分类」，原文保留，不猜测。
 */
public final class StartupRecoveryState {
  private StartupRecoveryState() {}

  public enum Tone {
    GOOD,
    INFO,
    IDLE,
    WARN,
    BAD
  }

  /** 页面结论；code 是稳定错误码，便于日志与反馈引用。 */
  public enum Kind {
    IDLE(Tone.IDLE, ""),
    RUNNING(Tone.GOOD, ""),
    STARTING(Tone.INFO, ""),
    STOPPED(Tone.IDLE, ""),
    PORT(Tone.BAD, "WEB_PORT_IN_USE"),
    PLUGIN(Tone.BAD, "PLUGIN_LOAD_FAILED"),
    REGISTER(Tone.BAD, "PLUGIN_REGISTER_FAILED"),
    PAGE(Tone.BAD, "WEB_PAGE_FATAL"),
    CONFIG(Tone.BAD, "CONFIG_CHECK_FAILED"),
    EXIT(Tone.BAD, "PROCESS_EXITED"),
    CREDENTIAL(Tone.BAD, "API_KEY_UNREADABLE"),
    HEALTH(Tone.BAD, "HEALTH_CHECK_TIMEOUT"),
    FAILED(Tone.BAD, "STARTUP_FAILED"),
    ENVIRONMENT(Tone.BAD, "RUNTIME_BROKEN"),
    INTERRUPTED(Tone.WARN, "STARTUP_INTERRUPTED"),
    PENDING(Tone.WARN, "REPAIR_PENDING"),
    BUSY(Tone.WARN, "ENV_TASK_BUSY");

    public final Tone tone;
    public final String code;

    Kind(Tone tone, String code) {
      this.tone = tone;
      this.code = code;
    }
  }

  /** 一次失败的分类结果；subject 为插件名、端口或运行时名，code 为进程退出码；均可能为空。 */
  public static final class Failure {
    public final Kind kind;
    public final String subject, code, raw;

    Failure(Kind kind, String subject, String code, String raw) {
      this.kind = kind;
      this.subject = subject == null ? "" : subject;
      this.code = code == null ? "" : code;
      this.raw = raw == null ? "" : raw;
    }
  }

  private static final Pattern PORT_LINE = Pattern.compile("Web 端口被占用：([0-9]{1,5})");
  private static final Pattern PLUGIN_LINE = Pattern.compile("插件加载异常：([^\\n]{1,180})");
  private static final Pattern CONFIG_LINE = Pattern.compile("启动配置检查失败：");

  private static final String EXIT_BEFORE = "%s 进程退出，退出码 %s（鉴权前）";
  private static final String EXIT_AFTER = "%s 进程退出，退出码 %s（鉴权后）";
  private static final String HEALTH_REASON = "服务连续三次健康检查未响应";
  private static final String[] CREDENTIAL_REASONS = {
    "API Key 暂时无法读取，请解锁设备后重试。原凭据已保留。",
    "保存 API Key 的设备密钥已失效，请重新输入。原记录未自动删除。",
    "当前设备无法读取已保存的 API Key，请重试或重新输入。原记录已保留。"
  };

  /**
   * 分类失败原因。reason 为持久化的原因（任意界面语言）；zhLog 为同一次启动的中文时间线。
   * backendReady 表示已拿到官方鉴权链接，用于区分插件加载失败与网页初始化失败。
   */
  public static Failure classify(String reason, String zhLog, boolean backendReady) {
    String why = reason == null ? "" : reason.trim(), log = zhLog == null ? "" : zhLog;
    int port = WebPortPolicy.conflictPort(why);
    Matcher portLine = PORT_LINE.matcher(why + "\n" + log);
    if (port > 0 || portLine.find())
      return new Failure(
          Kind.PORT, port > 0 ? Integer.toString(port) : portLine.group(1), Kind.PORT.code, why);
    String[] exit = exit(why);
    if (exit != null) return new Failure(Kind.EXIT, exit[0], exit[1], why);
    if (anyLanguage(why, HEALTH_REASON)) return new Failure(Kind.HEALTH, "", "", why);
    for (String credential : CREDENTIAL_REASONS)
      if (anyLanguage(why, credential)) return new Failure(Kind.CREDENTIAL, "", "", why);
    Failure register = registerFailure(why);
    if (register != null) return register;
    Matcher plugin = PLUGIN_LINE.matcher(log);
    String name = "";
    while (plugin.find()) name = plugin.group(1).trim();
    if (!name.isEmpty()) return new Failure(backendReady ? Kind.PAGE : Kind.PLUGIN, name, "", why);
    if (CONFIG_LINE.matcher(log).find() || CONFIG_LINE.matcher(why).find())
      return new Failure(Kind.CONFIG, "", "", why);
    return new Failure(Kind.FAILED, "", "", why);
  }

  /** 注册失败的可行动分类；只认应用自己写下的固定标记，不猜 guest 输出正文。 */
  private static Failure registerFailure(String why) {
    if (why.contains("PLUGIN_PROFILE_LOCK_STALE") || why.contains("PLUGIN_PROFILE_LOCK_TIMEOUT"))
      return new Failure(Kind.REGISTER, "", "PLUGIN_REGISTER_LOCK", why);
    if (why.contains("BUILTIN_REGISTER_TIMEOUT"))
      return new Failure(Kind.REGISTER, "", "PLUGIN_REGISTER_TIMEOUT", why);
    if (why.contains("BUILTIN_REGISTER_EXIT_")
        || why.contains("BUILTIN_REGISTER_RECEIPT_MISSING")
        || why.contains("BUILTIN_REGISTER_NO_RESULT")
        || why.contains("BUILTIN_REGISTER_FAIL"))
      return new Failure(Kind.REGISTER, "", "PLUGIN_REGISTER_FAILED", why);
    return null;
  }

  /** 返回 {运行时, 退出码}；只认应用自己的退出模板（任意界面语言）。 */
  static String[] exit(String reason) {
    for (String language : UiLanguagePreference.LANGUAGES)
      for (String template : new String[] {EXIT_BEFORE, EXIT_AFTER}) {
        Matcher match = templatePattern(translate(language, template)).matcher(reason);
        if (match.matches()) return new String[] {match.group(1), match.group(2)};
      }
    return null;
  }

  private static String translate(String language, String zh) {
    if (UiLanguagePreference.ZH.equals(language)) return zh;
    try {
      String value = UiMessages.forLanguage(language).get(zh);
      return value == null ? zh : value;
    } catch (RuntimeException unknown) {
      return zh;
    }
  }

  private static boolean anyLanguage(String reason, String zh) {
    for (String language : UiLanguagePreference.LANGUAGES)
      if (reason.equals(translate(language, zh))) return true;
    return false;
  }

  /** 把 {@code %s} 模板转成精确匹配的正则；其余字符按字面量处理。 */
  static Pattern templatePattern(String template) {
    StringBuilder regex = new StringBuilder("^");
    int from = 0;
    for (int at; (at = template.indexOf("%s", from)) >= 0; from = at + 2)
      regex.append(Pattern.quote(template.substring(from, at))).append("(.+?)");
    return Pattern.compile(
        regex.append(Pattern.quote(template.substring(from))).append('$').toString());
  }

  /** 单条启动记录的唯一结论。 */
  public enum Outcome {
    READY(Tone.GOOD),
    READY_SAFE(Tone.GOOD),
    READY_THEN_STOPPED(Tone.GOOD),
    READY_WAITING(Tone.INFO),
    RUNNING(Tone.INFO),
    CANCELLED(Tone.IDLE),
    INTERRUPTED(Tone.WARN),
    FAILED(Tone.BAD),
    LOST_AFTER_READY(Tone.BAD);

    public final Tone tone;

    Outcome(Tone tone) {
      this.tone = tone;
    }
  }

  /**
   * status 为持久化记录状态（ready/failed/stopped/starting）。stopped 会覆盖 ready，故用 browserReadyMs
   * 区分「成功后手动停止」与「启动时被取消」；starting 只有属于当前正在进行的一代才算进行中。
   */
  public static Outcome outcome(
      String status, boolean safe, long browserReadyMs, boolean current, boolean starting) {
    return outcome(status, safe, -1, browserReadyMs, current, starting);
  }

  /**
   * 带后端就绪证据的版本。{@code backendReadyMs >= 0} 表示这次启动已经拿到鉴权链接，
   * 只是没有写下最终 ready/failed：这不是“中断 · 无结果”，而是已就绪等待进入 / 被新启动取代。
   */
  public static Outcome outcome(
      String status,
      boolean safe,
      long backendReadyMs,
      long browserReadyMs,
      boolean current,
      boolean starting) {
    boolean ready = browserReadyMs >= 0;
    switch (status == null ? "" : status) {
      case "ready":
        return safe ? Outcome.READY_SAFE : Outcome.READY;
      case "stopped":
        return ready ? Outcome.READY_THEN_STOPPED : Outcome.CANCELLED;
      case "failed":
        return ready ? Outcome.LOST_AFTER_READY : Outcome.FAILED;
      default:
        if (backendReadyMs >= 0 && !(current && starting)) return Outcome.READY_WAITING;
        return current && starting ? Outcome.RUNNING : Outcome.INTERRUPTED;
    }
  }

  /** 失败分类的短名，用于记录行「失败 · 端口被占用」。 */
  public static String shortKind(Kind kind) {
    switch (kind) {
      case PORT:
        return UiText.choose("端口被占用", "Port in use");
      case PLUGIN:
        return UiText.choose("插件出错", "Plugin error");
      case REGISTER:
        return UiText.choose("插件注册未完成", "Plugin registration incomplete");
      case PAGE:
        return UiText.choose("网页加载失败", "Web page failed");
      case CONFIG:
        return UiText.choose("配置检查失败", "Configuration check failed");
      case EXIT:
        return UiText.choose("进程退出", "Process exited");
      case CREDENTIAL:
        return UiText.choose("API Key 无法读取", "API key unreadable");
      case HEALTH:
        return UiText.choose("健康检查无响应", "Health check timed out");
      default:
        return UiText.choose("原因未分类", "Unclassified cause");
    }
  }

  /** 记录行标签：每条只有一个结论。 */
  public static String outcomeLabel(Outcome outcome, Kind failure) {
    switch (outcome) {
      case READY:
        return UiText.text("成功");
      case READY_SAFE:
        return UiText.choose("成功 · 安全模式", "Succeeded · safe mode");
      case READY_THEN_STOPPED:
        return UiText.choose("成功 · 后已手动停止", "Succeeded · stopped later");
      case READY_WAITING:
        return UiText.choose("已就绪 · 等待进入", "Ready · waiting to enter");
      case RUNNING:
        return UiText.text("进行中");
      case CANCELLED:
        return UiText.choose("启动时被手动取消", "Cancelled during start");
      case INTERRUPTED:
        return UiText.choose("中断 · 无结果", "Interrupted · no result");
      case LOST_AFTER_READY:
        return UiText.format("就绪后中断 · %s", shortKind(failure));
      default:
        return UiText.format("失败 · %s", shortKind(failure));
    }
  }

  /** 用时说明：明确是「就绪用时」「失败时刻」还是「已用时」。 */
  public static String elapsedLabel(Outcome outcome, long elapsedMs, long browserReadyMs) {
    long readySeconds = Math.max(0, browserReadyMs) / 1000, seconds = Math.max(0, elapsedMs) / 1000;
    switch (outcome) {
      case READY:
      case READY_SAFE:
      case READY_THEN_STOPPED:
      case LOST_AFTER_READY:
        return UiText.format("%s 秒就绪", readySeconds);
      case READY_WAITING:
        return UiText.format("第 %s 秒已就绪", seconds);
      case RUNNING:
        return UiText.format("已用 %s 秒", seconds);
      case INTERRUPTED:
        return UiText.format("第 %s 秒后无记录", seconds);
      case CANCELLED:
        return UiText.format("第 %s 秒取消", seconds);
      default:
        return UiText.format("第 %s 秒失败", seconds);
    }
  }

  /** 页面判定输入；最新一条记录可能为 null（尚无记录或尚未读取）。 */
  public static final class Input {
    public boolean pending, busy, environmentReady = true, starting, running;
    public Outcome latest;
    public Failure latestFailure;

    /** 当前没有记录、但存在已持久化失败原因时使用。 */
    public Failure storedFailure;
  }

  /** 页面只给一个结论，优先级：未完成修复 → 环境任务 → 环境损坏 → 启动中 → 运行中 → 最近结果。 */
  public static Kind page(Input in) {
    if (in.pending) return Kind.PENDING;
    if (in.busy) return Kind.BUSY;
    if (!in.environmentReady) return Kind.ENVIRONMENT;
    if (in.starting) return Kind.STARTING;
    if (in.running) return Kind.RUNNING;
    if (in.latest == null) return in.storedFailure == null ? Kind.IDLE : in.storedFailure.kind;
    switch (in.latest) {
      case FAILED:
      case LOST_AFTER_READY:
        return in.latestFailure == null ? Kind.FAILED : in.latestFailure.kind;
      case INTERRUPTED:
      case RUNNING:
        return Kind.INTERRUPTED;
      case READY_THEN_STOPPED:
      case CANCELLED:
        return Kind.STOPPED;
      case READY_WAITING:
        return Kind.IDLE;
      default:
        return Kind.IDLE;
    }
  }

  /** 状态卡标题；subject 为插件名、端口或运行时名，code 仅用于进程退出码。 */
  public static String title(Kind kind, String subject, String code) {
    String s = subject == null ? "" : subject;
    switch (kind) {
      case RUNNING:
        return UiText.choose("DSH 正在正常运行", "DSH is running normally");
      case STARTING:
        return UiText.choose("正在启动 DSH", "Starting DSH");
      case STOPPED:
        return UiText.choose("DSH 已手动停止", "DSH was stopped manually");
      case PORT:
        return s.isEmpty()
            ? UiText.choose("启动失败：Web 端口被占用", "Start failed: Web port in use")
            : UiText.format("启动失败：端口 %s 被占用", s);
      case PLUGIN:
        return UiText.format("启动失败：插件 %s 加载出错", s);
      case REGISTER:
        if ("PLUGIN_REGISTER_TIMEOUT".equals(code))
          return UiText.choose(
              "启动失败：插件注册超时（可直接重试）", "Start failed: plugin registration timed out (retry directly)");
        if ("PLUGIN_REGISTER_LOCK".equals(code))
          return UiText.choose(
              "启动失败：插件配置有待确认的锁（可直接重试）",
              "Start failed: a plugin configuration lock needs confirmation (retry directly)");
        return UiText.choose(
            "启动失败：插件注册未完成（可直接重试）",
            "Start failed: plugin registration did not complete (retry directly)");
      case PAGE:
        return UiText.format("网页加载失败：%s 初始化出错", s);
      case CONFIG:
        return UiText.choose("启动失败：启动配置检查未通过", "Start failed: startup configuration check failed");
      case EXIT:
        return UiText.format("启动失败：%s 进程退出，退出码 %s", s, code == null ? "" : code);
      case CREDENTIAL:
        return UiText.choose("启动失败：API Key 无法读取", "Start failed: API key unreadable");
      case HEALTH:
        return UiText.choose("运行中断：服务连续 3 次健康检查无响应", "Interrupted: service missed 3 health checks");
      case FAILED:
        return UiText.choose("启动失败：原因未分类", "Start failed: cause not classified");
      case ENVIRONMENT:
        return UiText.choose("运行环境损坏或需要更新", "Runtime is damaged or needs an update");
      case INTERRUPTED:
        return UiText.choose("上次启动被中断，没有结果", "Last start was interrupted with no result");
      case PENDING:
        return UiText.choose("有一次配置修复没有完成", "A configuration repair did not finish");
      case BUSY:
        return UiText.text("环境任务进行中");
      default:
        return UiText.choose("DSH 未运行", "DSH is not running");
    }
  }

  /** 状态卡说明：说清是什么、是不是故障、下一步做什么。 */
  public static String detail(Kind kind) {
    switch (kind) {
      case RUNNING:
        return UiText.choose(
            "网页已就绪，最近一次启动没有错误。这里的修复操作会先停止 Web。",
            "The web page is ready and the last start had no errors. Repairs here stop Web first.");
      case STARTING:
        return UiText.choose(
            "启动完成或失败前，会改动配置的操作暂不可用。",
            "Actions that change configuration are unavailable until the start succeeds or fails.");
      case STOPPED:
        return UiText.choose("Web 是手动停止的，这不是故障。", "Web was stopped manually. This is not a fault.");
      case PORT:
        return UiText.choose(
            "另一个程序正在使用这个端口。改用自动端口或换一个端口后重试。",
            "Another program is using this port. Switch to an automatic port or choose another, then retry.");
      case PLUGIN:
        return UiText.choose(
            "只有这个插件报错。卸载它，或先安全启动再到插件页处理。",
            "Only this plugin reported an error. Remove it, or start in safe mode and fix it on the Plugins page.");
      case REGISTER:
        return UiText.choose(
            "插件注册没有完成。已经写好的共享依赖链接与插件登记会被下一次启动复用；直接重试通常可以继续，不需要清除数据。若上一次注册被超时结束，重试会接管已退出进程留下的锁。",
            "Plugin registration did not complete. Shared runtime links and plugin records already written are reused on the next start; retry directly and do not clear data. If the previous attempt was ended by a timeout, the retry takes over the lock left by the exited process.");
      case PAGE:
        return UiText.choose(
            "后端已启动，网页插件初始化失败。安全启动只加载官方基础界面。",
            "The backend started but a web plugin failed to initialize. Safe start loads only the official basic interface.");
      case CONFIG:
        return UiText.choose(
            "配置文件没有通过启动检查。恢复健康快照或新建该配置。",
            "A configuration file failed the startup check. Restore a healthy snapshot or create that configuration again.");
      case EXIT:
        return UiText.choose(
            "服务进程意外退出，日志保留了退出前的输出。",
            "The service process exited unexpectedly. The log keeps its output before exit.");
      case CREDENTIAL:
        return UiText.choose(
            "已保存的 API Key 读不出来，原记录已保留。到配置页重新输入后再启动。",
            "The saved API key cannot be read; the original record was kept. Enter it again on the Configuration page, then start.");
      case HEALTH:
        return UiText.choose(
            "Web 曾正常就绪，之后停止响应并被结束。重试通常可以恢复。",
            "Web was ready, then stopped responding and was ended. Retrying usually recovers it.");
      case FAILED:
        return UiText.choose(
            "没有识别出具体原因，下方保留原文。可以先安全启动，或查看日志。",
            "No specific cause was recognized; the original text is kept below. Try a safe start or view the log.");
      case ENVIRONMENT:
        return UiText.choose(
            "这不是配置问题，重试和安全启动都无效。请先修复运行环境；对话和配置会单独保护。",
            "This is not a configuration problem; retrying or safe start will not help. Repair the runtime first; conversations and configuration are protected separately.");
      case INTERRUPTED:
        return UiText.choose(
            "这次启动既没有成功也没有报错，通常是应用被系统结束。不能确定是否有故障。",
            "This start neither succeeded nor reported an error, usually because the system ended the app. It is unknown whether anything is wrong.");
      case PENDING:
        return UiText.choose(
            "修复写到一半被中断。必须先回滚，之后才能启动或做其它修复。",
            "The repair was interrupted mid-write. Roll it back before starting or making other repairs.");
      case BUSY:
        return UiText.choose(
            "任务结束后记录会自动刷新，修复操作随即可用。",
            "Records refresh automatically when the task ends, and repairs become available.");
      default:
        return UiText.choose(
            "当前没有启动任务，也没有未处理的故障。", "No start is in progress and there are no unresolved faults.");
    }
  }

  /** 操作不可用的原因；空串表示可用。 */
  public enum Guard {
    /** 启动类：重试 / 安全启动 / 启动。 */
    START,
    /** 改动配置或插件：会先停止 Web。 */
    CHANGE,
    /** 恢复最近健康快照：另需存在可用快照。 */
    RESTORE,
    /** 回滚未完成的修复：只在确有待处理修复时可用。 */
    PENDING_ONLY
  }

  public static String blocked(Guard guard, Kind page, boolean taskBusy, boolean hasHealthy) {
    if (taskBusy || page == Kind.BUSY)
      return UiText.choose("等待当前任务完成", "Waiting for the current task");
    if (guard == Guard.PENDING_ONLY)
      return page == Kind.PENDING ? "" : UiText.choose("没有未完成的修复", "No unfinished repair");
    if (page == Kind.PENDING)
      return UiText.choose("先回滚未完成的修复", "Roll back the unfinished repair first");
    if (page == Kind.STARTING) return UiText.choose("启动进行中", "A start is in progress");
    if (guard == Guard.START && page == Kind.ENVIRONMENT) return UiText.text("运行环境未就绪");
    if (guard == Guard.START && page == Kind.RUNNING)
      return UiText.choose("已在运行", "Already running");
    if (guard == Guard.RESTORE && !hasHealthy)
      return UiText.choose("没有可用的健康快照", "No healthy snapshot available");
    return "";
  }

  /** 稳定的显示键：状态卡与操作组只在它变化时重建，避免 700ms 刷新闪烁。 */
  public static String key(Kind kind, Failure failure, boolean taskBusy, boolean hasHealthy) {
    return String.format(
        Locale.ROOT,
        "%s|%s|%s|%s|%s",
        kind,
        failure == null ? "" : failure.subject,
        failure == null ? "" : failure.raw.hashCode(),
        taskBusy,
        hasHealthy);
  }
}
