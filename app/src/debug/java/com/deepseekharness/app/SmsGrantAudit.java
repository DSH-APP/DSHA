package com.deepseekharness.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import com.deepseekharness.app.core.DeviceGrants;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.UUID;

/** 隔离授权偏好，只检查执行计划；不启动桥、不读取或发送真实短信。 */
public final class SmsGrantAudit extends Instrumentation {
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    private void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }
    @Override public void onStart() {
        Bundle result = new Bundle();
        String prefix = "sms-audit-" + UUID.randomUUID() + "-";
        Context context = new ContextWrapper(getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefix + name, mode);
            }
        };
        try {
            DeviceGrants grants = new DeviceGrants(context);
            check(!grants.smsReadAllowed(), "新环境必须默认未授权");
            HttpShellService bridge = new HttpShellService(context);
            // 没有实际监听代次时确认不可用，不能默认放行。
            Method plan = HttpShellService.class.getDeclaredMethod("devicePlan", String.class, boolean.class);
            plan.setAccessible(true);
            String query = "content query --uri content://sms --projection _id:date --where '1=0'";
            JSONObject denied = new JSONObject((String) plan.invoke(bridge, query, false));
            check("DENY".equals(denied.getString("kind")) && denied.getJSONArray("argv").length() == 0, "无授权时不可执行");
            check(grants.setSmsReadAllowed(true), "授权存储成功");
            JSONObject allowed = new JSONObject((String) plan.invoke(bridge, query, false));
            check("READ".equals(allowed.getString("kind")) && "remembered".equals(allowed.getString("authorization")), "预授权不需要逐次确认");
            check(allowed.getJSONArray("argv").toString().contains("--user"), "明确限定 Android 用户");
            for (String unsafe : new String[]{"content delete --uri content://sms", "content query --uri content://contacts", query + " --user 999", query + "; id"}) {
                String value = (String) plan.invoke(bridge, unsafe, false);
                check(value.startsWith("[POLICY_BLOCKED]") || "DENY".equals(new JSONObject(value).getString("kind")), "授权不能扩大为写入、跨用户或其他提供者");
            }
            check(grants.setSmsReadAllowed(false), "撤销存储成功");
            check("DENY".equals(new JSONObject((String) plan.invoke(bridge, query, false)).getString("kind")), "撤销后下一条查询立即拒绝");
            check(!new DeviceGrants(context).smsReadAllowed(), "重新读取没有恢复授权");
            result.putString("result", "PASS"); result.putInt("checks", checks);
        } catch (Throwable error) { result.putString("result", "FAIL: " + error); }
        finally {
            context.getSharedPreferences("dsha_device_grants", 0).edit().clear().commit();
            context.getSharedPreferences(com.deepseekharness.app.util.Constants.PREFS, 0).edit().clear().commit();
        }
        finish("PASS".equals(result.getString("result")) ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }
}
