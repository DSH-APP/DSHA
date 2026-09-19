package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

/** 真实源码/声明/目录移动，链接元数据模拟以覆盖不授予 symlink 权限的 Windows 主机。 */
public class PluginRestoreGraphTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    static final String A="package-"+"a".repeat(20),B="package-"+"b".repeat(20);
    private final GuestDataResolverTest.LinkFs fs=new GuestDataResolverTest.LinkFs();
    private void put(File root,String path,String content)throws Exception{File target=new File(root,path);Files.createDirectories(target.getParentFile().toPath());Files.writeString(target.toPath(),content);}
    private Map<String,Object> node(String id,String name,Map<String,Object> edges){return new LinkedHashMap<>(Map.of("name",name,"managed",false,"sourceRoot",id,"edges",edges));}
    private Map<String,Object> graph(){return Map.of("version",2,"nodes",Map.of(A,node(A,"user-plugin",Map.of("helper",B)),B,node(B,"helper",Map.of("user-plugin",A))),"profiles",Map.of("web",Map.of("user-plugin",A)),"shared",Map.of(),"complete",true);}
    private File fixture()throws Exception{
        File root=temp.newFolder();put(root,"packages/"+A+"/package.json","{\"name\":\"user-plugin\",\"dependencies\":{\"helper\":\"1\"},\"scripts\":{\"postinstall\":\"node install.cjs\"}}");
        put(root,"packages/"+A+"/install.cjs","throw new Error('PLUGIN_HOOK_MUST_NOT_RUN');");
        put(root,"packages/"+B+"/package.json","{\"name\":\"helper\",\"dependencies\":{\"user-plugin\":\"1\"},\"bin\":{\"helper-cli\":\"cli.js\"}}");
        put(root,"packages/"+B+"/cli.js","preserved user command bytes");put(root,"profiles/web/package.json","{\"dependencies\":{\"user-plugin\":\"link:/old/device/plugin\"}}");return root;
    }
    @Test public void rebuildsTransitiveCycleBinsAndProfileLinksWithoutExecutingCode()throws Exception{
        File root=fixture();var report=new PluginRestoreGraph(fs,root,(name,proof)->null).rebuild(graph(),new BackupControl(null));
        assertEquals(true,report.get("relationshipsRebuilt"));assertEquals(false,report.get("executed"));
        String link=fs.readLink(new File(root,"packages/"+A+"/node_modules/helper"));assertEquals(new File(root,"packages/"+B).getCanonicalFile(),new File(root,"packages/"+A+"/node_modules/"+link).getCanonicalFile());
        assertNotNull(fs.readLink(new File(root,"packages/"+A+"/node_modules/.bin/helper-cli")));
        assertTrue(new File(root,"original-declarations/profiles/web/package.json").isFile());
        File moved=new File(temp.getRoot(),"moved");Files.move(root.toPath(),moved.toPath());
        assertEquals("preserved user command bytes",Files.readString(new File(moved,"packages/"+A+"/node_modules/"+link+"/cli.js").toPath()));
    }
    @Test public void legacyV4GraphRetainsActualNodeAndProfileRelationships()throws Exception{
        File root=fixture();var old=Map.<String,Object>of("legacyVersion",4,"dependencyGraph",Map.of("a".repeat(20),Map.of("name","user-plugin","links",Map.of("helper","b".repeat(20))),"b".repeat(20),Map.of("name","helper","links",Map.of())),
                "declarations",List.of(Map.of("profile","web","name","user-plugin","node","a".repeat(20))));
        new PluginRestoreGraph(fs,root,(name,proof)->null).rebuild(old,new BackupControl(null));
        assertNotNull(fs.readLink(new File(root,"profiles/web/node_modules/user-plugin")));assertNotNull(fs.readLink(new File(root,"packages/"+A+"/node_modules/helper")));
    }
    @Test public void unavailableManagedDependencyDoesNotDiscardPluginSource()throws Exception{
        File root=fixture();Map<String,Object> description=new LinkedHashMap<>(graph());var managed=new LinkedHashMap<String,Object>();managed.put("name","helper");managed.put("managed",true);managed.put("proof","1".repeat(64));managed.put("edges",Map.of());
        description.put("nodes",Map.of(A,node(A,"user-plugin",Map.of("helper",B)),B,managed));
        var report=new PluginRestoreGraph(fs,root,(name,proof)->null).rebuild(description,new BackupControl(null));assertEquals(false,report.get("relationshipsRebuilt"));
        assertTrue(new File(root,"packages/"+A+"/install.cjs").exists());assertEquals(false,report.get("executed"));
    }
    @Test public void executablePathEscapeOrMissingReferenceIsRejected()throws Exception{
        File root=fixture();put(root,"packages/"+B+"/package.json","{\"name\":\"helper\",\"bin\":{\"x\":\"../outside\"}}");
        assertThrows(IOException.class,()->new PluginRestoreGraph(fs,root,(name,proof)->null).rebuild(graph(),new BackupControl(null)));
        File another=fixture();Map<String,Object> description=new LinkedHashMap<>(graph());description.put("nodes",Map.of(A,node(A,"user-plugin",Map.of("helper","package-"+"c".repeat(20)))));
        assertThrows(IOException.class,()->new PluginRestoreGraph(fs,another,(name,proof)->null).rebuild(description,new BackupControl(null)));
    }
    @Test public void existingCustomBinWrapperIsPreservedAndReported()throws Exception{
        File root=fixture();put(root,"packages/"+A+"/node_modules/.bin/helper-cli","custom wrapper");var report=new PluginRestoreGraph(fs,root,(name,proof)->null).rebuild(graph(),new BackupControl(null));
        assertEquals("custom wrapper",Files.readString(new File(root,"packages/"+A+"/node_modules/.bin/helper-cli").toPath()));assertEquals(false,report.get("relationshipsRebuilt"));
    }
    @Test public void oldArchiveBuiltinPayloadIsDiscardedAndUserPluginStillRebuilds()throws Exception{
        File root=fixture();put(root,"packages/"+B+"/package.json","{\"name\":\"dsh-web-mobile\",\"version\":\"old\"}");put(root,"packages/"+B+"/old.js","must disappear");
        Map<String,Object> description=Map.of("version",2,"nodes",Map.of(
                A,node(A,"user-plugin",Map.of("dsh-web-mobile",B)),
                B,node(B,"dsh-web-mobile",Map.of())),
                "profiles",Map.of("web",Map.of("user-plugin",A,"dsh-web-mobile",B)),"shared",Map.of(),"complete",true);
        var report=new PluginRestoreGraph(fs,root,(name,proof)->null).rebuild(description,new BackupControl(null));
        assertFalse(new File(root,"packages/"+B).exists());assertFalse(new File(root,"profiles/web/node_modules/dsh-web-mobile").exists());
        assertNotNull(fs.readLink(new File(root,"profiles/web/node_modules/user-plugin")));
        assertEquals(List.of("dsh-web-mobile"),report.get("ignoredSystemPlugins"));assertEquals(true,report.get("relationshipsRebuilt"));
    }
    @Test public void signedBuiltinStateUsesCurrentApkPathAndKeepsDisabledIntent()throws Exception{
        File root=fixture();put(root,"profiles/web/node_modules/dsh-web-mobile/package.json","{\"name\":\"dsh-web-mobile\",\"version\":\"old\"}");
        Map<String,Object> description=new LinkedHashMap<>(graph());description.put("systemPlugins",Map.of("web",Map.of("dsh-web-mobile",Map.of("enabled",false,"disabled",true))));
        var report=new PluginRestoreGraph(fs,root,(name,proof)->null).rebuild(description,new BackupControl(null));
        assertFalse(new File(root,"profiles/web/node_modules/dsh-web-mobile").exists());assertTrue(new File(root,"profiles/web/node_modules/dsh-web-mobile.disabled").isFile());
        Map<String,Object> profile=BackupJson.read(Files.readAllBytes(new File(root,"profiles/web/package.json").toPath()),BackupLimits.MANIFEST);
        assertEquals("link:/root/dsha-web-mobile",((Map<?,?>)profile.get("dependencies")).get("dsh-web-mobile"));
        assertEquals(1L,report.get("systemPluginStatesApplied"));assertEquals(false,report.get("executed"));
    }
}
