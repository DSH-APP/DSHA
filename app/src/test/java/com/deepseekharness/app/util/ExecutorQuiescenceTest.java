package com.deepseekharness.app.util;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExecutorQuiescenceTest {
    @Test public void closesSubmissionBeforeWaitingForQueuedWriter() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try{
            ExecutorQuiescence gate=new ExecutorQuiescence();
            CountDownLatch writerStarted=new CountDownLatch(1),releaseWriter=new CountDownLatch(1),drained=new CountDownLatch(1);
            assertTrue(gate.execute(executor,()->{writerStarted.countDown();try{releaseWriter.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            assertTrue(writerStarted.await(1,TimeUnit.SECONDS));
            AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread waiter=new Thread(()->{try{gate.closeAndAwait(executor,2_000);}catch(Throwable error){failure.set(error);}finally{drained.countDown();}});
            waiter.start();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
            while(!gate.isClosed()&&System.nanoTime()<deadline)Thread.yield();
            assertTrue(gate.isClosed());
            assertFalse(gate.execute(executor,()->fail("late writer must be rejected")));
            assertFalse(drained.await(100,TimeUnit.MILLISECONDS));
            releaseWriter.countDown();
            assertTrue(drained.await(1,TimeUnit.SECONDS));
            assertNull(failure.get());
            gate.reopen();assertTrue(gate.execute(executor,()->{}));
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(1,TimeUnit.SECONDS));}
    }

    @Test public void drainTimeoutIsBoundedAndKeepsWriterGateClosed() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor();
        CountDownLatch release=new CountDownLatch(1);
        try{
            ExecutorQuiescence gate=new ExecutorQuiescence();
            assertTrue(gate.execute(executor,()->{try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}));
            try{gate.closeAndAwait(executor,1);fail("expected timeout");}
            catch(IOException expected){assertEquals("EXECUTOR_QUIESCE_TIMEOUT",expected.getMessage());}
            assertTrue(gate.isClosed());
        }finally{release.countDown();executor.shutdownNow();executor.awaitTermination(1,TimeUnit.SECONDS);}
    }
}
