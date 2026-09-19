package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class NativePluginGraphTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private final JvmBackupFileSystem fs=new JvmBackupFileSystem();
    private void write(File file,String text)throws Exception{file.getParentFile().mkdirs();Files.writeString(file.toPath(),text);}
    @Test public void preservesLocalSourceTransitiveDependenciesAndCustomWrappers()throws Exception{
        File root=temporary.newFolder(),dsh=new File(root,"root/.dsh"),global=new File(root,"usr/local/lib/node_modules");
        write(new File(dsh,"profiles/web/package.json"),"{\"dependencies\":{\"user-plugin\":\"1.0.0\"}}");
        File plugin=new File(global,"user-plugin");write(new File(plugin,"package.json"),"{\"name\":\"user-plugin\",\"version\":\"1.0.0\",\"dependencies\":{\"helper\":\"1\"}}");
        write(new File(plugin,"index.js"),"user modified source");write(new File(plugin,"node_modules/helper/package.json"),"{\"name\":\"helper\",\"version\":\"1.0.0\"}");
        write(new File(plugin,"node_modules/helper/value"),"user dependency");write(new File(plugin,"node_modules/.bin/custom"),"user wrapper");
        var graph=new NativePluginGraph(fs,new GuestDataResolver(fs,root,null,List.of()),dsh,global,root,Map.of());graph.collect(new BackupControl(null));
        assertEquals(2,graph.sources.size());assertEquals(Boolean.TRUE,graph.description().get("complete"));graph.verify(new BackupControl(null));
        List<String> captured=new ArrayList<>();for(BackupSource source:graph.sources)source.walk(item->{if(item.kind.equals("FILE"))captured.add(item.path);},new BackupControl(null));
        assertTrue(captured.contains("index.js"));assertTrue(captured.contains("value"));assertTrue(captured.contains("node_modules/.bin/custom"));
        var profiles=(Map<?,?>)graph.description().get("profiles");assertNotNull(((Map<?,?>)profiles.get("web")).get("user-plugin"));
    }
    @Test public void onlyExactPackageProofCanExcludeBytes()throws Exception{
        File root=temporary.newFolder(),dsh=new File(root,"root/.dsh"),global=new File(root,"usr/local/lib/node_modules"),plugin=new File(global,"p");
        write(new File(dsh,"profiles/web/package.json"),"{\"dependencies\":{\"p\":\"1\"}}");write(new File(plugin,"package.json"),"{\"name\":\"p\"}");write(new File(plugin,"user.js"),"original");
        String proof=ManagedPackageProof.digest(fs,plugin,new BackupControl(null));var resolver=new GuestDataResolver(fs,root,null,List.of());
        var original=new NativePluginGraph(fs,resolver,dsh,global,root,Map.of("p",proof));original.collect(new BackupControl(null));assertTrue(original.sources.isEmpty());
        write(new File(plugin,"user.js"),"modified");assertThrows(IOException.class,()->original.verify(new BackupControl(null)));
        var modified=new NativePluginGraph(fs,resolver,dsh,global,root,Map.of("p",proof));modified.collect(new BackupControl(null));assertEquals(1,modified.sources.size());
    }
    @Test public void undeclaredDependenciesKeepTheirBindingAndMetadataChangesInvalidateSnapshot()throws Exception{
        File root=temporary.newFolder(),dsh=new File(root,"root/.dsh"),global=new File(root,"usr/local/lib/node_modules"),plugin=new File(global,"p");
        write(new File(dsh,"profiles/web/package.json"),"{\"dependencies\":{\"p\":\"1\"}}");write(new File(plugin,"package.json"),"{\"name\":\"p\",\"version\":\"1\"}");
        write(new File(plugin,"node_modules/extra/package.json"),"{\"name\":\"extra\",\"version\":\"2\"}");
        var graph=new NativePluginGraph(fs,new GuestDataResolver(fs,root,null,List.of()),dsh,global,root,Map.of());graph.collect(new BackupControl(null));
        var nodes=(Map<?,?>)graph.description().get("nodes");Map<?,?> parent=(Map<?,?>)nodes.values().stream().filter(value->((Map<?,?>)value).get("name").equals("p")).findFirst().orElseThrow();
        assertNotNull(((Map<?,?>)parent.get("edges")).get("extra"));
        write(new File(plugin,"package.json"),"{\"name\":\"p\",\"version\":\"3\"}");assertThrows(IOException.class,()->graph.verify(new BackupControl(null)));
    }
    @Test public void signedBuiltinCopiesAreExcludedButEnableIntentIsRecorded()throws Exception{
        File root=temporary.newFolder(),dsh=new File(root,"root/.dsh"),global=new File(root,"usr/local/lib/node_modules");
        write(new File(dsh,"profiles/web/package.json"),"{\"dependencies\":{\"dsh-web-mobile\":\"link:/old\",\"user-plugin\":\"1\"},\"dsh\":{\"profile\":{\"bundles\":[\"dsh-web-mobile\",\"user-plugin\"]}}}");
        File oldBuiltin=new File(dsh,"profiles/web/node_modules/dsh-web-mobile");write(new File(oldBuiltin,"package.json"),"{\"name\":\"dsh-web-mobile\",\"version\":\"old\"}");write(new File(oldBuiltin,"old.js"),"must not enter backup");
        File detached=new File(dsh,"plugin-src/dsh-web-mobile");write(new File(detached,"package.json"),"{\"name\":\"dsh-web-mobile\",\"version\":\"older\"}");write(new File(detached,"draft.js"),"must not enter backup");
        File history=new File(dsh,"plugin-history/old-system/package");write(new File(history,"package.json"),"{\"name\":\"dsh-web-mobile\",\"version\":\"oldest\"}");
        File user=new File(dsh,"profiles/web/node_modules/user-plugin");write(new File(user,"package.json"),"{\"name\":\"user-plugin\",\"version\":\"1\"}");write(new File(user,"index.js"),"keep user bytes");
        var graph=new NativePluginGraph(fs,new GuestDataResolver(fs,root,null,List.of()),dsh,global,root,Map.of());graph.collect(new BackupControl(null));
        assertEquals(1,graph.sources.size());assertEquals("user-plugin",graph.sources.get(0).description().get("name"));
        Map<?,?> description=graph.description(),nodes=(Map<?,?>)description.get("nodes");assertFalse(nodes.values().stream().anyMatch(value->((Map<?,?>)value).get("name").equals("dsh-web-mobile")));
        Map<?,?> systems=(Map<?,?>)description.get("systemPlugins"),web=(Map<?,?>)systems.get("web"),state=(Map<?,?>)web.get("dsh-web-mobile");assertEquals(true,state.get("enabled"));assertEquals(false,state.get("disabled"));
        assertEquals("DEPENDENCIES_CAPTURED_IN_GRAPH",graph.exclusion(oldBuiltin));
        assertEquals("DEPENDENCIES_CAPTURED_IN_GRAPH",graph.exclusion(detached));
        assertEquals("DEPENDENCIES_CAPTURED_IN_GRAPH",graph.exclusion(history.getParentFile()));
    }
}
