package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 终端底部附加键的纯逻辑：设备形态、默认布局、编解码与文案，不依赖 Android。 */
public final class TerminalExtraKeys {
  public enum Profile {
    PHONE,
    TABLET
  }

  /** 沿用既有 SharedPreferences 名称，不新增偏好文件。 */
  public static final String PREFS = "deepseekharness";

  public static final int MAX_ROWS = 2;
  public static final int MAX_KEYS_PER_ROW = 10;

  private static final String PREF_PHONE = "term_extra_keys_phone";
  private static final String PREF_TABLET = "term_extra_keys_tablet";

  /** 允许持久化的动作 ID 全集，顺序即设置页展示顺序。 */
  private static final List<String> CATALOG =
      Collections.unmodifiableList(
          Arrays.asList(
              "ESC",
              "TAB",
              "CTRL",
              "ALT",
              "LEFT",
              "RIGHT",
              "UP",
              "DOWN",
              "HOME",
              "END",
              "PGUP",
              "PGDN",
              "CC",
              "CD",
              "CZ",
              "CL",
              "PIPE",
              "SLASH",
              "DASH",
              "TILDE",
              "COLON",
              "SEMI",
              "STAR",
              "AMP",
              "EQUAL",
              "PLUS",
              "QUESTION"));

  private TerminalExtraKeys() {}

  /** 600dp 及以上按平板布局；这是资源限定符的既有边界。 */
  public static Profile profileFor(int smallestWidthDp) {
    return smallestWidthDp >= 600 ? Profile.TABLET : Profile.PHONE;
  }

  /** 手机与平板分别保存自定义布局，键名保持历史命名。 */
  public static String prefKey(Profile profile) {
    return profileOrDefault(profile) == Profile.TABLET ? PREF_TABLET : PREF_PHONE;
  }

  /** 每套默认值都返回新的可变深拷贝，调用方可以就地编辑。 */
  public static List<List<String>> defaults(Profile profile) {
    List<List<String>> rows = new ArrayList<>();
    if (profileOrDefault(profile) == Profile.TABLET) {
      rows.add(
          new ArrayList<>(
              Arrays.asList("ESC", "TAB", "CTRL", "ALT", "LEFT", "DOWN", "UP", "RIGHT")));
      rows.add(
          new ArrayList<>(Arrays.asList("CC", "CD", "CZ", "HOME", "END", "PIPE", "SLASH", "DASH")));
    } else {
      rows.add(new ArrayList<>(Arrays.asList("ESC", "SLASH", "DASH", "HOME", "UP", "END", "PGUP")));
      rows.add(
          new ArrayList<>(Arrays.asList("TAB", "CTRL", "ALT", "LEFT", "DOWN", "RIGHT", "PGDN")));
    }
    return rows;
  }

  /** 空输入、全部非法或超出可保存范围时回到该形态的默认布局。 */
  public static List<List<String>> decode(String raw, Profile profile) {
    if (raw == null || raw.trim().isEmpty()) return defaults(profile);
    List<List<String>> rows = new ArrayList<>();
    for (String line : raw.split("\\|", -1)) {
      List<String> row = new ArrayList<>();
      for (String part : line.split(",", -1)) {
        String action = normalizeAction(part);
        if (action != null) row.add(action);
      }
      rows.add(row);
    }
    List<List<String>> sanitized = sanitize(rows);
    return sanitized.isEmpty() ? defaults(profile) : sanitized;
  }

  /** 限制行数、每行键数与全局重复，去掉空行和空动作，保序返回可编辑副本。 */
  public static List<List<String>> sanitize(List<List<String>> rows) {
    List<List<String>> result = new ArrayList<>();
    if (rows == null) return result;
    Set<String> seen = new LinkedHashSet<>();
    for (List<String> row : rows) {
      if (result.size() >= MAX_ROWS) break;
      List<String> kept = new ArrayList<>();
      if (row != null) {
        for (String value : row) {
          if (kept.size() >= MAX_KEYS_PER_ROW) break;
          if (value == null) continue;
          String action = value.trim();
          if (action.isEmpty()) continue;
          if (seen.add(action)) kept.add(action);
        }
      }
      if (!kept.isEmpty()) result.add(kept);
    }
    return result;
  }

