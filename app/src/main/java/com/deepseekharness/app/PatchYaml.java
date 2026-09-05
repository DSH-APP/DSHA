package com.deepseekharness.app;

/**
 * dsh 的 {@code cordis.patch.yml} 的最小处理：删掉注册某个插件的列表项块。
 *
 * <p><b>为什么单独一个类</b>：它改的是<b>用户 profile 里的配置文件</b> —— 写坏了
 * dsh 直接起不来，比它要治的那个 bug 更糟。所以逻辑必须是纯字符串处理、能被断言覆盖
 * （见 {@code tools/pure-logic-test.sh} 里 patch-yaml 那组）。刻意不引 YAML 库：
 * 完整解析再序列化会重排用户的注释与顺序，那是另一种"写坏"。
 *
 * <p>要治的现象：同一个插件在 profile 与 bundle 各注册一次 → 两份都加载 →
 * {@code locale namespace "mobileNav" already has locale "zh"} → 插件整个加载失败。
 * dsh 写进 profile 的行用随机 id（真机见到 {@code d8828179}），插件名在块内的
 * {@code name:} 上，所以必须整块判断而不是只看 id 行。
 */
final class PatchYaml {

    private PatchYaml() {
    }

    /** 一行的缩进宽度（空格数）。 */
    static int indent(String line) {
        int n = 0;
        while (n < line.length() && line.charAt(n) == ' ') n++;
        return n;
    }

    /**
     * 删掉所有「内容里含 {@code needle}」的 {@code - id:} 列表项块。
     *
     * <p>删空之后还会收尾：如果某个 {@code - insert:} / {@code - remove:} 下面一项都不剩，
     * 连它自己一起删 —— 留一个没有子项的键，dsh 解析出来是 null，等于把这个 bug
     * 换成另一个。
     *
     * @return 改动后的全文；<b>没有任何改动时返回 null</b>（调用方据此决定是否写盘）
     */
    static String stripBlocksContaining(String txt, String needle) {
        if (txt == null || needle == null || needle.isEmpty()) return null;
        if (!txt.contains(needle)) return null;
        String[] lines = txt.split("\n", -1);
        java.util.List<String> out = new java.util.ArrayList<>(lines.length);
        boolean changed = false;
        int i = 0;
        while (i < lines.length) {
            String line = lines[i];
            if (!line.trim().startsWith("- id:")) {
                out.add(line);
                i++;
                continue;
            }
            int ind = indent(line);
            java.util.List<String> block = new java.util.ArrayList<>();
            block.add(line);
            int j = i + 1;
            while (j < lines.length) {
                String nl = lines[j];
                if (nl.trim().isEmpty()) {          // 空行跟着当前块走，不留孤立空行
                    block.add(nl);
                    j++;
                    continue;
                }
                if (indent(nl) <= ind) break;       // 同级或更浅 → 块结束
                block.add(nl);
                j++;
            }
            if (String.join("\n", block).contains(needle)) {
                changed = true;                     // 整块丢掉
            } else {
                out.addAll(block);
            }
            i = j;
        }
        if (!changed) return null;
        return dropEmptyKeys(out);
    }

    /** 删掉「下面没有任何子项」的顶层键（{@code - insert:} 这类）。 */
    private static String dropEmptyKeys(java.util.List<String> lines) {
        java.util.List<String> out = new java.util.ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = line.trim();
            boolean isKey = t.startsWith("- ") && t.endsWith(":");
            if (!isKey) {
                out.add(line);
                continue;
            }
            int ind = indent(line);
            boolean hasChild = false;
            for (int j = i + 1; j < lines.size(); j++) {
                String nl = lines.get(j);
                if (nl.trim().isEmpty()) continue;
                if (indent(nl) <= ind) break;
                hasChild = true;
                break;
            }
            if (hasChild) out.add(line);
            // 没有子项 → 连这个键一起丢
        }
        // 收尾：连续空行压成一个，避免每次清理都在文件里多留几行空白
        java.util.List<String> tidy = new java.util.ArrayList<>(out.size());
        boolean prevBlank = false;
        for (String l : out) {
            boolean blank = l.trim().isEmpty();
            if (blank && prevBlank) continue;
            tidy.add(l);
            prevBlank = blank;
        }
        return String.join("\n", tidy);
    }
}
