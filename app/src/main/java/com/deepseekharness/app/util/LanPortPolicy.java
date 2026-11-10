package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** The current app leaves 3081 for legacy LAN sharing; a busy preference falls back at bind time. */
public final class LanPortPolicy {
  public static final int LEGACY_PORT = 3081;
  public static final int CURRENT_PORT = 3082;

  private LanPortPolicy() {}

  public static int preferred(String applicationId) {
    return "com.dsh.client".equals(applicationId) ? LEGACY_PORT : CURRENT_PORT;
  }

  public static boolean reserved(int port) {
    return port == LEGACY_PORT || port == CURRENT_PORT;
  }

  /** Return the held listener itself, never a port discovered by opening and closing a probe. */
  public static ServerSocket bind(int preferred) throws IOException {
    if (preferred < 1 || preferred > 65535) throw new IllegalArgumentException("LAN_PORT_RANGE");
    try {
      return bindOnce(preferred);
    } catch (BindException busy) {
      if (!BridgePortPolicy.addressInUse(busy)) throw busy;
      return bindOnce(0);
    }
  }

  private static ServerSocket bindOnce(int port) throws IOException {
    ServerSocket socket = new ServerSocket();
    try {
      socket.setReuseAddress(true);
      socket.bind(new InetSocketAddress("0.0.0.0", port));
      return socket;
    } catch (IOException | RuntimeException error) {
      try {
        socket.close();
      } catch (IOException closing) {
        error.addSuppressed(closing);
      }
      throw error;
    }
  }
}
