package com.deepseekharness.app.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class DeviceAppPolicyTest {
    @org.junit.Test public void clonedAppsResolveAllUidsAndProtectSystemProfiles() {
        DeviceAppPolicy.Snapshot snapshot = DeviceAppPolicy.snapshot("package:example.app uid:12345,99912345\n",
                "package:android uid:1000,99901000\n");
        org.junit.Assert.assertEquals(java.util.Collections.singletonList("example.app"), snapshot.targets(DeviceShellPolicy.inspect("kill 42"),
                "PID UID NAME\n42 99912345 example.app\n"));
        org.junit.Assert.assertTrue(snapshot.grouped().contains("uid=12345,99912345"));
        try {
            snapshot.targets(DeviceShellPolicy.inspect("kill 42 43"), "PID UID NAME\n42 99912345 example.app\n43 99901000 system_server\n");
            org.junit.Assert.fail();
        } catch (IllegalArgumentException expected) { }
        DeviceAppPolicy.Snapshot shared = DeviceAppPolicy.snapshot("package:example.shared uid:22222,99901000\n", "package:android uid:1000,99901000\n");
        try { shared.targets(DeviceShellPolicy.inspect("am force-stop example.shared"), ""); org.junit.Assert.fail(); }
        catch (IllegalArgumentException expected) { }
    }
    private static final String USERS = "package:example.app uid:10123\npackage:other.app uid:10124\npackage:com.dsh.client uid:10125\n";
    private static final String SYSTEMS = "package:android uid:1000\npackage:com.android.systemui uid:10101\n";
    @Test public void groupsAreCompleteAndOnlyUserTargetsPass() {
        DeviceAppPolicy.Snapshot apps = DeviceAppPolicy.snapshot(USERS, SYSTEMS);
        assertEquals(5, apps.apps.size()); assertTrue(apps.grouped().contains("[用户应用]")); assertTrue(apps.grouped().contains("[系统应用]"));
        assertEquals(java.util.Arrays.asList("example.app"), apps.targets(DeviceShellPolicy.inspect("am force-stop example.app"), ""));
        for (String name : new String[]{"android", "com.android.systemui", "com.dsh.client", "absent.app"}) {
            try { apps.targets(DeviceShellPolicy.inspect("am force-stop " + name), ""); fail(name); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void pidIsMappedToOneUserPackageInsteadOfSignalledDirectly() {
        DeviceAppPolicy.Snapshot apps = DeviceAppPolicy.snapshot(USERS, SYSTEMS);
        String processes = "PID UID NAME\n1 0 init\n1234 1000 system_server\n1235 10123 example.app:worker\n1236 10125 libproroot-bridge.so\n";
        assertEquals(java.util.Arrays.asList("example.app"), apps.targets(DeviceShellPolicy.inspect("kill -9 1235"), processes));
        for (String target : new String[]{"1234", "1236", "9999", "1235 1234"}) {
            try { apps.targets(DeviceShellPolicy.inspect("kill -9 " + target), processes); fail(target); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void systemSharedUidAndMalformedInventoryDeny() {
        DeviceAppPolicy.Snapshot apps = DeviceAppPolicy.snapshot(USERS + "package:shared.user uid:10101\n", SYSTEMS);
        try { apps.targets(DeviceShellPolicy.inspect("am force-stop shared.user"), ""); fail(); } catch (IllegalArgumentException expected) { }
        for (String output : new String[]{"", "garbage", "package:android uid:0\npackage:android uid:1"}) {
            try { DeviceAppPolicy.snapshot(USERS, output); fail(output); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void ambiguousSharedUidNeedsPackageName() {
        DeviceAppPolicy.Snapshot apps = DeviceAppPolicy.snapshot(USERS + "package:shared.user uid:10123\n", SYSTEMS);
        try { apps.targets(DeviceShellPolicy.inspect("kill 1235"), "PID UID NAME\n1235 10123 worker"); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
