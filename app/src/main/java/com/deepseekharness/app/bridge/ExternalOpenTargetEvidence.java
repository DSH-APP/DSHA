package com.deepseekharness.app.bridge;

import com.deepseekharness.app.capabilities.TargetEvidence;

/** 固定一次外跳请求的完整 URI；用于审批后复核，不把 URI 内容写入通用日志。 */
public final class ExternalOpenTargetEvidence implements TargetEvidence {
  public final String uri;
  public final String scheme;

  public ExternalOpenTargetEvidence(String uri, String scheme) {
    if (uri == null || uri.isEmpty() || scheme == null || scheme.isEmpty())
      throw new IllegalArgumentException("EXTERNAL_TARGET_INVALID");
    this.uri = uri;
    this.scheme = scheme;
  }

  @Override
  public String kind() {
    return "external-open:" + scheme;
  }
}
