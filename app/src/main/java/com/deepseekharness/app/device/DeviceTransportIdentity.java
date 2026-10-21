package com.deepseekharness.app.device;

import com.deepseekharness.app.util.SelfPackageIdentity;

/** 执行身份与请求应用身份分别记录；未知身份不能用默认包名或 shell UID 补齐。 */
public final class DeviceTransportIdentity {
  public final int executionUid, applicationUid, androidUser;
  public final String applicationId, sourceApk;
  public final boolean packageVerified;

  public DeviceTransportIdentity(int executionUid, SelfPackageIdentity owner) {
    this(
        executionUid,
        owner == null ? -1 : owner.uid,
        owner == null ? "" : owner.name,
        owner == null ? "" : owner.source,
        owner != null);
  }

  public DeviceTransportIdentity(
      int executionUid,
      int applicationUid,
      String applicationId,
      String sourceApk,
      boolean packageVerified) {
    if (executionUid < -1 || applicationUid < -1)
      throw new IllegalArgumentException("DEVICE_UID_INVALID");
    if (packageVerified
        && (applicationUid < 10000
            || !SelfPackageIdentity.validName(applicationId)
            || !com.deepseekharness.app.util.DeviceShellPolicy.canonicalApkPath(sourceApk)))
      throw new IllegalArgumentException("DEVICE_PACKAGE_IDENTITY_INVALID");
    this.executionUid = executionUid;
    this.applicationUid = applicationUid;
    this.androidUser = applicationUid < 0 ? -1 : applicationUid / 100000;
    this.applicationId = applicationId == null ? "" : applicationId;
    this.sourceApk = sourceApk == null ? "" : sourceApk;
    this.packageVerified = packageVerified;
  }

  public static DeviceTransportIdentity unknown() {
    return new DeviceTransportIdentity(-1, null);
  }
}
