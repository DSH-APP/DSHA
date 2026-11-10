package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 对话页错误面板的纯判定（docs/design/web-crash-20261102）：结论、按钮与逐层状态。
 *
 * <p>只接收调用方已经确认的事实，不推测根因：服务层结论优先，内核与连接问题在服务不在时只作为状态行列出。 文案由界面层按 {@link Kind} 与
 * {@link Value} 渲染，此类不含显示字符串。
 */
public final class WebCrashReport {
  private WebCrashReport() {}

  /** 错误面板结论；每一项对应一个真实代码入口。 */
  public enum Kind {
    /** 重启预算用尽：RestartBudget.blocked()，已停止自动重启。 */
    BLOCKED,
    /** 服务结束后新代次正在启动（保活自动重启或手动重启）。 */
    RESTARTING,
    /** 本次打开的页面，服务鉴权后退出；不会自动重启。 */
    EXITED,
    /** App 重开后接回，确认原进程已退出：suspendRuntimePage(exited=true)。 */
    EXITED_KEPT,
    /** App 重开后接回，身份未确认：suspendRuntimePage(exited=false)。 */
    VERIFY,
    /** 加载时服务尚未就绪。 */
    STARTING,
    /** 页面主框架连接被拒绝：进程在但端口不通，保活连续 3 次后自动重启。 */
    HUNG,
    /** 其它主框架网络错误。 */
    NETWORK,
    /** 401/403，且已自动重新鉴权一次。 */
    AUTH,
    /** 其它 HTTP 错误。 */
    HTTP,
    /** 获取对话凭据失败。 */
    AUTH_REFRESH,
    /** onRenderProcessGone(didCrash=true)。 */
    RENDER_CRASH,
    /** onRenderProcessGone(didCrash=false)。 */
    RENDER_RECLAIMED,
    /** 网页能力检查失败。 */
    WEBVIEW_OUTDATED,
    /** 创建 WebView 抛出异常。 */
    WEBVIEW_INIT,
    /** 对话地址无效。 */
    INVALID_ADDRESS,
    /** 其它未分类错误：保留调用方原有标题与说明。 */
    GENERIC
  }

  public enum Tone {
    BAD,
    WARN,
    INFO
  }

  public enum Action {
    /** 回启动页（leavePreview）。 */
    HOME,
    /** 重新核对保留的会话身份。 */
    RECHECK,
    /** 重新加载对话。 */
    RELOAD,
    /** 打开当前 WebView 提供方的商店页。 */
    STORE,
    /** 在系统浏览器打开。 */
    BROWSER,
    /** 打开启动恢复页。 */
    RECOVERY,
    /** 导出错误日志。 */
    LOGS,
    /** 等待中，按钮不可点。 */
    WAIT
  }

  /** 状态行所属的层，顺序即显示顺序。 */
  public enum Layer {
    SERVICE,
    AUTO_RECOVERY,
    ENGINE,
    PAGE,
    CONNECTION
  }

  public enum Level {
    GOOD,
    WARN,
    BAD,
    RUNNING,
    IDLE
  }

  /** 状态值；界面按值选文案，{@link Row#number} 只在注明的值上使用。 */
  public enum Value {
    SERVICE_RUNNING,
    SERVICE_STARTING,
    /** number = 退出码；未知时为 {@link #UNKNOWN_EXIT}。 */
    SERVICE_EXITED,
    SERVICE_NOT_RESPONDING,
    SERVICE_UNCONFIRMED,
    SERVICE_NOT_READY,
    /** number = 连续失败次数。 */
    AUTO_PAUSED,
    AUTO_IN_PROGRESS,
    AUTO_NONE,
    AUTO_WAITING_CHECKS,
    AUTO_VERIFYING,
    AUTO_WATCHING,
    AUTO_OFF,
    ENGINE_OK,
    ENGINE_CRASHED,
    ENGINE_RECLAIMED,
    ENGINE_OUTDATED,
    ENGINE_FAILED,
    PAGE_KEPT,
    PAGE_CLOSED,
    PAGE_LOAD_FAILED,
    PAGE_NOT_OPENED,
    CONNECTION_NOT_APPLICABLE,
    CONNECTION_REFUSED,
    /** number = WebView 错误码。 */
    CONNECTION_ERROR,
    CONNECTION_AUTH,
    /** number = HTTP 状态码。 */
    CONNECTION_HTTP,
    CONNECTION_CREDENTIAL,
    CONNECTION_UNKNOWN
  }

  public static final int UNKNOWN_EXIT = Integer.MIN_VALUE;

  /** WebViewClient.ERROR_CONNECT 的值；此层不 import Android，常量在单测中与 SDK 对照。 */
  public static final int ERROR_CONNECT = -6;

