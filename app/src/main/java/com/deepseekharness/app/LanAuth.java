package com.deepseekharness.app;

/**
 * 局域网桥的凭据判定与请求行改写 —— 纯字符串逻辑，<b>刻意不依赖任何 Android API</b>。
 *
 * <p>为什么从 {@link LanProxyService} 里抽出来：这几个函数全是手写的 HTTP 头/查询串
 * 切分，历史上连着栽过三次，而且每次都是「看起来对、真跑才知道错」的那种：
 * <ul>
 *   <li>token 在整个请求头里 {@code indexOf("token=")} → 实际靠 Referer 生效，
 *       token 会随外链泄漏，WebSocket 则必然 401；</li>
 *   <li>剥离 token 时把 HTTP 版本一起吃掉 → 后端收到 {@code GET /} 直接 400；</li>
 *   <li>{@code [&]?token=} 没有参数名边界 → 误删 {@code csrf_token=}。</li>
 * </ul>
 * 抽成无 Android 依赖的类之后，{@code tools/pure-logic-test.sh} 能用 javac 直接编译
 * 运行断言，不需要设备、不需要 SDK、不需要联网。这类字符串处理必须能真跑。
 */
final class LanAuth {

    /** 凭据不通过。 */
    static final int AUTH_DENY = 0;
    /** 通过，凭据来自 Cookie 或 X-DSHA-Token（无需再下发 Cookie）。 */
    static final int AUTH_OK = 1;
    /** 通过，凭据来自 URL {@code ?token=}（调用方应回设 Cookie）。 */
    static final int AUTH_OK_SET_COOKIE = 2;

    /** Cookie 名。故意与后端 auth patch 的 {@code dsha_t} 区分：那是 dsh 自己的
     *  token，两者语义不同，混用会让「哪一层拒的」查不清楚。 */
    static final String COOKIE_NAME = "dsha_token";

    private LanAuth() {
    }

    /**
     * 校验请求凭据。只认三个来源：请求行的 {@code ?token=}、{@code X-DSHA-Token} 头、
     * 本站 Cookie。
     *
     * <p><b>token 为空一律拒绝</b>（fail-closed）。原来是「无 token 配置 → 放行」，
     * 而桥监听在 {@code 0.0.0.0}，一旦 token 因为任何原因没初始化，同一 WiFi 下的
     * 任何设备都能无凭据打开 dsh WebUI —— 而 WebUI 里的 agent 能执行 bash。宁可
     * 局域网用不了，也不能默认敞开。
     *
     * @param head  完整请求头（含请求行）
     * @param token 本机 LAN token
     * @return {@link #AUTH_DENY} / {@link #AUTH_OK} / {@link #AUTH_OK_SET_COOKIE}
     */
    static int tokenOk(String head, String token) {
        if (head == null || token == null || token.isEmpty()) return AUTH_DENY;
        int nl = head.indexOf('\n');
        String reqLine = nl >= 0 ? head.substring(0, nl) : head;
        String q = queryToken(reqLine);
        if (q != null && constantTimeEquals(token, q)) return AUTH_OK_SET_COOKIE;
        for (String l : head.split("\\r?\\n")) {
            int i = l.indexOf(':');
            if (i <= 0) continue;
            String key = l.substring(0, i).trim();
            String v = l.substring(i + 1).trim();
            if (key.equalsIgnoreCase("X-DSHA-Token")) {
                return constantTimeEquals(token, v) ? AUTH_OK : AUTH_DENY;
            }
            if (key.equalsIgnoreCase("Cookie")) {
                for (String c : v.split(";")) {
                    c = c.trim();
                    if (c.startsWith(COOKIE_NAME + "=")
                            && constantTimeEquals(token, c.substring(COOKIE_NAME.length() + 1))) {
                        return AUTH_OK;
                    }
                    // Cookie 在但不对（换过 token / 别的实例发的）继续看其它认证头；
                    // 有效的 query 已在遍历头之前优先处理。
                }
            }
        }
        return AUTH_DENY;
    }

    /** 从请求行（{@code GET /p?a=1&token=xxx HTTP/1.1}）取 token 参数值，没有则 null。
     *  只看请求行的查询串，不扫其它头 —— 否则 Referer 里残留的 token 会被当成凭据。 */
    static String queryToken(String reqLine) {
        if (reqLine == null) return null;
        int sp1 = reqLine.indexOf(' ');
        if (sp1 < 0) return null;
        int sp2 = reqLine.indexOf(' ', sp1 + 1);
        String target = sp2 > sp1 ? reqLine.substring(sp1 + 1, sp2) : reqLine.substring(sp1 + 1);
        return queryTokenFromTarget(target);
    }

