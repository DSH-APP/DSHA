package com.deepseekharness.app.util;

import java.util.Map;
import java.util.regex.Pattern;

/** 仅按当前清单已知路径或完整模块标识归因；同一标识属于多个插件时不猜测。 */
public final class PluginFailureOwner {
    private PluginFailureOwner() { }
    public static String find(Map<String,String> owners, String text) {
        if (text == null) return "";
        String result = ""; int length = 0;
        for (Map.Entry<String,String> entry : owners.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("/") && key.length() > length && text.contains(key)) {
                result = entry.getValue(); length = key.length();
            }
        }
        if (length > 0) return result;
        for (Map.Entry<String,String> entry : owners.entrySet()) {
            String key = entry.getKey();
            if (key.length() < 3 || !key.startsWith("\"")) continue;
            key = key.substring(1, key.length() - 1);
            if (!Pattern.compile("(?<![A-Za-z0-9@_./-])" + Pattern.quote(key) + "(?![A-Za-z0-9@_./-])").matcher(text).find()) continue;
            if (entry.getValue().isEmpty() || (!result.isEmpty() && !result.equals(entry.getValue()))) return "";
            result = entry.getValue();
        }
        return result;
    }
}
