# AGENTS.md — English contributor and agent guide

> Current publication identity: **`dsh.android.client`**, retaining version/code190, E7E3 and all features. This supersedes historical package rules below. Old `com.dsh.client` data are separate; use backup export/restore to migrate. This publication is authorized for DSH-APP/DSHA and the existing private maintenance repository. [Current record](docs/en/releases/v0.2.1-rc.2-build190-package-rename-20261021.md).


Current F13 frozen inventory: 2215 source members / 200 Markdown documents; 1029 adopted Java files. Distribution/format proofs and JUnit execution counts are separate. The fixed packet is 738,101,893 bytes, SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`. Originals expand directly at project paths, not as a nested packet. These are not the complete source ZIP's size or digest.

> **F13 `0.2.1-rc.2-20261021.2107-dsh0.2.1-rc.2` / code190.** Directory-service composition, attachment-resolver disposal and recovery after transient background proof failure are fixed. Standard 150 selected classes/952 JUnit cases (3 skipped); Low 150 selected classes/952 cases (76 skipped). Both have zero failures/errors. Release Lint has zero errors and Standard/Low warnings 249/162; eight actual-APK gates passed. Original failures, corrections and negative controls remain separate; this is not one continuous green run. No device/ADB, real-account/model or new endurance acceptance. [Associated Release target](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.2107) · [Scope and evidence](docs/en/releases/v0.2.1-rc.2-build190-fix13-20261021.md). Actual remote availability is established by the Release and independent receipt.

Published F12 retains its original identity: [immutable English record](docs/en/releases/v0.2.1-rc.2-build190-fix12-20261021.md) · [original Release](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.1413). F11 remains an unpublished local checkpoint.

> This is the complete English maintenance guide. Final current identity and software scope come from [README](README.en.md) and the [release introduction](docs/en/release-introduction.md). The F11 figures below describe a previous source snapshot, not publication or device acceptance of subsequent work.

> Previous F11 snapshot: `0.2.1-rc.2-20261021.1157-dsh0.2.1-rc.2` / code 190 fixed LAN, official Web login, the first-use notice, plugin provenance, and host recovery, and added 21 topic documents. Both variants ran 143 classes / 881 JUnit tests each with zero failures/errors (Standard 3 skips, Low 76). Release Lint had zero errors and 249/162 warnings; eight actual-APK gates passed. Original failures, reruns, and host-port contention limits remain separate. That work excluded device/ADB, real-account/model conversations, and endurance acceptance. The [historical F11 record](docs/releases/v0.2.1-rc.2-build190-fix11-20261021.md) preserves its scope and evidence. F11 was not uploaded; the prepared 1157 Release URL is not proof of download availability or remote publication. F10 remains historical.

> User decision for build 190 (2026-10-19): use released build 170 / `v0.2.0-rc2` (`8362bba747fcf84c19e436c3dbb594a0fc388a67`) as the source base and selectively migrate global grants, virtual screen, task notifications, and related functions from build 187 (`ac7bf8eda8b46a22f6412b17ec3574bf895b1876`). Preserve production historical data, host v5 backup, and migration chains; do not introduce the experimental fresh-only data/backup architecture. Restore `proot + proroot`, with stable proot for first installation and absent an explicit choice; proroot remains an explicit option. Do not migrate bxroot. Release identity stays `com.dsh.client` / E7E3 / non-debuggable, versionCode 190, base/download filename version `0.2.1-rc.2`, official DSH `0.2.1-rc.2`, Ubuntu base10. Destructive complete-format production entry points and erasure backends remain retired. Never clean an external workspace or replay old transaction writes/deletions there. Standard comes first; retain original historical Low172 and existing release artifacts. The lead agent alone finalizes total assets, descriptors, signed packaging, and delivery. Read the build 170 counts and versions below as base history.

This is DSHA's entry point for AI agents and new contributors, intended to avoid a whole-repository scan. Read [README](README.en.md) first.

**Previous F11 entry snapshot:** 1157/code 190; source distribution 2135 members / 135 Markdown files, Java adoption scope 1023. Its report records tests and actual APK results, while its local receipts bind candidate source and ZIP identity. F11 was not uploaded; its prepared Release URL does not prove remote state. Host recovery adopts only the same active Web. UNKNOWN retains a separate launcher stop barrier. Even if the original launcher remains alive, invalid recovery proof rejects the current page, old DOM, and background-task recovery. Old-function negative controls and new-function positive controls remain separate. Do not reuse those distribution totals as the current inventory.

**Repository facts:** build 170 base/code 190; Java 17/:app/arm64; DSH 0.2.1-rc.2/base10; proot by default with explicit proroot. Fixed packaged startup and managed dsh wrappers remain. Keyboard animations do not call setPadding every frame: a bounded native frame channel checks the complete current identity, Web composition moves only the focused composer, the final frame commits layout, and blur/detach/PiP clears it. Mobile 3.2.1 is pinned to main `10e58ce`, retaining safe server differences without restoring upstream-withdrawn #236. OneClickBackup reuses NativeBackupJobs safe stop/v5/tar.gz/read-back verification. Standard writes Download/DSHA; old Low systems select a directory first. It does not scan external workspaces, export native keys by default, automatically restart, or replay operations. Source freeze, initial test failures/reruns, precise APK assets, and final upload receipts remain separate; browser-mechanism regression is not phone FPS evidence. Complete source ZIPs expand fixed inputs directly. Do not build from the old F-drive build 173 source.

Maintenance entry points: [documentation index](docs/en/index.md), [project handbook](docs/en/project-architecture-handbook.md), [permissions](docs/en/device-permissions-architecture.md), [runtime/data/plugins](docs/en/runtime-data-plugin-architecture.md), [engines](docs/en/runtime-engines.md), [ports](docs/en/ports-and-networking.md), [DNS](docs/en/dns-configuration.md), [virtual screen](docs/en/virtual-screen-architecture.md), [ADR 0003](docs/en/adr/0003-virtual-screen-lifecycle-and-input.md), and [references/provenance](docs/en/references-and-provenance.md).

**Historical build 170 base policy:** mobile UI 3.0.5; third-party plugins auto-commit/enable after checks, with lifecycle scripts and pnpmfile allowed. New exports use password-free tar.gz, while internal automatic copies and old encrypted readers remain. Both variants' 45 related checks and limited Android 13 non-destructive release-APK acceptance are in the [historical release notes](docs/releases/v0.2.0-rc2-notes.md) and [build 170 record](docs/releases/v0.2.21-build170-20261009.md). Current mobile UI and other values follow the later decisions above. Directory counts below are historical indexes.

---

## Directory map — historical build 170 counts, current responsibility navigation

```text
app/src/main/java/com/deepseekharness/app/
├── *.java (root package, 27) Device/services: accessibility, bridge, LAN, overlay, Shizuku/Root, PTY
├── ui/      (69)             MainActivity, Fragments, Activities, dialogs
├── core/    (27)             HarnessController, maintenance barrier, backup tasks, diagnostics
├── runtime/ (18)             ProotBootstrap, ContainerRuntime, WebProcessManager, installation
├── backup/  (62)             v5 encrypted backup, restore, migration, historical formatting, automatic backup
├── vscreen/ (10)             Channels, preview, input, accessibility bridge
├── recovery/(4)             Independent emergency DSH lifecycle, separate from the main environment
├── bridge/  (3)              AppBridge contract, AdbBridge, LAN access
├── data/    (3)              KeyVault (Keystore), portable settings, export
└── util/    (118)            Pure logic, no Android API imports; unit tests required
```

### Root package (`com.deepseekharness.app`): devices and system services

| Class | Responsibility |
|---|---|
| `HttpShellService` | **3090 bridge**, historically about 105 KB and the largest class: 24 `/exec` / `/app/*` endpoints, token-gated |
| `DshaAccessibilityService` | `uiDump / uiTap / uiTapText / uiInput / uiKey / uiSwipe / uiScreenshot` and virtual-screen forwarding |
| `DeviceBridgeService` | ADB/Shizuku/Root channel selection and keepalive |
| `DeviceShellExecutor` / `DeviceAppInventory` / `DeviceSense` | Device commands (util allowlist), app inventory, sensors/location/flashlight |
| `LanProxyService` / `LanAuth` | LAN proxy `:3081`, fail-closed token authentication |
| `OverlayController` / `OverlayLines` | Streaming overlay: lyric-like AI output and command approval |
| `HarnessService` / `WebPreviewActivity` | Foreground service/watchdog and embedded Web preview (WebView/Gecko) |
| `PtySession` | Termux terminal-emulator JNI wrapper for a real PTY |
| `ShizukuShell` / `RootShell` / `ShellService` / `AdbKeepAliveReceiver` / `BootReceiver` | Channel implementations and boot/keepalive receivers |
| `DshaDocumentsProvider` | File-manager access to private directories, including MT Manager |
| `DangerShellGuard` / `BridgeAskDialog` / `ConfirmReceiver` | Command confirmation through notification/dialog/overlay and agent questions |
| `DshaApp` / `UpdateDownloadService` | Application entry, including early system-language capture; in-app update downloads |

### Layer ownership — read before changing code

| Layer | Key classes | Responsibility |
|---|---|---|
| `ui/` | MainActivity / WelcomeActivity / Launch·Config·Settings·Plugin·Workspace Fragment / WebPreviewActivity | UI and startup gates |
| `core/` | `HarnessController` (historically about 57 KB, orchestration core) | Start/stop dsh and capture authenticated links. **Do not add new responsibilities here**; create focused collaborators |
| `core/` | `ConfigStore` | Sole configuration read/write entry: port/model/workdir/API key |
| `core/` | `InstallRepository` / `MaintenanceCoordinator` / `RuntimeTasks` / `StartupDiagnostics` | Install state, maintenance barrier, running-task locks, startup diagnostics |
| `runtime/` | `ProotBootstrap` / `ContainerRuntime` / `WebProcessManager` | proot command assembly, runtime/BINDS selection, Web stop with sentinel/PID/birth verification |
| `runtime/` | `InstallPipeline` / `ManagedRuntimeAssets` / `RuntimeTrial` | Six-step installation, managed assets, update trial |
| `backup/` | `NativeBackupJobs` / `BackupArchive` / `HostDataTransaction` / `AutomaticBackups` / historical `FactoryReset` | v5 encrypted backup/restore, data transactions, automatic plans, historical formatting |
| `recovery/` | `RecoveryController` / `RecoveryRuntime` / `RecoveryRepairBroker` | Independent emergency DSH, bypassing main-environment migration/startup lock |
| `vscreen/` | `VirtualScreenManager` / `VirtualScreenAccessibility` / `VirtualScreenPreviewView` | App-owned authenticated connection/heartbeat; input includes the latest frame sequence |
| `bridge/` | `AppBridge` contract / `AdbBridge` (TLS 1.3-PSK + SPAKE2 wireless pairing) / `LocalNetworkAccess` | Bridge seams and device channels |
| `data/` | `KeyVault` | Keystore AES/GCM encryption of API keys |
| `util/` | `Constants` / `ShellQuote` / `Query` / `WebProcSel` / `DeviceShellPolicy` / `HttpProtocol` / `BackupScope`, historically 118 | Pure logic without Android imports; **unit tests required** |

---

## Test layout — historical build 170 snapshot

These counts describe the base's historical distribution only. Use the current report/receipt for selected classes/XML, failures/errors/skips, and final software results. Do not derive execution counts from static files. The [historical F11 record](docs/releases/v0.2.1-rc.2-build190-fix11-20261021.md) describes only F11's execution.

`app/src/test/java/com/deepseekharness/app/` historically contained 172 test classes: `util/`112, `backup/`45, `core/`6, `runtime/`6, `vscreen/`1, `ui/`1, root 1 (`LanAuthTest`).

```bash
bash build.sh :app:testStandardDebugUnitTest   # Complete unit-test task
```

Locked invariants that refactoring must preserve:

- `ShellQuote`: POSIX single-quote escaping; malicious values cannot escape.
- `Query`: match each complete parameter name. `indexOf(key+"=")` allowed suffix hijacking and was fixed. An empty parameter differs from an absent one.
- `BackupScope`: partial backups must never be named `DSHA-backup-*`, or old versions may interpret them as full restores and remove configuration/plugins. `dshPaths` and `mergeSubdirs` correspond one to one.
- `WebProcSel`: recognize dsh processes and **never accidentally kill proot/proroot**; killing the container launcher can take the environment/application down with it.
- `DeviceShellPolicy`: default-deny allowlist; accept only a completely recognized single argv, never a user-supplied shell program.
- `HttpProtocol` / `SocketDispatch`: independent header-byte and total-deadline limits; bounded LAN registration, short requests, long streams, and reverse pumps.

---

## Security model

Read the [security model](docs/en/security-model.md) first. Essential boundaries:

- **Device-command allowlisting is always active** (`util/DeviceShellPolicy`). Formatting, partitions, SELinux, and system-property writes are rejected, as are unrecognized commands. Rejection returns `[POLICY_BLOCKED]` with exit 126 and **no “allow anyway” button**. Root/Shizuku/ADB share this policy.
- **Bridge credential guard** (`util/BridgePathPolicy`): `/app/export` and `/app/readfile` reject credential areas such as `.dsh`, `.ssh`, and `.android`, combining string rules with `getCanonicalPath()` against symlink bypasses.
- **Least-privilege defaults**: sensitive ADB, Root, SMS, sensors, location, overlay, and LAN capabilities default off and are revocable. DSHA still runs dsh with minimal configuration.
- **Backup credential exclusion**: bridge-token files are wholly excluded; `.credentials.yaml` removes local connection secrets field by field while retaining user API keys.
- **This is not a kernel sandbox**: containers, plugins, and terminals run under the app's Android UID. The allowlist protects bundled entry points, not arbitrary self-written code. Do not describe this as security isolation.
- Keep known weaknesses in the security model, including `danger-full-access`, historical plaintext backups, and pending signing-key rotation. Do not delete them.

---

## Technical constraints

### Versions and builds

- **Current identity comes from the frozen receipt and app/build.gradle.** code 190 and E7E3 apply to release APKs. 0.1.7-rc2/147 and A3F4 are historical, not current build/signing inputs. See the current scope above.
- `standard`: minSdk 30, system WebView. `low`: minSdk 23, bundled GeckoView 143. Build tasks: `assembleStandardRelease` / `assembleLowRelease`. Low proot/loader/terminal JNI are rebuilt for API 23 (`tools/build-low-proot.py`, `tools/termux-jni/`). **Do not treat Standard proot as an Android 6 executable.**
- Offline rootfs (`assets/offline-rootfs.bin`) is **not committed**; preparation scripts generate it. `offline-rootfs.layout=split-runtime-v1` marks separate Ubuntu and `dsh-runtime.bin` packages; builds check both digests.
- `tools/dsh-runtime/package-lock.json` locks new dsh dependencies. `tools/prepare-runtime-descriptor.py` generates independent managed-contract descriptors; the APK version is diagnostic context only. Update managed trees within the same base while keeping personal data in place. **UI-only APK updates do not replace the environment; ordinary dsh updates do not increment the Ubuntu base.**
- `tools/ubuntu-tools/packages.lock.json` locks offline curl/git/certificates (`tools/prepare-ubuntu-tools.py` generates `ubuntu-tools.bin`). Web ES compatibility dependencies are locked in `tools/web-compat/` (`node tools/prepare-web-compat.mjs`). Generated files are not committed, and builds verify digests.
- Chinese/English UI messages come from `tools/i18n/messages.json`, with a generated Java dictionary. Preferences: `system` (default), `zh`, `en`. **Capture system language at the earliest process stage** (`SystemLanguage.initialize()` before any `Locale.setDefault`), or “follow system” can lock itself after a switch. Language changes recreate UI without stopping terminals/Web; do not replace user input or original commands automatically.
- Old WebView polyfills for `AbortSignal.any/timeout` and `crypto.randomUUID` must precede application scripts in managed HTML and cover document-start and Worker execution.

### Data and backup

- **Automatic backup defaults on**, per the 2026-09-17 requirement overriding older off rules. `AutomaticBackups` supports daily time, 1–168-hour intervals, and after-stop modes (`util/AutomaticBackupPolicy` has tests). Defer while running rather than force-stopping Web/terminals. Retain the latest three verified automatic copies; do not automatically delete manual or unknown records.
- v5 user exports are password-free `DSHA-data-v5-<UUID>.tar.gz` containing DSHADATA integrity payloads. Internal automatic copies remain encrypted. Historical encrypted originals still require their password and complete authentication. Native API keys are excluded by default. Verify integrity/authentication before preflight and write only private numbered slots. User archives cannot activate local authorization; managed scripts, executable configuration, and unknown plugins are isolated first.
- Do not infer data format from APK/dsh versions. `DataFormatEvidence` keeps unchecked data unknown. A sender's claimed verified format does not authorize deleting originals.
- Upgrade maintenance may clean regenerable caches and excess copies while idle, preserving at least two healthy plan-matching copies. Never delete modified/extra-file originals. A verified rebuild establishes `retired-proof.json` before cleaning `previous-linux`.
- Production complete-format entry points/erasure backends are retired. Do not restore old cleanup. External-workspace maintenance disconnects private references only, without traversing, overwriting, or deleting its contents. Legitimate cache cleanup still requires controlled private targets and NOFOLLOW checks.
- Authorized upgrade acceptance uses original-package, same-signature, non-debuggable release APKs and non-destructive checks. Do not inject force-kill, deleted Bash, or bad-plugin faults into production data.

### Runtime and updates

- New managed updates use `ManagedRuntimeTransaction` / `HostDataTransaction`. A real isolated `RuntimeTrial` checks static assets/native modules, actual port/authentication, storage/session write-and-reopen, browser/backend handshake, and guest exit before commit. Receipts live outside rootfs and bind file digests. Keep at least the direct healthy predecessor.
- `RuntimeTrial` records actual `runtimeMode`. If selected proroot explicitly exits before authentication without plugin faults, `WebRuntimeFallback` may try proot once. **Do not report proot success as native proroot success.** Static checks select proot first; slow startup does not trigger retries.
- `RuntimeTrialRecords` rotates confirmed-closed scenes only: latest three successes and five failures. Do not automatically delete unclosed/abnormal directories or impose a permanent cumulative-trial cap.
- Readiness parses the real `/bin/bash` ELF and loader path. Use lstat for executable bits (`canExecute` is unreliable under Android W^X). A damaged system goes to environment repair rather than repeated configuration safe-start.
- Production plugin policy permits unlocked dependency resolution, lifecycle scripts, and pnpmfile. Links/local packages/updates auto-commit and enable after format/path/actual-dependency/digest/transaction checks. Preserve explicit user disabling and safe-mode intent; do not restore the native review gate. `plugin-transactions.py` records candidates/originals; uncommitted journals remain in maintenance gates.
- Separate system/user plugins. System source is not user-backup content; current APK managed proofs reconstruct it. `.disabled` intent wins over conflicting old entities.
- `NARB_DISABLE_NATIVE_CACHE=1` loads native extensions directly from bundled directories; default caching initially dangles under `--link2symlink`.

### Web, bridge, and terminal

- Main Web starts through the current managed dsh wrapper and fixed packaged entry with the common RuntimeTools environment. Default automatic port is `--port 0`, with actual port recorded after authentication. Explicit manual settings follow current selection policy. See [runtime mechanisms](docs/en/runtime-data-plugin-architecture.md), rather than copying an old fixed 3080 command. Execute proot from nativeLibraryDir, not filesDir as a W^X workaround.
- In the inspected previous snapshot, `WebPortPolicy.choose` selected fixed/local-backup/dynamic ports before startup. Backup memory is local only and does not migrate. That snapshot did not wire production `retryBindRace`; helper/tests alone did not establish automatic bind-race retry. Authentication, keepalive, and LAN use the actual port, never a port-inferred PID. Check the maintained [port page](docs/en/ports-and-networking.md) and final receipt for subsequent changes.
- Waiting for Web authentication has no forced termination deadline (60 seconds is a notice). Stop/maintenance share `WebProcessManager` PID and birth-identity checks.
- Feature-detect bridge protocol via `/app/version` (`BRIDGE_PROTOCOL=2`); plugins use `>=`, not `==`. Command query-string budgets must accommodate existing 8192-character commands.
- LAN body boundaries follow method, status, and explicit framing. Only a validated 101 WebSocket handshake switches to a tunnel. Do not strip hop-by-hop headers and still send mismatched encoded bodies.
- Native fork/exec handshake registers PTY birth identity: the child reads its own stat, the parent confirms, then exec occurs. Do not return to a single Java read after launch. PTY/simple terminals own separate tabs/sessions and retain processes through page/rotation/language changes. Closing a tab removes it only after confirming that session's exit. Session IDs increase permanently; display numbers independently reuse the lowest gap.

### Emergency, virtual screen, and devices

- **Independent emergency DSH:** `recovery/` bypasses main-environment migration/data binding/global startup lock. `tools/recovery-runtime/lock.json` independently pins archives. Emergency profiles expose only five controlled repair tools; a “native safe profile” cannot replace an independent root. `RecoveryRepairBroker` HTTP cannot confirm writes. Native confirmation binds candidate, source digest, and data generation, then uses the stop barrier and host transaction.
- Emergency asset deduplication: `recovery-asset-locations.json` maps physical locations only. Share packaged bytes only if the main archive SHA exactly matches the independent lock. Extracted roots remain independent; rescue does not read the main rootfs as a dependency.
- **Virtual screen** (Standard, API 30+): App owns managed Core. Raw coordinates require the latest observed frame; semantic node/editor and anchored actions have distinct identity/target checks. Do not require static pixels or silently fall back between modes. UNKNOWN is never replayed. See [architecture](docs/en/virtual-screen-architecture.md) and [ADR 0003](docs/en/adr/0003-virtual-screen-lifecycle-and-input.md).
- **Microphone:** declare both `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS`. `BrowserMicrophone` requests permission only on Web request, not ordinary startup. Allow pure audio only from the current local page; reject camera/screen audio. An old callback cannot approve a new page.
- **Device-acceptance experience:** `uiautomator dump` suppresses other accessibility services. Screenshot grants bind DSH generation and expire on stop/disconnect/revocation. Store screenshots in app-private Pictures/DSHA, without all-files access. PiP can cover lower controls; automation must check actual visible region/orientation. Screenshots/UI reads must not put sensitive values in public evidence.
- Register `rikka.shizuku.ShizukuProvider`. Standard uses 13.1.5, Low 12.2.0; do not conceal minSdk 24 with overrideLibrary. Validate manager UID, Binder descriptor, one-shot request, and timeout in callbacks.
- Built-in `dsh-web-mobile` is 3.2.1 at upstream main `10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4`. `tools/apply-mobile-client-patches.mjs` manages local changes. Do not restore withdrawn upstream viewport changes or reapply rc1 navigation patches now owned upstream.

---

## Startup contracts

- `welcomed == false` → `WelcomeActivity` → Start → `MainActivity`. Main checks `welcomed` before entry; otherwise return to Welcome.
- Normal execution checks `.offline-extracted` and complete environment identity. Maintenance may allow a limited Main for configuration/logs; **it must not forge readiness markers**. Terminal/Web/plugin execution still passes environment gates.
- Startup failure enters native `StartupRecoveryActivity`; waiting for authentication alone is not failure. The latest five redacted startup records live independently in private storage.
- Compatibility retry occurs once only after confirmed proroot exit, before authentication, without explicit plugin failure. **Timeout cannot trigger it.**
- Host `ConfigurationSnapshots` stores each file's content outside rootfs, keeping three healthy and three pre-repair snapshots. A committed old journal must not overwrite later configuration edits.
- DocumentsProvider preserves existing opaque `root` and `linux/ubuntu/...` IDs. Resolve guest absolute links within rootfs; deleting a link does not recurse into its target. Validate an opened file descriptor before truncating a write target.
- SMS queries are a separate sensitive capability: strict `content query` for the current Android user only. Native preauthorization defaults off, is revocable, and is not restored from system backup.

---

## Known traps inherited from the original implementation

- **Stop uses PID files plus birth identity, not reverse port lookup.** Non-root `/proc/net/tcp` reads may silently be empty, and `/proc` can use hidepid. Startup writes `echo $$ > /root/.dsha-web.pid`, then `exec node`. Stop writes `/root/.dsha-stopped` first so the watchdog exits instead of instantly resurrecting. Check old-PID reuse with kernel `kill(pid,0)` and a second birth-identity check, never force-kill by bare PID/port/name.
- **SELinux blocks `link(2)` in app-private directories.** proot needs `--link2symlink`; rootfs `.l2s` for `PROOT_L2S_DIR` must bind to the same absolute host path, or dpkg hardlink chown/stat may report missing files.
- **Two signing keys have different roles:** historical E7E3 signs APKs; a separate keystore signs incremental-update manifests. Never mix them.
- Android `FileInputStream(FileDescriptor)` does not take ownership. Host backups explicitly transfer ownership through ParcelFileDescriptor auto-closing streams.
- Data maintenance closes PTY/simple terminals before the RuntimeTasks barrier. A proroot terminal verifies/reaps the guest in its own current session; terminating only the launcher cannot release its work lock.
- Maintenance success text is not readiness proof. Stay on the native page if unready, without automatic navigation loops. Do not represent data-maintenance busy state with general plugin-query/terminal locks.

---

## Delivery and acceptance

- 2054, 2330/F9, and 0140/F10 keep their own published historical identities. The 1157/F11 candidate remains an unpublished local software checkpoint. Current work uses the existing `ym2025szz/DSHA-prerelase` private repository; final software/precise APK results come from the current record, with attachment/remote state verified by Release and an independent external receipt. The local Standard target remains `F:\DSHA_RESTART\release\0.2.1-rc.2\dsha-0.2.1-rc.2.apk`; old bytes/tests there cannot substitute for current final proof.
- All device operations are paused: no install, start, stop, or conversation. Software checks inspect actual package/code 190/E7E3/`debuggable=false`; passing them does not authorize phone actions.
- Git retains necessary notice/lock-covered small JNI/vendor/interpreter dependencies, 27 historical build sources, and one website fixture. Do not claim all vendor native binaries reproducible. Large generated archives stay out of Git. Complete Release source ZIPs additionally contain original large inputs checked by ci-assets.py against each SHA, expanded into project paths for recipe builds after toolchain setup. No separate bundle is needed, and all inputs cannot be reverse-recovered from an APK. [CONTRIBUTING](CONTRIBUTING.en.md) lists nine explicit audit-JSON paths. Do not upload keys, user data, device images/logs, or historical scenes. Put ZIP digest only in the external Release body.
- Describe acceptance scope accurately. Local checks do not establish Android 6/7,16 KiB, or all external-model services. Standard/Low and virtual-screen claims follow actual device evidence.
- This work adds only its authorized prerelease/software scope. Historical fix 2/fix 3 remain unchanged. Do not update the original repository/Release/website feed or deploy the website.

---

## Coding conventions

- Write comments/UI strings in Chinese; use Chinese commit messages with a `type:` prefix explaining why.
- Give each collaborator one responsibility. Put pure logic in `util/` with tests, and preserve historical SharedPreferences keys.
- Match existing style: guard risky operations with try/catch, degrade gracefully, and show useful failure toasts.
- Center native buttons/options consistently, including font padding. Ordinary cards, primary buttons, and text states share theme resources; no separate gradients or hardcoded colors creating mismatched frames. Layout changes run style acceptance through `LayoutAuditInstrumentation` in Chinese/English, day/night, short screens, and 1.3× text.
- App dialogs use `DshaDialogBuilder`. Custom content must scroll on short screens/large fonts. Render application state through `UiStateText` at display boundaries rather than caching a language.
- DNS defaults to `auto`: Node dual-stack lookup retries IPv 4 once only for `EAI_AGAIN` / `EAI_FAIL`, without replaying HTTP or downgrading explicit IPv 6. Configuration also offers `ipv4` (glibc `no-aaaa`) and `native`. Web/plugins/shell/PTY share RuntimeTools preload environment.

---

## Documentation and workflow references

| Document | Content |
|---|---|
| [BUILD](BUILD.en.md) | JDK 17/SDK 37/NDK 26/Python 3.9+, build, signing, publication/delivery |
| [Security model](docs/en/security-model.md) | Security boundaries and known weaknesses |
| [Plugins](docs/en/plugins.md) | Plugin installation, packaging contracts, validation scope |
| [Historical Low guide](docs/android-low.md) | Low compatibility differences: API 23 rebuilds, desugaring, old-system permissions |
| [Historical releases](docs/releases/) | Release notes and device acceptance, with each artifact's original attribution |
| [CHANGELOG](CHANGELOG.md) | Complete historical change record |

## Historical build 190 fix 1 supplement — 2026-10-19

All limited acceptance below belongs only to those historical APKs. These paragraphs translate the historical supplement; pending status refers to its original document freeze, not the current filesystem.

The local dated fix 1 versionName was **`0.2.1-rc.2-20261019.2352-dsh0.2.1-rc.2`**. versionCode 190, `com.dsh.client`, and download filename `dsha-0.2.1-rc.2.apk` were unchanged. Release APK SHA-256: `5b1d8f5d6f71c7527f48d336013c74c6a2bf9e4f8811df6d416337deaac1ac38`, 314,189,001 bytes. `fix1-dated-version-final` passed in 91.39 seconds. Both variants ran 511 tests with zero failures/errors (Standard 508 pass/3 skip, Low 481 pass/30 skip); Lint 0 errors, 253/165 warnings. runtimeId remained `fa4759062b5cc65fea8bdff170fc4554083555f598d42596f57ad023d0442da8`. At 15:53:39 UTC, after normal stop, the exact same-signature APK replaced the installation and was delivered early under the regular filename. Installed digest matched; UID/first-install time remained; f744 moved into history.

At 15:55:04 UTC this APK actually reached DSH ready/proroot/0.2.1-rc.2/environment READY, with **backend authentication ready in 7.7 seconds and no fallback**. At 15:55:29 UTC the same existing acceptance session remained readable.

The sixth real DeepSeek round first returned status STOPPED/cleanup false, then created an empty portrait screen (1,330 ms), checked active status on the same Core, and closed it precisely (964 ms). Raw tool details confirmed ok true, EXITED, displayReleaseAcknowledged true, STOPPED, and cleanupPending false. Requested 720×1280 was not an exact-resolution pass: the API accepted orientation only and actual size was 1008×1792. The model invoked no other app/input/screenshot/personal-read tools.

At 16:00:51 UTC, closing the modal then Back returned to Main and reentered the same session. Home entered real PiP at 16:02:53; the physical launcher opened Main plus original PiP at 16:06:11; the original task/session restored fullscreen at 16:07:51. After normal Stop in the small window, the old Web task disappeared at 16:10:17, DSH was stopped/environment READY, only host remained under the app UID, HarnessService was absent, and independent DeviceBridge remained. At 16:11:48 proroot authenticated in 3.2 seconds without fallback; at 16:12:57 a different new task restored the same session fullscreen 1440×3200/PiP false, without residual small-window geometry.

Browser-ready logs were 46.0 seconds/96.1 seconds including human dwell, separate from backend 7.7 seconds/3.2 seconds; they were not end-to-end startup benchmarks.

At 16:18:49 UTC temporary ON was revoked to OFF/CACHE_VERIFIED/rev 3. At 16:21:19 DSH stopped normally, no Web/Harness remained, only host plus independent DeviceBridge. A stopped same-SHA upgrade at 16:21:31 retained UID/first-install time. A new host process read OFF/NONE/rev 3 at 16:24:09, still without Web/Harness. Operations stopped once the user switched to another app; the 16:25 screen was excluded from DSHA acceptance. No final diagnostic export was added. Only the listed local Standard/WebView paths were accepted.

The historical planned source ZIP was `artifacts/source/build190-fix1/dsha-build190-fix1-source.zip`, and planned final receipt `artifacts/deliveries/build190-fix1.json`. At that document freeze they still awaited generation/verification, so existence was not claimed. Old-SHA results were not transferred to that APK.

Previously delivered f744 was `0.2.1-rc.2 / 190`, E7E3, non-debuggable, SHA-256 `f744bbb21b5df4c5eee3e72ee8321bfb5994d852c08b98d65b9b82d65c9c3738`, 314,188,808 bytes. Both variants ran 511 tests: Standard 508 pass/3 skip, Low 481 pass/30 skip, zero failures/errors; Lint 0 errors/253/165 warnings. Actual APK checks passed: 75 installs/147 assets/371 launchers, same runtimeId `fa4759062b5cc65fea8bdff170fc4554083555f598d42596f57ad023d0442da8`. These are f744 history only. Exact same-signature replacement and early delivery completed. After an authorized real reboot and boot-change verification, Main was environment READY/Web stopped, global ON/NONE/revision 2 reopened, and native ADB connection was verified. Complete MCP functionality did not pass, so this was not full-device acceptance. The dated APK later had its own proroot readiness and empty virtual-screen creation/close evidence for the listed limited paths. Endurance/full matrices remained uncovered; source ZIP/final receipt were still pending then, with no GitHub/website/Release upload.

- That work fixed version/registry consistency, rc1 migration provenance, wrapper compatibility, BrowserOrigin private root/official empty drafts, Harness retirement, proroot entry/shim, final LF in grant XML, mixed-task navigation, and MCP error mapping. Production data, same-generation pages, unknown-exit protection, and independent DeviceBridge remained. Host boundaries including 37 grant, 41 navigation, 21 native-toolchain checks are in the [historical fix 1 report](docs/releases/v0.2.1-rc.2-build190-fix1-20261019.md).
- Historical 692: limited Android 13 OFF repair. Android 16 reproduced 9ee Back/launcher failure; 692 reached READY/proroot 6.1 seconds/no fallback and passed limited Back/Main/physical-launcher/same-session fullscreen paths. After native OFF/revision 1 recovery, same-SHA replacement/reopen at 13:10:31 UTC read ON/NONE/revision 2. Real read-only model traces produced printf,/app/device,/app/plugins(163/0/0) results; authorization failure and the CLI missing-profile correction remain. MCP status was NOT_RUNNING; a human answered FAIL to ask-user and submitted; later create returned JOURNAL_UNAVAILABLE. This was not virtual-screen acceptance.
- f316 fixed NioInstallationIdentity FileStore rejection while retaining NOFOLLOW/CREATE_NEW/Unix mode `0600` and original identity; 11 targeted tests passed. Actual round 5 passed the journal stage but reached ADB_CONNECT_FAILED→START_UNKNOWN, cleanupRequired=true, without Core identity; the old barrier remained. Later native ADB verification did not prove that earlier work never executed. Around 21:20 local, parallel user pairing/freeform→fullscreen fragments were not assigned a single cause.
- f744's four NOT_SENT fixes accepted atomic withdrawal only for a helper that actually exited, complete output, and an exact valid uncommitted ticket. Commit/withdraw share a lock; committed/lost-receipt/mismatched/expired/truncated/exception cases remain UNKNOWN. The manager still checks epoch/state/journal commit, does not clear f316 UNKNOWN, and does not replay. Agent-targeted 131 tests(1 skip) had zero failures/errors, with Python 8/29 tests passing; the latter two printed OK without saved raw logs then. Independent four-case review passed 21/21; device effects awaited acceptance.
- For the old UNKNOWN without Core identity, the lead first stopped DSH normally and confirmed no app-owned guest, obtained the user's **explicit reboot authorization for that one occasion only**, then actually rebooted around 15:03 UTC, confirmed boot changed, and replaced f744. This is not general authorization for automatic reboot or clearing old barriers. f744's limited Main/grant/ADB results did not prove complete MCP. The dated APK had limited navigation/stop-generation/OFF cross-process/final-stop proof but no new final diagnostic export. 692 partial results and 9ee's 115.042-second default-proot cold test retain their own old SHAs. Low190 had no device acceptance, and no new endurance/all-model/all-ROM claim was added. Local delivery depends on actual generation/digest verification of `artifacts/deliveries/build190-fix1.json`; no GitHub publication was authorized then.
