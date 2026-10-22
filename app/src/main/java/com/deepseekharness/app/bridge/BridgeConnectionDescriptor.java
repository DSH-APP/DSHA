package com.deepseekharness.app.bridge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One bound listener identity. Credentials are deliberately absent from ordinary snapshots. */
public final class BridgeConnectionDescriptor {
  public static final int SCHEMA_VERSION = 1;
  public static final String GUEST_PATH = "/run/dsha-bridge/connection.json";
  public static final String ENVIRONMENT = "DSHA_BRIDGE_DESCRIPTOR";
  public static final String URL_ENVIRONMENT = "DSHA_BRIDGE_URL";
  private final String applicationId, installInstanceId, bridgeRunId, token;
  private final long generation;
  private final int port;

  public BridgeConnectionDescriptor(
      String applicationId,
      String installInstanceId,
      String bridgeRunId,
      long generation,
      int port,
      String token) {
    if (!com.deepseekharness.app.util.AppIdentity.applicationId().equals(applicationId)
        || !uuid(installInstanceId)
        || !uuid(bridgeRunId)
        || generation <= 0
        || port < 1
        || port > 65535
        || port == 3090
        || port == 3081
        || port == 3190
        || token == null
        || !token.matches("[A-Za-z0-9_-]{32,128}"))
      throw new IllegalArgumentException("BRIDGE_DESCRIPTOR_IDENTITY");
    this.applicationId = applicationId;
    this.installInstanceId = installInstanceId;
    this.bridgeRunId = bridgeRunId;
    this.generation = generation;
    this.port = port;
    this.token = token;
  }

  private static boolean uuid(String value) {
    try {
      return value != null && UUID.fromString(value).toString().equals(value);
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }

  public String applicationId() {
    return applicationId;
  }

  public String installInstanceId() {
    return installInstanceId;
  }

  public String bridgeRunId() {
    return bridgeRunId;
  }

  public long generation() {
    return generation;
  }

  public String host() {
    return "127.0.0.1";
  }

  public int port() {
    return port;
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + port;
  }

  public String state() {
    return "ready";
  }

  public String credentialHeader() {
    return token;
  }

  public Map<String, Object> safeSnapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", SCHEMA_VERSION);
    result.put("applicationId", applicationId);
    result.put("installInstanceId", installInstanceId);
    result.put("bridgeRunId", bridgeRunId);
    result.put("generation", generation);
    result.put("host", host());
    result.put("port", port);
    result.put("baseUrl", baseUrl());
    result.put("state", state());
    return Collections.unmodifiableMap(result);
  }

  /** Only the private 0600 publisher may serialize this credential-bearing map. */
  public Map<String, Object> privateRecord() {
    Map<String, Object> result = new LinkedHashMap<>(safeSnapshot());
    result.put("token", token);
    return Collections.unmodifiableMap(result);
  }

  public boolean authenticates(String app, String run, String presented) {
    return applicationId.equals(app)
        && bridgeRunId.equals(run)
        && presented != null
        && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }

  public Map<String, String> environment() {
    return Map.of(
        ENVIRONMENT,
        GUEST_PATH,
        URL_ENVIRONMENT,
        baseUrl(),
        "DSHA_BRIDGE_INSTALLATION_ID",
        installInstanceId,
        "DSHA_BRIDGE_APPLICATION_ID",
        applicationId);
  }

  @Override
  public String toString() {
    return "BridgeConnectionDescriptor{applicationId="
        + applicationId
        + ",generation="
        + generation
        + ",port="
        + port
        + ",state=ready}";
  }
}
