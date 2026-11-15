package com.deepseekharness.app.util;

/**
 * 页面挂载点空白探针的纯判定与协议。
 *
 * <p>探针由网页执行一次：读取当前 path、已知挂载点的文本/子节点数量与 body 文本长度，经 console
 * 回一行 {@code [DSHA_ROUTE] ...}。这里只做解析、清洗与空白判定，不依赖 Android；页码、文本长度等
 * 数值统一夹到诊断需要的区间，路径复用 {@link WebSubresourceDiagnostic#path(String)} 的清洗规则。
 */
public final class WebRouteProbe {
  private WebRouteProbe() {}

  public static final String PREFIX = "[DSHA_ROUTE] ";
  public static final String ROUTE_BLANK = "WEB_ROUTE_BLANK";
  public static final String JS_ERROR = "WEB_JS";
  public static final int MAX_BODY_TEXT = 200_000;

  /**
   * 探针脚本：页面完成后延迟一次执行。挂载点用固定白名单，避免把任意选择器交给网页； 读取失败时回一行可识别的失败事实，不抛出到页面。
   *
   * <p>同时安装一次性的 window.onerror / unhandledrejection 上报（console.error），让未被应用捕获的
   * 异常进入既有的 WEB_JS 链路；消息与源码地址都去 query/fragment 并限长，不记录页面正文。
   */
  public static String script() {
    return "(function(){try{"
        + "var install=function(){"
        + "if(window.__dshaErrorHook)return;window.__dshaErrorHook=1;"
        + "var addr=function(v){try{var s=String(v||'');s=s.split('#')[0].split('?')[0];return s.length>180?s.substring(0,180):s}catch(e){return ''}};"
        + "var say=function(v){try{var s=String(v==null?'':v);if(s.length>300)s=s.substring(0,300);var now=Date.now();if((window.__dshaErrCount||0)>=20)return;if(s===window.__dshaErrLast&&now-(window.__dshaErrAt||0)<5000)return;window.__dshaErrLast=s;window.__dshaErrAt=now;window.__dshaErrCount=(window.__dshaErrCount||0)+1;console.error('[DSHA_JS_ERROR] '+s)}catch(e){}};"
        + "window.addEventListener('error',function(e){try{var m=e&&e.message?e.message:(e&&e.error?String(e.error):'error');say(addr(m)+' @ '+addr(e&&e.filename)+':'+(e&&e.lineno||0))}catch(x){}},true);"
        + "window.addEventListener('unhandledrejection',function(e){try{var r=e&&e.reason;say('unhandledrejection '+(r&&r.message?r.message:String(r))) }catch(x){}},true);"
        + "};"
        + "var nav='';try{nav=location.href;if(window.__dshaRouteProbeUrl===nav)return;window.__dshaRouteProbeUrl=nav}catch(e){}"
        + "var sample=function(second){"
        + "var mounts=['#root','#app','#app-root','#dsh-root','[data-dsha-app]','#__next','#__nuxt'];"
        + "var mount='',mountText=-1,mountChildren=-1;"
        + "for(var i=0;i<mounts.length;i++){var el=null;try{el=document.querySelector(mounts[i])}catch(e){}"
        + "if(el){mount=mounts[i];"
        + "try{var t=(el.innerText||el.textContent||'').replace(/\\s+/g,' ').trim();mountText=t.length>200000?200000:t.length}catch(e){mountText=-1}"
        + "try{mountChildren=el.querySelectorAll('*').length;if(mountChildren>200000)mountChildren=200000}catch(e){mountChildren=-1}"
        + "break;}}"
        + "var body=0;try{var b=(document.body?(document.body.innerText||document.body.textContent||''):'').replace(/\\s+/g,' ').trim();body=b.length>200000?200000:b.length}catch(e){body=-1}"
        + "var p='';try{p=encodeURI(location.pathname||'/')}catch(e){p=''}"
        + "try{console.log('[DSHA_ROUTE] path='+p+' mount='+encodeURIComponent(mount)+' mountText='+mountText+' mountChildren='+mountChildren+' bodyText='+body+' probe='+(second?'2':'1'))}catch(e){}"
        + "};"
        + "install();"
        + "setTimeout(function(){try{sample(false)}catch(e){};setTimeout(function(){try{sample(true)}catch(e){}},3500)},2200)"
        + "}catch(e){}})()";
  }

