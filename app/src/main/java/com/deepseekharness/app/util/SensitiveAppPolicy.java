package com.deepseekharness.app.util;

import java.util.Locale;

/** A conservative classification aid, never a proof that an app or screen is harmless. */
public final class SensitiveAppPolicy {
  private SensitiveAppPolicy() {}

  public static boolean sensitive(String pkg) {
    if (pkg == null || pkg.isEmpty()) return true;
    String p = pkg.toLowerCase(Locale.ROOT);
    for (String key :
        new String[] {
          "alipay",
          "tencent.mm",
          "unionpay",
          "jdpay",
          "wallet",
          "paypal",
          "bank",
          "icbc",
          "ccb",
          "abchina",
          "bankofchina",
          "cmbchina",
          "bankcomm",
          "psbc",
          "cebbank",
          "cmbc",
          "spdb",
          "citic",
          "hxb",
          "keepass",
          "bitwarden",
          "lastpass",
          "1password",
          "authenticator",
          "com.android.settings",
          "com.android.systemui",
          "telephony",
          "messaging",
          "sms",
          "securities",
          "broker",
          "trading"
        }) if (p.contains(key)) return true;
    return false;
  }
}
