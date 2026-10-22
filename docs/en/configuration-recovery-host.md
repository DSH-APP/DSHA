# Host configuration checkpoints and recovery

> Translation verified: 2026-10-21. Source baseline: the current Chinese host-configuration recovery note, read against the F10 `087724c` maintenance baseline. Historical test statements below retain their original scope; translation adds no execution or device evidence. See the [English README](../../README.en.md) and [release introduction](release-introduction.md) for the final identity and current validation scope.

The separate “Reset configuration” entry uses `NativeConfigurationReset` to save and switch `settings.yaml` and the current private working directory's `.env` as two transaction roots. Its record is `host-backup-operations/<UUID>/config-reset.json`; interruptions use the common host recovery entry. This does not change the fixed scope of the six-file checkpoints below. Credentials are checked and decrypted first; failure does not produce empty configuration over the originals. External directories, configuration symlinks, and targets across filesystems are not forcibly reset.

`StartupRepairs` commands list, prepare, healthy, before, new, restore, and recover execute through the Android host filesystem. Plugin install/remove continues to use its separate existing manager; its failure does not block display of native configuration records.

Only six fixed files are handled: `profiles/web/package.json`, `profiles/web/cordis.patch.yml`, `profiles/web/pnpm-workspace.yaml`, `profiles/web/pnpm-lock.yaml`, `settings.yaml`, and `cordis.patch.yml`. Sessions, attachments, workspaces, and KeyVault are not traversed. Symlinks and unreadable paths return explicit errors instead of being treated as empty data.

New checkpoints live in `files/startup-config-snapshots/<UUID>/`. Files 0–5 store separate bodies, and `metadata.json` records presence, size, and SHA-256; each file is limited to 4 MiB. Sources and private copies are read again after capture, and the small catalog is updated only after verification. Three healthy and three pre-repair checkpoints are kept, with a separate reference to the pre-start candidate. A checkpoint selected during recovery is pinned so rotation of pre-repair copies cannot delete it first.

A healthy checkpoint requires both the matching startup ID and six-file contents unchanged from the pre-start capture. UI changes, source changes, or checkpoint corruption cannot produce a false healthy record. The retired full-backup-by-startup-count behavior has not returned.

Old v1 JSON/base64 checkpoints remain readable, with a 36 MiB document limit and a 4 MiB per-file limit; each item is decoded and verified. They appear as `legacy-healthy-*` / `legacy-before-*` and do not execute Python or arbitrarily deserialize configuration contents.

Changes are recorded under `files/startup-config-operations/<UUID>/` and reuse `HostDataTransaction`: preserve a pre-repair checkpoint, prepare candidates and per-file before/after digests, write switching intent, switch and verify files, then mark finalized. Configuration bodies do not pass through command-line arguments. Native preferences and Keystore are not write targets of this six-file transaction.

An interrupted old `pending.json` is first copied into a fixed private input. If the native transaction is finalized but the old journal has not moved, only journal cleanup is completed; the old configuration is not applied again over later user edits. If only an old state marker remains without a journal, the marker is retained privately and current configuration is unchanged.

The native UI lists fixed files that were absent in the checkpoint and will return to absence on restore. Partial repairs affect only the selected fixed files. Rootfs damage, checkpoint damage, a missing journal, and successful recovery have separate records; creating an empty directory does not fabricate runtime readiness.

The source note's verification points are `ConfigurationSnapshotsTest` and `ConfigurationCrashProcess`. Its process-kill tests use real temporary directories; Windows testing did not cover directory power-loss durability, and Android device verification remained deferred at the user's request. The current operational guide is [configuration snapshots](config-snapshots.md).
