package com.deepseekharness.app.util;

/** 按完整行脱敏，避免 token 横跨两次读取时部分明文落盘；超长行直接略过。 */
public final class DshAuthLog {
    private final StringBuilder pending = new StringBuilder();
    private boolean discarded;
    public String append(String chunk) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (c == '\n') {
                out.append(discarded ? "[已略过超长输出]" : DshAuthUrl.redact(pending.toString())).append('\n');
                pending.setLength(0); discarded = false;
            } else if (!discarded) {
                pending.append(c);
                if (pending.length() > 65_536) { pending.setLength(0); discarded = true; }
            }
        }
        return out.toString();
    }
    public String finish() { return append("\n"); }
}
