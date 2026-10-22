package com.deepseekharness.app.device;

import com.deepseekharness.app.capabilities.CapabilityResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 真实退出码、输出完整性、本地关闭和远端退出证明不能互相替代。 */
public final class DeviceTransportResult {
  private static final Pattern EXIT = Pattern.compile("\\[EXIT=([0-9]{1,3})\\]\\s*$");
  public final CapabilityResult outcome;
  public final String transport, output;
  public final Integer exitCode;
  public final boolean outputComplete, localExitConfirmed, remoteExitConfirmed;
  public final DeviceTransportIdentity identity;

  public DeviceTransportResult(
      String transport,
      CapabilityResult outcome,
      String output,
      Integer exitCode,
      boolean outputComplete,
      boolean localExitConfirmed,
      boolean remoteExitConfirmed,
      DeviceTransportIdentity identity) {
    if (transport == null
        || outcome == null
        || identity == null
        || exitCode != null && (exitCode < 0 || exitCode > 255))
      throw new IllegalArgumentException("DEVICE_RESULT_INVALID");
    if (outcome.state == CapabilityResult.State.UNKNOWN && remoteExitConfirmed)
      throw new IllegalArgumentException("UNKNOWN_CANNOT_PROVE_REMOTE_EXIT");
    this.transport = transport;
    this.outcome = outcome;
    this.output = output == null ? "" : output;
    this.exitCode = exitCode;
    this.outputComplete = outputComplete;
    this.localExitConfirmed = localExitConfirmed;
    this.remoteExitConfirmed = remoteExitConfirmed;
    this.identity = identity;
  }

  public static DeviceTransportResult notSent(String transport, String code, String output) {
    return new DeviceTransportResult(
        transport,
        CapabilityResult.notSent(code, ""),
        output,
        null,
        true,
        true,
        false,
        DeviceTransportIdentity.unknown());
  }

  public static DeviceTransportResult rejected(String transport, String code, String output) {
    return new DeviceTransportResult(
        transport,
        CapabilityResult.rejected(code, ""),
        output,
        null,
        true,
        true,
        false,
        DeviceTransportIdentity.unknown());
  }

  public static DeviceTransportResult unknown(
      String transport,
      String code,
      String output,
      boolean localExitConfirmed,
      DeviceTransportIdentity identity) {
    return new DeviceTransportResult(
        transport,
        CapabilityResult.unknown(code, ""),
        output,
        null,
        false,
        localExitConfirmed,
        false,
        identity);
  }

  public DeviceTransportResult withOutput(String output) {
    return new DeviceTransportResult(
        transport,
        outcome,
        output,
        exitCode,
        outputComplete,
        localExitConfirmed,
        remoteExitConfirmed,
        identity);
  }

  /** 只在旧门面边界使用；正常 exit 125 仍是正常完成，缺失结束标记才是 UNKNOWN。 */
  public static DeviceTransportResult fromLegacy(
      String transport, String output, DeviceTransportIdentity identity) {
    String text = output == null ? "" : output;
    if (text.contains("[EXECUTION_UNKNOWN]")
        || text.endsWith("[EXIT=timeout]")
        || text.endsWith("[EXIT=interrupted]"))
      return unknown(transport, "DEVICE_EXECUTION_UNKNOWN", text, false, identity);
    if (text.startsWith("[POLICY_BLOCKED]"))
      return new DeviceTransportResult(
          transport,
          CapabilityResult.rejected("DEVICE_POLICY_BLOCKED", ""),
          text,
          null,
          true,
          true,
          false,
          identity);
    Matcher marker = EXIT.matcher(text);
    if (!marker.find() || Integer.parseInt(marker.group(1)) > 255)
      return unknown(transport, "DEVICE_RESULT_INCOMPLETE", text, false, identity);
    return new DeviceTransportResult(
        transport,
        CapabilityResult.completed("DEVICE_COMMAND_COMPLETED", ""),
        text,
        Integer.valueOf(marker.group(1)),
        !text.contains("[OUTPUT_TRUNCATED]"),
        true,
        false,
        identity);
  }
}
