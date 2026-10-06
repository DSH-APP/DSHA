package com.deepseekharness.app.util;

/** 系统选择器取消的旧结果排空前，不把同一注册入口交给新页面的上传请求。 */
public final class GeckoFileChooserState<Request> {
  private Request pending;

  public boolean begin(Request request) {
    if (request == null || pending != null) return false;
    pending = request;
    return true;
  }

  public boolean waiting() {
    return pending != null;
  }

  public Request pending() {
    return pending;
  }

  /** 即使网页已取消请求，系统回调仍只取回最初发起选择的那个请求。 */
  public Request takeResult() {
    Request request = pending;
    pending = null;
    return request;
  }

  public Request takeResult(Request expected) {
    return expected != null && pending == expected ? takeResult() : null;
  }
}
