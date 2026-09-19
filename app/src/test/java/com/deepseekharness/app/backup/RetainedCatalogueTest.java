package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class RetainedCatalogueTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();private final BackupFileSystem fs=new JvmBackupFileSystem();
    private File put(String path,String text)throws Exception{File file=new File(temp.getRoot(),path);Files.createDirectories(file.getParentFile().toPath());Files.writeString(file.toPath(),text);return file;}
    @Test public void oldTreeCanBeReadWithoutBashAndDoesNotReadCurrentData()throws Exception{
        String id=UUID.randomUUID().toString(),prefix=EnvironmentRebuildTransaction.HOME+"/"+id;
        File original=put(prefix+"/previous-linux/ubuntu/root/.dsh/sessions/message","old conversation");
        put("linux/ubuntu/root/.dsh/sessions/message","new conversation");
        put(prefix+"/committed",id+"\ncommitted\n");var catalogue=new RetainedCatalogue(fs,temp.getRoot(),new File(temp.getRoot(),"linux/ubuntu/root/.dsh"));
        var entry=catalogue.resolve("ENVIRONMENT:"+id+":previous-linux-data");var roots=catalogue.sources(entry);assertEquals(1,roots.size());
        List<String> contents=new ArrayList<>();roots.get(0).walk(item->{if(item.kind.equals("FILE"))try(InputStream input=roots.get(0).open(item)){contents.add(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));}},new BackupControl(null));
        assertEquals(List.of("old conversation"),contents);assertEquals("old conversation",Files.readString(original.toPath()));
        assertEquals("new conversation",Files.readString(new File(temp.getRoot(),"linux/ubuntu/root/.dsh/sessions/message").toPath()));
    }
    @Test public void unknownRecordDoesNotHideReadableOldTree()throws Exception{
        String id=UUID.randomUUID().toString();put("host-backup-operations/unknown-user-folder/notes","only original");
        put(EnvironmentRebuildTransaction.HOME+"/"+id+"/previous-linux/ubuntu/root/.dsh/settings.yaml","owned: true");
        var catalog=new RetainedCatalogue(fs,temp.getRoot(),new File(temp.getRoot(),"home"));
        assertTrue(catalog.list().stream().anyMatch(entry->entry.status.equals("UNRECOGNIZED")));
        assertEquals("application",catalog.resolve("ENVIRONMENT:"+id+":previous-linux-data").scope);
        assertThrows(IOException.class,()->catalog.resolve("ENVIRONMENT:../../outside:previous-linux-data"));
        assertEquals("only original",Files.readString(new File(temp.getRoot(),"host-backup-operations/unknown-user-folder/notes").toPath()));
    }
    @Test public void corruptedMarkerRemainsUnknownAndCannotAuthorizeRestore()throws Exception{
        String id=UUID.randomUUID().toString();String base=EnvironmentRebuildTransaction.HOME+"/"+id;
        put(base+"/previous-linux/ubuntu/root/.dsh/sessions/message","retained");put(base+"/committed","wrong owner");
        var catalog=new RetainedCatalogue(fs,temp.getRoot(),new File(temp.getRoot(),"home"));
        assertEquals("UNREADABLE",catalog.list().get(0).status);assertThrows(IOException.class,()->catalog.resolve("ENVIRONMENT:"+id+":previous-linux-data"));
    }
    @Test public void selectedPersonalOriginalRestoresAsASeparateProjectRoot()throws Exception{
        String id=UUID.randomUUID().toString();put(ManagedRuntimeTransaction.HOME+"/"+id+"/previous/runtime-0/my-notes","unique modified dependency");
        var catalog=new RetainedCatalogue(fs,temp.getRoot(),new File(temp.getRoot(),"home"));var source=catalog.sources(catalog.resolve("RUNTIME:"+id+":previous")).get(0);
        assertEquals("projects",source.scope());assertEquals("project",source.description().get("logicalKind"));assertTrue(source.id().startsWith("project-"));
    }
}
