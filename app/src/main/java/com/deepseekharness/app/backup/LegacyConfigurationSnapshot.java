package com.deepseekharness.app.backup;

import com.google.gson.Strictness;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 旧六文件检查点的只读适配；逐个解码，不把整个 base64 检查点装入对象树。 */
final class LegacyConfigurationSnapshot {
    interface Data { void file(String name,byte[] bytes)throws IOException; }
    static final int FILE_LIMIT=4*1024*1024, DOCUMENT_LIMIT=36*1024*1024;
    private LegacyConfigurationSnapshot(){}
    static Map<String,Object> read(InputStream source,Data output,BackupControl control)throws IOException{
        InputStream bounded=new FilterInputStream(source){long bytes;
            public int read()throws IOException{int next=super.read();if(next>=0)bytes=BackupLimits.add(bytes,1,DOCUMENT_LIMIT);return next;}
            public int read(byte[] b,int offset,int size)throws IOException{int n=in.read(b,offset,size);if(n>0)bytes=BackupLimits.add(bytes,n,DOCUMENT_LIMIT);return n;}};
        // Gson 的单个字符串也有上限，异常输入不能先分配整份 36 MiB 文档再被拒绝。
        Reader text=new FilterReader(new InputStreamReader(bounded,StandardCharsets.UTF_8)){
            boolean string,escaped;int length;
            private void check(char c)throws IOException{if(string){if(++length>6*1024*1024)throw new IOException("RECOVERY_SIZE");if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='\"')string=false;}
                else if(c=='\"'){string=true;length=0;}}
            public int read(char[] chars,int offset,int count)throws IOException{int n=in.read(chars,offset,count);control.check();for(int i=0;i<n;i++)check(chars[offset+i]);return n;}
            public int read()throws IOException{char[] c=new char[1];return read(c,0,1)<0?-1:c[0];}
        };
        Map<String,Object> result=new LinkedHashMap<>(),files=new LinkedHashMap<>();Set<String> keys=new HashSet<>();
        try(JsonReader reader=new JsonReader(text)){
            reader.setStrictness(Strictness.STRICT);reader.beginObject();
            while(reader.hasNext()){
                String key=reader.nextName();if(!keys.add(key)||!Set.of("version","id","created","dshVersion","startupId","files").contains(key))throw new IOException("RECOVERY_FORMAT");
                if(!key.equals("files")){Object value=BackupJson.readValue(reader);result.put(key,value);continue;}
                reader.beginObject();while(reader.hasNext()){
                    control.check();String name=reader.nextName();if(!ConfigurationSnapshots.FILES.contains(name)||files.containsKey(name))throw new IOException("RECOVERY_FORMAT");
                    byte[] data=null;
                    if(reader.peek()==JsonToken.NULL)reader.nextNull();
                    else{
                        reader.beginObject();String hash=null,encoded=null;Set<String> fields=new HashSet<>();
                        while(reader.hasNext()){
                            String field=reader.nextName();if(!fields.add(field))throw new IOException("RECOVERY_FORMAT");
                            if(field.equals("sha256"))hash=reader.nextString();else if(field.equals("data"))encoded=reader.nextString();else throw new IOException("RECOVERY_FORMAT");
                        }reader.endObject();
                        if(hash==null||!hash.matches("[a-f0-9]{64}")||encoded==null||encoded.length()>((FILE_LIMIT+2)/3)*4)throw new IOException("RECOVERY_SIZE");
                        try{data=Base64.getDecoder().decode(encoded);}catch(IllegalArgumentException invalid){throw new IOException("RECOVERY_FORMAT");}
                        if(data.length>FILE_LIMIT||!hash.equals(BackupArchive.hex(BackupArchive.sha().digest(data))))throw new IOException("RECOVERY_CHECKSUM");
                    }
                    files.put(name,ConfigurationSnapshots.entry(data));if(output!=null)output.file(name,data);
                }reader.endObject();
            }reader.endObject();if(reader.peek()!=JsonToken.END_DOCUMENT)throw new IOException("RECOVERY_FORMAT");
        }catch(IllegalStateException|NumberFormatException invalid){throw new IOException("RECOVERY_FORMAT",invalid);}
        if(BackupJson.number(result,"version")!=1||!new HashSet<>(ConfigurationSnapshots.FILES).equals(files.keySet())
                ||!BackupJson.string(result,"id").matches("[a-f0-9]{32}")||BackupJson.number(result,"created")<0)throw new IOException("RECOVERY_FORMAT");
        if(result.containsKey("dshVersion")&&(!(result.get("dshVersion") instanceof String)||!((String)result.get("dshVersion")).matches("[A-Za-z0-9._+-]{1,80}")))throw new IOException("RECOVERY_FORMAT");
        if(result.containsKey("startupId")&&(!(result.get("startupId") instanceof String)||((String)result.get("startupId")).length()>128))throw new IOException("RECOVERY_FORMAT");
        result.put("files",files);return result;
    }
}
