package com.deepseekharness.app.util;

import java.util.ArrayDeque;
import java.util.Arrays;

/** 安装任务的线程安全状态；页面只读快照，销毁页面不能释放任务的单飞锁。 */
public final class InstallTask {
    public enum Outcome { IDLE, RUNNING, SUCCEEDED, FAILED, CANCELLED }
    public enum Step { PENDING, RUNNING, OK, FAILED, SKIPPED }
    public static final String[] NAMES = {"Linux 环境", "基础工具", "Node.js", "pnpm", "deepseek-harness", "运行补丁"};
    private static final int LOG_LIMIT = 48 * 1024;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    private final Step[] steps = new Step[6];
    private final String[] details = new String[6];
    private Outcome outcome = Outcome.IDLE;
    private boolean repair, cancelRequested, cancellable = true, privateKey, truncated;
    private int selected, current, logSize;
    private long started, stageStarted, finished, revision;
    private String phase = com.deepseekharness.app.util.UiText.text("尚未检查"), failure = "";
    // 页面每秒轮询一次；日志只有 append 时才变化，复用渲染结果避免反复拼接 48 KiB。
    private long cachedLogRevision = -1;
    private String cachedLog = "";

    public InstallTask() { Arrays.fill(steps, Step.PENDING); Arrays.fill(details, com.deepseekharness.app.util.UiText.text("尚未检查")); }

    public synchronized boolean start(boolean repair, int selected) {
        if (selected < 0 || selected > 6) throw new IllegalArgumentException(com.deepseekharness.app.util.UiText.text("安装步骤必须为 0—6"));
        if (outcome == Outcome.RUNNING) return false;
        this.repair = repair; this.selected = selected;
        outcome = Outcome.RUNNING; cancelRequested = false; cancellable = true;
        current = 0; started = stageStarted = System.nanoTime(); finished = 0;
        phase = com.deepseekharness.app.util.UiText.text("准备检查"); failure = ""; privateKey = truncated = false;
        lines.clear(); logSize = 0; Arrays.fill(steps, Step.PENDING); Arrays.fill(details, com.deepseekharness.app.util.UiText.text("尚未检查")); revision++;
        cachedLogRevision = -1; cachedLog = "";
        return true;
    }

    /** 与取消请求共用锁；取消已到达时不能再进入写入阶段。 */
    public synchronized void stage(int step, String label, boolean cancellable) {
        checkCancelled();
        current = step; phase = safe(label); stageStarted = System.nanoTime(); this.cancellable = cancellable;
        if (step > 0) { steps[step - 1] = Step.RUNNING; details[step - 1] = safe(label); }
        append(label); revision++;
    }

    public synchronized void result(int step, boolean ok, String detail) {
        steps[step - 1] = ok ? Step.OK : Step.FAILED;
        details[step - 1] = safe(detail);
        append((ok ? com.deepseekharness.app.util.UiText.text("通过 · ") : com.deepseekharness.app.util.UiText.text("未通过 · ")) + NAMES[step - 1] + com.deepseekharness.app.util.UiText.text("：") + detail); revision++;
    }

    public synchronized void skip(int step, String detail) {
        steps[step - 1] = Step.SKIPPED; details[step - 1] = safe(detail);
        append(com.deepseekharness.app.util.UiText.text("跳过 · ") + NAMES[step - 1] + com.deepseekharness.app.util.UiText.text("：") + detail); revision++;
    }

    public synchronized boolean requestCancel() {
        if (outcome != Outcome.RUNNING || cancelRequested) return false;
        cancelRequested = true;
        append(cancellable ? com.deepseekharness.app.util.UiText.text("已请求取消检查，正在结束探测进程…") : com.deepseekharness.app.util.UiText.text("已请求停止；等待当前修复完成安全收尾，再停止后续步骤。"));
        revision++; return true;
    }
    public synchronized boolean cancellationRequested() { return cancelRequested; }
    public synchronized void checkCancelled() { if (cancelRequested) throw new Cancelled(); }

    /** 取消后仍可报告必要收尾；此阶段不释放单飞或全局环境锁。 */
    public synchronized void cleaningUp(String detail) {
        phase = safe(detail); cancellable = false; stageStarted = System.nanoTime(); append(detail);
    }