  /** 面板所需事实；未知时保留默认值，不从文案反推。 */
  public static final class Facts {
    public int exitCode = UNKNOWN_EXIT;
    public int failures;
    public int failureLimit = RestartBudget.LIMIT;
    public boolean autoRestart;
    public int errorCode;
    public int httpStatus;
  }

  public static final class Spec {
    public final Tone tone;
    public final Action primary;
    public final Action secondary;

    /** 服务不可用：系统浏览器打开同样不可用。 */
    public final boolean serviceDown;

    Spec(Tone tone, Action primary, Action secondary, boolean serviceDown) {
      this.tone = tone;
      this.primary = primary;
      this.secondary = secondary;
      this.serviceDown = serviceDown;
    }
  }

  public static final class Row {
    public final Layer layer;
    public final Level level;
    public final Value value;
    public final int number;

    Row(Layer layer, Level level, Value value, int number) {
      this.layer = layer;
      this.level = level;
      this.value = value;
      this.number = number;
    }
  }

  /**
   * 页面所属服务已结束（就绪身份消失且未被接回保留）时，决定是否停在面板上。
   *
   * <p>重启预算用尽时 Controller 自己会走停止流程，WebLifecycle 因此同样记为「用户停止」；所以预算用尽必须先于
   * 用户停止判断，否则这一结论永远不会显示。
   *
   * @return null 表示照常收尾窗口（用户停止或普通维护停止）；否则返回要显示的结论。
   */
  public static Kind ended(
      boolean userStopped, boolean stopping, boolean restartBlocked, boolean starting) {
    if (restartBlocked) return Kind.BLOCKED;
    if (userStopped || stopping) return null;
    if (starting) return Kind.RESTARTING;
    return Kind.EXITED;
  }

  /** 主框架网络错误分类：连接被拒绝说明本机端口不通。 */
  public static Kind network(int errorCode) {
    return errorCode == ERROR_CONNECT ? Kind.HUNG : Kind.NETWORK;
  }

  /** HTTP 错误分类；401/403 已在调用方自动重新鉴权一次之后才会到这里。 */
  public static Kind http(int status) {
    return status == 401 || status == 403 ? Kind.AUTH : Kind.HTTP;
  }

  public static Spec spec(Kind kind) {
    switch (kind) {
      case BLOCKED:
        return new Spec(Tone.BAD, Action.RECOVERY, Action.HOME, true);
      case RESTARTING:
        return new Spec(Tone.INFO, Action.WAIT, Action.HOME, true);
      case EXITED:
        return new Spec(Tone.BAD, Action.HOME, Action.LOGS, true);
      case EXITED_KEPT:
        return new Spec(Tone.BAD, Action.HOME, Action.RECHECK, true);
      case VERIFY:
        return new Spec(Tone.WARN, Action.RECHECK, Action.HOME, true);
      case STARTING:
        return new Spec(Tone.INFO, Action.HOME, Action.RELOAD, true);
      case HUNG:
        return new Spec(Tone.WARN, Action.RELOAD, Action.HOME, true);
      case NETWORK:
        return new Spec(Tone.WARN, Action.RELOAD, Action.HOME, false);
      case AUTH:
        return new Spec(Tone.INFO, Action.HOME, Action.RELOAD, false);
      case HTTP:
        return new Spec(Tone.BAD, Action.RELOAD, Action.HOME, false);
      case AUTH_REFRESH:
        return new Spec(Tone.WARN, Action.RELOAD, Action.HOME, false);
      case RENDER_CRASH:
        return new Spec(Tone.BAD, Action.RELOAD, Action.HOME, false);
      case RENDER_RECLAIMED:
        return new Spec(Tone.WARN, Action.RELOAD, Action.HOME, false);
      case WEBVIEW_OUTDATED:
        return new Spec(Tone.INFO, Action.STORE, Action.BROWSER, false);
      case WEBVIEW_INIT:
        return new Spec(Tone.BAD, Action.STORE, Action.BROWSER, false);
      case INVALID_ADDRESS:
        return new Spec(Tone.WARN, Action.HOME, Action.RELOAD, true);
      case GENERIC:
      default:
        return new Spec(Tone.WARN, Action.RELOAD, Action.HOME, false);
    }
  }

  /** 逐层状态：五层各一行，顺序固定。 */
  public static List<Row> rows(Kind kind, Facts facts) {
    List<Row> rows = new ArrayList<>(5);
    rows.add(service(kind, facts));
    rows.add(autoRecovery(kind, facts));
    rows.add(engine(kind));
    rows.add(page(kind));
    rows.add(connection(kind, facts));
    return Collections.unmodifiableList(rows);
  }

  private static Row row(Layer layer, Level level, Value value) {
    return new Row(layer, level, value, 0);
  }

