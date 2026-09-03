package com.deepseekharness.app;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 「工作区 → ADB 一键配对」输入器（App 前台场景；通知卡片走 RemoteInput 就地输入）。
 *
 * 打开即 Nsd 自动发现配对/连接端口（发现到就免输端口）；
 * 输 6 位码 → 开始配对 → 后台 ensureReady(幂等) + pair → 成功直连 adbd 并保存连接端口。
 */
public class AdbPairActivity extends Activity {

    private TextView statusText;
    private EditText codeEt;
    private Button startBtn;

    private volatile int discoveredPairPort = 0;
    private volatile int discoveredConnPort = 0;
    private volatile boolean pairing = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DshaTheme.apply(this);
        DshaBackground.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        discoverPorts();
    }

    private View buildUi() {
        int pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, (int) (24 * getResources().getDisplayMetrics().density), pad, pad);

        TextView title = new TextView(this);
        title.setText("🔐 ADB 无线配对（免 Shizuku）");
        title.setTextSize(18);
        title.setTextColor(0xFF222222);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("手机：设置 → 开发者选项 → 无线调试 → 「使用配对码配对设备」。\n"
                + "输入屏幕上的 6 位码即可（端口自动发现，无需手填）。码是一次性的，请尽快。");
        hint.setTextSize(13);
        hint.setLineSpacing(4, 1f);
        hint.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(hint);

        codeEt = new EditText(this);
        codeEt.setHint("6 位配对码");
        codeEt.setInputType(InputType.TYPE_CLASS_NUMBER);
        codeEt.setGravity(Gravity.CENTER);
        codeEt.setTextSize(24);
        root.addView(codeEt);

        startBtn = new Button(this);
        startBtn.setText("⚡ 开始配对");
        startBtn.setAllCaps(false);
        startBtn.setOnClickListener(v -> startPair());
        root.addView(startBtn);

        statusText = new TextView(this);
        statusText.setText("正在自动发现端口…（若无则默认 5555）");
        statusText.setTextSize(13);
        statusText.setPadding(0, (int) (10 * getResources().getDisplayMetrics().density), 0, 0);
        root.addView(statusText);

        Button autoBtn = new Button(this);
        autoBtn.setText("⚡ 免手抄：自动读配对码");
        autoBtn.setOnClickListener(v -> onAutoReadClick());
        root.addView(autoBtn);

        Button advanced = new Button(this);
        advanced.setText("手动填端口（高级）");
        advanced.setAllCaps(false);
        advanced.setTextSize(12);
        advanced.setOnClickListener(v -> showManualPorts());
        root.addView(advanced);

        return root;
    }

    /** 「自动读配对码」：让无障碍服务从系统配对弹窗里直接读码，省掉手抄这一步。
     *  配对码只有两分钟有效期，抄错一位就得从头再来 —— 这是整条 ADB 通道最劝退的地方。 */
    private void onAutoReadClick() {
        if (!DshaAccessibilityService.enabled(this)) {
            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("需要先开启无障碍服务")
                    .setMessage("开启后 DSHA 能直接读出配对弹窗里的 6 位码并自动配对。\n\n"
                            + "隐私：可读范围在清单里已限定为系统「设置」应用，其他应用的画面"
                            + "系统不会投递给我们；而且只在你点过这个按钮之后的两分钟内才读取，"
                            + "读到配对码立即停止，不保存、不上传任何屏幕内容。\n\n"
                            + "在接下来的列表里找到「DSHA 配对助手」并打开即可。")
                    .setPositiveButton("去开启", (d, w) -> {
                        try {
                            startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
                        } catch (Throwable t) {
                            setStatus("打不开无障碍设置：" + t.getMessage());
                        }
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        DshaAccessibilityService.startWatch((code, ip, port) -> runOnUiThread(() -> {
            codeEt.setText(code);
            if (port != null && !port.isEmpty()) {
                try {
                    discoveredPairPort = Integer.parseInt(port);
                } catch (NumberFormatException ignored) {
                }
            }
            setStatus("已自动读到配对码 " + code
                    + (port == null || port.isEmpty() ? "" : "（配对端口 " + port + "）")
                    + "，正在配对…");
            startPair();
        }));
        setStatus("已开始监听（两分钟内有效）。请在系统页面点「使用配对码配对设备」，"
                + "弹窗一出现就会自动读码配对，不用回到这里。");
        try {
            // 直接跳无线调试页；个别机型没有这个 action，退回开发者选项
            startActivity(new android.content.Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Throwable t2) {
                setStatus("请手动打开：设置 → 开发者选项 → 无线调试");
            }
        }
    }

    @Override
    protected void onDestroy() {
        // 离开页面就关掉监听窗口，别让它白白挂着两分钟
        DshaAccessibilityService.stopWatch();
        super.onDestroy();
    }

    private void showManualPorts() {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        ll.setPadding(pad, 4, pad, 4);
        final EditText pairPort = new EditText(this);
        pairPort.setHint("配对端口（手机配对弹窗里的 :端口）");
        pairPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText connPort = new EditText(this);
        connPort.setHint("连接端口（默认 5555）");
        connPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (discoveredPairPort > 0) pairPort.setText(String.valueOf(discoveredPairPort));
        ll.addView(pairPort);
        ll.addView(connPort);
        new android.app.AlertDialog.Builder(this)
                .setTitle("手动端口")
                .setView(ll)
                .setPositiveButton("确定", (d, w) -> {
                    if (!pairPort.getText().toString().trim().isEmpty()) {
                        try {
                            discoveredPairPort = Integer.parseInt(pairPort.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                    }
                    if (!connPort.getText().toString().trim().isEmpty()) {
                        try {
                            discoveredConnPort = Integer.parseInt(connPort.getText().toString().trim());
                        } catch (Exception ignored) {
                        }
                    }
                    setStatus("已设置：配对端口=" + discoveredPairPort
                            + " 连接端口=" + (discoveredConnPort > 0 ? discoveredConnPort : 5555));
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void setStatus(String s) {
        if (statusText != null) statusText.setText(s);
    }

    private void startPair() {
        if (pairing) return;
        String code = codeEt.getText().toString().trim();
        if (code.length() < 6) {
            Toast.makeText(this, "配对码不足 6 位", Toast.LENGTH_SHORT).show();
            return;
        }
        pairing = true;
        startBtn.setEnabled(false);
        startBtn.setText("配对中…（首次自动装环境）");
        new Thread(() -> {
            final String out = doPair(code);
            runOnUiThread(() -> {
                pairing = false;
                startBtn.setEnabled(true);
                startBtn.setText("⚡ 开始配对");
                boolean ok = out.contains("PAIR_OK");
                setStatus(ok ? "🎉 配对成功！agent 可用：/root/dsh-bin/adb-shell \"id\"\n" + out : "\n" + out);
                if (!ok) {
                    Toast.makeText(AdbPairActivity.this,
                            "配对未成功（可能是码已失效，回到无线调试重新点「使用配对码配对设备」）",
                            Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    /** 一键流程：自动装环境（幂等，后台已预热则秒回）→ 自动发现端口 → 单次配对 */
    private String doPair(String code) {
        try {
            HarnessController c = HarnessController.get(this);
            if (c == null || !c.getProot().isInstalled()) {
                return "环境未安装，请先到「安装」页装好 deepseek-harness。";
            }
            String prep = AdbBridge.ensureReady(this, c.getProot());
            if (!prep.contains("SETUP_DONE")) {
                return "环境准备失败，详见输出：\n" + prep;
            }
            String pp = discoveredPairPort > 0 ? String.valueOf(discoveredPairPort) : "";
            String cp = discoveredConnPort > 0 ? String.valueOf(discoveredConnPort) : "";
            return AdbBridge.pair(c.getProot(), code, pp, cp, DeviceBridgeService.pairHost);
        } catch (Throwable e) {
            return "ERROR: " + e;
        }
    }

    /** NsdManager 自动发现无线调试配对/连接端口（发现到就不用手填） */
    private void discoverPorts() {
        try {
            NsdManager nm = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nm == null) return;
            discover(nm, "_adb-tls-pairing._tcp.", p -> {
                discoveredPairPort = p;
                runOnUiThread(() -> setStatus("检测到配对端口：" + p + "，直接输码即可 ~"));
            });
            discover(nm, "_adb-tls-connect._tcp.", p -> discoveredConnPort = p);
        } catch (Throwable ignored) {
        }
    }

    private void discover(final NsdManager nm, final String type, final java.util.function.IntConsumer sink) {
        try {
            final NsdManager.DiscoveryListener[] holder = new NsdManager.DiscoveryListener[1];
            holder[0] = new NsdManager.DiscoveryListener() {
                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo info) {
                    nm.resolveService(info, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                        }

                        @Override
                        public void onServiceResolved(NsdServiceInfo serviceInfo) {
                            final int p = serviceInfo.getPort();
                            if (p > 0) {
                                try {
                                    sink.accept(p);
                                } catch (Throwable ignored) {
                                }
                            }
                            try {
                                nm.stopServiceDiscovery(holder[0]);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo info) {
                }
            };
            nm.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, holder[0]);
        } catch (Throwable ignored) {
        }
    }
}
