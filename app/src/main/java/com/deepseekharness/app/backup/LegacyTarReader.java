package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 历史 tar/gzip 的宿主只读层。返回受限成员流，不按归档路径直接创建文件，也不执行内容。 */
public final class LegacyTarReader {
    public static final class Member {
        public final String path,type,target;
        public final long size;
        public final int mode;
        public Member(String path,String type,String target,long size){this(path,type,target,size,type.equals("DIRECTORY")?0700:0600);}
        public Member(String path,String type,String target,long size,int mode){this.path=path;this.type=type;this.target=target;this.size=size;this.mode=mode&0777;}
    }
    public interface Visitor { void entry(Member entry,InputStream data)throws IOException; }
    private LegacyTarReader() { }
    public static void read(InputStream raw,Visitor visitor,BackupControl control)throws IOException{
        try(PushbackInputStream start=new PushbackInputStream(raw,2)){
        int first=start.read(),second=start.read();if(second>=0)start.unread(second);if(first>=0)start.unread(first);
        boolean gzip=first==31&&second==139;
        try(InputStream input=gzip?new StrictGzipInputStream(start):start){
        byte[] header=new byte[512],buffer=new byte[65536];long total=0,metadata=0,pathBytes=0;int count=0,headers=0;
        Set<String> names=new HashSet<>();String longName=null,longLink=null;Map<String,String> pax=Collections.emptyMap();
        while(true){control.check();if(++headers>BackupLimits.ENTRIES*4)throw new IOException("TAR_HEADER_LIMIT");exact(input,header,512);if(zero(header)){
                byte[] secondBlock=new byte[512];exact(input,secondBlock,512);if(!zero(secondBlock))throw new IOException("TAR_END");
                // 一直读到外层 EOF，强制 GZIP 校验 CRC 和截断；只允许零填充，拒绝拼接另一个归档。
                int n;long tail=0;while((n=input.read(buffer))!=-1){control.check();tail=BackupLimits.add(tail,n,1024*1024);for(int i=0;i<n;i++)if(buffer[i]!=0)throw new IOException("TAR_TRAILING_DATA");}
                if(longName!=null||longLink!=null||!pax.isEmpty())throw new IOException("TAR_METADATA_WITHOUT_ENTRY");return;
            }
            long checksum=number(header,148,8),sum=0;for(int i=0;i<512;i++)sum+=(i>=148&&i<156)?32:header[i]&255;if(checksum!=sum)throw new IOException("TAR_CHECKSUM");
            String name=text(header,0,100),prefix=text(header,345,155),target=text(header,157,100);if(!prefix.isEmpty())name=prefix+"/"+name;
            int type=header[156]&255;long size=number(header,124,12);if(size>BackupLimits.BYTES)throw new IOException("ARCHIVE_LIMIT");
            if(type=='L'||type=='K'||type=='x'||type=='g'){
                if(size>BackupLimits.MANIFEST)throw new IOException("TAR_METADATA_LIMIT");metadata=BackupLimits.add(metadata,size,BackupLimits.METADATA);
                byte[] bytes=new byte[(int)size];exact(input,bytes,bytes.length);padding(input,size);
                if(type=='L')longName=terminated(bytes);else if(type=='K')longLink=terminated(bytes);
                else {Map<String,String> values=pax(bytes);if(type=='g'){if(values.containsKey("path")||values.containsKey("linkpath")||values.containsKey("size"))throw new IOException("TAR_GLOBAL_PATH");}
                    else {if(!pax.isEmpty())throw new IOException("TAR_DUPLICATE_PAX");pax=values;}}
                continue;
            }
            if(longName!=null)name=longName;if(longLink!=null)target=longLink;
            if(pax.containsKey("path"))name=pax.get("path");if(pax.containsKey("linkpath"))target=pax.get("linkpath");
            if(pax.containsKey("size"))try{size=Long.parseLong(pax.get("size"));}catch(NumberFormatException e){throw new IOException("TAR_SIZE",e);}
            longName=null;longLink=null;pax=Collections.emptyMap();
            while(name.startsWith("./"))name=name.substring(2);if(name.endsWith("/"))name=name.substring(0,name.length()-1);
            if(name.isEmpty()||name.equals(".")){if(type!='5'||size!=0)throw new IOException("TAR_ROOT");continue;}
            BackupLimits.path(name);String key=BackupLimits.collisionKey("legacy",name);if(!names.add(key))throw new IOException("DUPLICATE_PATH");
            pathBytes=BackupLimits.add(pathBytes,key.length()*2L,8L*1024*1024);if(++count>BackupLimits.ENTRIES)throw new IOException("ENTRY_LIMIT");
            String kind=type==0||type=='0'||type=='7'?"FILE":type=='5'?"DIRECTORY":type=='2'?"LINK":type=='1'?"HARDLINK":null;
            if(kind==null||(!kind.equals("FILE")&&size!=0)||size<0)throw new IOException("TAR_SPECIAL_FILE");total=BackupLimits.add(total,size,BackupLimits.BYTES);
            if(target.length()>2048||target.indexOf('\0')>=0)throw new IOException("LINK_FORMAT");
            long mode=number(header,100,8);if(mode>07777)throw new IOException("TAR_MODE");
            Limited limited=new Limited(input,size,control);visitor.entry(new Member(name,kind,target,size,(int)mode),limited);
            while(limited.read(buffer)!=-1){}padding(input,size);control.report("LEGACY_VERIFYING",count,total);
        }
        }
        }
    }
    private static final class Limited extends InputStream {
        final InputStream input;final BackupControl control;long left;
        Limited(InputStream input,long size,BackupControl control){this.input=input;left=size;this.control=control;}
        public int read()throws IOException{byte[] b=new byte[1];int n=read(b);return n<0?-1:b[0]&255;}
        public int read(byte[] b,int off,int len)throws IOException{control.check();if(len==0)return 0;if(left==0)return -1;int n=input.read(b,off,(int)Math.min(left,len));if(n<0)throw new EOFException("TAR_TRUNCATED");left-=n;return n;}
        public void close(){ }
    }
    private static String text(byte[] bytes,int at,int size)throws IOException{int end=at;while(end<at+size&&bytes[end]!=0)end++;String value=new String(bytes,at,end-at,StandardCharsets.UTF_8);if(value.indexOf('\ufffd')>=0)throw new IOException("TAR_ENCODING");return value;}
    private static String terminated(byte[] value)throws IOException{String result=text(value,0,value.length);if(result.length()>2048)throw new IOException("TAR_PATH_LIMIT");return result;}
    private static boolean zero(byte[] bytes){for(byte value:bytes)if(value!=0)return false;return true;}
    private static void exact(InputStream input,byte[] bytes,int size)throws IOException{int at=0;while(at<size){int n=input.read(bytes,at,size-at);if(n<0)throw new EOFException("TAR_TRUNCATED");if(n>0)at+=n;}}
    private static void padding(InputStream input,long size)throws IOException{int pad=(int)((512-size%512)%512);exact(input,new byte[512],pad);}
    private static long number(byte[] data,int start,int length)throws IOException{
        long value=0;if((data[start]&0x80)!=0){if((data[start]&0x40)!=0)throw new IOException("TAR_NUMBER");value=data[start]&0x7f;
            for(int i=start+1;i<start+length;i++){if(value>(BackupLimits.BYTES+1024*1024)/256)throw new IOException("TAR_NUMBER");value=value*256+(data[i]&255);}return value;}
        String text=new String(data,start,length,StandardCharsets.US_ASCII).replace('\0',' ').trim();if(text.isEmpty())return 0;
        if(!text.matches("[0-7]+"))throw new IOException("TAR_NUMBER");try{return Long.parseLong(text,8);}catch(NumberFormatException e){throw new IOException("TAR_NUMBER",e);}
    }
    private static Map<String,String> pax(byte[] data)throws IOException{
        Map<String,String> out=new LinkedHashMap<>();int at=0;while(at<data.length){int space=at;while(space<data.length&&data[space]!=' ')space++;
            if(space-at>12||space==data.length)throw new IOException("PAX_FORMAT");int length;
            try{length=Integer.parseInt(new String(data,at,space-at,StandardCharsets.US_ASCII));}catch(NumberFormatException e){throw new IOException("PAX_FORMAT",e);}
            if(length<space-at+3||length>data.length-at||data[at+length-1]!='\n')throw new IOException("PAX_FORMAT");
            String record=new String(data,space+1,at+length-space-2,StandardCharsets.UTF_8);int equals=record.indexOf('=');if(equals<1)throw new IOException("PAX_FORMAT");
            String key=record.substring(0,equals),value=record.substring(equals+1);if(key.startsWith("GNU.sparse")||out.put(key,value)!=null)throw new IOException("PAX_UNSUPPORTED");at+=length;
        }return out;
    }
}
