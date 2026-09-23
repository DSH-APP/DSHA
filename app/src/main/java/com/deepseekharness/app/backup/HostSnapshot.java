package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** 逐文件暂存，最终重新读取源作有界变化检测；任何非源读取错误（例如磁盘满）均中止产物。 */
public final class HostSnapshot {
    private HostSnapshot() { }
    public interface FinalCheck { void verify(BackupControl control)throws IOException; }
    public static Map<String,Object> create(BackupFileSystem fs,List<BackupSource> roots,File ownedTask,File archive,
            Map<String,Object> provenance,boolean rescue,BackupControl control)throws IOException {
        return create(fs,roots,ownedTask,archive,provenance,rescue,control,null);
    }
    public static Map<String,Object> create(BackupFileSystem fs,List<BackupSource> roots,File ownedTask,File archive,
            Map<String,Object> provenance,boolean rescue,BackupControl control,FinalCheck additional)throws IOException {
        if(roots.isEmpty())throw new IOException("NO_SELECTED_DATA");if(roots.size()>512)throw new IOException("ROOTS_LIMIT");
        Map<String,BackupSource> sources=new LinkedHashMap<>();for(BackupSource source:roots){BackupLimits.root(source.id());if(sources.put(source.id(),source)!=null)throw new IOException("DUPLICATE_ROOT");}
        if(!archive.getAbsoluteFile().getParentFile().equals(ownedTask.getAbsoluteFile()))throw new IOException("UNSAFE_ARTIFACT");
        for(BackupSource source:roots)if(source.containsLocal(ownedTask)||source.containsLocal(archive))throw new IOException("BACKUP_DESTINATION_IN_SOURCE");
        File index=fs.child(ownedTask,"source-checks"),capture=fs.child(ownedTask,"capture");boolean[] partial={false};
        long[] readBytes={0};int[] files={0};
        try(OutputStream output=fs.create(archive)){
            BackupArchive.Writer writer=new BackupArchive.Writer(output,control);
            try(DataOutputStream checks=new DataOutputStream(fs.create(index))) {
            for(BackupSource root:roots) {
                root.walk(item->{
                    control.check();if(++files[0]>BackupLimits.ENTRIES)throw new IOException("ENTRY_LIMIT");
                    if(item.kind.equals("MISSING")||item.kind.equals("UNREADABLE")){
                        if(!rescue)throw new IOException(item.reason.isEmpty()?item.kind+"_SOURCE":item.reason);partial[0]=true;writer.add(record(root,item,0,""),null);return;
                    }
                    String sha="";long size=0;
                    if(item.kind.equals("FILE")){
                        IOException sourceError=null;InputStream input=null;
                        try(OutputStream captured=fs.create(capture)) {
                            try{input=root.open(item);}catch(IOException error){sourceError=error;}
                            if(input!=null){byte[] buffer=new byte[65536];while(sourceError==null){int n;
                                try{control.check();n=input.read(buffer);}catch(InterruptedIOException e){throw e;}catch(IOException e){sourceError=e;break;}
                                if(n<0)break;size=BackupLimits.add(size,n,BackupLimits.BYTES);readBytes[0]=BackupLimits.add(readBytes[0],n,BackupLimits.BYTES);
                                captured.write(buffer,0,n);control.report("CAPTURING",files[0],readBytes[0]);}
                                try{input.close();}catch(IOException e){sourceError=e;}input=null;}
                        } finally {if(input!=null)input.close();}
                        if(sourceError!=null){fs.delete(capture);if(!rescue)throw sourceError;partial[0]=true;
                            writer.add(record(root,new BackupSource.Item(item.path,"UNREADABLE",0,item.token,"","SOURCE_READ_FAILED"),0,""),null);return;}
                        try(InputStream captured=fs.read(capture,fs.stat(capture))){sha=BackupArchive.digest(captured,control);}
                        try(InputStream captured=fs.read(capture,fs.stat(capture))){writer.add(record(root,item,size,sha),captured);}finally{fs.delete(capture);}
                    } else writer.add(record(root,item,0,""),null);
                    Map<String,Object> check=new LinkedHashMap<>();check.put("root",root.id());check.put("path",item.path);check.put("kind",item.kind);
                    check.put("token",item.token);check.put("target",item.target);check.put("sha256",sha);check.put("size",item.size);
                    byte[] line=BackupJson.write(check,BackupLimits.RECORD);checks.writeInt(line.length);checks.write(line);
                },control);
            }
            }
            Map<String,Long> changed=new LinkedHashMap<>();
            try(DataInputStream input=new DataInputStream(fs.read(index,fs.stat(index)))){
                int first;while((first=input.read())!=-1){int length=(first<<24)|(input.readUnsignedByte()<<16)|(input.readUnsignedByte()<<8)|input.readUnsignedByte();if(length<2||length>BackupLimits.RECORD)throw new IOException("CHECK_INDEX");byte[] row=new byte[length];input.readFully(row);
                    Map<String,Object> values=BackupJson.read(row,BackupLimits.RECORD);BackupSource root=sources.get(BackupJson.string(values,"root"));if(root==null)throw new IOException("CHECK_INDEX");
                    BackupSource.Item item=new BackupSource.Item(BackupJson.string(values,"path"),BackupJson.string(values,"kind"),BackupJson.number(values,"size"),BackupJson.string(values,"token"),BackupJson.string(values,"target"),"");
                    try{root.verify(item,BackupJson.string(values,"sha256"),control);}catch(IOException error){if(!rescue)throw error;partial[0]=true;changed.put(root.id(),changed.getOrDefault(root.id(),0L)+1);}
                }
            }
            if(additional!=null)try{additional.verify(control);}catch(IOException error){if(!rescue)throw error;partial[0]=true;changed.put("additional-source-verification",1L);}
            Map<String,Object> manifest=new LinkedHashMap<>(provenance);List<Map<String,Object>> descriptions=new ArrayList<>();for(BackupSource root:roots)descriptions.add(root.description());
            manifest.put("roots",descriptions);manifest.put("changedSources",changed);manifest.put("integrity",partial[0]?"PARTIAL":rescue?"BEST_EFFORT":roots.stream().anyMatch(BackupSource::external)?"EXTERNAL_CHECKED":"QUIESCENT");
            return writer.finish(manifest);
        }finally{if(!fs.stat(capture).type.equals("MISSING"))fs.delete(capture);}
    }
    private static BackupArchive.Record record(BackupSource source,BackupSource.Item item,long size,String sha)throws IOException{
        Map<String,Object> values=new LinkedHashMap<>();values.put("root",source.id());values.put("path",item.path);values.put("kind",item.kind);
        values.put("scope",source.category(item));values.put("size",size);values.put("sha256",sha);
        values.put("mode",item.mode);
        if(item.kind.equals("LINK"))values.put("target",item.target);if(!item.reason.isEmpty())values.put("reason",item.reason);
        return new BackupArchive.Record(values);
    }
}
