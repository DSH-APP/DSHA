package com.deepseekharness.app.util;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class HttpProtocolTest {
    private HttpProtocol.Head request(String extra)throws Exception{return HttpProtocol.parse("GET / HTTP/1.1\r\nHost: localhost\r\n"+extra+"\r\n",true);}
    private HttpProtocol.Head response(String code,String extra)throws Exception{return HttpProtocol.parse("HTTP/1.1 "+code+"\r\n"+extra+"\r\n",false);}
    private byte[] bytes(String text){return text.getBytes(StandardCharsets.ISO_8859_1);}
    @Test public void absentZeroAndPositiveLengthsRemainDistinct()throws Exception{
        var get=request("");assertEquals(HttpProtocol.Kind.CLOSE,response("200 OK","").responseBody(get).kind);
        var zero=response("200 OK","Content-Length: 0\r\n").responseBody(get);assertEquals(HttpProtocol.Kind.FIXED,zero.kind);assertEquals(0,zero.length);
        assertEquals(25,response("200 OK","Content-Length: 25\r\n").responseBody(get).length);
        assertEquals(HttpProtocol.Kind.NONE,get.requestBody().kind);
    }
    @Test public void bodylessAndInterimResponsesNeverWaitForEof()throws Exception{
        var head=HttpProtocol.parse("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n",true);
        assertEquals(HttpProtocol.Kind.NONE,response("200 OK","Content-Length: 999\r\n").responseBody(head).kind);
        for(String code:new String[]{"100 Continue","103 Early Hints","204 No Content","304 Not Modified"})assertEquals(HttpProtocol.Kind.NONE,response(code,"").responseBody(request("")).kind);
        assertThrows(IOException.class,()->response("204 No Content","Content-Length: 0\r\n"));
    }
    @Test public void ambiguousUnsupportedAndOverflowLengthsFailBeforeForwarding()throws Exception{
        for(String fields:new String[]{"Content-Length: 0\r\nContent-Length: 4\r\n","Content-Length: 0, 0\r\n","Content-Length: -1\r\n","Content-Length: 9223372036854775808\r\n","Content-Length: 1\r\nTransfer-Encoding: chunked\r\n","Transfer-Encoding: gzip, chunked\r\n"})assertThrows(IOException.class,()->request(fields));
        assertThrows(IOException.class,()->request(" folded: unsafe\r\n"));
        assertThrows(IOException.class,()->request("Connection: content-length\r\n"));
        assertThrows(IOException.class,()->HttpProtocol.parse("CONNECT host:443 HTTP/1.1\r\nHost: host\r\n\r\n",true));
    }
    @Test public void upgradeRequiresActualMatchingWebsocketHandshake()throws Exception{
        var get=request("Connection: keep-alive, Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n");
        assertTrue(get.websocketRequest());assertEquals(HttpProtocol.Kind.FIXED,response("403 Forbidden","Content-Length: 0\r\n").responseBody(get).kind);
        var accepted=response("101 Switching Protocols","Connection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n");
        assertEquals(HttpProtocol.Kind.UPGRADE,accepted.responseBody(get).kind);
        assertThrows(IOException.class,()->accepted.responseBody(request("")));
        assertThrows(IOException.class,()->response("101 Switching Protocols","Upgrade: websocket\r\n").responseBody(get));
    }
    @Test public void chunkedExtensionsTrailersAndNextMessageRemainByteExact()throws Exception{
        String wire="4;name=\"a;b\"\r\n\u0000\u00ffAB\r\n0\r\nDigest: example\r\n\r\nNEXT";
        ByteArrayInputStream input=new ByteArrayInputStream(bytes(wire));ByteArrayOutputStream output=new ByteArrayOutputStream();HttpProtocol.copyChunked(input,output);
        assertArrayEquals(bytes(wire.substring(0,wire.length()-4)),output.toByteArray());assertEquals("NEXT",new String(input.readAllBytes(),StandardCharsets.ISO_8859_1));
    }
    @Test public void malformedShortChunkAndTrailerBoundariesAreRejected()throws Exception{
        for(String body:new String[]{"1\r\n","-1\r\n","8000000000000000\r\n","2\r\nx\r\n","0\r\nSet-Cookie: secret\r\n\r\n","0\r\nContent-Length: 3\r\n\r\n","1;bad=\"open\r\nx\r\n0\r\n\r\n"})assertThrows(IOException.class,()->HttpProtocol.copyChunked(new ByteArrayInputStream(bytes(body)),new ByteArrayOutputStream()));
        assertThrows(EOFException.class,()->HttpProtocol.copyExact(new ByteArrayInputStream(new byte[1]),new ByteArrayOutputStream(),2));
    }
    @Test public void firstFieldTotalAndCountLimitsApplyDuringReading()throws Exception{
        var limits=new HttpProtocol.Limits(40,20,80,2,1000);
        for(String head:new String[]{"GET /"+"x".repeat(60),"GET / HTTP/1.1\r\nX: "+"y".repeat(22),"GET / HTTP/1.1\r\nHost: a\r\nA: a\r\nB: b\r\n\r\n"})assertThrows(IOException.class,()->HttpProtocol.readHead(new ByteArrayInputStream(bytes(head)),null,limits,HttpProtocol.deadline(1000),true));
        var total=new HttpProtocol.Limits(40,40,80,30,1000);
        assertThrows(IOException.class,()->HttpProtocol.readHead(new ByteArrayInputStream(bytes("GET / HTTP/1.1\r\nHost: a\r\n"+"A: "+"a".repeat(25)+"\r\nB: "+"b".repeat(25)+"\r\n\r\n")),null,total,HttpProtocol.deadline(1000),true));
    }
    @Test public void existingCommandLimitFitsUtf8PercentEncodedBridgeRequest()throws Exception{
        String command=URLEncoder.encode("测".repeat(8192),StandardCharsets.UTF_8);
        String raw="GET /exec?cmd="+command+"&token="+"a".repeat(64)+" HTTP/1.1\r\nHost: 127.0.0.1:3090\r\n\r\n";
        assertEquals(raw.length(),HttpProtocol.readHead(new ByteArrayInputStream(bytes(raw)),null,HttpProtocol.BRIDGE,HttpProtocol.deadline(5000),true).raw().length());
    }
    @Test public void hopFieldsAreRemovedWithoutRemovingChunkFraming()throws Exception{
        var request=request("Connection: keep-alive, Custom-Hop\r\nCustom-Hop: local\r\nKeep-Alive: timeout=10\r\nTE: trailers\r\nTransfer-Encoding: chunked\r\nContent-Encoding: gzip\r\n");
        String forwarded=request.forwarded(false,true);assertFalse(forwarded.contains("Custom-Hop"));assertFalse(forwarded.contains("Keep-Alive:"));assertFalse(forwarded.contains("TE:"));
        assertTrue(forwarded.contains("Transfer-Encoding: chunked"));assertTrue(forwarded.contains("Content-Encoding: gzip"));
    }
    @Test public void slowByteSenderCannotRenewHeaderDeadline()throws Exception{
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());Socket client=new Socket(listener.getInetAddress(),listener.getLocalPort());Socket server=listener.accept()){
            ExecutorService pool=Executors.newSingleThreadExecutor();Future<?> slow=pool.submit(()->{try{for(int i=0;i<100;i++){client.getOutputStream().write('G');client.getOutputStream().flush();Thread.sleep(30);}}catch(Exception ignored){}});
            long began=System.nanoTime();try{assertThrows(SocketTimeoutException.class,()->HttpProtocol.readHead(server.getInputStream(),server,HttpProtocol.LAN,HttpProtocol.deadline(240),true));
                long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began);assertTrue("deadline="+elapsed,elapsed>=180&&elapsed<1500);
            }finally{slow.cancel(true);pool.shutdownNow();assertTrue(pool.awaitTermination(2,TimeUnit.SECONDS));}
        }
    }
    @Test public void queuedTimeCountsAndZeroBodyLeavesFollowingResponseUntouched()throws Exception{
        assertThrows(SocketTimeoutException.class,()->HttpProtocol.readHead(new ByteArrayInputStream(bytes("GET / HTTP/1.1\r\nHost: a\r\n\r\n")),null,HttpProtocol.LAN,System.nanoTime()-1,true));
        ByteArrayInputStream input=new ByteArrayInputStream(bytes("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nHTTP/1.1 204 No Content\r\n\r\n"));
        var zero=HttpProtocol.readHead(input,null,HttpProtocol.LAN,HttpProtocol.deadline(1000),false);HttpProtocol.copyBody(zero.responseBody(request("")),input,new ByteArrayOutputStream());
        assertEquals(204,HttpProtocol.readHead(input,null,HttpProtocol.LAN,HttpProtocol.deadline(1000),false).status);
    }
}
