package com.deepseekharness.app.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.BuildConfig;
import com.deepseekharness.app.R;
import com.deepseekharness.app.core.UpdateRepository;
import com.deepseekharness.app.util.UpdatePolicy;

/** 更新由用户选择通道和确认安装；下载任务不依赖页面生命周期。 */
public final class UpdateActivity extends AppCompatActivity {
    private UpdateRepository repository;
    private boolean resumeInstall;
    private boolean installDispatchReady;
    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved); setContentView(R.layout.activity_update);
        repository = new ViewModelProvider(this).get(UpdateRepository.class);
        resumeInstall = saved != null && saved.getBoolean("resumeInstall");
        repository.restoreInterruptedInstall(saved != null && saved.getBoolean("installPending"));
        ((TextView) findViewById(R.id.update_current)).setText(BuildConfig.VERSION_NAME + " · 版本码 " + BuildConfig.VERSION_CODE
                + (BuildConfig.LOW_ANDROID ? " · 兼容版" : " · 标准版"));
        RadioGroup channels = findViewById(R.id.update_channels);
        channels.check(UpdatePolicy.PREVIEW.equals(repository.channel()) ? R.id.update_preview : R.id.update_stable);
        channels.setOnCheckedChangeListener((g, id) -> repository.setChannel(id == R.id.update_preview ? UpdatePolicy.PREVIEW : UpdatePolicy.STABLE));
        findViewById(R.id.update_back).setOnClickListener(v -> finish());
        findViewById(R.id.update_check).setOnClickListener(v -> repository.check());
        findViewById(R.id.update_download).setOnClickListener(v -> {
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 104);
            else repository.download();
        });
        findViewById(R.id.update_cancel).setOnClickListener(v -> repository.cancel());
        findViewById(R.id.update_install).setOnClickListener(v -> install());
        findViewById(R.id.update_browser).setOnClickListener(v -> {
            UpdateRepository.State state = repository.state().getValue();
            AboutDialog.openBrowser(this, state != null && state.release != null ? state.release.pageUrl
                    : "https://github.com/qiannianhuanxiang/DSHA/releases");
        });
        repository.state().observe(this, this::renderState);
        repository.installation().observe(this, state -> {
            renderState(repository.state().getValue());
            dispatchInstall();
        });
        if (saved == null && !repository.hasTask()) repository.check();
    }
    private void renderState(UpdateRepository.State state) {
        if (state == null) return;
        UpdateRepository.InstallState install = repository.installation().getValue();
        UpdateUi.render(findViewById(android.R.id.content), state, install.pending(), install.verifying);
        if (install.pending()) {
            ((TextView) findViewById(R.id.update_status)).setText(install.verifying
                    ? "正在重新校验安装包…" : "校验完成，返回此页面后继续安装");
        } else if (install.error != null) {
            ((TextView) findViewById(R.id.update_status)).setText(state.message + "\n" + install.error);
        }
    }

    private void install() {
        if (repository.installationPending()) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                resumeInstall = true;
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()))); return;
            }
            repository.requestInstall();
        } catch (Exception error) { resumeInstall = false; showInstallError(error); }
    }
    private void dispatchInstall() {
        if (!installDispatchReady || isFinishing() || isDestroyed() || getSupportFragmentManager().isStateSaved()) return;
        java.io.File apk = repository.takeInstallReady();
        if (apk == null) return;
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updates", apk);
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception error) { showInstallError(error); }
    }
    private void showInstallError(Exception error) {
        repository.installFailed("无法安装：" + error.getMessage() + "；可重试");
        Toast.makeText(this, repository.installation().getValue().error, Toast.LENGTH_LONG).show();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 104) repository.download();
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        saved.putBoolean("resumeInstall", resumeInstall);
        saved.putBoolean("installPending", repository.installationPending());
        super.onSaveInstanceState(saved);
    }
    @Override protected void onResume() {
        super.onResume();
        if (resumeInstall) {
            resumeInstall = false;
            if (android.os.Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls()) install();
            else Toast.makeText(this, "未允许安装更新，可稍后重试", Toast.LENGTH_SHORT).show();
        }
    }
    @Override protected void onPostResume() {
        super.onPostResume();
        installDispatchReady = true;
        dispatchInstall();
    }
    @Override protected void onPause() {
        installDispatchReady = false;
        super.onPause();
    }
}
