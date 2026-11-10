package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Bounded device bridge allocation; leave the legacy installation's only port free first. */
public final class BridgePortPolicy {
  public static final int PRIMARY_PORT = 3090;
  public static final int ATTEMPTS = 10;

  private BridgePortPolicy() {}

  /** Production order leaves 3090 available to an unmodified com.dsh.client installation. */
  public static int[] candidates() {
    return candidates(AppIdentity.applicationId());
  }

  public static int[] candidates(String applicationId) {
    int[] ports = new int[ATTEMPTS];
    boolean legacy = "com.dsh.client".equals(applicationId);
    for (int index = 0; index < ATTEMPTS; index++)
      ports[index] = PRIMARY_PORT + (legacy ? index : (index + 1) % ATTEMPTS);
    return ports;
  }

  public static boolean reserved(int port) {
    return port >= PRIMARY_PORT && port < PRIMARY_PORT + ATTEMPTS;
  }

  /** A bridge port must be a normal TCP port and must not steal the Web/LAN reserved ports. */
  public static boolean valid(int port) {
    return port >= 1 && port <= 65535 && port != 3080 && !LanPortPolicy.reserved(port);
  }

  /** 仅全组候选均占用时抛出；权限和其它网络错误保留原异常。 */
  public static final class Exhausted extends BindException {
    Exhausted(BindException cause) {
      super("BRIDGE_PORTS_EXHAUSTED");
      initCause(cause);
    }
  }

  /** 不做先探测后绑定；成功返回的 socket 始终由调用者持有。 */
  public static ServerSocket bind(int[] ports) throws IOException {
    if (ports.length == 0) throw new IllegalArgumentException("BRIDGE_PORT_CANDIDATES");
    BindException busy = null;
    for (int port : ports) {
      if (!valid(port)) throw new IllegalArgumentException("BRIDGE_PORT_RANGE");
      ServerSocket socket = new ServerSocket();
      try {
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("127.0.0.1", port));
        return socket;
      } catch (IOException | RuntimeException error) {
        try {
          socket.close();
        } catch (IOException closing) {
          error.addSuppressed(closing);
        }
        if (!(error instanceof BindException binding) || !addressInUse(binding)) throw error;
        busy = binding;
      }
    }
    throw new Exhausted(busy);
  }

  static boolean addressInUse(BindException error) {
    String message = String.valueOf(error.getMessage());
    return message.contains("EADDRINUSE") || message.contains("Address already in use");
  }

  public static String rangeLabel() {
    return PRIMARY_PORT + "-" + (PRIMARY_PORT + ATTEMPTS - 1);
  }
}
