package com.deepseekharness.app;

import android.content.Context;
import android.util.Log;

import com.deepseekharness.app.util.Constants;
import com.deepseekharness.app.util.SensitiveData;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional LAN bridge for the dsh Web UI.
 *
 * <p>dsh itself is always loopback-only. This service is the only component
 * that binds the LAN address and it never forwards the dsh launch token or a
 * backend Set-Cookie header to a LAN client. The dsh BrowserAuth cookie is
 * captured after an internal loopback exchange and retained only in memory.</p>
 */
public final class LanProxyService {

    private static final String TAG = "DSHA-LanProxy";
    private static final String PREF_LAN_TOKEN = Constants.KEY_LAN_TOKEN_V2;
    private static final String LAN_COOKIE_MAX_AGE = "2592000";
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final int LAN_PORT = 3081;
    public static final int DEFAULT_BACKEND_PORT = 3080;

    private static volatile String lanToken = "";

    /**
     * BrowserAuth state is published as one immutable reference.  Keeping the
     * generation and cookie in separate volatile fields would allow a request
     * to observe a generation from one dsh run and a cookie from another.
     */
    private static final class AuthState {
        final String cookie;
        final long generation;

        AuthState(String cookie, long generation) {
            this.cookie = cookie;
            this.generation = generation;
        }
    }

    private static final AuthState NO_DSH_AUTH = new AuthState("", 0);
    private static volatile AuthState dshAuth = NO_DSH_AUTH;

    /**
     * Opaque, immutable request credential lease.  It is deliberately not
     * serializable and exposes no cookie accessor; it only lets a handler
     * prove its prior snapshot still belongs to the live dsh generation.
     */
    static final class AuthSnapshot {
        private final AuthState state;

        private AuthSnapshot(AuthState state) {
            this.state = state;
        }
    }

    /** One bind/listener lifetime.  An old accept loop must never tear down a new one. */
    private static final class ProxyRun {
        final long epoch;
        final long generation;
        final int backendPort;
        final ExecutorService pool;
        final ConcurrentHashMap<Socket, Boolean> clients = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Socket, Boolean> backends = new ConcurrentHashMap<>();
        volatile boolean active = true;
        /** Set only after the ServerSocket bind has completed successfully. */
        volatile boolean bound;
        volatile ServerSocket server;
        volatile Thread acceptThread;

        ProxyRun(long epoch, long generation, int backendPort, ExecutorService pool) {
            this.epoch = epoch;
            this.generation = generation;
            this.backendPort = backendPort;
            this.pool = pool;
        }
    }

    private static volatile ProxyRun activeRun;
    private static long nextRunEpoch;
    /* Retained for the package-private rewriteResponse(String) test helper. */
    private static volatile int backendPort = DEFAULT_BACKEND_PORT;
    private static volatile String logPath = "";
    private static final Object LOG_LOCK = new Object();
    private static final java.util.Map<String, Long> lastConnLog =
            new java.util.concurrent.ConcurrentHashMap<>();

    private LanProxyService() {
    }

    /** Return the persistent 256-bit LAN credential, generating it if needed. */
    public static synchronized String getLanToken(Context ctx) {
        if (isValidLanToken(lanToken)) return lanToken;
        String stored = "";
        try {
            if (ctx != null) {
                stored = ctx.getApplicationContext()
                        .getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                        .getString(PREF_LAN_TOKEN, "");
            }
        } catch (Throwable ignored) {
        }
        if (isValidLanToken(stored)) {
            lanToken = stored;
            return stored;
        }
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String generated = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        lanToken = generated;
        try {
            if (ctx != null) {
                ctx.getApplicationContext().getSharedPreferences(Constants.PREFS, Context.MODE_PRIVATE)
                        .edit().putString(PREF_LAN_TOKEN, generated).apply();
            }
        } catch (Throwable ignored) {
        }
        return generated;
    }

