package com.deepseekharness.app.diagnostics;

import java.util.*;

/** Immutable exception graph; explicit budgets and references distinguish omissions from absence. */
public final class Failure {
  public final String code, summary, exceptionClass;
  public final List<String> frames;
  public final Failure cause;
  public final List<Failure> suppressed;
  public final boolean truncated, cycle;
  public final int omittedFrames, omittedSuppressed;
  public final String messageOmissionReason;
  public final int omittedMessageChars;

  /** Optional per-call policy for exceptions which may echo screen/input or request content. */
  public static final class MessagePolicy {
    private final Set<String> safeCodes;
    private final String omissionReason;

    private MessagePolicy(Set<String> fixedCodes, String omissionReason) {
      for (String code : fixedCodes)
        if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,80}"))
          throw new IllegalArgumentException("INVALID_SAFE_DIAGNOSTIC_CODE");
      safeCodes = Set.copyOf(fixedCodes);
      this.omissionReason = omissionReason;
    }

    /** Callers supply fixed literal codes, never request values or exception-derived strings. */
    public static MessagePolicy safeCodes(Set<String> fixedCodes) {
      return new MessagePolicy(
          Objects.requireNonNull(fixedCodes), "SCREEN_BUSINESS_CONTENT_EXCLUDED");
    }

    /** Request bodies remain excluded; only caller-owned fixed literal codes may be retained. */
    public static MessagePolicy requestSafeCodes(Set<String> fixedCodes) {
      return new MessagePolicy(
          Objects.requireNonNull(fixedCodes), "REQUEST_BUSINESS_CONTENT_EXCLUDED");
    }
  }

  private Failure(
      String code,
      String summary,
      String type,
      List<String> frames,
      Failure cause,
      List<Failure> suppressed,
      boolean truncated,
      boolean cycle,
      int omittedFrames,
      int omittedSuppressed,
      String messageOmissionReason,
      int omittedMessageChars) {
    this.code = code;
    this.summary = summary;
    this.exceptionClass = type;
    this.frames = List.copyOf(frames);
    this.cause = cause;
    this.suppressed = List.copyOf(suppressed);
    this.truncated = truncated;
    this.cycle = cycle;
    this.omittedFrames = omittedFrames;
    this.omittedSuppressed = omittedSuppressed;
    this.messageOmissionReason = messageOmissionReason;
    this.omittedMessageChars = omittedMessageChars;
  }

  public static Failure capture(String code, Throwable error) {
    return capture(code, error, null);
  }

  public static Failure capture(String code, Throwable error, MessagePolicy messagePolicy) {
    String stable =
        code != null
                && code.matches("[A-Za-z0-9_.:-]{1,120}")
                && code.equals(DiagnosticPrivacy.text(code))
            ? code
            : "UNCLASSIFIED_FAILURE";
    try {
      return visit(stable, error, new IdentityHashMap<>(), new int[] {0, 0, 0}, 0, messagePolicy);
    } catch (Throwable unavailable) {
      return error == null
          ? null
          : new Failure(
              stable,
              "EXCEPTION_CAPTURE_UNAVAILABLE",
              error.getClass().getName(),
              List.of(),
              null,
              List.of(),
              true,
              false,
              0,
              0,
              "",
              0);
    }
  }

  private static Failure visit(
      String code,
      Throwable error,
      IdentityHashMap<Throwable, Boolean> seen,
      int[] budget,
      int depth,
      MessagePolicy messagePolicy) {
    if (error == null) return null;
    boolean cycle = seen.put(error, Boolean.TRUE) != null;
    String type = error.getClass().getName(), message = error.getMessage();
    boolean omitMessage =
        messagePolicy != null
            && message != null
            && !message.isEmpty()
            && !messagePolicy.safeCodes.contains(message);
    String summary = omitMessage ? "EXCEPTION_MESSAGE_OMITTED" : DiagnosticPrivacy.text(message);
    String omissionReason = omitMessage ? messagePolicy.omissionReason : "";
    int omittedChars = omitMessage ? message.length() : 0;
    int summaryLimit = Math.max(0, Math.min(8192, 32768 - budget[2]));
    boolean clipped = omitMessage || summary.length() > summaryLimit;
    if (summary.length() > summaryLimit) summary = summary.substring(0, summaryLimit);
    budget[2] += summary.length();
    if (cycle || depth >= 16 || ++budget[0] > 64)
      return new Failure(
          code,
          summary,
          type,
          List.of(),
          null,
          List.of(),
          !cycle || omitMessage,
          cycle,
          0,
          0,
          omissionReason,
          omittedChars);
    StackTraceElement[] stack = error.getStackTrace();
    List<String> frames = new ArrayList<>();
    int kept = 0;
    while (kept < stack.length && budget[1] < 512 && budget[2] < 32768) {
      String frame = DiagnosticPrivacy.text(stack[kept].toString());
      int limit = Math.min(4096, 32768 - budget[2]);
      if (frame.length() > limit) {
        frame = frame.substring(0, limit);
        clipped = true;
      }
      frames.add(frame);
      budget[2] += frame.length();
      budget[1]++;
      kept++;
    }
    Throwable[] suppressed = error.getSuppressed();
    List<Failure> children = new ArrayList<>();
    int take = Math.min(16, suppressed.length);
    Failure cause = visit(code, error.getCause(), seen, budget, depth + 1, messagePolicy);
    for (int i = 0; i < take; i++)
      children.add(visit(code, suppressed[i], seen, budget, depth + 1, messagePolicy));
    return new Failure(
        code,
        summary,
        type,
        frames,
        cause,
        children,
        clipped || kept < stack.length || take < suppressed.length,
        false,
        stack.length - kept,
        suppressed.length - take,
        omissionReason,
        omittedChars);
  }

  public Map<String, Object> toMap() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("code", code);
    m.put("summary", summary);
    m.put("exceptionClass", exceptionClass);
    m.put("frames", frames);
    m.put("cause", cause == null ? null : cause.toMap());
    List<Object> children = new ArrayList<>();
    for (Failure f : suppressed) children.add(f.toMap());
    m.put("suppressed", children);
    m.put("truncated", truncated);
    m.put("cycle", cycle);
    m.put("omittedFrames", (long) omittedFrames);
    m.put("omittedSuppressed", (long) omittedSuppressed);
    if (!messageOmissionReason.isEmpty()) {
      m.put("messageOmissionReason", messageOmissionReason);
      m.put("omittedMessageChars", (long) omittedMessageChars);
    }
    return m;
  }

  public boolean complete() {
    if (truncated) return false;
    if (cause != null && !cause.complete()) return false;
    for (Failure f : suppressed) if (!f.complete()) return false;
    return true;
  }
}
