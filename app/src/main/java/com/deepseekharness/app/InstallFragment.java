package com.deepseekharness.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

/** 安装模块：分步安装（rootfs / 基础工具 / Node / harness），多源测速，一键补装 */
public class InstallFragment extends Fragment {

    private HarnessController c;
    private TextView statusText, progressText, errorText, stepStatusText;
    private Button installBtn, uninstallBtn, copyBtn, crashBtn;
    private Button step1Btn, step2Btn, step3Btn, step4Btn, step5Btn, step6Btn;
    private ProgressBar progressBar;
    private AlertDialog sourceDialog;
    private final java.util.concurrent.atomic.AtomicBoolean stepCheckRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private boolean initialRefreshed = false;

    private final HarnessController.StateListener stateListener = this::refreshFromState;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_install, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        c = HarnessController.get(requireContext());
        statusText = view.findViewById(R.id.install_status);
        progressText = view.findViewById(R.id.install_progress);
        errorText = view.findViewById(R.id.install_error);
        stepStatusText = view.findViewById(R.id.install_steps);
        installBtn = view.findViewById(R.id.install_btn);
        uninstallBtn = view.findViewById(R.id.install_uninstall);
        copyBtn = view.findViewById(R.id.install_copy);
        crashBtn = view.findViewById(R.id.install_crash);
        progressBar = view.findViewById(R.id.install_progressbar);
        step1Btn = view.findViewById(R.id.install_step1);
        step2Btn = view.findViewById(R.id.install_step2);
        step3Btn = view.findViewById(R.id.install_step3);
        step4Btn = view.findViewById(R.id.install_step4);
        step5Btn = view.findViewById(R.id.install_step5);
        step6Btn = view.findViewById(R.id.install_step6);
        SubPageBack.bind(this, view);

        c.addStateListener(stateListener);

        installBtn.setOnClickListener(v -> {
            if (c.getApiKey().isEmpty()) {
                // 允许跳过：dsh 的 WebUI 自己就能配服务商（官方或第三方 base_url），
                // 在这里硬拦着反而让「只想先装环境」的用户走不下去
                new AlertDialog.Builder(requireContext())
                        .setTitle("还没填 API key")
                        .setMessage("可以直接跳过。\n\n"
                                + "装好之后打开 DeepSeek Harness 的 WebUI，"
                                + "在里面的设置里可以自行配置官方或第三方 API"
                                + "（自定义接口地址、模型名、密钥都行）。\n\n"
                                + "跳过时 .env 不会写入 key，一切交给 WebUI 里的设置。")
                        .setPositiveButton("跳过，直接安装", (d, w) -> c.install())
                        .setNeutralButton("去填 key", (d, w) ->
                                requireActivity().getSupportFragmentManager().beginTransaction()
                                        .replace(R.id.fragment_container, new ConfigFragment())
                                        .addToBackStack("config")
                                        .commit())
                        .setNegativeButton("取消", null)
                        .show();
                return;
            }
            c.install();
        });

        step1Btn.setOnClickListener(v -> c.installStep(HarnessController.STEP_ROOTFS));
        step2Btn.setOnClickListener(v -> c.installStep(HarnessController.STEP_TOOLS));
        step3Btn.setOnClickListener(v -> c.installStep(HarnessController.STEP_NODE));
        step4Btn.setOnClickListener(v -> c.installStep(HarnessController.STEP_PNPM));
        step5Btn.setOnClickListener(v -> c.installStep(HarnessController.STEP_HARNESS));
        step6Btn.setOnClickListener(v -> c.installStep(HarnessController.STEP_GUARD));

        uninstallBtn.setOnClickListener(v -> {
            // 卸载整目录可能耗时（删除 rootfs 几十秒），后台执行避免 UI 卡死
            installBtn.setEnabled(false);
            uninstallBtn.setEnabled(false);
            statusText.setText("正在清除环境…");
            new Thread(() -> {
                c.getProot().uninstall();
                // 环境已删：步骤缓存强制失效（否则 5s 内 UI 仍显示"已安装"）
                c.invalidateSteps();
                // 清除环境要删掉整个 rootfs（几 GB、几十秒），这期间用户很可能已经切走页面。
                // 那时 requireActivity()/requireContext() 会抛 IllegalStateException，
                // 碰 view 会 NPE —— 表现为「清完环境 App 闪退」。
                final android.app.Activity act = getActivity();
                if (act == null || !isAdded()) return;
                act.runOnUiThread(() -> {
                    if (!isAdded()) return;
                    installBtn.setEnabled(true);
                    uninstallBtn.setEnabled(true);
                    Toast.makeText(act, "已清除环境", Toast.LENGTH_SHORT).show();
                    refreshFromState();
                });
            }).start();
        });

