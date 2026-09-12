package com.deepseekharness.app.util;

import java.io.IOException;
import java.net.*;
import java.util.regex.*;

/** 仅探测本机监听能力；不访问占用者、不读取端口进程表、不发送凭据。 */
public final class WebPortPolicy {
    private WebPortPolicy() { }
    public interface Probe { boolean available(int port) throws IOException; }
    public static final class Choice {
        public final int preferred, listen;
        Choice(int preferred,int listen) { this.preferred=preferred;this.listen=listen; }
        public boolean fallback() { return preferred!=listen; }
    }
    /** 0 交给 dsh/操作系统在真正监听时分配，不预先挑号再赌端口仍空闲。 */
    public static Choice choose(int preferred,int previous,Probe probe) throws IOException {
        if(preferred<1||preferred>65535)throw new IllegalArgumentException("Invalid Web port");
        if(!reserved(preferred)&&probe.available(preferred))return new Choice(preferred,preferred);
        if(previous>0&&previous<=65535&&previous!=preferred&&!reserved(previous)&&probe.available(previous))return new Choice(preferred,previous);
        return new Choice(preferred,0);
    }
    public static boolean reserved(int port) { return port==3081||port==3090; }
    public static boolean available(int port) throws IOException {
        try(ServerSocket socket=new ServerSocket()) {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),1);
            return true;
        } catch(BindException error) {
            String message=String.valueOf(error.getMessage()).toLowerCase(java.util.Locale.ROOT);
            if(message.contains("eaddrinuse")||message.contains("address already in use"))return false;
            throw error;
        }
    }
    public static String authentication(String output,int listen) {
        if(listen!=0)return DshAuthUrl.findAny(output,listen);
        // 动态端口只能来自本轮进程的完整官方启动行，不宽松扫描插件日志内的 URL。
        DshAuthUrl.Parsed parsed=DshAuthUrl.fromStartupOutput(output);
        if(parsed==null)return null;
        int port=URI.create(parsed.authUrl).getPort();
        return reserved(port)?null:parsed.authUrl;
    }
    public static int conflictPort(String detail) {
        if(detail==null || !detail.contains("@deepseek-ai/dsh-host-webserver")
                || !detail.contains("EADDRINUSE") || !detail.contains("listen"))return -1;
        Matcher match=Pattern.compile("127\\.0\\.0\\.1:([0-9]{1,5})(?![0-9])").matcher(detail);
        if(!match.find())return -1;
        int port=Integer.parseInt(match.group(1));return port>0&&port<=65535?port:-1;
    }
}
