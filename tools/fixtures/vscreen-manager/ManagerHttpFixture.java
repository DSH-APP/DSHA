package com.deepseekharness.app.vscreen;

import com.deepseekharness.app.util.HttpProtocol;
import com.deepseekharness.app.util.VirtualScreenCoreIdentity;
import com.deepseekharness.app.util.VirtualScreenHandshake;
import com.deepseekharness.app.util.VirtualScreenLifecycleState;
import java.io.*;
import java.net.*;
import java.util.Map;
import java.util.UUID;
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
  private static PeerServer server;
  private static final AtomicInteger clearedUi = new AtomicInteger();
  private static final CopyOnWriteArrayList<Long> stopCallbacks = new CopyOnWriteArrayList<>();
  private static VirtualScreenManager manager =
      new VirtualScreenManager(
          null,
          () -> 100,
          new VirtualScreenManager.Visuals() {
            public void present() {}

            public void stopped(long fence) {
              stopCallbacks.add(fence);
              if (manager.epoch() == fence) clearedUi.incrementAndGet();
            }
          });
  private static Object stateLock;
  private static AtomicLong epoch;

  private static void require(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static Field field(String name) throws Exception {
    Field field = VirtualScreenManager.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private static String token(String owner) {
    try {
      byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(owner.getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest).substring(0, 48);
    } catch (Exception impossible) { throw new AssertionError(impossible); }
  }

  private static void install(long value, String owner, String generation, long sequence) throws Exception {
    installInto(manager, server, value, owner, generation, sequence);
  }

  private static void installInto(VirtualScreenManager target, PeerServer endpoint, long value,
      String owner, String generation, long sequence) throws Exception {
    Object lock = field("LOCK").get(target);
    synchronized (lock) {
      var birth = VirtualScreenCoreIdentity.parse("123:" + value + ":2000:11111111-2222-3333-4444-555555555555:" + new UUID(0, value));
      var handle = new VirtualScreenHandshake(endpoint.getAddress().getPort(), token(owner), birth);
      endpoint.handle = handle;
      ((AtomicLong) field("LIFECYCLE_EPOCH").get(target)).set(value);
      field("activeEpoch").setLong(target, value);
      field("token").set(target, handle.token);
      field("core").set(target, handle);
      field("pendingCleanup").set(target, null);
      field("lifecycleState").set(target, VirtualScreenLifecycleState.READY);
      field("generation").set(target, generation);
      field("port").setInt(target, endpoint.getAddress().getPort());
      field("stateRevision").setLong(target, value);
      field("frameSequence").setLong(target, sequence);
      field("starting").setBoolean(target, false);
    }
  }

  /** Synthetic platform replies, but the socket peer proof and HTTP parser are production code. */
  private static final class PeerServer {
    final ServerSocket listener;
    final Map<String, java.util.function.Consumer<PeerExchange>> handlers = new ConcurrentHashMap<>();
    volatile VirtualScreenHandshake handle;
    volatile boolean running;
    Executor executor;
    volatile Throwable failure;

    PeerServer(InetSocketAddress address, int backlog) throws IOException {
      listener = new ServerSocket(); listener.bind(address, backlog);
    }
    static PeerServer create(InetSocketAddress address, int backlog) throws IOException {
      return new PeerServer(address, backlog);
    }
    InetSocketAddress getAddress() { return (InetSocketAddress) listener.getLocalSocketAddress(); }
    void setExecutor(Executor executor) { this.executor = executor; }
    void createContext(String route, java.util.function.Consumer<PeerExchange> handler) { handlers.put(route, handler); }
    void removeContext(String route) { handlers.remove(route); }
    void start() {
      running = true;
      Thread accept = new Thread(() -> {
        while (running) {
          try { Socket socket = listener.accept(); executor.execute(() -> serve(socket)); }
          catch (IOException closed) { if (running) failure = closed; }
        }
      }, "vscreen-fixture-peer");
      accept.setDaemon(true); accept.start();
    }
    void serve(Socket socket) {
      try (socket) {
        VirtualScreenHandshake endpoint = handle;
        require(endpoint != null, "fixture Core birth handle required");
        var peer = VirtualScreenPeerProof.server(socket, endpoint);
        var head = HttpProtocol.readHead(socket.getInputStream(), socket, HttpProtocol.BRIDGE, HttpProtocol.deadline(3000), true);
        require(head != null && peer.authorizes(head.method, head.target, head.value(VirtualScreenPeerProof.HEADER)), "actual same-socket HMAC required before business route");
        require(head.value("Authorization").isEmpty(), "no bearer credential is sent");
        var exchange = new PeerExchange(socket, endpoint, head);
        var handler = handlers.get(exchange.getRequestURI().getPath());
        require(handler != null, "unexpected business route: " + exchange.getRequestURI().getPath());
        handler.accept(exchange);
      } catch (Throwable error) { failure = error; }
    }
    void stop(int ignored) throws IOException { running = false; listener.close(); }
  }

  private static final class PeerExchange implements AutoCloseable {
    final Socket socket;
    final VirtualScreenHandshake endpoint;
    final HttpProtocol.Head head;
    PeerExchange(Socket socket, VirtualScreenHandshake endpoint, HttpProtocol.Head head) {
      this.socket = socket; this.endpoint = endpoint; this.head = head;
    }
    URI getRequestURI() { return URI.create(head.target); }
    OutputStream getResponseBody() throws IOException { return socket.getOutputStream(); }
    void sendResponseHeaders(int status, long length) throws IOException {
      socket.getOutputStream().write(("HTTP/1.1 " + status + " OK\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      socket.getOutputStream().flush();
    }
    public void close() { try { socket.close(); } catch (IOException ignored) {} }
  }

  private static String response(long sequence) {
    return "{\"ok\":true,\"generation\":\"display-a\",\"frameSeq\":"
        + sequence
        + ",\"displayId\":4,\"package\":\"target.pkg\"}";
  }

  private static void reply(PeerExchange exchange, String body)
      throws Exception {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(200, bytes.length);
    try (var out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void await(CountDownLatch latch) throws Exception {
    require(latch.await(3, TimeUnit.SECONDS), "fixture deadline");
  }

  private static void staleSessionAndStateLock() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    server.createContext(
        "/vscreen/preview",
        exchange -> {
          try {
            require(
                token("owner-a").equals(exchange.endpoint.token),
                "real authenticated Core identity");
            entered.countDown();
            await(release);
            reply(exchange, response(1));
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    install(11, "owner-a", "display-a", 0);
    Future<JSONObject> response = CLIENTS.submit(() -> manager.preview());
    await(entered);
    Future<Boolean> monitor =
        CLIENTS.submit(
            () -> {
              synchronized (stateLock) {
                return true;
              }
            });
    require(monitor.get(1, TimeUnit.SECONDS), "I/O must not hold state LOCK");
    install(12, "owner-b", "display-b", 9);
    release.countDown();
    JSONObject value = response.get(3, TimeUnit.SECONDS);
    require(!value.optBoolean("ok"), "stale request rejected");
    require(
        "display-b".equals(field("generation").get(manager)),
        "late response must not replace new display");
    require(
        field("frameSequence").getLong(manager) == 9, "late response must not replace new frame");
    server.removeContext("/vscreen/preview");
    System.out.println("PASS actual blocked HTTP releases LOCK and old session cannot commit");
  }

  private static void staleGrantAndTarget() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean authorized = new AtomicBoolean(true);
    server.createContext(
        "/vscreen/preview",
        exchange -> {
          try {
            require(
                exchange.getRequestURI().getRawQuery().contains("expectedPackage=target.pkg"),
                "actual expected target sent");
            entered.countDown();
            await(release);
            reply(exchange, response(8));
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    install(21, "owner-a", "display-a", 0);
    Future<String> response =
        CLIENTS.submit(
            () ->
                manager.withBridgeTarget(
                    "target.pkg", authorized::get, () -> manager.preview().toString()));
    await(entered);
    authorized.set(false);
    release.countDown();
    JSONObject value = new JSONObject(response.get(3, TimeUnit.SECONDS));
    require(
        "SCREEN_TARGET_CHANGED".equals(value.optString("error")),
        "revoked grant/target result rejected");
    require(
        field("frameSequence").getLong(manager) == 0, "old grant result must not commit metadata");
    server.removeContext("/vscreen/preview");
    System.out.println("PASS actual HTTP preserves target and old authority cannot commit");
  }

  private static void outOfOrderFrames() throws Exception {
    AtomicInteger requests = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    server.createContext(
        "/vscreen/preview",
        exchange -> {
          try {
            int request = requests.incrementAndGet();
            if (request == 1) {
              entered.countDown();
              await(release);
            }
            reply(exchange, response(request == 1 ? 1 : 2));
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    install(31, "owner-a", "display-a", 0);
    Future<JSONObject> old = CLIENTS.submit(() -> manager.preview());
    await(entered);
    JSONObject latest = manager.preview();
    require(latest.optBoolean("ok"), "newer frame accepted");
    require(manager.frameCurrent(latest), "new frame receipt current");
    release.countDown();
    JSONObject stale = old.get(3, TimeUnit.SECONDS);
    require("STALE_FRAME".equals(stale.optString("error")), "older frame response rejected");
    require(field("frameSequence").getLong(manager) == 2, "sequence cannot regress");
    server.removeContext("/vscreen/preview");
    System.out.println("PASS actual out-of-order frame responses cannot regress state");
  }

  private static void orderedTouch() throws Exception {
    CopyOnWriteArrayList<Integer> actions = new CopyOnWriteArrayList<>();
    CountDownLatch down = new CountDownLatch(1), release = new CountDownLatch(1);
    server.createContext(
        "/vscreen/touch",
        exchange -> {
          try {
            String query = exchange.getRequestURI().getRawQuery();
            int action = Integer.parseInt(query.replaceFirst(".*&action=([0-9]+)&.*", "$1"));
            actions.add(action);
            if (action == 0) {
              down.countDown();
              await(release);
            }
            reply(exchange, response(1));
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    install(41, "owner-a", "display-a", 1);
    String stroke = "12345678-1234-1234-1234-123456789abc";
    Future<JSONObject> first = CLIENTS.submit(() -> manager.touch("display-a", 1, stroke, 0, 1, 2));
    await(down);
    ReentrantLock ordered = (ReentrantLock) field("ACTIONS").get(manager);
    CountDownLatch moveStarted = new CountDownLatch(1), upStarted = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread move =
        new Thread(
            () -> {
              moveStarted.countDown();
              try {
                require(
                    manager.touch("display-a", 1, stroke, 2, 2, 3).optBoolean("ok"), "move result");
              } catch (Throwable error) {
                failure.compareAndSet(null, error);
              }
            });
    move.start();
    await(moveStarted);
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
    while (!ordered.hasQueuedThread(move) && System.nanoTime() < deadline) Thread.yield();
    require(ordered.hasQueuedThread(move), "move queued on ordered channel");
    Thread up =
        new Thread(
            () -> {
              upStarted.countDown();
              try {
                require(
                    manager.touch("display-a", 1, stroke, 1, 3, 4).optBoolean("ok"), "up result");
              } catch (Throwable error) {
                failure.compareAndSet(null, error);
              }
            });
    up.start();
    await(upStarted);
    release.countDown();
    require(first.get(3, TimeUnit.SECONDS).optBoolean("ok"), "down result");
    move.join(3000);
    up.join(3000);
    require(!move.isAlive() && !up.isAlive(), "touch channel drained");
    require(failure.get() == null, "all touch results successful: " + failure.get());
    require(actions.equals(List.of(0, 2, 1)), "touch down/move/up remain ordered");
    server.removeContext("/vscreen/touch");
    System.out.println("PASS actual concurrent touch requests preserve one ordered channel");
  }

  private static void olderActionReceipt() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    server.createContext(
        "/vscreen/touch",
        exchange -> {
          try {
            entered.countDown();
            await(release);
            reply(exchange, response(1));
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    server.createContext(
        "/vscreen/preview",
        exchange -> {
          try {
            reply(exchange, response(2));
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    install(51, "owner-a", "display-a", 1);
    JSONObject invalid =
        manager.touch("old-generation", 1, "12345678-1234-1234-1234-123456789abc", 0, 1, 2);
    require(
        "STALE_GENERATION".equals(invalid.optString("error")),
        "generation error semantics preserved before I/O");
    Future<JSONObject> action =
        CLIENTS.submit(
            () -> manager.touch("display-a", 1, "12345678-1234-1234-1234-123456789abc", 0, 1, 2));
    await(entered);
    require(manager.preview().optBoolean("ok"), "newer observation accepted");
    release.countDown();
    JSONObject receipt = action.get(3, TimeUnit.SECONDS);
    require(
        receipt.optBoolean("ok"), "actual input result must not be rewritten as a frame failure");
    require(
        field("frameSequence").getLong(manager) == 2,
        "old input metadata cannot regress observed frame");
    require(
        !manager.frameCurrent(receipt), "older input receipt cannot be displayed as current frame");
    server.removeContext("/vscreen/touch");
    server.removeContext("/vscreen/preview");
    System.out.println(
        "PASS real input result preserved while older metadata cannot regress or render");
  }

  private static void executedWriteIsNotReportedAsRejection() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean authorized = new AtomicBoolean(true);
    server.createContext(
        "/vscreen/touch",
        exchange -> {
          try {
            entered.countDown();
            await(release);
            reply(exchange, response(1));
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    install(81, "owner-a", "display-a", 1);
    Future<String> response =
        CLIENTS.submit(
            () ->
                manager.withBridgeTarget(
                    "target.pkg",
                    authorized::get,
                    () ->
                        manager
                            .touch("display-a", 1, "12345678-1234-1234-1234-123456789abc", 0, 1, 2)
                            .toString()));
    await(entered);
    authorized.set(false);
    release.countDown();
    JSONObject value = new JSONObject(response.get(3, TimeUnit.SECONDS));
    require(value.optBoolean("ok"), "executed write keeps its real result");
    require(
        value.optBoolean("targetChangedAfterExecution"),
        "executed write is marked as already executed");
    require(value.optString("error").isEmpty(), "executed write is not reported as a rejection");
    server.removeContext("/vscreen/touch");
    System.out.println("PASS executed write is marked instead of reported as not executed");
  }

  private static void runChangedWriteIsNotReportedAsRejection() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    server.createContext(
        "/vscreen/touch",
        exchange -> {
          try {
            entered.countDown();
            await(release);
            reply(
                exchange,
                "{\"ok\":true,\"generation\":\"display-b\",\"frameSeq\":1,\"displayId\":4,\"package\":\"target.pkg\"}");
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    install(91, "owner-a", "display-a", 1);
    Future<JSONObject> response =
        CLIENTS.submit(
            () -> manager.touch("display-a", 1, "12345678-1234-1234-1234-123456789abc", 0, 1, 2));
    await(entered);
    release.countDown();
    JSONObject value = response.get(3, TimeUnit.SECONDS);
    require(value.optBoolean("ok"), "executed write keeps its real result");
    require(
        value.optBoolean("runChangedAfterExecution"),
        "generation change after execution is marked as a run change");
    require(
        value.optString("error").isEmpty(), "executed write is not reported as a stale generation");
    require(
        "display-a".equals(field("generation").get(manager)),
        "returned generation must not replace the live display");
    require(
        field("frameSequence").getLong(manager) == 1,
        "returned generation must not commit frame metadata");
    server.removeContext("/vscreen/touch");
    System.out.println(
        "PASS generation change after execution is marked instead of reported as not executed");
  }

  private static void keyWriteIsNotReportedAsRejection() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean authorized = new AtomicBoolean(true);
    server.createContext(
        "/vscreen/key",
        exchange -> {
          try {
            require(
                exchange.getRequestURI().getRawQuery().contains("frameSeq=1"),
                "key request carries the observed frame");
            entered.countDown();
            await(release);
            reply(exchange, response(1));
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    install(101, "owner-a", "display-a", 1);
    Future<String> response =
        CLIENTS.submit(
            () ->
                manager.withBridgeTarget(
                    "target.pkg",
                    authorized::get,
                    () -> manager.action("key", "display-a", 1, "keycode=4").toString()));
    await(entered);
    authorized.set(false);
    release.countDown();
    JSONObject value = new JSONObject(response.get(3, TimeUnit.SECONDS));
    require(value.optBoolean("ok"), "executed key keeps its real result");
    require(
        value.optBoolean("targetChangedAfterExecution"),
        "key on a changed target is marked as already executed");
    require(value.optString("error").isEmpty(), "key is not reported as a rejection");
    server.removeContext("/vscreen/key");
    System.out.println(
        "PASS key route on a changed target is marked instead of reported as not executed");
  }

  private static void distinctOwnersAndLateClose() throws Exception {
    VirtualScreenManager peer =
        new VirtualScreenManager(
            null,
            () -> 100,
            new VirtualScreenManager.Visuals() {
              public void present() {}

              public void stopped(long fence) {}
            });
    PeerServer peerServer = PeerServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
    peerServer.setExecutor(SERVER);
    peerServer.createContext("/vscreen/status", exchange -> {
      try { reply(exchange, response(3).replace("display-a", "display-peer")); }
      catch (Exception failure) { throw new RuntimeException(failure); }
      finally { exchange.close(); }
    });
    peerServer.start();
    installInto(peer, peerServer, 71, "owner-peer", "display-peer", 3);
    Object peerLock = field("LOCK").get(peer);
    require(peerLock != stateLock, "owners never share state LOCK");
    server.createContext(
        "/vscreen/status",
        exchange -> {
          try {
            reply(exchange, response(2));
          } catch (Exception failure) {
            throw new RuntimeException(failure);
          } finally {
            exchange.close();
          }
        });
    CountDownLatch closeEntered = new CountDownLatch(1), closeRelease = new CountDownLatch(1);
    server.createContext(
        "/vscreen/close",
        exchange -> {
          try {
            require(
                token("owner-a").equals(exchange.endpoint.token),
                "close retains the old authenticated Core identity");
            closeEntered.countDown();
            await(closeRelease);
            reply(exchange, "{\"ok\":true}");
          } catch (Exception failure) {
            throw new RuntimeException(failure);
          } finally {
            exchange.close();
          }
        });
    try {
      install(61, "owner-a", "display-a", 2);
      require(manager.status().optBoolean("ok"), "first owner status accepts its generation");
      require(
          peer.status().optBoolean("ok"), "second owner accepts its distinct authenticated Core/generation");
      Future<JSONObject> oldClose = CLIENTS.submit(manager::close);
      await(closeEntered);
      install(63, "owner-new", "display-new", 7);
      Object nodeCache = field("accessibility").get(manager);
      Field display = VirtualScreenAccessibility.class.getDeclaredField("treeDisplay");
      display.setAccessible(true);
      display.setInt(nodeCache, 99);
      closeRelease.countDown();
      require(
          oldClose.get(3, TimeUnit.SECONDS).optString("error").equals("VSCREEN_CLOSE_UNKNOWN"),
          "remote release reply cannot replace an unavailable birth exit proof");
      require(
          "display-new".equals(manager.generation()),
          "late close never detaches new manager state");
      require(display.getInt(nodeCache) == 99, "late close never clears new node cache");
      require(
          stopCallbacks.contains(62L) && clearedUi.get() == 0,
          "stale close callback carries old fence and cannot clear current UI");
      require(
          peer.epoch() == 71 && "display-peer".equals(peer.generation()),
          "one owner's close never changes another owner");
      require(peer.status().optBoolean("ok"), "unaffected peer remains request-capable");
      System.out.println(
          "PASS actual distinct-Core HMAC/state isolation, release-versus-exit and late-close UI fence");
    } finally {
      closeRelease.countDown();
      server.removeContext("/vscreen/status");
      server.removeContext("/vscreen/close");
      peerServer.stop(0);
      ((ScheduledExecutorService) field("WORKER").get(peer)).shutdownNow();
    }
  }

  private static void unknownWriteSurvivesRevocation() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    AtomicBoolean authorized = new AtomicBoolean(true);
    AtomicInteger sent = new AtomicInteger();
    server.createContext(
        "/vscreen/key",
        exchange -> {
          try {
            sent.incrementAndGet();
            entered.countDown();
            await(release);
            reply(
                exchange,
                "{\"ok\":false,\"error\":\"INPUT_RESULT_UNKNOWN\",\"executionUnknown\":true}");
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    install(111, "owner-a", "display-a", 1);
    Future<String> response =
        CLIENTS.submit(
            () ->
                manager.withBridgeTarget(
                    "target.pkg",
                    authorized::get,
                    () -> manager.action("key", "display-a", 1, "keycode=4").toString()));
    await(entered);
    authorized.set(false);
    release.countDown();
    JSONObject value = new JSONObject(response.get(3, TimeUnit.SECONDS));
    require(
        "INPUT_RESULT_UNKNOWN".equals(value.optString("error")),
        "unknown must not become a rejection");
    require(value.optBoolean("executionUnknown"), "uncertainty survives revocation");
    require(sent.get() == 1, "unknown request must not replay");
    server.removeContext("/vscreen/key");
    System.out.println("PASS unknown write survives revocation without replay");
  }

  private static void semanticNodeDoesNotAcquirePixelFreshness() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    server.createContext(
        "/vscreen/status",
        exchange -> {
          try {
            entered.countDown();
            await(release);
            reply(exchange, response(1));
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    server.createContext(
        "/vscreen/preview",
        exchange -> {
          try {
            reply(exchange, response(2));
          } catch (Exception error) {
            throw new RuntimeException(error);
          } finally {
            exchange.close();
          }
        });
    install(121, "owner-a", "display-a", 1);
    Future<JSONObject> result =
        CLIENTS.submit(() -> manager.node("display-a", -1, "missing", "click", ""));
    await(entered);
    require(manager.preview().optBoolean("ok"), "newer preview accepted");
    release.countDown();
    JSONObject node = result.get(3, TimeUnit.SECONDS);
    require(
        "EXPLICIT_SCREEN_REQUEST_REQUIRED".equals(node.optString("error")),
        "semantic path requires explicit capability context without pixel rejection");
    require(
        field("frameSequence").getLong(manager) == 2,
        "semantic identity receipt cannot regress frame metadata");
    server.removeContext("/vscreen/status");
    server.removeContext("/vscreen/preview");
    System.out.println("PASS semantic node identity survives newer concurrent pixel metadata");
  }

  public static void main(String[] args) throws Exception {
    stateLock = field("LOCK").get(manager);
    epoch = (AtomicLong) field("LIFECYCLE_EPOCH").get(manager);
    server = PeerServer.create(new InetSocketAddress("127.0.0.1", 0), 8);
    server.setExecutor(SERVER);
    server.start();
    try {
      staleSessionAndStateLock();
      staleGrantAndTarget();
      outOfOrderFrames();
      orderedTouch();
      olderActionReceipt();
      executedWriteIsNotReportedAsRejection();
      runChangedWriteIsNotReportedAsRejection();
      keyWriteIsNotReportedAsRejection();
      distinctOwnersAndLateClose();
      unknownWriteSurvivesRevocation();
      semanticNodeDoesNotAcquirePixelFreshness();
      require(server.failure == null, "fixture server failure: " + server.failure);
    } finally {
      server.stop(0);
      CLIENTS.shutdownNow();
      SERVER.shutdownNow();
      ((ScheduledExecutorService) field("WORKER").get(manager)).shutdownNow();
      synchronized (stateLock) {
        field("token").set(manager, "");
        field("generation").set(manager, "");
        field("activeEpoch").setLong(manager, -1);
      }
    }
  }
}
