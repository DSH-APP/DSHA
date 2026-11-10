# DSH-A-Su build and signing instructions

> Current identity (build356): `DSH-A-Su` / `io.github.sunsetrne.dsha` / `0.2.1-rc.5-20261110.1700-dsh0.2.2-rc.1` / versionCode 356.
> Single Standard variant: minSdk 30 (Android 11+), arm64-v8a, system WebView. The old `low`/GeckoView line and the separate island package are gone.
> Signing: a newly created Mod release certificate (SHA-256 `a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d`); the original E7E3 key is no longer used.
> Fixed build inputs: published as a public pre-release Release asset and pinned by SHA-256; see [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md).
> Historical build receipts and old identity numbers remain in [CHANGELOG.md](CHANGELOG.md) and [docs/releases/](docs/releases/); they are not inputs for the current build or signing identity.

Read [README](README.en.md), [AGENTS](AGENTS.en.md) and [CONTRIBUTING](CONTRIBUTING.en.md) first. `app/build.gradle`, `ci/release-identity.json` and `ci/sunset-inputs.lock.json` are the three authoritative sources for the current build identity.

## Environment and variants

| Input | Requirement |
|---|---|
| JDK / native language | Java 17, no Kotlin |
| Gradle / AGP | Repository Wrapper 9.3.1 / AGP 9.1.1 |
| Android SDK | `platforms;android-37.0`, build-tools 36.0.0; compile/target API 37 |
| NDK | 26.3.11579264 (only for native builds; the repo ships prebuilt arm64 JNI) |
| Python / Node | Python 3.9+ for generators (`DSHA_PYTHON`); Node 24; CI pins in `ci/toolchain.lock.json` |
| Standard | minSdk 30, system WebView, arm64-v8a (the only variant) |

There is one Standard build line. The test variant only changes package/version via `-Pdsha.sunsetTest=true` (`io.github.sunsetrne.dsha.test`); it coexists with the release package and has isolated data. The original `dsh.android.client` and the older `com.dsh.client` are separate packages that this Mod does not overwrite.

Set `sdk.dir` in local `local.properties`, and point `JAVA_HOME`, `ANDROID_SDK_ROOT` (or `ANDROID_HOME`) and `DSHA_PYTHON` at real tools. Do not copy another developer's absolute toolchain paths. `app/build.gradle` defines `versionCode`, `versionName` and `dshaApkNameVersion`; this page does not maintain a competing set of numbers.

## Fixed build inputs

The offline rootfs, DSH runtime, Ubuntu tools, pnpm, Python support, glibc Python, ADB wheels and recovery archives are not committed to Git. `tools/ci-assets.py` restores them according to `ci/sunset-inputs.lock.json`, which pins the public pre-release asset URL, SHA-256, byte count, format and Release/asset names.

```bash
python3 tools/ci-assets.py \
  --url "$(python3 -c 'import json;print(json.load(open("ci/sunset-inputs.lock.json"))["url"])')" \
  --sha256 "$(python3 -c 'import json;print(json.load(open("ci/sunset-inputs.lock.json"))["sha256"])')"
python3 tools/prepare-runtime-descriptor.py --check   # use --write after changing inputs and review the diff
```

See [docs/fixed-build-inputs.md](docs/fixed-build-inputs.md) for the contents, restore flow, upgrade/rollover procedure and integrity requirements. A source-only checkout without the fixed inputs can run source-only checks but cannot produce a complete APK.

## Software validation

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
python3 tools/check-source-hygiene.py
python3 tools/check-ui-i18n.py
python3 tools/localize-android-ui.py --resources-only --check
python3 tools/generate-changelog.py --check
./gradlew :app:testStandardDebugUnitTest :app:lintStandardRelease
python3 tools/run-host-tests.py --stage runtime
python3 tools/verify-stability.py --stage software
```

`preBuild` in `app/build.gradle` also runs managed-asset, built-in plugin, credential-rule, proroot, language and changelog gates; do not bypass a failing gate to produce a package. On Windows use `gradlew.bat`. When AGP/aapt2 is unavailable, `tools/run-unit-tests.py` is a pure-logic fallback, not a replacement for full Android compilation, APK checks or resource gates.

## Release packaging and signing

Explicitly supply `DSHA_KEYSTORE`, `DSHA_KEYSTORE_PASSWORD` and `DSHA_KEY_PASSWORD`; the alias defaults to `dsha-a-su` and can be overridden with `DSHA_KEY_ALIAS`. `ci/release-identity.json` is authoritative: `packageName = io.github.sunsetrne.dsha`, `certificateSha256 = a4b0413c67fa46dd30dc7e2bce3ad56151ed8bfde2a3315fff685baf786b5d5d`.

Before `assembleStandardRelease`, `packageStandardRelease` and `validateSigningStandardRelease`, `verifyPublishCertificate` checks the keystore certificate and the applicationId against that record and stops packaging on any mismatch. Historical E7E3 or a debug certificate can no longer produce a release package. Private keys are never committed; rotating the key prevents over-the-top upgrades of already installed Mod builds.

```bash
./gradlew :app:assembleStandardRelease
```

The artifact is `app/build/outputs/apk/standard/release/dsha-<dshaApkNameVersion>.apk` and must ship with a `.sha256` computed from the actual file. Verify the real APK's package name, versionCode/versionName, `debuggable=false`, arm64 ABI, certificate SHA-256 and managed-asset/recovery-archive digests. Intermediate `app/build` packages and unsigned CI packages (`-Pdsha.unsignedPackage=true`) are never presented as releases.

## Phones and historical scope

This build change does not include device installation, start/stop, input or model-conversation acceptance. User data, sessions, configuration, plugins and unknown runtime state are preserved; a “normal Stop” message alone does not prove exit. Historical device receipts bind their own APK and time and cannot become new-package acceptance.

Linux tmpfs publication tests do not prove persistent-disk, power-loss or Android FUSE behavior. Android 10 and below are outside the supported range; Android 6/7, vendor Root/Shizuku and 16 KiB matrices remain historical records only. See the [module map](docs/en/module-map.md) and [security model](docs/en/security-model.md) for responsibilities and controls, and the [project handbook](docs/en/project-architecture-handbook.md) and [documentation index](docs/en/index.md) before maintaining source.
