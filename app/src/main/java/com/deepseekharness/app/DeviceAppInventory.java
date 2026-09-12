package com.deepseekharness.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.deepseekharness.app.util.DeviceAppPolicy;
import org.json.JSONArray;
import org.json.JSONObject;

/** 展示 Android 可见的应用；停止应用另由执行器取得完整清单并严格校验。 */
final class DeviceAppInventory {
    final JSONArray entries = new JSONArray();
    DeviceAppInventory(Context context) throws Exception {
        PackageManager manager = context.getPackageManager();
        java.util.List<PackageInfo> packages = manager.getInstalledPackages(0);
        packages.sort((a,b) -> a.packageName.compareTo(b.packageName));
        for (PackageInfo item : packages) {
            ApplicationInfo app = item.applicationInfo; if (app == null) continue;
            boolean system = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            String label = item.packageName;
            try { label = String.valueOf(manager.getApplicationLabel(app)); } catch (RuntimeException ignored) { }
            entries.put(new JSONObject().put("name", item.packageName).put("uid", app.uid).put("system", system)
                    .put("label", label));
        }
    }
}
