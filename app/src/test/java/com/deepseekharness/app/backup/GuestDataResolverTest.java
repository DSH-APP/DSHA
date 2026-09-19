package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class GuestDataResolverTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    static final class LinkFs implements BackupFileSystem {
        final JvmBackupFileSystem real=new JvmBackupFileSystem();final Map<String,String> links=new HashMap<>();
        public Node stat(File path)throws IOException{if(links.containsKey(path.getPath()))return new Node("LINK",path.getPath(),1,0,1,0777);return real.stat(path);}
        public String readLink(File file){return links.get(file.getPath());}
        public List<String> list(File file)throws IOException{return real.list(file);}public InputStream read(File f,Node n)throws IOException{return real.read(f,n);}
        public OutputStream create(File f)throws IOException{return real.create(f);}public void directory(File f)throws IOException{real.directory(f);}
        public void move(File a,File b)throws IOException{real.move(a,b);}public void delete(File f)throws IOException{real.delete(f);}public void syncDirectory(File f)throws IOException{real.syncDirectory(f);}
        public void mode(File f,int m)throws IOException{real.mode(f,m);}public void symlink(String target,File file){links.put(file.getPath(),target);}
    }
    @Test public void mapsGuestAndHistoricalL2sWithoutReadingForeignHost()throws Exception{
        File root=temporary.newFolder();Files.createDirectories(new File(root,"root").toPath());Files.createDirectories(new File(root,".l2s").toPath());Files.writeString(new File(root,".l2s/owned").toPath(),"user bytes");
        LinkFs fs=new LinkFs();File source=new File(root,"root/a");fs.links.put(source.getPath(),"/data/user/10/com.dsh.client/files/linux/ubuntu/.l2s/owned");
        var resolver=new GuestDataResolver(fs,root,null,List.of());assertEquals(new File(root,".l2s/owned"),resolver.resolve(source).file);
        fs.links.put(source.getPath(),"/.l2s/owned");assertEquals(new File(root,".l2s/owned"),resolver.resolve(source).file);
    }
    @Test public void onlyApprovedPublicBindingsAndNoCycles()throws Exception{
        File root=temporary.newFolder(),publicRoot=temporary.newFolder();Files.createDirectories(new File(root,"root").toPath());File allowed=new File(publicRoot,"Documents/dshdata");allowed.mkdirs();
        LinkFs fs=new LinkFs();File source=new File(root,"root/a");fs.links.put(source.getPath(),"/sdcard/Documents/dshdata");
        var resolver=new GuestDataResolver(fs,root,publicRoot,List.of(allowed));assertEquals(allowed,resolver.resolve(source).file);
        fs.links.put(source.getPath(),"/sdcard/other");assertThrows(IOException.class,()->resolver.resolve(source));
        fs.links.put(source.getPath(),"a");assertThrows(IOException.class,()->resolver.resolve(source));
        fs.links.put(source.getPath(),"/proc/self/fd/5");assertThrows(IOException.class,()->resolver.resolve(source));
    }
}
