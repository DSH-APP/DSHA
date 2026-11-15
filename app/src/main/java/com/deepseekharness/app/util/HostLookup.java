package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /app/net/resolve 的纯逻辑：主机名校验与固定文本响应。
 *
 * <p>只做「域名 → 数字地址」，不接受 IP 字面量、localhost 或单标签名，避免把桥当成任意
 * 探测器；响应为固定前缀的行文本，容器侧解析器不依赖 JSON 结构以外的任何字段。
 */
public final class HostLookup {
  public static final String HEADER = "DSHA_RESOLVE_V1";
  public static final int MAX_ADDRESSES = 32;

  private HostLookup() {}

  public enum Status {
    OK,
    NXDOMAIN,
    NODATA,
    TIMEOUT,
    NO_NETWORK,
    BUSY,
    FAILED,
    INVALID
  }

  /** 归一化主机名：小写、去尾点；不合法返回 null。 */
  public static String normalize(String raw) {
    if (raw == null) return null;
    String host = raw.trim().toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
    if (host.isEmpty() || host.length() > 253) return null;
    if (host.indexOf('.') < 0) return null; // 单标签名交给 /etc/hosts 与 search，不走宿主
    if (host.equals("localhost") || host.endsWith(".localhost")) return null;
    String[] labels = host.split("\\.", -1);
    boolean numeric = true;
    for (String label : labels) {
      if (label.isEmpty() || label.length() > 63) return null;
      if (label.startsWith("-") || label.endsWith("-")) return null;
      for (int i = 0; i < label.length(); i++) {
        char c = label.charAt(i);
        boolean ok = c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '_';
        if (!ok) return null;
      }
      if (!label.matches("[0-9]+")) numeric = false;
    }
    if (numeric) return null; // IPv4 字面量本就不需要解析
    return host;
  }

  /** family 只接受 0 / 4 / 6；其他值返回 -1。 */
  public static int family(String raw) {
    if (raw == null || raw.isEmpty() || raw.equals("0")) return 0;
    if (raw.equals("4")) return 4;
    if (raw.equals("6")) return 6;
    return -1;
  }

  /** 按 family 过滤并去重；IPv4 在前（与 auto 模式的 gai.conf 排序一致），截断上限。 */
  public static List<String> select(List<String> addresses, int family) {
    List<String> v4 = new ArrayList<>(), v6 = new ArrayList<>();
    if (addresses != null)
      for (String address : addresses) {
        if (address == null || address.isEmpty()) continue;
        boolean six = address.indexOf(':') >= 0;
        if (address.indexOf('%') >= 0) continue;
        List<String> target = six ? v6 : v4;
        if (!target.contains(address)) target.add(address);
      }
    List<String> out = new ArrayList<>();
    if (family != 6) out.addAll(v4);
    if (family != 4) out.addAll(v6);
    return out.size() > MAX_ADDRESSES ? new ArrayList<>(out.subList(0, MAX_ADDRESSES)) : out;
  }

  public static String render(Status status, List<String> addresses) {
    StringBuilder out = new StringBuilder(HEADER).append('\n');
    List<String> list = addresses == null ? List.of() : addresses;
    Status effective = status == Status.OK && list.isEmpty() ? Status.NODATA : status;
    out.append("status=").append(effective.name()).append('\n');
    if (effective == Status.OK)
      for (String address : list)
        out.append(address.indexOf(':') >= 0 ? "6 " : "4 ").append(address).append('\n');
    return out.toString();
  }
}
