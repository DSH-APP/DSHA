import com.deepseekharness.app.util.DeviceShellPolicy;
import com.deepseekharness.app.util.SmsQuery;
import java.util.List;

/** Actual production dry-plan calls. It never sends a command or grants a capability. */
public final class DevicePolicyProbe {
  private static int checked;

  private static void expect(DeviceShellPolicy.Kind expected, String command) {
    DeviceShellPolicy.Plan plan = DeviceShellPolicy.inspect(command);
    if (plan.kind != expected || plan.allowed() != (expected != DeviceShellPolicy.Kind.DENY))
      throw new AssertionError(command + " expected=" + expected + " actual=" + plan.kind);
    checked++;
  }

  public static void main(String[] args) {
    for (String command : List.of("touch /sdcard/DCIM/photo", "rm -rf /storage/emulated/0/Pictures",
        "settings put global airplane_mode_on 1", "content insert --uri content://sms --bind body:s:x",
        "content delete --uri content://sms", "touch /system/new", "echo x > /system/new",
        "sh -c 'settings put global x 1'", "getprop; rm -rf /"))
      expect(DeviceShellPolicy.Kind.DENY, command);
    for (String command : List.of("getprop", "/system/bin/getprop ro.product.model", "id",
        "settings get global airplane_mode_on"))
      expect(DeviceShellPolicy.Kind.READ, command);
    expect(DeviceShellPolicy.Kind.FILE, "touch /sdcard/Download/policy-fixture");
    String query = "content query --uri content://sms --where 1=0 --user 0";
    expect(DeviceShellPolicy.Kind.SENSITIVE_READ, query);
    SmsQuery.validate(DeviceShellPolicy.inspect(query).argv);
    expect(DeviceShellPolicy.Kind.DENY, "content query --uri content://contacts --user 0");
    if (!DeviceShellPolicy.pathRules().get("protected").equals(
        List.of("/dcim", "/pictures", "/android/data", "/android/obb")))
      throw new AssertionError("The native path rules used by the executor changed");
    System.out.println("PASS native device dry plans: " + checked
        + "; production Java, no Android process or permission evidence");
  }
}
