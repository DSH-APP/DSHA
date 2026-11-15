package com.termux.terminal;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.Test;

public class TerminalInputQueueTest {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test public void fifoCopiesInputAndRejectsWholeEntryWhenFull() throws Exception {
        TerminalInputQueue q = new TerminalInputQueue(8);
        byte[] first = bytes("abc");
        assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(first, 0, first.length));
        first[0] = 'x';
        byte[] inFlight = q.take();
        assertArrayEquals(bytes("abc"), inFlight);
        assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("de"), 0, 2));
        assertEquals(TerminalInputQueue.Offer.FULL, q.offer(bytes("1234"), 0, 4));
        assertEquals(TerminalInputQueue.Offer.BLOCKED, q.offer(bytes("x"), 0, 1));
        assertFalse(q.resumeAfterOverflow());
        q.completed(inFlight);
        byte[] second = q.take();
        assertArrayEquals(bytes("de"), second);
        q.completed(second);
        assertTrue(q.resumeAfterOverflow());
        assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("1234"), 0, 4));
        byte[] third = q.take();
        assertArrayEquals(bytes("1234"), third);
        q.completed(third);
    }

    @Test public void stalledConsumerNeverBlocksProducerAndCloseDiscardsOnlyPending() throws Exception {
        TerminalInputQueue q = new TerminalInputQueue(4);
        assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("ab"), 0, 2));
        byte[] inFlight = q.take();
        assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("cd"), 0, 2));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(TerminalInputQueue.Offer.FULL,
                    executor.submit(() -> q.offer(bytes("e"), 0, 1)).get(1, TimeUnit.SECONDS));
            TerminalInputQueue.CloseResult result = q.close();
            assertEquals(2, result.discardedBytes);
            assertTrue(result.writeInProgress);
            assertEquals(TerminalInputQueue.Offer.CLOSED, q.offer(bytes("f"), 0, 1));
            assertNull(q.take());
            q.completed(inFlight);
            assertNull(q.close());
        } finally { executor.shutdownNow(); }
    }

    @Test public void closingWakesWaitingConsumerWithoutReplaying() throws Exception {
        TerminalInputQueue q = new TerminalInputQueue(16);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> waiting = executor.submit(q::take);
            TerminalInputQueue.CloseResult result = q.close();
            assertEquals(0, result.discardedBytes);
            assertFalse(result.writeInProgress);
            assertNull(waiting.get(1, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test public void stopCancelsQueuedInputWithoutWakingWriterBeforeProcessExit() throws Exception {
        TerminalInputQueue q = new TerminalInputQueue(8);
        assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("ab"), 0, 2));
        byte[] inFlight = q.take();
        assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("cd"), 0, 2));
        TerminalInputQueue.CloseResult cancelled = q.cancelPending();
        assertEquals(2, cancelled.discardedBytes);
        assertTrue(cancelled.writeInProgress);
        assertFalse(q.mayContinue(inFlight));
        assertEquals(TerminalInputQueue.Offer.CLOSED, q.offer(bytes("x"), 0, 1));
        q.completed(inFlight);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<byte[]> waiting = executor.submit(q::take);
            try {
                waiting.get(100, TimeUnit.MILLISECONDS);
                fail("writer must remain waiting until process exit");
            } catch (TimeoutException expected) { }
            assertNull(q.close());
            assertNull(waiting.get(1, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
    }

    @Test public void slowConsumerKeepsAcceptedEntriesInOrderWithoutBlockingProducer() throws Exception {
        TerminalInputQueue q = new TerminalInputQueue(6);
        CountDownLatch firstTaken = new CountDownLatch(1);
        CountDownLatch allowDrain = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("aa"), 0, 2));
            Future<String> consumed = executor.submit(() -> {
                byte[] first = q.take();
                firstTaken.countDown();
                if (!allowDrain.await(2, TimeUnit.SECONDS)) throw new AssertionError("consumer not released");
                q.completed(first);
                byte[] second = q.take();
                q.completed(second);
                byte[] third = q.take();
                q.completed(third);
                return new String(first, StandardCharsets.UTF_8)
                        + new String(second, StandardCharsets.UTF_8)
                        + new String(third, StandardCharsets.UTF_8);
            });
            assertTrue(firstTaken.await(1, TimeUnit.SECONDS));
            Future<TerminalInputQueue.Offer> producer = executor.submit(() -> {
                assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("bb"), 0, 2));
                assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("cc"), 0, 2));
                return q.offer(bytes("d"), 0, 1);
            });
            assertEquals(TerminalInputQueue.Offer.FULL, producer.get(1, TimeUnit.SECONDS));
            allowDrain.countDown();
            assertEquals("aabbcc", consumed.get(1, TimeUnit.SECONDS));
            assertEquals(TerminalInputQueue.Offer.BLOCKED, q.offer(bytes("x"), 0, 1));
            assertTrue(q.resumeAfterOverflow());
        } finally {
            allowDrain.countDown();
            q.close();
            executor.shutdownNow();
        }
    }

    @Test public void cancelDuringWriteStopsRemainingChunksAndDiscardsFollowingEntry() throws Exception {
        TerminalInputQueue q = new TerminalInputQueue(16);
        CountDownLatch firstChunkWritten = new CountDownLatch(1);
        CountDownLatch allowNextChunk = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("abcdef"), 0, 6));
            Future<String> written = executor.submit(() -> {
                byte[] entry = q.take();
                StringBuilder output = new StringBuilder();
                for (int offset = 0; offset < entry.length && q.mayContinue(entry); offset += 2) {
                    output.append(new String(entry, offset, 2, StandardCharsets.UTF_8));
                    if (offset == 0) {
                        firstChunkWritten.countDown();
                        if (!allowNextChunk.await(2, TimeUnit.SECONDS)) throw new AssertionError("writer not released");
                    }
                }
                q.completed(entry);
                return output.toString();
            });
            assertTrue(firstChunkWritten.await(1, TimeUnit.SECONDS));
            assertEquals(TerminalInputQueue.Offer.ACCEPTED, q.offer(bytes("xyz"), 0, 3));
            TerminalInputQueue.CloseResult result = q.cancelPending();
            assertTrue(result.writeInProgress);
            assertEquals(3, result.discardedBytes);
            allowNextChunk.countDown();
            assertEquals("ab", written.get(1, TimeUnit.SECONDS));
            assertEquals(TerminalInputQueue.Offer.CLOSED, q.offer(bytes("x"), 0, 1));
        } finally {
            allowNextChunk.countDown();
            q.close();
            executor.shutdownNow();
        }
    }
}
