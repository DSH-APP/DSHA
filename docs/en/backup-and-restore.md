# Backup, restore, and retained data

> Translation verified: 2026-10-21. Source baseline: released F10 `087724c` and the current Chinese build190 maintenance page. Entry points and boundaries follow that source review. Translation adds no build, device, ADB, hands-on restore, or long-duration validation. See the [English README](../../README.en.md) and [release introduction](release-introduction.md) for final identity and executed scope; a particular artifact's results remain bound to its release record and digest.

This page covers user operations and failure boundaries. See the [v5 format](backup-format-v5.md) for binary layout, cryptographic parameters, and resource limits, and [runtime, data, and plugins](runtime-data-plugin-architecture.md) for data ownership. A DSHA host backup is distinct from DSH's own session files and is not a full Ubuntu image.

## Choose an entry point and scope

| Entry point | Contents and destination | When it takes effect |
|---|---|---|
| Data and backups → One-click backup | Fixed `application` scope: conversations, attachments, settings, user plugins, and the current in-app workspace if location checks pass; native API key excluded by default | Once a job is accepted, safely stops DSH and running tasks before capture; restart manually afterward |
| Restore and more backup options → Manual export | Select application data, sessions, settings, plugins, or projects; optionally select SAF project directories and explicitly include the native API key | Executes after destination selection and submission; canceling the picker does not collect or replay anything |
| Automatic backup | Personal application data by default, without copying Ubuntu; use manual backup for personal projects | Captures when due and DSH, terminals, and other writers have stopped; does not forcibly stop work |
| Application data and recovery / Retained data | Inspect/export readable current data or a selected historical tree; rescue may be partial or best effort | Follow the displayed source, scope, and result level; it is not automatically a complete healthy copy |

The one-click entry follows [WorkspaceFragment](../../app/src/main/java/com/deepseekharness/app/ui/WorkspaceFragment.java) → [OneClickBackup](../../app/src/main/java/com/deepseekharness/app/backup/OneClickBackup.java) → [NativeBackupJobs](../../app/src/main/java/com/deepseekharness/app/backup/NativeBackupJobs.java) into the existing host flow. Repeated taps, rotation, and page recreation do not resubmit the same preparation request. A process restart during destination selection requires a new request; the old choice is not executed automatically.

The in-app workspace, other private projects, and external workspaces are different sets. One-click backup does not traverse every project or scan external contents through an external-workspace reference. Select external files separately for manual backup, or back them up with their owning app. [NativeDataLocations](../../app/src/main/java/com/deepseekharness/app/backup/NativeDataLocations.java) determines source resolution, exclusions, and projections. A path displayed in the UI does not mean its files are in the archive.

## Save and confirm completion

On API29+, one-click backup creates a new file in system `Download/DSHA` through MediaStore, without all-files access. Low on API23–28 asks for a SAF directory once and retains the grant for reuse; select again if the grant is lost. Provider cancellation and stream I/O run in the background.

A new export is `DSHA-data-v5-<UUID>.tar.gz`, password-free, with a standard tar/gzip shell, `portable.dshbak`, and a readme. Each export uses a new UUID rather than overwriting an existing file. The archive provides integrity checks, not confidentiality. Conversations, projects, `.env`, and plugin configuration may contain secrets, so select a trusted location even when the native API key is excluded.

The job generates and verifies private payload first, then writes the destination and compares the full readback digest. Downloads pending state is cleared only after closing the stream and completing readback. Failure cleans only the exact target this job created and owns. A SAF object is preserved and failure reported if ownership cannot be established; another file with the same name is not deleted. Process termination does not trigger a scan to clean old public pending entries.

“Written; destination readback unverified” is not “verified backup” and does not update the last successful backup record. Retained private verified output can be exported again, but private copies and local keys are not guaranteed to survive uninstall. New manual exports have no password option. Historical encrypted inputs still require the original password; internal automatic copies remain encrypted with a host-generated local password.

| Payload level | Actual meaning | Follow-up |
|---|---|---|
| `QUIESCENT` | Application writers stopped and sources checked again | Still inspect the archive's actual scope |
| `EXTERNAL_CHECKED` | Sources that external apps may change were checked repeatedly | Does not establish a write lock on external apps |
| `BEST_EFFORT` | Rescue capture without application-level quiescence | Preserve originals and inspect entries |
| `PARTIAL` | Missing, unreadable, or changed items exist | Read the manifest; do not promote it to complete |

Plugin dependency warnings remain separate. `DATA_SAVED_PLUGIN_WARNINGS` can mean data was saved but plugins need review before restore; it does not mean all dependencies are healthy.

## Automatic backup settings

Changes in [AutomaticBackupActivity](../../app/src/main/java/com/deepseekharness/app/ui/AutomaticBackupActivity.java) are saved and [AutomaticBackups](../../app/src/main/java/com/deepseekharness/app/backup/AutomaticBackups.java) reschedules the local JobScheduler. Backups are on by default, daily at 03:00 local time; alternatives are every 1–168 hours or after DSH stops. Daily scheduling follows the current local time zone. It is not an exact alarm: system scheduling, power saving, and busy state may delay execution.

A due check requires completed first-run setup, no other backup, no start/stop operation, no active authenticated Web session, no runtime tasks, no pending maintenance recovery, and confirmed Web shutdown. “Create automatic backup now” still requires the feature enabled and an idle environment; it does not force DSH to stop. [AutomaticBackupService](../../app/src/main/java/com/deepseekharness/app/backup/AutomaticBackupService.java) cancels only the job ID it owns, so an old callback cannot cancel a later manual export. Disabling cancels future scheduling and preserves existing copies. If the service already owns an automatic job, a later poll that observes disabling cancels that job.

