package com.deepseekharness.app.backup;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RuntimeDescriptorTest {
    // 目录切换夹具使用同一份合成读写实现；变更 runtime 键仅代表其它运行资产。
    static Map<String,Object> inputs(char revision){return Map.of("runtime",String.valueOf(revision).repeat(64),"dsh-runtime.bin","1".repeat(64),"agent-preset-patch.json","2".repeat(64),"session-interaction-patch.json","3".repeat(64),"persona-compat-patch.json","4".repeat(64));}
    static Map<String,Object> value(){Map<String,Object> v=new LinkedHashMap<>();v.put("version",1L);v.put("baseVersion","10");v.put("dshVersion","0.1.5-rc.2");v.put("launcherContract","DSHA_ARM64_V2");v.put("bridgeProtocol",2L);v.put("dataRead",List.of("dsh-v3"));v.put("dataWrite","dsh-v3");v.put("inputs",inputs('a'));identify(v);return v;}
    static void identify(Map<String,Object> value){try{value.put("runtimeId",RuntimeDescriptor.calculateId(value));}catch(java.io.IOException error){throw new IllegalArgumentException(error);}}
    @Test public void uiApkVersionDoesNotInvalidateRuntime()throws Exception{
        Map<String,Object> a=value(),b=value();a.put("apkCode",130L);b.put("apkCode",131L);assertTrue(new RuntimeDescriptor(a).latest(new RuntimeDescriptor(b)));
    }
    @Test public void compatibleIsNotTheSameAsLatest()throws Exception{
        Map<String,Object> changed=value();changed.put("inputs",Map.of("runtime","b".repeat(64)));identify(changed);assertFalse(new RuntimeDescriptor(value()).latest(new RuntimeDescriptor(changed)));assertTrue(new RuntimeDescriptor(value()).compatible(new RuntimeDescriptor(changed)));
        changed.put("launcherContract","DIFFERENT");identify(changed);assertFalse(new RuntimeDescriptor(value()).compatible(new RuntimeDescriptor(changed)));
    }
    @Test public void staticChecksAloneCannotConfirmHealth(){
        Map<String,Object> proof=new LinkedHashMap<>();proof.put("runtimeId","a".repeat(64));proof.put("nonce","1".repeat(32));proof.put("assets",true);proof.put("nativeModules",true);assertFalse(RuntimeDescriptor.healthy(proof,"a".repeat(64)));
        proof.put("port",3080L);proof.put("confirmedAt",1L);
        for(String check:List.of("process","authentication","localApi","renderer","dataRead","dataWrite"))proof.put(check,true);
        assertFalse(RuntimeDescriptor.healthy(proof,"a".repeat(64)));proof.put("processExited",true);
        assertFalse(RuntimeDescriptor.healthy(proof,"a".repeat(64)));proof.put("storageFreshReopened",true);
        assertTrue(RuntimeDescriptor.healthy(proof,"a".repeat(64)));assertFalse(RuntimeDescriptor.healthy(proof,"b".repeat(64)));
    }
    @Test public void metadataCannotKeepAnOldIdentityAfterChangingItsInputs()throws Exception{
        var changed=value();changed.put("inputs",Map.of("runtime","changed bytes"));assertThrows(java.io.IOException.class,()->new RuntimeDescriptor(changed));
        var original=value();var descriptor=new RuntimeDescriptor(original);original.put("dataWrite","foreign");assertEquals("dsh-v3",descriptor.json().get("dataWrite"));
    }
    @Test public void readsTheActualBuildGeneratedDescriptor()throws Exception{
        java.io.File file=new java.io.File("src/main/assets/runtime-descriptor.json");if(!file.isFile())file=new java.io.File("app/src/main/assets/runtime-descriptor.json");
        assertTrue(file.isFile());var descriptor=new RuntimeDescriptor(BackupJson.read(java.nio.file.Files.readAllBytes(file.toPath()),BackupLimits.MANIFEST));assertTrue(descriptor.latest(descriptor));
    }
}
