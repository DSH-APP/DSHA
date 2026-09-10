package com.deepseekharness.app.util;

/** 对锁定的上游源码应用单个精确补丁；重复执行幂等，结构变化时拒绝猜测或部分匹配。 */
public final class ExactTextPatch {
    private ExactTextPatch() { }
    public static String apply(String source, String before, String after) {
        if (source == null || before == null || after == null || before.isEmpty() || after.isEmpty() || before.equals(after))
            throw new IllegalArgumentException("无效的文本补丁");
        int old = count(source, before), patched = count(source, after);
        if (patched == 1 && old == count(after, before)) return source;
        if (old != 1 || patched != 0) throw new IllegalArgumentException("上游模块结构与输入适配补丁不符，原文件保留");
        return source.replace(before, after);
    }
    private static int count(String value, String part) {
        int count = 0, start = 0, at;
        while ((at = value.indexOf(part, start)) >= 0) { count++; start = at + part.length(); }
        return count;
    }
}
