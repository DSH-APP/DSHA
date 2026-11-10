package com.deepseekharness.app.bridge;

import com.deepseekharness.app.capabilities.TargetEvidence;

/** 固定一次剪贴板读取请求的目标类别，不携带剪贴板内容。 */
public final class ClipboardTargetEvidence implements TargetEvidence {
  @Override
  public String kind() {
    return "clipboard";
  }
}