    private static boolean isValidLanToken(String value) {
        return value != null && value.length() == 43 && value.matches("[A-Za-z0-9_-]{43}");
    }

    /**
     * Publish one BrowserAuth cookie for the current dsh generation. The
     * caller must have obtained it from a loopback 303 exchange.
     */
    public static synchronized boolean setDshAuthCookie(String cookie, long generation) {
        if (!isValidDshCookie(cookie) || generation <= 0) return false;
        String normalized = cookie.trim();
        ProxyRun run = activeRun;
        // A different dsh generation must never reuse the old listener. A
        // same-generation cookie refresh is safe: existing workers retain an
        // old AuthState snapshot and fail the identity check below, while new
        // requests receive only the replacement cookie.
        if (run != null && run.generation != generation) {
            deactivateRunLocked(run);
            closeRunSockets(run);
        }
        dshAuth = new AuthState(normalized, generation);
        return true;
    }

    /** Clear the in-memory backend credential when dsh stops or restarts. */
    public static synchronized void clearDshAuth(long generation) {
        AuthState current = dshAuth;
        if (generation == 0 || current.generation == generation) {
            dshAuth = NO_DSH_AUTH;
            ProxyRun run = activeRun;
            if (run != null && (generation == 0 || run.generation == generation)) {
                deactivateRunLocked(run);
                closeRunSockets(run);
            }
        }
    }

    public static boolean hasDshAuth(long generation) {
        return hasDshAuth(dshAuth, generation);
    }

    private static boolean hasDshAuth(AuthState state, long generation) {
        return state != null && generation > 0 && generation == state.generation
                && isValidDshCookie(state.cookie);
    }

    /** Return an opaque coherent credential snapshot for the requested generation. */
    static synchronized AuthSnapshot snapshotDshAuth(long generation) {
        AuthState current = dshAuth;
        return hasDshAuth(current, generation) ? new AuthSnapshot(current) : null;
    }

    /** True only while a previously acquired credential snapshot is still current. */
    static synchronized boolean isCurrentDshAuth(AuthSnapshot snapshot) {
        return snapshot != null && dshAuth == snapshot.state
                && hasDshAuth(snapshot.state, snapshot.state.generation);
    }

    private static synchronized AuthSnapshot snapshotDshAuth(ProxyRun run) {
        if (!isActiveRunLocked(run)) return null;
        AuthState current = dshAuth;
        return hasDshAuth(current, run.generation) ? new AuthSnapshot(current) : null;
    }

    private static synchronized boolean isCurrentDshAuth(ProxyRun run, AuthSnapshot snapshot) {
        return snapshot != null && isActiveRunLocked(run) && dshAuth == snapshot.state
                && hasDshAuth(snapshot.state, run.generation);
    }

    private static synchronized void invalidateDshAuth(ProxyRun run, AuthSnapshot snapshot) {
        if (snapshot != null && isActiveRunLocked(run) && dshAuth == snapshot.state) {
            dshAuth = NO_DSH_AUTH;
        }
    }

    private static boolean isValidDshCookie(String cookie) {
        if (cookie == null) return false;
        int semi = cookie.indexOf(';');
        if (semi >= 0) cookie = cookie.substring(0, semi);
        int eq = cookie.indexOf('=');
        if (eq <= 0 || eq != cookie.lastIndexOf('=')) return false;
        String name = cookie.substring(0, eq);
        String value = cookie.substring(eq + 1);
        return name.startsWith("dsh-auth-") && name.length() > "dsh-auth-".length()
                && name.matches("dsh-auth-[A-Za-z0-9_-]+")
                && value.matches("[A-Za-z0-9_.-]+") && value.length() <= 4096;
    }

