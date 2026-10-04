package com.deepseekharness.app.util;

/** RFC 1918 address selection; a public 172.* address is not a private LAN fallback. */
public final class LanAddressPolicy {
  private LanAddressPolicy() {}

  public static boolean privateIpv4(String address) {
    if (address == null) return false;
    String[] parts = address.split("\\.", -1);
    if (parts.length != 4) return false;
    int[] bytes = new int[4];
    for (int i = 0; i < parts.length; i++) {
      if (!parts[i].matches("0|[1-9][0-9]{0,2}")) return false;
      bytes[i] = Integer.parseInt(parts[i]);
      if (bytes[i] > 255) return false;
    }
    return bytes[0] == 10
        || bytes[0] == 192 && bytes[1] == 168
        || bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31;
  }
}
