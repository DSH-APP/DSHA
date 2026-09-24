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
    @Test public void settingsOnlyRestoreHasInspectableExportableEntriesForEveryProfile()throws Exception{
        String id=UUID.randomUUID().toString();
        put("plugin-imports/"+id+"/settings/profiles/web/cordis.patch.yml","- id: llm\n  config: {model: saved}\n");
        put("plugin-imports/"+id+"/settings/profiles/team/package.json","{\"dsh\":{\"profile\":{}}}");
        var catalog=new RetainedCatalogue(fs,temp.getRoot(),new File(temp.getRoot(),"home"));
        var entries=catalog.list();assertEquals(2,entries.size());
        for(var entry:entries){assertEquals(RetainedCatalogue.Kind.SETTINGS,entry.kind);assertNotNull(entry.source);
            var sources=catalog.sources(entry);assertEquals(1,sources.size());assertEquals("settings",sources.get(0).scope());
            assertEquals("dsh-profile-config",sources.get(0).description().get("logicalKind"));
            assertTrue(com.deepseekharness.app.util.ProfileConfigPath.accepts((String)sources.get(0).description().get("name")));
        }
    }
    @Test public void migratedAndRestoredLegacyPresetsRemainAccessibleWithoutActivation()throws Exception{
        put("home/.dsha-rc1-migration/legacy-agent-presets/crew/0123456789abcdef/bundle/package.json","{\"name\":\"dsha-legacy-preset-crew\"}");
        String id=UUID.randomUUID().toString();put("plugin-imports/"+id+"/declarations/.agent-presets/old/agent.cordis.yml","- id: test\n");
        var catalog=new RetainedCatalogue(fs,temp.getRoot(),new File(temp.getRoot(),"home"));var entries=catalog.list().stream().filter(e->e.kind==RetainedCatalogue.Kind.PRESET).toList();assertEquals(2,entries.size());
        for(var entry:entries){assertEquals(RetainedCatalogue.Kind.PRESET,entry.kind);assertNotNull(catalog.resolve(entry.key()).source);assertEquals("projects",catalog.sources(entry).get(0).scope());}
        assertTrue(entries.stream().anyMatch(e->e.status.equals("CONVERSION_REQUIRED")));
        assertTrue(entries.stream().anyMatch(e->e.status.equals("QUARANTINED")));
    }
    @Test public void rc1MigrationRecordsAreVisibleAndExportable()throws Exception{
        put("rc1-migration-state/current.json","{\"version\":2,\"generation\":\"01234567-89ab-cdef-0123-456789abcdef\",\"status\":\"pending\"}");
        var catalog=new RetainedCatalogue(fs,temp.getRoot(),new File(temp.getRoot(),"home"));var entry=catalog.list().stream().filter(e->e.kind==RetainedCatalogue.Kind.MIGRATION).findFirst().orElseThrow();
        assertEquals("PENDING_RETRY",entry.status);assertNotNull(entry.source);assertEquals("settings",catalog.sources(entry).get(0).scope());
    }
}
