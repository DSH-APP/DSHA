package com.deepseekharness.app.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

/** BackupScope 的不变式：文件名前缀与恢复范围必须一一对应，绝不把部分备份当全量。 */
public class BackupScopeTest {

    @Test
    public void onlyFullBackupUsesLegacyPrefix() {
        assertEquals("DSHA-backup-", BackupScope.fileNamePrefix(BackupScope.FULL));
        assertEquals("DSHA-sessions-", BackupScope.fileNamePrefix(BackupScope.SESSIONS));
        assertEquals("DSHA-plugins-", BackupScope.fileNamePrefix(BackupScope.PLUGINS));
        assertEquals("DSHA-settings-", BackupScope.fileNamePrefix(BackupScope.SETTINGS));
    }

    @Test
    public void onlyFullIsVisibleToLegacyScan() {
        assertTrue(BackupScope.visibleToLegacyScan(BackupScope.FULL));
        assertFalse(BackupScope.visibleToLegacyScan(BackupScope.SESSIONS));
        assertFalse(BackupScope.visibleToLegacyScan(BackupScope.PLUGINS));
        assertFalse(BackupScope.visibleToLegacyScan(BackupScope.SETTINGS));
    }

    @Test
    public void archiveNamesPreserveScopeForLegacyRestore() {
        for (int scope : BackupScope.ALL) {
            String name = BackupScope.archiveName(scope, "latest");
            assertEquals(scope, BackupScope.fromFileName(name));
            assertEquals(scope == BackupScope.FULL, name.startsWith("DSHA-backup-"));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void archiveNameCannotEscapeDownloadDirectory() {
        BackupScope.archiveName(BackupScope.FULL, "../other");
    }

    @Test
    public void fromFileNameMatchesPrefix() {
        assertEquals(BackupScope.SESSIONS,
                BackupScope.fromFileName("/some/dir/DSHA-sessions-2026-01-01.tar.gz"));
        assertEquals(BackupScope.PLUGINS,
                BackupScope.fromFileName("DSHA-plugins-42.tar.gz"));
        assertEquals(BackupScope.FULL,
                BackupScope.fromFileName("DSHA-backup-1.tar.gz"));
        assertEquals(BackupScope.FULL, BackupScope.fromFileName(null));
    }

    @Test
    public void idRoundTrips() {
        for (int scope : BackupScope.ALL) {
            assertEquals(scope, BackupScope.fromId(BackupScope.id(scope)));
        }
        // 未知范围拒绝；无范围的历史包必须通过专门预检与用户确认。
        org.junit.Assert.assertThrows(IllegalArgumentException.class, () -> BackupScope.fromId("bogus"));
        assertThrows(IllegalArgumentException.class, () -> BackupScope.fromId(null));
    }

    @Test
    public void dshPathsAndMergeSubdirsLineUp() {
        // 备份打了什么，恢复就必须合并什么 —— 错位会「恢复成功但东西没回来」
        for (int scope : BackupScope.ALL) {
            String[] paths = BackupScope.dshPaths(scope);
            String[] subs = BackupScope.mergeSubdirs(scope);
            assertEquals("scope " + scope + " 的路径与合并子树数量不一致",
                    paths.length, subs.length);
        }
    }

    @Test
    public void fullScopePacksWholeDsh() {
        assertEquals(0, BackupScope.dshPaths(BackupScope.FULL).length);
        assertEquals(0, BackupScope.mergeSubdirs(BackupScope.FULL).length);
    }

    @Test
    public void sessionsScopePacksSessionsAndRegistry() {
        // dsh 1.2：会话文件在 sessions/，UI 入口在 storages/workspace.json 注册表。
        // 只带 sessions/ 恢复后 WebUI 认不出会话，所以注册表必须一起打包合并。
        assertArrayEquals(new String[] { ".dsh/sessions", ".dsh/storages", ".dsh/attachments" },
                BackupScope.dshPaths(BackupScope.SESSIONS));
        assertArrayEquals(new String[] { "sessions", "storages", "attachments" },
                BackupScope.mergeSubdirs(BackupScope.SESSIONS));
        assertArrayEquals(BackupScope.mergeSubdirs(BackupScope.SESSIONS),
                BackupScope.snapshotEntries(BackupScope.SESSIONS));
    }
}