        copyBtn.setOnClickListener(v -> {
            String err = c.getError();
            if (err == null || err.isEmpty()) {
                DshaSnack.show(InstallFragment.this, "当前没有报错内容");
                return;
            }
            ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("dsh_error", err));
            DshaSnack.show(InstallFragment.this, "报错内容已复制");
        });
        // 状态列表点击 → 手动刷新（切模块回来不自动刷，需要时点这里）
        stepStatusText.setOnClickListener(v -> {
            initialRefreshed = true;
            refreshFromState();
        });

        crashBtn.setOnClickListener(v -> {
            java.io.File f = new java.io.File(requireContext().getFilesDir(), "crash.log");
            if (!f.exists() || f.length() == 0) {
                DshaSnack.show(InstallFragment.this, "没有崩溃日志（尚未发生过闪退）");
                return;
            }
            String content;
            try {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r");
                long start = Math.max(0, raf.length() - 2000);
                raf.seek(start);
                byte[] buf = new byte[(int) (raf.length() - start)];
                raf.readFully(buf);
                raf.close();
                content = new String(buf, "UTF-8");
            } catch (Exception e) {
                content = "读取失败: " + e.getMessage();
            }
            final String finalContent = content;
            new android.app.AlertDialog.Builder(requireContext())
                    .setTitle("最近崩溃日志")
                    .setMessage(finalContent)
                    .setNegativeButton("复制", (d, w) -> {
                        ClipboardManager cm = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("dsh_crash", finalContent));
                        DshaSnack.show(InstallFragment.this, "已复制，发给开发者即可");
                    })
                    .setNeutralButton("清空日志", (d, w) -> {
                        try {
                            java.io.FileOutputStream fo = new java.io.FileOutputStream(f, false);
                            fo.close();
                            DshaSnack.show(InstallFragment.this, "崩溃日志已清空");
                        } catch (Exception e) {
                            DshaSnack.show(InstallFragment.this, "清空失败: " + e.getMessage());
                        }
                    })
                    .setPositiveButton("关闭", null)
                    .show();
        });

        refreshFromState();
    }

    @Override
    public void onResume() {
        super.onResume();
        // 切模块回来不自动重刷（proot 检查开销大）：只在首次进入时刷新；
        // 状态列表文字可点击手动刷新
        if (c != null && !initialRefreshed) {
            initialRefreshed = true;
            refreshFromState();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (c != null) c.removeStateListener(stateListener);
    }

    private void refreshFromState() {
        if (!isAdded()) return;
        // 先快速刷新不依赖步骤状态的部分（err 展示 / 进度显示 / 按钮可用性）
        String err = c.getError();
        if (err != null && !err.isEmpty()) {
            errorText.setVisibility(View.VISIBLE);
            copyBtn.setVisibility(View.VISIBLE);
            crashBtn.setVisibility(View.VISIBLE);
            errorText.setText(err);
            progressBar.setVisibility(View.GONE);
            progressText.setVisibility(View.GONE);
        } else {
            errorText.setVisibility(View.GONE);
            copyBtn.setVisibility(View.GONE);
            crashBtn.setVisibility(View.VISIBLE); // 崩溃日志按钮常驻，随时可查
            if (c.isBusy()) {
                progressBar.setVisibility(View.VISIBLE);
                progressText.setVisibility(View.VISIBLE);
                int p = Math.max(0, Math.min(100, c.getPercent()));
                progressBar.setProgress(p);
                progressText.setText(stageDisplay(p));
            } else {
                progressBar.setVisibility(View.GONE);
                String msg = c.getMessage();
                if (msg != null && !msg.isEmpty()) {
                    progressText.setVisibility(View.VISIBLE);
                    progressText.setText(msg);
                } else {
                    progressText.setVisibility(View.GONE);
                }
            }
        }
        boolean running = c.isBusy();
        installBtn.setEnabled(!running);
        step1Btn.setEnabled(!running);
        step2Btn.setEnabled(!running);
        step3Btn.setEnabled(!running);
        step4Btn.setEnabled(!running);
        step5Btn.setEnabled(!running);
        step6Btn.setEnabled(!running);
        uninstallBtn.setEnabled(!running);
        // 步骤状态：主线程先用缓存立即画（零耗时不卡），后台线程补算最新值再刷
        boolean[] cached = c.peekStepCache();
        boolean[] cachedUp = c.peekUpdatableCache();
        refreshSteps(cached, cachedUp);
        refreshStatus(cached);
        if (stepCheckRunning.getAndSet(true)) return;
        new Thread(() -> {
            boolean[] done = c.stepDoneSnapshot();
            if (!isAdded()) {
                stepCheckRunning.set(false);
                return;
            }
            final android.app.Activity act = getActivity();
            if (act == null) { // 极端情况：isAdded 与 getActivity 间 Activity 被回收
                stepCheckRunning.set(false);
                return;
            }
            act.runOnUiThread(() -> {
                stepCheckRunning.set(false);
                if (!isAdded()) return;
                refreshSteps(done, c.peekUpdatableCache());
                refreshStatus(done);
            });
        }).start();
        if (c.isAwaitingSourceChoice()) showSourceDialog();
    }

    /** 测速完成：弹窗让用户自选下载源 */
    private void showSourceDialog() {
        if (sourceDialog != null && sourceDialog.isShowing()) return;
        String[] labels = c.getPendingSourceLabels();
        if (labels.length == 0) return;
        int defaultIdx = Math.max(0, c.getPendingDefaultIndex());
        final int[] sel = {defaultIdx};
        sourceDialog = new AlertDialog.Builder(requireContext())
                .setTitle("选择下载源（已测速）")
                .setSingleChoiceItems(labels, defaultIdx, (d, which) -> sel[0] = which)
                .setPositiveButton("就用这个源", (d, which) -> {
                    c.onSourceChosen(sel[0]);
                    sourceDialog = null;
                })
                .setNegativeButton("自动选最快", (d, which) -> {
                    c.onSourceChosen(-1);
                    sourceDialog = null;
                })
                .setOnCancelListener(d -> {
                    c.onSourceChosen(-1);
                    sourceDialog = null;
                })
                .show();
    }

    /** 更新 4 个步骤的状态显示（复用批量查询结果，不再逐一查询） */
    private void refreshSteps(boolean[] done, boolean[] upd) {
        step1Btn.setText(stepLabel(done, upd, HarnessController.STEP_ROOTFS));
        step2Btn.setText(stepLabel(done, upd, HarnessController.STEP_TOOLS));
        step3Btn.setText(stepLabel(done, upd, HarnessController.STEP_NODE));
        // 修正 123546 bug（对齐 fixed45）：按钮4=④ pnpm，按钮5=⑤ harness
        step4Btn.setText(stepLabel(done, upd, HarnessController.STEP_PNPM));
        step5Btn.setText(stepLabel(done, upd, HarnessController.STEP_HARNESS));
        step6Btn.setText(stepLabel(done, upd, HarnessController.STEP_GUARD));
        // 修正 123546 bug（对齐 fixed45）：按钮4=④ pnpm，按钮5=⑤ harness，按钮6=⑥ 安全与补丁
        stepStatusText.setText(
                "① Linux 环境（rootfs）   " + mark(done, upd, HarnessController.STEP_ROOTFS) + "\n" +
                "② 基础工具（apt）       " + mark(done, upd, HarnessController.STEP_TOOLS) + "\n" +
                "③ Node.js               " + mark(done, upd, HarnessController.STEP_NODE) + "\n" +
                "④ Node 附加(pnpm/node-gyp) " + mark(done, upd, HarnessController.STEP_PNPM) + "\n" +
                "⑤ deepseek-harness      " + mark(done, upd, HarnessController.STEP_HARNESS) + "\n" +
                "⑥ 安全与补丁            " + mark(done, upd, HarnessController.STEP_GUARD));
    }

    private String mark(boolean[] done, boolean[] upd, int step) {
        if (c.isBusy() && c.getCurrentStep() == step) return "⏳ 进行中";
        if (step >= 1 && step < done.length && done[step]) return "✅ 已就绪";
        // 装了旧版但未达标（如 dsh rc.6 < rc.8）→ 提示可更新而非"未安装"
        if (step >= 1 && step < upd.length && upd[step]) return "⭐ 可更新";
        return "⬜ 未安装";
    }

    private String stepLabel(boolean[] done, boolean[] upd, int step) {
        String name = HarnessController.stepName(step);
        if (step >= 1 && step < done.length && done[step]) return "重装 " + name;
        if (step >= 1 && step < upd.length && upd[step]) return "⭐ 更新 " + name;
        return "安装 " + name;
    }

    /** 进度文案：stage 已含 %（下载那种）则不重复拼接百分比 */
    private String stageDisplay(int p) {
        String s = c == null ? null : c.getStage();
        if (s != null && s.contains("%")) return s;
        return (s == null ? "" : s) + (p >= 0 ? " " + p + "%" : "");
    }

    private void refreshStatus(boolean[] done) {
        int cnt = 0;
        for (int s = HarnessController.STEP_ROOTFS; s <= HarnessController.STEP_GUARD; s++) {
            if (s < done.length && done[s]) cnt++;
        }
        // 正在跑安装/自动重跑⑥时，别报"全部安装完成"——步骤标记是上一轮留下的，
        // 后台其实还在更新守卫和内置插件，此时说完成会让人以为可以直接开 WebUI。
        if (c != null && c.isBusy()) {
            String stage = c.getStage() == null ? "" : c.getStage();
            statusText.setText("🔄 正在安装/更新" + (stage.isEmpty() ? "" : "：" + stage)
                    + "\n\n完成前请勿启动 Web UI。");
            installBtn.setText("安装中…");
            return;
        }
        if (cnt == 6) {
            statusText.setText("✅ 全部安装完成\n\n可到「启动」页启动 Web UI。");
            installBtn.setText("重新安装（补装缺失步骤）");
        } else if (cnt > 0) {
            statusText.setText("🔄 已完成 " + cnt + "/6 步，可一键补装剩余步骤。");
            installBtn.setText("一键安装剩余步骤");
        } else {
            statusText.setText("📦 尚未安装\n\n点击下方按钮：\n一键安装 = 按顺序补装 4 个步骤\n也可单独安装某一步\n（约需 5~15 分钟，请保持网络畅通）");
            installBtn.setText("一键安装");
        }
    }
}
