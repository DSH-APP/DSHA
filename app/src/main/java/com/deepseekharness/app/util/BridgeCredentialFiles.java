package com.deepseekharness.app.util;

import com.deepseekharness.app.backup.BackupFileSystem;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Descriptor-checked bridge credentials in the selected data root; no guest-link write fallback. */
public final class BridgeCredentialFiles {
  private BridgeCredentialFiles() {}

  public static String read(BackupFileSystem fs, File file) throws IOException {
    var node = fs.stat(file);
    if (node.type.equals("MISSING")) return "";
    if (!node.type.equals("FILE")) throw new IOException("BRIDGE_TOKEN_FILE_TYPE");
    String token = new String(fs.small(file, 256), StandardCharsets.UTF_8).trim();
    return token.matches("[A-Za-z0-9_-]{32,128}") ? token : "";
  }

  public static void publish(BackupFileSystem fs, File file, String token, boolean retainOld)
      throws IOException {
    if (token == null || !token.matches("[A-Za-z0-9_-]{32,128}"))
      throw new IOException("BRIDGE_TOKEN_FORMAT");
    File root = file.getParentFile();
    if (!fs.stat(root).type.equals("DIRECTORY"))
      throw new IOException("BRIDGE_DATA_ROOT_UNAVAILABLE");
    var old = fs.stat(file);
    if (!old.type.equals("MISSING") && !old.type.equals("FILE"))
      throw new IOException("BRIDGE_TOKEN_FILE_TYPE");
    if (retainOld && old.type.equals("FILE")) {
      fs.move(
          file, fs.child(root, CredentialPaths.BRIDGE_TOKEN + ".retained-" + UUID.randomUUID()));
      fs.syncDirectory(root);
    }
    fs.atomic(root, file.getName(), token.getBytes(StandardCharsets.UTF_8));
    fs.mode(file, 0600);
    byte[] header = ("X-Token: " + token + "\n").getBytes(StandardCharsets.UTF_8);
    File headers = fs.child(root, CredentialPaths.BRIDGE_HEADERS);
    fs.atomic(root, headers.getName(), header);
    fs.mode(headers, 0600);
    if (!read(fs, file).equals(token) || !java.util.Arrays.equals(header, fs.small(headers, 256)))
      throw new IOException("BRIDGE_TOKEN_READBACK");
  }

  /** Publish the selected bridge port; this is machine state, not a credential. */
  public static void publishPort(BackupFileSystem fs, File file, int port) throws IOException {
    if (port < 1 || port > 65535) throw new IOException("BRIDGE_PORT_RANGE");
    File root = file.getParentFile();
    if (root == null || !fs.stat(root).type.equals("DIRECTORY"))
      throw new IOException("BRIDGE_DATA_ROOT_UNAVAILABLE");
    var old = fs.stat(file);
    if (!old.type.equals("MISSING") && !old.type.equals("FILE"))
      throw new IOException("BRIDGE_PORT_FILE_TYPE");
    fs.atomic(root, file.getName(), Integer.toString(port).getBytes(StandardCharsets.US_ASCII));
    fs.mode(file, 0600);
    if (readPort(fs, file) != port) throw new IOException("BRIDGE_PORT_READBACK");
  }

  /** Returns -1 for missing/invalid state; guests then fall back to the legacy port. */
  public static int readPort(BackupFileSystem fs, File file) {
    try {
      var node = fs.stat(file);
      if (!node.type.equals("FILE") || node.size < 1 || node.size > 5 || (node.mode & 0077) != 0)
        return -1;
      String raw = new String(fs.small(file, 5), StandardCharsets.US_ASCII);
      if (!raw.matches("[0-9]{1,5}")) return -1;
      int port = Integer.parseInt(raw);
      return port >= 1 && port <= 65535 ? port : -1;
    } catch (Throwable error) {
      return -1;
    }
  }
}
