package com.deepseekharness.app.util;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;

/** A revocable registration capability delivered only to an owned formal Web launch. */
public final class AgentTaskRegistration {
  private final long generation;
  private final String instance;
  private final String nonce;
  private boolean active = true;

  public AgentTaskRegistration(long generation, String instance) {
    this(generation, instance, randomNonce());
  }

  /** 仅恢复已经交给本次完整核验活跃Web的能力，不生成替代授权。 */
  public static AgentTaskRegistration restore(long generation, String instance, String nonce) {
    return new AgentTaskRegistration(generation, instance, nonce);
  }

  private AgentTaskRegistration(long generation, String instance, String savedNonce) {
    if (generation < 1 || instance == null || instance.isEmpty())
      throw new IllegalArgumentException("TASK_REGISTRATION_IDENTITY");
    if (savedNonce == null || !savedNonce.matches("[a-f0-9]{64}"))
      throw new IllegalArgumentException("TASK_REGISTRATION_NONCE");
    this.generation = generation;
    this.instance = instance;
    nonce = savedNonce;
  }

  private static String randomNonce() {
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    StringBuilder value = new StringBuilder(64);
    for (byte part : random) value.append(String.format(java.util.Locale.ROOT, "%02x", part & 255));
    return value.toString();
  }

  /** This map is for the formal Web exec environment, never common shell/runtime defaults. */
  public synchronized Map<String, String> launchEnvironment() {
    if (!active) throw new IllegalStateException("TASK_REGISTRATION_REVOKED");
    return Map.of(
        "DSH_TASK_GENERATION", Long.toString(generation),
        "DSH_TASK_INSTANCE", instance,
        "DSH_TASK_TOKEN", nonce);
  }

  public synchronized void revoke() {
    active = false;
  }

  /** Readiness and its port must come from the same authenticated owned Web identity. */
  public synchronized Snapshot ready(PreviewPageSession.Identity identity) {
    if (!active
        || identity == null
        || identity.generation() != generation
        || !instance.equals(identity.instanceId())) return null;
    return new Snapshot(this, identity);
  }

  public static final class Snapshot {
    private final AgentTaskRegistration lease;
    private final PreviewPageSession.Identity identity;
    private final int port;

    private Snapshot(AgentTaskRegistration lease, PreviewPageSession.Identity identity) {
      this.lease = lease;
      this.identity = identity;
      port = URI.create(identity.authUrl()).getPort();
    }

    public PreviewPageSession.Identity identity() {
      return identity;
    }

    public int port() {
      return port;
    }

    /** 已捕获的旧Snapshot也必须在同一能力锁内完成事件/claim，撤销后不能继续派发。 */
    public <T> T whileActive(java.util.function.Supplier<T> action, T unavailable) {
      synchronized (lease) {
        return lease.active ? action.get() : unavailable;
      }
    }

    /** Invalid shared-token callers cannot reserve or replace the legitimate reporter. */
    public boolean register(
        AgentTaskState state,
        String client,
        long requestedGeneration,
        String requestedInstance,
        String requestedNonce,
        int requestedPort,
        long now) {
      synchronized (lease) {
        if (!lease.active
            || port < 1
            || requestedPort != port
            || requestedGeneration != identity.generation()
            || !identity.instanceId().equals(requestedInstance)
            || requestedNonce == null
            || !requestedNonce.matches("[a-f0-9]{64}")
            || !MessageDigest.isEqual(
                lease.nonce.getBytes(StandardCharsets.US_ASCII),
                requestedNonce.getBytes(StandardCharsets.US_ASCII))) return false;
        state.identity(identity.generation(), identity.instanceId());
        return state.bind(client, now);
      }
    }
  }
}
