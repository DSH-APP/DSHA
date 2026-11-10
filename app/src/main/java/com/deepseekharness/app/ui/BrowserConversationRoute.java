package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import com.deepseekharness.app.util.PreviewPageSession;

/** 原生和任务通知共用的聊天入口；会话选择仍由真实 Host 服务执行并回执。 */
public final class BrowserConversationRoute {
  private static java.lang.ref.WeakReference<PictureInPictureActivity> browser =
      new java.lang.ref.WeakReference<>(null);
  private static PreviewPageSession.Identity browserIdentity;
  private static boolean browserPreference;
  public static final String MODE = "dsha-browser-mode";
  public static final String GENERATION = "dsha-route-generation";
  public static final String INSTANCE = "dsha-route-instance";
  public static final String SESSION = "dsha-route-session";
  public static final String REQUEST = "dsha-route-request";
  public static final String QUESTION = "dsha-route-question";

  private BrowserConversationRoute() {}

  /** 当前存活的浏览器宿主；已结束或正在结束时为 null。 */
  static PictureInPictureActivity owner() {
    var owner = browser.get();
    return owner == null || owner.isFinishing() || owner.isDestroyed() ? null : owner;
  }

  static int taskId() {
    var owner = browser.get();
    return owner == null || owner.isFinishing() || owner.isDestroyed() ? -1 : owner.getTaskId();
  }

  public static Intent conversation(
      Context context, long generation, String instance, String session) {
    PreviewPageSession.Identity current =
        com.deepseekharness.app.core.HarnessController.get(context).getReadyWebPageIdentity();
    Intent intent = conversation(context, current == null ? null : current.authUrl(), null);
    return intent
        .putExtra(GENERATION, generation)
        .putExtra(INSTANCE, instance)
        .putExtra(SESSION, session)
        .putExtra(REQUEST, java.util.UUID.randomUUID().toString());
  }

  /** 同一个会话入口，额外要求页面就绪后把待答问题滚进可视区，并在没有选项时聚焦回答框。 */
  public static Intent question(Context context, long generation, String instance, String session) {
    return conversation(context, generation, instance, session).putExtra(QUESTION, true);
  }

  public static Intent conversation(Context context, String url, String cookie) {
    Intent intent = new Intent(context, WebPreviewActivity.class);
    var owner = browser.get();
    var current =
        com.deepseekharness.app.core.HarnessController.get(context).getReadyWebPageIdentity();
    if (owner != null
        && !owner.isFinishing()
        && !owner.isDestroyed()
        && current != null
        && browserPreference == PreviewFallback.preferred(context)
        && current.equals(browserIdentity)) intent.setClass(context, owner.getClass());
    return intent
        .putExtra(MODE, "FULL")
        .putExtra("url", url)
        .putExtra("cookie", cookie)
        // 与 Main 同一任务：已有宿主压在 Main 下面时重排到前面，不再叠第二个；不清栈、不另起任务。
        .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
  }

  static void register(PictureInPictureActivity owner) {
    browser = new java.lang.ref.WeakReference<>(owner);
    browserIdentity =
        com.deepseekharness.app.core.HarnessController.get(owner).getReadyWebPageIdentity();
    browserPreference = PreviewFallback.preferred(owner);
  }

  static void unregister(PictureInPictureActivity owner) {
    if (browser.get() == owner) {
      browser.clear();
      browserIdentity = null;
    }
  }

  public static boolean current(Intent intent, PreviewPageSession.Identity identity) {
    return !intent.hasExtra(GENERATION)
        || identity != null
            && intent.getLongExtra(GENERATION, -1) == identity.generation()
            && identity.instanceId().equals(intent.getStringExtra(INSTANCE));
  }

  static void copy(Intent from, Intent to) {
    for (String key : new String[] {MODE, INSTANCE, SESSION, REQUEST})
      if (from.hasExtra(key)) to.putExtra(key, from.getStringExtra(key));
    if (from.hasExtra(GENERATION)) to.putExtra(GENERATION, from.getLongExtra(GENERATION, -1));
    if (from.hasExtra(QUESTION)) to.putExtra(QUESTION, from.getBooleanExtra(QUESTION, false));
  }
}
