package com.deepseekharness.app.util;

/** 正式网页所属的运行实例；地址相同也不能把旧 DOM 交给新进程。 */
public final class PreviewPageSession {
  public record Identity(long generation, String instanceId, String authUrl) {
    public Identity {
      if (generation <= 0
          || instanceId == null
          || instanceId.isEmpty()
          || WebPreviewPolicy.loopbackBaseUrl(authUrl) == null)
        throw new IllegalArgumentException("PREVIEW_PAGE_IDENTITY");
    }
  }

  private Identity identity;

  /** 仅在真正创建新页面时登记；等待鉴权不能冒充已经替换了旧页面。 */
  public boolean claim(Identity current) {
    if (current == null || current.equals(identity)) return false;
    identity = current;
    return true;
  }

  public Identity identity() {
    return identity;
  }

  /** 未就绪、停止中及进程换代都不能批准旧页的异步回调。 */
  public boolean isCurrent(Identity current) {
    return identity != null && identity.equals(current);
  }

  public void clear() {
    identity = null;
  }
}
