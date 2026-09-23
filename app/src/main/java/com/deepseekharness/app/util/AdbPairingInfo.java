package com.deepseekharness.app.util;
import java.util.regex.*;
/** 只接纳系统配对弹窗的独立六位码；配对端口与主页面连接端口分别保存。 */
public final class AdbPairingInfo {
    private AdbPairingInfo() { }
    private static final Pattern CODE=Pattern.compile("(?m)^\\s*([0-9]{6})\\s*$");
    private static final Pattern ADDRESS=Pattern.compile("(?<![0-9.])([0-9]{1,3}(?:\\.[0-9]{1,3}){3}):([0-9]{1,5})(?![0-9])");
    public static final class Value {public final String code,host,port;Value(String code,String host,String port){this.code=code;this.host=host;this.port=port;}}
    public static Value pairing(String text){
        if(text==null)return null;String lower=text.toLowerCase(java.util.Locale.ROOT);
        boolean label=text.contains("WLAN 配对码")||text.contains("WLAN配对码")||lower.contains("wi-fi pairing code")||lower.contains("wi‑fi pairing code")||text.contains("\n配对码\n");
        if(!label)return null;Matcher code=CODE.matcher(text);if(!code.find())return null;Value address=address(text);return address==null?null:new Value(code.group(1),address.host,address.port);
    }
    public static Value connection(String text){
        if(text==null||pairing(text)!=null)return null;String lower=text.toLowerCase(java.util.Locale.ROOT);
        if(!(text.contains("无线调试")||lower.contains("wireless debugging")))return null;
        if(!(text.contains("IP 地址和端口")||lower.contains("ip address")&&lower.contains("port")))return null;return address(text);
    }
    private static Value address(String text){Matcher match=ADDRESS.matcher(text);while(match.find()){boolean valid=true;for(String octet:match.group(1).split("\\."))if(Integer.parseInt(octet)>255)valid=false;int port=Integer.parseInt(match.group(2));if(valid&&port>0&&port<=65535)return new Value("",match.group(1),match.group(2));}return null;}
}
