package com.deepseekharness.app.util;

/** 在受管 HTML 的首个脚本之前注入兼容入口；升级时只替换自己的有界区块。 */
public final class HtmlBootstrapPatch {
    private HtmlBootstrapPatch() { }
    private static final String START="<!-- DSHA_BROWSER_COMPAT_BEGIN -->",END="<!-- DSHA_BROWSER_COMPAT_END -->";
    public static String apply(String html,String javascript) {
        String block=START+"\n<script>"+javascript.replaceAll("(?i)</script","<\\\\/script")+"</script>\n"+END;
        int start=html.indexOf(START),end=html.indexOf(END);
        if(start>=0||end>=0) {
            if(start<0||end<start||html.indexOf(START,start+START.length())>=0||html.indexOf(END,end+END.length())>=0)
                throw new IllegalArgumentException("Invalid browser compatibility block");
            return html.substring(0,start)+block+html.substring(end+END.length());
        }
        int head=html.indexOf("<head>");
        if(head<0||html.indexOf("<head>",head+6)>=0)throw new IllegalArgumentException("HTML head is not unique");
        int position=head+6;
        String charset="<meta charset=\"utf-8\" />";
        int meta=html.indexOf(charset,position),firstScript=html.indexOf("<script",position);
        if(meta>=0 && (firstScript<0||meta<firstScript))position=meta+charset.length();
        return html.substring(0,position)+"\n"+block+html.substring(position);
    }
}
