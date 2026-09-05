package com.deepseekharness.app;

/**
 * HTTP 查询串取参数 —— 树里唯一一份实现，纯逻辑无 Android 依赖。
 *
 * <p><b>为什么必须逐参数比较参数名</b>：原先 3090 桥的 {@code getParam} 是
 * {@code query.indexOf(key + "=")}，在整个查询串里任意位置找 {@code key=}，
 * 于是任何<b>以目标参数名结尾</b>的参数都会把它劫持掉：
 *
 * <pre>
 *   ?key=abc&y=200   取 y  → 命中 "ke<b>y=</b>abc" → 得到 "abc"，不是 "200"
 *   ?xtext=junk&text=real  取 text → 得到 "junk"
 * </pre>
 *
 * 局域网桥那边为 token 修过完全一样的毛病（见 {@link LanAuth} 的类注释），教训也写下来了，
 * 但 3090 桥这份漏了 —— 又是一次「同一份判断散落两处」。现在两边都走这里。
 *
 * <p>桥上现有的参数名恰好没在同一端点内撞上（{@code key} 与 {@code y} 分属
 * {@code /app/ui/key} 与 {@code /app/ui/tap}），所以这个缺陷一直没被触发；但只要有人加一个
 * 参数就可能撞，而症状是「取到了另一个参数的值」——排查起来很难想到是解析层。
 */
final class Query {

    private Query() {
    }

    /** 从请求目标（{@code /p?a=1&b=2}）里切出查询串；没有 {@code ?} 返回空串。 */
    static String of(String target) {
        if (target == null) return "";
        int i = target.indexOf('?');
        return i >= 0 ? target.substring(i + 1) : "";
    }

    /** 请求目标中不带查询串的路由部分。端点分发必须精确匹配，不能用 startsWith。 */
    static String path(String target) {
        if (target == null) return "";
        int i = target.indexOf('?');
        return i >= 0 ? target.substring(0, i) : target;
    }

    /**
     * 取参数原值（<b>不做 URL 解码</b>），没有该参数返回 {@code null}。
     *
     * <p>区分「参数不存在」（null）和「参数存在但值为空」（空串）：token 校验依赖这个区别 ——
     * {@code ?token=} 带了个空 token 该走 fail-closed 的拒绝路径，而不是当成没带凭据。
     */
    static String raw(String query, String key) {
        if (query == null || key == null || key.isEmpty()) return null;
        int hash = query.indexOf('#');
        if (hash >= 0) query = query.substring(0, hash);
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            // eq == 0 是「= 开头」的畸形段（没有参数名）；eq < 0 是没带值的裸参数名
            if (eq <= 0) continue;
            if (kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
        }
        return null;
    }

    /**
     * 取参数值并做 URL 解码；参数不存在或解码失败返回 {@code def}。
     *
     * <p>解码用 {@code UTF-8}，{@code +} 按 form 语义还原成空格 —— 与改造前的行为一致，
     * 桥的调用方（容器里的 curl 与内置插件）都是这么编码的。
     */
    static String param(String query, String key, String def) {
        String v = raw(query, key);
        if (v == null) return def;
        try {
            return java.net.URLDecoder.decode(v, "UTF-8");
        } catch (Exception e) {
            // 畸形百分号转义（%ZZ）：给默认值，不要把半解码的串当命令用
            return def;
        }
    }
}
