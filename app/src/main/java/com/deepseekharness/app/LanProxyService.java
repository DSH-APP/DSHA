package com.deepseekharness.app;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * 局域网转发桥（Shizuku 式思路的轻量版）：
 * 在 App 侧监听 0.0.0.0:{@link #LAN_PORT}，把 HTTP 请求转发到本机 127.0.0.1:backendPort，
 * 并把 Host 头重写为 127.0.0.1:backendPort —— 后端 WebUI 的 Host 校验看到的是 loopback，
 * 天然放行，彻底绕开 CLI 的 0.0.0.0 拦截与 trusted-host 限制。支持 keep-alive、chunked、
 * WebSocket 升级（升级后双向透传）与 Location 重写（防重定向回 127.0.0.1）。
 */
public final class LanProxyService {

    private static final String TAG = "DSHA-LanProxy";
    /** LAN 桥访问 token：开启局域网时生成，访问需带 ?token= 或 X-DSHA-Token
     *  （防同 WiFi 任意设备访问 dsh → 官方明确拒绝 0.0.0.0 的原因）。
     *  首次生成后存 prefs（跨重启保持），启动页显示带 token 的地址。 */
    private static volatile String lanToken = "";

    /** 获取 LAN token（首次生成 16 位随机并持久化）。
     *
     *  <p>鉴权改成 fail-closed 之后，「token 为空」等于桥拒绝一切请求，所以这里
     *  必须保证拿得到值：先把新生成的值认到内存里再落盘，持久化失败（prefs 异常、
     *  存储满）顶多是下次启动换一个 token，不该让局域网功能整个不可用。
     *  原来的写法把赋值放在 try 的最后一行，任一步抛异常都会留下空 token。 */
    public static String getLanToken(android.content.Context ctx) {
        if (!lanToken.isEmpty()) return lanToken;
        String t = "";
        try {
            t = ctx.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                    .getString("lan_token", "");
        } catch (Throwable ignored) {   // 含 ctx == null
        }
        if (t == null || t.isEmpty()) {
            t = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            lanToken = t;               // 先认账
            try {
                ctx.getSharedPreferences("deepseekharness", android.content.Context.MODE_PRIVATE)
                        .edit().putString("lan_token", t).apply();
            } catch (Throwable ignored) {
            }
            return t;
        }
        lanToken = t;
        return t;
    }

    // 凭据判定、请求行改写、定长比较都在 LanAuth —— 那是一组纯字符串逻辑，
    // 抽出去是为了能在没有设备的情况下真跑断言（tools/pure-logic-test.sh）。

    /** 桥监听端口：WebUI 默认 3080，桥用 3081 避免端口冲突（用户访问 http://<手机IP>:3081/） */
    public static final int LAN_PORT = 3081;
    /** 全局后端端口：每次 start 时用当前配置覆盖（自定义端口场景必须跟随 WebUI）。 */
    public static final int DEFAULT_BACKEND_PORT = 3080;

    private static volatile int backendPort = DEFAULT_BACKEND_PORT;

    private static ServerSocket server;
    private static Thread acceptThread;
    private static volatile boolean running;
    /** 连接处理线程池（限制并发，防线程耗尽） */
    private static BridgeLimits.Workers pool;
    /** 启动时缓存局域网 IP（仅用于日志/就绪提示；Location 重写已改为实时取
     *  getLanAddress()，WiFi 切换后重定向地址依然正确，不依赖本缓存） */
    private static volatile String lanIp = "";
    /** rootfs 日志路径（终端可 tail /root/dsh-lan.log 查看桥状态） */
    private static volatile String logPath = "";
    /** dsh 0.1.3 BrowserAuth launch credential. It only ever goes to loopback. */
    private static volatile String upstreamLaunchToken = "";
    /** dsh stdout is the source of the per-process BrowserAuth URL. */
    private static volatile String webLogPath = "";

    private LanProxyService() {}

    /** 兼容旧调用：未传端口时保持默认 3080。 */
    public static synchronized void start(String rootfsDir, android.content.Context ctx) {
        int port;
        try {
            port = HarnessController.get(ctx).getPortInt();
        } catch (Throwable ignored) {
            port = DEFAULT_BACKEND_PORT;
        }
        start(rootfsDir, ctx, port);
    }

    /** 真正的启动入口：后端端口每次取自当前配置。 */
    public static synchronized void start(String rootfsDir, android.content.Context ctx, int backendPortArg) {
        if (running) return;
        int backend = backendPortArg > 0 && backendPortArg <= 65535 ? backendPortArg : DEFAULT_BACKEND_PORT;
        if (backend == LAN_PORT) backend = 3080; // 与桥监听端口冲突时回退默认（配置页已拦截，这里兜底）
        backendPort = backend;
        logPath = rootfsDir + "/root/dsh-lan.log";
        webLogPath = rootfsDir + "/root/dsh-web.log";
        clearUpstreamLaunchUrl();
        // 无条件初始化 token：鉴权是 fail-closed 的，空 token 会让桥拒绝一切请求。
        // getLanToken 内部已容忍 ctx == null（退化为内存 token，本次会话仍可用）。
        getLanToken(ctx);
        running = true;
        // 连接线程池：固定 8 线程（防局域网扫描/大量连接耗尽），daemon 线程
        pool = new BridgeLimits.Workers(8, "lanproxy");
        lanIp = HarnessController.getLanAddress();
        log("LAN 桥启动中: 0.0.0.0:" + LAN_PORT + " → 127.0.0.1:" + backend + " (LAN IP=" + lanIp + ")");
        acceptThread = new Thread(() -> {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                try {
                    server.bind(new InetSocketAddress("0.0.0.0", LAN_PORT));
                } catch (java.net.BindException be) {
                    // 端口被占时说清是谁的问题，否则表现成「局域网打不开」很难查
                    log("LAN 桥启动失败：端口 " + LAN_PORT + " 已被其它应用占用（" + be.getMessage() + "）");
                    android.util.Log.e("DSHA", "LAN 桥端口 " + LAN_PORT + " 被占用: " + be);
                    running = false;
                    BridgeLimits.Workers workers = pool;
                    if (workers != null) workers.shutdownNow();
                    pool = null;
                    return;
                }
                log("LAN 桥已就绪 ✓ 访问地址: http://" + (lanIp.isEmpty() ? "<手机IP>" : lanIp) + ":" + LAN_PORT + "/");
                while (running) {
                    try {
                        Socket client = server.accept();
                        client.setSoTimeout(15_000);
                        // 固定线程池：限制并发连接线程数（防局域网扫描/大量连接耗尽线程）
                        BridgeLimits.Workers workers = pool;
                        if (workers == null) client.close();
                        else workers.submitSocket(client, () -> handle(client));
                    } catch (IOException e) {
                        if (running) log("accept 异常: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                log("桥启动失败: " + e.getMessage());
                running = false;
            } finally {
                closeQuietly(server);
                server = null;
            }
        }, "lanproxy-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public static synchronized void stop() {
        if (running) {
            log("LAN 桥已停止");
        }
        running = false;
        if (acceptThread != null) acceptThread.interrupt();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        closeQuietly(server);
        server = null;
    }

    public static boolean isRunning() { return running; }

    /** Accept only a strict loopback URL parsed from dsh's own stdout. */
    static void setUpstreamLaunchUrl(String url, int expectedPort) {
        String token = WebLaunchUrl.tokenFromUrl(url, expectedPort);
        upstreamLaunchToken = token;
    }

    /** A new dsh process gets a new launch token; never reuse the previous one's. */
    static void clearUpstreamLaunchUrl() {
        upstreamLaunchToken = "";
    }

    /**
     * The launch page normally populates the token within one heartbeat. Read
     * the same local stdout as a fallback so a LAN request can still bootstrap
     * after Android recreated only the proxy service.
     */
    private static String upstreamTokenForBackend() {
        String token = upstreamLaunchToken;
        if (WebLaunchUrl.isValidToken(token)) return token;
        String path = webLogPath;
        if (path.isEmpty()) return "";
        try {
            java.io.File f = new java.io.File(path);
            if (!f.isFile() || f.length() == 0) return "";
            long start = Math.max(0, f.length() - 24576);
            byte[] data = new byte[(int) (f.length() - start)];
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r")) {
                raf.seek(start);
                int off = 0;
                while (off < data.length) {
                    int n = raf.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
                String log = new String(data, 0, off, java.nio.charset.StandardCharsets.UTF_8);
                String url = WebLaunchUrl.fromDshWebLog(log, backendPort);
                token = WebLaunchUrl.tokenFromUrl(url, backendPort);
                if (!token.isEmpty()) upstreamLaunchToken = token;
                return token;
            }
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** 日志上限与连接日志节流。
     *
     *  <p>桥的日志写在 rootfs 里（App 私有目录，主人的磁盘常年 97%），而
     *  「连接来自 X」这类行是<b>不需要通过鉴权就能触发</b>的 —— 同一 WiFi 下一次
     *  端口扫描、或者随便一个循环 connect，就能让这个文件一直涨。属于放大面：
     *  攻击者不需要 token 也能消耗我们的磁盘。轮转 + 同 IP 节流两头掐。 */
    private static final long LOG_MAX_BYTES = 512 * 1024;
    private static final Object LOG_LOCK = new Object();
    private static final java.util.Map<String, Long> lastConnLog =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 同一来源 IP 60 秒内只记一次连接日志。 */
    private static boolean shouldLogConn(String ip) {
        long now = System.currentTimeMillis();
        Long last = lastConnLog.get(ip);
        if (last != null && now - last < 60000) return false;
        if (lastConnLog.size() > 256) lastConnLog.clear();   // 不让它无界增长
        lastConnLog.put(ip, now);
        return true;
    }

    /** 状态日志：同时写 logcat 与 rootfs /root/dsh-lan.log（App 终端 tail 可见）。
     *  超过 {@link #LOG_MAX_BYTES} 轮转一次，最多占两倍上限。 */
    private static void log(String msg) {
        Log.i(TAG, msg);
        String p = logPath;
        if (p.isEmpty()) return;
        synchronized (LOG_LOCK) {
            try {
                java.io.File f = new java.io.File(p);
                if (f.length() > LOG_MAX_BYTES) {
                    java.io.File old = new java.io.File(p + ".1");
                    //noinspection ResultOfMethodCallIgnored
                    old.delete();
                    if (!f.renameTo(old)) {
                        // 改名失败（权限/占用）→ 直接清空。宁可丢历史也不涨到爆盘。
                        try (java.io.FileOutputStream trunc = new java.io.FileOutputStream(f, false)) {
                            trunc.write(("[日志超过 " + (LOG_MAX_BYTES / 1024)
                                    + "KB，已截断]\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    }
                }
                try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f, true)) {
                    String line = "[" + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date())
                            + "] " + msg + "\n";
                    fo.write(line.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        }
    }

    // ================= 单连接处理 =================

    private static void handle(Socket client) {
        String clientIp = client.getInetAddress() == null ? "" : client.getInetAddress().getHostAddress();
        final boolean logConn = shouldLogConn(clientIp);
        if (logConn) log("连接来自: " + clientIp);
        try (Socket clientSock = client) {
            InputStream cin = clientSock.getInputStream();
            OutputStream cout = clientSock.getOutputStream();
            byte[] reqHead = new byte[65536];
            while (running) {
                // 1. 读请求头（到 \r\n\r\n）
                int headLen = readHeader(cin, reqHead);
                if (headLen <= 0) break; // EOF / 超时
                // 防慢请求头只需较短超时；真正的上传仍按原来的两分钟预算走，
                // 否则大附件在慢 Wi-Fi 上会被我们自己切断。
                clientSock.setSoTimeout(120000);
                String head = new String(reqHead, 0, headLen, java.nio.charset.StandardCharsets.ISO_8859_1);
                int nl = head.indexOf('\n');
                if (nl < 0) break; // 畸形请求头：无换行直接断开，防 substring 越界
                String reqLine = head.substring(0, nl).trim();
                if (reqLine.isEmpty()) break;

                // ===== CORS 预检：放在鉴权**之前** =====
                // 预检请求不携带凭据（浏览器不会给 OPTIONS 带 Cookie），所以放在
                // 401 后面必然被拒 —— 而 CORS 规范要求预检必须回 2xx，否则真正的
                // 请求根本发不出去。这里只回 CORS 头、不转发后端、不读任何数据，
                // 提到鉴权前不泄漏任何东西。
                if (reqLine.toUpperCase(java.util.Locale.ROOT).startsWith("OPTIONS ")) {
                    String cors = "HTTP/1.1 204 No Content\r\n"
                            + "Access-Control-Allow-Origin: *\r\n"
                            + "Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n"
                            + "Access-Control-Allow-Headers: *\r\n"
                            + "Access-Control-Max-Age: 86400\r\n"
                            + "Content-Length: 0\r\n"
                            + "Connection: close\r\n\r\n";
                    cout.write(cors.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    cout.flush();
                    break; // 预检结束，关连接
                }

                // ===== LAN 鉴权：无凭据返回 401（防同 WiFi 任意设备访问）=====
                int auth = LanAuth.tokenOk(head, lanToken);
                if (auth == LanAuth.AUTH_DENY) {
                    // 原来这里写死 Content-Length: 30，而 body 实际 24 字节。浏览器
                    // 等不到声明的长度就报 ERR_CONTENT_LENGTH_MISMATCH，用户看到的是
                    // 一个网络错误，而不是「要 token」——真实原因被自己藏起来了。
                    byte[] body = (lanToken.isEmpty()
                            ? "401 未授权：局域网访问 token 尚未生成。请回到 DSHA 重新开一次局域网访问，"
                              + "在启动页复制带 token 的完整地址。"
                            : "401 未授权：地址里缺少 token。请在 DSHA 启动页复制完整地址"
                              + "（形如 http://<手机IP>:3081/?token=...）。")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    String deny = "HTTP/1.1 401 Unauthorized\r\n"
                            + "Content-Type: text/plain; charset=utf-8\r\n"
                            + "Content-Length: " + body.length + "\r\n"
                            + "Connection: close\r\n\r\n";
                    cout.write(deny.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    cout.write(body);
                    cout.flush();
                    if (lanToken.isEmpty() && logConn) {
                        log("拒绝 " + clientIp + "：本机 token 未生成，桥按 fail-closed 拒绝全部请求");
                    }
                    break;
                }

                boolean upgrade = containsIgnoreCase(head, "Upgrade: websocket")
                        || reqLine.contains("HTTP/1.1") && containsIgnoreCase(head, "Connection: Upgrade");

                // 2. 改写 Host 头 → 127.0.0.1:<backendPort>
                String rewritten = rewriteHost(head, auth == LanAuth.AUTH_OK_SET_COOKIE);
                byte[] headBytes = rewritten.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);

                // 3. 连接后端
                try (Socket back = new Socket()) {
                    back.setSoTimeout(120000);
                    back.connect(new InetSocketAddress("127.0.0.1", backendPort), 5000);
                    InputStream bin = back.getInputStream();
                    OutputStream bout = back.getOutputStream();
                    bout.write(headBytes);
                    bout.flush();
                    // 请求体透传（Content-Length 部分；chunked 请求体也按 chunked 转发）
                    long bodyLen = contentLength(rewritten);
                    if (bodyLen > 0) {
                        pipeBytes(cin, bout, bodyLen);
                    } else if (containsIgnoreCase(head, "Transfer-Encoding: chunked")) {
                        pipeChunked(cin, bout);
                    }

                    // 4. 读响应头
                    byte[] respHead = new byte[65536];
                    int rhLen = readHeader(bin, respHead);
                    if (rhLen <= 0) break;
                    String rHead = new String(respHead, 0, rhLen, java.nio.charset.StandardCharsets.ISO_8859_1);
                    boolean upgraded = rHead.startsWith("HTTP/1.1 101") || containsIgnoreCase(rHead, "Upgrade: websocket");

                    // 响应头转发（Location 重写防跳回 127.0.0.1 + 附加 CORS 头）
                    String outHead = rewriteLocation(rHead);
                    // 附加 CORS 响应头（局域网跨域放行；没有则浏览器拦截 → ERR_HTTP_RESPONSE_CODE_FAILURE）
                    if (!containsIgnoreCase(outHead, "Access-Control-Allow-Origin")) {
                        outHead = outHead.replace("\r\n\r\n",
                                "\r\nAccess-Control-Allow-Origin: *\r\n\r\n");
                    }
                    // 凭据来自 URL ?token= → 回设 Cookie。之后的静态资源、XHR 和
                    // **WebSocket 握手**都会自动带上，页面里不必到处拼 token，
                    // 用户也不用担心刷新后丢凭据。SameSite=Strict 保证它不会跟着
                    // 跨站请求发出去；不加 Secure —— 局域网是 http，加了浏览器直接
                    // 不存。追加而非覆盖：后端自己也发 dsha_t，两个都要留。
                    if (auth == LanAuth.AUTH_OK_SET_COOKIE) {
                        outHead = outHead.replace("\r\n\r\n",
                                "\r\nSet-Cookie: " + LanAuth.COOKIE_NAME + "=" + lanToken
                                        + "; Path=/; SameSite=Strict; HttpOnly; Max-Age=2592000\r\n\r\n");
                    }
                    cout.write(outHead.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                    cout.flush();

                    if (upgraded) {
                        // WebSocket：双向透传直到任一侧关闭
                        pumpBidirectional(clientSock, back, cin, cout, bin, bout);
                        break;
                    }
                    // 普通响应体
                    long cl = contentLength(rHead);
                    boolean chunked = containsIgnoreCase(rHead, "Transfer-Encoding: chunked");
                    boolean closeConn = containsIgnoreCase(rHead, "Connection: close");
                    if (cl > 0) {
                        pipeBytes(bin, cout, cl);
                    } else if (chunked) {
                        pipeChunked(bin, cout);
                    } else {
                        // 无长度：流式转发直到后端 EOF（SSE/长连接）
                        pumpStream(bin, cout);
                    }
                    if (closeConn) break;
                    // keep-alive：继续下一请求
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // ================= IO 工具 =================

    /** 读头部直到 \r\n\r\n（或 \n\n），返回字节数；EOF 返回 -1；超长一律拒绝。 */
    private static int readHeader(InputStream in, byte[] buf) throws IOException {
        int pos = 0, matched = 0;
        while (pos < buf.length) {
            int b = in.read();
            if (b < 0) return pos == 0 ? -1 : pos;
            buf[pos++] = (byte) b;
            if (matched == 0 && b == '\r') matched = 1;
            else if (matched == 1 && b == '\n') matched = 2;
            else if (matched == 2 && b == '\r') matched = 3;
            else if (matched == 3 && b == '\n') return pos;
            else if (matched == 2 && b == '\n') return pos; // 兼容 \n\n
            else matched = 0;
        }
        throw new IOException("请求头超过 64KB");
    }

    private static void pipeBytes(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[8192];
        long left = n;
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) break;
            out.write(buf, 0, r);
            left -= r;
        }
        out.flush();
    }

    /** chunked 透传直到末尾 0 块；单块上限 1MB + 块尾必须 CRLF（畸形流直接结束）。 */
    private static void pipeChunked(InputStream in, OutputStream out) throws IOException {
        final int MAX_CHUNK = 1024 * 1024;
        java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        while (true) {
            line.reset();
            int b;
            int size = -1;
            while ((b = in.read()) >= 0) {
                line.write(b);
                if (line.size() >= 2 && line.toByteArray()[line.size() - 2] == '\r' && line.toByteArray()[line.size() - 1] == '\n') {
                    try {
                        String h = new String(line.toByteArray(), java.nio.charset.StandardCharsets.ISO_8859_1).trim();
                        size = Integer.parseInt(h.split(";")[0].trim(), 16);
                    } catch (Exception e) { size = -1; }
                    break;
                }
                if (line.size() > 1024) break;
            }
            if (b < 0) break;
            if (size < 0 || size > MAX_CHUNK) break; // 非法/超大块：中止透传（客户端可重新提交）
            out.write(line.toByteArray());
            if (size == 0) { out.flush(); break; }
            if (size > 0) {
                pipeBytes(in, out, size);
                // 块尾必须 CRLF（EOF 时 -1 不写入，防脏字节 0xff）
                int c1 = in.read(); int c2 = in.read();
                if (c1 < 0 || c2 < 0 || c1 != '\r' || c2 != '\n') break;
                out.write(c1);
                out.write(c2);
            }
        }
        out.flush();
    }

    private static void pumpStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) >= 0) {
            out.write(buf, 0, r);
            out.flush();
        }
    }

    /** WebSocket 升级后的双向透传：跑到任一侧关闭为止。
     *
     *  <p>原来是两个新线程各 {@code join(60000)} —— <b>连接只要活过 60 秒就被我们
     *  自己切断</b>：join 返回后外层 break，try-with-resources 一路关掉 socket。
     *  局域网 WebUI 的表现是聊到一分钟左右消息流断掉、必须刷新页面。叠加
     *  {@code SoTimeout=120000} 还有第二刀：WS 空闲两分钟也算超时。而 WS 本来就
     *  该长时间空闲，超时判据在这里没有意义。
     *
     *  <p>现在两侧 SoTimeout 归零，打开 TCP keepalive 让 OS 去发现真正死掉的连接；
     *  一个方向留在当前线程跑（每条 WS 少占一个线程），任一方向结束就立刻关掉两个
     *  socket，另一方向随即从 read 退出，不会有线程挂住。 */
    private static void pumpBidirectional(Socket clientSock, Socket back,
                                          InputStream cin, OutputStream cout,
                                          InputStream bin, OutputStream bout) {
        try {
            clientSock.setSoTimeout(0);
            back.setSoTimeout(0);
            clientSock.setKeepAlive(true);
            back.setKeepAlive(true);
        } catch (Throwable ignored) {
        }
        Runnable closeBoth = () -> {
            try { clientSock.close(); } catch (Throwable ignored) {}
            try { back.close(); } catch (Throwable ignored) {}
        };
        Thread up = new Thread(() -> {
            try { pumpStream(cin, bout); } catch (Throwable ignored) {}
            closeBoth.run();          // 客户端先断 → 立刻收掉后端方向
        }, "lanproxy-ws-up");
        up.setDaemon(true);
        up.start();
        try { pumpStream(bin, cout); } catch (Throwable ignored) {}
        closeBoth.run();              // 后端先断 → 同理
        try { up.join(3000); } catch (InterruptedException ignored) {}
    }

    private static long contentLength(String head) {
        for (String l : head.split("\r?\n")) {
            int i = l.indexOf(':');
            if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase("Content-Length")) {
                try { return Long.parseLong(l.substring(i + 1).trim()); } catch (Exception e) { return 0; }
            }
        }
        return 0;
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        int idx = s.toLowerCase(java.util.Locale.ROOT).indexOf(needle.toLowerCase(java.util.Locale.ROOT));
        return idx >= 0;
    }

    /** Rewrites Host for the loopback backend and exchanges LAN auth for BrowserAuth on /. */
        private static String rewriteHost(String head, boolean exchangeRequested) {
                StringBuilder sb = new StringBuilder();
                String upstreamToken = upstreamTokenForBackend();
                boolean hostDone = false;
                boolean first = true;
                for (String l : head.split("\\r?\\n")) {
                    if (l.isEmpty()) { sb.append("\r\n"); continue; }
                    int i = l.indexOf(':');
                    String key = i > 0 ? l.substring(0, i).trim() : "";
                    if (first) {
                        first = false;
                        // The LAN token authenticates only this proxy. Strip it
                        // before forwarding, then add dsh's per-process token only
                        // to GET / so BrowserAuth can mint its HttpOnly cookie.
                        sb.append(LanAuth.withUpstreamTokenForRootRequest(
                                l, upstreamToken, exchangeRequested)).append("\r\n");
                        continue;
                    }
                    if (key.equalsIgnoreCase("Host")) {
                    sb.append("Host: 127.0.0.1:").append(backendPort).append("\r\n");
                    hostDone = true;
                } else if (key.equalsIgnoreCase("Origin")) {
                    // 关键：dsh /api trust fence 要求 Origin.host === Host（严格含端口）。
                    // 桥把 Host 重写成 loopback，但局域网浏览器的 Origin 是
                    // http://<手机IP>:3081 → 不匹配 → 403 → ERR_HTTP_RESPONSE_CODE_FAILURE。
                    // 把 Origin 也重写成 loopback 同源，让后端 trust fence 放行。
                    sb.append("Origin: http://127.0.0.1:").append(backendPort).append("\r\n");
                } else if (key.equalsIgnoreCase("sec-fetch-site")) {
                                // cross-site 标记被 trust fence 直接拒绝 → 改 same-origin
                                sb.append("Sec-Fetch-Site: same-origin\r\n");
                            } else {
                                // 普通头 / 请求行：保留（token 只作为本站鉴权，不回传后端）
                                sb.append(l).append("\r\n");
                            }
            }
            // Java 的 String.split 默认丢弃尾部空字符串，而 HTTP 请求头以 \r\n\r\n 收尾 ——
            // 上面这个循环因此永远补不出协议要求的那个结尾空行，重组结果只到最后一个头字段。
            // 后端认为头还没发完，继续等；客户端在等响应，两边卡死（issue #42：从 3081 桥
            // 访问时页面 Loading 到超时）。在这里统一收尾，不依赖 split 的结果 ——
            // 也让下面插 X-Dsha-Token 的 lastIndexOf("\r\n\r\n") 找得到位置（找不到时它会
            // 把 token 追加到头的末尾，那样同样是一份没有结尾空行的请求头）。
            String joined = sb.toString();
            if (!joined.endsWith("\r\n\r\n")) {
                sb.append(joined.endsWith("\r\n") ? "\r\n" : "\r\n\r\n");
            }
            if (!hostDone) sb.insert(0, "Host: 127.0.0.1:" + backendPort + "\r\n");
            // Legacy dsh instances still use DSHA's host-webserver patch. Keep
            // its loopback-only header for that compatibility path; BrowserAuth
            // ignores it and uses the root query token above instead.
            String bt = HttpShellService.currentToken();
            if (!bt.isEmpty() && !containsIgnoreCase(sb.toString(), "X-Dsha-Token")) {
                int end = sb.lastIndexOf("\r\n\r\n");
                if (end >= 0) {
                    sb.insert(end + 2, "X-Dsha-Token: " + bt + "\r\n");
                } else {
                    sb.append("X-Dsha-Token: ").append(bt).append("\r\n");
                }
            }
            return sb.toString();
        }

            /** 响应头里 Location 重写：127.0.0.1:<backendPort> → 局域网IP:3081（防跳回本机）。
             *  每次实时取 IP（WiFi 切换后 IP 变化也能正确重写，不缓存旧值）。 */
        private static String rewriteLocation(String head) {
            if (!containsIgnoreCase(head, "Location:")) return head;
            String ip = HarnessController.getLanAddress();
            if (ip == null || ip.isEmpty()) ip = "127.0.0.1";
            StringBuilder sb = new StringBuilder();
            for (String l : head.split("\\r?\\n")) {
                if (l.isEmpty()) { sb.append("\r\n"); continue; }
                int i = l.indexOf(':');
                if (i > 0 && l.substring(0, i).trim().equalsIgnoreCase("Location")) {
                    String v = l.substring(i + 1).trim();
                    v = v.replace("http://127.0.0.1:" + backendPort, "http://" + ip + ":" + LAN_PORT);
                    v = v.replace("http://localhost:" + backendPort, "http://" + ip + ":" + LAN_PORT);
                    sb.append("Location: ").append(v).append("\r\n");
                } else {
                    sb.append(l).append("\r\n");
                }
            }
            return sb.toString();
        }

    private static void closeQuietly(ServerSocket s) {
        try { if (s != null) s.close(); } catch (Exception ignored) {}
    }
}
