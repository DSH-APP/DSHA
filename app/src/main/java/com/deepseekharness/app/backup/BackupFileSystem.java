package com.deepseekharness.app.backup;

import java.io.*;
import java.util.*;

/** 窄文件系统边界：生产实现使用 Android lstat/NOFOLLOW/fstat，JVM 使用实际临时文件验证。 */
public interface BackupFileSystem {
    final class Node {
        public final String type,key;
        public final long size,modified,device;
        public final int mode;
        public Node(String type,String key,long size,long modified,long device,int mode){this.type=type;this.key=key;this.size=size;this.modified=modified;this.device=device;this.mode=mode;}
        public boolean same(Node other){return other!=null&&type.equals(other.type)&&key.equals(other.key)&&size==other.size&&modified==other.modified&&mode==other.mode;}
    }
    Node stat(File file)throws IOException;
    String readLink(File file)throws IOException;
    List<String> list(File directory)throws IOException;
    InputStream read(File file,Node expected)throws IOException;
    OutputStream create(File file)throws IOException;
    void directory(File file)throws IOException;
    void move(File source,File target)throws IOException;
    void delete(File file)throws IOException;
    void syncDirectory(File directory)throws IOException;
    void mode(File file,int mode)throws IOException;
    void symlink(String target,File link)throws IOException;
    /** 仅用于已退出使用的事务私有树；实现可恢复本 UID 目录的遍历权限。 */
    default void prepareOwnedRemoval(File directory)throws IOException { }

    default File child(File root,String relative)throws IOException{
        BackupLimits.path(relative);File current=root.getAbsoluteFile();
        if(!stat(current).type.equals("DIRECTORY"))throw new IOException("UNSAFE_ROOT");
        if(relative.isEmpty())return current;
        String[] parts=relative.split("/");for(int i=0;i<parts.length;i++){
            if(!stat(current).type.equals("DIRECTORY"))throw new IOException("PARENT_LINK_OR_MISSING");
            current=new File(current,parts[i]);
        }return current;
    }
    default void parents(File root,String relative)throws IOException{
        BackupLimits.path(relative);String[] parts=relative.split("/");File at=root;
        if(!stat(at).type.equals("DIRECTORY"))throw new IOException("UNSAFE_ROOT");
        for(int i=0;i<parts.length-1;i++){File next=new File(at,parts[i]);Node node=stat(next);
            if(node.type.equals("MISSING"))directory(next);else if(!node.type.equals("DIRECTORY"))throw new IOException("PARENT_LINK");at=next;}
    }
    default void removeOwned(File root,String relative)throws IOException{
        if(relative.isEmpty())throw new IOException("REFUSE_ROOT_DELETE");
        File target=child(root,relative);Node node=stat(target);
        if(node.type.equals("MISSING"))return;
        if(node.type.equals("DIRECTORY")){prepareOwnedRemoval(target);for(String name:list(target))removeOwned(root,relative.isEmpty()?name:relative+"/"+name);}
        if(relative.isEmpty())throw new IOException("REFUSE_ROOT_DELETE");delete(target);
    }
    default byte[] small(File file,int limit)throws IOException{
        Node before=stat(file);if(!before.type.equals("FILE")||before.size>limit)throw new IOException("METADATA_LIMIT");
        try(InputStream in=read(file,before);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>limit)throw new IOException("METADATA_LIMIT");out.write(buffer,0,n);}return out.toByteArray();}
    }
    default void atomic(File directory,String name,byte[] bytes)throws IOException{
        BackupLimits.path(name);if(name.contains("/"))throw new IOException("RECORD_NAME");
        File target=child(directory,name),part=child(directory,name+".tmp-"+UUID.randomUUID());
        try(OutputStream out=create(part)){out.write(bytes);}
        // move 不覆盖；已有记录改名保留到提交完成，恢复方按固定名称核对。
        Node original=stat(target);File previous=child(directory,name+".previous");
        if(!stat(previous).type.equals("MISSING"))throw new IOException("UNFINISHED_RECORD_WRITE");
        if(!original.type.equals("MISSING"))move(target,previous);
        move(part,target);syncDirectory(directory);
        if(!original.type.equals("MISSING"))delete(previous);syncDirectory(directory);
    }
}
