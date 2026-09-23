package com.deepseekharness.app;

/** 仅独立验收包导出的空宿主，不自动安装、启动 Web 或运行数据作业。 */
public final class DeviceAuditActivity extends androidx.appcompat.app.AppCompatActivity {
    @Override protected void onCreate(android.os.Bundle saved) {
        super.onCreate(saved);
        try { DeviceAuditSupport.requireIsolated(this); } catch (java.io.IOException error) { finish(); return; }
        android.widget.TextView label=new android.widget.TextView(this);
        label.setText("DSHA rc2.1 · 独立数据验收");label.setPadding(32,48,32,48);setContentView(label);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
