# Configuration snapshots, reset, and settings migration

> Translation verified: 2026-10-21. Source baseline: released F10 `087724c` and the current Chinese build190 host-configuration guide. This is documentation work, not new device or interrupted-recovery testing. The [host configuration recovery note](configuration-recovery-host.md) retains the original scope of its historical validation statements. Final build identity and executed scope are in the [English README](../../README.en.md) and [release introduction](release-introduction.md).

DSHA protects configuration in three separate ways: six-file startup snapshots protect DSH startup configuration; “Reset configuration” handles shared settings and the current private workspace's `.env`; host-settings migration imports a typed whitelist. Their scopes differ, and none is a complete data backup. Use [backup and restore](backup-and-restore.md) for conversations, attachments, or original plugin content.

## Six-file startup snapshots

[ConfigurationSnapshots](../../app/src/main/java/com/deepseekharness/app/backup/ConfigurationSnapshots.java) handles only these six fixed relative paths under the current DSH data root:

| Path | Scope |
|---|---|
| `profiles/web/package.json` | Web profile bundles, dependencies, and profile settings |
| `profiles/web/cordis.patch.yml` | Web profile patch |
| `profiles/web/pnpm-workspace.yaml` | Web profile workspace dependency settings |
| `profiles/web/pnpm-lock.yaml` | Existing Web profile lock |
| `settings.yaml` | Shared DSH settings |
| `cordis.patch.yml` | Shared DSH patch |

Snapshots do not traverse sessions, attachments, workspaces, plugin entities, or Keystore, and do not export native preferences. Links, unreadable paths, and oversized files return explicit errors rather than being read as missing/empty configuration. “Originally absent” is retained as a fact; a restore may return the corresponding fixed file to absence, as listed in the UI.

New snapshots live in private `files/startup-config-snapshots/<UUID>/`. Bodies are numbered 0–5. `metadata.json` records presence, size, digest, creation time, and source DSH version; each file is limited to 4 MiB. Source contents and private copies are rechecked after capture before catalog update. The catalog keeps three `healthy-*`, three `before-*`, and a separate startup `candidate` reference. Abnormal originals are not removed by normal rotation, and a snapshot selected for restore remains pinned until that operation ends.

### When a snapshot becomes healthy

A candidate is captured before startup and bound to that startup ID. [StartupRepairs.healthy](../../app/src/main/java/com/deepseekharness/app/core/StartupRepairs.java) saves a healthy reference only when the current generation reports browser readiness, has not begun stopping, and all six files still exactly match the pre-start candidate. A created process, page navigation, or authentication success alone does not meet that condition. Editing configuration during startup invalidates the candidate instead of producing a false healthy record.

“Healthy” associates that startup configuration with a corresponding readiness record; it does not prove every plugin, model provider, or session is correct. A DSH version in snapshot metadata is not data-format compatibility proof either.

## Native recovery operations and when they take effect

[StartupRecoveryActivity](../../app/src/main/java/com/deepseekharness/app/ui/StartupRecoveryActivity.java) works without Web. It shows recent startup records, selects snapshots, creates selected default configuration, and handles unfinished configuration operations. The UI calls `StartupRepairs` through [BackupTask](../../app/src/main/java/com/deepseekharness/app/core/BackupTask.java); writing requires ownership of the maintenance gate.

1. Open the list from startup failure/recovery and check whether the entry is healthy or pre-repair, its time, absent files, and source ID.
2. Review and confirm the change scope. The operation stops Web, terminals, and writers and first saves a pre-repair six-file snapshot.
3. Retry startup after the host transaction completes. A success message means configuration transaction completion, not automatic runtime readiness.

“Create Web startup configuration” replaces `package.json` and the Web `cordis.patch.yml` with current host defaults. It does not reinstall removed plugins or imply that all four profile files are rebuilt. “Shared settings” creates only `settings.yaml` as `{}`; “Shared patch” creates only the top-level `cordis.patch.yml` as `[]`. Previous bodies enter the pre-repair snapshot. Sessions, plugin files, and native API keys are outside these write targets.

