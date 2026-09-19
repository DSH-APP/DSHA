package com.deepseekharness.app.util;

import org.junit.Test;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class SocketDispatchTest {
    private static final class Pairs implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,16,InetAddress.getLoopbackAddress());final List<Socket> peers=new ArrayList<>();
        Pairs()throws Exception{}
        Socket server()throws Exception{Socket client=new Socket(listener.getInetAddress(),listener.getLocalPort());client.setSoTimeout(2000);peers.add(client);return listener.accept();}
        public void close()throws Exception{for(Socket peer:peers)peer.close();listener.close();}
    }
    @Test public void admissionPrecedesRegistrationAndCloseReclaimsCompanions()throws Exception{
        try(Pairs sockets=new Pairs();SocketDispatch dispatch=new SocketDispatch("test-admission",2,1,1,1)){
            var first=dispatch.accept(sockets.server());var second=dispatch.accept(sockets.server());assertNotNull(first);assertNotNull(second);
            assertNull(dispatch.accept(sockets.server()));assertEquals(-1,sockets.peers.get(2).getInputStream().read());
            first.attach(sockets.server());assertEquals(Long.valueOf(2),dispatch.metrics().get("peakConnections"));
            dispatch.close();first.close();second.close();for(Socket peer:sockets.peers)assertEquals(-1,peer.getInputStream().read());
            assertEquals(Long.valueOf(0),dispatch.metrics().get("connections"));assertTrue(dispatch.awaitStopped(2000));
            assertNull(dispatch.accept(sockets.server()));assertEquals(-1,sockets.peers.get(4).getInputStream().read());
        }
    }
    @Test public void longStreamsCannotConsumeShortRequestWorkersAndAllQueuesAreBounded()throws Exception{
        try(Pairs sockets=new Pairs();SocketDispatch dispatch=new SocketDispatch("test-lanes",10,2,2,2)){
            CountDownLatch streams=new CountDownLatch(2),release=new CountDownLatch(1),shorts=new CountDownLatch(2);
            for(int i=0;i<2;i++){var owner=dispatch.accept(sockets.server());assertTrue(dispatch.stream(owner,()->{streams.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{owner.close();}}));}
            assertTrue(streams.await(2,TimeUnit.SECONDS));
            var extra=dispatch.accept(sockets.server());assertFalse(dispatch.stream(extra,()->fail("stream queue must not grow")));extra.close();
            for(int i=0;i<2;i++){var owner=dispatch.accept(sockets.server());assertTrue(dispatch.request(owner,()->{shorts.countDown();owner.close();}));}
            assertTrue("streaming must leave request lane available",shorts.await(2,TimeUnit.SECONDS));
            CountDownLatch occupied=new CountDownLatch(2);
            for(int i=0;i<2;i++){var owner=dispatch.accept(sockets.server());assertTrue(dispatch.request(owner,()->{occupied.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{owner.close();}}));}
            assertTrue(occupied.await(2,TimeUnit.SECONDS));
            for(int i=0;i<2;i++){var owner=dispatch.accept(sockets.server());assertTrue(dispatch.request(owner,owner::close));}
            var rejected=dispatch.accept(sockets.server());assertFalse(dispatch.request(rejected,rejected::close));rejected.close();
            assertEquals(Long.valueOf(2),dispatch.metrics().get("queued"));assertEquals(Long.valueOf(2),dispatch.metrics().get("streamThreads"));
            dispatch.close();release.countDown();assertTrue(dispatch.awaitStopped(2000));assertEquals(Long.valueOf(0),dispatch.metrics().get("connections"));
        }
    }
    @Test public void separatePumpLaneCannotDeadlockAgainstFullStreamLane()throws Exception{
        try(Pairs sockets=new Pairs();SocketDispatch dispatch=new SocketDispatch("test-pumps",2,1,1,2)){
            CountDownLatch entered=new CountDownLatch(2),finished=new CountDownLatch(2);List<Throwable> failures=Collections.synchronizedList(new ArrayList<>());
            for(int i=0;i<2;i++){var owner=dispatch.accept(sockets.server());assertTrue(dispatch.stream(owner,()->{
                try{entered.countDown();assertTrue(entered.await(2,TimeUnit.SECONDS));var pump=dispatch.pump(owner,()->{});pump.get(2,TimeUnit.SECONDS);}
                catch(Throwable failure){failures.add(failure);}finally{owner.close();finished.countDown();}
            }));}
            assertTrue(finished.await(4,TimeUnit.SECONDS));assertTrue(failures.toString(),failures.isEmpty());assertEquals(Long.valueOf(0),dispatch.metrics().get("connections"));
            dispatch.close();assertTrue(dispatch.awaitStopped(2000));
        }
    }
}
