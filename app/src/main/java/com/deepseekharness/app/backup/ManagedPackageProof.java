package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 与构建生成证明使用同一编码；文件字节、可执行位或链接被改动就不再视作可重建原件。 */
public final class ManagedPackageProof {
    private ManagedPackageProof() { }
    public static String digest(BackupFileSystem fs,File root,BackupControl control)throws IOException{
        TreeMap<String,String> rows=new TreeMap<>();long[] size={0,0};walk(fs,root,"",rows,size,control);
        java.security.MessageDigest hash=BackupArchive.sha();for(String row:rows.values())hash.update(row.getBytes(StandardCharsets.UTF_8));return BackupArchive.hex(hash.digest());
    }
    private static void walk(BackupFileSystem fs,File file,String path,Map<String,String> rows,long[] limits,BackupControl control)throws IOException{
        control.check();BackupLimits.path(path);BackupFileSystem.Node node=fs.stat(file);
        if(node.type.equals("DIRECTORY")){for(String name:fs.list(file))walk(fs,new File(file,name),path.isEmpty()?name:path+"/"+name,rows,limits,control);return;}
        if(rows.size()>=BackupLimits.ENTRIES)throw new IOException("PACKAGE_LIMIT");String line;
        if(node.type.equals("FILE")){limits[0]=BackupLimits.add(limits[0],node.size,BackupLimits.BYTES);String sum;try(InputStream input=fs.read(file,node)){sum=BackupArchive.digest(input,control);}line=path+"\0FILE\0"+node.mode+"\0"+sum+"\n";}
        else if(node.type.equals("LINK"))line=path+"\0LINK\0"+fs.readLink(file)+"\n";
        else throw new IOException("PACKAGE_TYPE");limits[1]=BackupLimits.add(limits[1],line.length()*2L,BackupLimits.METADATA);rows.put(path,line);
    }
}