  /** 解析后的探针事实；字段缺失或越界时按未知处理，不推测页面状态。 */
  public static final class Result {
    public final String path, mount;
    public final int mountText, mountChildren, bodyText;

    /** 1 = 首次采样，2 = 稳定后的复核采样，-1 = 未提供。只有 2 才能触发空白兜底。 */
    public final int probe;

    Result(String path, String mount, int mountText, int mountChildren, int bodyText, int probe) {
      this.path = path;
      this.mount = mount;
      this.mountText = mountText;
      this.mountChildren = mountChildren;
      this.bodyText = bodyText;
      this.probe = probe;
    }

    /**
     * 已知挂载点存在且既无文本也无子节点，或没有已知挂载点时整页只有空文本，才算空白。 任一信号缺失（读不到）都按非空白处理，避免误报把正常页面挡住。
     */
    public boolean blank() {
      if (!mount.isEmpty()) {
        if (mountText < 0 || mountChildren < 0) return false;
        return mountText <= 1 && mountChildren == 0;
      }
      return bodyText >= 0 && bodyText <= 2;
    }

    public boolean readable() {
      return bodyText >= 0 || mountText >= 0;
    }

    public String record() {
      return "path="
          + path
          + " mount="
          + (mount.isEmpty() ? "-" : mount)
          + " mountText="
          + clamp(mountText)
          + " mountChildren="
          + clamp(mountChildren)
          + " bodyText="
          + clamp(bodyText)
          + " probe="
          + probe;
    }
  }

  /** 只接受带固定前缀的单行；返回 null 表示不是本协议的探针行。 */
  public static Result parse(String message) {
    if (message == null || !message.startsWith(PREFIX)) return null;
    String rest = message.substring(PREFIX.length()).trim();
    if (rest.isEmpty() || rest.length() > 900 || rest.indexOf('\n') >= 0) return null;
    String path = "/", mount = "";
    int mountText = -1, mountChildren = -1, bodyText = -1, probe = -1;
    boolean any = false;
    for (String field : rest.split(" ")) {
      int equal = field.indexOf('=');
      if (equal <= 0) continue;
      String key = field.substring(0, equal), value = field.substring(equal + 1);
      switch (key) {
        case "path" -> {
          // 页面侧只编码 pathname；这里不解码，直接按已编码路径清洗，避免还原出页面内容。
          path = WebSubresourceDiagnostic.path(value);
          any = true;
        }
        case "mount" -> mount = mountCode(percentSafe(value));
        case "mountText" -> mountText = number(value);
        case "mountChildren" -> mountChildren = number(value);
        case "bodyText" -> bodyText = number(value);
        case "probe" -> probe = Math.max(-1, Math.min(2, number(value)));
        default -> {
          // 未知字段忽略：协议只认固定白名单，避免把其它页面内容带进诊断。
        }
      }
    }
    return any ? new Result(path, mount, mountText, mountChildren, bodyText, probe) : null;
  }

  /** 挂载点只保留白名单中的固定名，其它输入一律忽略，不把页面提供的选择器写进诊断。 */
  static String mountCode(String value) {
    return switch (value) {
      case "#root", "#app", "#app-root", "#dsh-root", "[data-dsha-app]", "#__next", "#__nuxt" ->
          value;
      default -> "";
    };
  }

  /** 只解码百分号编码的 ASCII 字符；不存在或非法编码时退回原值。 */
  static String percentSafe(String value) {
    if (value == null || value.isEmpty()) return "";
    StringBuilder out = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char c = value.charAt(index);
      if (c == '%' && index + 2 < value.length()) {
        int high = Character.digit(value.charAt(index + 1), 16);
        int low = Character.digit(value.charAt(index + 2), 16);
        char decoded = (char) ((high << 4) | low);
        if (high >= 0 && low >= 0 && (decoded == '/' || decoded == '#' || decoded == '?')) {
          out.append(decoded);
          index += 2;
          continue;
        }
      }
      out.append(c);
    }
    return out.toString();
  }

  static int number(String value) {
    try {
      int parsed = Integer.parseInt(value);
      return Math.max(-1, Math.min(MAX_BODY_TEXT, parsed));
    } catch (NumberFormatException invalid) {
      return -1;
    }
  }

  private static int clamp(int value) {
    return Math.max(-1, Math.min(MAX_BODY_TEXT, value));
  }
}
