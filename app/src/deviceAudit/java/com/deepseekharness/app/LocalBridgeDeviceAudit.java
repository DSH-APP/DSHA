package com.deepseekharness.app;

import android.app.*;
import android.os.Bundle;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.util.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 真正的 3090 监听与解析负载；仅审计安装，端口被其它安装占用时不接管。 */
public final class LocalBridgeDeviceAudit extends Instrumentation {
    private final List<Map<String,Object>> cases=new ArrayList<>();private final List<Socket> sockets=new ArrayList<>();
    private void check(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
    private void pass(String name){cases.add(Map.of("name",name,"status","PASS"));Bundle result=new Bundle();result.putString("case",name);result.putString("status","PASS");sendStatus(1,result);}
    private Socket connect()throws IOException{Socket socket=new Socket("127.0.0.1",3090);socket.setSoTimeout(7000);sockets.add(socket);return socket;}
    private void write(Socket socket,String text)throws IOException{socket.getOutputStream().write(text.getBytes(StandardCharsets.ISO_8859_1));socket.getOutputStream().flush();}
    private void closed(Socket socket)throws IOException{try{check(socket.getInputStream().read()==-1,"INVALID_HEADER_NOT_CLOSED");}catch(SocketException expectedReset){}}
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;HttpShellService bridge=null;Map<String,Object> metrics=new LinkedHashMap<>();
        try{
            DeviceAuditSupport.requireIsolated(getTargetContext());screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);
            try(ServerSocket probe=new ServerSocket()){probe.bind(new InetSocketAddress("127.0.0.1",3090));}
            bridge=new HttpShellService(getTargetContext());bridge.start();long until=android.os.SystemClock.elapsedRealtime()+5000;while(!HttpShellService.isReady()&&android.os.SystemClock.elapsedRealtime()<until)Thread.sleep(20);check(HttpShellService.isReady(),"LOCAL_BRIDGE_PORT_UNAVAILABLE");
            var field=HttpShellService.class.getDeclaredField("authToken");field.setAccessible(true);String token=(String)field.get(null);check(token!=null&&!token.isEmpty(),"BRIDGE_AUTH_NOT_READY");
            try(Socket socket=connect()){
                String target="/health?padding="+"x".repeat(74000);write(socket,"GET "+target+" HTTP/1.1\r\nHost: localhost\r\nX-Token: "+token+"\r\n\r\n");
                var head=HttpProtocol.readHead(socket.getInputStream(),socket,HttpProtocol.LAN,HttpProtocol.deadline(7000),false);check(head!=null&&head.status==200,"ENCODED_COMMAND_BUDGET");
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();HttpProtocol.copyBody(head.responseBody(HttpProtocol.parse("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n",true)),socket.getInputStream(),bytes);
                check(bytes.toString("UTF-8").contains("\"OK\""),"AUTHENTICATED_HEALTH_FAILED");
            }pass("real_bridge_large_valid_target_and_authenticated_route");
            for(String invalid:List.of("GET /"+"x".repeat(99000),"GET /health HTTP/1.1\r\nHost: a\r\nX: "+"y".repeat(17000),
                    "GET /health HTTP/1.1\r\nHost: a\r\n"+("X: "+"z".repeat(15000)+"\r\n").repeat(10)+"\r\n",
                    "GET /health HTTP/1.1\r\nHost: a\r\n"+"X: a\r\n".repeat(101)+"\r\n"))try(Socket socket=connect()){try{write(socket,invalid);}catch(SocketException expectedReset){}closed(socket);}
            pass("real_bridge_line_field_total_and_count_limits");
            try(Socket socket=connect()){
                Thread slow=new Thread(()->{try{for(int i=0;i<40;i++){socket.getOutputStream().write('G');socket.getOutputStream().flush();Thread.sleep(200);}}catch(Exception stopped){}});slow.setDaemon(true);slow.start();long began=System.nanoTime();closed(socket);long elapsed=(System.nanoTime()-began)/1000000;metrics.put("slowHeaderMs",elapsed);check(elapsed<6500,"BRIDGE_SLOW_HEADER_RENEWS_BUDGET");slow.interrupt();slow.join(1000);
            }pass("real_bridge_absolute_header_deadline");
            List<Socket> flood=new ArrayList<>();for(int i=0;i<32;i++)flood.add(connect());Thread.sleep(250);metrics.put("flood",HttpShellService.resourceMetrics());
            check(HttpShellService.resourceMetrics().get("connections")<=20&&HttpShellService.resourceMetrics().get("queued")<=16,"BRIDGE_UNBOUNDED_QUEUE");bridge.stop();
            for(Socket socket:flood){socket.setSoTimeout(2000);closed(socket);}check(!HttpShellService.isReady(),"BRIDGE_STOP_NOT_COMPLETE");pass("real_bridge_admission_queue_and_shutdown_reclaim_sockets");
        }catch(Throwable failure){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(failure)));}
        finally{if(bridge!=null)bridge.stop();for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}if(screen!=null){Activity activity=screen;runOnMainSync(activity::finish);}
            try{result.putString("report",new String(BackupJson.write(Map.of("status",result.containsKey("failure")?"FAIL":"PASS","tests",cases,"metrics",metrics,"flavor",BuildConfig.FLAVOR),65536),StandardCharsets.UTF_8));}catch(Exception error){result.putString("failure",error.toString());}finish(result.containsKey("failure")?1:0,result);}
    }
}
