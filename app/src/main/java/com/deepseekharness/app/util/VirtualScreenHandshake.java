package com.deepseekharness.app.util;

/** 协议1握手，实际端口和出生身份与秘密一起由受管启动通道返回。 */
public final class VirtualScreenHandshake {
  public final int port;
  public final String token;
  public final VirtualScreenCoreIdentity identity;

  public VirtualScreenHandshake(int port, String token, VirtualScreenCoreIdentity identity) {
    if (port < 1
        || port > 65535
        || token == null
        || !token.matches("[a-f0-9]{48}")
        || identity == null) throw new IllegalArgumentException("VSCREEN_HANDSHAKE_INVALID");
    this.port = port;
    this.token = token;
    this.identity = identity;
  }

  public String payload() {
    return "1 " + port + " " + token + " " + identity.encode();
  }

  public static VirtualScreenHandshake parse(String payload) {
    String[] fields = payload == null ? new String[0] : payload.split(" ", -1);
    if (fields.length != 4 || !fields[0].equals("1") || !fields[1].matches("[1-9][0-9]{0,4}"))
      throw new IllegalArgumentException("VSCREEN_HANDSHAKE_INVALID");
    return new VirtualScreenHandshake(
        Integer.parseInt(fields[1]), fields[2], VirtualScreenCoreIdentity.parse(fields[3]));
  }

  public static VirtualScreenHandshake started(String output) {
    if (output == null || !output.contains("[EXIT=0]")) return null;
    VirtualScreenHandshake found = null;
    for (String line : output.split("\\r?\\n")) {
      if (!line.startsWith("DSHA_VSCREEN_STARTED ")) continue;
      if (found != null) return null;
      try {
        found = parse(line.substring("DSHA_VSCREEN_STARTED ".length()));
      } catch (RuntimeException invalid) {
        return null;
      }
    }
    return found;
  }
}
