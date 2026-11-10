import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.SmsQuery;
import java.util.List;

/** Actual production dry-plan calls. It never sends a command or grants a capability. */
public final class DevicePolicyProbe {
  private static int checked;

  private static void expect(
      DeviceShellPolicy.Kind expected, String command, DeviceShellPolicy.Mode mode) {
    DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command, mode);
    if (plan.kind != expected || plan.allowed() != (expected != DeviceShellPolicy.Kind.DENY))
      throw new AssertionError(
          command
              + " mode="
              + DeviceShellPolicy.Mode.idOf(mode)
              + " expected="
              + expected
              + " actual="
              + plan.kind);
    checked++;
  }

  private static void expect(DeviceShellPolicy.Kind expected, String command) {
    expect(expected, command, DeviceShellPolicy.Mode.STRICT);
  }

  /** 放宽/完全放宽的 plan 必须携带原始命令和档位，不得被改写或拆成 argv。 */
  private static void expectShell(String command, DeviceShellPolicy.Mode mode) {
    expect(DeviceShellPolicy.Kind.SHELL, command, mode);
    DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command, mode);
    if (!command.equals(plan.shell) || plan.mode != mode || !plan.argv.isEmpty())
      throw new AssertionError("SHELL plan did not preserve the raw command: " + command);
  }

  private static void expectStrictFallback(String label) {
    if (DeviceShellPolicy.Mode.parse(label) != DeviceShellPolicy.Mode.STRICT)
      throw new AssertionError("invalid mode did not fall back to STRICT: " + label);
  }

  public static void main(String[] args) {
    for (String command :
        List.of(
            "touch /sdcard/DCIM/photo",
            "rm -rf /storage/emulated/0/Pictures",
            "settings put global airplane_mode_on 1",
            "content insert --uri content://sms --bind body:s:x",
            "content delete --uri content://sms",
            "touch /system/new",
            "echo x > /system/new",
            "sh -c 'settings put global x 1'",
            "getprop; rm -rf /",
            "mkfs.ext4 /dev/block/by-name/userdata",
            "reboot"))
      expect(DeviceShellPolicy.Kind.DENY, command);
    for (String command :
        List.of(
            "getprop",
            "/system/bin/getprop ro.product.model",
            "id",
            "settings get global airplane_mode_on"))
      expect(DeviceShellPolicy.Kind.READ, command);
    expect(DeviceShellPolicy.Kind.FILE, "touch /sdcard/Download/policy-fixture");
    String query = "content query --uri content://sms --where 1=0 --user 0";
    for (String mode : List.of("strict", "relaxed", "full")) {
      DeviceShellPolicy.Plan sms =
          DeviceShellPolicy.inspect(query, DeviceShellPolicy.Mode.parse(mode));
      if (sms.kind != DeviceShellPolicy.Kind.SENSITIVE_READ || !sms.allowed())
        throw new AssertionError("SMS gate changed in mode " + mode);
      SmsQuery.validate(sms.argv);
      checked++;
      expect(
          DeviceShellPolicy.Kind.DENY,
          "echo ok; content query --uri content://sms --where 1=0",
          DeviceShellPolicy.Mode.parse(mode));
    }
    expect(DeviceShellPolicy.Kind.DENY, "content query --uri content://contacts --user 0");

    // 放宽档：放行普通 root 命令，仍拒绝格式化、重启与删除整块存储等不可逆操作。
    expectShell("settings put global airplane_mode_on 1", DeviceShellPolicy.Mode.RELAXED);
    expectShell("pm list packages -3", DeviceShellPolicy.Mode.RELAXED);
    for (String command :
        List.of(
            "mkfs.ext4 /dev/block/by-name/userdata",
            "reboot -p",
            "rm -rf /sdcard",
            "rm -rf /storage/emulated/0"))
      expect(DeviceShellPolicy.Kind.DENY, command, DeviceShellPolicy.Mode.RELAXED);

    // 完全放宽：不再按命令内容拦截；短信痕迹仍走 SENSITIVE_READ 或拒绝，不降级成任意 shell。
    for (String command :
        List.of(
            "settings put global airplane_mode_on 1",
            "mkfs.ext4 /dev/block/by-name/userdata",
            "reboot",
            "rm -rf /sdcard"))
      expectShell(command, DeviceShellPolicy.Mode.FULL);

    // 非法/缺失档位一律回落严格档，不能把写坏的配置当成放宽。
    expectStrictFallback("");
    expectStrictFallback("bogus");
    expectStrictFallback("bypass");
    expect(DeviceShellPolicy.Kind.DENY, "settings put global airplane_mode_on 1", null);
    expect(
        DeviceShellPolicy.Kind.DENY,
        "mkfs.ext4 /dev/block/by-name/userdata",
        DeviceShellPolicy.Mode.parse("bogus"));
    if (!DeviceShellPolicy.Mode.idOf(DeviceShellPolicy.Mode.RELAXED).equals("relaxed")
        || !DeviceShellPolicy.Mode.idOf(DeviceShellPolicy.Mode.FULL).equals("full")
        || !DeviceShellPolicy.Mode.idOf(DeviceShellPolicy.Mode.STRICT).equals("strict"))
      throw new AssertionError("root policy mode ids used by the bridge changed");

    if (!DeviceShellPolicy.pathRules().get("protected").equals(
        List.of("/dcim", "/pictures", "/android/data", "/android/obb")))
      throw new AssertionError("The native path rules used by the executor changed");
    System.out.println(
        "PASS native device dry plans: "
            + checked
            + "; production Java, no Android process or permission evidence");
  }
}
