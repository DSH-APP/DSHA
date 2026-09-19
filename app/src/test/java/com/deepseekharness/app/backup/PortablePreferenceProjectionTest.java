package com.deepseekharness.app.backup;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class PortablePreferenceProjectionTest {
    @Test public void deviceCredentialsAndAutomaticBackupsNeverEnterProjection(){
        var values=new LinkedHashMap<String,Object>();values.put("ui_language","en");values.put("workdir","/sdcard/Projects/existing");values.put("confirm_shell",true);
        for(String key:List.of("api_key","bridge_token","shizuku_authorized","root_enabled","adb_paired","sms_authorized","auto_backup","backup_launch_count","welcomed","environment_ready"))values.put(key,true);
        assertEquals(Map.of("ui_language","en","workdir","/sdcard/Projects/existing","confirm_shell",true),PortablePreferenceProjection.project(values));
    }
    @Test public void wrongPreferenceTypesCannotBreakLanguageInitialization(){
        assertEquals(Map.of("desktop_mode",false),PortablePreferenceProjection.project(Map.of("ui_language",true,"ui_theme",2,"port",3080,"confirm_shell","false","desktop_mode",false)));
    }
    @Test public void localTokensDoNotBlockImportButNewUserPreferencesDo(){
        var portable=Map.of("projectionVersion",1,"ui_language","zh");
        assertTrue(PortablePreferenceProjection.mayImport(Map.of("bridge_token","new local token"),portable));
        assertFalse(PortablePreferenceProjection.mayImport(Map.of("ui_language","en"),portable));
        assertFalse(PortablePreferenceProjection.mayImport(Map.of(),Map.of("projectionVersion","invalid")));
    }
}
