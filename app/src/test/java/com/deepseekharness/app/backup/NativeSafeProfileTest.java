package com.deepseekharness.app.backup;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import static org.junit.Assert.*;

public class NativeSafeProfileTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    @Test public void createsBasicProfileWithoutAnyRuntimeAndPreservesWeb()throws Exception{
        File dsh=temp.newFolder(),web=new File(dsh,"profiles/web");Files.createDirectories(web.toPath());Files.writeString(new File(web,"package.json").toPath(),"broken user configuration");
        var fs=new JvmBackupFileSystem();String before=BackupTree.digest(fs,web,new BackupControl(null));String profile=NativeSafeProfile.create(fs,dsh,"0123456789abcdef");
        assertEquals("dsha-recovery-0123456789abcdef",profile);assertEquals(before,BackupTree.digest(fs,web,new BackupControl(null)));
        Map<String,Object> pkg=BackupJson.read(fs.small(new File(dsh,"profiles/"+profile+"/package.json"),16384),16384);
        assertEquals(Collections.emptyMap(),pkg.get("dependencies"));var settings=(Map<?,?>)((Map<?,?>)pkg.get("dsh")).get("profile");
        assertEquals(List.of("@deepseek-ai/dsh-base","@deepseek-ai/dsh-web-app"),settings.get("bundles"));assertEquals("startup",settings.get("patchReload"));
        assertThrows(IOException.class,()->NativeSafeProfile.create(fs,dsh,"0123456789abcdef"));assertEquals(before,BackupTree.digest(fs,web,new BackupControl(null)));
    }
    @Test public void missingDataAndInvalidIdentityAreNotFreshInstall()throws Exception{
        var fs=new JvmBackupFileSystem();assertThrows(IOException.class,()->NativeSafeProfile.create(fs,new File(temp.getRoot(),"missing"),"0123456789abcdef"));
        assertThrows(IOException.class,()->NativeSafeProfile.create(fs,temp.getRoot(),"../../outside"));assertFalse(new File(temp.getRoot(),"profiles").exists());
    }
}
