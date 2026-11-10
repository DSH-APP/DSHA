# DSH-A-Su · DSHA Sunset Mod

> A third-party **Mod** of [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) — not an official DSHA distribution.
> Runs the complete [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) on Android: no root, no Termux, works right after install.

## Status

| Item | Value |
|---|---|
| App name | **DSH-A-Su** (test variant: `DSH-A-Su Test`) |
| Version | `0.2.1-rc.5-20261110.1700-dsh0.2.2-rc.1` |
| versionCode | 356 |
| Package | `io.github.sunsetrne.dsha` (test variant `io.github.sunsetrne.dsha.test`) |
| Coexistence | Fully independent from the original `dsh.android.client` and the older `com.dsh.client`; data and system grants are not shared |
| Signing | Mod release certificate, SHA-256 `a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d` |
| System | Android 11+ (API 30) / arm64-v8a; **Android 10 and below are no longer supported** |
| Main branch | `main` (the former `Sunset-3090-Q` was merged and removed) |
| License | MIT (inherited from upstream) |

## What this is

DeepSeek Harness is DeepSeek's official agent harness, written for glibc Linux; running it on Android directly runs into native-module builds, SELinux restrictions, sandbox startup and desktop-oriented layout.

DSHA wraps all of that into a single APK: a complete Ubuntu environment where `apt`, interactive PTYs and native modules that need compiling all work. On first launch it unpacks and verifies the bundled offline environment; models and API keys are then configured in the Web UI — no Termux, root or command line required.

This repository is a Mod maintained on top of the original; the changes focus on the terminal, device capabilities and the mobile UI.

## Shape of this Mod (build209 and later)

- **Own identity**: app name `DSH-A-Su`, package `io.github.sunsetrne.dsha`, a newly created release key. It installs next to the original without touching its data, grants or signature chain; migration is export-a-backup → import into the new package.
- **Single Standard variant**: only the Android 11+ / arm64 system-WebView build remains; the `low` compatibility line and GeckoView are removed, and no Android 10-or-below package is produced.
- **Island edition folded in**: task-status island (Live Updates / Dynamic Island) and multi-select/custom answers in notifications are built into the regular package; the separate island package name is gone.
- **Variant-neutral accessibility declaration**: the service is declared in `src/main`, does not restrict `packageNames` and is not replaced per flavor; its component is derived from the runtime package name, so the regular, test and debug packages can each be enabled independently without displacing one another.
- **Public fixed build inputs**: the offline rootfs, DSH runtime, Ubuntu tools and other large files ship as a public pre-release asset; `ci/sunset-inputs.lock.json` pins its SHA-256, so a clone can restore them and build the full APK (see [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md)).
- **All 10 UI languages kept**: zh / zh-Hant / en / ru / ja / ko / ar / pt / la / fr; short UI labels in fr, pt, ru, ar and la were shortened for screen space, without changing long-form meaning.

## Mod highlights

- **Three-mode root command policy**: strict (default allow-list), relaxed (blocks irreversible actions) and fully relaxed. Root channel only; Shizuku / ADB stay strict, and SMS reads always require native authorization.
- **Terminal**: standalone full-screen terminal, session sidebar (three states, landscape compression), extra keys, an output "more" panel, output preview / copy / share / save and a responsive sidebar.
- **Mobile UI v5**: plugin page cards and filters, componentized settings sub-pages, page and dialog transitions, unified launch state and floating-ball details.
- **Device capabilities**: accessibility read/tap, ADB / Shizuku / Root command channels, SMS read gate, virtual display, notifications, clipboard and sensors.
- **Web UI and access**: mobile-adapted interface, LAN access with authentication, dynamic ports.
- **Backup and restore**: encrypted backups (v5 format), upgrade and restore, external-workspace protection.
- **Plugin ecosystem**: bundled plugins, plugin package installation, permission and state management.
- **Reliability and recovery**: least-privilege model, self-check diagnostics, emergency recovery entry and background Web recovery.

## Branches

The public repository keeps a single `main` branch (the former `Sunset-3090-Q` carried the same content and was merged and removed):

| Branch | Purpose |
|---|---|
| `main` | Default and mainline: terminal, UI, device capabilities, root three-mode policy and the Mod identity change |

Per-version changes and verification boundaries are recorded in [CHANGELOG.md](CHANGELOG.md) and [docs/releases/](docs/releases/).

## Install and migrate

1. Get an APK (this repository does not host a permanent APK asset; see the build section below, or use your own CI output).
2. On first launch the bundled offline environment is unpacked and verified; the main screen then appears and the Web service starts in the background.
3. Open the Web UI and configure provider, model and API key in settings.
4. To migrate from the original DSHA: export a backup there first, then import it in DSH-A-Su. Data, system grants and accessibility switches are not inherited across packages, and both apps can coexist.
5. To upgrade from an older DSH-A-Su build: the package name and the Mod release key recorded here must match.

## Build from source

Requires JDK 17, Node 24, Python 3.9+, Android SDK 37 and NDK 26. Restore the fixed inputs from the public pre-release asset; toolchain and signing are documented in [BUILD.md](BUILD.md) and [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md).

```bash
python3 tools/ci-assets.py --url "$(python3 -c 'import json;print(json.load(open("ci/sunset-inputs.lock.json"))["url"])')" \
  --sha256 "$(python3 -c 'import json;print(json.load(open("ci/sunset-inputs.lock.json"))["sha256"])')"
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
./gradlew :app:testStandardDebugUnitTest :app:lintStandardRelease
./gradlew :app:assembleStandardRelease
```

On Windows use `gradlew.bat`. `Debug` in test task names only selects the test variant; it does not require building or installing a debug APK. Signing keys, user data, device screenshots and logs are never committed.

## Documentation

| Document | Content |
|---|---|
| [AGENTS.md](AGENTS.md) | Project layout, startup contract and technical constraints (contributor entry point) |
| [docs/en/index.md](docs/en/index.md) | Complete English documentation index |
| [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md) | Public pre-release asset for fixed build inputs, restore/update flow |
| [docs/i18n-length-policy.md](docs/i18n-length-policy.md) | UI copy length budget and multilingual shortening policy |
| [docs/security-model.en.md](docs/security-model.en.md) | Security model: permission boundaries and known weaknesses |
| [docs/plugins.md](docs/plugins.md) | Plugin installation and packaging requirements |
| [BUILD.md](BUILD.md) / [CONTRIBUTING.md](CONTRIBUTING.md) | Build, signing and contribution workflow |
| [CHANGELOG.md](CHANGELOG.md) | Full change history |

## Compatibility and known limitations

- arm64, Android 11+ only; permissions, background behaviour and power policies differ notably between ROMs.
- Virtual display, root policy modes and SMS reads depend on the OS version and native authorization state.
- Some vendor native dependencies are not guaranteed to be fully reproducible from source; the first offline-environment unpack takes a while.
- The desktop/PC connection capability has been removed from the current version; phone control and local/LAN access remain.

## Credits and license

This project is a Mod derived from [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) and keeps the original [MIT](LICENSE) license (Copyright (c) 2026 qiannianhuanxiang), preserving the original copyright and license notices.

The upstream harness is [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness); the container layer uses [proot](https://github.com/termux/proot) / [proroot](https://github.com/coderredlab/proroot), the terminal is based on [Termux terminal-view](https://github.com/termux/termux-app), the mobile adaptation is based on [dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile), and the fallback device channel is [Shizuku](https://shizuku.rikka.app/). See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the full third-party notices.

Thanks to the original author [@qiannianhuanxiang](https://github.com/qiannianhuanxiang) and all contributors.
