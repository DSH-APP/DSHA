package com.deepseekharness.app.backup;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 测试使用真实文件读写、同步、移动和链接，不把事务操作全部 mock。 */
public final class JvmBackupFileSystem implements BackupFileSystem {
    @Override public Node stat(File file)throws IOException{
        try{BasicFileAttributes a=Files.readAttributes(file.toPath(),BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
            return new Node(a.isSymbolicLink()?"LINK":a.isDirectory()?"DIRECTORY":a.isRegularFile()?"FILE":"SPECIAL",String.valueOf(a.fileKey()),a.size(),a.lastModifiedTime().toMillis(),1,0700);
        }catch(NoSuchFileException e){return new Node("MISSING","",0,0,0,0);}
    }
    public String readLink(File file)throws IOException{return Files.readSymbolicLink(file.toPath()).toString();}
    public List<String> list(File file)throws IOException{List<String> names=new ArrayList<>();try(var stream=Files.newDirectoryStream(file.toPath())){for(Path p:stream)names.add(p.getFileName().toString());}Collections.sort(names);return names;}
    public InputStream read(File file,Node expected)throws IOException{
        if(!expected.type.equals("FILE")||!expected.same(stat(file)))throw new IOException("SOURCE_CHANGED");
        InputStream in=Files.newInputStream(file.toPath(),LinkOption.NOFOLLOW_LINKS);
        return new FilterInputStream(in){boolean closed;public void close()throws IOException{if(closed)return;closed=true;try{super.close();}finally{if(!expected.same(stat(file)))throw new IOException("SOURCE_CHANGED");}}};
    }
    public OutputStream create(File file)throws IOException{
        var channel=FileChannel.open(file.toPath(),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);OutputStream out=java.nio.channels.Channels.newOutputStream(channel);
        return new FilterOutputStream(out){boolean closed;public void write(byte[] bytes,int off,int len)throws IOException{out.write(bytes,off,len);}public void close()throws IOException{if(closed)return;closed=true;try{flush();channel.force(true);}finally{super.close();}}};
    }
    public void directory(File file)throws IOException{Files.createDirectory(file.toPath());syncDirectory(file.getParentFile());}
    public void move(File source,File target)throws IOException{if(Files.exists(target.toPath(),LinkOption.NOFOLLOW_LINKS))throw new FileAlreadyExistsException(target.toString());Files.move(source.toPath(),target.toPath());}
    public void delete(File file)throws IOException{Files.delete(file.toPath());}
    public void mode(File file,int mode)throws IOException{if(System.getProperty("os.name").startsWith("Windows"))return;Set<PosixFilePermission> permissions=EnumSet.noneOf(PosixFilePermission.class);int[] masks={0400,0200,0100,0040,0020,0010,0004,0002,0001};PosixFilePermission[] names=PosixFilePermission.values();for(int i=0;i<masks.length;i++)if((mode&masks[i])!=0)permissions.add(names[i]);Files.setPosixFilePermissions(file.toPath(),permissions);}
    public void symlink(String target,File link)throws IOException{Files.createSymbolicLink(link.toPath(),Path.of(target));}
    public void syncDirectory(File file)throws IOException{if(System.getProperty("os.name").startsWith("Windows"))return;try(var channel=FileChannel.open(file.toPath(),StandardOpenOption.READ)){channel.force(true);}}
}
