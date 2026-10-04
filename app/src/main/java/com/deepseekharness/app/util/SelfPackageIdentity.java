package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.Arrays;

/** PackageManager facts must match the current authorized APK binding, never a default package. */
public final class SelfPackageIdentity {
  public final String name, source;
  public final int uid;

  private SelfPackageIdentity(String name, int uid, String source) {
    this.name = name;
    this.uid = uid;
    this.source = source;
  }

  public static boolean validName(String name) {
    return name != null && name.matches("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
  }

  public static SelfPackageIdentity launch(String name, int uid, String source, String boundApk)
      throws IOException {
    if (!validName(name)
        || uid < 10000
        || !DeviceShellPolicy.canonicalApkPath(source)
        || !source.equals(boundApk)) throw new IOException("SELF_PACKAGE_SOURCE_UNVERIFIED");
    return new SelfPackageIdentity(name, uid, source);
  }

  public static SelfPackageIdentity caller(
      String name, int uid, String source, String boundApk, int callingUid, String[] packages)
      throws IOException {
    if (callingUid < 10000
        || uid != callingUid
        || packages == null
        || !Arrays.asList(packages).contains(name))
      throw new IOException("SELF_PACKAGE_CALLER_UNVERIFIED");
    return launch(name, uid, source, boundApk);
  }
}