  private static Row service(Kind kind, Facts facts) {
    switch (kind) {
      case BLOCKED:
      case EXITED:
      case EXITED_KEPT:
        return new Row(Layer.SERVICE, Level.BAD, Value.SERVICE_EXITED, facts.exitCode);
      case RESTARTING:
      case STARTING:
        return row(Layer.SERVICE, Level.RUNNING, Value.SERVICE_STARTING);
      case VERIFY:
        return row(Layer.SERVICE, Level.WARN, Value.SERVICE_UNCONFIRMED);
      case HUNG:
        return row(Layer.SERVICE, Level.WARN, Value.SERVICE_NOT_RESPONDING);
      case INVALID_ADDRESS:
        return row(Layer.SERVICE, Level.WARN, Value.SERVICE_NOT_READY);
      default:
        return row(Layer.SERVICE, Level.GOOD, Value.SERVICE_RUNNING);
    }
  }

  private static Row autoRecovery(Kind kind, Facts facts) {
    switch (kind) {
      case BLOCKED:
        return new Row(
            Layer.AUTO_RECOVERY,
            Level.BAD,
            Value.AUTO_PAUSED,
            Math.max(facts.failures, facts.failureLimit));
      case RESTARTING:
      case STARTING:
        return row(Layer.AUTO_RECOVERY, Level.RUNNING, Value.AUTO_IN_PROGRESS);
      case EXITED:
      case EXITED_KEPT:
        return row(Layer.AUTO_RECOVERY, Level.WARN, Value.AUTO_NONE);
      case VERIFY:
        return row(Layer.AUTO_RECOVERY, Level.RUNNING, Value.AUTO_VERIFYING);
      case HUNG:
        return facts.autoRestart
            ? row(Layer.AUTO_RECOVERY, Level.WARN, Value.AUTO_WAITING_CHECKS)
            : row(Layer.AUTO_RECOVERY, Level.IDLE, Value.AUTO_OFF);
      default:
        return facts.autoRestart
            ? row(Layer.AUTO_RECOVERY, Level.GOOD, Value.AUTO_WATCHING)
            : row(Layer.AUTO_RECOVERY, Level.IDLE, Value.AUTO_OFF);
    }
  }

  private static Row engine(Kind kind) {
    switch (kind) {
      case RENDER_CRASH:
        return row(Layer.ENGINE, Level.BAD, Value.ENGINE_CRASHED);
      case RENDER_RECLAIMED:
        return row(Layer.ENGINE, Level.WARN, Value.ENGINE_RECLAIMED);
      case WEBVIEW_OUTDATED:
        return row(Layer.ENGINE, Level.WARN, Value.ENGINE_OUTDATED);
      case WEBVIEW_INIT:
        return row(Layer.ENGINE, Level.BAD, Value.ENGINE_FAILED);
      default:
        return row(Layer.ENGINE, Level.GOOD, Value.ENGINE_OK);
    }
  }

  private static Row page(Kind kind) {
    switch (kind) {
      case EXITED_KEPT:
      case VERIFY:
        return row(Layer.PAGE, Level.GOOD, Value.PAGE_KEPT);
      case BLOCKED:
      case RESTARTING:
      case EXITED:
      case RENDER_CRASH:
      case RENDER_RECLAIMED:
        return row(Layer.PAGE, Level.WARN, Value.PAGE_CLOSED);
      case HUNG:
      case NETWORK:
      case AUTH:
      case HTTP:
      case WEBVIEW_OUTDATED:
      case GENERIC:
        return row(Layer.PAGE, Level.WARN, Value.PAGE_LOAD_FAILED);
      default:
        return row(Layer.PAGE, Level.IDLE, Value.PAGE_NOT_OPENED);
    }
  }

  private static Row connection(Kind kind, Facts facts) {
    switch (kind) {
      case BLOCKED:
      case RESTARTING:
      case EXITED:
      case EXITED_KEPT:
      case STARTING:
      case INVALID_ADDRESS:
        return row(Layer.CONNECTION, Level.IDLE, Value.CONNECTION_NOT_APPLICABLE);
      case HUNG:
        return row(Layer.CONNECTION, Level.BAD, Value.CONNECTION_REFUSED);
      case NETWORK:
        return new Row(Layer.CONNECTION, Level.BAD, Value.CONNECTION_ERROR, facts.errorCode);
      case AUTH:
        return new Row(Layer.CONNECTION, Level.WARN, Value.CONNECTION_AUTH, facts.httpStatus);
      case HTTP:
        return new Row(Layer.CONNECTION, Level.BAD, Value.CONNECTION_HTTP, facts.httpStatus);
      case AUTH_REFRESH:
        return row(Layer.CONNECTION, Level.WARN, Value.CONNECTION_CREDENTIAL);
      default:
        return row(Layer.CONNECTION, Level.IDLE, Value.CONNECTION_UNKNOWN);
    }
  }
}
