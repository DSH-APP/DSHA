package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「本进程之外的受管准备完成戳」：覆盖升级后的下一次启动用它证明受管覆盖层已经由当前 APK
 * 完整写入，避免在首启再整份重装一次。
 *
 * <p>这是一份**可复核的缓存**，不是完成许可：调用方必须自己按同一文件集合重新读取
 * {@code RuntimeAssetStamp} 并核验受管身份；戳记不一致、文件缺失或路径越界都退回完整准备。
 * 文件只写入 rootfs 之外的私有运行时目录，不含用户数据。
 */
public final class RuntimePreparedProof {
  private static final String HEADER = "DSHA_RUNTIME_PREPARED_V1";
  public static final int MAX_BYTES = 1024 * 1024;
  private static final int MAX_FILES = 4096;
  private static final int MAX_VALUE = 4096;

  private RuntimePreparedProof() {}

  public static final class Proof {
    public final String apk;
    public final String stamp;
    public final List<String> files;

    private Proof(String apk, String stamp, List<String> files) {
      this.apk = apk;
      this.stamp = stamp;
      this.files = Collections.unmodifiableList(files);
    }
  }

  /** 受管文件相对路径：只接受 ASCII 安全字符，不允许绝对路径、反斜杠或 .. 分段。 */
  public static boolean validRelative(String value) {
    if (value == null || value.isEmpty() || value.length() > 240) return false;
    if (!value.matches("[A-Za-z0-9_@+.-][A-Za-z0-9_@+./-]*")) return false;
    for (String part : value.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
    return true;
  }

  public static String format(String apk, String stamp, List<String> relativeFiles) {
    if (!validValue(apk) || !validValue(stamp) || relativeFiles == null || relativeFiles.isEmpty())
      throw new IllegalArgumentException("RUNTIME_PREPARED_PROOF_INPUT");
    if (relativeFiles.size() > MAX_FILES)
      throw new IllegalArgumentException("RUNTIME_PREPARED_PROOF_COUNT");
    Set<String> unique = new LinkedHashSet<>(relativeFiles);
    if (unique.size() != relativeFiles.size())
      throw new IllegalArgumentException("RUNTIME_PREPARED_PROOF_DUPLICATE");
    StringBuilder text = new StringBuilder(HEADER).append('\n');
    text.append("apk ").append(apk.length()).append(' ').append(apk).append('\n');
    text.append("stamp ").append(stamp.length()).append(' ').append(stamp).append('\n');
    text.append("files ").append(relativeFiles.size()).append('\n');
    for (String relative : relativeFiles) {
      if (!validRelative(relative))
        throw new IllegalArgumentException("RUNTIME_PREPARED_PROOF_PATH");
      text.append(relative.length()).append(' ').append(relative).append('\n');
    }
    String value = text.toString();
    if (value.length() > MAX_BYTES)
      throw new IllegalArgumentException("RUNTIME_PREPARED_PROOF_SIZE");
    return value;
  }

  /** 严格解析；任何格式、计数或路径异常都返回 null，让调用方退回完整准备。 */
  public static Proof parse(String text) {
    if (text == null || text.isEmpty() || text.length() > MAX_BYTES) return null;
    String[] lines = text.split("\n", -1);
    if (lines.length < 5 || !HEADER.equals(lines[0])) return null;
    String apk = named(lines[1], "apk", false);
    String stamp = named(lines[2], "stamp", false);
    if (apk == null || stamp == null) return null;
    Matcher files = FILES.matcher(lines[3]);
    if (!files.matches()) return null;
    int count;
    try {
      count = Integer.parseInt(files.group(1));
    } catch (NumberFormatException invalid) {
      return null;
    }
    if (count <= 0 || count > MAX_FILES || lines.length != count + 5) return null;
    List<String> result = new ArrayList<>(count);
    Set<String> unique = new LinkedHashSet<>();
    for (int index = 0; index < count; index++) {
      String relative = named(lines[4 + index], null, true);
      if (relative == null || !unique.add(relative)) return null;
      result.add(relative);
    }
    if (!lines[lines.length - 1].isEmpty()) return null;
    return new Proof(apk, stamp, result);
  }

  private static final Pattern FILES = Pattern.compile("files ([0-9]{1,4})");

  /** 解析 {@code <name> <len> <value>}（相对路径行为 {@code <len> <value>}），长度必须精确一致。 */
  private static String named(String line, String expectedName, boolean relative) {
    String rest = line;
    if (expectedName != null) {
      if (!line.startsWith(expectedName + " ")) return null;
      rest = line.substring(expectedName.length() + 1);
    }
    int space = rest.indexOf(' ');
    if (space <= 0 || space >= rest.length() - 1) return null;
    int length;
    try {
      length = Integer.parseInt(rest.substring(0, space));
    } catch (NumberFormatException invalid) {
      return null;
    }
    String value = rest.substring(space + 1);
    if (length <= 0 || length > MAX_VALUE || value.length() != length) return null;
    if (relative) return validRelative(value) ? value : null;
    return validValue(value) ? value : null;
  }

  private static boolean validValue(String value) {
    if (value == null || value.isEmpty() || value.length() > MAX_VALUE) return false;
    for (int index = 0; index < value.length(); index++) {
      char current = value.charAt(index);
      if (current < 0x20 || current > 0x7e) return false;
    }
    return true;
  }
}