    /** 从请求目标（{@code /p?a=1&token=xxx}）取 token 参数值，没有则 null。
     *
     *  <p>HttpShellService（3090 桥）也用这一份：它原来自己写了
     *  {@code query.indexOf("token=")}，同样没有参数名边界 ——
     *  {@code ?xtoken=junk&token=真值} 会先命中 {@code xtoken=} 取到 junk 而误拒。
     *  两处各写一套判断正是这个项目反复栽的模式，合并到一份并配上断言。 */
    static String queryTokenFromTarget(String target) {
        String v = Query.raw(Query.of(target), "token");
        return v == null ? null : v.trim();
    }

    /**
     * 剥掉请求行里的 {@code token=} 参数（只用于本站鉴权，不该转发进后端日志）。
     *
     * <p>原实现：
     * <pre>
     * int q = line.indexOf('?');
     * String path  = line.substring(0, q);   // "GET /"
     * String query = line.substring(q + 1);  // "token=xxx HTTP/1.1"  ← 版本号进来了
     * String cleaned = query.replaceAll("[&]?token=[^&]*", "");  // 全被吃掉 → ""
     * if (cleaned.isEmpty()) return path;    // "GET /"  ← HTTP 版本没了
     * </pre>
     * token 是唯一 query 参数时（打开首页 {@code /?token=xxx}，最常见的场景）转发给
     * 后端的请求行退化成 {@code GET /}，Node 的 parser 当畸形请求 400。且
     * {@code [&]?token=} 没有参数名边界，{@code csrf_token=} 会被连带剥掉。
     *
     * <p>现在按「方法 / 目标 / 版本」三段切开，只动中间那段的 query，逐参数精确
     * 比较参数名，与 {@link #queryToken} 同一口径。
     */
    static String stripTokenFromRequestLine(String line) {
        if (line == null) return null;
        int sp1 = line.indexOf(' ');
        if (sp1 < 0) return line;
        int sp2 = line.indexOf(' ', sp1 + 1);
        String head = line.substring(0, sp1 + 1);                    // "GET "
        String target = sp2 > sp1 ? line.substring(sp1 + 1, sp2) : line.substring(sp1 + 1);
        String tail = sp2 > sp1 ? line.substring(sp2) : "";           // " HTTP/1.1"
        int q = target.indexOf('?');
        if (q < 0) return line;
        String path = target.substring(0, q);
        String query = target.substring(q + 1);
        String frag = "";
        int hash = query.indexOf('#');
        if (hash >= 0) {
            frag = query.substring(hash);
            query = query.substring(0, hash);
        }
        StringBuilder kept = new StringBuilder();
        for (String kv : query.split("&")) {
            if (kv.isEmpty()) continue;
            int eq = kv.indexOf('=');
            String k = eq > 0 ? kv.substring(0, eq) : kv;
            if (k.equals("token")) continue;
            if (kept.length() > 0) kept.append('&');
            kept.append(kv);
        }
        String rebuilt = kept.length() > 0 ? path + "?" + kept + frag : path + frag;
        return head + rebuilt + tail;
    }

    /**
     * Replaces the proxy's query token with a validated upstream BrowserAuth
     * token for an index request. The proxy token must never reach dsh logs;
     * the upstream token is only needed once to mint the authority-bound cookie.
     * Non-root/API requests stay token-free after the proxy token is stripped.
     */
    static String withUpstreamTokenForRootRequest(String line, String upstreamToken,
                                                   boolean exchangeRequested) {
        String stripped = stripTokenFromRequestLine(line);
        if (!exchangeRequested || !WebLaunchUrl.isValidToken(upstreamToken) || stripped == null) {
            return stripped;
        }
        int sp1 = stripped.indexOf(' ');
        if (sp1 < 0) return stripped;
        int sp2 = stripped.indexOf(' ', sp1 + 1);
        String method = stripped.substring(0, sp1);
        if (!"GET".equalsIgnoreCase(method)) return stripped;
        String target = sp2 > sp1 ? stripped.substring(sp1 + 1, sp2) : stripped.substring(sp1 + 1);
        String tail = sp2 > sp1 ? stripped.substring(sp2) : "";
        int hash = target.indexOf('#');
        String beforeFragment = hash >= 0 ? target.substring(0, hash) : target;
        String fragment = hash >= 0 ? target.substring(hash) : "";
        int q = beforeFragment.indexOf('?');
        String path = q >= 0 ? beforeFragment.substring(0, q) : beforeFragment;
        if (!"/".equals(path)) return stripped;
        String query = q >= 0 ? beforeFragment.substring(q + 1) : "";
        String rebuilt = query.isEmpty() ? "/?token=" + upstreamToken
                : "/?" + query + "&token=" + upstreamToken;
        return method + " " + rebuilt + fragment + tail;
    }

    /** 定长比较，避免按前缀长度泄漏信息。 */
    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            char ca = a.charAt(i);
            char cb = i < b.length() ? b.charAt(i) : 0;
            diff |= ca ^ cb;
        }
        diff |= a.length() ^ b.length();
        return diff == 0;
    }
}
