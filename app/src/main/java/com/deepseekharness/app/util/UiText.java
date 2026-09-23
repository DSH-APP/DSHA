package com.deepseekharness.app.util;

/** 运行时文案与 XML 资源共用应用语言；此层不访问 Android 或用户文件。 */
public final class UiText {
    private static volatile String language = "zh";
    private UiText() { }
    /**
     * 设置渲染语言。只接受<strong>生效语言</strong> zh/en：{@code system} 等偏好值会被
     * 解析成当前系统语言，避免把"跟随系统"当成第三种语言塞进渲染层（那会让 choose()
     * 永远走中文分支）。
     */
    public static void setLanguage(String value) {
        language=UiLanguagePreference.resolve(value,SystemLanguage.tag());
    }
    public static String language() { return language; }
    public static String choose(String chinese,String english) { return "en".equals(language)?english:chinese; }
    public static String text(String value) {
        if(value==null||!"en".equals(language))return value;
        String translated=UiMessages.EN.get(value);return translated==null?value:translated;
    }
    public static CharSequence text(CharSequence value) {
        if(value==null)return null;String translated=text(value.toString());
        return translated.equals(value.toString())?value:translated;
    }
    public static int text(int resource) { return resource; }
    public static String[] text(String[] values) {
        String[] translated=values.clone();for(int i=0;i<translated.length;i++)translated[i]=text(translated[i]);return translated;
    }
    /** 这些前缀仍用于内部状态识别；只在应用状态控件的边界翻译。 */
    public static String status(String value) {
        String translated=text(value);if(value==null||!value.equals(translated)||!"en".equals(language))return translated;
        for(String prefix:new String[]{"环境任务进行中：","环境任务进行中","重置失败："})
            if(value.startsWith(prefix))return text(prefix)+text(value.substring(prefix.length()));
        return value;
    }
    /** 仅翻译内置工具状态首行，下面的命令、路径和模型内容保持原样。 */
    public static String toolStatus(String value) {
        if(value==null||!"en".equals(language))return value;
        int line=value.indexOf('\n');String head=line<0?value:value.substring(0,line),tail=line<0?"":value.substring(line);
        if(!head.startsWith("⚙ "))return value;
        String translated=text(head);
        if(translated.equals(head)&&head.startsWith("⚙ 正在使用 "))translated=text("⚙ 正在使用 ")+head.substring("⚙ 正在使用 ".length());
        return translated+tail;
    }
}
