package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static com.deepseekharness.app.util.BridgeQuestions.End.*;
import static org.junit.Assert.*;

public class BridgeQuestionsTest {
    @Test public void lateAnswerCannotCrossIntoNextQuestionOrReleaseIt() throws Exception {
        AtomicLong clock = new AtomicLong();
        BridgeQuestions questions = new BridgeQuestions(clock::get);
        BridgeQuestions.Request old = questions.begin(10);
        clock.set(TimeUnit.MILLISECONDS.toNanos(11));
        assertFalse(questions.answer(old, "过期答案")); assertEquals(TIMEOUT, questions.await(old));
        questions.release(old);
        BridgeQuestions.Request current = questions.begin(100);
        assertTrue(current.generation > old.generation);
        assertFalse(questions.answer(old, "迟到点击"));
        questions.cancel(old, DISMISSED); questions.release(old);
        assertNull(questions.begin(100)); assertTrue(questions.pending(current));
        assertTrue(questions.answer(current, "本轮答案")); assertEquals(ANSWER, questions.await(current));
        assertEquals("本轮答案", current.answer()); assertEquals("", old.answer());
    }

    @Test public void delayedUiShowCannotDisplayAnExpiredQuestion() {
        AtomicLong clock = new AtomicLong(); BridgeQuestions questions = new BridgeQuestions(clock::get);
        BridgeQuestions.Request request = questions.begin(120_000);
        clock.set(TimeUnit.SECONDS.toNanos(120));
        assertFalse(questions.pending(request)); assertEquals(TIMEOUT, request.end());
        assertFalse(questions.answer(request, "不应被接受"));
    }

    @Test public void onlyOneOfConcurrentClicksWinsAndCleanupCannotOverwriteIt() throws Exception {
        BridgeQuestions questions = new BridgeQuestions(); BridgeQuestions.Request request = questions.begin(5000);
        CountDownLatch go = new CountDownLatch(1), done = new CountDownLatch(8);
        AtomicInteger winners = new AtomicInteger();
        for (int i = 0; i < 8; i++) {
            final String answer = "选项" + i;
            Thread click = new Thread(() -> {
                try { go.await(); if (questions.answer(request, answer)) winners.incrementAndGet(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                finally { done.countDown(); }
            });
            click.setDaemon(true); click.start();
        }
        go.countDown(); assertTrue(done.await(2, TimeUnit.SECONDS)); assertEquals(1, winners.get());
        String selected = request.answer();
        questions.cancel(request, DISMISSED); questions.stop();
        assertEquals(ANSWER, questions.await(request)); assertEquals(selected, request.answer());
    }

    @Test public void lifecycleCancellationWakesWaiterWithoutInventingUserDismissal() throws Exception {
        BridgeQuestions questions = new BridgeQuestions(); BridgeQuestions.Request request = questions.begin(120_000);
        AtomicReference<BridgeQuestions.End> result = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try { result.set(questions.await(request)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        waiter.setDaemon(true); waiter.start();
        questions.cancel(request, BACKGROUND); waiter.join(1000);
        assertFalse(waiter.isAlive()); assertEquals(BACKGROUND, result.get());
        assertEquals("", request.answer()); assertFalse(questions.answer(request, "旧弹窗点击"));
    }

    @Test public void serviceStopReleasesWaitAndNewGenerationIgnoresOldCallback() throws Exception {
        BridgeQuestions questions = new BridgeQuestions(); BridgeQuestions.Request request = questions.begin(120_000);
        questions.stop(); assertEquals(STOPPED, questions.await(request)); questions.release(request);
        BridgeQuestions.Request next = questions.begin(1000);
        assertNotNull(next); assertFalse(questions.answer(request, "旧答复"));
        assertTrue(questions.answer(next, "")); assertEquals(ANSWER, questions.await(next));
    }

    @Test public void unansweredRequestReturnsAtItsRealDeadline() throws Exception {
        BridgeQuestions questions = new BridgeQuestions(); BridgeQuestions.Request request = questions.begin(30);
        long started = System.nanoTime();
        assertEquals(TIMEOUT, questions.await(request));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
        questions.release(request); assertNotNull(questions.begin(1000));
    }

    @Test public void cancelledRequestNeverSuppliesAnAnswerToItsSuccessor() throws Exception {
        BridgeQuestions questions = new BridgeQuestions(); BridgeQuestions.Request old = questions.begin(1000);
        assertNull(questions.begin(1000)); questions.cancel(old, DISMISSED);
        assertEquals(DISMISSED, questions.await(old)); questions.release(old);
        BridgeQuestions.Request next = questions.begin(1000);
        assertFalse(questions.answer(old, "过期")); questions.cancel(next, UNAVAILABLE);
        assertEquals(UNAVAILABLE, questions.await(next)); assertEquals("", next.answer());
    }
}
