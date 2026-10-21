package com.deepseekharness.app.vscreen;

import com.deepseekharness.app.bridge.BridgeHandshake;
import com.deepseekharness.app.util.AppIdentity;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Same-connection peer proof using the shared bridge HMAC primitive; no credential on the wire. */
final class VirtualScreenPeerProof {
  static final String HEADER = "X-DSHA-VScreen-Proof";

  static final class Session {
    private final VirtualScreenHandshake endpoint;
    private final String client, server;

    Session(VirtualScreenHandshake endpoint, String client, String server) {
      this.endpoint = endpoint;
      this.client = client;
      this.server = server;
    }

    private String identity() {
      return AppIdentity.applicationId()
          + "\n1\nhttp://127.0.0.1:"
          + endpoint.port
          + "\n"
          + endpoint.identity.encode()
          + "\n"
          + client
          + "\n"
          + server;
    }

    String serverProof() {
      return BridgeHandshake.hmac(endpoint.token, "DSHA-VSCREEN-SERVER/1\n" + identity());
    }

    String clientProof(String method, String target) {
      return BridgeHandshake.hmac(
          endpoint.token,
          "DSHA-VSCREEN-CLIENT/1\n"
              + identity()
              + "\n"
              + method
              + "\n"
              + target
              + "\n"
              + BridgeHandshake.sha(new byte[0]));
    }

    boolean authorizes(String method, String target, String proof) {
      return BridgeHandshake.matches(clientProof(method, target), proof);
    }
  }

  static Session client(Socket socket, VirtualScreenHandshake endpoint) throws IOException {
    String client = BridgeHandshake.nonce();
    write(socket, "DSHA_VSCREEN_HELLO 1 " + client);
    String[] reply = line(socket).split(" ", -1);
    if (reply.length != 4
        || !reply[0].equals("DSHA_VSCREEN_PROOF")
        || !reply[1].equals("1")
        || !BridgeHandshake.nonceValid(reply[2])) throw new IOException("VSCREEN_PEER_UNPROVEN");
    Session session = new Session(endpoint, client, reply[2]);
    if (!BridgeHandshake.matches(session.serverProof(), reply[3]))
      throw new IOException("VSCREEN_PEER_UNPROVEN");
    return session;
  }

  static Session server(Socket socket, VirtualScreenHandshake endpoint) throws IOException {
    String[] hello = line(socket).split(" ", -1);
    if (hello.length != 3
        || !hello[0].equals("DSHA_VSCREEN_HELLO")
        || !hello[1].equals("1")
        || !BridgeHandshake.nonceValid(hello[2])) throw new IOException("VSCREEN_PEER_UNPROVEN");
    Session session = new Session(endpoint, hello[2], BridgeHandshake.nonce());
    write(socket, "DSHA_VSCREEN_PROOF 1 " + session.server + " " + session.serverProof());
    return session;
  }

  private static void write(Socket socket, String value) throws IOException {
    socket.getOutputStream().write((value + "\r\n").getBytes(StandardCharsets.US_ASCII));
    socket.getOutputStream().flush();
  }

  private static String line(Socket socket) throws IOException {
    long deadline = System.nanoTime() + 3_000_000_000L;
    InputStream input = socket.getInputStream();
    ByteArrayOutputStream text = new ByteArrayOutputStream();
    while (text.size() < 256) {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) throw new IOException("VSCREEN_PEER_TIMEOUT");
      socket.setSoTimeout((int) Math.max(1, (remaining + 999999L) / 1_000_000L));
      int value = input.read();
      if (value == '\r') {
        remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("VSCREEN_PEER_TIMEOUT");
        socket.setSoTimeout((int) Math.max(1, (remaining + 999999L) / 1_000_000L));
        if (input.read() != '\n') throw new IOException("VSCREEN_PEER_LINE_INVALID");
        return text.toString(StandardCharsets.US_ASCII.name());
      }
      if (value < 32 || value > 126) throw new IOException("VSCREEN_PEER_LINE_INVALID");
      text.write(value);
    }
    throw new IOException("VSCREEN_PEER_LINE_LIMIT");
  }
}
