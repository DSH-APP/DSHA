package com.deepseekharness.app.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Builds the only fastboot/adb argv forms exposed by the external-device API. */
public final class ExternalFastbootPlan {
  private ExternalFastbootPlan() {}

  public static List<String> adb(
      String loader, String libraryPath, String binary, String serial, String... args) {
    List<String> result = prefix(loader, libraryPath, binary);
    result.add("-P");
    result.add("5038");
    result.add("-s");
    result.add(requireSerial(serial));
    Collections.addAll(result, args);
    return immutable(result);
  }

  public static List<String> fastboot(
      String loader, String libraryPath, String binary, String serial, String... args) {
    List<String> result = prefix(loader, libraryPath, binary);
    result.add("-s");
    result.add(requireSerial(serial));
    Collections.addAll(result, args);
    return immutable(result);
  }

  public static List<String> adbServer(String loader, String libraryPath, String binary, int port) {
    if (port < 1024 || port > 65535) throw new IllegalArgumentException("EXTERNAL_ADB_PORT");
    List<String> result = prefix(loader, libraryPath, binary);
    result.add("-P");
    result.add(Integer.toString(port));
    result.add("nodaemon");
    result.add("server");
    return immutable(result);
  }

  public static List<String> flash(
      String loader,
      String libraryPath,
      String binary,
      String serial,
      String partition,
      String image) {
    if (!ExternalDevicePolicy.supportedFlashPartition(partition))
      throw new IllegalArgumentException("EXTERNAL_PARTITION");
    if (image == null || image.isEmpty()) throw new IllegalArgumentException("EXTERNAL_IMAGE");
    return fastboot(loader, libraryPath, binary, serial, "flash", partition, image);
  }

  private static List<String> prefix(String loader, String libraryPath, String binary) {
    requirePath(loader);
    requirePath(libraryPath);
    requirePath(binary);
    List<String> result = new ArrayList<>();
    result.add(loader);
    result.add("--library-path");
    result.add(libraryPath);
    result.add(binary);
    return result;
  }

  private static String requireSerial(String serial) {
    if (!ExternalDevicePolicy.isUsbSerial(serial))
      throw new IllegalArgumentException("EXTERNAL_SERIAL");
    return serial;
  }

  private static void requirePath(String path) {
    if (path == null
        || !path.startsWith("/")
        || path.indexOf('\0') >= 0
        || path.contains("/../")
        || path.endsWith("/..")) throw new IllegalArgumentException("EXTERNAL_TOOL_PATH");
  }

  private static List<String> immutable(List<String> value) {
    if (value.size() > 32) throw new IllegalArgumentException("EXTERNAL_ARGV_LIMIT");
    for (String arg : value)
      if (arg == null || arg.indexOf('\0') >= 0 || arg.length() > 4096)
        throw new IllegalArgumentException("EXTERNAL_ARG");
    return Collections.unmodifiableList(new ArrayList<>(value));
  }
}
