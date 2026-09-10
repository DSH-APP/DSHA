package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class DshAuthSessionTest {
    private static final String TOKEN = "A".repeat(43);
    private static final String COOKIE = "dsh-auth-" + "B".repeat(43) + "=v1.cGF5bG9hZA." + "C".repeat(43);
    private static String redirect(String cookie) {
        return "303 See Other\r\nLocation: /\r\nSet-Cookie: " + cookie + "; Path=/; HttpOnly\r\n";
    }
    private static final class Server implements AutoCloseable {
        final ServerSocket socket = new ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());
        final Thread worker;
        volatile Throwable failure;
        Server(String... responses) throws Exception {
            worker = new Thread(() -> {
                try {
                    for (String response : responses) {
                        try (Socket client = socket.accept()) {
                            client.setSoTimeout(3000);
                            BufferedReader input = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
                            StringBuilder request = new StringBuilder(); String line;
                            while ((line = input.readLine()) != null && !line.isEmpty()) request.append(line).append('\n');
                            requests.add(request.toString());
                            client.getOutputStream().write(("HTTP/1.1 " + response + "Content-Length: 0\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        }
                    }
                } catch (Throwable error) { if (!socket.isClosed()) failure = error; }
            });
            worker.setDaemon(true); worker.start();
        }
        String url() { return "http://127.0.0.1:" + socket.getLocalPort() + "/?token=" + TOKEN; }
        DshAuthSession.Result exchange() { return DshAuthSession.exchange(url(), socket.getLocalPort(), () -> true, 2500); }
        public void close() throws Exception { socket.close(); worker.join(3000); if (failure != null) throw new AssertionError(failure); }
    }

    @Test public void waitsForServiceAndVerifiesCookieOnCleanRoot() throws Exception {
        try (Server server = new Server("503 Starting\r\n", redirect(COOKIE), "200 OK\r\n")) {
            DshAuthSession.Result result = server.exchange();
            assertTrue(result.message, result.ready()); assertEquals(COOKIE, result.cookie);
            assertEquals(3, server.requests.size());
            assertTrue(server.requests.get(2).startsWith("GET / HTTP/1.1"));
            assertTrue(server.requests.get(2).contains("Cookie: " + COOKIE));
            assertFalse(server.requests.get(2).contains(TOKEN));
        }
    }
    @Test public void staleTokenIsExplainedWithoutRetryingOrLoggingIt() throws Exception {
        try (Server server = new Server("401 Unauthorized\r\n")) {
            DshAuthSession.Result result = server.exchange();
            assertEquals(DshAuthSession.Status.EXPIRED, result.status);
            assertTrue(result.message.contains("401")); assertFalse(result.message.contains(TOKEN));
            assertEquals(1, server.requests.size());
        }
    }
    @Test public void neverFollowsRedirectWithCredentials() throws Exception {
        try (Server server = new Server("303 See Other\r\nLocation: https://example.invalid/\r\nSet-Cookie: " + COOKIE + "\r\n")) {
            assertEquals(DshAuthSession.Status.INVALID_RESPONSE, server.exchange().status);
            assertEquals(1, server.requests.size());
        }
    }
    @Test public void rejectedCookieIsNotReportedAsReady() throws Exception {
        try (Server server = new Server(redirect(COOKIE), "403 Forbidden\r\n")) {
            assertEquals(DshAuthSession.Status.EXPIRED, server.exchange().status);
        }
    }
    @Test public void missingAndEmptyCookiesFail() throws Exception {
        for (String cookie : new String[] {"other=value", "dsh-auth-" + "B".repeat(43) + "="}) {
            try (Server server = new Server(redirect(cookie))) {
                assertEquals(DshAuthSession.Status.INVALID_RESPONSE, server.exchange().status);
            }
        }
    }
    @Test public void wrongPortAndOldGenerationNeverConnect() throws Exception {
        try (Server server = new Server()) {
            assertEquals(DshAuthSession.Status.INVALID_RESPONSE,
                    DshAuthSession.exchange(server.url(), server.socket.getLocalPort() + 1, () -> true, 100).status);
            assertEquals(DshAuthSession.Status.CANCELLED,
                    DshAuthSession.exchange(server.url(), server.socket.getLocalPort(), () -> false, 100).status);
            assertTrue(server.requests.isEmpty());
        }
    }
    @Test public void responseFromStoppedGenerationIsDiscarded() throws Exception {
        try (Server server = new Server(redirect(COOKIE), "200 OK\r\n")) {
            AtomicBoolean first = new AtomicBoolean(true);
            DshAuthSession.Result result = DshAuthSession.exchange(server.url(), server.socket.getLocalPort(),
                    () -> first.getAndSet(false), 1500);
            assertEquals(DshAuthSession.Status.CANCELLED, result.status); assertNull(result.cookie);
        }
    }
    @Test public void unavailableServerHasBoundedWait() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        long start = System.nanoTime();
        assertEquals(DshAuthSession.Status.NOT_READY,
                DshAuthSession.exchange("http://127.0.0.1:" + port + "/?token=" + TOKEN, port, () -> true, 200).status);
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000);
    }
}
