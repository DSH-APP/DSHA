package com.deepseekharness.app.bridge;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

/** Android 17 的局域网授权。仅在开启 LAN / ADB 功能时由界面发起请求。 */
public final class LocalNetworkAccess {
    public static final String PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK";
    private LocalNetworkAccess() { }

    public static boolean granted(Context context) {
        return Build.VERSION.SDK_INT < 37
                || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    public static void applyConfiguredFeatures(Context context) {
        if (!granted(context)) return;
        Context app = context.getApplicationContext();
        if (com.deepseekharness.app.DeviceBridgeService.isAdbEnabled(app))
            com.deepseekharness.app.DeviceBridgeService.apply(app);
        com.deepseekharness.app.core.ConfigStore config = new com.deepseekharness.app.core.ConfigStore(app);
        if (config.isLanMode()) {
            com.deepseekharness.app.core.HarnessController controller =
                    com.deepseekharness.app.core.HarnessController.get(app);
            com.deepseekharness.app.LanProxyService.start(
                    controller.proot().getRootfsDir().getAbsolutePath(), app,
                    controller.getWebPort(), controller.getWebGeneration());
        }
    }
}
