package com.deepseekharness.app.ui;

import android.content.Context;
import androidx.appcompat.app.AlertDialog;
import com.deepseekharness.app.R;
import com.deepseekharness.app.util.UiText;

/** 选字工具条的“更多”入口；会话校验由调用者提供，菜单本身不发送终端输入。 */
final class TerminalActionsDialog {
  private TerminalActionsDialog() {}

  static AlertDialog create(
      Context context,
      String sessionName,
      Runnable paste,
      Runnable output,
      Runnable display,
      Runnable keys,
      Runnable sessions) {
    CardPage page =
        new CardPage(context, UiText.choose("终端操作", "Terminal actions"), sessionName, true);
    AlertDialog dialog = CardSheet.create(context, page);
    var card = page.card();
    page.entry(
        card,
        UiText.choose("粘贴文本…", "Paste text…"),
        "",
        R.drawable.ic_ui_copy,
        () -> {
          dialog.dismiss();
          paste.run();
        });
    page.entry(
        card,
        UiText.choose("查看终端输出…", "View terminal output…"),
        "",
        R.drawable.ic_terminal,
        () -> {
          dialog.dismiss();
          output.run();
        });
    page.entry(
        card,
        UiText.choose("显示设置…", "Display settings…"),
        "",
        R.drawable.ic_terminal_display,
        () -> {
          dialog.dismiss();
          display.run();
        });
    page.entry(
        card,
        UiText.choose("快捷按键…", "Extra keys…"),
        "",
        R.drawable.ic_terminal_keyboard,
        () -> {
          dialog.dismiss();
          keys.run();
        });
    page.entry(
        card,
        UiText.choose("会话管理", "Manage sessions"),
        "",
        R.drawable.ic_terminal_menu,
        () -> {
          dialog.dismiss();
          sessions.run();
        });
    page.button(page.footer, UiText.choose("关闭", "Close"), false, dialog::dismiss);
    return dialog;
  }
}
