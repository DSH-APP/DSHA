package com.deepseekharness.app.backup;
import org.junit.Test;
import static org.junit.Assert.*;

public class DataRootPolicyTest {
    @Test public void executableAppEntrypointsAndSharedCordisPatchAreQuarantined(){
        for(String name:new String[]{"plugin-manager.py","startup-observer.cjs","cordis.patch.yml","custom-script.js"})assertTrue(name,DataRootPolicy.quarantineCode(name));
        assertFalse(DataRootPolicy.quarantineCode("settings.yaml"));assertFalse(DataRootPolicy.quarantineCode("sessions"));
        assertTrue(DataRootPolicy.directData("settings.yaml"));assertTrue(DataRootPolicy.directData(".dsha-apikey"));assertFalse(DataRootPolicy.directData("custom-plugin-source"));
    }
    @Test public void machineJournalsCannotReplaceCurrentSessionState(){
        for(String name:new String[]{"dsha-startup-checkpoints",".dsha-web.identity",".runtime-health.json","bridge-token"})assertTrue(name,DataRootPolicy.machine(name));
        assertFalse(DataRootPolicy.machine("plugin-sources.json"));assertFalse(DataRootPolicy.machine(".credentials.yaml"));
    }
}
