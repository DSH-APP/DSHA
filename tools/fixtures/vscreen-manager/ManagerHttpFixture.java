package com.deepseekharness.app.vscreen;

import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;

/** Actual current manager + loopback HTTP; no virtual display, Android action, su, ADB or phone. */
public final class ManagerHttpFixture {
  private static final ExecutorService CLIENTS = Executors.newFixedThreadPool(4);
  private static final ExecutorService SERVER = Executors.newFixedThreadPool(4);
  private static HttpServer server;
  private static final AtomicInteger clearedUi = new AtomicInteger();
  private static final CopyOnWriteArrayList<Long> stopCallbacks = new CopyOnWriteArrayList<>();
  private static VirtualScreenManager manager = new VirtualScreenManager(null, () -> 100, new VirtualScreenManager.Visuals() {
    public void present() {}
    public void stopped(long fence) { stopCallbacks.add(fence); if (manager.epoch() == fence) clearedUi.incrementAndGet(); }
  });
  private static Object stateLock;
  private static AtomicLong epoch;

  private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
  private static Field field(String name) throws Exception { Field field=VirtualScreenManager.class.getDeclaredField(name);field.setAccessible(true);return field; }
  private static void install(long value,String token,String generation,long sequence) throws Exception {
    synchronized(stateLock){epoch.set(value);field("activeEpoch").setLong(manager,value);field("token").set(manager,token);field("generation").set(manager,generation);field("port").setInt(manager,server.getAddress().getPort());field("stateRevision").setLong(manager,value);field("frameSequence").setLong(manager,sequence);field("starting").setBoolean(manager,false);}
  }
  private static String response(long sequence) { return "{\"ok\":true,\"generation\":\"display-a\",\"frameSeq\":"+sequence+",\"displayId\":4,\"package\":\"target.pkg\"}"; }
  private static void reply(com.sun.net.httpserver.HttpExchange exchange,String body) throws Exception {
    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
  }
  private static void await(CountDownLatch latch) throws Exception { require(latch.await(3,TimeUnit.SECONDS),"fixture deadline"); }

