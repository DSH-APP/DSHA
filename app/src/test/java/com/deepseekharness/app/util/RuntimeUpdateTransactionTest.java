package com.deepseekharness.app.util;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import static org.junit.Assert.*;

/** 真实同盘改名与中断回滚；夹具只触及测试私有目录。 */
public class RuntimeUpdateTransactionTest {
    @Rule public TemporaryFolder fixture = new TemporaryFolder();
    private static final String RUNTIME = ManagedRuntimeLayout.ROOT + ManagedRuntimeLayout.DSH;
    private static final String IDENTITY = "linux/.offline-identity";
    private void put(File root, String path, String value) throws Exception {
        File file = new File(root, path); assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.writeString(file.toPath(), value);
    }
    private String read(File root, String path) throws Exception { return Files.readString(new File(root, path).toPath()); }
    private RuntimeUpdateTransaction prepared() throws Exception {
        put(fixture.getRoot(), RUNTIME + "/package.json", "old");
        put(fixture.getRoot(), IDENTITY, "114:10:0.1.5-alpha.1");
        put(fixture.getRoot(), "linux/ubuntu/root/.dsh/sessions/private", "conversation");
        RuntimeUpdateTransaction task = RuntimeUpdateTransaction.create(fixture.getRoot());
        put(task.stage(), RUNTIME + "/package.json", "new");
        put(task.stage(), IDENTITY, "115:10:0.1.5-alpha.2");
        return task;
    }
    @Test public void successOnlyReplacesManagedRuntimeAndIdentity() throws Exception {
        RuntimeUpdateTransaction task = prepared(); task.begin(List.of(RUNTIME, IDENTITY)); task.replace(); task.commit();
        assertEquals("new", read(fixture.getRoot(), RUNTIME + "/package.json"));
        assertEquals("conversation", read(fixture.getRoot(), "linux/ubuntu/root/.dsh/sessions/private"));
        assertNull(RuntimeUpdateTransaction.pending(fixture.getRoot()));
        task.rollback(); assertEquals("new", read(fixture.getRoot(), RUNTIME + "/package.json"));
    }
    @Test public void deathAfterSwitchRestoresOriginalWithoutTouchingConversation() throws Exception {
        RuntimeUpdateTransaction task = prepared(); task.begin(List.of(RUNTIME, IDENTITY)); task.replace();
        RuntimeUpdateTransaction.pending(fixture.getRoot()).rollback();
        assertEquals("old", read(fixture.getRoot(), RUNTIME + "/package.json"));
        assertEquals("114:10:0.1.5-alpha.1", read(fixture.getRoot(), IDENTITY));
        assertEquals("new", read(task.directory(), "failed/0/package.json"));
        assertEquals("conversation", read(fixture.getRoot(), "linux/ubuntu/root/.dsh/sessions/private"));
        assertNull(RuntimeUpdateTransaction.pending(fixture.getRoot()));
    }
    @Test public void partialSwitchAndRollbackAreRecoverable() throws Exception {
        RuntimeUpdateTransaction task = prepared(); task.begin(List.of(RUNTIME, IDENTITY));
        assertTrue(new File(task.stage(), IDENTITY).delete());
        assertThrows(IOException.class, task::replace);
        RuntimeUpdateTransaction.pending(fixture.getRoot()).rollback();
        assertEquals("old", read(fixture.getRoot(), RUNTIME + "/package.json"));
        assertEquals("114:10:0.1.5-alpha.1", read(fixture.getRoot(), IDENTITY));
    }
    @Test public void deathBeforeFirstRenameLeavesOriginalInPlace() throws Exception {
        RuntimeUpdateTransaction task = prepared(); task.begin(List.of(RUNTIME, IDENTITY));
        RuntimeUpdateTransaction.pending(fixture.getRoot()).rollback();
        assertEquals("old", read(fixture.getRoot(), RUNTIME + "/package.json"));
    }
    @Test public void userPathsAndDuplicatePlansAreRejectedBeforeSwitch() throws Exception {
        RuntimeUpdateTransaction task = prepared();
        assertThrows(IOException.class, () -> task.begin(List.of("linux/ubuntu/root/.dsh/sessions")));
        assertThrows(IOException.class, () -> task.begin(List.of(RUNTIME, RUNTIME)));
        assertNull(RuntimeUpdateTransaction.pending(fixture.getRoot()));
        assertEquals("old", read(fixture.getRoot(), RUNTIME + "/package.json"));
    }
    @Test public void committedUpdateIsTheOnlyOneEligibleForCleanup() throws Exception {
        RuntimeUpdateTransaction task = prepared(); task.begin(List.of(RUNTIME, IDENTITY)); task.replace();
        final int[] calls = {0};
        task.cleanup(file -> calls[0]++); assertEquals(0, calls[0]);
        task.commit(); task.cleanup(file -> calls[0]++); assertEquals(2, calls[0]);
        task.cleanup(file -> calls[0]++); assertEquals(2, calls[0]);
    }
    @Test public void layoutBoundaryAndBaseMatching() {
        assertTrue(ManagedRuntimeLayout.sameBase("115:10:0.1.5-alpha.2", "114:10:0.1.5-alpha.1"));
        assertTrue(ManagedRuntimeLayout.sameBase("116:10:0.1.5-rc.1", "115:10:0.1.5-alpha.2"));
        assertFalse(ManagedRuntimeLayout.sameBase("115:10:0.1.5-alpha.2", "113:9:0.1.2-rc.1"));
        assertFalse(ManagedRuntimeLayout.sameBase("115:10:0.1.5-alpha.2", "10"));
        for (String path : new String[]{"root/.dsh", "root/project", "root/.dsh/settings.yaml", "usr/local/lib/node_modules/npm", "usr/local/lib/node_modules/../private"})
            assertFalse(path, ManagedRuntimeLayout.allowed(ManagedRuntimeLayout.ROOT + path));
        for (String path : ManagedRuntimeLayout.paths()) assertTrue(path, ManagedRuntimeLayout.allowed(ManagedRuntimeLayout.ROOT + path));
    }
}
