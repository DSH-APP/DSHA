package com.deepseekharness.app.ui;

/** Android test entry: actual single-file chooser and shared isolated browser scenarios. */
public final class WebInstrumentation extends WebBrowserAudit {
  @Override
  protected boolean multipleSelection() {
    return false;
  }
}
