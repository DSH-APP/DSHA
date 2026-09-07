# DSHA

<p align="center">
  <b>DeepSeek Harness launcher for Android</b><br>
  Run the full <a href="https://github.com/deepseek-ai/deepseek-harness">deepseek-harness</a> on your phone — no root, no Termux, nothing to type
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="MIT"></a>
  <a href="https://github.com/qiannianhuanxiang/DSHA/releases/latest"><img src="https://img.shields.io/github/v/release/qiannianhuanxiang/DSHA?color=blue" alt="release"></a>
  <a href="https://github.com/qiannianhuanxiang/DSHA/stargazers"><img src="https://img.shields.io/github/stars/qiannianhuanxiang/DSHA?style=flat" alt="stars"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="android">
  <img src="https://img.shields.io/badge/arch-arm64--v8a-lightgrey" alt="arch">
</p>

<p align="center">
  <b>English</b> · <a href="README.md">简体中文</a> · <a href="CHANGELOG.md">Changelog</a> · <a href="docs/security-model.en.md">Security model</a> · <a href="AGENTS.md">AGENTS.md (for AI / contributors)</a>
</p>

> 🤖 If you are an AI agent or a new contributor, read **[AGENTS.md](AGENTS.md)** first
> (structure, startup contracts, traps we already fell into). Don't scan the whole repo.

---

## v1.2.0-rc1.4 — usability and reliability preview