[AutomaticBackupPruner](../../app/src/main/java/com/deepseekharness/app/backup/AutomaticBackupPruner.java) rotates only explicitly automatic, verified copies whose result is `COMPLETE` or `DATA_SAVED_PLUGIN_WARNINGS`, keeping the latest three. It checks contents and the completed operation record again; directories with unknown additional files or a busy record are retained. Manual copies, unknown records, partial results, and public exports are outside this pruning rule. If encrypted automatic copies already exist but their local key is missing, no replacement key is generated to hide the problem.

## Restore from a file or private copy

1. Open the native restore entry under “Restore and more backup options,” then choose an archive or verified private copy. This entry does not wait for Ubuntu/Web to be usable.
2. Supply the original password for historical encrypted input. Password-free v5 is identified by contents, not by an assumption that matching extensions mean encryption or full scope.
3. Wait for complete authentication and outer/inner digest checks. Input is written only into private numbered slots for this operation; archive paths do not directly become current write targets.
4. Review actual scope, conflicts, warnings, project destination, and credential selection, then confirm stopping and restoring. Canceling the preview does not commit candidates.
5. Check the final result and start the regular environment manually. Restored data does not prove model credentials, plugin loading, or Web readiness.

[NativeRestorePlan](../../app/src/main/java/com/deepseekharness/app/backup/NativeRestorePlan.java) and [NativeRestoreTargets](../../app/src/main/java/com/deepseekharness/app/backup/NativeRestoreTargets.java) remap scopes, logical roots, and allowed targets. Project destinations are a private restore area or restore directories within guest home, not arbitrary external paths supplied by the archive. After confirmation, [MaintenanceCoordinator](../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java) stops writers and rechecks the input digest and current targets inside its barrier. [HostDataTransaction](../../app/src/main/java/com/deepseekharness/app/backup/HostDataTransaction.java) then preserves prior state and commits.

Bridge tokens, PIDs, device grants, and old transaction ownership cannot take effect from an archive; the bridge token is regenerated after restore. Native settings accept only allowed fields. A native key is projected only when explicitly selected and encrypted locally. System plugin bytes are rebuilt from the current signed APK, while enable/disable intent is retained. User plugins, executable configuration, and shared patches are quarantined under the restore rules and require separate review before activation.

`DATA_RESTORED_PLUGINS_QUARANTINED`, `DATA_RESTORED_SETTINGS_REVIEW`, `API_KEY_OMITTED`, and `API_KEY_MISSING` have different meanings and cannot all be rewritten as unconditional success. [LegacyBackupImporter](../../app/src/main/java/com/deepseekharness/app/backup/LegacyBackupImporter.java) privately converts historical v1–v4 before the same preflight. Historical compatibility remains bound to actual samples and version records.

## Maintenance, old trees, and failures

Production full-format and complete-reinstall erasure capabilities are retired. Maintenance does not require uninstalling, clearing data, or deleting journals. External workspace maintenance can only disconnect private app references; it cannot traverse, delete, overwrite, or rename external contents, or replay old journaled external writes/deletes. Managed environment updates/rebuilds have their own transactions; an ordinary APK update is not a reason to rebuild user data.

[RetainedCatalogue](../../app/src/main/java/com/deepseekharness/app/backup/RetainedCatalogue.java) and [RetainedDataActivity](../../app/src/main/java/com/deepseekharness/app/ui/RetainedDataActivity.java) provide a read-only inventory, item inspection, export, and restore preflight. An old tree uses its own mappings rather than following old links into current data. Unknown and damaged entries are listed separately, same-name conflicts retain current contents, and one broken record cannot block all other readable copies. Managed predecessor cleanup requires managed proof and per-root digests; it does not derive from the backup “keep three” rule.

If exit is unconfirmed, the input digest changes, a Provider becomes unreadable, or maintenance is pending, retain current state and use native records/recovery. Do not delete journals to bypass a barrier, kill a bare PID, or automatically rerun an unknown write task.

## Source and verification entry points

- One-click and destinations: [OneClickBackupTest](../../app/src/test/java/com/deepseekharness/app/backup/OneClickBackupTest.java), [OneClickBackupLifecycleTest](../../app/src/test/java/com/deepseekharness/app/backup/OneClickBackupLifecycleTest.java), [BackupDirectoryTargetTest](../../app/src/test/java/com/deepseekharness/app/backup/BackupDirectoryTargetTest.java), and [BackupDownloadsPublicationTest](../../app/src/test/java/com/deepseekharness/app/backup/BackupDownloadsPublicationTest.java).
- Scheduling and retention: [AutomaticBackupPolicyTest](../../app/src/test/java/com/deepseekharness/app/util/AutomaticBackupPolicyTest.java) and [AutomaticBackupPrunerTest](../../app/src/test/java/com/deepseekharness/app/backup/AutomaticBackupPrunerTest.java).
- Format and restore: [BackupArchiveTest](../../app/src/test/java/com/deepseekharness/app/backup/BackupArchiveTest.java), [HostDataTransactionTest](../../app/src/test/java/com/deepseekharness/app/backup/HostDataTransactionTest.java), and [RetainedCatalogueTest](../../app/src/test/java/com/deepseekharness/app/backup/RetainedCatalogueTest.java).

These links help select related regressions; this translation did not rerun them. F10 software/actual-APK results remain in the [historical F10 release record](../releases/v0.2.1-rc.2-build190-prerelease-20261020.md). No device backup, Provider matrix, or full historical-data restore matrix was repeated for this documentation work.
