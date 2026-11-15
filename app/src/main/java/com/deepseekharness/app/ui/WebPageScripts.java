package com.deepseekharness.app.ui;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;

public final class WebPageScripts {
  private WebPageScripts() {}

  /** 应急页只注入语言与语法兼容，不注册正式设备/插件桥。 */
  public static String emergencyCompatibility(Context context) {
    return language(context)
        + "\n"
        + read(context, "web-integration/es-compat.js")
        + "\n"
        + read(context, "web-integration/compat.js");
  }

  public static String compatibility(Context context) {
    return "window.__DSHA_NATIVE_PLUGINS__=true;\n"
        + read(context, "web-integration/safe-area.js")
        + "\n"
        + language(context)
        + "\n"
        + theme(context)
        + "\n"
        + read(context, "web-integration/es-compat.js")
        + "\n"
        + read(context, "web-integration/compat.js")
        + "\n"
        + read(context, "web-integration/files.js")
        + "\n"
        + read(context, "bridge-token-compat.cjs")
        + "\n"
        + read(context, "web-integration/startup.js");
  }

  public static String compatibility(
      Context context, com.deepseekharness.app.util.StartupPageGate gate) {
    return "window.__DSHA_PAGE_BINDING__={nonce:'"
        + gate.nonce()
        + "'};\n"
        + compatibility(context)
        + "\n//# sourceURL="
        + gate.source();
  }

  /**
   * 原生亮暗偏好交给集成插件（dsha-native-theme-sync）；偏好值经 normalize 只可能是三个常量之一。
   * 应急页不注入：应急 DSH 没有集成插件。
   */
  public static String theme(Context context) {
    var config = new com.deepseekharness.app.core.ConfigStore(context);
    return "window.__DSHA_THEME__={preference:'"
        + config.getUiTheme()
        + "',sync:"
        + config.isWebThemeSync()
        + "};window.dispatchEvent(new CustomEvent('dsha-theme'));";
  }

  public static String language(Context context) {
    String id = new com.deepseekharness.app.core.ConfigStore(context).getUiLanguage();
    return "window.__DSHA_LANGUAGE__='"
        + id
        + "';window.dispatchEvent(new CustomEvent('dsha-language'));"
        + "if(!window.__dshaLanguageSelectionBound){window.__dshaLanguageSelectionBound=true;window.addEventListener('dsha-language-selected',e=>{if(['zh','zh-Hant','en','ru','ja','ko','ar','pt','la','fr'].includes(e.detail))window.DshaLanguage?.postMessage(e.detail);});}";
  }

  private static String read(Context context, String path) {
    try (InputStream in = context.getAssets().open(path)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException error) {
      return "";
    }
  }

  public static String back(Context context) {
    try (InputStream in = context.getAssets().open("web-integration/page.js")) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int n;
      while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      return "(function(){"
          + new String(out.toByteArray(), StandardCharsets.UTF_8)
          + ";return window.__dshaPageBack();})()";
    } catch (IOException error) {
      return "false";
    }
  }

  public static org.json.JSONObject presentationBinding(
      com.deepseekharness.app.util.BrowserThemeGate gate) {
    var value = new org.json.JSONObject();
    try {
      value
          .put("nonce", gate.nonce())
          .put("generation", gate.identity().generation())
          .put("instance", gate.identity().instanceId());
    } catch (org.json.JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    return value;
  }

  public static String presentation(
      Context context, com.deepseekharness.app.util.BrowserThemeGate gate) {
    return read(context, "web-integration/ime.js")
        + "\n"
        + read(context, "web-integration/page.js")
        + "\nwindow.__dshaNativePresentation.install("
        + presentationBinding(gate).toString()
        + ");";
  }

  public static org.json.JSONObject sessionRoute(
      android.content.Intent intent, com.deepseekharness.app.util.BrowserThemeGate gate) {
    var request = presentationBinding(gate);
    try {
      request
          .put("sessionId", intent.getStringExtra(BrowserConversationRoute.SESSION))
          .put("requestToken", intent.getStringExtra(BrowserConversationRoute.REQUEST));
    } catch (org.json.JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    return request;
  }

  /**
   * 进入会话后把待答问题滚进可视区；没有选项时才聚焦回答框。
   *
   * <p>判据是提问卡里有没有实际选项行 {@code Mbwy4a_optionRow}。选项容器
   * {@code Mbwy4a_options} 对每张提问卡都会渲染（选项为空时只剩空容器），用它判断会恒真，
   * 任何问题都不会聚焦，不能作为判据。
   *
   * <p>有选项时不能抢焦点：软键盘弹起后提问卡的底部「提交」会被挤出可视区，用户点完
   * 选项找不到提交，表现成“选项点不动”。卡片由网页侧插件渲染，类名带宿主哈希前缀，
   * 因此按前缀匹配并轮询等待渲染完成。
   */
  public static String focusQuestion() {
    return "(function(){var tries=0;function find(){"
        + "var card=document.querySelector('[class*=\"Mbwy4a_frame\"]');"
        + "if(!card)return null;"
        + "if(!card.querySelector('[class*=\"Mbwy4a_body\"]'))return null;"
        + "var field=card.querySelector('[class*=\"Mbwy4a_fieldInput\"]');"
        + "if(!field)field=card.querySelector('[class*=\"Mbwy4a_body\"] textarea');"
        + "if(!field)field=card.querySelector('[class*=\"Mbwy4a_body\"] input');"
        + "return field?{card:card,field:field}:null;}"
        + "function tick(){var hit=find();if(hit){try{hit.card.scrollIntoView({block:'center'});"
        + "if(!hit.card.querySelector('[class*=\"Mbwy4a_optionRow\"]'))"
        + "hit.field.focus({preventScroll:true});}catch(e){}return;}"
        + "if(++tries<40)setTimeout(tick,250);}tick();})();";
  }

  /** 帧只携带几何与本页身份，不包含 URL、输入内容或模型资料。 */
  public static org.json.JSONObject imeFrame(
      com.deepseekharness.app.util.BrowserThemeGate gate, org.json.JSONObject frame) {
    org.json.JSONObject request = presentationBinding(gate);
    try {
      for (String key : new String[] {"frameId", "epoch", "width", "height", "phase"})
        if (frame.has(key)) request.put(key, frame.get(key));
    } catch (org.json.JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    return request;
  }
}