This is a **Pre-release**, maintained by contributor [@ym2025szz](https://github.com/ym2025szz), with thanks to original author [@qiannianhuanxiang](https://github.com/qiannianhuanxiang) and the other contributors.

This preview improves installation checks and live output, backup/restore, command timeouts and terminal cleanup, background downloads, plugin tasks, update state, and both embedded browser engines. Native pages use a moderately compact layout, a fixed configuration save button, and the existing toolbar day/night toggle. Display and runtime options explain their purpose and when changes take effect.

- **Standard:** Android 11+ / arm64, system WebView, **212.87 MiB**. [APK](https://github.com/DSH-APP/DSHA/releases/download/v1.2.0-rc1.4/dsha-1.2.0-rc1.4.apk) · [SHA-256](https://github.com/DSH-APP/DSHA/releases/download/v1.2.0-rc1.4/dsha-1.2.0-rc1.4.apk.sha256).
- **Compatibility:** Android 6+ / arm64, bundled Gecko fallback, **289.69 MiB**. [APK](https://github.com/DSH-APP/DSHA/releases/download/v1.2.0-rc1.4/dsha-1.2.0-rc1.4low.apk) · [SHA-256](https://github.com/DSH-APP/DSHA/releases/download/v1.2.0-rc1.4/dsha-1.2.0-rc1.4low.apk.sha256).

Version code **113**, the historical APK certificate, environment version **9**, and dsh **0.1.2-rc.1** are retained. Same-signature upgrades preserve the environment. Both variants share application data and cannot coexist.

On Android 13, the six-step check took about **1.9 seconds**; actual DeepSeek responses, in-app wireless ADB, backups, process cleanup, and both browser engines were verified. Builds, signing and related tests pass. Other Android versions, real 16 KB kernels and vendor background behavior still have coverage limits.

[GitHub release](https://github.com/DSH-APP/DSHA/releases/tag/v1.2.0-rc1.4) · [Release notes](docs/releases/v1.2.0-rc1.4.md) · [Verification and limitations](docs/functional-audit-rc1.4.md)

## v1.2.0-rc1.2 preview — Android standard / compatibility builds

Maintained and published by contributor [@ym2025szz](https://github.com/ym2025szz), continuing the 1.2 refactor of [@qiannianhuanxiang](https://github.com/qiannianhuanxiang)'s project. This is a **pre-release**.

- **Standard:** Android 11+ / arm64, system WebView, 212.39 MiB.
- **Compatibility:** intended for Android 6–12 / arm64, with Gecko 143 for older WebViews, 289.46 MiB. Both builds share the package name and data, so they cannot coexist.
- Compared with rc1: plugin link installation, import/export/delete, certificate and file-picker fixes, a dsha.cc browsing link, fullscreen chat, npm support and installation repairs. The standard APK is about 48.7% smaller than the published rc1 while retaining the offline environment.
- Version code 111, same release certificate as rc1/rc1.1; compatible in-place upgrades do not require uninstalling first. The bundled dsh remains 0.1.2-rc.1.

[Download both APKs and SHA-256 files / full release notes](https://github.com/qiannianhuanxiang/DSHA/releases/tag/v1.2.0-rc1.2) · [Browse plugins](https://dsha.cc/)

Builds, lint and signing checks pass; Android 13 covers both browser engines and plugin/npm flows, with earlier core checks on Android 16. Target SDK is API 37; Android 6–12, Android 17 and 16 KB devices still need additional hardware testing. Please report the device, Android version and reproduction steps in [Issues](https://github.com/qiannianhuanxiang/DSHA/issues) or QQ group **975836806**.

The existing documentation below describes the earlier releases, including 1.1.10. The preview's scope is described above and in its release notes.

---

## What this is

DeepSeek Harness (`@deepseek-ai/dsh`) is DeepSeek's official agent harness — think Claude Code.
It targets glibc Linux, so running it on Android means hitting a wall of problems: native
modules that won't compile, `link(2)` blocked by SELinux, a sandbox that won't start,
a frontend laid out for desktops.

**DSHA packages all of that into one APK.** Install, paste your API key, press start —
no Termux, no root, not a single command. Inside is a complete Ubuntu 24.04 environment:
`apt` works, interactive PTYs work, native modules that need a compiler install fine.
It's the same thing you'd run on a server.

---

## Why DSHA

|  | |
|---|---|
| 🚫 **No terminal required** | The offline Ubuntu rootfs ships inside the APK. Nothing to install, configure, or type |
| 🐧 **A real glibc environment** | Not a trimmed-down one: `apt`, PTY, native modules, Python, git all present. Upstream plugins run unmodified |
| ⚡ **proroot: zero ptrace overhead** | Classic proot costs two context switches per syscall; proroot does in-process path translation via LD_PRELOAD + binary patching. Measured **+58%** across key operations on real hardware |
| 🔌 **ADB without Shizuku** | Wireless pairing and keep-alive are built in, so the agent can actually drive the phone — tap, screenshot, install apps |
| 💾 **Survives uninstall** | Conversations and settings live in `Documents/dshdata`, visible and backup-able from any file manager; the API key is encrypted with the Android Keystore |
| 🩺 **Tells you what broke** | 23 self-checks with one-tap repair, 15 self-healing scripts, and when the web UI won't start it names the plugin responsible |

---

## Get running in 30 seconds

1. Grab the latest APK from [Releases](https://github.com/qiannianhuanxiang/DSHA/releases/latest) (arm64 only)
2. First launch unpacks the bundled environment (a few minutes, once)
3. Paste your DeepSeek API key on the **Config** page → press start on the **Launch** page → the Web UI opens itself

That's it. If you'd rather see every step, use the step-by-step installer — each stage can be
reinstalled or updated on its own.

---

## What's in the box

DSHA isn't just "it boots". Everything below is implemented and shipping.

<details open>
<summary><b>① Environment &amp; installation</b></summary>

| Capability | Notes |
|---|---|
| Bundled offline rootfs | Ubuntu 24.04 arm64 inside the APK — full deployment works with no network |
| Step-by-step installer | rootfs / base tools / Node.js / harness as four independent stages, individually reinstallable, nothing downloaded twice |
| Parallel mirror benchmarking | Tsinghua, Aliyun, Huawei, Tencent, NJU, HIT, npmmirror… benchmarked in parallel, you pick from the results |
| Two install paths | Prebuilt package or from source; the source path handles native module builds (node-pty and friends) automatically |
| Verified as it goes | Every stage validates its own output — a half-finished install never reports success |
| Resumable | Fail or quit midway and you continue from that stage, not from scratch |

</details>

<details open>
<summary><b>② Runtime &amp; performance</b></summary>

| Capability | Notes |
|---|---|
| proroot / proot | proroot by default (no ptrace overhead), switchable back to proot from Config |
| Measured gains | vivo V2352A / Android 14: +58% overall on key operations, +94% on tar (that's what backups use), +82% on stat-heavy work (node module resolution) |
| Three fallbacks | Missing runtime files → silently back to proot; three consecutive boot failures → forced switch with an explanation; installation always uses proot |
| Node.js 24 + pnpm | Same runtime as upstream |
| Foreground service | Status visible in the notification shade; Android won't quietly reap it |
| Watchdog | If the web process dies it comes back on its own |

</details>

<details open>
<summary><b>③ Data safety &amp; migration</b></summary>

| Capability | Notes |
|---|---|
| Uninstall-proof data | Sessions / settings / attachments live in `Documents/dshdata`, with private symlinks left in place |
| Encrypted API key | Android Keystore (AES/CBC), key never leaves the Keystore; the copy inside backups is encrypted too |
| Full backups | Manual backups rotate through 10 slots; automatic backups **alternate between two slots**, so a complete previous copy always survives |
| Backups explain themselves | Each archive carries `DSHA-README.txt`: what's inside, how to pull data out by hand, what won't survive a device change |
| Pre-restore inspection | The whole archive is read once, read-only — gzip's CRC catches truncation and corruption — and you're told how many sessions, how large, from which version |
| Very forgiving restore | Old backups are always accepted; missing plugins reinstall in the background; `link:` paths are rewritten across devices; local-path plugin sources are inlined into the archive |
| Post-restore migration | Version adaptation runs automatically (retire replaced built-in plugins, restore current ones) and the bridge token is realigned |
| Credentials excluded | The machine's bridge token is kept out of backups — archives land in a public directory and shouldn't carry this device's credentials |
| Corrupt session quarantine | Broken session files move to `corrupt-backup`, retrievable any time, so one bad file can't hold the whole UI hostage |

</details>

<details open>
<summary><b>④ Device control (let the agent actually use the phone)</b></summary>

| Capability | Notes |
|---|---|
| Wireless ADB, built in | Pairing and keep-alive included — **Shizuku not required**. The agent can tap, swipe, screenshot, install apps, read logs |
| Shizuku channel | Kept as an alternative path for users who already have it |
| App bridge (127.0.0.1:3090) | The agent can post system notifications, read device info, and ask you for confirmation |
| Dangerous-command gate | Commands that overwrite critical paths or delete recursively get held for approval, over **three channels** — notification, in-app dialog, or the floating bar |
| Streaming floating bar | Model output scrolls across the top of the screen like song lyrics; shows the actual command being run, reasoning optional; background colour, opacity, line count and dwell time all adjustable, with a preview |
| Built-in terminal | Drop straight into the Ubuntu shell — `apt install` whatever you need |
| Root-free file access | The MT Manager file provider is injected, so you can browse and edit the app's private directory from a file manager |

</details>

<details open>
<summary><b>⑤ Reliability: it tells you what broke</b></summary>

| Capability | Notes |
|---|---|
| 23 self-checks + one-tap repair | Bridge, ADB, plugins, sessions, backups, runtime, public data, guard patches, web auth… each inspected, and whatever can be fixed is fixed on the spot |
| Plain-language plugin diagnosis | When the web UI won't start you get "it's plugin X, the service it wants doesn't exist, tap here to fix" — not a screen of Node stack traces |
| 15 self-healing &amp; patch scripts | pnpm shell restoration, bundle resolution repair, profile boot repair, `.l2s` chain flattening, session repair, dependency repair, filesystem write patches… |
| Signed incremental updates | Key scripts update from GitHub with **offline signature verification** (public key embedded; a bad signature rejects the whole batch), so fixes don't wait for a new APK |
| Failures written to disk | Backup, install and boot failures record their reason to a file that the self-check reads — "nothing happened" never stays unexplainable |
| CI gatekeepers | Every push runs Fast checks: manifest consistency, offline signature verification, 300 pure-logic assertions, and real compilation of every asset script. Releases abort outright on a certificate fingerprint mismatch |

</details>

<details open>
<summary><b>⑥ Network &amp; access</b></summary>

| Capability | Notes |
|---|---|
| Embedded WebView | GeckoView, so you're not at the mercy of the system WebView version |
| LAN access | Keep dsh on the phone, use it from a laptop or tablet browser. Token auth is fail-closed and sets a `SameSite=Strict` cookie on first hit so the token can't leak through outbound links |
| One-tap addresses | Copy the local or LAN URL (token included) from the launch page; refreshed by heartbeat |
| Old-browser polyfills | `AbortSignal.any/timeout` and `crypto.randomUUID` are injected automatically — the latter is mandatory over LAN HTTP, which is not a secure context |
| Configurable port | Set the web port yourself; conflicts fall back with an explanation |

</details>

<details open>
<summary><b>⑦ Plugin ecosystem</b></summary>

| Capability | Notes |
|---|---|
| Plugin marketplace | Browse, install, update, enable/disable, remove — all in-app |
| Mobile UI adaptation built in | Ships [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile) (MIT): single column on narrow screens, directory as a drawer, settings as a bottom sheet, status-bar safe area, readable tables and bubbles |
| Device skill guide built in | Tells the agent which device capabilities are available here |
| Hard dependencies rewritten | Plugins that hard-require a service get rewritten to runtime injection — one plugin should not take the whole plugin tree down with it |
| Built-ins protected | Built-in plugins can't be deleted by accident, and ones you disabled stay disabled across upgrades |
| Import / export | Plugin configuration can be exported and restored |

</details>

<details open>
<summary><b>⑧ For developers &amp; agents</b></summary>

| Capability | Notes |
|---|---|
| [AGENTS.md](AGENTS.md) | Entry document for AI and new contributors: structure, contracts, known traps — skip the full-repo scan |
| Agent skills | [`agent-skills/`](agent-skills/) ships `device-shell` (ADB / Shizuku bridge) and `screen-ocr-operator` (OCR + batched screen actions) |
| Pure-logic test suite | 300 assertions with no Android dependency; `bash tools/pure-logic-test.sh` finishes in seconds |
| Activity log | Key actions and failure reasons are recorded, so bug reports have something to stand on |
| Build entirely in CI | No computer needed: push a tag and a signed APK comes out, with an arm64 runner building the rootfs |

</details>

---

## How this compares

There are two ways to run dsh on Android. Both pay for something, and saying so is more
useful than trading labels:

| | **Container approach** (DSHA) | **Termux bootstrap approach** |
|---|---|---|
| Method | proot/proroot + full glibc rootfs | Termux packages running natively on Android bionic |
| Setup | Install an APK | Install Termux → type commands → install a toolchain |
| Environment | Complete Ubuntu; `apt` and native modules just work | Every native module needs a bionic patch or rebuild |
| Overhead | proroot has no ptrace cost | No container layer at all, theoretically fastest |
| Sandbox | Available | bubblewrap blocked by sepolicy, degrades |

Credit where it's due: the Termux side has come a long way.
[deepseek-harness-termux](https://github.com/Vengisk/deepseek-harness-termux) compiles node-pty
with exact upstream patches and swaps `link(2)` for `rename(2)` to get around SELinux;
[deepseek-harness-android](https://github.com/FunnelCakes/deepseek-harness-android) keeps a
genuinely useful compatibility matrix. "Termux builds are feature-crippled" is an outdated claim.

**DSHA's difference isn't that they can't run it.** It's that setup needs no command line,
the environment is real glibc rather than patches holding bionic together, and the app layer
brings things a script can't: direct ADB, the floating bar, the backup/restore system,
self-checks and self-healing, the plugin marketplace.

---

## Known limits

Listed up front so you don't discover them after installing:

| Item | Status | Notes |
|---|---|---|
| Architecture | ⚠️ arm64-v8a only | No 32-bit or x86 devices |
| OS | ✅ Android 8.0+ | Older versions untested |
| Download size | ⚠️ ~370 MB | The price of bundling a full Ubuntu — and what buys you the zero-setup install |
| bash sandbox | ⚠️ Unavailable | Android sepolicy blocks bubblewrap, so dsh runs with `danger-full-access`. Judge the risk yourself |
| Zhuoyitong / HarmonyOS anco | ❓ Unverified | Should work in theory, no device regression yet |
| Floating bar | ⚠️ Needs permission | Drawn with `TYPE_APPLICATION_OVERLAY`; the real status-bar ticker API isn't reachable without root |
| Data location | ⚠️ Needs file permission | If "All files access" is denied, data stays private and dies with uninstall (the self-check states which case you're in) |

👉 For **what each permission actually exposes and what the agent can reach on your phone**, see the [security model](docs/security-model.en.md) — including the weaknesses we list ourselves.

---

## Architecture

```
┌──────────────────────── APK ────────────────────────┐
│ Native Android (Java 17) · Material3 · GeckoView    │
│  ├ Launch / Install / Config / Workspace / Plugins  │
│  │   / Terminal / Settings                          │
│  ├ Foreground service + watchdog + notifications    │
│  ├ App bridge :3090    LAN bridge :3081             │
│  └ Floating bar (TYPE_APPLICATION_OVERLAY)          │
├─────────────────────────────────────────────────────┤
│ proroot (default, no ptrace cost) / proot (fallback) │
├─────────────────────────────────────────────────────┤
│ Ubuntu 24.04 arm64 · Node.js 24 · pnpm              │
│  └ @deepseek-ai/dsh  →  Web UI :3080                │
└─────────────────────────────────────────────────────┘
```

Data: sessions / settings / attachments in `Documents/dshdata` (visible, backup-able).
`DSH_HOME` itself and `.credentials.yaml` deliberately stay private.

---

## Wireless ADB pairing (device shell)

Once paired, the agent can drive this phone directly — **no Shizuku needed**.

**First time (about a minute)**

1. Settings → About phone → tap "Build number" seven times to unlock developer options
2. Developer options → enable **Wireless debugging**
3. Open Wireless debugging → "Pair device with pairing code" → note the **IP:port** and the **6-digit code**
4. Back in DSHA → **Workspace** page → ADB section → enter both → pair

**After that** DSHA maintains the connection itself (keep-alive plus reconnect) and recovers
after a reboot. Nothing more to do.

**Verify**: run `adb shell id` in the built-in terminal; `uid=2000(shell)` means you're set.

**Give it to your agent**: copy the skills into your agent's skill directory:

```bash
cp -r agent-skills/device-shell ~/.agents/skills/
cp -r agent-skills/screen-ocr-operator ~/.agents/skills/
```

---

## Building

**In CI (recommended — no computer required)**

```bash
git tag v1.2.3 && git push origin v1.2.3   # triggers the release pipeline, signed APK comes out
```

Two stages: `ubuntu-24.04-arm` builds the rootfs natively on arm64 (cached), then
`ubuntu-latest` packs it into the APK and verifies the certificate fingerprint.
Pushing to `main` also runs a debug build plus Fast checks.

**Locally** (Gradle 8.5 + Android SDK + JDK 17)

```bash
./build.sh                      # needs app/src/main/assets/offline-rootfs.tar.gz first
bash tools/pure-logic-test.sh   # 300 pure-logic assertions, no device needed
```

---

## Credits

- [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) — the harness itself
- [proot](https://github.com/termux/proot) / [proroot](https://github.com/coderredlab/proroot) — root-free containers (see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md))
- [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile) — bundled mobile adaptation
- [Shizuku](https://shizuku.rikka.app/) — alternative device command channel

## Community

QQ group **975836806** — test builds, bug reports, plugin talk.

## License

[MIT](LICENSE). Third-party licenses in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Star History

<a href="https://github.com/qiannianhuanxiang/DSHA/stargazers">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/star-history-dark.svg" />
    <source media="(prefers-color-scheme: light)" srcset="docs/star-history.svg" />
    <img alt="DSHA Star History" src="docs/star-history.svg" width="820" />
  </picture>
</a>

<sub>Generated weekly by [`tools/gen-star-history.py`](tools/gen-star-history.py)
([workflow](.github/workflows/star-history.yml)). Since 2026-06-30 GitHub restricts the
stargazers API to a repository's own admins and collaborators, which breaks third-party
live-embedded charts — so we fetch the data and draw the chart ourselves.</sub>