Restore binds the selected slot and ID, reloads them, and checks them before commit. Corruption, slot rotation, or source changes stop writing with errors such as `RECOVERY_CHECKSUM` and `RECOVERY_CHANGED`. A file originally absent is handled by an explicit removal plan, not replaced with an empty file.

## Interruptions and old checkpoints

Change journals live under private `files/startup-config-operations/<UUID>/` and reuse [HostDataTransaction](../../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java) candidates, before/after digests, switching intent, and finalized/rolled-back terminal states. Bodies do not pass through shell arguments. Unfinished journals block normal startup and must be handled through the common recovery entry, rather than deleted to bypass it.

Old v1 JSON/base64 checkpoints remain readable, with a 36 MiB document limit and 4 MiB per-file limit, and appear as `legacy-healthy-*` / `legacy-before-*`. Recovery fixes and verifies an input copy before preparing the current host transaction; it does not execute Python or arbitrarily deserialize configuration. A finalized old operation only receives archival cleanup, never reapplies its old snapshot over later user changes. If an old state marker remains without a journal that can be rolled back, the marker is retained and current configuration remains unchanged; the UI distinguishes this outcome.

## “Reset configuration” uses another transaction

[NativeConfigurationReset](../../app/src/main/java/com/deepseekharness/app/backup/NativeConfigurationReset.java) handles `settings.yaml` and the current private working directory's `.env` as two roots. It builds candidates from checked native credentials and saves/switches them through the existing [HostDataTransaction](../../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java). Its record is `host-backup-operations/<UUID>/config-reset.json`, recognized by common host recovery after interruption.

It does not reset six-file snapshots, empty plugins/sessions, or format Ubuntu. Temporarily unreadable credentials cause failure while preserving original ciphertext, not an empty key write. External workdirs, configuration links, and targets across filesystems are not forcibly reset. Restart and check runtime behavior after the write completes.

## Importing and merging host settings

Manual v5 backups use [ConfigStore.exportPortableSettings/prepareHostSettings](../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java) to project port/mode, workdir, tool permission mode, shell confirmation, desktop UA, update checking, eco mode, PiP layout/enablement, theme, and language. Only present whitelisted fields merge into current settings; absent fields retain their current values. Invalid ports and overlong/NUL paths are rejected. The native API key is excluded by default. When explicitly included/restored, it is first encrypted and read back through local Keystore; credential unavailability must not collapse into an empty string.

Restoring settings does not import ADB/Root/Shizuku grants, screen permissions, running PIDs, session tokens, or automatic-backup passwords. Runtime parameters such as port and workdir apply on the next start. Language, theme, and Web display settings also follow their own UI recreation/page-loading paths; completed import does not imply that the already running page has changed. Production model/provider editing remains in Web rather than returning to the old native configuration page.

System migration has a separate [PortableSettings](../../app/src/main/java/com/deepseekharness/app/data/PortableSettings.java) / [PortablePreferenceProjection](../../app/src/main/java/com/deepseekharness/app/backup/PortablePreferenceProjection.java) whitelist projection. It imports automatically only if active preferences have no transferable fields and the projection version matches; current settings take precedence. It does not copy keys, grants, automatic-backup preferences, or runtime state. This is separate from a user-confirmed v5 restore merge.

## Modification and verification entry points

[ConfigurationSnapshotsTest](../../app/src/test/java/com/deepseekharness/app/backup/ConfigurationSnapshotsTest.java) covers capture, rotation, source changes, pinned snapshots, and transactions. [LegacyConfigurationSnapshot](../../app/src/main/java/com/deepseekharness/app/backup/LegacyConfigurationSnapshot.java) constrains historical reading. [ConfigStore](../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java) and four-state credential reads define settings import. For every new setting, decide separately whether it belongs in snapshot bodies, user backups, the system-migration whitelist, or local-only state; do not import all domains together.

This translation checks the call-chain explanation and relative references only. Historical process-kill/temporary-directory tests are not Android power-loss durability tests. No hands-on restore, uninstall migration, or complete historical-data matrix was repeated for this build as part of documentation work.