  /** 先 sanitize 再以 | 分行、, 分动作；没有可保存内容时为空串。 */
  public static String encode(List<List<String>> rows) {
    List<List<String>> safe = sanitize(rows);
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < safe.size(); i++) {
      if (i > 0) out.append('|');
      List<String> row = safe.get(i);
      for (int j = 0; j < row.size(); j++) {
        if (j > 0) out.append(',');
        out.append(row.get(j));
      }
    }
    return out.toString();
  }

  /** 设置页使用的动作全集副本，调用方排序或过滤不会影响内部表。 */
  public static List<String> catalog() {
    return new ArrayList<>(CATALOG);
  }

  public static String label(String action) {
    if (action == null) return "";
    switch (action) {
      case "ESC":
        return "Esc";
      case "TAB":
        return "Tab";
      case "CTRL":
        return "Ctrl";
      case "ALT":
        return "Alt";
      case "LEFT":
        return "←";
      case "RIGHT":
        return "→";
      case "UP":
        return "↑";
      case "DOWN":
        return "↓";
      case "HOME":
        return "Home";
      case "END":
        return "End";
      case "PGUP":
        return "PgUp";
      case "PGDN":
        return "PgDn";
      case "CC":
        return "^C";
      case "CD":
        return "^D";
      case "CZ":
        return "^Z";
      case "CL":
        return "^L";
      case "PIPE":
        return "|";
      case "SLASH":
        return "/";
      case "DASH":
        return "-";
      case "TILDE":
        return "~";
      case "COLON":
        return ":";
      case "SEMI":
        return ";";
      case "STAR":
        return "*";
      case "AMP":
        return "&";
      case "EQUAL":
        return "=";
      case "PLUS":
        return "+";
      case "QUESTION":
        return "?";
      default:
        return action;
    }
  }

  /** 说明按键用途；文案随当前应用语言切换。 */
  public static String description(String action) {
    if (action == null) return "";
    switch (action) {
      case "ESC":
        return UiText.choose("发送 Esc 键", "Send the Esc key");
      case "TAB":
        return UiText.choose("发送 Tab 键，用于补全或缩进", "Send Tab for completion or indentation");
      case "CTRL":
        return UiText.choose("修饰键：下一次按键转为 Ctrl 组合", "Modifier: the next key becomes Ctrl+key");
      case "ALT":
        return UiText.choose("修饰键：下一次按键转为 Alt 组合", "Modifier: the next key becomes Alt+key");
      case "LEFT":
        return UiText.choose("方向键：光标左移", "Arrow key: move the cursor left");
      case "RIGHT":
        return UiText.choose("方向键：光标右移", "Arrow key: move the cursor right");
      case "UP":
        return UiText.choose("方向键：上移历史或光标行", "Arrow key: move up through history or lines");
      case "DOWN":
        return UiText.choose("方向键：下移历史或光标行", "Arrow key: move down through history or lines");
      case "HOME":
        return UiText.choose("跳到行首", "Jump to the start of the line");
      case "END":
        return UiText.choose("跳到行尾", "Jump to the end of the line");
      case "PGUP":
        return UiText.choose("向上翻页", "Scroll up one page");
      case "PGDN":
        return UiText.choose("向下翻页", "Scroll down one page");
      case "CC":
        return UiText.choose(
            "控制字符 ^C：中断当前命令", "Control character ^C: interrupt the current command");
      case "CD":
        return UiText.choose(
            "控制字符 ^D：结束输入或退出会话", "Control character ^D: end input or exit the session");
      case "CZ":
        return UiText.choose("控制字符 ^Z：挂起当前命令", "Control character ^Z: suspend the current command");
      case "CL":
        return UiText.choose("控制字符 ^L：清屏", "Control character ^L: clear the screen");
      case "PIPE":
        return UiText.choose("输入管道符号 |", "Type the pipe symbol |");
      case "SLASH":
        return UiText.choose("输入斜杠 /，用于路径分隔", "Type the slash / used in paths");
      case "DASH":
        return UiText.choose("输入连字符 -，用于命令选项", "Type the dash - used for command options");
      case "TILDE":
        return UiText.choose("输入波浪号 ~，表示主目录", "Type the tilde ~ for the home directory");
      case "COLON":
        return UiText.choose(
            "输入冒号 :，用于路径与参数分隔", "Type the colon : used between paths and arguments");
      case "SEMI":
        return UiText.choose("输入分号 ;，用于分隔命令", "Type the semicolon ; used to separate commands");
      case "STAR":
        return UiText.choose("输入星号 *，用于通配符", "Type the asterisk * for wildcards");
      case "AMP":
        return UiText.choose(
            "输入与号 &，让命令在后台运行", "Type the ampersand & to run a command in the background");
      case "EQUAL":
        return UiText.choose("输入等号 =，用于变量赋值", "Type the equals sign = for variable assignment");
      case "PLUS":
        return UiText.choose(
            "输入加号 +，用于权限与增量", "Type the plus sign + for permissions and increments");
      case "QUESTION":
        return UiText.choose(
            "输入问号 ?，用于单字符通配", "Type the question mark ? for single-character wildcards");
      default:
        return UiText.choose("终端按键", "Terminal key");
    }
  }

  /** CTRL 与 ALT 是一次性修饰键，不直接发送字符。 */
  public static boolean isModifier(String action) {
    return "CTRL".equals(action) || "ALT".equals(action);
  }

  /** 控制字符按 Ctrl+字母编码发送，与普通符号键区分。 */
  public static boolean isControl(String action) {
    switch (action == null ? "" : action) {
      case "CC":
      case "CD":
      case "CZ":
      case "CL":
        return true;
      default:
        return false;
    }
  }

  /** 键帽外观分类：只决定底色、字体与字号，不影响按键语义。 */
  public enum KeyStyle {
    /** Ctrl / Alt：功能键底色 + 锁定指示条。 */
    MODIFIER,
    /** Esc / Tab / Home / End / PgUp / PgDn：功能键底色。 */
    FUNCTION,
    /** 方向键：标准底色、较大字号。 */
    ARROW,
    /** ^C / ^D / ^Z / ^L：等宽、强调色。 */
    CONTROL,
    /** 其余单字符符号：等宽。 */
    SYMBOL
  }

  public static KeyStyle style(String action) {
    if (isModifier(action)) return KeyStyle.MODIFIER;
    if (isControl(action)) return KeyStyle.CONTROL;
    switch (action == null ? "" : action) {
      case "ESC":
      case "TAB":
      case "HOME":
      case "END":
      case "PGUP":
      case "PGDN":
        return KeyStyle.FUNCTION;
      case "LEFT":
      case "RIGHT":
      case "UP":
      case "DOWN":
        return KeyStyle.ARROW;
      default:
        return KeyStyle.SYMBOL;
    }
  }

  private static Profile profileOrDefault(Profile profile) {
    return profile == null ? Profile.PHONE : profile;
  }

  /** trim、大写归一后只接受 catalog 内的动作，其余一律丢弃。 */
  private static String normalizeAction(String value) {
    if (value == null) return null;
    String action = value.trim().toUpperCase(Locale.ROOT);
    return CATALOG.contains(action) ? action : null;
  }
}
