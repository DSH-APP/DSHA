package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class InstallTaskTest {
    @Test public void simultaneousStartsAdmitExactlyOneTask() throws Exception {
        InstallTask task = new InstallTask();
        CountDownLatch start = new CountDownLatch(1); AtomicInteger admitted = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            Thread thread = new Thread(() -> { try { start.await(); if (task.start(false, 0)) admitted.incrementAndGet(); }
                catch (InterruptedException e) { throw new AssertionError(e); } });
            threads.add(thread); thread.start();
        }
        start.countDown(); for (Thread thread : threads) thread.join();
        assertEquals(1, admitted.get()); assertTrue(task.snapshot().busy());
    }
    @Test public void snapshotsCannotMutateTaskAndNewPageSeesResults() {
        InstallTask task = new InstallTask(); task.start(false, 0);
        task.stage(2, "检查工具", true); task.result(2, true, "已通过");
        InstallTask.Snapshot oldPage = task.snapshot(); oldPage.steps[1] = InstallTask.Step.FAILED;
        task.append("切页后继续输出"); task.finish(InstallTask.Outcome.SUCCEEDED, "全部完成");
        assertEquals(InstallTask.Step.OK, task.snapshot().steps[1]);
        assertTrue(task.snapshot().log.contains("切页后继续输出")); assertFalse(task.snapshot().busy());
    }
    @Test public void cancellationBlocksEnteringNextWriteStage() {
        InstallTask task = new InstallTask(); task.start(true, 0);
        task.stage(2, "正在修复", false); assertTrue(task.requestCancel());
        assertFalse(task.requestCancel()); assertFalse(task.snapshot().cancellable);
        assertTrue(task.snapshot().log.contains("安全收尾"));
        assertThrows(InstallTask.Cancelled.class, () -> task.stage(4, "不应执行", false));
        task.finish(InstallTask.Outcome.CANCELLED, "已停止"); assertFalse(task.snapshot().busy());
    }
    @Test public void exceptionCompletionReleasesTaskAndClearsOldCancellation() {
        InstallTask task = new InstallTask(); task.start(false, 3); task.stage(3, "检查 Node", true);
        task.requestCancel(); task.finish(InstallTask.Outcome.FAILED, "token=abc123 崩溃");
        assertEquals(InstallTask.Step.FAILED, task.snapshot().steps[2]);
        assertFalse(task.snapshot().failure.contains("abc123"));
        assertTrue(task.start(false, 1)); assertFalse(task.cancellationRequested());
        assertTrue(task.snapshot().log.isEmpty());
    }
    @Test public void redactsSecretsBeforeRetainingLogIncludingMultilineKeys() {
        InstallTask task = new InstallTask(); task.start(false, 0);
        task.append("API_KEY=sk-supersecret123456 https://user:p455@example.org?token=abc123");
        task.append("Authorization: Bearer xyz123");
        task.append("-----BEGIN PRIVATE KEY-----"); task.append("base64-private-material");
        task.append("-----END PRIVATE KEY-----"); task.append("后续正常输出");
        String log = task.snapshot().log;
        for (String secret : new String[]{"sk-supersecret123456", "p455", "abc123", "xyz123", "base64-private-material"}) assertFalse(log, log.contains(secret));
        assertTrue(log.contains("后续正常输出")); assertTrue(log.contains("私钥已隐藏"));
    }
    @Test public void logKeepsBoundedTailAndHidesWholeOversizedLines() {
        InstallTask task = new InstallTask(); task.start(false, 0);
        task.append("password='" + "s".repeat(5000) + "'");
        assertTrue(task.snapshot().log.contains("输出行过长"));
        for (int i = 0; i < 500; i++) task.append("行" + i + " " + "x".repeat(200));
        String log = task.snapshot().log; assertTrue(log.length() < 50 * 1024);
        assertTrue(log.contains("较早输出已省略")); assertTrue(log.contains("行499")); assertFalse(log.contains("行0 "));
    }
}
