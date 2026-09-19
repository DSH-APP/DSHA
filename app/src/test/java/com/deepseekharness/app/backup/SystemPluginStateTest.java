package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class SystemPluginStateTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final BackupFileSystem fs=new JvmBackupFileSystem();
    private void put(File root,String path,String text)throws Exception{File file=new File(root,path);Files.createDirectories(file.getParentFile().toPath());Files.writeString(file.toPath(),text);}

    @Test public void appliesOnlyIntentToActiveCandidateAndDropsOldBuiltinBytes()throws Exception{
        File profiles=temp.newFolder("profiles");put(profiles,"web/package.json","{\"dependencies\":{\"user-plugin\":\"1\"},\"dsh\":{\"profile\":{\"bundles\":[\"user-plugin\",\"dsh-web-mobile\"]}}}");
        put(profiles,"web/node_modules/dsh-web-mobile/package.json","{\"name\":\"dsh-web-mobile\",\"version\":\"old\"}");put(profiles,"web/node_modules/dsh-web-mobile/old.js","old signed plugin bytes");
        Map<String,Object> state=Map.of("web",Map.of("dsh-web-mobile",Map.of("enabled",false,"disabled",true)));
        assertEquals(1,SystemPluginState.apply(fs,profiles,state,new BackupControl(null),false));
        assertFalse(new File(profiles,"web/node_modules/dsh-web-mobile").exists());assertTrue(new File(profiles,"web/node_modules/dsh-web-mobile.disabled").isFile());
        Map<String,Object> profile=BackupJson.read(Files.readAllBytes(new File(profiles,"web/package.json").toPath()),BackupLimits.MANIFEST);
        assertEquals("link:/root/dsha-web-mobile",((Map<?,?>)profile.get("dependencies")).get("dsh-web-mobile"));
        assertFalse(((List<?>)((Map<?,?>)((Map<?,?>)profile.get("dsh")).get("profile")).get("bundles")).contains("dsh-web-mobile"));
        assertEquals("1",((Map<?,?>)profile.get("dependencies")).get("user-plugin"));
    }

    @Test public void missingWebProfileCanBeCreatedFromCurrentRuntimeContract()throws Exception{
        File profiles=new File(temp.newFolder(),"profiles");Map<String,Object> state=Map.of("web",Map.of("dsh-auto-review",Map.of("enabled",true,"disabled",false)));
        assertEquals(1,SystemPluginState.apply(fs,profiles,state,new BackupControl(null),true));
        Map<String,Object> profile=BackupJson.read(Files.readAllBytes(new File(profiles,"web/package.json").toPath()),BackupLimits.MANIFEST);
        assertTrue(((List<?>)((Map<?,?>)((Map<?,?>)profile.get("dsh")).get("profile")).get("bundles")).contains("dsh-auto-review"));
    }

    @Test public void legacyGraphWithoutStateRemainsCompatible()throws Exception{
        assertTrue(SystemPluginState.fromGraph(Map.of("version",1L,"nodes",Map.of())).isEmpty());
    }
}
