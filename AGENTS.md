# AGENTS.md

DSHA is the **DeepSeek Harness Android launcher**: it runs the `@deepseek-ai/dsh` Web UI on a stock, non-rooted **arm64 Android 8+** phone — no ROOT and no Termux required. This file gets an agent productive in the tree without reading it whole.

## Read order

1. This file.
2. `README.md` and `BUILD.md` — user-facing overview and build prerequisites.

## One-paragraph picture

The APK ships the Termux `proot` binary (in `jniLibs/arm64-v8a`, shipped as `libproot.so`) plus an offline **Ubuntu 24.04 arm64** rootfs. On first run the rootfs is extracted into app-private storage; afterwards the container runtime chroots into it, where **Node 24 + pnpm + `@deepseek-ai/dsh`** run the harness. The default runtime is **proroot** (no ptrace); `proot` stays as the fallback the self-heal ladder falls back to — see `ContainerRuntime` / `ProotBootstrap.runtime()`, and never assume the shipped binary in use. The native UI (pure Java, Material3, bottom nav) drives the install/start/stop and hosts the Web UI preview in a system WebView (optional GeckoView).

## Tech constraints (design around these)

- **Java 17 only, no Kotlin**, single Gradle module `:app`.
- `applicationId com.dsh.client`; Java package `com.deepseekharness.app`; `minSdk 26`, `compileSdk/targetSdk 34`, NDK 26, **arm64-v8a only**.
- Version lives in `app/build.gradle` (`versionName` / `versionCode`); file `VERSION` mirrors the tag.
- The offline rootfs (`assets/offline-rootfs.*`) is **not committed** — CI generates it; local builds supply their own.

## Startup contract (do not break)

- `welcomed == false` → `WelcomeActivity` (3 pages) → `ExtractActivity` (mandatory).
- `welcomed == true` but not `isOfflineExtracted()` → `ExtractActivity` (mandatory).
- `isOfflineExtracted()` → main UI (bottom nav tabs: 启动 / 终端 / 市场 / 设置).
- `ExtractActivity → MainActivity` **must** pass `skip_extract=true`, or Main re-launches Extract forever.
- Entry into main UI keys **only** on `.offline-extracted` (not on the older `.installed` marker).
- Default runtime is the npm/prebuilt route (`use_rc6=true` — the pref name is historical, it installs rc.8). Do **not** place a half-cloned source tree in the rootfs alongside it — `startWeb` prefers a source tree and half a tree breaks startup.

## Architecture (bottom-up)

```
native UI (Activities/Fragments)
   → HarnessController      business core: install steps, config, start/stop Web, backup/restore, downloads
     → PluginController     plugins + plugin market (split out; reached through forwarding methods on the host)
   → ProotBootstrap         proot exec, rootfs download/extract, offline-bundle handling
   → libproot.so + Ubuntu rootfs → Node 24 + pnpm + @deepseek-ai/dsh
```

Key files under `app/src/main/java/com/deepseekharness/app/`:

