package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 事务候选及被覆盖原件的摘要；链接只记录本体，不沿链接遍历。 */
public final class BackupTree {
    private BackupTree() { }
    public static String digest(BackupFileSystem fs,File root,BackupControl control)throws IOException{
        MessageDigest hash=BackupArchive.sha();long[] limits={0,0,0};visit(fs,root,"",hash,control,limits);return BackupArchive.hex(hash.digest());
    }
    /** 私有候选复制；不跟随链接，也不使用硬链接。复制前后独立核验源与候选字节。 */
    public static void copy(BackupFileSystem fs,File source,File target,BackupControl control)throws IOException{
        String from=source.getAbsolutePath(),to=target.getAbsolutePath();
        if(to.equals(from)||to.startsWith(from+File.separator)||from.startsWith(to+File.separator))throw new IOException("COPY_OVERLAP");
        String before=digest(fs,source,control);copyNode(fs,source,target,control,new long[]{0,0,0},0);
        if(!before.equals(digest(fs,source,control))||!before.equals(digest(fs,target,control)))throw new IOException("COPY_VERIFICATION");
    }
    private static void copyNode(BackupFileSystem fs,File source,File target,BackupControl control,long[] limits,int depth)throws IOException{
        control.check();if(depth>BackupLimits.DEPTH||++limits[0]>BackupLimits.ENTRIES)throw new IOException("COPY_LIMIT");
        if(!fs.stat(target).type.equals("MISSING"))throw new IOException("COPY_TARGET_EXISTS");BackupFileSystem.Node node=fs.stat(source);
        if(node.type.equals("DIRECTORY")){
            fs.directory(target);var children=fs.list(source);limits[2]=BackupLimits.add(limits[2],children.size(),BackupLimits.ENTRIES);
            for(String name:children){BackupLimits.path(name);if(name.contains("/"))throw new IOException("INVALID_CHILD");copyNode(fs,new File(source,name),new File(target,name),control,limits,depth+1);}
            fs.mode(target,node.mode);
        }else if(node.type.equals("FILE")){
            try(InputStream in=fs.read(source,node);OutputStream out=fs.create(target)){
                byte[] bytes=new byte[65536];int count;while((count=in.read(bytes))!=-1){control.check();limits[1]=BackupLimits.add(limits[1],count,BackupLimits.BYTES);out.write(bytes,0,count);control.report("COPYING_CANDIDATE",limits[0],limits[1]);}
            }fs.mode(target,node.mode);
        }else if(node.type.equals("LINK"))fs.symlink(fs.readLink(source),target);
        else if(!node.type.equals("MISSING"))throw new IOException("SPECIAL_FILE");
        if(!node.same(fs.stat(source)))throw new IOException("SOURCE_CHANGED");
    }
    private static void field(MessageDigest hash,String value){byte[] b=value.getBytes(StandardCharsets.UTF_8);hash.update((byte)(b.length>>24));hash.update((byte)(b.length>>16));hash.update((byte)(b.length>>8));hash.update((byte)b.length);hash.update(b);}
    private static void visit(BackupFileSystem fs,File file,String relative,MessageDigest hash,BackupControl control,long[] limits)throws IOException{
        control.check();BackupLimits.path(relative);if(++limits[0]>BackupLimits.ENTRIES)throw new IOException("ENTRY_LIMIT");
        BackupFileSystem.Node before=fs.stat(file);field(hash,relative);field(hash,before.type);
        if(before.type.equals("FILE")||before.type.equals("DIRECTORY"))field(hash,Integer.toOctalString(before.mode));
        if(before.type.equals("FILE")){limits[1]=BackupLimits.add(limits[1],before.size,BackupLimits.BYTES);try(InputStream in=fs.read(file,before)){field(hash,BackupArchive.digest(in,control));}}
        else if(before.type.equals("DIRECTORY")){var children=fs.list(file);limits[2]=BackupLimits.add(limits[2],children.size(),BackupLimits.ENTRIES);
            for(String name:children){BackupLimits.path(name);if(name.contains("/"))throw new IOException("INVALID_CHILD");visit(fs,new File(file,name),relative.isEmpty()?name:relative+"/"+name,hash,control,limits);}}
        else if(before.type.equals("LINK"))field(hash,fs.readLink(file));
        else if(!before.type.equals("MISSING"))throw new IOException("SPECIAL_FILE");
        if(!before.same(fs.stat(file)))throw new IOException("SOURCE_CHANGED");
    }
}
