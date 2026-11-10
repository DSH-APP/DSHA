package com.deepseekharness.app.util;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;

/** Salted local verifier for the user-selected LAN password. */
public final class LanPassword {
  public static final String PREF_KEY = "lan_password_verifier_v1";
  private static final int ITERATIONS = 100000;

  private LanPassword() {}

  public static boolean validPassword(String password) {
    return password != null
        && password.length() >= 8
        && password.length() <= 128
        && password.indexOf('\0') < 0;
  }

  public static String create(String password) {
    if (!validPassword(password))
      throw new IllegalArgumentException("LAN password must have 8 to 128 characters");
    byte[] salt = new byte[16];
    new SecureRandom().nextBytes(salt);
    return Base64.getEncoder().encodeToString(salt)
        + ":"
        + Base64.getEncoder().encodeToString(derive(password, salt));
  }

  public static boolean verify(String password, String encoded) {
    if (!validPassword(password) || encoded == null) return false;
    try {
      String[] parts = encoded.split(":", -1);
      if (parts.length != 2) return false;
      byte[] salt = Base64.getDecoder().decode(parts[0]);
      byte[] expected = Base64.getDecoder().decode(parts[1]);
      return salt.length == 16
          && expected.length == 32
          && MessageDigest.isEqual(expected, derive(password, salt));
    } catch (IllegalArgumentException failure) {
      return false;
    }
  }

  private static byte[] derive(String password, byte[] salt) {
    PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 256);
    try {
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();
    } catch (java.security.GeneralSecurityException failure) {
      throw new IllegalStateException(failure);
    } finally {
      spec.clearPassword();
    }
  }
}
