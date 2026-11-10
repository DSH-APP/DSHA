package com.deepseekharness.app.util;

/** 终端输入的纯逻辑：一次性修饰键、粘贴边界与确认身份，不依赖 Android。 */
public final class TerminalInput {
  public static final int MAX_PASTE_CHARS = 16 * 1024;

  private TerminalInput() {}

  /** 读取不消费；只有真正交给终端的字符/功能键才消费一次。 */
  public static final class Modifiers {
    private boolean control, alt;

    public boolean control() {
      return control;
    }

    public boolean alt() {
      return alt;
    }

    public void toggleControl() {
      control = !control;
    }

    public void toggleAlt() {
      alt = !alt;
    }

    public void clear() {
      control = false;
      alt = false;
    }
  }

  public static final class Paste {
    public final String text;
    public final boolean multiline;
    public final boolean removedControls;
    public final boolean removedTrailingNewlines;
    public final boolean tooLong;

    private Paste(
        String text, boolean multiline, boolean controls, boolean trailing, boolean tooLong) {
      this.text = text;
      this.multiline = multiline;
      this.removedControls = controls;
      this.removedTrailingNewlines = trailing;
      this.tooLong = tooLong;
    }

    public boolean allowed() {
      return !tooLong && !text.isEmpty();
    }
  }

  /** 保留 Unicode 和内部换行；剔除可注入控制序列的字符，绝不补发 Enter。 */
  public static Paste preparePaste(String source) {
    if (source == null) source = "";
    if (source.length() > MAX_PASTE_CHARS) return new Paste("", false, false, false, true);
    String normalized = source.replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder clean = new StringBuilder(normalized.length());
    boolean controls = false;
    for (int i = 0; i < normalized.length(); ) {
      int cp = normalized.codePointAt(i);
      i += Character.charCount(cp);
      // Tab 保留供文本粘贴使用；ESC、DEL、C0/C1 不得成为终端协议输入。
      if ((cp < 32 && cp != '\n' && cp != '\t') || (cp >= 127 && cp <= 159)) {
        controls = true;
      } else {
        clean.appendCodePoint(cp);
      }
    }
    boolean multiline = clean.indexOf("\n") >= 0;
    int end = clean.length();
    while (end > 0 && clean.charAt(end - 1) == '\n') end--;
    boolean trailing = end != clean.length();
    return new Paste(clean.substring(0, end), multiline, controls, trailing, false);
  }

  /** 弹窗不能把旧会话的确认交给新选择、重建后的视图或新 owner。 */
  public static boolean sameTarget(
      String expectedEpoch,
      long expectedSession,
      long expectedView,
      String actualEpoch,
      long actualSession,
      long actualView,
      boolean inputAllowed) {
    return inputAllowed
        && expectedEpoch != null
        && expectedEpoch.equals(actualEpoch)
        && expectedSession > 0
        && expectedSession == actualSession
        && expectedView == actualView;
  }
}
