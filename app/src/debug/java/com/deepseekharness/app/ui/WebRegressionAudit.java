package com.deepseekharness.app.ui;

/** Debug entry: actual multi-file chooser and shared isolated browser scenarios. */
public final class WebRegressionAudit extends WebBrowserAudit {
  @Override
  protected boolean multipleSelection() {
    return true;
  }
}
