package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

/** 链接元数据模拟历史 Android 布局，归档和实际载荷使用真实私有临时文件。 */
public class L2sSnapshotTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void nestedHistoricalAttachmentLinkContainsRealBytes()throws Exception{
        File root=temp.newFolder(),data=new File(root,"root/.dsh/attachments"),payload=new File(root,".l2s/image");Files.createDirectories(data.toPath());Files.createDirectories(payload.getParentFile().toPath());
        byte[] expected={0,1,2,3,(byte)255};Files.write(payload.toPath(),expected);File link=new File(data,"image.png");Files.writeString(link.toPath(),"placeholder");
        var fs=new GuestDataResolverTest.LinkFs();fs.links.put(link.getPath(),"/data/user/10/com.dsh.client/files/linux/ubuntu/.l2s/image");
        var resolver=new GuestDataResolver(fs,root,null,List.of());var source=new FileBackupSource(fs,"attachments","sessions",data,false,null,resolver);
        File task=temp.newFolder(),archive=new File(task,"snapshot");HostSnapshot.create(fs,List.of(source),task,archive,BackupArchiveTest.summary(),false,new BackupControl(null));
        ByteArrayOutputStream restored=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(archive)){
            BackupArchive.read(in,new BackupArchive.Visitor(){public OutputStream payload(int ordinal,BackupArchive.Record record){
                if(!record.path.equals("image.png"))return null;assertEquals("FILE",record.kind);return restored;
            }},new BackupControl(null));
        }assertArrayEquals(expected,restored.toByteArray());
    }
    @Test public void cyclicOrMissingL2sPayloadCannotClaimCompleteSnapshot()throws Exception{
        File root=temp.newFolder(),data=new File(root,"root/project"),storage=new File(root,".l2s");Files.createDirectories(data.toPath());Files.createDirectories(storage.toPath());
        File link=new File(data,"source");Files.writeString(link.toPath(),"placeholder");var fs=new GuestDataResolverTest.LinkFs();fs.links.put(link.getPath(),"/.l2s/missing");
        var resolver=new GuestDataResolver(fs,root,null,List.of());var source=new FileBackupSource(fs,"project","projects",data,false,null,resolver);File task=temp.newFolder();
        assertThrows(IOException.class,()->HostSnapshot.create(fs,List.of(source),task,new File(task,"missing"),BackupArchiveTest.summary(),false,new BackupControl(null)));
        fs.links.put(new File(storage,"missing").getPath(),"/.l2s/missing");File second=temp.newFolder();
        assertThrows(IOException.class,()->HostSnapshot.create(fs,List.of(source),second,new File(second,"cycle"),BackupArchiveTest.summary(),false,new BackupControl(null)));
    }
    @Test public void ordinaryProjectLinksStayMetadataAndDoNotReadOutsideRoot()throws Exception{
        File root=temp.newFolder(),data=new File(root,"root/project");Files.createDirectories(data.toPath());File link=new File(data,"outside");Files.writeString(link.toPath(),"placeholder");
        var fs=new GuestDataResolverTest.LinkFs();fs.links.put(link.getPath(),"/proc/self/fd/9");var source=new FileBackupSource(fs,"project","projects",data,false,null,new GuestDataResolver(fs,root,null,List.of()));
        List<BackupSource.Item> records=new ArrayList<>();source.walk(records::add,new BackupControl(null));var record=records.stream().filter(item->item.path.equals("outside")).findFirst().orElseThrow();
        assertEquals("LINK",record.kind);assertEquals("/proc/self/fd/9",record.target);
    }
}
