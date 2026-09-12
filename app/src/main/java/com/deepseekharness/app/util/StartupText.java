package com.deepseekharness.app.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** 仅用于应用自己产生的启动事件；命令输出、插件异常正文和用户内容不做全文替换。 */
public final class StartupText {
    private StartupText() { }
    private static final Map<String, String> PREFIXES = new LinkedHashMap<>();
    static {
        PREFIXES.put("首选 Web 端口已占用：", "Preferred Web port is in use: ");
        PREFIXES.put("实际 Web 端口：", "Active Web port: ");
        PREFIXES.put("Web 端口被占用：", "Web port in use: ");
        PREFIXES.put("插件加载异常：", "Plugin load error: ");
        PREFIXES.put("配置检查：", "Configuration check: ");
        PREFIXES.put("正在加载插件：", "Loading plugin: ");
        PREFIXES.put("插件加载完成：", "Plugin loaded: ");
        PREFIXES.put("插件等待服务：", "Plugin waiting for services: ");
        PREFIXES.put("启动配置检查失败：", "Startup configuration check failed: ");
        PREFIXES.put("加载器观察不可用，保留原始异常输出：", "Loader monitoring unavailable; original errors retained: ");
        PREFIXES.put("找不到插件目录，请到插件管理检查安装：", "Plugin directory not found; check its installation: ");
        PREFIXES.put("正在初始化网页插件：", "Initializing web plugin: ");
        PREFIXES.put("网页插件初始化返回：", "Web plugin initialization returned: ");
        PREFIXES.put("运行方式：", "Runtime mode: ");
        PREFIXES.put("profile package.json 无法解析：", "Cannot parse profile package.json: ");
        PREFIXES.put("启动失败：", "Startup failed: ");
        PREFIXES.put("启动未完成：", "Startup did not complete: ");
        PREFIXES.put("插件注册失败：", "Plugin registration failed: ");
    }
    public static String render(String text) {
        return render(text, UiText.language());
    }
    public static String render(String text, String language) {
        if (text == null) return "";
        java.util.regex.Matcher exited=java.util.regex.Pattern.compile("^(proot|proroot)(?: 进程退出，退出码 | process exited with code )(-?[0-9]+) ?(?:（(鉴权前|鉴权后)）|\\((before authentication|after authentication)\\))$").matcher(text);
        if(exited.matches())return exited.group(1)+choose(language," 进程退出，退出码 "," process exited with code ")+exited.group(2)
                +("鉴权前".equals(exited.group(3))||"before authentication".equals(exited.group(4))?choose(language,"（鉴权前）"," (before authentication)"):choose(language,"（鉴权后）"," (after authentication)"));
        java.util.regex.Matcher created=java.util.regex.Pattern.compile("^dsh web (?:进程已创建|process created) → 127\\.0\\.0\\.1:([0-9]+)(?:（等待鉴权链接…）|\\(waiting for authentication link…\\))$").matcher(text);
        if(created.matches())return choose(language,"dsh web 进程已创建 → 127.0.0.1:","dsh web process created → 127.0.0.1:")+created.group(1)
                +choose(language,"（等待鉴权链接…）","(waiting for authentication link…)");
        java.util.regex.Matcher registered=java.util.regex.Pattern.compile("^BUILTIN_REGISTER_OK: ([0-9]+) (?:个内置插件注册就绪|built-in plugins registered)$").matcher(text);
        if(registered.matches())return "BUILTIN_REGISTER_OK: "+registered.group(1)+choose(language," 个内置插件注册就绪"," built-in plugins registered");
        if(text.startsWith("BUILTIN_REGISTER_FAIL: "))return "BUILTIN_REGISTER_FAIL: "+render(text.substring(23),language);
        String direct = exact(text,language);
        if (!direct.equals(text)) return direct;
        for (Map.Entry<String,String> entry : PREFIXES.entrySet()) {
            String prefix = text.startsWith(entry.getKey()) ? entry.getKey()
                    : text.startsWith(entry.getValue()) ? entry.getValue() : null;
            if (prefix != null) return choose(language,entry.getKey(),entry.getValue()) + exact(text.substring(prefix.length()),language);
        }
        return text;
    }
    public static String choose(String language,String zh,String en) { return "en".equals(language)?en:zh; }
    private static String exact(String value, String language) {
        if ("en".equals(language)) return UiMessages.EN.getOrDefault(value,value);
        for (Map.Entry<String,String> entry : UiMessages.EN.entrySet())
            if (entry.getValue().equals(value)) return entry.getKey();
        return value;
    }
}
