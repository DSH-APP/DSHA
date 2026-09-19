package com.deepseekharness.app.util;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 有界 HTTP/1 头与流式消息边界。正文预算独立，不用头部限额裁剪附件。 */
public final class HttpProtocol {
    private HttpProtocol() { }
    public static final Limits LAN = new Limits(16384,16384,65536,100,5000);
    // 设备命令最多 8192 字符；UTF-8 百分号编码最坏约 72 KiB，另留查询参数空间。
    public static final Limits BRIDGE = new Limits(98304,16384,131072,100,5000);
    public static final int BODY_IDLE_MS=120000, STREAM_THRESHOLD=65536;
    private static final String TOKEN="[!#$%&'*+.^_`|~0-9A-Za-z-]+";
    public static final class Limits {
        public final int firstLine,field,total,count,timeoutMs;
        public Limits(int firstLine,int field,int total,int count,int timeoutMs){
            if(firstLine<1||field<1||total<firstLine||count<1||timeoutMs<1)throw new IllegalArgumentException();
            this.firstLine=firstLine;this.field=field;this.total=total;this.count=count;this.timeoutMs=timeoutMs;
        }
    }
    public static final class Failure extends IOException {
        public final int status;
        public Failure(int status,String code){super(code);this.status=status;}
    }
    public static long deadline(int millis){return System.nanoTime()+millis*1000000L;}
    private static final class HeaderReader {
        final InputStream in;final Socket socket;final long until;final int maximum;int bytes,lastTimeout;
        HeaderReader(InputStream in,Socket socket,long until,int maximum){this.in=in;this.socket=socket;this.until=until;this.maximum=maximum;}
        int read()throws IOException{
            if(until!=Long.MAX_VALUE){long remaining=until-System.nanoTime();if(remaining<=0)throw new SocketTimeoutException("HTTP_HEADER_DEADLINE");
                int timeout=(int)Math.min(Integer.MAX_VALUE,Math.max(1,(remaining+999999)/1000000));
                if(socket!=null&&timeout!=lastTimeout){socket.setSoTimeout(timeout);lastTimeout=timeout;}}
            int value=in.read();if(value>=0&&++bytes>maximum)throw new Failure(431,"HTTP_HEADERS_TOO_LARGE");return value;
        }
        String line(int max,boolean eofAllowed)throws IOException{
            ByteArrayOutputStream text=new ByteArrayOutputStream(Math.min(max,512));
            while(true){int value=read();if(value<0){if(text.size()==0&&eofAllowed)return null;throw new EOFException("HTTP_INCOMPLETE_HEADER");}
                if(value=='\r'){if(read()!='\n')throw new Failure(400,"HTTP_INVALID_CRLF");return text.toString(StandardCharsets.ISO_8859_1.name());}
                if(value=='\n'||value==0||value==127||(value<32&&value!='\t'))throw new Failure(400,"HTTP_INVALID_FIELD_BYTE");
                if(text.size()>=max)throw new Failure(431,"HTTP_LINE_TOO_LARGE");text.write(value);
            }
        }
    }
    public static Head readHead(InputStream in,Socket socket,Limits limits,long until,boolean request)throws IOException{
        HeaderReader reader=new HeaderReader(in,socket,until,limits.total);
        String first=reader.line(limits.firstLine,true);if(first==null)return null;
        List<Field> fields=new ArrayList<>();
        while(true){String line=reader.line(limits.field,false);if(line.isEmpty())break;
            if(fields.size()>=limits.count)throw new Failure(431,"HTTP_TOO_MANY_FIELDS");fields.add(field(line));}
        return new Head(first,fields,request);
    }
    public static Head parse(String value,boolean request)throws IOException{
        ByteArrayInputStream input=new ByteArrayInputStream(value.getBytes(StandardCharsets.ISO_8859_1));
        Head result=readHead(input,null,BRIDGE,deadline(5000),request);
        if(result==null||input.available()!=0)throw new Failure(400,"HTTP_INVALID_HEADER_END");return result;
    }
    public static final class Field {
        public final String name,value;
        Field(String name,String value){this.name=name;this.value=value;}
    }
    private static Field field(String line)throws IOException{
        int colon=line.indexOf(':');if(colon<=0||!line.substring(0,colon).matches(TOKEN))throw new Failure(400,"HTTP_INVALID_FIELD_NAME");
        return new Field(line.substring(0,colon),line.substring(colon+1).trim());
    }
    public enum Kind { NONE,FIXED,CHUNKED,CLOSE,UPGRADE }
    public static final class Body {
        public final Kind kind;public final long length;
        Body(Kind kind,long length){this.kind=kind;this.length=length;}
        public boolean streaming(){return kind==Kind.CHUNKED||kind==Kind.CLOSE||kind==Kind.UPGRADE||length>STREAM_THRESHOLD;}
    }
    public static final class Head {
        public final String first,method,target,version;public final int status;public final List<Field> fields;
        private final Long length;private final boolean chunked;
        Head(String first,List<Field> fields,boolean request)throws IOException{
            this.first=first;this.fields=Collections.unmodifiableList(fields);
            String[] parts=first.split(" ",3);
            if(request){
                if(parts.length!=3||!parts[0].matches(TOKEN)||!parts[1].startsWith("/")||parts[1].contains("#")||parts[1].matches(".*[\\s\\x00-\\x1f\\x7f].*")||!parts[2].matches("HTTP/1\\.[01]"))throw new Failure(400,"HTTP_INVALID_REQUEST_LINE");
                method=parts[0];target=parts[1];version=parts[2];status=0;
                if(method.equals("CONNECT"))throw new Failure(405,"HTTP_CONNECT_UNSUPPORTED");
                if(values("Host").size()>1||(version.equals("HTTP/1.1")&&values("Host").size()!=1)||(!value("Host").isEmpty()&&value("Host").matches(".*[\\s,/].*")))throw new Failure(400,"HTTP_INVALID_HOST");
            }else{
                if(parts.length<2||!parts[0].matches("HTTP/1\\.[01]")||!parts[1].matches("[1-5][0-9]{2}"))throw new Failure(502,"HTTP_INVALID_STATUS_LINE");
                version=parts[0];method="";target="";status=Integer.parseInt(parts[1]);
            }
            List<String> lengths=values("Content-Length"),codings=values("Transfer-Encoding");
            if(lengths.size()>1||codings.size()>1||(!lengths.isEmpty()&&!codings.isEmpty()))throw new Failure(request?400:502,"HTTP_AMBIGUOUS_LENGTH");
            length=lengths.isEmpty()?null:decimal(lengths.get(0),request?400:502);
            if(!codings.isEmpty()&&(!codings.get(0).equalsIgnoreCase("chunked")||!version.equals("HTTP/1.1")))throw new Failure(request?501:502,"HTTP_TRANSFER_CODING_UNSUPPORTED");
            chunked=!codings.isEmpty();
            for(String option:tokens("Connection"))if(Set.of("content-length","transfer-encoding","host","cookie","authorization").contains(option))throw new Failure(400,"HTTP_INVALID_CONNECTION_OPTION");
            if(!request&&(status<200||status==204)&&(length!=null||chunked))throw new Failure(502,"HTTP_BODY_FORBIDDEN");
            if(request&&!value("Expect").isEmpty()&&!value("Expect").equalsIgnoreCase("100-continue"))throw new Failure(417,"HTTP_EXPECTATION_UNSUPPORTED");
            for(String trailer:tokens("Trailer"))if(forbiddenTrailer(trailer))throw new Failure(400,"HTTP_FORBIDDEN_TRAILER");
        }
        public List<String> values(String name){List<String> result=new ArrayList<>();for(Field field:fields)if(field.name.equalsIgnoreCase(name))result.add(field.value);return result;}
        public String value(String name){List<String> values=values(name);return String.join(",",values);}
        public Set<String> tokens(String name)throws IOException{
            Set<String> result=new HashSet<>();for(String line:values(name))for(String token:line.split(",",-1)){
                token=token.trim();if(!token.matches(TOKEN))throw new Failure(400,"HTTP_INVALID_TOKEN_LIST");result.add(token.toLowerCase(Locale.ROOT));}return result;
        }
        public boolean close()throws IOException{return version.equals("HTTP/1.0")||tokens("Connection").contains("close");}
        public boolean websocketRequest()throws IOException{
            if(!tokens("Connection").contains("upgrade")||!value("Upgrade").equalsIgnoreCase("websocket"))return false;
            if(!method.equals("GET")||!version.equals("HTTP/1.1")||!value("Sec-WebSocket-Version").equals("13")||requestBody().kind!=Kind.NONE)throw new Failure(400,"HTTP_INVALID_WEBSOCKET_REQUEST");
            try{if(Base64.getDecoder().decode(value("Sec-WebSocket-Key")).length!=16)throw new IllegalArgumentException();}catch(IllegalArgumentException error){throw new Failure(400,"HTTP_INVALID_WEBSOCKET_KEY");}return true;
        }
        public Body requestBody(){return chunked?new Body(Kind.CHUNKED,0):length==null||length==0?new Body(Kind.NONE,0):new Body(Kind.FIXED,length);}
        public Body responseBody(Head request)throws IOException{
            if(status==101){
                if(!request.websocketRequest()||!tokens("Connection").contains("upgrade")||!value("Upgrade").equalsIgnoreCase("websocket"))throw new Failure(502,"HTTP_UNEXPECTED_UPGRADE");
                try{String accept=Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((request.value("Sec-WebSocket-Key")+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                    if(!accept.equals(value("Sec-WebSocket-Accept")))throw new Failure(502,"HTTP_INVALID_WEBSOCKET_ACCEPT");
                }catch(java.security.NoSuchAlgorithmException impossible){throw new IOException(impossible);}return new Body(Kind.UPGRADE,0);
            }
            if(request.method.equals("HEAD")||status<200||status==204||status==304)return new Body(Kind.NONE,0);
            return chunked?new Body(Kind.CHUNKED,0):length!=null?new Body(Kind.FIXED,length):new Body(Kind.CLOSE,0);
        }
        public String raw(){StringBuilder text=new StringBuilder(first).append("\r\n");for(Field field:fields)text.append(field.name).append(": ").append(field.value).append("\r\n");return text.append("\r\n").toString();}
        public String forwarded(boolean upgrade,boolean close)throws IOException{
            Set<String> remove=new HashSet<>(tokens("Connection"));remove.addAll(Set.of("connection","keep-alive","proxy-connection","proxy-authenticate","proxy-authorization","te","upgrade","expect"));
            StringBuilder text=new StringBuilder(first).append("\r\n");
            for(Field field:fields)if(!remove.contains(field.name.toLowerCase(Locale.ROOT)))text.append(field.name).append(": ").append(field.value).append("\r\n");
            if(upgrade)text.append("Connection: Upgrade\r\nUpgrade: websocket\r\n");else text.append(close?"Connection: close\r\n":"Connection: keep-alive\r\n");
            return text.append("\r\n").toString();
        }
    }
    private static long decimal(String value,int status)throws IOException{
        if(!value.matches("[0-9]+"))throw new Failure(status,"HTTP_INVALID_LENGTH");
        try{return Long.parseLong(value);}catch(NumberFormatException error){throw new Failure(status,"HTTP_LENGTH_OVERFLOW");}
    }
    public static void copyExact(InputStream in,OutputStream out,long length)throws IOException{
        byte[] buffer=new byte[8192];long left=length;while(left>0){int n=in.read(buffer,0,(int)Math.min(buffer.length,left));if(n<0)throw new EOFException("HTTP_INCOMPLETE_BODY");if(n==0)continue;out.write(buffer,0,n);out.flush();left-=n;}
    }
    public static void copyToEnd(InputStream in,OutputStream out)throws IOException{byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(n==0)continue;out.write(buffer,0,n);out.flush();}}
    private static boolean forbiddenTrailer(String name){return Set.of("content-length","transfer-encoding","trailer","host","connection","keep-alive","upgrade","te","cookie","set-cookie","set-cookie2","authorization","proxy-authorization","proxy-authenticate","x-token","x-dsha-token").contains(name)||name.startsWith("dsh-auth-");}
    public static void copyChunked(InputStream in,OutputStream out)throws IOException{
        while(true){
            // 正文的分块行不使用未鉴权请求头的截止时间；仍有单行与 trailer 总量界限。
            String line=new HeaderReader(in,null,Long.MAX_VALUE,8194).line(8192,false);int digits=0;while(digits<line.length()&&Character.digit(line.charAt(digits),16)>=0)digits++;String number=line.substring(0,digits);
            if(!number.matches("[0-9A-Fa-f]{1,16}"))throw new Failure(400,"HTTP_INVALID_CHUNK_SIZE");
            long size;try{size=Long.parseLong(number,16);}catch(NumberFormatException error){throw new Failure(400,"HTTP_CHUNK_OVERFLOW");}
            if(digits<line.length()&&!validExtensions(line.substring(digits)))throw new Failure(400,"HTTP_INVALID_CHUNK_EXTENSION");
            if(size==0){
                HeaderReader trailers=new HeaderReader(in,null,Long.MAX_VALUE,32768);List<String> fields=new ArrayList<>();
                while(true){String value=trailers.line(8192,false);if(value.isEmpty())break;Field field=field(value);
                    if(fields.size()>=32||forbiddenTrailer(field.name.toLowerCase(Locale.ROOT)))throw new Failure(400,"HTTP_INVALID_TRAILER");fields.add(value);}
                out.write((line+"\r\n").getBytes(StandardCharsets.ISO_8859_1));for(String value:fields)out.write((value+"\r\n").getBytes(StandardCharsets.ISO_8859_1));out.write(new byte[]{13,10});out.flush();return;
            }
            out.write((line+"\r\n").getBytes(StandardCharsets.ISO_8859_1));copyExact(in,out,size);
            if(in.read()!='\r'||in.read()!='\n')throw new Failure(400,"HTTP_INVALID_CHUNK_END");out.write(new byte[]{13,10});out.flush();
        }
    }
    private static boolean validExtensions(String value){
        int at=0;while(at<value.length()){
            at=spaces(value,at);if(at>=value.length()||value.charAt(at++)!=';')return false;at=spaces(value,at);
            int start=at;while(at<value.length()&&token(value.charAt(at)))at++;if(at==start)return false;at=spaces(value,at);
            if(at<value.length()&&value.charAt(at)=='='){
                at=spaces(value,at+1);if(at>=value.length())return false;
                if(value.charAt(at)=='"'){
                    at++;boolean ended=false;while(at<value.length()){char ch=value.charAt(at++);if(ch=='"'){ended=true;break;}
                        if(ch=='\\'){if(at>=value.length())return false;ch=value.charAt(at++);}
                        if(ch!='\t'&&(ch<32||ch==127))return false;}
                    if(!ended)return false;
                }else{start=at;while(at<value.length()&&token(value.charAt(at)))at++;if(at==start)return false;}
                at=spaces(value,at);
            }
        }return true;
    }
    private static int spaces(String text,int at){while(at<text.length()&&(text.charAt(at)==' '||text.charAt(at)=='\t'))at++;return at;}
    private static boolean token(char ch){return ch>='0'&&ch<='9'||ch>='A'&&ch<='Z'||ch>='a'&&ch<='z'||"!#$%&'*+.^_`|~-".indexOf(ch)>=0;}
    public static void copyBody(Body body,InputStream in,OutputStream out)throws IOException{
        if(body.kind==Kind.FIXED)copyExact(in,out,body.length);else if(body.kind==Kind.CHUNKED)copyChunked(in,out);else if(body.kind==Kind.CLOSE)copyToEnd(in,out);else if(body.kind==Kind.UPGRADE)throw new IOException("HTTP_UPGRADE_REQUIRES_TUNNEL");out.flush();
    }
}
