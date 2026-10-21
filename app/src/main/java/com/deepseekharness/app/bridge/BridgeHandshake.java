package com.deepseekharness.app.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Domain-separated HMAC proofs; neither challenge leg carries the credential. */
public final class BridgeHandshake {
  public static final String PATH = "/bridge/challenge";
  private static final SecureRandom RANDOM = new SecureRandom();

  private BridgeHandshake() {}

  public static String nonce() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return hex(bytes);
  }

  public static boolean nonceValid(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  private static String identity(BridgeConnectionDescriptor d, String client, String server) {
    if (!nonceValid(client) || !nonceValid(server))
      throw new IllegalArgumentException("BRIDGE_NONCE_INVALID");
    return d.applicationId()
        + "\n"
        + d.installInstanceId()
        + "\n"
        + d.bridgeRunId()
        + "\n"
        + d.generation()
        + "\n"
        + d.baseUrl()
        + "\n"
        + client
        + "\n"
        + server;
  }

  public static String serverProof(BridgeConnectionDescriptor d, String client, String server) {
    return hmac(d.credentialHeader(), "DSHA-BRIDGE-SERVER/1\n" + identity(d, client, server));
  }

  public static String clientProof(
      BridgeConnectionDescriptor d,
      String client,
      String server,
      String method,
      String target,
      String bodyHash) {
    if (!nonceValid(bodyHash)) throw new IllegalArgumentException("BRIDGE_BODY_HASH_INVALID");
    return hmac(
        d.credentialHeader(),
        "DSHA-BRIDGE-CLIENT/1\n"
            + identity(d, client, server)
            + "\n"
            + method
            + "\n"
            + target
            + "\n"
            + bodyHash);
  }

  public static String hmac(String key, String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
      return hex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static String sha(byte[] bytes) {
    try {
      return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  public static boolean matches(String expected, String supplied) {
    return nonceValid(expected)
        && nonceValid(supplied)
        && MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.US_ASCII),
            supplied.getBytes(StandardCharsets.US_ASCII));
  }

  private static String hex(byte[] bytes) {
    StringBuilder value = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
    return value.toString();
  }
}
