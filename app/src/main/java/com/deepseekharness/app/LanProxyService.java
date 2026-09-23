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
import com.deepseekharness.app.util.HttpProtocol;
import com.deepseekharness.app.util.SocketDispatch;

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
        final SocketDispatch dispatch = new SocketDispatch("dsha-lan",32,8,16,8);
        volatile boolean active = true;
        /** Set only after the ServerSocket bind has completed successfully. */
        volatile boolean bound;
        volatile ServerSocket server;
        volatile Thread acceptThread;

        ProxyRun(long epoch, long generation, int backendPort) {
            this.epoch = epoch;
            this.generation = generation;
            this.backendPort = backendPort;
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
        ProxyRun run = new ProxyRun(++nextRunEpoch, generation, resolvedBackend);
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
                    client.setSoTimeout(HttpProtocol.LAN.timeoutMs);
                    if (!isActiveRun(run)) {
                        closeQuietly(client);
                        continue;
                    }
                    SocketDispatch.Ticket ticket=run.dispatch.accept(client);
                    if(ticket==null)continue;
                    try {
                        new LanConnection(ticket,run).next(true);
                    } catch (Throwable rejected) {
                        ticket.close();
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
            run.dispatch.close();
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
        run.dispatch.close();
    }

    private static void closeRunSockets(ProxyRun run) {
        if (run == null) return;
        run.dispatch.close();
    }

    /** 有限连接的各阶段转交所有权；流式正文不会占满短请求线程。 */
    private static final class LanConnection {
        final SocketDispatch.Ticket owner;final ProxyRun run;final InputStream in;final OutputStream out;
        LanConnection(SocketDispatch.Ticket owner,ProxyRun run)throws IOException{
            this.owner=owner;this.run=run;in=new java.io.BufferedInputStream(owner.socket.getInputStream(),8192);out=owner.socket.getOutputStream();
        }
        void next(boolean first){
            if(!isActiveRun(run)||owner.ended()){owner.close();return;}
            long until=first?owner.acceptedAt+HttpProtocol.LAN.timeoutMs*1000000L:HttpProtocol.deadline(HttpProtocol.LAN.timeoutMs);
            if(!run.dispatch.request(owner,()->read(until)))owner.close();
        }
        void fail(int status){
            try{writePlain(out,"HTTP/1.1 "+status+" Request Failed","Request could not be processed");}catch(IOException ignored){}finally{owner.close();}
        }
        void read(long until){
            try{
                if(!isActiveRun(run)){owner.close();return;}
                HttpProtocol.Head request=HttpProtocol.readHead(in,owner.socket,HttpProtocol.LAN,until,true);
                if(request==null){owner.close();return;}
                String ip=owner.socket.getInetAddress()==null?"":owner.socket.getInetAddress().getHostAddress();
                if(shouldLogConn(ip))log(com.deepseekharness.app.util.UiText.text("连接来自 ")+ip);
                String token=lanToken;int authorized=LanAuth.tokenOk(request.raw(),token);
                if(authorized==LanAuth.AUTH_DENY){fail(401);return;}
                if(authorized==LanAuth.AUTH_OK_SET_COOKIE){try{writeLanRedirect(out,token);}finally{owner.close();}return;}
                AuthSnapshot auth=snapshotDshAuth(run);if(auth==null){fail(503);return;}
                boolean websocket=request.websocketRequest();
                Exchange exchange=new Exchange(this,request,auth,websocket);
                boolean stream=request.requestBody().streaming()||websocket||request.value("Accept").toLowerCase(Locale.ROOT).contains("text/event-stream");
                if(stream){if(!run.dispatch.stream(owner,()->exchange.serve(true)))fail(503);}
                else exchange.serve(false);
            }catch(java.net.SocketTimeoutException timeout){fail(408);}
            catch(HttpProtocol.Failure invalid){fail(invalid.status);}
            catch(IOException error){owner.close();}
        }
    }
    private static final class Exchange {
        final LanConnection client;final HttpProtocol.Head request;final AuthSnapshot auth;final boolean websocket;
        Socket back;InputStream bin;OutputStream bout;boolean responseStarted;
        Exchange(LanConnection client,HttpProtocol.Head request,AuthSnapshot auth,boolean websocket){this.client=client;this.request=request;this.auth=auth;this.websocket=websocket;}
        void serve(boolean longLane){
            try{
                if(!isActiveRun(client.run)||client.owner.ended()){client.owner.close();return;}
                back=new Socket();client.owner.attach(back);back.setSoTimeout(HttpProtocol.BODY_IDLE_MS);
                client.owner.socket.setSoTimeout(HttpProtocol.BODY_IDLE_MS);
                back.connect(new InetSocketAddress("127.0.0.1",client.run.backendPort),5000);
                bin=new java.io.BufferedInputStream(back.getInputStream(),8192);bout=back.getOutputStream();
                // 保留已有代次/鉴权写入临界区；逐跳头在注入本轮内部 Cookie 之前处理。
                String forwarded=writeCurrentRequest(client.run,auth,request.forwarded(websocket,!websocket),bout);
                if(forwarded==null){client.fail(503);return;}
                if(request.value("Expect").equalsIgnoreCase("100-continue")){client.out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));client.out.flush();}
                HttpProtocol.copyBody(request.requestBody(),client.in,bout);
                long until=HttpProtocol.deadline(HttpProtocol.BODY_IDLE_MS);HttpProtocol.Head response;int interim=0;
                while(true){
                    response=HttpProtocol.readHead(bin,back,HttpProtocol.LAN,until,false);if(response==null)throw new IOException("HTTP_BACKEND_EOF");
                    if(response.status>=200||response.status==101)break;
                    if(++interim>8)throw new IOException("HTTP_TOO_MANY_INFORMATIONAL");
                    response.responseBody(request);
                    client.out.write(rewriteResponse(response.forwarded(false,false),client.run.backendPort).getBytes(StandardCharsets.ISO_8859_1));client.out.flush();
                }
                HttpProtocol.Body body=response.responseBody(request);
                if(response.status==401||response.status==403)invalidateDshAuth(client.run,auth);
                boolean persistent=!request.close()&&body.kind!=HttpProtocol.Kind.CLOSE&&body.kind!=HttpProtocol.Kind.UPGRADE;
                String head=rewriteResponse(response.forwarded(body.kind==HttpProtocol.Kind.UPGRADE,!persistent),client.run.backendPort);
                boolean eventStream=response.value("Content-Type").toLowerCase(Locale.ROOT).startsWith("text/event-stream");
                if(!longLane&&(body.streaming()||eventStream)){
                    if(!client.run.dispatch.stream(client.owner,()->transfer(body,head,persistent,eventStream)))client.fail(503);
                    return;
                }
                transfer(body,head,persistent,eventStream);
            }catch(HttpProtocol.Failure invalid){if(!responseStarted)client.fail(invalid.status==400?400:502);else client.owner.close();}
            catch(IOException error){if(!responseStarted)client.fail(502);else client.owner.close();}
        }
        void transfer(HttpProtocol.Body body,String head,boolean persistent,boolean eventStream){
            try{
                if(!isActiveRun(client.run)||client.owner.ended()){client.owner.close();return;}
                back.setSoTimeout(eventStream?0:HttpProtocol.BODY_IDLE_MS);
                responseStarted=true;client.out.write(head.getBytes(StandardCharsets.ISO_8859_1));client.out.flush();
                if(body.kind==HttpProtocol.Kind.UPGRADE){tunnel();return;}
                HttpProtocol.copyBody(body,bin,client.out);client.owner.detach(back);
                if(persistent)client.next(false);else client.owner.close();
            }catch(IOException failure){client.owner.close();}
        }
        void tunnel()throws IOException{
            client.owner.socket.setSoTimeout(0);back.setSoTimeout(0);client.owner.socket.setKeepAlive(true);back.setKeepAlive(true);
            java.util.concurrent.Future<?> up=client.run.dispatch.pump(client.owner,()->{
                try{HttpProtocol.copyToEnd(client.in,bout);back.shutdownOutput();}catch(IOException failure){client.owner.close();}
            });
            try{
                HttpProtocol.copyToEnd(bin,client.out);client.owner.socket.shutdownOutput();
                try{up.get(3000,java.util.concurrent.TimeUnit.MILLISECONDS);}catch(java.util.concurrent.TimeoutException ignored){}
                catch(java.util.concurrent.ExecutionException failure){client.owner.close();}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            }finally{client.owner.close();up.cancel(true);}
        }
    }
    /** 仅返回计数，不包含请求、地址或鉴权资料。 */
    public static java.util.Map<String,Long> resourceMetrics(){ProxyRun run=activeRun;return run==null?java.util.Collections.emptyMap():run.dispatch.metrics();}

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
