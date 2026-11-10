package com.deepseekharness.app.ui;

/** 终端内容面板的窄接口；导航、会话目录与输入各自负责，不拥有进程生死。 */
public interface TerminalPane {
  void newSession();

  boolean selectSession(long id);

  /** 仅在宿主对指定会话取得明确确认后调用。 */
  void closeSession(long id);

  void changeFont(int deltaSp);

  void showKeyboard();

  void paste();

  void restoreInputFocus();

  /** 附加键布局保存后刷新当前 PTY 视图；简易终端无附加键，默认不做事。 */
  default void refreshExtraKeys() {}

  boolean selectionBack();

  boolean isPty();

  interface Host {
    void onTerminalPaneChanged();

    void switchTerminalMode(boolean pty);

    void requestCloseSession(boolean pty, long id);
  }
}
