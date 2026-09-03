package com.deepseekharness.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 内置环境解压页：欢迎页之后<strong>必须</strong>经过这里。
 * 找不到包就停在本页把诊断打出来，绝不再偷偷跳去安装页。
 */
public class ExtractActivity extends AppCompatActivity {

    private TextView statusText;
    private TextView errorText;
    private TextView detailText;
    private ProgressBar bar;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DshaTheme.apply(this);
        DshaBackground.apply(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_extract);

        // 解压 300MB 要好几分钟，而这期间屏幕一灭，本 Activity 就进 stopped、进程优先级
        // 掉下去 —— 内存紧张时被 LMK 回收，用户看到的就是「装了新版特别容易闪退」。
        // 亮屏标志让用户盯着进度时不熄屏；PARTIAL_WAKE_LOCK 兜住万一真熄了屏的情况，
        // 保证 CPU 不休眠、解压能跑完。acquire 带 20 分钟超时，不必手动 release，
        // 免得漏在某条异常路径上（解压最慢的机型实测也远小于这个数）。
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                android.os.PowerManager.WakeLock wl =
                        pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "dsha:extract");
                wl.setReferenceCounted(false);
                wl.acquire(20 * 60 * 1000L);
            }
        } catch (Throwable e) {
            android.util.Log.w("DSHA", "解压期间拿不到 WakeLock: " + e);
        }

        statusText = findViewById(R.id.extract_status);
        errorText = findViewById(R.id.extract_error);
        detailText = findViewById(R.id.extract_detail);
        bar = findViewById(R.id.extract_bar);
        progressBar = findViewById(R.id.extract_progress);
        bar.setVisibility(ProgressBar.VISIBLE);

        String ver = "unknown";
        try {
            ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        // force_extract = 离线包升级重解压（跳过"已解压"短路，强制重新解压新内置包；
        // extractOfflineBundle 内部带 .dsh/.env 数据保护自动还原）
        boolean force = getIntent().getBooleanExtra("force_extract", false);
        statusText.setText("DSHA v" + ver + (force ? "\n正在升级内置环境…" : "\n正在检查内置环境…"));

        startExtraction(force);
    }

    private void startExtraction(final boolean force) {
        ProotBootstrap proot = new ProotBootstrap(this);
        new Thread(() -> {
            try {
                if (!force && proot.isOfflineExtracted()) {
                    runOnUiThread(() -> statusText.setText("内置环境已就绪"));
                    Thread.sleep(400);
                    proceed();
                    return;
                }
                // ===== 高危：重解压（force）前必须先深停 Web UI + 停掉保活服务 =====
                // rootfs 将被整体删除，node 进程还在跑会：
                //  1) 删到一半 node 崩溃，看门狗可能尝试重启（碰半删的 rootfs）
                //  2) 写对话/日志时文件被删 → 数据保护备份的 .dsh 可能不完整
                // 必须先 stopWebAndWait（等端口关透）+ 停 HarnessService（否则其
                // keepAlive 线程 15s 发现端口挂了会自动拉起 startWeb，干扰重解压）。
                if (force) {
                    HarnessController c = HarnessController.get(this);
                    if (c.isWebRunning()) {
                        runOnUiThread(() -> statusText.setText("正在停止 Web UI…"));
                        c.stopWebAndWait();
                    }
                    try {
                        Intent stopSvc = new Intent(this, HarnessService.class)
                                .setAction(HarnessService.ACTION_STOP);
                        startService(stopSvc); // ACTION_STOP → stopKeepAlive + stopSelf
                    } catch (Throwable ignored) {
                    }
                }
                if (!proot.hasOfflineBundle()) {
                    final String diag = proot.diagnoseBundle();
                    runOnUiThread(() -> {
                        bar.setVisibility(ProgressBar.GONE);
                        progressBar.setVisibility(ProgressBar.GONE);
                        detailText.setVisibility(TextView.GONE);
                        errorText.setVisibility(TextView.VISIBLE);
                        errorText.setText("APK 里没找到内置环境包。\n"
                                + "请确认安装的是 Actions 里解压出来的 app-debug.apk。\n\n"
                                + diag);
                        statusText.setText("无法解压");
                    });
                    return;
                }
                runOnUiThread(() -> {
                    statusText.setText("正在解压内置环境…");
                    progressBar.setVisibility(ProgressBar.VISIBLE);
                    detailText.setVisibility(TextView.VISIBLE);
                    detailText.setText("准备中…");
                });
                // 进度回调节流：每 400ms 才刷新一次 UI（解压 306MB 每秒几十次回调，
                // 全刷 setText 会把 UI 线程塞爆 → 进度条卡顿/掉帧）。
                // 速率与剩余时间用 RateMeter 平滑，避免数字每次都乱跳。
                final long[] lastUi = {0};
                final HarnessController.RateMeter meter = new HarnessController.RateMeter();
                final long startedAt = System.currentTimeMillis();
                proot.extractOfflineBundle((done, total) -> {
                    final double rate = meter.feed(done);
                    long now = System.currentTimeMillis();
                    if (now - lastUi[0] < 400) return;
                    lastUi[0] = now;
                    final long eta = meter.eta(done, total);
                    final int per1000 = total > 0 ? (int) (done * 1000 / total) : 0;
                    final String detail = (total > 0
                            ? HarnessController.fmtBytes(done) + " / " + HarnessController.fmtBytes(total)
                            : HarnessController.fmtBytes(done) + " 已解压")
                            + " · " + HarnessController.fmtRate(rate)
                            + (eta >= 0 ? " · 剩余约 " + HarnessController.fmtEta(eta) : "");
                    runOnUiThread(() -> {
                        if (total > 0) {
                            progressBar.setIndeterminate(false);
                            progressBar.setProgress(per1000);
                            statusText.setText("正在解压环境… " + (per1000 / 10) + "%");
                        } else {
                            progressBar.setIndeterminate(true);
                            statusText.setText("正在解压环境…");
                        }
                        detailText.setText(detail);
                    });
                });
                final long spent = (System.currentTimeMillis() - startedAt) / 1000;
                runOnUiThread(() -> {
                    progressBar.setIndeterminate(false);
                    progressBar.setProgress(1000);
                    statusText.setText("环境准备完成");
                    detailText.setText("共用时 " + HarnessController.fmtEta(spent));
                });
                Thread.sleep(300);
                proceed();
            } catch (Exception e) {
                final String diag = proot.diagnoseBundle() + "\n\n" + proot.diagnoseRootfs();
                runOnUiThread(() -> {
                    bar.setVisibility(ProgressBar.GONE);
                    progressBar.setVisibility(ProgressBar.GONE);
                    detailText.setVisibility(TextView.GONE);
                    errorText.setVisibility(TextView.VISIBLE);
                    errorText.setText("解压失败：" + e.getMessage() + "\n\n" + diag);
                    statusText.setText("解压失败（本页不会自动跳走）");
                });
            }
        }, "extract-offline").start();
    }

    private void proceed() {
        runOnUiThread(() -> {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("skip_extract", true);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        });
    }
}
