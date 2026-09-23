package com.deepseekharness.app;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;

/** 独立验收安装的前台入口；所有窗口状态均在主线程读取，不修改系统后台权限。 */
public final class DeviceAuditSupport {
    private DeviceAuditSupport() { }
    public static void requireIsolated(Context context) throws IOException {
        if (!context.getPackageName().equals("com.dsh.client.rc21audit")) throw new IOException("REFUSE_USER_INSTALLATION");
        if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            throw new IOException("REQUIRES_NON_DEBUGGABLE");
    }
    public static Activity foreground(Instrumentation driver) {
        Activity[] found = {null};
        driver.runOnMainSync(() -> { var current = ForegroundActivity.current(); if (ForegroundActivity.isResumed(current)) found[0] = current; });
        return found[0];
    }
    public static Activity open(Instrumentation driver, Class<? extends Activity> type) throws Exception {
        return open(driver,type,new Bundle());
    }
    public static Activity open(Instrumentation driver, Class<? extends Activity> type,Bundle extras) throws Exception {
        Context context = driver.getTargetContext(); requireIsolated(context);
        if (foreground(driver) == null) bringHost(driver,context);
        // 使用与既有安装验收相同的短时测试启动权限；不改设备或应用持久权限。
        boolean borrowed=Build.VERSION.SDK_INT>=29;
        Instrumentation.ActivityMonitor monitor=driver.addMonitor(type.getName(),null,false);
        try{
            if(borrowed)driver.getUiAutomation().adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND");
            driver.runOnMainSync(()->context.startActivity(new Intent(context,type).putExtras(extras).setAction("device.audit."+System.nanoTime()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            Activity opened=monitor.waitForActivityWithTimeout(15000);
            if(!type.isInstance(opened))throw new IOException("TEST_UI_NOT_OPEN:"+type.getSimpleName());
            driver.waitForIdleSync();return opened;
        }finally{driver.removeMonitor(monitor);if(borrowed)driver.getUiAutomation().dropShellPermissionIdentity();}
    }
    private static void bringHost(Instrumentation driver,Context context)throws IOException{
        try(ParcelFileDescriptor command=driver.getUiAutomation().executeShellCommand(
                "am start -W -n "+context.getPackageName()+"/com.deepseekharness.app.DeviceAuditActivity");
            InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(command);ByteArrayOutputStream captured=new ByteArrayOutputStream()){
            byte[] buffer=new byte[1024];int n;while((n=input.read(buffer))!=-1){if(captured.size()+n>16384)throw new IOException("TEST_START_OUTPUT_LIMIT");captured.write(buffer,0,n);}
            String output=captured.toString("UTF-8");if(output.contains("Error")||output.contains("Exception"))throw new IOException("TEST_ENTRY_FAILED:"+output);
        }
    }
}
