package com.deepseekharness.app;

import android.app.*;
import android.os.Bundle;
import com.deepseekharness.app.util.*;
import com.deepseekharness.app.backup.BackupJson;
import com.deepseekharness.app.core.HarnessController;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** 真机本地模拟后端与真实 LAN 服务；仅允许独立非调试验收包。 */
public final class NetworkDeviceAudit extends Instrumentation {
    private final List<Map<String,Object>> tests=new ArrayList<>();private final List<Socket> clients=new ArrayList<>();
    private String token;private Backend backend;private long generation=9131401;
    private void check(boolean value,String message)throws IOException{if(!value)throw new IOException(message);}
    private void pass(String name){tests.add(Map.of("name",name,"status","PASS"));Bundle value=new Bundle();value.putString("case",name);value.putString("status","PASS");sendStatus(1,value);}
    private static byte[] bytes(String text){return text.getBytes(StandardCharsets.ISO_8859_1);}
    private static void write(OutputStream out,String value)throws IOException{out.write(bytes(value));out.flush();}
    private Socket connect()throws IOException{Socket value=new Socket();value.connect(new InetSocketAddress("127.0.0.1",LanProxyService.LAN_PORT),2000);value.setSoTimeout(8000);clients.add(value);return value;}
    private String header(String method,String path,String more){return method+" "+path+" HTTP/1.1\r\nHost: localhost\r\nCookie: dsha_lan="+token+"\r\n"+more+"\r\n";}
    private HttpProtocol.Head response(Socket socket)throws IOException{return HttpProtocol.readHead(socket.getInputStream(),socket,HttpProtocol.LAN,HttpProtocol.deadline(8000),false);}
    private byte[] body(Socket socket,HttpProtocol.Head head,String method)throws Exception{ByteArrayOutputStream out=new ByteArrayOutputStream();HttpProtocol.copyBody(head.responseBody(HttpProtocol.parse(header(method,"/",""),true)),socket.getInputStream(),out);return out.toByteArray();}
    private void startLan()throws Exception{
        check(LanProxyService.setDshAuthCookie("dsh-auth-audit=owned_fixture_cookie",generation),"AUTH_FIXTURE");
        LanProxyService.start(null,getTargetContext(),backend.listener.getLocalPort(),generation);
        long until=System.nanoTime()+3000000000L;while(!LanProxyService.isBound()&&System.nanoTime()<until)Thread.sleep(20);check(LanProxyService.isBound(),"LAN_PORT_UNAVAILABLE");
    }
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){Bundle result=new Bundle();Activity screen=null;Map<String,Object> metrics=new LinkedHashMap<>();
        try{
            DeviceAuditSupport.requireIsolated(getTargetContext());screen=DeviceAuditSupport.open(this,DeviceAuditActivity.class);
            // 固定服务端口如果已被其它安装占用，直接失败，不停止或接管其它进程。
            try(ServerSocket probe=new ServerSocket()){probe.bind(new InetSocketAddress("127.0.0.1",LanProxyService.LAN_PORT));}
            backend=new Backend();token=LanProxyService.getLanToken(getTargetContext());startLan();
            metrics.put("before",sample());
            try(Socket socket=connect()){
                long began=System.nanoTime();write(socket.getOutputStream(),header("GET","/zero",""));var zero=response(socket);check(zero!=null&&zero.status==200&&body(socket,zero,"GET").length==0,"ZERO_RESPONSE status="+(zero==null?-1:zero.status)+", backend="+backend.listener.getInetAddress());
                write(socket.getOutputStream(),header("GET","/ok",""));check(Arrays.equals(bytes("OK"),body(socket,response(socket),"GET")),"KEEP_ALIVE_NEXT_REQUEST");check(System.nanoTime()-began<3000000000L,"ZERO_WAITED_FOR_BACKEND_EOF");
            }pass("zero_length_keep_alive_accepts_next_request");
            for(String target:new String[]{"/head","/204","/304"})try(Socket socket=connect()){
                String method=target.equals("/head")?"HEAD":"GET";write(socket.getOutputStream(),header(method,target,""));check(body(socket,response(socket),method).length==0,"BODYLESS_RESPONSE");
            }pass("head_204_304_do_not_wait_for_eof");
            try(Socket socket=connect()){
                write(socket.getOutputStream(),header("GET","/interim",""));check(response(socket).status==103,"EARLY_HINTS");check(response(socket).status==100,"CONTINUE");check(Arrays.equals(bytes("OK"),body(socket,response(socket),"GET")),"FINAL_AFTER_INTERIM");
            }pass("informational_responses_precede_final_response");
            try(Socket socket=connect()){
                write(socket.getOutputStream(),header("GET","/chunked",""));check(Arrays.equals(backend.chunked,body(socket,response(socket),"GET")),"LARGE_CHUNK_OR_TRAILER_CHANGED");
                write(socket.getOutputStream(),header("GET","/ok",""));check(Arrays.equals(bytes("OK"),body(socket,response(socket),"GET")),"TRAILER_CONSUMPTION");
            }pass("chunk_over_one_mib_extensions_trailer_and_keep_alive");
            try(Socket socket=connect()){
                write(socket.getOutputStream(),header("POST","/echo","Content-Length: "+backend.binary.length+"\r\n"));socket.getOutputStream().write(backend.binary);socket.getOutputStream().flush();check(Arrays.equals(backend.binary,body(socket,response(socket),"POST")),"BINARY_ATTACHMENT_CHANGED");
            }
            try(Socket socket=connect()){
                write(socket.getOutputStream(),header("GET","/gzip",""));var head=response(socket);check(head.value("Content-Encoding").equals("gzip"),"COMPRESSION_HEADER");check(Arrays.equals(backend.gzip,body(socket,head,"GET")),"GZIP_BYTES_CHANGED");
            }pass("large_attachment_and_compressed_binary_bytes_preserved");
            try(Socket socket=connect()){write(socket.getOutputStream(),header("GET","/eof",""));var head=response(socket);check(head.close()&&Arrays.equals(bytes("CLOSE"),body(socket,head,"GET")),"EOF_RESPONSE_BOUNDARY");}
            for(String target:new String[]{"/short","/bad-chunk"})try(Socket socket=connect()){
                write(socket.getOutputStream(),header("GET",target,""));var head=response(socket);boolean failed=false;try{body(socket,head,"GET");}catch(IOException expected){failed=true;}check(failed,"INCOMPLETE_BODY_ACCEPTED");
            }
            try(Socket socket=connect()){write(socket.getOutputStream(),header("GET","/ambiguous",""));check(response(socket).status==502,"AMBIGUOUS_BACKEND_FORWARDED");}
            pass("eof_short_bodies_invalid_chunks_and_conflicting_lengths");
            String upgrade="Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n";
            try(Socket socket=connect()){
                write(socket.getOutputStream(),header("GET","/reject-upgrade",upgrade));var head=response(socket);check(head.status==400&&body(socket,head,"GET").length==0,"REJECTED_UPGRADE_TUNNELED");
                write(socket.getOutputStream(),header("GET","/ok",""));check(Arrays.equals(bytes("OK"),body(socket,response(socket),"GET")),"AFTER_REJECTED_UPGRADE");
            }
            try(Socket socket=connect()){
                write(socket.getOutputStream(),header("GET","/ws",upgrade));var head=response(socket);check(head.responseBody(HttpProtocol.parse(header("GET","/ws",upgrade),true)).kind==HttpProtocol.Kind.UPGRADE,"WS_HANDSHAKE");
                socket.getOutputStream().write(new byte[]{(byte)0x81,(byte)0x82,1,2,3,4,(byte)('h'^1),(byte)('i'^2)});socket.getOutputStream().flush();byte[] frame=new byte[4];new DataInputStream(socket.getInputStream()).readFully(frame);check(Arrays.equals(frame,new byte[]{(byte)0x81,2,'h','i'}),"WS_FRAME_BYTES");
                socket.shutdownOutput();new DataInputStream(socket.getInputStream()).readFully(frame);check(Arrays.equals(frame,new byte[]{(byte)0x88,2,3,(byte)0xe8}),"WS_HALF_CLOSE");
            }pass("websocket_upgrade_denial_success_frames_and_half_close");
            List<Socket> sse=new ArrayList<>();for(int i=0;i<2;i++){Socket socket=connect();sse.add(socket);write(socket.getOutputStream(),header("GET","/sse","Accept: text/event-stream\r\n"));check(response(socket).status==200,"SSE_HEADER");check(socket.getInputStream().read()=='9',"SSE_NOT_STREAMING");}
            long began=System.nanoTime();for(int i=0;i<8;i++)try(Socket socket=connect()){write(socket.getOutputStream(),header("GET","/ok",""));check(Arrays.equals(bytes("OK"),body(socket,response(socket),"GET")),"ORDINARY_WITH_SSE");}
            metrics.put("ordinaryEightWithSseMs",(System.nanoTime()-began)/1000000);metrics.put("streaming",sample());pass("continuous_sse_leaves_ordinary_request_capacity");
            LanProxyService.stopLanListener();for(Socket socket:sse){socket.setSoTimeout(2000);int read=0;while(socket.getInputStream().read()!=-1)check(++read<65536,"SSE_STOP_UNBOUNDED");}startLan();pass("lan_stop_closes_streams_and_can_restart");
            try(Socket socket=connect()){
                Thread sender=new Thread(()->{try{for(int i=0;i<80;i++){socket.getOutputStream().write('G');socket.getOutputStream().flush();Thread.sleep(100);}}catch(Exception ignored){}});sender.setDaemon(true);sender.start();long begin=System.nanoTime();
                var head=response(socket);check(head==null||head.status==408,"SLOW_HEADER_RESULT");long elapsed=(System.nanoTime()-begin)/1000000;metrics.put("slowHeaderMs",elapsed);check(elapsed<6500,"SLOW_HEADER_RENEWED_DEADLINE");sender.interrupt();sender.join(1000);
            }
            try(Socket socket=connect()){try{write(socket.getOutputStream(),"GET /"+"x".repeat(20000));}catch(IOException allowedClose){}var head=response(socket);check(head==null||head.status==431,"LONG_NO_NEWLINE");}
            pass("absolute_slow_header_deadline_and_oversized_line");
            List<Socket> flood=new ArrayList<>();for(int i=0;i<48;i++)flood.add(connect());Thread.sleep(300);metrics.put("flood",sample());check(LanProxyService.resourceMetrics().get("connections")<=32&&LanProxyService.resourceMetrics().get("queued")<=16,"UNBOUNDED_LAN_RESOURCES");
            LanProxyService.stopLanListener();for(Socket socket:flood){socket.setSoTimeout(2000);try{check(socket.getInputStream().read()==-1,"FLOOD_SOCKET_NOT_CLOSED");}catch(SocketException closed){}}startLan();pass("connection_surge_bounded_and_stop_reclaims_queue");
            Socket held=connect();write(held.getOutputStream(),header("GET","/sse","Accept: text/event-stream\r\n"));check(response(held).status==200,"MAINTENANCE_STREAM_START");
            BackupManager.runDataTask(HarnessController.get(getTargetContext()),()->{check(!LanProxyService.isRunning(),"MAINTENANCE_LEFT_LAN_RUNNING");return null;});pass("maintenance_barrier_closes_lan");
            Thread.sleep(300);metrics.put("afterProxyStop",sample());result.putString("status","PASS");
        }catch(Throwable failure){result.putString("failure",SensitiveData.redact(android.util.Log.getStackTraceString(failure)));}
        finally{
            LanProxyService.stop();for(Socket socket:clients)try{socket.close();}catch(IOException ignored){}if(backend!=null)backend.close();
            try{Thread.sleep(200);metrics.put("afterFixtureCleanup",sample());
                if(metrics.get("before") instanceof Map){long before=((Number)((Map<?,?>)metrics.get("before")).get("fileDescriptors")).longValue();
                    long after=((Number)((Map<?,?>)metrics.get("afterFixtureCleanup")).get("fileDescriptors")).longValue();
                    if(before>=0&&after>before+12)result.putString("failure","NETWORK_FD_NOT_RECLAIMED: before="+before+", after="+after);}
            }catch(InterruptedException interrupted){Thread.currentThread().interrupt();result.putString("failure","NETWORK_CLEANUP_INTERRUPTED");}
            if(screen!=null){Activity activity=screen;runOnMainSync(activity::finish);}
            Map<String,Object> report=new LinkedHashMap<>();report.put("status",result.containsKey("failure")?"FAIL":"PASS");report.put("tests",tests);report.put("metrics",metrics);report.put("metricScope","whole audit process, includes local clients and mock backend; no Node/browser process in this load fixture");report.put("flavor",BuildConfig.FLAVOR);
            try{result.putString("report",new String(BackupJson.write(report,65536),StandardCharsets.UTF_8));}catch(IOException error){result.putString("failure",error.toString());}
            finish(result.containsKey("failure")?1:0,result);
        }
    }
    private Map<String,Object> sample(){Map<String,Object> values=new LinkedHashMap<>();values.put("proxy",LanProxyService.resourceMetrics());String[] fds=new File("/proc/self/fd").list();values.put("fileDescriptors",fds==null?-1:fds.length);values.put("threads",Thread.getAllStackTraces().size());android.os.Debug.MemoryInfo memory=new android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory);values.put("processPssKiB",memory.getTotalPss());values.put("javaUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());return values;}
    private static final class Backend implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,16,InetAddress.getByName("127.0.0.1"));final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
        final ThreadPoolExecutor pool=new ThreadPoolExecutor(8,8,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(16));final byte[] binary=new byte[1024*1024+777],gzip,chunked;volatile boolean active=true;
        Backend()throws IOException{new Random(130).nextBytes(binary);ByteArrayOutputStream zipped=new ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream out=new java.util.zip.GZIPOutputStream(zipped)){out.write(binary);}gzip=zipped.toByteArray();ByteArrayOutputStream chunk=new ByteArrayOutputStream();write(chunk,Integer.toHexString(binary.length)+";fixture=\"binary\"\r\n");chunk.write(binary);write(chunk,"\r\n0\r\nDigest: fixture\r\n\r\n");chunked=chunk.toByteArray();
            Thread accept=new Thread(()->{while(active)try{Socket socket=listener.accept();if(sockets.size()>=24){socket.close();continue;}sockets.add(socket);try{pool.execute(()->serve(socket));}catch(RejectedExecutionException busy){sockets.remove(socket);socket.close();}}catch(IOException stopped){break;}},"network-audit-backend");accept.setDaemon(true);accept.start();}
        void serve(Socket socket){try(socket){socket.setSoTimeout(10000);InputStream in=socket.getInputStream();OutputStream out=socket.getOutputStream();HttpProtocol.Head request=HttpProtocol.readHead(in,socket,HttpProtocol.BRIDGE,HttpProtocol.deadline(5000),true);if(request==null)return;
            ByteArrayOutputStream upload=new ByteArrayOutputStream();HttpProtocol.copyBody(request.requestBody(),in,upload);String path=request.target;
            if(path.equals("/zero")||path.equals("/reject-upgrade")){write(out,"HTTP/1.1 "+(path.equals("/zero")?"200 OK":"400 Bad Request")+"\r\nContent-Length: 0\r\nConnection: keep-alive\r\n\r\n");in.read();}
            else if(path.equals("/head")||path.equals("/204")||path.equals("/304")){write(out,"HTTP/1.1 "+(path.equals("/204")?"204 No Content":path.equals("/304")?"304 Not Modified":"200 OK")+"\r\n"+(path.equals("/204")?"":"Content-Length: 100\r\n")+"\r\n");in.read();}
            else if(path.equals("/interim")){write(out,"HTTP/1.1 103 Early Hints\r\nLink: </style.css>; rel=preload\r\n\r\nHTTP/1.1 100 Continue\r\n\r\n");fixed(out,bytes("OK"),"");}
            else if(path.equals("/chunked")){write(out,"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nTrailer: Digest\r\n\r\n");out.write(chunked);out.flush();in.read();}
            else if(path.equals("/echo"))fixed(out,upload.toByteArray(),"");
            else if(path.equals("/gzip"))fixed(out,gzip,"Content-Encoding: gzip\r\n");
            else if(path.equals("/eof"))write(out,"HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nCLOSE");
            else if(path.equals("/short"))write(out,"HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nNO");
            else if(path.equals("/bad-chunk"))write(out,"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\ninvalid\r\n");
            else if(path.equals("/ambiguous"))write(out,"HTTP/1.1 200 OK\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n");
            else if(path.equals("/ws")){write(out,"HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n\r\n");byte[] frame=new byte[8];new DataInputStream(in).readFully(frame);out.write(new byte[]{(byte)0x81,2,(byte)(frame[6]^frame[2]),(byte)(frame[7]^frame[3])});out.flush();while(in.read()!=-1){}out.write(new byte[]{(byte)0x88,2,3,(byte)0xe8});out.flush();}
            else if(path.equals("/sse")){write(out,"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n");while(active){write(out,"9\r\ndata: x\n\n\r\n");Thread.sleep(100);}}
            else fixed(out,bytes("OK"),"");
        }catch(Exception expectedDisconnect){}finally{sockets.remove(socket);}}
        void fixed(OutputStream out,byte[] body,String extra)throws IOException{write(out,"HTTP/1.1 200 OK\r\nContent-Length: "+body.length+"\r\n"+extra+"\r\n");out.write(body);out.flush();}
        public void close(){active=false;try{listener.close();}catch(IOException ignored){}for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}pool.shutdownNow();try{pool.awaitTermination(2000,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
    }
}
