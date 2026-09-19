package com.deepseekharness.app.backup;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class WorkerQuiescenceTest {
    @Test public void waitsForWorkerFinallyAndRejectsNewWorkersWhileClosed() throws Exception {
        WorkerQuiescence gate=new WorkerQuiescence();
        WorkerQuiescence.Lease running=gate.tryBegin();
        assertNotNull(running);
        gate.closeGate();
        assertNull(gate.tryBegin());

        CountDownLatch entered=new CountDownLatch(1),finished=new CountDownLatch(1);
        AtomicReference<Throwable> failure=new AtomicReference<>();
        Thread waiter=new Thread(()->{
            entered.countDown();
            try{gate.awaitIdle(new BackupControl(null),2_000);}
            catch(Throwable error){failure.set(error);}
            finally{finished.countDown();}
        });
        waiter.start();
        assertTrue(entered.await(1,TimeUnit.SECONDS));
        assertFalse("await must not trust an early busy=false state",finished.await(100,TimeUnit.MILLISECONDS));
        running.close();
        assertTrue(finished.await(1,TimeUnit.SECONDS));
        assertNull(failure.get());

        gate.reopen();
        WorkerQuiescence.Lease next=gate.tryBegin();
        assertNotNull(next);next.close();
    }

    @Test public void timeoutKeepsGateClosedUntilCallerExplicitlyReopens() throws Exception {
        WorkerQuiescence gate=new WorkerQuiescence();
        WorkerQuiescence.Lease running=gate.tryBegin();
        gate.closeGate();
        try{
            gate.awaitIdle(new BackupControl(null),1);
            fail("expected timeout");
        }catch(IOException expected){assertEquals("WORKER_QUIESCE_TIMEOUT",expected.getMessage());}
        assertTrue(gate.isClosed());
        assertNull(gate.tryBegin());
        running.close();gate.reopen();
        WorkerQuiescence.Lease next=gate.tryBegin();assertNotNull(next);next.close();
    }
}
