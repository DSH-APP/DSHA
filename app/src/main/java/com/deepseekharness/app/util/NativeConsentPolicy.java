package com.deepseekharness.app.util;

/** Local DSHA consent only. It never supplies Android permission, request data or target evidence. */
public record NativeConsentPolicy(boolean globalAllow, long revision) {
  public enum Decision {
    ALLOW,
    ASK,
    DENY
  }

  public static final long MAX_REVISION = 9_007_199_254_740_991L;

  public NativeConsentPolicy {
    if (revision < 0 || revision > MAX_REVISION)
      throw new IllegalArgumentException("CONSENT_REVISION");
  }

  public Decision effective(Decision individual) {
    if (individual == null) throw new IllegalArgumentException("CONSENT_DECISION");
    return globalAllow && individual == Decision.ASK ? Decision.ALLOW : individual;
  }

  public boolean currentGlobal(long expectedRevision) {
    return globalAllow && revision == expectedRevision;
  }

  public NativeConsentPolicy next(boolean allow) {
    if (revision == MAX_REVISION) throw new IllegalStateException("CONSENT_REVISION_EXHAUSTED");
    return new NativeConsentPolicy(allow, revision + 1);
  }

  public String encode() {
    // Android's preference XML serializer can indent after a final newline inside text.
    return "2\n" + globalAllow + "\n" + revision;
  }

  public enum ReadState {
    ON,
    OFF,
    UNCONFIGURED,
    MALFORMED,
    READ_UNAVAILABLE,
    CHANGING,
    SAVE_FAILED,
    RECONFIRM_REQUIRED
  }

  public enum SaveState {
    NONE,
    CACHE_VERIFIED,
    COMMIT_FAILED,
    READBACK_FAILED,
    INVALIDATION_FAILED,
    INVALID_RECORD,
    READ_FAILED,
    REVISION_EXHAUSTED
  }

  /** Local status only: no raw preference bytes, paths, keys, tokens or portable authority. */
  public record Snapshot(ReadState readState, SaveState saveState, long revision) {
    public NativeConsentPolicy policy() {
      return switch (readState) {
        case ON -> new NativeConsentPolicy(true, revision);
        case OFF, UNCONFIGURED -> new NativeConsentPolicy(false, revision);
        default -> new NativeConsentPolicy(false, MAX_REVISION);
      };
    }

    public boolean hasSwitchValue() {
      return readState == ReadState.ON
          || readState == ReadState.OFF
          || readState == ReadState.UNCONFIGURED;
    }

    public boolean canRebuild() {
      return readState == ReadState.MALFORMED || readState == ReadState.READ_UNAVAILABLE;
    }
  }

  /** Strict storage reader. Malformed local authority must never silently restart revision zero. */
  public static NativeConsentPolicy decodeStrict(String value) {
    if (value == null || value.length() > 64) throw new IllegalArgumentException("CONSENT_RECORD");
    String[] rows = value.split("\n", -1);
    boolean current = rows.length == 3 && rows[0].equals("2");
    boolean legacy = rows.length == 4 && rows[0].equals("1") && rows[3].isEmpty();
    if (!(current || legacy)
        || !(rows[1].equals("true") || rows[1].equals("false"))
        || !rows[2].matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("CONSENT_RECORD");
    return new NativeConsentPolicy(Boolean.parseBoolean(rows[1]), Long.parseLong(rows[2]));
  }

  /** Legacy fail-closed projection; writers use decodeStrict and never use this fallback as a base. */
  public static NativeConsentPolicy decode(String value) {
    try {
      return decodeStrict(value);
    } catch (IllegalArgumentException invalid) {
      return new NativeConsentPolicy(false, 0);
    }
  }
}
