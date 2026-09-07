package com.deepseekharness.app.ui;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import com.deepseekharness.app.DeviceBridgeService;
import com.deepseekharness.app.DshaAccessibilityService;
import com.deepseekharness.app.bridge.AdbBridge;
import com.deepseekharness.app.bridge.LocalNetworkAccess;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.runtime.ProotBootstrap;
import com.deepseekharness.app.util.AdbResult;
import com.deepseekharness.app.util.AdbEnvironmentTask;
import com.deepseekharness.app.util.SensitiveData;

/** 配对任务由 ViewModel 持有；页面重建不重跑握手，不丢进度与结果。 */
public class AdbPairActivity extends androidx.appcompat.app.AppCompatActivity {
    private TextView statusText;
    private EditText codeEt;
    private Button startBtn, verifyBtn, autoBtn, manualBtn;
    private PairModel model;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        model = new ViewModelProvider(this).get(PairModel.class);
        model.restore(saved);
        setContentView(buildUi());
        model.status.observe(this, text -> {
            statusText.setText(text);
            startBtn.setEnabled(!model.busy);
            verifyBtn.setEnabled(!model.busy);
            autoBtn.setEnabled(!model.busy);
            manualBtn.setEnabled(!model.busy);
            codeEt.setEnabled(!model.busy);
            startBtn.setText(model.busy ? "正在处理，请等待结果…" : "开始配对");
            if (!codeEt.getText().toString().equals(model.code)) codeEt.setText(model.code);
        });
        if (!LocalNetworkAccess.granted(this)) requestNetworkPermission();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        model.save(out);
        super.onSaveInstanceState(out);
    }

    private void requestNetworkPermission() {
        model.message("无线 ADB 需要局域网权限，以发现配对端口并连接设备");
        requestPermissions(new String[]{LocalNetworkAccess.PERMISSION}, 37);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 37) model.message(LocalNetworkAccess.granted(this)
                ? "局域网权限已允许，请输入本次配对码后开始配对"
                : "未允许局域网访问，请在系统 DSHA 权限设置中允许后重试");
    }

    private View buildUi() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad * 2, pad, pad);
        TextView title = new TextView(this);
        title.setText("ADB 无线配对");
        title.setTextSize(20);
        title.setTextColor(getColor(com.deepseekharness.app.R.color.text));
        root.addView(title);
        TextView hint = new TextView(this);
        hint.setText("系统设置 → 开发者选项 → 无线调试 → 使用配对码配对设备。\n"
                + "输入本次的 6 位码，每次配对都会重新发现本机端口。首次准备环境可能较久，码过期后请重新获取。\n"
                + "配对端口与无线调试主页面的连接端口不同；已配对时可直接验证连接。");
        hint.setPadding(0, pad, 0, pad);
        hint.setTextSize(14);
        root.addView(hint);
        codeEt = new EditText(this);
        codeEt.setHint("6 位配对码");
        codeEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeEt.setGravity(Gravity.CENTER);
        codeEt.setTextSize(24);
        codeEt.setSaveEnabled(false); // 一次性配对码不进入磁盘上的 Activity 状态。
        codeEt.setText(model.code);
        codeEt.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { model.code = s.toString(); }
            @Override public void afterTextChanged(android.text.Editable e) { }
        });
        root.addView(codeEt);
        startBtn = button(root, "开始配对", v -> {
            if (!LocalNetworkAccess.granted(this)) requestNetworkPermission();
            else model.start(false, "", 0);
        });
        verifyBtn = button(root, "验证已有连接", v -> {
            if (!LocalNetworkAccess.granted(this)) requestNetworkPermission();
            else model.start(true, "", 0);
        });
        autoBtn = button(root, "自动读取本次配对码", v -> autoRead());
        button(root, "打开无线调试设置", v -> openWirelessSettings());
        manualBtn = button(root, "手动地址与端口", v -> manualPorts());
        statusText = new TextView(this);
        statusText.setTextSize(14);
        statusText.setTextIsSelectable(true);
        statusText.setPadding(0, pad, 0, pad);
        root.addView(statusText);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        return scroll;
    }

    private Button button(LinearLayout root, String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(click);
        root.addView(b);
        return b;
    }

    private void autoRead() {
        if (!LocalNetworkAccess.granted(this)) { requestNetworkPermission(); return; }
        if (!DshaAccessibilityService.enabled(this)) {
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("需要先开启无障碍服务")
                    .setMessage("开启后请返回这里，再点自动读取。仅在你发起后的两分钟内读取系统配对弹窗；配对码不保存、不上传。")
                    .setPositiveButton("去开启", (d, w) -> {
                        try { startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
                        catch (RuntimeException e) { model.message("无法打开无障碍设置，请手动进入系统设置开启"); }
                    }).setNegativeButton("取消", null).show();
            return;
        }
        model.watch();
        openWirelessSettings();
    }

    /** 直接打开与自动读码共用回退，兼容没有无线调试独立 Activity 的 MIUI。 */
    private void openWirelessSettings() {
        try { startActivity(new Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")); }
        catch (RuntimeException e) {
            try { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)); }
            catch (RuntimeException ignored) { model.message("请手动进入开发者选项 → 无线调试 → 使用配对码配对设备"); }
        }
    }

    private void manualPorts() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, 0);
        EditText host = new EditText(this);
        host.setHint("本机 IP（可留空自动选择）");
        host.setText(model.host);
        row.addView(host);
        EditText pp = new EditText(this);
        pp.setHint("配对弹窗端口（留空重新发现）");
        pp.setInputType(InputType.TYPE_CLASS_NUMBER);
        pp.setText(model.pairPort);
        row.addView(pp);
        EditText cp = new EditText(this);
        cp.setHint("主页面连接端口（留空重新发现）");
        cp.setInputType(InputType.TYPE_CLASS_NUMBER);
        cp.setText(model.connectPort);
        row.addView(cp);
        androidx.appcompat.app.AlertDialog dialog = new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("手动地址与端口").setView(row).setPositiveButton("保存", null)
                .setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            try {
                AdbResult.port(pp.getText().toString());
                AdbResult.port(cp.getText().toString());
                String address = host.getText().toString().trim();
                if (!address.isEmpty() && !AdbBridge.localAddresses().contains(address)) {
                    host.setError("请填写本机无线调试页面的 IP"); return;
                }
                model.host = address;
                model.pairPort = pp.getText().toString().trim();
                model.connectPort = cp.getText().toString().trim();
                model.message("手动设置已保存。配对端口只用于下一次尝试，之后重新发现；可留空恢复自动发现。");
                dialog.dismiss();
            } catch (IllegalArgumentException e) { pp.setError(e.getMessage()); cp.setError(e.getMessage()); }
        }));
        dialog.show();
    }

    /** 只持有 Application；自动读码及后台任务不会捕获旧 Activity。 */
    public static final class PairModel extends AndroidViewModel {
        final MutableLiveData<String> status = new MutableLiveData<>("输入配对码后开始；端口会重新发现。");
        final Handler main = new Handler(Looper.getMainLooper());
        volatile boolean busy, cleared;
        String code = "", host = "", pairPort = "", connectPort = "";
        private Thread task;
        private boolean restored;
        private long watchEpoch;
        public PairModel(Application app) { super(app); }
        void message(String text) { if (!cleared) status.postValue(SensitiveData.redact(text)); }

        void save(Bundle out) {
            out.putBoolean("adb-pair-busy", busy);
            out.putString("adb-pair-status", status.getValue());
            out.putString("adb-pair-host", host);
            out.putString("adb-pair-port", pairPort);
            out.putString("adb-connect-port", connectPort);
        }
        void restore(Bundle saved) {
            if (restored) return;
            restored = true;
            if (saved == null) return;
            host = saved.getString("adb-pair-host", "");
            pairPort = saved.getString("adb-pair-port", "");
            connectPort = saved.getString("adb-connect-port", "");
            status.setValue(saved.getBoolean("adb-pair-busy")
                    ? "配对任务被系统中断，结果尚不确定。请先验证已有连接；若仍未配对，再获取新配对码。"
                    : saved.getString("adb-pair-status", "请输入本次配对码"));
        }
        void watch() {
            final long epoch = ++watchEpoch;
            message("已开始监听，两分钟内有效。请打开系统的「使用配对码配对设备」弹窗。");
            DshaAccessibilityService.startWatch((value, address, port) -> main.post(() -> {
                if (cleared || busy || epoch != watchEpoch) return;
                code = value;
                int p;
                try { p = AdbResult.port(port); } catch (IllegalArgumentException e) { p = 0; }
                start(false, address, p);
            }));
            main.postDelayed(() -> {
                if (!cleared && !busy && epoch == watchEpoch) {
                    watchEpoch++;
                    DshaAccessibilityService.stopWatch();
                    message("自动读码已到期；请重新点自动读取，或手动输入本次配对码。");
                }
            }, 120_000);
        }

        void start(boolean verify, String readHost, int readPort) {
            if (busy || cleared) return;
            // 仅拦截 Android 11+ 明确关闭的无线调试；读不到或旧系统仍走真实连接验证。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                int wireless = -1;
                try {
                    wireless = android.provider.Settings.Global.getInt(
                            getApplication().getContentResolver(), "adb_wifi_enabled", -1);
                } catch (RuntimeException ignored) { }
                if (wireless == 0) {
                    watchEpoch++;
                    DshaAccessibilityService.stopWatch();
                    String detail = "无线调试已关闭，请先开启";
                    DeviceBridgeService.adbDetail = detail;
                    DeviceBridgeService.adbState = "need_manual";
                    status.setValue(detail);
                    return;
                }
            }
            if (!verify && !AdbResult.code(code.trim())) { message("配对码必须恰好为 6 位数字"); return; }
            if (!LocalNetworkAccess.granted(getApplication())) { message("局域网权限未允许，请先在 DSHA 权限设置中允许"); return; }
            final String value = code.trim();
            final String savedPairPort = pairPort;
            final String pp = readPort > 0 ? String.valueOf(readPort) : pairPort;
            final String cp = connectPort;
            final String selectedHost = readPort > 0 ? readHost : host;
            pairPort = ""; // 一次性配对端口不跨请求复用，旧弹窗关闭后必须重新发现。
            busy = true;
            watchEpoch++;
            status.setValue(verify ? "正在准备已有连接验证…" : "正在准备配对环境…");
            DshaAccessibilityService.stopWatch();
            task = new Thread(() -> {
                String result;
                try {
                    result = AdbBridge.runEnvironmentTask(getApplication(), verify ? "ADB 验证完整任务" : "ADB 配对完整任务",
                            () -> perform(verify, value, pp, cp, selectedHost));
                } catch (AdbEnvironmentTask.Busy e) {
                    if (!cleared) {
                        DeviceBridgeService.adbDetail = e.getMessage();
                        DeviceBridgeService.adbState = "environment_busy";
                    }
                    result = "ENVIRONMENT_BUSY: " + e.getMessage();
                } catch (Throwable e) {
                    result = "ADB 操作未完成：" + SensitiveData.redact(String.valueOf(e));
                } finally {
                    // finally 只处理取消路径；完成路径统一在主线程更新所有 UI 状态。
                    if (cleared) busy = false;
                }
                final String finished = result;
                main.post(() -> {
                    if (cleared) return;
                    if (AdbResult.marker(finished, "ENVIRONMENT_BUSY")) pairPort = savedPairPort;
                    else code = "";
                    busy = false;
                    status.setValue(SensitiveData.redact(finished));
                });
            }, "adb-pair-task");
            task.start();
        }

        /** 整段由同一 Lease.run 执行，准备、发现、配对、验证及授权期间不允许移动环境。 */
        private String perform(boolean verify, String value, String pp, String cp, String selectedHost) throws Exception {
            if (cleared || Thread.currentThread().isInterrupted()) return "ADB 操作已取消";
            try (com.deepseekharness.app.core.RuntimeTasks ignored = com.deepseekharness.app.core.RuntimeTasks.begin()) {
                ProotBootstrap proot = new HarnessController(getApplication()).proot();
                if (!proot.isEnvironmentReady()) throw new IllegalStateException("环境未就绪，请先完成环境安装");
                String prep = AdbBridge.ensureReady(getApplication(), proot, this::message);
                if (AdbResult.marker(prep, "ENVIRONMENT_BUSY")) throw new AdbEnvironmentTask.Busy(prep);
                if (!AdbResult.marker(prep, "SETUP_DONE")) throw new IllegalStateException(prep);
                if (cleared || Thread.currentThread().isInterrupted()) return "ADB 操作已取消";
                String address = selectedHost == null ? "" : selectedHost;
                String port = pp;
                if (!verify && port.isEmpty()) {
                    message("正在重新发现本机配对端口（最多 6 秒）…");
                    AdbBridge.Endpoint endpoint = AdbBridge.discover(getApplication(), "_adb-tls-pairing._tcp.", 6000, this::message);
                    if (endpoint != null) { address = endpoint.host; port = String.valueOf(endpoint.port); }
                }
                if (cleared || Thread.currentThread().isInterrupted()) return "ADB 操作已取消";
                message(verify ? "正在验证已配对的设备连接，最多约 1 分钟…"
                        : "正在完成一次配对握手并验证连接，最多约 2 分钟；随后检查自动恢复授权…");
                String out = verify ? AdbBridge.verify(proot, cp, address) : AdbBridge.pair(proot, value, port, cp, address);
                if (AdbResult.marker(out, "ENVIRONMENT_BUSY")) throw new AdbEnvironmentTask.Busy(out);
                AdbResult.PairState state = AdbResult.pairState(out);
                DeviceBridgeService.recordPairResult(getApplication(), state, out);
                if (state == AdbResult.PairState.CONNECTED) return "连接已验证，ADB 设备命令可用。\n\n" + out;
                else if (state == AdbResult.PairState.PAIRED) return "配对已完成，连接尚未验证。请检查连接端口后点「验证已有连接」，无需再次配对。\n\n" + out;
                else return (verify ? "连接验证未通过。\n\n" : "配对未完成，请按以下原因处理。\n\n") + out;
            }
        }

        @Override protected void onCleared() {
            cleared = true;
            watchEpoch++;
            code = "";
            DshaAccessibilityService.stopWatch();
            main.removeCallbacksAndMessages(null);
            if (task != null) task.interrupt();
        }
    }
}
