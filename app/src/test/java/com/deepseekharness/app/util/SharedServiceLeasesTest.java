package com.deepseekharness.app.util;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SharedServiceLeasesTest {
    @Test public void webAndDeviceCanStartInEitherOrderAndOnlyLastDemandStopsListener() {
        for (boolean deviceFirst : new boolean[]{true, false}) {
            List<String> events = new ArrayList<>();
            SharedServiceLeases<String> pool = new SharedServiceLeases<>(v -> events.add("start:"+v), v -> events.add("stop:"+v));
            String first = deviceFirst ? "device" : "web";
            var a=pool.acquire(()->first); var b=pool.acquire(()->"must-not-create");
            a.close(); assertFalse(events.contains("stop:"+first));
            b.ensureStarted(); b.close();
            assertEquals(List.of("start:"+first,"start:"+first,"start:"+first,"stop:"+first),events);
        }
    }
    @Test public void oldReleaseAndRetryNeverTouchNewListener() {
        List<String> events=new ArrayList<>();
        SharedServiceLeases<String> pool=new SharedServiceLeases<>(v->events.add("start:"+v),v->events.add("stop:"+v));
        var old=pool.acquire(()->"old");old.close();var next=pool.acquire(()->"new");
        old.close();old.ensureStarted();assertEquals(List.of("start:old","stop:old","start:new"),events);
        next.close();assertEquals("stop:new",events.get(3));
    }
    @Test public void failedStartDoesNotLeakAnOwner() {
        AtomicInteger starts=new AtomicInteger(),stops=new AtomicInteger();
        SharedServiceLeases<Object> pool=new SharedServiceLeases<>(v->{if(starts.getAndIncrement()==0)throw new IllegalStateException();},v->stops.incrementAndGet());
        assertThrows(IllegalStateException.class,()->pool.acquire(Object::new));
        try(var retry=pool.acquire(Object::new)){retry.ensureStarted();}
        assertEquals(2,stops.get());
    }
    @Test public void concurrentOwnersShareExactlyOneResource() throws Exception {
        AtomicInteger created=new AtomicInteger(),stopped=new AtomicInteger();
        SharedServiceLeases<Object> pool=new SharedServiceLeases<>(v->{},v->stopped.incrementAndGet());
        CountDownLatch acquired=new CountDownLatch(12),release=new CountDownLatch(1),done=new CountDownLatch(12);
        for(int i=0;i<12;i++)new Thread(()->{try(var lease=pool.acquire(()->{created.incrementAndGet();return new Object();})){
            acquired.countDown();release.await();
        }catch(InterruptedException e){Thread.currentThread().interrupt();}finally{done.countDown();}}).start();
        assertTrue(acquired.await(3,TimeUnit.SECONDS));assertEquals(1,created.get());assertEquals(0,stopped.get());
        release.countDown();assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(1,stopped.get());
    }
}
