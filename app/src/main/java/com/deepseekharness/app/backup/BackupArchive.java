package com.deepseekharness.app.backup;

import java.io.*;
import java.security.*;
import java.util.*;

/** v5 宿主归档：有界 JSON 记录 + 长度限定载荷 + 完整文件尾摘要。无运行时依赖、无任意路径解压。 */
public final class BackupArchive {
    private static final byte[] MAGIC={'D','S','H','A','D','A','T','A'};
    private static final Set<String> SCOPES=new HashSet<>(Arrays.asList("application","sessions","settings","plugins","projects"));
    private static final Set<String> KINDS=new HashSet<>(Arrays.asList("FILE","DIRECTORY","LINK","MISSING","UNREADABLE","EXCLUDED"));
    private BackupArchive() { }
    public static MessageDigest sha() {try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    public static String hex(byte[] bytes){StringBuilder out=new StringBuilder(bytes.length*2);for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    public static String digest(InputStream input,BackupControl control)throws IOException {
        MessageDigest digest=sha();byte[] buffer=new byte[65536];long total=0;int n;
        while((n=input.read(buffer))!=-1){control.check();total=BackupLimits.add(total,n,BackupLimits.BYTES+256L*1024*1024);digest.update(buffer,0,n);}return hex(digest.digest());
    }
    public static final class Record {
        public final Map<String,Object> metadata;
        public final String root,path,kind,scope,sha256;
        public final long size;
        public Record(Map<String,Object> values)throws IOException{
            metadata=Collections.unmodifiableMap(new LinkedHashMap<>(values));
            root=BackupLimits.root(BackupJson.string(values,"root"));path=BackupLimits.path(BackupJson.string(values,"path"));
            kind=BackupJson.string(values,"kind");scope=BackupJson.string(values,"scope");size=BackupJson.number(values,"size");
            sha256=BackupJson.string(values,"sha256");
            if(values.containsKey("mode")&&(BackupJson.number(values,"mode")<0||BackupJson.number(values,"mode")>0777))throw new IOException("RECORD_MODE");
            if(!KINDS.contains(kind)||!SCOPES.contains(scope)||size<0||size>BackupLimits.BYTES)throw new IOException("RECORD_FORMAT");
            if(kind.equals("FILE")){if(!sha256.matches("[a-f0-9]{64}"))throw new IOException("RECORD_CHECKSUM");}
            else if(size!=0||!sha256.isEmpty())throw new IOException("RECORD_FORMAT");
            if(kind.equals("LINK")){
                String target=BackupJson.string(values,"target");
                if(target.isEmpty()||target.length()>2048||target.indexOf('\0')>=0||target.indexOf('\\')>=0)throw new IOException("LINK_FORMAT");
            }
        }
        public static Record of(String root,String path,String kind,String scope,long size,String hash)throws IOException {
            Map<String,Object> fields=new LinkedHashMap<>();fields.put("root",root);fields.put("path",path);fields.put("kind",kind);
            fields.put("scope",scope);fields.put("size",size);fields.put("sha256",hash);return new Record(fields);
        }
    }
    /** Writer 不关闭调用方文件描述符；调用方负责 fsync 和私有目录的提交。 */
    public static final class Writer {
        private final MessageDigest hash=sha();
        private final DigestOutputStream hashed;
        private final DataOutputStream output;
        private final BackupControl control;
        private final Index index=new Index();
        private boolean finished;
        public Writer(OutputStream stream,BackupControl control)throws IOException{
            this.control=control;hashed=new DigestOutputStream(stream,hash);output=new DataOutputStream(hashed);
            output.write(MAGIC);output.writeInt(5);
        }
        public void add(Record record,InputStream captured)throws IOException{
            if(finished)throw new IOException("ARCHIVE_FINISHED");control.check();
            byte[] metadata=BackupJson.write(record.metadata,BackupLimits.RECORD);index.add(record,metadata.length);
            output.writeInt(metadata.length);output.write(metadata);output.writeLong(record.size);
            MessageDigest fileHash=sha();byte[] buffer=new byte[65536];long remaining=record.size;
            while(remaining>0){control.check();int n=captured==null?-1:captured.read(buffer,0,(int)Math.min(buffer.length,remaining));
                if(n<0)throw new EOFException("SOURCE_TRUNCATED");if(n==0)continue;
                output.write(buffer,0,n);fileHash.update(buffer,0,n);remaining-=n;
                control.report("ARCHIVING",index.count,index.bytes-remaining);
            }
            if(record.kind.equals("FILE")){
                if(captured==null||captured.read()!=-1)throw new IOException("SOURCE_GREW");
                if(!record.sha256.equals(hex(fileHash.digest())))throw new IOException("SOURCE_CHANGED");
            }
        }
        public Map<String,Object> finish(Map<String,Object> information)throws IOException{
            if(finished)throw new IOException("ARCHIVE_FINISHED");finished=true;
            Map<String,Object> manifest=new LinkedHashMap<>(information);
            manifest.put("formatVersion",5L);manifest.put("entries",(long)index.count);manifest.put("bytes",index.bytes);
            validateSummary(manifest,index);
            byte[] data=BackupJson.write(manifest,BackupLimits.MANIFEST);output.writeInt(-1);output.writeInt(data.length);output.write(data);output.flush();
            byte[] sum=hash.digest();hashed.on(false);output.write(sum);output.flush();return manifest;
        }
    }
    public interface Visitor {
        /** 仅写隔离候选文件；返回 null 表示只校验此记录。流由读取器关闭。 */
        OutputStream payload(int ordinal,Record record)throws IOException;
        default void verified(int ordinal,Record record)throws IOException { }
    }
    public static Map<String,Object> read(InputStream raw,Visitor visitor,BackupControl control)throws IOException{
        MessageDigest hash=sha();DigestInputStream hashed=new DigestInputStream(raw,hash);DataInputStream input=new DataInputStream(hashed);
        byte[] magic=new byte[MAGIC.length];input.readFully(magic);
        if(!Arrays.equals(magic,MAGIC)||input.readInt()!=5)throw new IOException("ARCHIVE_VERSION");
        Index index=new Index();byte[] buffer=new byte[65536];
        while(true){control.check();int length=input.readInt();if(length==-1)break;
            if(length<2||length>BackupLimits.RECORD)throw new IOException("METADATA_LIMIT");
            byte[] bytes=new byte[length];input.readFully(bytes);Record record=new Record(BackupJson.read(bytes,BackupLimits.RECORD));index.add(record,length);
            long payload=input.readLong();if(payload!=record.size)throw new IOException("PAYLOAD_SIZE");
            OutputStream destination=visitor==null?null:visitor.payload(index.count-1,record);MessageDigest fileHash=sha();
            try {
                long remaining=payload;
                while(remaining>0){control.check();int n=input.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new EOFException("ARCHIVE_TRUNCATED");
                    if(n==0)continue;fileHash.update(buffer,0,n);if(destination!=null)destination.write(buffer,0,n);remaining-=n;
                    control.report("VERIFYING",index.count,index.bytes-remaining);}
            }finally{if(destination!=null)destination.close();}
            if(record.kind.equals("FILE")&&!record.sha256.equals(hex(fileHash.digest())))throw new IOException("PAYLOAD_CHECKSUM");
            if(visitor!=null)visitor.verified(index.count-1,record);
        }
        int length=input.readInt();if(length<2||length>BackupLimits.MANIFEST)throw new IOException("MANIFEST_LIMIT");
        byte[] summary=new byte[length];input.readFully(summary);Map<String,Object> manifest=BackupJson.read(summary,BackupLimits.MANIFEST);validateSummary(manifest,index);
        byte[] actual=hash.digest();hashed.on(false);byte[] expected=new byte[32];input.readFully(expected);
        if(!MessageDigest.isEqual(actual,expected)||input.read()!=-1)throw new IOException("ARCHIVE_FOOTER");
        return manifest;
    }
    private static void validateSummary(Map<String,Object> manifest,Index index)throws IOException{
        if(BackupJson.number(manifest,"formatVersion")!=5||BackupJson.number(manifest,"entries")!=index.count
                ||BackupJson.number(manifest,"bytes")!=index.bytes)throw new IOException("MANIFEST_PAYLOAD_MISMATCH");
        String operation=BackupJson.string(manifest,"operation"),integrity=BackupJson.string(manifest,"integrity");
        if(!Arrays.asList("EXPORT","MAINTENANCE","RESCUE").contains(operation)
                ||!Arrays.asList("QUIESCENT","EXTERNAL_CHECKED","PARTIAL","BEST_EFFORT").contains(integrity))throw new IOException("BACKUP_POLICY");
        if(index.incomplete&&!integrity.equals("PARTIAL")&&!integrity.equals("BEST_EFFORT"))throw new IOException("FALSE_COMPLETE");
        if(!manifest.containsKey("createdAt")||!manifest.containsKey("appVersion")||!manifest.containsKey("runtime")
                ||!manifest.containsKey("dataFormat")||!manifest.containsKey("sensitivePolicy")||!manifest.containsKey("plugins"))throw new IOException("MANIFEST_PROVENANCE");
        Object declared=manifest.get("roots");if(!(declared instanceof List)||((List<?>)declared).size()>512)throw new IOException("ROOTS_LIMIT");
        Set<String> roots=new HashSet<>();for(Object row:(List<?>)declared){if(!(row instanceof Map))throw new IOException("ROOTS_FORMAT");
            @SuppressWarnings("unchecked") Map<String,Object> root=(Map<String,Object>)row;
            String id=BackupLimits.root(BackupJson.string(root,"id"));if(!roots.add(id))throw new IOException("DUPLICATE_ROOT");
            if(!SCOPES.contains(BackupJson.string(root,"scope")))throw new IOException("UNKNOWN_SCOPE");
            String scope=BackupJson.string(root,"scope");
            if(!scope.equals("application")&&!Collections.singleton(scope).equals(index.scopes.get(id)))throw new IOException("ROOT_SCOPE_MISMATCH");
        }
        if(!roots.equals(index.roots))throw new IOException("ROOTS_PAYLOAD_MISMATCH");
    }
    private static final class Index {
        final NavigableSet<String> paths=new TreeSet<>();
        final Set<String> nonDirectories=new HashSet<>(),roots=new HashSet<>();
        final Map<String,Set<String>> scopes=new HashMap<>();
        int count,metadata;long bytes,keys;boolean incomplete;
        void add(Record record,int length)throws IOException{
            if(++count>BackupLimits.ENTRIES)throw new IOException("ENTRY_LIMIT");
            metadata=(int)BackupLimits.add(metadata,length,BackupLimits.METADATA);bytes=BackupLimits.add(bytes,record.size,BackupLimits.BYTES);
            String key=BackupLimits.collisionKey(record.root,record.path);keys=BackupLimits.add(keys,key.length()*2L,8L*1024*1024);
            if(!paths.add(key))throw new IOException("DUPLICATE_PATH");roots.add(record.root);
            scopes.computeIfAbsent(record.root,ignored->new HashSet<>()).add(record.scope);
            String parent=key;while(parent.contains("/")){parent=parent.substring(0,parent.lastIndexOf('/'));if(nonDirectories.contains(parent))throw new IOException("PATH_TYPE_CONFLICT");}
            if(!record.kind.equals("DIRECTORY")){
                // 记录必须父目录先于子项；空根目录记录同样参与检查。
                String prefix=key+"/",next=paths.ceiling(prefix);if(next!=null&&next.startsWith(prefix))throw new IOException("PATH_TYPE_CONFLICT");
                nonDirectories.add(key);
            }
            incomplete|=record.kind.equals("MISSING")||record.kind.equals("UNREADABLE");
        }
    }
}
