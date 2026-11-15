package com.deepseekharness.app.util;

import java.util.Locale;

/** Immutable identity pin for one externally controlled target (USB or wireless adb). */
public final class ExternalTargetBinding {
  public final String serial;
  public final String product;
  public final String fingerprint;
  public final long epoch;

  public ExternalTargetBinding(String serial, String product, String fingerprint, long epoch) {
    if (!ExternalDevicePolicy.isAdbTarget(serial))
      throw new IllegalArgumentException("TARGET_SERIAL");
    if (product == null || !product.matches("[A-Za-z0-9._-]{1,128}"))
      throw new IllegalArgumentException("TARGET_PRODUCT");
    if (fingerprint == null || fingerprint.length() > 512 || fingerprint.indexOf('\0') >= 0)
      throw new IllegalArgumentException("TARGET_FINGERPRINT");
    if (epoch <= 0) throw new IllegalArgumentException("TARGET_EPOCH");
    this.serial = serial;
    this.product = product;
    this.fingerprint = fingerprint;
    this.epoch = epoch;
  }

  /** 无线调试目标（IPv4:端口）只用于 L0–L1 调试与安装；fastboot 与刷写仍要求 USB。 */
  public boolean wireless() {
    return ExternalDevicePolicy.isWirelessTarget(serial);
  }

  public boolean matches(String serial, String product, String fingerprint) {
    return this.serial.equals(serial)
        && this.product.equals(product)
        && this.fingerprint.equals(fingerprint);
  }

  public String shortFingerprint() {
    String value = fingerprint;
    return value.length() <= 16 ? value : value.substring(0, 16);
  }

  public String describe() {
    return String.format(Locale.ROOT, "%s (%s, %s)", serial, product, shortFingerprint());
  }
}
