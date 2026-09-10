package com.deepseekharness.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import com.deepseekharness.app.util.DeviceAppPolicy;
import org.json.JSONArray;
import org.json.JSONObject;

/** 全量应用清单由 Android PackageManager 提供，分组数据也供 ADB 停止校验使用。 */
final class DeviceAppInventory {
    final DeviceAppPolicy.Snapshot snapshot;
    final JSONArray entries = new JSONArray();
    DeviceAppInventory(Context context) throws Exception {
        PackageManager manager = context.getPackageManager();
        StringBuilder users = new StringBuilder(), systems = new StringBuilder();
        java.util.List<PackageInfo> packages = manager.getInstalledPackages(0);
        packages.sort((a,b) -> a.packageName.compareTo(b.packageName));
        for (PackageInfo item : packages) {
            ApplicationInfo app = item.applicationInfo; if (app == null) continue;
            boolean system = (app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            (system ? systems : users).append("package:").append(item.packageName).append(" uid=").append(app.uid).append('\n');
            entries.put(new JSONObject().put("name", item.packageName).put("uid", app.uid).put("system", system)
                    .put("label", String.valueOf(manager.getApplicationLabel(app))));
        }
        snapshot = DeviceAppPolicy.snapshot(users.toString().replace(" uid=", " uid:"), systems.toString().replace(" uid=", " uid:"));
    }
}
