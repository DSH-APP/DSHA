package com.deepseekharness.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.DiagnosticRepository;
import com.deepseekharness.app.util.SensitiveData;

public final class DiagnosticActivity extends AppCompatActivity {
    private DiagnosticRepository repository;
    private EditText steps;
    private final ActivityResultLauncher<String> exporter = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
        if (uri == null) return;
        final String report = completeReport();
        new Thread(() -> {
            String message;
            try (java.io.OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) throw new java.io.IOException("无法写入所选位置");
                stream.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8)); message = "诊断报告已导出";
            } catch (Exception error) { message = "导出失败：" + error.getClass().getSimpleName(); }
            String done = message; runOnUiThread(() -> Toast.makeText(getApplicationContext(), done, Toast.LENGTH_LONG).show());
        }, "diagnostic-export").start();
    });
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); setContentView(R.layout.activity_diagnostics);
        repository = new ViewModelProvider(this).get(DiagnosticRepository.class);
        steps = findViewById(R.id.diagnostic_steps);
        findViewById(R.id.diagnostic_back).setOnClickListener(v -> finish());
        findViewById(R.id.diagnostic_refresh).setOnClickListener(v -> repository.generate());
        findViewById(R.id.diagnostic_repair).setOnClickListener(v -> repository.repairNetworkTools());
        findViewById(R.id.diagnostic_plugins).setOnClickListener(v -> startActivity(new android.content.Intent(this, MainActivity.class).putExtra("open_plugins", true)));
        findViewById(R.id.diagnostic_copy).setOnClickListener(v -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard == null) throw new IllegalStateException("剪贴板不可用");
                clipboard.setPrimaryClip(ClipData.newPlainText("DSHA 诊断", completeReport()));
                Toast.makeText(this, "已复制脱敏报告", Toast.LENGTH_SHORT).show();
            } catch (Exception error) { Toast.makeText(this, "复制失败，可尝试导出报告", Toast.LENGTH_LONG).show(); }
        });
        findViewById(R.id.diagnostic_export).setOnClickListener(v -> {
            try { exporter.launch("DSHA-diagnostic-" + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + ".txt"); }
            catch (RuntimeException error) { Toast.makeText(this, "无法打开保存位置，请使用复制报告或检查系统文件管理器", Toast.LENGTH_LONG).show(); }
        });
        repository.report.observe(this, text -> ((TextView) findViewById(R.id.diagnostic_report)).setText(text));
        repository.busy.observe(this, busy -> {
            ((TextView) findViewById(R.id.diagnostic_status)).setText(busy ? "正在检查环境…" : "报告保留在本机，复制或导出后可用于反馈");
            for (int id : new int[]{R.id.diagnostic_refresh,R.id.diagnostic_repair,R.id.diagnostic_copy,R.id.diagnostic_export}) findViewById(id).setEnabled(!busy);
        });
        if (saved == null) repository.generate();
    }
    private String completeReport() {
        return SensitiveData.redact(String.valueOf(repository.report.getValue()) + "\n用户补充复现步骤：\n" + (steps == null ? "" : steps.getText().toString()));
    }
}