    public synchronized void finish(Outcome result, String detail) {
        if (outcome != Outcome.RUNNING) return;
        outcome = result; finished = System.nanoTime(); phase = safe(detail); cancellable = false;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == Step.RUNNING || (steps[i] == Step.PENDING && (selected == 0 || selected == i + 1)
                    && (result == Outcome.FAILED || result == Outcome.CANCELLED))) {
                steps[i] = result == Outcome.FAILED ? Step.FAILED : Step.SKIPPED;
                details[i] = safe(com.deepseekharness.app.util.UiText.text("检查未完成：") + detail);
            }
        }
        if (result == Outcome.FAILED) failure = safe(detail);
        append(detail); revision++;
    }

    /** 只保留脱敏后的有界日志；私钥块必须跨行隐藏，不能逐行调用普通正则后直接保存。 */
    public synchronized void append(String value) {
        if (value == null) return;
        for (String line : value.split("\\r?\\n|\\r", -1)) {
            if (line.isEmpty()) continue;
            if (line.contains("-----BEGIN ") && line.contains("PRIVATE KEY-----")) {
                privateKey = !line.contains("-----END "); line = com.deepseekharness.app.util.UiText.text("[私钥已隐藏]");
            } else if (privateKey) {
                if (line.contains("-----END ") && line.contains("PRIVATE KEY-----")) privateKey = false;
                continue;
            }
            String entry = "[" + elapsed(System.nanoTime(), started) + "s] " + safe(line) + "\n";
            lines.addLast(entry); logSize += entry.length();
            while (logSize > LOG_LIMIT && !lines.isEmpty()) {
                logSize -= lines.removeFirst().length(); truncated = true;
            }
            revision++;
        }
    }

    private static String safe(String value) {
        if (value.contains("-----BEGIN ") && value.contains("PRIVATE KEY-----")) return com.deepseekharness.app.util.UiText.text("[私钥已隐藏]");
        // 超长行整体隐藏，避免截断破坏带引号凭据的识别边界。
        if (value.length() > 4096) return com.deepseekharness.app.util.UiText.text("[输出行过长，已隐藏]");
        return SensitiveData.redact(value.replaceAll("\\x1B\\[[0-?]*[ -/]*[@-~]", "")
                .replaceAll("[\\p{Cntrl}&&[^\\t]]", ""));
    }
    private static long elapsed(long now, long start) { return start == 0 ? 0 : Math.max(0, (now - start) / 1_000_000_000L); }

    public synchronized Snapshot snapshot() {
        long now = outcome == Outcome.RUNNING ? System.nanoTime() : finished;
        if (cachedLogRevision != revision) {
            StringBuilder log = new StringBuilder(truncated ? com.deepseekharness.app.util.UiText.text("[较早输出已省略，仅保留最近 48 KiB]\n") : "");
            for (String line : lines) log.append(line);
            cachedLog = log.toString();
            cachedLogRevision = revision;
        }
        return new Snapshot(outcome, repair, selected, current, cancelRequested, cancellable,
                safe(phase), failure, steps.clone(), details.clone(), cachedLog, elapsed(now, started),
                elapsed(now, stageStarted), revision);
    }

    public static final class Snapshot {
        public final Outcome outcome;
        public final boolean repair, cancelRequested, cancellable;
        public final int selected, current;
        public final String phase, failure, log;
        public final Step[] steps;
        public final String[] details;
        public final long elapsedSeconds, stageSeconds, revision;
        Snapshot(Outcome outcome, boolean repair, int selected, int current, boolean cancelRequested,
                 boolean cancellable, String phase, String failure, Step[] steps, String[] details, String log,
                 long elapsedSeconds, long stageSeconds, long revision) {
            this.outcome = outcome; this.repair = repair; this.selected = selected; this.current = current;
            this.cancelRequested = cancelRequested; this.cancellable = cancellable; this.phase = phase;
            this.failure = failure; this.steps = steps; this.details = details; this.log = log;
            this.elapsedSeconds = elapsedSeconds; this.stageSeconds = stageSeconds; this.revision = revision;
        }
        public boolean busy() { return outcome == Outcome.RUNNING; }
    }
    public static final class Cancelled extends RuntimeException {
        public Cancelled() { super(com.deepseekharness.app.util.UiText.text("任务已取消")); }
    }
}