    /**
     * Select the BrowserAuth cookie from an HTTP response without assuming it
     * is the first {@code Set-Cookie} field. Android URLConnection variants
     * differ in whether repeated headers are returned as a list or one comma
     * separated value, so inspect both shapes and accept only dsh-auth pairs.
     */
    static String extractDshAuthCookie(
            java.util.Map<String, ? extends java.util.List<String>> headers) {
        if (headers == null || headers.isEmpty()) return null;
        for (java.util.Map.Entry<String, ? extends java.util.List<String>> entry
                : headers.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().equalsIgnoreCase("Set-Cookie")) {
                continue;
            }
            java.util.List<String> values = entry.getValue();
            if (values == null) continue;
            for (String value : values) {
                if (value == null || value.isEmpty()) continue;
                for (String segment : value.split(",")) {
                    int semi = segment.indexOf(';');
                    String pair = (semi >= 0 ? segment.substring(0, semi) : segment).trim();
                    if (isValidDshCookie(pair)) return pair;
                }
            }
        }
        return null;
    }

    /** Compatibility overload: without an exchanged BrowserAuth cookie it does nothing. */
    public static synchronized void start(String rootfsDir, Context ctx) {
        start(rootfsDir, ctx, DEFAULT_BACKEND_PORT, dshAuth.generation);
    }

    /** Compatibility overload retained for service callers. */
    public static synchronized void start(String rootfsDir, Context ctx, int backend) {
        start(rootfsDir, ctx, backend, dshAuth.generation);
    }

    /** Start the LAN bridge only after a current BrowserAuth cookie is available. */
    public static synchronized void start(String rootfsDir, Context ctx, int backend, long generation) {
        if (!com.deepseekharness.app.bridge.LocalNetworkAccess.granted(ctx)) {
            stopLanListener();
            return;
        }
        if (isActiveRunLocked(activeRun)) return;
        if (!hasDshAuth(generation)) return;
        int resolvedBackend = backend > 0 && backend <= 65535 && backend != LAN_PORT
                ? backend : DEFAULT_BACKEND_PORT;
        getLanToken(ctx);
        if (!isValidLanToken(lanToken)) return;
        logPath = rootfsDir == null ? "" : rootfsDir + "/root/dsh-lan.log";
        ExecutorService executor = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "dsha-lan-proxy");
            t.setDaemon(true);
            return t;
        });
        ProxyRun run = new ProxyRun(++nextRunEpoch, generation, resolvedBackend, executor);
        // Keep the old compatibility field for rewriteResponse(String), but request
        // handling always uses the immutable port captured by ProxyRun.
        backendPort = resolvedBackend;
        activeRun = run;
        Thread accept = new Thread(() -> acceptLoop(run), "dsha-lan-accept-" + run.epoch);
        accept.setDaemon(true);
        run.acceptThread = accept;
        accept.start();
    }

    private static void acceptLoop(ProxyRun run) {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress("0.0.0.0", LAN_PORT));
            synchronized (LanProxyService.class) {
                if (!isActiveRunLocked(run)) {
                    closeQuietly(ss);
                    return;
                }
                run.server = ss;
                run.bound = true;
            }
            log(com.deepseekharness.app.util.UiText.text("LAN 代理已启动：0.0.0.0:") + LAN_PORT + " -> 127.0.0.1:" + run.backendPort);
            while (isActiveRun(run)) {
                try {
                    Socket client = ss.accept();
                    client.setSoTimeout(120000);
                    if (!isActiveRun(run)) {
                        closeQuietly(client);
                        continue;
                    }
                    run.clients.put(client, Boolean.TRUE);
                    try {
                        run.pool.execute(() -> handle(client, run));
                    } catch (Throwable rejected) {
                        run.clients.remove(client);
                        closeQuietly(client);
                    }
                } catch (IOException e) {
                    if (isActiveRun(run)) log(com.deepseekharness.app.util.UiText.text("接收连接失败：") + e.getClass().getSimpleName());
                }
            }
        } catch (IOException e) {
            if (isActiveRun(run)) log(com.deepseekharness.app.util.UiText.text("LAN 代理绑定失败：") + e.getClass().getSimpleName());
        } finally {
            synchronized (LanProxyService.class) {
                closeQuietly(ss);
                if (run.server == ss) run.server = null;
                run.bound = false;
                if (activeRun == run) {
                    run.active = false;
                    activeRun = null;
                }
            }
            closeRunSockets(run);
            run.pool.shutdownNow();
        }
    }

    public static void stop() {
        stop(0);
    }

    /**
     * Stop only the externally reachable listener after the user turns LAN
     * sharing off.  The local dsh BrowserAuth cookie remains in memory so the
     * loopback WebView does not become "unauthenticated" merely because the
     * optional proxy was disabled.  dsh stop/restart still uses {@link #stop()}
     * or {@link #clearDshAuth(long)} and clears that credential immediately.
     */
    public static void stopLanListener() {
        ProxyRun run;
        synchronized (LanProxyService.class) {
            run = activeRun;
            if (run != null) deactivateRunLocked(run);
        }
        if (run != null) {
            closeRunSockets(run);
            log(com.deepseekharness.app.util.UiText.text("LAN 代理已停止"));
        }
    }

    /**
     * Stop only the proxy run belonging to {@code generation}.  A controller
     * clearing an old BrowserAuth handoff uses this overload so a delayed
     * cleanup cannot tear down a newer dsh/LAN generation that has already
     * exchanged its cookie.  Generation {@code 0} retains the unconditional
     * service-shutdown behavior used by lifecycle callbacks.
     */
    public static void stop(long generation) {
        ProxyRun run;
        synchronized (LanProxyService.class) {
            run = activeRun;
            if (generation > 0) {
                if (run == null || run.generation != generation) run = null;
                else deactivateRunLocked(run);
            } else if (run != null) {
                deactivateRunLocked(run);
            }
            // Stopping the listener is also a hard BrowserAuth boundary: an
            // old worker must not be able to resume after dsh comes back.
            if (generation <= 0 || dshAuth.generation == generation) {
                dshAuth = NO_DSH_AUTH;
            }
        }
        if (run != null) closeRunSockets(run);
        if (generation <= 0 || run != null) log(com.deepseekharness.app.util.UiText.text("LAN 代理已停止"));
    }

    public static boolean isRunning() {
        ProxyRun run = activeRun;
        return isActiveRun(run) && run.bound;
    }

    /** Explicit name for callers that need to distinguish bind success from startup in flight. */
    public static boolean isBound() {
        return isRunning();
    }

    private static boolean isActiveRun(ProxyRun run) {
        return run != null && run.active && activeRun == run;
    }

    private static boolean isActiveRunLocked(ProxyRun run) {
        return run != null && run.active && activeRun == run;
    }

    private static void deactivateRunLocked(ProxyRun run) {
        if (run == null) return;
        if (activeRun == run) activeRun = null;
        run.active = false;
        closeQuietly(run.server);
        run.server = null;
        if (run.acceptThread != null) run.acceptThread.interrupt();
        run.pool.shutdownNow();
    }

    private static void closeRunSockets(ProxyRun run) {
        if (run == null) return;
        for (Socket client : run.clients.keySet()) closeQuietly(client);
        for (Socket back : run.backends.keySet()) closeQuietly(back);
    }

    private static void handle(Socket client, ProxyRun run) {
        String ip = client.getInetAddress() == null ? "" : client.getInetAddress().getHostAddress();
        if (shouldLogConn(ip)) log(com.deepseekharness.app.util.UiText.text("连接来自 ") + ip);
        try (Socket c = client) {
            InputStream in = c.getInputStream();
            OutputStream out = c.getOutputStream();
            byte[] requestBuffer = new byte[65536];
            while (isActiveRun(run)) {
                int n = readHeader(in, requestBuffer);
                if (n <= 0) return;
                String head = new String(requestBuffer, 0, n, StandardCharsets.ISO_8859_1);
                int nl = head.indexOf('\n');
                if (nl < 0) return;
                String line = head.substring(0, nl).trim();
                if (line.isEmpty()) return;

                // Use one LAN token snapshot for validation and the 303 cookie so a
                // token refresh can never validate with one value and set another.
                String currentLanToken = lanToken;
                int auth = LanAuth.tokenOk(head, currentLanToken);
                if (auth == LanAuth.AUTH_DENY) {
                    writePlain(out, "HTTP/1.1 401 Unauthorized", "LAN token required");
                    return;
                }
                if (auth == LanAuth.AUTH_OK_SET_COOKIE) {
                    writeLanRedirect(out, currentLanToken);
                    return;
                }
                AuthSnapshot authSnapshot = snapshotDshAuth(run);
                if (authSnapshot == null) {
                    writePlain(out, "HTTP/1.1 503 Service Unavailable",
                            "dsh authentication is not ready");
                    return;
                }

                boolean websocket = containsIgnoreCase(head, "Upgrade: websocket")
                        || (line.contains("HTTP/1.1") && containsIgnoreCase(head, "Connection: Upgrade"));
                Socket back = new Socket();
                run.backends.put(back, Boolean.TRUE);
                try {
                    back.setSoTimeout(120000);
                    back.connect(new InetSocketAddress("127.0.0.1", run.backendPort), 5000);
                    InputStream bin = back.getInputStream();
                    OutputStream bout = back.getOutputStream();
                    /*
                     * The final generation check and request-header write are
                     * one critical section.  stop()/restart takes this same
                     * lock before invalidating state, so a stale worker either
                     * writes before stop linearizes or returns 503 without
                     * sending an unauthenticated backend request.
                     */
                    String forwarded = writeCurrentRequest(run, authSnapshot, head, bout);
                    if (forwarded == null) {
                        writePlain(out, "HTTP/1.1 503 Service Unavailable",
                                "dsh authentication is not ready");
                        return;
                    }
                    long requestLength = contentLength(forwarded);
                    if (requestLength > 0) pipeBytes(in, bout, requestLength);
                    else if (containsIgnoreCase(forwarded, "Transfer-Encoding: chunked")) pipeChunked(in, bout);

                    byte[] responseBuffer = new byte[65536];
                    int rn = readHeader(bin, responseBuffer);
                    if (rn <= 0) return;
                    String response = new String(responseBuffer, 0, rn, StandardCharsets.ISO_8859_1);
                    if (response.startsWith("HTTP/1.1 401") || response.startsWith("HTTP/1.1 403")) {
                        // A restarted dsh invalidates the old BrowserAuth cookie. Do not
                        // continue proxying as an unauthenticated raw forwarder.
                        invalidateDshAuth(run, authSnapshot);
                    }
                    String cleanResponse = rewriteResponse(response, run.backendPort);
                    out.write(cleanResponse.getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    boolean upgraded = websocket || response.startsWith("HTTP/1.1 101")
                            || containsIgnoreCase(response, "Upgrade: websocket");
                    if (upgraded) {
                        pumpBidirectional(c, back, in, out, bin, bout);
                        return;
                    }
                    long length = contentLength(response);
                    if (length > 0) pipeBytes(bin, out, length);
                    else if (containsIgnoreCase(response, "Transfer-Encoding: chunked")) pipeChunked(bin, out);
                    else pumpStream(bin, out);
                    if (containsIgnoreCase(response, "Connection: close")) return;
                } finally {
                    run.backends.remove(back);
                    closeQuietly(back);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            run.clients.remove(client);
        }
    }

    /**
     * Write an authenticated request only if this handler still owns the
     * current listener and BrowserAuth snapshot.  Returning null is a
     * fail-closed state transition, never a signal to forward without cookie.
     */
    private static String writeCurrentRequest(ProxyRun run, AuthSnapshot snapshot,
                                              String head, OutputStream out) throws IOException {
        synchronized (LanProxyService.class) {
            if (!isCurrentDshAuth(run, snapshot)) return null;
            String forwarded = rewriteRequest(head, run.backendPort, snapshot.state.cookie);
            if (forwarded.isEmpty()) return null;
            out.write(forwarded.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            return forwarded;
        }
    }

    private static void writeLanRedirect(OutputStream out, String token) throws IOException {
        if (!isValidLanToken(token)) {
            writePlain(out, "HTTP/1.1 503 Service Unavailable", "LAN token is not ready");
            return;
        }
        String response = "HTTP/1.1 303 See Other\r\n"
                + "Location: /\r\nCache-Control: no-store\r\n"
                + "Referrer-Policy: no-referrer\r\n"
                + "Set-Cookie: " + LanAuth.COOKIE_NAME + "=" + token
                + "; Path=/; Max-Age=" + LAN_COOKIE_MAX_AGE
                + "; HttpOnly; SameSite=Strict\r\n"
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void writePlain(OutputStream out, String status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String header = status + "\r\nContent-Type: text/plain; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nContent-Length: " + bytes.length
                + "\r\nConnection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    /** Rewrite all browser-controlled authority/cookie fields for loopback. */
    static String rewriteRequest(String head, int backend, String dshCookie) {
        // This helper is intentionally fail-closed too.  The handler already
        // has a generation-bound snapshot, but no caller should ever be able
        // to turn an absent/invalid BrowserAuth cookie into a raw backend
        // request by using the rewrite helper directly.
        if (head == null || !isValidDshCookie(dshCookie)) return "";
        StringBuilder result = new StringBuilder();
        boolean first = true;
        boolean host = false;
        boolean origin = false;
        boolean secFetchSite = false;
        boolean cookieAdded = false;
        for (String line : head.split("\\r?\\n", -1)) {
            if (line.isEmpty()) {
                // Browser navigation requests may omit these fields. Add their
                // loopback equivalents so dsh never sees an ambiguous origin.
                if (!host) {
                    result.append("Host: 127.0.0.1:").append(backend).append("\r\n");
                    host = true;
                }
                if (!origin) {
                    result.append("Origin: http://127.0.0.1:").append(backend).append("\r\n");
                    origin = true;
                }
                if (!secFetchSite) {
                    result.append("Sec-Fetch-Site: same-origin\r\n");
                    secFetchSite = true;
                }
                if (!cookieAdded && isValidDshCookie(dshCookie)) {
                    result.append("Cookie: ").append(dshCookie).append("\r\n");
                    cookieAdded = true;
                }
                result.append("\r\n");
                continue;
            }
            if (first) {
                result.append(LanAuth.stripTokenFromRequestLine(line)).append("\r\n");
                first = false;
                continue;
            }
            int colon = line.indexOf(':');
            String key = colon > 0 ? line.substring(0, colon).trim() : "";
            String lower = key.toLowerCase(Locale.ROOT);
            if (lower.equals("host")) {
                result.append("Host: 127.0.0.1:").append(backend).append("\r\n");
                host = true;
            } else if (lower.equals("origin")) {
                result.append("Origin: http://127.0.0.1:").append(backend).append("\r\n");
                origin = true;
            } else if (lower.equals("sec-fetch-site")) {
                result.append("Sec-Fetch-Site: same-origin\r\n");
                secFetchSite = true;
            } else if (lower.equals("referer") || lower.equals("cookie") || lower.equals("authorization")
                    || (lower.contains("dsha") && lower.contains("token"))) {
                // Never pass a LAN credential, a stale external cookie, or a
                // similarly named compatibility header to dsh.
            } else {
                result.append(line).append("\r\n");
            }
        }
        if (!host) {
            // Host belongs after the request line.  Inserting at offset zero
            // would turn it into the first line and make the request invalid.
            int firstBreak = result.indexOf("\r\n");
            String hostLine = "Host: 127.0.0.1:" + backend + "\r\n";
            if (firstBreak >= 0) result.insert(firstBreak + 2, hostLine);
            else result.insert(0, hostLine);
        }
        if (!cookieAdded && isValidDshCookie(dshCookie)) {
            int end = result.lastIndexOf("\r\n\r\n");
            if (end >= 0) result.insert(end, "Cookie: " + dshCookie + "\r\n");
        }
        // 归一化结尾（与 rewriteResponse 相同）：split(-1) 会给请求头后多留一个 \r\n，
        // 对 GET 无影响，但对 POST 会把正文顶错位（Content-Length 少算 2 字节）→ dsh 400。
        // 恰好保留一个空行分隔，正文从正确偏移开始。
        String out = result.toString();
        while (out.endsWith("\r\n")) out = out.substring(0, out.length() - 2);
        return out + "\r\n\r\n";
    }

    /** Drop backend cookies and rewrite redirects so dsh credentials stay internal. */
    static String rewriteResponse(String head) {
        return rewriteResponse(head, backendPort);
    }

    private static String rewriteResponse(String head, int responseBackendPort) {
        StringBuilder result = new StringBuilder();
        // 注意：split 默认限位会把「头与正文之间的空行」这个末尾空串也删掉（少了 \r\n\r\n），
        // 而 -1 又会多留一个空串（多了 \r\n）。两者都会让客户端解析失败 ——
        // 这里用 -1 处理后统一归一化结尾：剥掉多余 CRLF，恰好保留一个空行分隔。
        for (String line : head.split("\\r?\\n", -1)) {
            if (line.isEmpty()) {
                result.append("\r\n");
                continue;
            }
            int colon = line.indexOf(':');
            String key = colon > 0 ? line.substring(0, colon).trim() : "";
            if (key.equalsIgnoreCase("Set-Cookie") || key.equalsIgnoreCase("Set-Cookie2")
                    || key.equalsIgnoreCase("Authorization")
                    || key.equalsIgnoreCase("Proxy-Authorization")
                    || key.equalsIgnoreCase("X-Dsha-Token")
                    || key.equalsIgnoreCase("X-Token")
                    || key.toLowerCase(Locale.ROOT).startsWith("dsh-auth-")) continue;
            // The LAN endpoint is a same-origin proxy.  Do not expose a
            // permissive backend CORS policy (especially "*") to a browser;
            // keeping it would invite callers to treat the proxy as a
            // cross-origin API and would be misleading once credentials are
            // involved.
            if (key.equalsIgnoreCase("Access-Control-Allow-Origin")
                    || key.equalsIgnoreCase("Access-Control-Allow-Credentials")
                    || key.equalsIgnoreCase("Access-Control-Allow-Headers")
                    || key.equalsIgnoreCase("Access-Control-Allow-Methods")) continue;
            if (key.equalsIgnoreCase("Location")) {
                String value = line.substring(colon + 1).trim();
                // Drop the backend authority, leaving the original path's
                // leading slash intact. Replacing the authority with "/"
                // would turn "/?x=1" into "//?x=1".
                value = value.replace("http://127.0.0.1:" + responseBackendPort, "");
                value = value.replace("http://localhost:" + responseBackendPort, "");
                value = LanAuth.stripTokenFromUrl(value);
                result.append("Location: ").append(value).append("\r\n");
            } else {
                result.append(line).append("\r\n");
            }
        }
        String out = result.toString();
        // 归一化结尾：多余的 CRLF 会让 chunked 正文首行解析成空十六进制长度
        while (out.endsWith("\r\n")) out = out.substring(0, out.length() - 2);
        return out + "\r\n\r\n";
    }

    private static boolean shouldLogConn(String ip) {
        long now = System.currentTimeMillis();
        Long previous = lastConnLog.get(ip);
        if (previous != null && now - previous < 60000) return false;
        if (lastConnLog.size() > 256) lastConnLog.clear();
        lastConnLog.put(ip, now);
        return true;
    }

    private static void log(String message) {
        // The same line is sent to logcat and the persistent diagnostic file.
        // Sanitize once at this boundary so future call sites cannot leak a
        // BrowserAuth/LAN credential through either sink.
        message = SensitiveData.redact(message);
        Log.i(TAG, message);
        if (logPath.isEmpty()) return;
        synchronized (LOG_LOCK) {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(logPath, true)) {
                out.write((message + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Throwable ignored) {
            }
        }
    }

    private static int readHeader(InputStream in, byte[] buffer) throws IOException {
        int position = 0;
        int matched = 0;
        while (position < buffer.length) {
            int b = in.read();
            if (b < 0) return position == 0 ? -1 : position;
            buffer[position++] = (byte) b;
            if (matched == 0 && b == '\r') matched = 1;
            else if (matched == 1 && b == '\n') matched = 2;
            else if (matched == 2 && b == '\r') matched = 3;
            else if (matched == 3 && b == '\n') return position;
            else if (matched == 2 && b == '\n') return position;
            else matched = 0;
        }
        // A full buffer without a terminator is not a header. Forwarding that
        // truncated prefix would turn an oversized request into a different
        // request, so callers must close it fail-closed.
        return -2;
    }

    private static long contentLength(String head) {
        for (String line : head.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    return Math.max(0, Long.parseLong(line.substring(colon + 1).trim()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static boolean containsIgnoreCase(String value, String needle) {
        return value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static void pipeBytes(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[8192];
        long left = length;
        while (left > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) return;
            out.write(buffer, 0, n);
            left -= n;
        }
        out.flush();
    }

    private static void pipeChunked(InputStream in, OutputStream out) throws IOException {
        final int maxChunk = 1024 * 1024;
        while (true) {
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            int b;
            while ((b = in.read()) >= 0) {
                line.write(b);
                if (line.size() >= 2) {
                    byte[] bytes = line.toByteArray();
                    int n = bytes.length;
                    if (bytes[n - 2] == '\r' && bytes[n - 1] == '\n') break;
                }
                if (line.size() > 1024) return;
            }
            if (b < 0) return;
            String text = line.toString(StandardCharsets.ISO_8859_1.name()).trim();
            int size;
            try {
                size = Integer.parseInt(text.split(";", 2)[0].trim(), 16);
            } catch (NumberFormatException e) {
                return;
            }
            if (size < 0 || size > maxChunk) return;
            out.write(line.toByteArray());
            if (size == 0) {
                // 终结块是 0\r\n\r\n：补上第二个 \r\n，否则 chunked 流不完整，
                // 客户端（浏览器/curl）会判「chunk hex-length 非法」而断开
                out.write('\r');
                out.write('\n');
                out.flush();
                return;
            }
            pipeBytes(in, out, size);
            int c1 = in.read();
            int c2 = in.read();
            if (c1 != '\r' || c2 != '\n') return;
            out.write(c1);
            out.write(c2);
        }
    }

    private static void pumpStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            out.write(buffer, 0, n);
            out.flush();
        }
    }

    private static void pumpBidirectional(Socket client, Socket back, InputStream cin,
                                          OutputStream cout, InputStream bin, OutputStream bout) {
        try {
            client.setSoTimeout(0);
            back.setSoTimeout(0);
            client.setKeepAlive(true);
            back.setKeepAlive(true);
        } catch (Throwable ignored) {
        }
        Runnable close = () -> {
            closeQuietly(client);
            closeQuietly(back);
        };
        Thread up = new Thread(() -> {
            try {
                pumpStream(cin, bout);
            } catch (Throwable ignored) {
            }
            close.run();
        }, "dsha-lan-ws-up");
        up.setDaemon(true);
        up.start();
        try {
            pumpStream(bin, cout);
        } catch (Throwable ignored) {
        }
        close.run();
        try {
            up.join(3000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(ServerSocket socket) {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }
}