  private static void staleSessionAndStateLock() throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    server.createContext("/vscreen/preview",exchange->{try{require("Bearer owner-a".equals(exchange.getRequestHeaders().getFirst("Authorization")),"real bearer identity");entered.countDown();await(release);reply(exchange,response(1));}catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}});
    install(11,"owner-a","display-a",0);
    Future<JSONObject> response=CLIENTS.submit(()->manager.preview());await(entered);
    Future<Boolean> monitor=CLIENTS.submit(()->{synchronized(stateLock){return true;}});
    require(monitor.get(1,TimeUnit.SECONDS),"I/O must not hold state LOCK");
    install(12,"owner-b","display-b",9);release.countDown();
    JSONObject value=response.get(3,TimeUnit.SECONDS);require(!value.optBoolean("ok"),"stale request rejected");
    require("display-b".equals(field("generation").get(manager)),"late response must not replace new display");
    require(field("frameSequence").getLong(manager)==9,"late response must not replace new frame");
    server.removeContext("/vscreen/preview");System.out.println("PASS actual blocked HTTP releases LOCK and old session cannot commit");
  }
  private static void staleGrantAndTarget() throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean authorized=new AtomicBoolean(true);
    server.createContext("/vscreen/preview",exchange->{try{require(exchange.getRequestURI().getRawQuery().contains("expectedPackage=target.pkg"),"actual expected target sent");entered.countDown();await(release);reply(exchange,response(8));}catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}});
    install(21,"owner-a","display-a",0);
    Future<String> response=CLIENTS.submit(()->manager.withBridgeTarget("target.pkg",authorized::get,()->manager.preview().toString()));await(entered);
    authorized.set(false);release.countDown();JSONObject value=new JSONObject(response.get(3,TimeUnit.SECONDS));
    require("SCREEN_TARGET_CHANGED".equals(value.optString("error")),"revoked grant/target result rejected");
    require(field("frameSequence").getLong(manager)==0,"old grant result must not commit metadata");
    server.removeContext("/vscreen/preview");System.out.println("PASS actual HTTP preserves target and old authority cannot commit");
  }
  private static void outOfOrderFrames() throws Exception {
    AtomicInteger requests=new AtomicInteger();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    server.createContext("/vscreen/preview",exchange->{try{int request=requests.incrementAndGet();if(request==1){entered.countDown();await(release);}reply(exchange,response(request==1?1:2));}catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}});
    install(31,"owner-a","display-a",0);Future<JSONObject> old=CLIENTS.submit(()->manager.preview());await(entered);
    JSONObject latest=manager.preview();require(latest.optBoolean("ok"),"newer frame accepted");require(manager.frameCurrent(latest),"new frame receipt current");
    release.countDown();JSONObject stale=old.get(3,TimeUnit.SECONDS);require("STALE_FRAME".equals(stale.optString("error")),"older frame response rejected");
    require(field("frameSequence").getLong(manager)==2,"sequence cannot regress");server.removeContext("/vscreen/preview");
    System.out.println("PASS actual out-of-order frame responses cannot regress state");
  }
  private static void orderedTouch() throws Exception {
    CopyOnWriteArrayList<Integer> actions=new CopyOnWriteArrayList<>();CountDownLatch down=new CountDownLatch(1),release=new CountDownLatch(1);
    server.createContext("/vscreen/touch",exchange->{try{String query=exchange.getRequestURI().getRawQuery();int action=Integer.parseInt(query.replaceFirst(".*&action=([0-9]+)&.*","$1"));actions.add(action);if(action==0){down.countDown();await(release);}reply(exchange,response(1));}catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}});
    install(41,"owner-a","display-a",1);String stroke="12345678-1234-1234-1234-123456789abc";
    Future<JSONObject> first=CLIENTS.submit(()->manager.touch("display-a",1,stroke,0,1,2));await(down);
    ReentrantLock ordered=(ReentrantLock)field("ACTIONS").get(manager);CountDownLatch moveStarted=new CountDownLatch(1),upStarted=new CountDownLatch(1);AtomicReference<Throwable> failure=new AtomicReference<>();
    Thread move=new Thread(()->{moveStarted.countDown();try{require(manager.touch("display-a",1,stroke,2,2,3).optBoolean("ok"),"move result");}catch(Throwable error){failure.compareAndSet(null,error);}});move.start();await(moveStarted);
    long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(!ordered.hasQueuedThread(move)&&System.nanoTime()<deadline)Thread.yield();require(ordered.hasQueuedThread(move),"move queued on ordered channel");
    Thread up=new Thread(()->{upStarted.countDown();try{require(manager.touch("display-a",1,stroke,1,3,4).optBoolean("ok"),"up result");}catch(Throwable error){failure.compareAndSet(null,error);}});up.start();await(upStarted);
    release.countDown();require(first.get(3,TimeUnit.SECONDS).optBoolean("ok"),"down result");move.join(3000);up.join(3000);require(!move.isAlive()&&!up.isAlive(),"touch channel drained");
    require(failure.get()==null,"all touch results successful: "+failure.get());require(actions.equals(List.of(0,2,1)),"touch down/move/up remain ordered");server.removeContext("/vscreen/touch");System.out.println("PASS actual concurrent touch requests preserve one ordered channel");
  }
  private static void olderActionReceipt() throws Exception {
    CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
    server.createContext("/vscreen/touch",exchange->{try{entered.countDown();await(release);reply(exchange,response(1));}catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}});
    server.createContext("/vscreen/preview",exchange->{try{reply(exchange,response(2));}catch(Exception error){throw new RuntimeException(error);}finally{exchange.close();}});
    install(51,"owner-a","display-a",1);
    JSONObject invalid=manager.touch("old-generation",1,"12345678-1234-1234-1234-123456789abc",0,1,2);
    require("STALE_GENERATION".equals(invalid.optString("error")),"generation error semantics preserved before I/O");
    Future<JSONObject> action=CLIENTS.submit(()->manager.touch("display-a",1,"12345678-1234-1234-1234-123456789abc",0,1,2));await(entered);
    require(manager.preview().optBoolean("ok"),"newer observation accepted");release.countDown();
    JSONObject receipt=action.get(3,TimeUnit.SECONDS);require(receipt.optBoolean("ok"),"actual input result must not be rewritten as a frame failure");
    require(field("frameSequence").getLong(manager)==2,"old input metadata cannot regress observed frame");
    require(!manager.frameCurrent(receipt),"older input receipt cannot be displayed as current frame");
    server.removeContext("/vscreen/touch");server.removeContext("/vscreen/preview");
    System.out.println("PASS real input result preserved while older metadata cannot regress or render");
  }
  private static void distinctOwnersAndLateClose() throws Exception {
    VirtualScreenManager peer = new VirtualScreenManager(null, () -> 100, new VirtualScreenManager.Visuals() {
      public void present() {}
      public void stopped(long fence) {}
    });
    AtomicLong peerEpoch = (AtomicLong) field("LIFECYCLE_EPOCH").get(peer);
    Object peerLock = field("LOCK").get(peer);
    synchronized (peerLock) {
      peerEpoch.set(71); field("activeEpoch").setLong(peer,71); field("token").set(peer,"owner-peer");
      field("generation").set(peer,"display-peer"); field("port").setInt(peer,server.getAddress().getPort());
      field("stateRevision").setLong(peer,71); field("frameSequence").setLong(peer,3); field("starting").setBoolean(peer,false);
    }
    require(peerLock != stateLock,"owners never share state LOCK");
    server.createContext("/vscreen/status",exchange->{try {
      String token=exchange.getRequestHeaders().getFirst("Authorization");
      reply(exchange, "Bearer owner-peer".equals(token) ? response(3).replace("display-a","display-peer") : response(2));
    } catch(Exception failure) { throw new RuntimeException(failure); } finally { exchange.close(); }});
    CountDownLatch closeEntered=new CountDownLatch(1), closeRelease=new CountDownLatch(1);
    server.createContext("/vscreen/close",exchange->{try {
      require("Bearer owner-a".equals(exchange.getRequestHeaders().getFirst("Authorization")),"close retains old session bearer");
      closeEntered.countDown(); await(closeRelease); reply(exchange,"{\"ok\":true}");
    } catch(Exception failure) { throw new RuntimeException(failure); } finally { exchange.close(); }});
    try {
      install(61,"owner-a","display-a",2);
      require(manager.status().optBoolean("ok"),"first owner status accepts its generation");
      require(peer.status().optBoolean("ok"),"second owner status accepts its own bearer/generation");
      Future<JSONObject> oldClose=CLIENTS.submit(manager::close); await(closeEntered);
      install(63,"owner-new","display-new",7);
      Object nodeCache=field("accessibility").get(manager);
      Field display=VirtualScreenAccessibility.class.getDeclaredField("treeDisplay"); display.setAccessible(true); display.setInt(nodeCache,99);
      closeRelease.countDown(); require(oldClose.get(3,TimeUnit.SECONDS).optBoolean("ok"),"old remote close result returns");
      require("display-new".equals(manager.generation()),"late close never detaches new manager state");
      require(display.getInt(nodeCache)==99,"late close never clears new node cache");
      require(stopCallbacks.contains(62L) && clearedUi.get()==0,"stale close callback carries old fence and cannot clear current UI");
      require(peer.epoch()==71 && "display-peer".equals(peer.generation()),"one owner's close never changes another owner");
      require(peer.status().optBoolean("ok"),"unaffected peer remains request-capable");
      System.out.println("PASS actual distinct-owner bearer/state isolation and late close cache/UI callback fence");
    } finally {
      closeRelease.countDown(); server.removeContext("/vscreen/status"); server.removeContext("/vscreen/close");
      ((ScheduledExecutorService)field("WORKER").get(peer)).shutdownNow();
    }
  }
  public static void main(String[] args) throws Exception {
    stateLock=field("LOCK").get(manager);epoch=(AtomicLong)field("LIFECYCLE_EPOCH").get(manager);
    server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),8);server.setExecutor(SERVER);server.start();
    try{staleSessionAndStateLock();staleGrantAndTarget();outOfOrderFrames();orderedTouch();olderActionReceipt();distinctOwnersAndLateClose();}
    finally{server.stop(0);CLIENTS.shutdownNow();SERVER.shutdownNow();((ScheduledExecutorService)field("WORKER").get(manager)).shutdownNow();synchronized(stateLock){field("token").set(manager,"");field("generation").set(manager,"");field("activeEpoch").setLong(manager,-1);}}
  }
}
