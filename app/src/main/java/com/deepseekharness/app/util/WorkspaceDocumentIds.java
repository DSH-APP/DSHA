package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.ArrayDeque;

/** Convert a configured guest directory to an existing SAF ID, never to a host path. */
public final class WorkspaceDocumentIds {
  private WorkspaceDocumentIds() {}

  public static String checked(String id) throws IOException {
    if (id == null || id.isEmpty()) throw new IOException("WORKSPACE_DOCUMENT_ID");
    if (id.equals("root")) return id;
    for (String part : id.split("/", -1)) DocumentPaths.checkName(part);
    return id;
  }

  public static String forGuestDirectory(String directory) throws IOException {
    if (directory == null || !directory.startsWith("/") || directory.indexOf('\\') >= 0)
      throw new IOException("WORKSPACE_GUEST_DIRECTORY");
    ArrayDeque<String> parts = new ArrayDeque<>();
    for (String part : directory.split("/", -1)) {
      if (part.isEmpty() || part.equals(".")) continue;
      if (part.equals("..")) {
        if (parts.isEmpty()) throw new IOException("WORKSPACE_GUEST_DIRECTORY");
        parts.removeLast();
      } else {
        DocumentPaths.checkName(part);
        parts.addLast(part);
      }
    }
    return GuestPaths.ROOT_RELATIVE + (parts.isEmpty() ? "" : "/" + String.join("/", parts));
  }

  public static String parent(String id) throws IOException {
    String value = checked(id);
    int slash = value.lastIndexOf('/');
    return slash < 0 ? "root" : value.substring(0, slash);
  }

  public static String location(String id) throws IOException {
    String value = checked(id);
    if (value.equals("root")) return "DSHA";
    if (value.equals(GuestPaths.ROOT_RELATIVE)) return "/";
    if (value.startsWith(GuestPaths.ROOT_RELATIVE + "/"))
      return value.substring(GuestPaths.ROOT_RELATIVE.length());
    return "DSHA/" + value;
  }
}
