package com.deepseekharness.app.util;

/** 保留用户 DNS/search/options；仅管理自己的 IPv4 兼容行。自动模式保留 IPv6。 */
public final class ResolverConfig {
    private ResolverConfig() { }
    private static final String OWNED="# DSHA managed IPv4 DNS compatibility\noptions no-aaaa\n";
    public static String mode(String value) {
        return "ipv4".equals(value)||"native".equals(value)?value:"auto";
    }
    public static String reconcile(String original,String mode) {
        String text=original==null?"":original;
        text=text.replace(OWNED,"").replace(OWNED.replace("\n","\r\n"),"");
        boolean nameserver=false,noAaaa=false;
        for(String line:text.split("\\R")) {
            String clean=line.split("[#;]",2)[0].trim();
            if(clean.matches("nameserver\\s+\\S+.*"))nameserver=true;
            if(clean.matches("options\\s+.*"))for(String token:clean.split("\\s+"))if(token.equals("no-aaaa"))noAaaa=true;
        }
        if(!nameserver)text=terminated(text)+"nameserver 8.8.8.8\nnameserver 223.5.5.5\n";
        if("ipv4".equals(mode(mode))&&!noAaaa)text=terminated(text)+OWNED;
        return text;
    }
    private static String terminated(String value) {return value.isEmpty()||value.endsWith("\n")?value:value+"\n";}
}
