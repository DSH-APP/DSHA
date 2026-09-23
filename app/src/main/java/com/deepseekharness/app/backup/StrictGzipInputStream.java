package com.deepseekharness.app.backup;

import java.io.*;
import java.util.zip.*;

/** RFC 1952 单成员读取器；校验 CRC/ISIZE 并拒绝尾随垃圾，避免常见 GZIPInputStream 的尾部宽松接受。 */
public final class StrictGzipInputStream extends InputStream {
    private final PushbackInputStream raw;
    private final Inflater inflater;
    private final CRC32 crc=new CRC32();
    private final byte[] compressed=new byte[65536];
    private int supplied;
    private long size,compressedSize;
    private boolean finished,closed;
    public StrictGzipInputStream(InputStream source)throws IOException{
        raw=new PushbackInputStream(source,compressed.length);CRC32 header=new CRC32();
        if(headerByte(header)!=31||headerByte(header)!=139||headerByte(header)!=8)throw new IOException("GZIP_HEADER");
        int flags=headerByte(header);if((flags&0xe0)!=0)throw new IOException("GZIP_FLAGS");for(int i=0;i<6;i++)headerByte(header);
        if((flags&4)!=0){int length=headerByte(header)|(headerByte(header)<<8);for(int i=0;i<length;i++)headerByte(header);}
        if((flags&8)!=0)terminated(header);if((flags&16)!=0)terminated(header);
        if((flags&2)!=0){long expected=byteValue()|(byteValue()<<8);if(expected!=(header.getValue()&65535))throw new IOException("GZIP_HEADER_CRC");}
        inflater=new Inflater(true);
    }
    private int byteValue()throws IOException{int value=raw.read();if(value<0)throw new EOFException("GZIP_TRUNCATED");return value;}
    private int headerByte(CRC32 header)throws IOException{int value=byteValue();header.update(value);return value;}
    private void terminated(CRC32 header)throws IOException{for(int i=0;i<8192;i++)if(headerByte(header)==0)return;throw new IOException("GZIP_METADATA_LIMIT");}
    private long little()throws IOException{return (long)byteValue()|((long)byteValue()<<8)|((long)byteValue()<<16)|((long)byteValue()<<24);}
    @Override public int read()throws IOException{byte[] one=new byte[1];int n=read(one,0,1);return n<0?-1:one[0]&255;}
    @Override public int read(byte[] bytes,int at,int count)throws IOException{
        if(closed)throw new IOException("STREAM_CLOSED");if(count==0)return 0;if(finished)return -1;
        try {
            for(;;){int n=inflater.inflate(bytes,at,count);if(n>0){size=BackupLimits.add(size,n,BackupLimits.BYTES+256L*1024*1024);crc.update(bytes,at,n);return n;}
                if(inflater.finished()){
                    int remaining=inflater.getRemaining();if(remaining>0)raw.unread(compressed,supplied-remaining,remaining);
                    if(little()!=crc.getValue()||little()!=(size&0xffffffffL))throw new IOException("GZIP_TRAILER");
                    if(raw.read()!=-1)throw new IOException("GZIP_TRAILING_DATA");finished=true;return -1;
                }
                if(inflater.needsDictionary())throw new IOException("GZIP_DICTIONARY");
                if(inflater.needsInput()){supplied=raw.read(compressed);if(supplied<0)throw new EOFException("GZIP_TRUNCATED");
                    compressedSize=BackupLimits.add(compressedSize,supplied,BackupLimits.BYTES+256L*1024*1024);inflater.setInput(compressed,0,supplied);}
                else throw new IOException("GZIP_STALLED");
            }
        }catch(DataFormatException error){throw new IOException("GZIP_DEFLATE",error);}
    }
    @Override public void close()throws IOException{if(closed)return;closed=true;inflater.end();raw.close();}
}