| File | Responsibility |
|---|---|
| `MainActivity.java` | Shell, startup gates, crash log, update check, backup reminder, upgrade/migration guards |
| `HarnessController.java` | ~6200-line business core (install steps, config, Web lifecycle, backup/restore, device bridge). Read before editing; make minimal patches |
| `PluginController.java` | Plugins + plugin market, split out of the controller in 2026-08 (1668 lines). Coupling to the host is deliberately narrow: `Context`, `ProotBootstrap`, and seven host methods — see its javadoc before widening it. UI callers go through the forwarding block on `HarnessController` |
| `ShellQuote.java` | POSIX single-quote escaping — the **only** shell-quoting implementation in the tree. Values from the plugin market reach `bash -c` through it, so it carries round-trip assertions in `tools/pure-logic-test.sh` |
| `SensitiveData.java` | The **only** redaction helper. Everything that gets logged, persisted or shown (activity log, Logcat, PTY title, overlay text, download errors, plugin toggle errors) goes through it; real HTTP requests keep the raw value. Its `Stream` class buffers until a newline because dsh emits one logical line in arbitrary `read()` chunks and a credential can straddle the boundary. Android-free, asserted in `tools/pure-logic-test.sh` |
| `DshaDocumentsProvider.java` | SAF root so any file manager (not just MT's private contract) can browse the rootfs. Guarded by the signature-level `MANAGE_DOCUMENTS`, and every documentId is canonicalized against `files/linux/ubuntu/root` |
| `ProotBootstrap.java` | proot/env exec, offline extraction, multi-mirror downloads |
| `ExtractActivity.java` | First-run offline extraction screen |
| `BackupManager.java` | Manual + migration backups to `Download/DSHA`, restore |
| `UpdateChecker.java` | GitHub releases check |
| `HarnessService.java` | Foreground keep-alive |
| `HttpShellService.java` | `127.0.0.1:3090` command bridge |
| `ShizukuShell.java` | Real-device shell via Shizuku |
| `LanProxyService.java` | LAN access |
| `TarGzipExtractor.java` | Pure-Java tar/tar.gz extraction |
| `*Fragment.java` | Per-tab UI: Launch/Install/Config/Workspace/Terminal/Plugin/Settings |

### Rootfs paths (guest paths, reached through proot)

- Rootfs: `files/linux/ubuntu/`; extracted marker: `files/linux/.offline-extracted`.
- User data inside rootfs: `/root/.dsh` (profile config, conversations, plugins), `/root/<workdir>/.env` (default workdir `deepseek-harness`), `/root/dsh-web.log`.

### Upgrade-migration behaviour

Protecting user data across upgrades/reinstalls is split across two spots — keep both in mind when touching startup:

- `MainActivity.maybeRunUpgradeMigration()` compares stored `last_version` with `versionName`; on version change it background-packs `.dsh` + `.env` + log to `Download/DSHA/DSHA-migration-<from>-to-<to>-*.tar.gz` and, for a fresh rootfs with an existing snapshot, offers a restore (per-version "暂不" persists).
- `HarnessController.upgradeGuard()` runs at Main startup, auto-backs-up old data via `BackupManager.backupToExternal()` when `versionCode` rises; `maybePromptRestore(Activity)` (called only when `skip_extract=true`, so the rootfs is already extracted) prompts to restore the latest `DSHA-backup-*.tar.gz` into an empty rootfs via `restoreFromBackup()`.

## Plugin machinery

- Plugins live in `/root/.dsh/profiles/web/node_modules` (`PLUGIN_DIRS`), declared in `package.json` `dependencies` and in `dsh.profile.bundles`.
- Disabling renames `name` → `name.disabled`, deletes the dependency, and stashes the original source at `.dsha-src-<name>`.
- Re-enabling restores the source from the stash. If the stash is gone the caller falls back to `"*"`; `toggleScript()` (in `HarnessController`) must never write `"*"`, `"null"`, or empty into `dependencies` — a `"*"` dependency on a non-npm plugin name breaks `pnpm install`.
- Market index is `PLUGINS-ALL.md` from `awesome-dsh-plugins`, fetched through mirror URLs with a local cache fallback.

## Self-healing scripts (`app/src/main/assets/`)

Injected into the rootfs on demand and run through proot; all are idempotent and fail soft.

| Script | Called from | Purpose |
|---|---|---|
| `heal-session.sh` → `heal-sessions.py` | `doHealSessionCorruption` (Main start + `startWeb`) | Scan `/root/.dsh/sessions`, repair missing `message.id`, isolate unrepairable logs. Matches `session.jsonl{,.zstd}` **exactly** and stores pre-fix copies under `corrupt-backup/` — an earlier prefix match re-healed its own backups and doubled the file count every launch |
| `fs-write-patch.sh` | `maybeFixFsWrite` (Main start) + `installGuard` + `startWebCommand` | Patch `dsh-fs-local` so a **new** file is published with `rename` instead of `link` (see traps) |
| `backup-prepare.py` | `BackupManager.backup` | Emit `.dsha-backup-manifest.json` and inline `link:`/`file:` plugin sources into `.dsha-plugin-src/` |
| `restore-merge.py` | `restoreFromBackup` | Locate `.dsh` at any depth, remap the workdir name, re-land inlined plugins, rewrite `link:` paths, add the `node_modules` symlinks, drop unresolvable bundles, write `.dsh/restore-report.txt` |
| `rootfs-confirm-install.sh` | `ensureDangerGuard` | `/root/dsh-bin` wrappers + `dsh-confirm.sh` (3090 bridge, dual-stack) |
| `adb-{shell,pair,setup}.{py,sh}` | `AdbBridge` | Wireless-ADB channel |
| `webui-{polyfill,degrade-patch,origin-port-patch}.sh`, `lan-bind-patch.sh`, `fix-stale-bundles.sh`, `dsh-deps-heal.sh` | pre-start self-heal in `startWebCommand` | WebView/LAN/bundle/dependency fixes |
| `pnpm-env-fix.sh` | before every `pnpm install` (source route + plugin install) | Turn off pnpm's hard-link import **where pnpm ≥10 actually reads it** (`pnpm-workspace.yaml`, not `.npmrc` — see traps), clear failed git-hosted staging dirs, drop dangling `.l2s` links in the store |

### Version markers — bump these or old installs keep the stale copy

| Constant | Where | Bump when |
|---|---|---|
| `AdbBridge.SCRIPT_VERSION` | `/root/.dsh/script-version` | any `adb-*.py/sh` change |
| `HarnessController.GUARD_VERSION` | `/root/dsh-bin/.version` | `rootfs-confirm-install.sh` change (**must equal the number echoed at the end of that script**) |
| `HarnessController.STEP6_VERSION` | `/root/.dsh/step6.version` | anything step ⑥ installs |
| `HarnessController.BUILTIN_ASSET_VERSION` | `/root/.dsh/builtin-assets.version` | builtin plugin assets change |
| `device-shell-guide/package.json` `version` | plugin dir | that plugin's code change |

## Backup format v2 (tolerant by design)

Rule: **restore as much as possible, never fail the whole archive over one unknown entry, and tell the user what was skipped.**

### Backup scope — `full` / `sessions` / `settings` / `plugins`

`BackupScope` is the **single** definition of "how much does one backup cover": it owns the tar
path list, the sub-trees the restore side merges, the file-name prefix and the UI labels. Two
invariants are asserted in `tools/pure-logic-test.sh`:

- **Only a full backup is named `DSHA-backup-*`.** Partial backups are `DSHA-sessions-*` /
  `DSHA-settings-*` / `DSHA-plugins-*` on purpose: older builds (and `looksLikeBackupName`, which drives the
  automatic restore prompt) only scan the `DSHA-backup-` prefix, and a full restore *moves the
  whole `.dsh` aside and replaces it*. Letting an old build treat a sessions-only archive as a
  full backup would wipe the user's config and plugins.
- **What gets packed and what gets merged must line up item by item** (`dshPaths` vs
  `mergeSubdirs`).

The scope is written into the manifest (`"scope": "full"|"sessions"|"plugins"`).
`restore-merge.py` trusts the manifest first, the `--scope` argument second (App infers it from
the file name; a name can be renamed, the manifest cannot), and falls back to `full` — which is
exactly what a manifest-less legacy archive means. A partial backup **refuses to be created**
when the manifest fails to generate: without the scope marker it would restore as a full archive.

### `.dsha-pub/` — the dereferenced snapshot of hot data

After the public-data migration, `.dsh/sessions`, `storages`, `attachments` and `settings.yaml`
are **symlinks** into `/sdcard/Documents/dshdata`, and `tar` stores the link, not the target
(verified: the archive contains a single `lrwxrwxrwx .dsh/sessions -> …` line and not one
conversation). Restoring on the same device hides the problem — the link still points at the
public dir where the data lives — but **restoring on another device yields a dangling link and
zero conversations**, which is exactly the case the feature is advertised for.

`tar -h` is not an option: it is global and would expand every `link:` plugin under
`node_modules` as well. So backup copies just those four entries, dereferenced, into
`.dsha-pub/` inside the archive; restore lands them **after** `.dsh`, and writes into the
symlink target when the destination is still a valid link (keeping the "data lives in the public
dir" layout, so an uninstall doesn't take it away). Old builds ignore the directory and behave
exactly as before — the change is purely additive.

- Archive holds `.dsh`, `<workdir>/.env`, `dsh-web.log`, plus (v2) `.dsha-backup-manifest.json` and `.dsha-plugin-src/`.
- Restore stages into `/root/.dsha-restore-stage`, then `restore-merge.py` merges: `.dsh` at any nesting depth, `.env` remapped onto the *current* workdir name, inlined plugins landed in `/root/plugin-src/<name>` with `link:` rewritten and `node_modules/<name>` symlinked.
- Bundles that still cannot resolve are removed from `dsh.profile.bundles` (dsh must be able to boot) and reported; those with a registry spec are printed as `MISSING_PLUGINS:` and reinstalled **silently in the background** by `autoInstallPluginsSilently`.
- Pre-existing `.dsh` is moved to `.dsh.pre-restore-<ts>` instead of deleted. Archives without a manifest still restore (heuristics).
- `TarGzipExtractor.extractLenient` skips suspicious/oversized entries (counted in `lastSkipped`) rather than aborting.

## UI design tokens

`values/dimens.xml` + `values/styles.xml` own every spacing, radius and text size; layouts must reference tokens, not literals.

- Spacing: 4 / 8 / 12 / 16 / 20 (`gap_hair` … `gap_section`, `page_pad`, `card_pad`).
- Radius: `radius_card` 18dp (containers), `radius_control` 14dp (buttons/inputs), `radius_small` 10dp, `radius_pill`.
- Text: `text_display` 24sp, `text_title` 17sp, `text_body` 15sp, `text_label` 13sp, `text_caption` 12sp (+ `text_hero` for the welcome pages).
- Backgrounds are `ripple` + `selector` drawables: press feedback **and** a disabled state. Inputs highlight their stroke on focus. Bottom-nav tint must stay `@color/nav_item_tint` (a flat `@color/primary` makes all four tabs look selected).
- `themes.xml` sets the M3 semantic colors (`colorSurface`/`colorSurfaceVariant`/`colorOutline`/`colorSecondaryContainer`/…) so Switch/CheckBox/Spinner/AlertDialog follow the app palette instead of Material's default purple. `alertDialogTheme` needs a full Dialog theme, `materialAlertDialogTheme` needs a ThemeOverlay — passing the wrong kind crashes the dialog.

## 3090 bridge endpoints (what the agent can call)

`HttpShellService` listens on `127.0.0.1:3090` (plus `[::1]`), token in `/root/.dsh/.bridge_token`, every request must carry `?token=` or `X-Token`. The rootfs side is steered by the `device-shell-guide` prompt.

| Endpoint | Purpose |
|---|---|
| `/exec?cmd=` | Shizuku shell (may be unavailable; ADB channel is the primary one) |
| `/confirm?cmd=&force=1` | Ask the user to approve a command; blocks, 60s timeout ⇒ deny |
| `/app/device` | Model, Android version, battery, network, screen, foreground flag, storage, memory |
| `/app/apps?q=&limit=&user=1` | Installed packages (`pkg<TAB>label`); needs `QUERY_ALL_PACKAGES` on Android 11+ |
| `/app/launch?pkg=` | Launch an app through `PackageManager` — no ADB needed |
| `/app/clip` / `/app/clip?text=` | Read (foreground only, OS restriction) / write the clipboard |
| `/app/ask?q=&options=a\|b\|c` | Modal question, **blocks up to 120s**, returns the chosen label |
| `/app/notify?title=&text=` | Notification (suppressed while the app is foreground) |
| `/app/toast?text=` | In-app toast |
| `/app/share?text=` \| `?path=` | System share sheet (files must live under `/sdcard`) |
| `/app/open?url=` | Open a link (http/https/geo/tel/mailto/market only) |
| `/app/vibrate?ms=` | Haptic ping when a long task finishes |
| `/app/export?path=&name=` | Copy a file into `Download/DSHA` via MediaStore. **Guest paths only** (`/root/…`, canonicalized against the rootfs) — the earlier "try the host path first" fallback let the bridge token read any file the App itself could, `shared_prefs` included |
| `/app/readfile?path=` | Read a text file under primary external storage (`/sdcard`, full-segment prefix check after canonicalization; credential files are refused both before and after resolving symlinks) |
| `/app/overlay`, `/app/overlay/reply` | Used by the builtin `dsh-status-overlay` plugin (streaming bar + in-place reply pickup) |
| `/health` | Liveness probe (still token-gated) |

Rules when adding endpoints: keep them **token-gated**, dispatch on the request target with the query string stripped (`Query.path`) and match the route **exactly** — a `startsWith` route silently swallows every longer path and makes correctness depend on declaration order. Refuse paths outside the documented root for file access, never expose credential files, and return plain text — `handle()` wraps whatever you return in `{"result":"…"}`, so nested JSON gets double-escaped. Read parameters **only** through `getParam`/`intParam` (they delegate to `Query`, the single query-string parser in the tree — never hand-roll `indexOf("key=")`, it has no parameter-name boundary and any parameter *ending* with your key hijacks it). Blocking endpoints must have a timeout and a single-flight guard that is an `AtomicBoolean` with `compareAndSet` (see `askBusy` / `confirmBusy`) — a `volatile boolean` plus "check then set" is not atomic, and two requests will trample each other's state. Dialogs that gate a blocking call must not `countDown` from `OnDismiss` (see traps).

## Upgrade compatibility (old installs must upgrade in place)

Users install by tapping an APK, so **签名一致是能否覆盖安装的唯一硬条件**. Rules:

- All builds should share one keystore. `app/build.gradle` reads `DSHA_KEYSTORE` /
  `DSHA_KEYSTORE_PASSWORD` / `DSHA_KEY_ALIAS` / `DSHA_KEY_PASSWORD` (env or gradle property);
  without them it falls back to AGP's default debug keystore, which **differs per machine/CI
  runner** — that fallback is what forces users to uninstall first. CI does the same via the
  `DSHA_KEYSTORE_B64` secret and prints the certificate fingerprint after every build.
- Current fingerprint of the historical local builds (v1.1.0 … today):
  `e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5`. Compare before publishing.
- `versionCode` must only ever increase; Android refuses to install a lower one over a higher one.
- Never rename or drop a SharedPreferences key: `use_rc6`, `workdir`, `api_key`… are read by
  every past version. Add new keys instead (`Constants`).
- Anything shipped into the rootfs must be version-marked (see the table above) so an upgraded
  APK re-injects it; `installGuard` is idempotent and safe to re-run on old rootfs trees.
- Data written by an older release must keep working: `restore-merge.py` accepts manifest-less
  archives, `heal-sessions.py` migrates the legacy `session.jsonl.*.corrupt-*` copies that older
  builds left inside `sessions/`, and new backups stay readable by old builds (they just see two
  extra entries).
- New permissions must be install-time (`normal`) ones — `QUERY_ALL_PACKAGES` / `VIBRATE` are,
  so覆盖安装不会弹权限询问也不会失败.

## ADB keep-alive (layered, `DeviceBridgeService`)

The wireless-debugging port is random and changes on every reboot / toggle, Doze freezes background
networking, and a plain background service can be reclaimed at any time — so keep-alive is layered:

| Layer | Mechanism |
|---|---|
| Probe schedule | Adaptive: 60s while connected, `3/6/12/24/45s` backoff right after a drop, 120s once it keeps failing |
| Event triggers | `registerDefaultNetworkCallback` (network back), `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT`, config page open, app foreground — all funnel through `kick(reason)` with a 1.5s debounce and single-flight guard |
| Doze fallback | `AdbKeepAliveReceiver` + `setAndAllowWhileIdle` (~9 min in practice); `Handler.postDelayed` alone gets deferred indefinitely in deep sleep |
| Service survival | `START_STICKY`; the **foreground** `HarnessService` also re-`apply()`s the bridge service every 15s if the ADB switch is on but the service is gone; `BootReceiver` restarts it and re-arms the alarm |
| Self-heal ladder | probe → mDNS re-discover port → retry → auto re-enable wireless debugging (`WRITE_SECURE_SETTINGS`, else Shizuku) → wait 5s → retry |
| Port fallbacks | `connect_port` → mDNS → **`connect_port_history` (last 5 successful ports)** → `5555`; every success is remembered |
| Failure classes | `ok` / `reconnecting` / `installing` / `need_pair` (pairing invalid) / `need_manual` (wireless debugging off) / `network_lost`; only notifies after 3 consecutive failures and cancels the notification on recovery |
| Visibility | State mirrored to `/root/.dsh/adb-status` (`state/detail/failures/last_ok/updated`) — read by the self-test and readable from the container; config page shows it live |
| Battery whitelist | Config page has a one-tap `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` button; without it Doze freezes the socket and no amount of retrying helps. The self-test fails this check explicitly |

When touching this code: keep every probe on a background thread (it shells into proot), keep the
single-flight guard, and never lower `SCRIPT_VERSION` — old installs keep their stale scripts otherwise.

## Build & CI

- Two-stage Actions workflow (`.github/workflows/android-build.yml`):
  1. `bundle` on `ubuntu-24.04-arm` — chroot-provisions the offline rootfs (node-pty must be built on native aarch64; **never** qemu).
  2. `apk` on `ubuntu-latest` — copies the bundle into assets and runs `assembleDebug` (must strip any local `aapt2FromMavenOverride` line from `gradle.properties`).
- `protectOfflineBundle` renames `assets/offline-rootfs.tar.gz` → `.bin` before packaging (aapt silently untars `.tar.gz`).
- Artifact `dsha-debug-apk` uploads the raw `app-debug.apk`; GitHub wraps every artifact download in a ZIP, so testers must unzip before installing.
- Local build: `./build.sh` (needs Gradle 8.5, SDK 34, NDK 26, `local.properties` with `sdk.dir`).

### Signing — two different keys, never mix them

- **Published APKs are signed with a debug keystore.** `secrets.DSHA_KEYSTORE_B64` holds a
  `debug.keystore` (alias `androiddebugkey`, cert SHA-256 `E7:E3:A3:1A:75:94:6F:26…`) — the
  wrong file was uploaded when the secret was first created, and every release since v1.1.7
  carries that signature. Android only allows same-signature upgrades, so switching keys now
  would force every user to uninstall, and the rootfs lives in the private dir (it would be
  gone). Treat it as **the** publish key: it is a tool-generated file that AGP silently
  regenerates when the SDK dir is wiped, so a backup lives at
  `/workspace/DSHA-ACTUAL-PUBLISH-KEY-debug.keystore`. Key rotation (APK Signature Scheme v3)
  is a separate, unscheduled task; see `DSHA-签名现状与风险.md` in the workspace.
- `DSHA-release.keystore` (alias `dsha`, RSA 4096, `ED:8A:AE:A2…`) is used **only** to sign
  the incremental-update manifest (`tools/sign-runtime-manifest.sh`; the matching public key
  ships as `assets/runtime-update-pubkey.pem`). Never export `DSHA_KEYSTORE` pointing at the
  publish key *before* the manifest is signed — that script reads the env var first, and a
  manifest signed with the wrong key makes every client reject the whole update batch. It now
  restores the previous `.sig` when self-verification fails, so a bad signature can't slip
  into a commit.
- `release.yml` **fails** when the APK fingerprint doesn't match the publish key. Shipping a
  package users cannot install is worse than a failed release. (It used to warn only — and it
  had the wrong expected fingerprint, so it warned on every single build and nobody looked.)
- `./build.sh` points APK signing at the publish key automatically (after the manifest is
  signed), so locally built packages can be installed over an official release.

## Security invariants (do not weaken)

Every one of these replaced something that looked fine and was exploitable from outside the App:

- **Bridge routing matches exactly.** `Query.path` strips the query string; endpoints compare with `equals` (only the `/app/ui/` dispatch prefix uses `startsWith`). Order-dependent routing is not a defence.
- **Local sockets are hostile.** Anything on the device can dial 127.0.0.1, and anything on the Wi-Fi can dial 3081. Both bridges share `BridgeLimits`: bounded worker queue (rejection closes the socket), per-line and total request-header caps, and `shutdownNow()` closes queued *and* running sockets so a blocked read cannot outlive the bridge.
- **File writes reserve names atomically.** `SafeFiles.reserve` creates the file with `Files.createFile` instead of `exists()`-then-write, so a dangling symlink or a racing writer cannot be followed; `SafeFiles.replace` uses `ATOMIC_MOVE` and **fails** rather than deleting the destination first.
- **Path boundaries compare whole segments.** `SafeFiles.isInside` / `isPrimaryExternalPath`: `/sdcard-old` is not inside `/sdcard`.
- **Credentials are authenticated-encrypted or not stored.** API keys use AES-GCM through Keystore; there is deliberately **no plaintext fallback** — `setApiKey` returns `false` and the UI says so. Backups skip the key rather than writing it in the clear (backups land in public `Download/`).
- **The update manifest and its signature come from the same mirror.** Taking whichever manifest and whichever signature answered first turns any CDN skew into a permanent verification failure.
- **Confirmation authority lives in the App.** No container-side flag can waive it; internal calls carry a single-use ticket minted by `HttpShellService`.
- **Embedded browsers only load the local Web UI.** Both WebView and GeckoView route anything else to the system browser, and downloads only accept the loopback origin.



- Comments and UI strings are **Chinese**; commit messages are Chinese with a `type:` prefix that explains **why** (match `git log`).
- `HarnessController.java` is huge — apply the smallest patch that works; don't rewrite the file.
- Match existing style: wrap risky work in try/catch, degrade to a sensible fallback, toast failures to the user.
- Do not remove the protection Gradle tasks or the extraction invariants "for cleanliness" — they exist for the reasons above.

## Known traps (verify before assuming)

- **保护用户数据的那一步必须 fail-closed，而且要能回滚。** 重解压内置环境之前会把整个 `/root` 挪进 `.data-preserve-*`（主路径 `renameTo`：同文件系统、O(1)、软链原样带走）。rename 不成才退回 `FileCopy.copyPreservingLinks`，那里**无法原样重建软链就直接抛** —— 悬空的 `sessions` 链接没有可读的退路，跟随一个目录链接会把几 KB 的保护快照变成几 GB 的半份拷贝。调用方收到异常要先把已经挪走的条目 rename 回 `/root`（`ProotBootstrap.restoreProtected`）再中止升级：中止时 rootfs 完好，代价只是这次升级没做成。**别改回「记一行 warning 然后继续」** —— 下一步就是删 rootfs，那一项数据永久没了，而现场只有一行没人看的日志。升级完成后的「挪回去」阶段相反：只记 failed 不中止（rootfs 已经换好，数据还在保护目录里）。
- **`BackupScope.fromFileName` 这类前缀判据要先剥掉目录。** 调用方不止传文件名，也有传 `File.getPath` 的整条路径 —— 不剥目录，`/storage/…/DSHA-sessions-x.tar.gz` 会被判成 `FULL`，而全量恢复是「整个 `.dsh` 挪走再替换」，配置、凭据、插件一起被换掉。
- **`needsPublicDataSnapshot` 从 `snapshotEntries` 派生，别再写第二份范围表。** 上游 1.2 把两者分开写，结果新加的 `settings` 范围在一处说「不需要快照」、另一处却返回 `settings.yaml`。眼下无害（生产代码只看 `snapshotEntries` 非空），但下一个按前者判断的人就会打出一个 `settings.yaml` 是悬空软链的包。
- **诊断文本只在展示与落盘边界脱敏，真实请求用原值。** 新增日志/Toast/活动日志/悬浮条文本时套 `SensitiveData.redact`；处理长时间进程输出用 `SensitiveData.Stream`（凭据会跨 `read()` 块）。**唯一刻意不脱敏的地方是终端复制回调** —— 剪贴板是用户主动复制的内容，改写它等于给他一个假的粘贴结果。

- **确认弹窗要和通知一起发，而且别拿 dismiss 当拒绝.** 早期实现是「前台弹窗 / 后台通知」二选一：Activity 一被 pause，用户就再也看不到弹窗，只能干等 60s 超时被拒 —— 这正是「确认框有时不出现」的由来。现在两条都发（通知是权威渠道），弹窗用 `setCancelable(false)` 且**不在** `OnCancel`/`OnDismiss` 里 `countDown`（pause 造成的 dismiss 会被误判成用户拒绝）。在 finishing 的 Activity 上 `show()` 会抛 `BadTokenException`，那是主线程，异常不在 `handle()` 的 catch 范围内，必须自己 try 住。**这套约定对每一个阻塞式对话框都成立** —— `/app/ask` 曾经原样犯了一遍（`OnDismiss` 里 `countDown`，于是旋屏、切深色模式、Activity 被回收都会给 agent 送回一句「用户关掉了提问框」，而用户什么都没做）。修法是只认 `OnCancel`（用户主动取消）、不认 `OnDismiss`；代价是 Activity 重建时那次提问要等满超时，宁可让 agent 多等也不要给它假答案。
- **确认要带 epoch.** 锁屏残留通知、通知历史、手表转发上的旧「允许」按钮，会把授权决定打到**下一个**请求上（等于一次点击授权了另一条命令）。每次确认递增 `confirmEpoch`，回调校验 epoch 并认领 latch，过期点击直接丢弃。`confirmBusy` 必须是 `AtomicBoolean`：「检查后置位」不原子的话两个请求会互相覆盖 `pendingLatch`。清理顺序也有讲究 —— 先清 latch/弹窗/通知，最后才放开 `confirmBusy`，否则下一个请求抢先发出的通知会被本轮的 `cancelConfirmNotification()`（固定通知 ID）取消掉。
- **3090 桥要跨实例互斥.** `HarnessService` 与 `DeviceBridgeService` 各 new 一个 `HttpShellService` 都调 `start()`，实例字段 `running` 挡不住跨实例重复启动：第二个实例绑定失败，却会把活着的那个从 `instance` 抹掉，通知按钮全废。用静态 `STARTED` + 实例 `owner`，只有持有者的 `stop()` 才做清理。反过来，`stopWeb` 关掉桥后 ADB 开关仍开着，所以保活探测里要检查 `instance() == null` 并补起来。
- **App 自己调 `adb-shell.py` 要带一次性票，不是 `DSH_INTERNAL=1`.** 脚本内有 fail-closed 确认关卡，而保活探测每分钟（断线时每 3 秒）跑一次 `id` —— 不给它一条免打扰通道就会不停弹确认框。但判据不能放在脚本里：`DSH_INTERNAL` 是环境变量，**容器内任何进程都能自设**，等于给拿到容器 shell 的 agent 留一条免确认执行 ADB 命令的路。现在 App 侧 `HttpShellService.mintInternalTicket()` 现场铸票（一次性、60 秒过期），六个内部调用点用 `DSH_INTERNAL=<票>` 传进去，脚本原样转给 `/confirm?internal=`，由桥消费校验；伪造的票在桥侧不成立，照常弹窗给用户。`id` 本来就在只读白名单里，所以那四个探活点连确认请求都不发。改动 `is_readonly_cmd` 或票机制时跑 `python3 tools/adb-guard-test.py`（22 条断言，含「`dumpsys deviceidle whitelist +pkg` 不算只读」）。
- **A bundle needs both `bundles` and `dependencies`.** dsh's reconcile drops any entry that is listed in `dsh.profile.bundles` but has no matching entry in `dependencies` — it cannot resolve it, so it prunes it. `ensureBuiltinBundles` used to restore only the bundle name and the `node_modules` symlink, producing an endless restore→prune loop: the user just sees the plugin never taking effect. A real field capture looked like this — entity dir present, symlink present, **both** `bundles` and `dependencies` missing the plugin, while `dsh-client-ui-mobile-adapt` (whose dependency entry survived) stayed registered. Always write `dependencies[name] = "link:<real path>"` alongside the bundle entry, and treat "registered" as *both* present.

- **Don't write an installed-marker before verifying.** `ensureDeviceShellGuide` wrote `dsha-device-shell-guide-installed` unconditionally, but its registration block is guarded by `if (profiles/web/package.json exists)` and step ⑥ usually runs *before* dsh first creates the web profile. The marker then made every later run skip the work. Markers must be written only after a positive check (`guideRegistered`).
- **停止 Web 不能靠「按命令行找进程」.** 两条环境限制在设备上实测确认过：① `/proc/net/tcp` 对非 root App 是 `Permission denied`（Android 10+ 收紧 /proc/net），所以 `ss` / `netstat` / `lsof` / 端口反查全都返回**静默的空**；② `/proc` 只看得到同 uid 的进程（hidepid），跨 proot 会话扫描不保证看得见对方。停止链路因此以 **pid 文件**为主力：`runCoreCommand` 在 `exec node` 前写 `$$`（`exec` 不换 pid，写下的就是 node 的 pid），停止时容器内 `WebProcSel.pidsFile()` 与 Android 侧 `killByPidFiles()` 各按 pid 杀一遍，杀前核对 `/proc/<pid>/cmdline` 防 pid 回卷复用。cmdline 匹配（`WebProcSel.pidsDsh`）与端口反查（`pidsPort`）降为附加层，别再把它们当主力改。判据全部收在 `WebProcSel`（唯一定义），字符串断言在 `tools/pure-logic-test.sh`，**真起进程跑一遍**在 `tools/stop-proc-test.sh` —— 这条测试存在的理由就是「片段拼对了但在目标环境里全程返回空」这类故障纯逻辑测不出来。
- **「停止之后又被拉起来」要用哨兵治，不要靠杀干净.** 看门狗与它写的重启脚本（`dsh-cmd.txt`）是容器内独立的 bash 进程，App 侧的 `keepalive_paused` / `last_web_stop`（`shouldAutoStartWeb`）完全管不到它们 —— 漏杀一个，几秒后 Web 就自己回来了，用户看到的是「停止后 dsh 秒复活」。所以停止的第一步是 `touch /root/.dsha-stopped`（`WebProcSel.STOP_SENTINEL`）：看门狗每轮循环开头、重启脚本第一行都检查它，看见就自己退出。删除点只有一个 —— `startWebCommand()` 开头（用户明确要启动）。停止后 4 秒还会跑一次「复活侦测」，把占用端口进程的 **PPid 与父进程 cmdline** 写进活动日志：「谁拉起来的」只有父进程能回答。
- **Hard links are forbidden in app-private storage.** `link()` under `/data/user/0/<pkg>/` fails with `AccessDeniedException` (SELinux), which is why proot needs `--link2symlink` at all. The extension emulates a link as *symlink → `.l2s.` intermediate*, stored next to the source unless `PROOT_L2S_DIR` is set. dsh publishes a **new** file with `link(temp, target)` and then deletes its staging dir — so every freshly written file became a dangling symlink (`write` reported success, the file was unreadable, `edit` was fine because it uses `rename`). Fixed by `fs-write-patch.sh` (publish new files with `rename`) plus `PROOT_L2S_DIR=<rootfs>/.l2s` as a fallback. Don't "simplify" this away.

- **pnpm ≥10 的这类设置只从 `pnpm-workspace.yaml` 读，写进 `.npmrc` 等于没写.** 对照实验（2026-08-30，pnpm 11.21.0，同一个包同一个 store）：`.npmrc` 里 `package-import-method=copy` → pnpm 照样打印 *Packages are hard linked*，`stat` 出来 nlink=5；`pnpm-workspace.yaml` 里 `packageImportMethod: copy` → *Packages are copied*，nlink=1。dsh 自己的 profile 模板注释也写着「pnpm ≥10 reads its settings from pnpm-workspace.yaml, not .npmrc」。所以「关掉硬链接导入」这条修复曾长期是死代码 —— link 被 `--link2symlink` 模拟的机器上 pnpm 一直在铺 `.l2s` 链，悬空后表现为莫名 ENOENT，dsh 侧则因为 `require.resolve` 把 realpath 解进 store 的扁平哈希目录而报 `MissingClientBundleError`。现在由 `pnpm-env-fix.sh` §③ 往 profile 与工作区的 `pnpm-workspace.yaml` 幂等追加，`.npmrc` 那两行只留给 pnpm 9 / npm。**写之前先探测**（容器内 `ln` 一次看结果是不是 symlink，`DSHA_LINK_EMULATED=1/0` 可覆盖）：真硬链接可用的机器强行改成复制会白扔掉 store 去重，profile 装几十个插件能多占几百 MB。断言在 `tools/pnpm-import-test.sh`（沙箱里真跑脚本、真查文件，18 条）。

- **Injected messages need an `id`.** dsh validates every persisted `user/assistant/tool` event via `assertMessageEventShape`; a message without a non-empty `id` makes the **whole session history** refuse to load (`lacks an identified message`). `device-shell-guide` hand-rolls a message, so it must set `id: randomUUID()` — this was the root cause behind a long run of "history unavailable" reports, and session healing only cleaned up after it.
- **Bind the 3090 bridge to `127.0.0.1` explicitly.** `InetAddress.getLoopbackAddress()` returns `::1` on Android, so the bridge listened only on IPv6 while every rootfs client dials IPv4 — the confirmation dialog could never fire and commands came back `USER_REJECTED`. A second listener on `[::1]` is kept for clients that resolve `localhost`.
- **The bridge body must be valid JSON.** `{"result":YES}` (no quotes) broke `adb-shell.py`'s `'"YES"' in body` check, so even pressing *Allow* read back as a rejection.
- **Locating the offline bundle**: enumerate the APK zip (`ZipFile(packageCodePath)`) looking for `offline-rootfs.{tar.gz,tar,bin,tgz}` — `AssetManager.open` cannot open 300MB+ entries, and aapt may have renamed the entry to `.tar` or `.bin`.
- **Extraction**: `TarGzipExtractor.extractAuto` sniffs `1f 8b` gzip magic and falls back to raw tar.
- **`libproot.so`**: patched to fix a WebUI crash; don't replace it with a stock proot build.
- Android 10+ external writes go through MediaStore; direct `Download/DSHA` paths only work as a fallback on older or permission-less devices.