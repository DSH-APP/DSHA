# DSHA 0.2.1-rc.3-20261025.2100-dsh0.2.1-rc.3 / build198

- Merge Sunset-3090-Q plugin management improvements, reducing redundant refreshes while retaining confirmed operation state.
- Fix confirmation policy changes leaving later screen reads permanently paused. Explicit denials, user pauses and Android permissions remain effective.
- Remove PC workspaces, QR pairing, PC connectors and relay components. Keep phone control and local/LAN access.
- Give native and web content one top safe-area owner, keeping the status bar visible. Retain ten languages and system-follow mode.

## Historical notes from the separate Sunset test branch (not acceptance of this merged build)

### DSHA 0.2.1-rc.3-20261026.0735-dsh0.2.1-rc.3 / build198

- The `dsh-auto-review` alias pair that shares one card with the native plugins page now reports the official version actually loaded by its patch: the value is replaced only when the module pinned by `peerDependencies` really resolves and the installed version equals the pin. Unresolved or mismatched pins keep the wrapper version so the native side still reports unconfirmed. Other wrappers such as `dsh-computer-use-android` keep their own version in the list and are not substituted. This fixes the Auto review card staying at “load unconfirmed” forever.
- Native plugin identity normalization is centralized in `PluginIdentity`; item `stableKey` and runtime projection share one alias rule instead of maintaining it twice.
- Web activity snapshot reads add `BackupFileSystem.currentSnapshot`: only the bytes behind the opened descriptor are checked, tolerating the writer publishing a new snapshot by rename every 2 seconds. The Android implementation keeps NOFOLLOW and the size bound but no longer requires the path inode to stay unchanged during the read.
- The runtime evidence freshness window grows from 10 seconds to 30 seconds (the writer heartbeats every 2 seconds and generation/instance are checked separately), so a long task occupying the event loop no longer turns a healthy heartbeat into “unconfirmed”.
- `PluginRuntimeProjection.rejection` returns reason codes; `PluginRepository` records a `PLUGIN_RUNTIME` line only when the conclusion changes (missing identity, generation mismatch, read failure, expired snapshot, or remaining unconfirmed plugins), instead of failing silently.
- New regressions: JVM snapshot reads under atomic replacement and the byte bound; projection rejection codes; wrapper bundles reporting the pinned official version, including the negative branch where the pin and the installed version differ.

Version code moves to 198; package name, historical signature, regular file name and the bundled DSH rc.3 are unchanged. This branch runs Standard only; this round is verified by CI compilation and unit tests, with no device acceptance.
