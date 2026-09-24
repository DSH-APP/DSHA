package com.deepseekharness.app.util;
import org.junit.Test;
import static org.junit.Assert.*;
public class ProfileConfigPathTest {
    @Test public void everyExplicitProfileCanCarrySettings() {
        assertTrue(ProfileConfigPath.accepts("profiles/web/cordis.patch.yml"));
        assertTrue(ProfileConfigPath.accepts("profiles/team-2/package.json"));
        for(String path : new String[]{"profiles/../package.json","profiles/web/../../settings.yaml","profiles/web/node_modules/x", "profiles/web\\x/package.json","/profiles/web/package.json"}) assertFalse(path,ProfileConfigPath.accepts(path));
    }
}
