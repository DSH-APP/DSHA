package com.deepseekharness.app;

import android.util.Base64;
import com.deepseekharness.app.util.SensitiveData;
import java.nio.charset.StandardCharsets;

/** 由 su 启动的短期进程；在真正的 root 身份下复用路径、应用清单与命令守卫。 */
public final class RootShellMain {
    public static final String RESULT = "DSHA_ROOT_RESULT=";
    private RootShellMain() { }
    public static void main(String[] args) {
        String output;
        try {
            if (android.os.Process.myUid() != 0) throw new SecurityException("su 未授予 root 身份");
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2048]; int count;
            while ((count = System.in.read(buffer)) != -1) {
                if (bytes.size() + count > 16_384) throw new IllegalArgumentException("设备命令过长");
                bytes.write(buffer, 0, count);
            }
            org.json.JSONObject request = new org.json.JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            String command = request.getString("command");
            int smsUser = request.optInt("smsUser", -1);
            ShellService shell = new ShellService();
            output = smsUser < 0 ? shell.exec(command) : shell.execAuthorizedSms(command, smsUser);
        } catch (Throwable e) {
            output = "[ROOT_EXEC_FAILED] " + SensitiveData.redact(String.valueOf(e)) + "\n[EXIT=126]";
        }
        System.out.println(RESULT + Base64.encodeToString(output.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        System.out.flush();
        System.exit(0);
    }
}
