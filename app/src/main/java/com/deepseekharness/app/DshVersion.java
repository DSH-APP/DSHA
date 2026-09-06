package com.deepseekharness.app;

/**
 * dsh 版本号的比较 —— <b>唯一</b>定义。
 *
 * <p><b>为什么必须抽出来</b>：这个分数决定「已装的比线上的旧不旧」，而那个判断的后果是
 * <b>自动重装 dsh</b>。原来的实现只认 {@code X.Y.Z-rc.N}，遇到 alpha 一律算 0 分 ——
 * 于是装了 0.1.3-alpha.1 的机器会认为「本地版本 0 &lt; npm 上的 0.1.2-rc.1」，
 * 自动把 dsh「升级」成 0.1.2，把离线包里精心装好的 0.1.3 覆盖掉。降级比不升级糟得多，
 * 所以这段逻辑要能被断言覆盖，不能埋在 6000 行的控制器里。
 *
 * <p>预发布顺序按 semver 的惯例：{@code alpha < beta < rc < 正式版}。
 */
final class DshVersion {

    private DshVersion() {
    }

    /** 预发布标记的等级。正式版最高 —— 同一个 X.Y.Z，正式版必须排在所有预发布之后。 */
    private static int preRank(String tag) {
        if (tag == null || tag.isEmpty()) return 9;   // 没有预发布段 = 正式版
        switch (tag) {
            case "alpha": return 1;
            case "beta":  return 2;
            case "rc":    return 3;
            default:      return 4;                    // 认不出的标记：排在 rc 之后、正式版之前
        }
    }

    /**
     * 把版本串换算成可比较的分数。认不出返回 0。
     *
     * <p>输入可能带 ANSI 颜色码，也可能一行里有多个版本
     * （{@code "0.1.1-rc.2 (compat 0.1.0-rc.8)"}）—— 那种取<b>最大</b>的那个，
     * 取第一个会把兼容声明当成本体版本。
     */
    static long score(String v) {
        if (v == null) return 0;
        String t = v.trim().toLowerCase();
        t = t.replaceAll("\u001B\\[[0-9;]*[a-zA-Z]", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "(\\d+)\\.(\\d+)\\.(\\d+)(?:-([a-z]+)\\.?(\\d+)?)?").matcher(t);
        long best = 0;
        while (m.find()) {
            long major = Long.parseLong(m.group(1));
            long minor = Long.parseLong(m.group(2));
            long patch = Long.parseLong(m.group(3));
            String tag = m.group(4);
            long num = m.group(5) == null ? 0 : Long.parseLong(m.group(5));
            long sc = ((major * 1000 + minor) * 1000 + patch) * 1000L
                    + preRank(tag) * 100L + Math.min(num, 99);
            if (sc > best) best = sc;
        }
        return best;
    }

    /** a 是不是比 b 新。两边都认不出时返回 false（宁可不动，也不要误判去重装）。 */
    static boolean isNewer(String a, String b) {
        long sa = score(a);
        long sb = score(b);
        return sa > 0 && sa > sb;
    }
}
