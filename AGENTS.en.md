# AGENTS.md — English contributor and agent guide


> **Status**: `0.2.1-rc.5-20261110.1700-dsh0.2.2-rc.1` / build356 / `io.github.sunsetrne.dsha` / DSH-A-Su / single Standard.
> Main branch `main`; Android 11+ (API 30) / arm64; proot by default, proroot optional; bundled DSH 0.2.2-rc.1.
> The island feature is folded into the regular package; the old low/GeckoView line and the separate island package are removed; signing uses the Mod-owned certificate (see BUILD.en.md).

DSHA's entry point for AI agents and new contributors — read [README](README.en.md) first, then this file, so you do not need to scan the whole repository.

**Repository facts**: Java 17 / `:app` / arm64; mobile UI 3.3.5; proot by default with optional proroot; pinned offline inputs are restored by `tools/ci-assets.py`. Build, signing and gates are documented in [BUILD.md](BUILD.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

Maintenance entry points: [documentation index](docs/en/index.md), [project handbook](docs/en/project-architecture-handbook.md), [permissions](docs/en/device-permissions-architecture.md), [runtime/data/plugins](docs/en/runtime-data-plugin-architecture.md), [runtime engines](docs/en/runtime-engines.md), [ports](docs/en/ports-and-networking.md), [virtual display](docs/en/virtual-screen-architecture.md), [ADR0003](docs/en/adr/0003-virtual-screen-lifecycle-and-input.md), [provenance](docs/en/references-and-provenance.md).

**History**: build and acceptance receipts for each version live in [CHANGELOG.md](CHANGELOG.md) and [docs/releases/](docs/releases/); directory counts below are historical indexes — trust the code.

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
| `DeviceShellPolicy` / `DeviceShellExecutor` | Parse and guard managed device commands before execution; approval does not override explicit rejection, including through Root |
| `BridgeAskDialog` / `ConfirmReceiver` | Native business questions and security-notification replies; global confirmation does not answer business choices |
| `DshaApp` / `UpdateDownloadService` | Application entry, including early system-language capture; in-app update downloads |

For Root maintenance, use [permission architecture](docs/en/device-permissions-architecture.md) and the [pinned upstream review scope](docs/en/references-and-provenance.md): nine su candidates and metadata checks do not identify a manager or prove compatibility with every version. The 65-second budget covers result collection only. Review of `140c7ba` on 2026-10-22 found that recursive READ does not validate nested symlink targets, leaving an SMS-protection implementation gap. Do not describe this as documentation-only or complete bypass prevention. Record the unresolved state, regressions and device evidence separately. Historical `DangerShellGuard` is absent from the production call chain.

### Layer ownership — read before changing code

| Layer | Key classes | Responsibility |
|---|---|---|
| `ui/` | MainActivity / OnboardingActivity / ExtractActivity (maintenance detail) / Launch·Config·Settings·Plugin·Workspace Fragment / WebPreviewActivity | UI and startup gates; first deployment runs in the background inside onboarding with a deployment capsule; over-the-top updates open the full-screen maintenance page |
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

- **Current identity comes from `app/build.gradle`, `ci/release-identity.json` and [CHANGELOG.md](CHANGELOG.md).** build356 / DSHA revision `0.2.1-rc.5` (bundled DSH 0.2.2-rc.1) / `io.github.sunsetrne.dsha` / DSH-A-Su / single Standard. Earlier code/build numbers, the old `dsh.android.client` package and the historical E7E3 signature are records only, not current build or signing inputs.
- `standard`: minSdk 30 (Android 11+), system WebView, arm64-v8a. It is the only build line; the task is `assembleStandardRelease`. The `low`/GeckoView line and legacy proot/loader are deleted (`assembleLowRelease` no longer exists), so **Android 10 and below are no longer supported**. The test variant only changes the package via `-Pdsha.sunsetTest=true` (`io.github.sunsetrne.dsha.test`).
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
- Built-in `dsh-web-mobile` is 3.3.5 (host 0.2.2 adaptation) at upstream main `646fe837440f9939228bdba0024d407eaac01128` (2026-11-08; release tag v3.3.5 = `14ecfccf5a6acb69e3a84229dee328d31801306f`, plus three post-tag README and plugin-page-host-layout fixes). It **follows upstream**; `tools/apply-mobile-client-patches.mjs` keeps only the host-integration minimum (server auth / safe area / `DSHA_GEAR_FIT_V1`) plus file transfer and locales. Session-input V4, phone search, shortcuts visible-band, font-family row and backdrop press tint were removed; do not reapply them. It is default-enabled again from build336 (device over-the-top acceptance pending).

---

## Startup contracts

- `welcomed == false` → `OnboardingActivity` (welcome → purpose → grants → done) → `MainActivity`. Main checks `welcomed` before entry; otherwise return to onboarding.
- Normal execution checks `.offline-extracted` and complete environment identity. Over-the-top updates, an unready environment, unfinished maintenance transactions and unclosed isolated-trial records all open the full-screen runtime maintenance page (`ExtractActivity`), which auto-starts the same candidate at most once; the limited Main is entered only after an explicit choice there, **the limited entry writes no readiness marker**, and Terminal/Web/plugin execution still passes environment gates.
- **After an over-the-top upgrade, if the installed runtime is not the current candidate, the user must see the full-screen maintenance page with a one-tap update**: `isEnvironmentReady()` (compatible + health receipt) does not mean "nothing to do", and a patch-level update still requires `RuntimeDescriptor.latest`. Auto-start may be blocked by an unresolved Web session, but the entry must not disappear. **Built-in plugins must not depend on whether a host widget exists**; use only their own attributes plus measured geometry. See [over-the-top upgrades and maintenance](docs/en/overwrite-upgrade-and-maintenance.md).
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

- Changes for the current version are in [CHANGELOG.md](CHANGELOG.md); build and acceptance receipts per version are archived in [docs/releases/](docs/releases/) and `artifacts/`.
- **External PRs and external merges require a dedicated line-by-line review**: a dedicated AI review session reads the **entire diff** (never samples, never CI-only) and checks security boundaries, missing logic, test coverage, and repository invariants. The only outcomes are APPROVE / REQUEST_CHANGES; a rejection must include `file:line`, the problem, the fix, and P0/P1/P2. Green CI does not mean mergeable; a conflict must be rebased and generated-file differences re-reviewed. The review session must not merge the PR or push the author's branch; merging still requires explicit user approval in the main session. See [the external PR and external merge review process](docs/en/external-pr-review.md).
- Acceptance scope is stated as measured: local verification is not stretched into "all OS versions, devices or external model services"; Standard / Low and virtual-display capabilities are claimed only against actual device evidence.
- Signing keys, user data, device screenshots and logs never enter the repository; installs, device operations and releases follow the authorization of that round.

## Parallel session convention

Several sessions often change this repository on the same machine at once. Merging, releasing and cleanup follow each worktree's session declaration; never guess from idle time that a session has finished.

- **One session, one worktree**: start each task with `git worktree add -b <type>/<name> .agents/worktrees/<type>/<name> origin/main`, based on the current `origin/main` (not on a side branch that has not reached main). Do not change code in the main checkout `/root/DSH-A-mod`; use it read-only. `/root/dsha-build` is the `local-ci` sync mirror, not a workspace.
- **`read` the worktree path before changing a file**: DSH's `fs-observation-policy` only accepts the same absolute path read by the `read` tool in the same session; otherwise `edit` or an overwriting `write` fails with `file has not been read`. Inside a worktree, read and edit `.agents/worktrees/<type>/<name>/...`; reading the same-named file in the main checkout does not count, and neither do shell reads (`cat` / `sed` / `grep`). Re-read after a new session, subagent, takeover or resume. Authorization only checks the version, so a small `read` window with `offset`/`limit` is enough. Re-`read` a file after a shell write before using `edit`.
- **Declare on creation**: write `.ACTIVE-SESSION` at the worktree root. It is listed in the shared `.git/info/exclude`, so `git add -A` never commits it; do not declare through tracked files such as a design README. Keep the Chinese field keys from [AGENTS.md](AGENTS.md) so every session can read them: `状态` status, `分支` branch and base commit, `会话` full session id, `开始` start (UTC), `更新` last update (UTC), `内容` one-line task, `当前阶段` current stage, `占用文件` files owned, `合并` `不可合并` (not mergeable) or `可合并（已提交 <commit>，…）` (mergeable), `后台任务` background jobs with log and output paths (or `无`).
- **Update every turn**: refresh `更新`, `当前阶段`, `合并` and `后台任务` before each reply to the user. Waiting for user confirmation or device checks still counts as active.
- **Hand back when done**: when the task is finished (or abandoned), rename the file to `.SESSION-DONE`, set the status to ended, and state in `合并` whether it may be merged and at which commit; for abandoned work write "abandoned, do not merge".
- **Merge / release sessions**: merge only worktrees whose `.SESSION-DONE` is marked mergeable. Leave any worktree with `.ACTIVE-SESSION`, or with no declaration, untouched and ask the user. After merging, append one line `已合入 buildNNN（<commit>）` to that `.SESSION-DONE` without changing other fields — **only after the push succeeded and `git merge-base --is-ancestor <commit> origin/main` is true**; while queued, write `待推送 buildNNN（<commit>）`.
- **List all declarations**: `for f in .agents/worktrees/*/.*SESSION* .agents/worktrees/*/*/.*SESSION* .ACTIVE-SESSION; do [ -f "$f" ] && { echo "== $f"; sed -n '1,10p' "$f"; }; done`

### Shared on-device environment (DSH and every session live in one app process)

- **Run Gradle only through `dsha-gradle`**: the `fcntl` exported by proroot v1.2.8 `libproroot-runtime.so` rewrites every failure to EPERM (lock contention should be EAGAIN). JDK `libnio` uses that symbol, so `FileChannel.tryLock` throws `IOException: Operation not permitted` while another JVM holds the lock and Gradle fails. The source fix lives in `tools/proroot-compat/stack-sysconf.c` (the compat proxy exports a correct `fcntl`) and takes effect once an APK containing it is installed. Inside a worktree use `dsha-gradle <tasks…>`, `dsha-gradle --status` and `dsha-gradle --stop`. It probes `fcntl` at start: unfixed means one global mutex with `--no-daemon`; fixed means up to 3 memory-admitted parallel slots sharing a warm daemon, queueing when memory is short. Gradle JVMs get `oom_score_adj` 900 so the kernel kills builds before dsh. Logs live in `/root/.cache/dsha-gradle-logs/`. Do not run `./gradlew` directly, do not create more `/root/.gradle-<task>` copies (about 1 GB each, plus another 2–3 GB JVM each), and never kill another session's Gradle.
- **Large git diffs crash under proroot**: on build330 (proroot v1.2.8), `git show --stat`, `git diff --stat` and `--numstat` exit 139 with empty stdout and `[proroot] SIGSEGV` on stderr for some commits or ≥8-path sets; `--name-status` / `--dirstat`, `git log` and ≤7-path chunks do not crash, and GitHub Linux CI is unaffected. Never review or merge from broad `--stat` output, and never let `| head` or `;` swallow the rc. For statistics run `python3 tools/git_guard.py -- <git args>` (fails loud on that signature without retry or partial output); repository tools already use that guard. See [runtime engines](docs/en/runtime-engines.md).
- **Memory is stability**: concurrent Gradle / Robolectric runs starve the dsh web process and interrupt every session at once. Run one build at a time; run the full unit suite only before merging.
- **No release-package installs on this device**: installing `io.github.sunsetrne.dsha` stops the whole app (PACKAGE UPDATED) and with it DSH and every session. Sessions must not install or downgrade it; the `io.github.sunsetrne.dsha.test` package is fine. Before the user installs a release package, active sessions update their declarations.
- **Keep local overrides outside the tree**: disable Conscrypt only through `-I /opt/dsha-x86/local-ci.init.gradle`; do not write temporary files such as `app/src/test/resources/robolectric.properties` into a worktree.
- **Register long jobs**: background / `nohup` jobs log to a file listed under `后台任务`, with the exit code recorded when done. A DSH restart kills them.

### Interruption and takeover

- After a DSH crash or exhausted balance, leave the worktree and declaration as they are. A taking-over session sets `会话` to its own id and notes `接手 <old id>（reason）`; one owner per worktree at a time.
- First step after takeover is read-only: `git status`, the tail of each registered job log and its outputs, leftover temporary files. Commands with UNKNOWN outcome (build, push, release dispatch) are checked, **never replayed automatically**; check `origin/main` and existing tags before any push or dispatch.

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
