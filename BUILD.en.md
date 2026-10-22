# Current DSHA build instructions

> Current public release: [v0.2.1-rc.2 / exact2100 Standard](docs/en/releases/v0.2.1-rc.2-notes.md), published2026-10-23. Use the matching public tag or the complete source attachment with fixed offline inputs. Earlier private-only paths and delivery notes below retain their historical scope; signing credentials are not included.

> Current publication identity: **`dsh.android.client`**, retaining version/code190, E7E3 and all features. This supersedes historical package rules below. Old `com.dsh.client` data are separate; use backup export/restore to migrate. This publication is authorized for DSH-APP/DSHA and the existing private maintenance repository. [Current record](docs/en/releases/v0.2.1-rc.2-build190-package-rename-20261021.md).


Current F13 frozen inventory: 2215 source members / 200 Markdown documents; 1029 adopted Java files. Distribution/format proofs and JUnit execution counts are separate. The fixed packet is 738,101,893 bytes, SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`. Originals expand directly at project paths, not as a nested packet. These are not the complete source ZIP's size or digest.

> **F13 `0.2.1-rc.2-20261021.2107-dsh0.2.1-rc.2` / code190.** Directory-service composition, attachment-resolver disposal and recovery after transient background proof failure are fixed. Standard 150 selected classes/952 JUnit cases (3 skipped); Low 150 selected classes/952 cases (76 skipped). Both have zero failures/errors. Release Lint has zero errors and Standard/Low warnings 249/162; eight actual-APK gates passed. Original failures, corrections and negative controls remain separate; this is not one continuous green run. No device/ADB, real-account/model or new endurance acceptance. [Associated Release target](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.2107) · [Scope and evidence](docs/en/releases/v0.2.1-rc.2-build190-fix13-20261021.md). Actual remote availability is established by the Release and independent receipt.

Published F12 retains its original identity: [immutable English record](docs/en/releases/v0.2.1-rc.2-build190-fix12-20261021.md) · [original Release](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.1413). F11 remains an unpublished local checkpoint.

Read [README](README.en.md), [AGENTS](AGENTS.en.md), [CONTRIBUTING](CONTRIBUTING.en.md), and the [handoff guide](docs/en/handoff.md) first. `app/build.gradle` defines the application version; `tools/dsh-runtime/package.json` and its lock define DSH dependencies. The current report defines complete delivery and unverified scope. Use [README](README.en.md) and the [release introduction](docs/en/release-introduction.md) for final current identity, rather than treating the previous snapshot below as a new publication claim.

**Previous F11 source snapshot:** `0.2.1-rc.2-20261021.1157-dsh0.2.1-rc.2` / code190 fixed LAN, official Web login, the first-use notice, plugin provenance, and host recovery, and added 21 topic documents. Both variants ran 143 classes / 881 JUnit tests each, with zero failures/errors (Standard 3 skips; Low 76); Release Lint had zero errors and 249/162 warnings, and eight actual-APK gates passed. Original failures, reruns, and host-port contention limits were retained separately. That work did not include device/ADB, real-account/model conversations, or endurance acceptance. The [historical F11 record](docs/releases/v0.2.1-rc.2-build190-fix11-20261021.md) preserves that checkpoint's scope and evidence. Its 1157 Release URL was prepared but F11 was not uploaded; it is not a download or remote-publication receipt. F10 remains historical too.

The maintained local source is `C:\Users\18768\.codex\worktrees\build190-from170\DSHA_RESTART`. The old build173 checkout at `F:\DSHA_RESTART` is not the build source. On other machines, use the final tag/full commit in the private repository or its matching complete source attachment.

## Environment and variants

| Input | Requirement |
|---|---|
| JDK / native application language | Java 17, no Kotlin |
| Gradle / AGP | Repository Wrapper 9.3.1 / AGP 9.1.1 |
| Android SDK | `platforms;android-37.0`, build-tools 36.0.0; compile/target API 37 |
| NDK | 26.3.11579264; terminal and Low proot use their managed entry points, API 23, and real ELF-alignment checks |
| Python / Node | Python 3.9+ for generators; CI pinning is in `ci/toolchain.lock.json`; Node 24 |
| Standard | minSdk 30, system WebView, arm64-v8a |
| Low | minSdk 23, additional Gecko fallback engine, arm64-v8a |

Both variants share `com.dsh.client` and user data and cannot be installed side by side. Minimum API, ELF 16 KiB alignment, and actual Android 6/7/16 KiB device usability are separate evidence. Windows supports development/builds; Windows skips cannot stand in for Linux-specific tests.

Set `sdk.dir` in local `local.properties`, and point JAVA_HOME, ANDROID_SDK_ROOT/ANDROID_HOME, and DSHA_PYTHON to installed tools. Do not copy another developer's absolute toolchain paths. `app/build.gradle` defines `versionCode`, `versionName`, and `dshaApkNameVersion`; this page does not maintain another competing set of current version numbers.

## Input preparation and proofs

Git retains the complete application, resources, locked generators/checkers, and small precompiled JNI/vendor/interpreter dependencies required for builds and covered by notices/locks. Large generated rootfs, DSH, and recovery archives are excluded from Git. Retain the actual restrictions on unknown-source or closed-source vendor inputs; do not claim all native vendor code is reproducible from source. See [CONTRIBUTING](CONTRIBUTING.en.md) for exact boundaries and nine explicit audit-JSON exceptions: six build-input metadata files plus three historical-document/provenance metadata files, without opening the whole audit directory.

The complete Release source ZIP additionally contains the original files from the fixed input package `locked-current-build-inputs.zip`, expanded directly into project paths rather than embedding that ZIP. **Historical F11 input identity:** 738,103,383 bytes, SHA-256 `f482503ac88b71b2f31cee802df557c637589dfeee69aca2ee8900608f8cdba3`, verified by `tools/ci-assets.py --pack` against every SHA. This is not the size of the complete source ZIP. Older 738,099,607/738,099,619-byte packages and their 12-byte line-ending difference belong to F10 or earlier and cannot replace a later input identity. Configure the toolchain and follow the current recipe; no separate bundle is needed. A Git clone supports source-only checks, while complete packaging still needs the fixed original inputs. The APK's transformed layers cannot recover the entire original rootfs; recovering every input from the APK is not guaranteed.

Distributable source uses actual LF bytes. Eleven managed/canonical bytepins in `.gitattributes` and historical originals retain exact bytes; do not normalize signed inputs across the repository. Nineteen small binary inputs remain as listed and are not rewritten by text newline rules. Shared UUID validation comes from Ids; four call sites have their shared dependency restored while preserving historical UUID format.

The previous F11 Java adoption scope had 1023 members with a complete check record. The final Java leaves were freshly format-checked at their final bytes; remaining source SHAs matched the complete-check proof. Its source-distribution snapshot had 2135 members / 135 Markdown files, including 21 newly created documents; a 22-entry document allowlist included the already-existing F11 release page. Distribution counts, formatter/token proofs, and JUnit execution counts are separate. Use the final frozen receipt for the current inventory rather than reusing those historical counts.

Historical F8's 999-member Java proof, earlier staged read/style-equivalence proofs, and F7 host-Core rebinding failure/fix positive and negative controls remain with their original records. They are not the current adoption scope or test count. Old software, APK, and device conclusions each belong to their original source/digest. A single delivery receipt binds final software and the precise APK.

Ubuntu base identity is independent of DSH and UI versions. `offline-rootfs.bin` / `dsh-runtime.bin` use split-runtime-v1. `tools/recovery-runtime/lock.json` constrains independent emergency pinned inputs; the main environment cannot become a rescue dependency. A source checkout without large archives is not a complete APK and cannot skip integrity gates.

When managed inputs change, update assets through their actual generators, then explicitly generate proofs:

```bash
python3 tools/prepare-backup-assets.py --write
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/prepare-test-runtime.py
```

Check generated proofs against actual bytes. Normal Gradle builds use check mode; an old cache must not skip new inputs. `app/build/test-runtimes/current.json` and source/content fingerprints constrain the current fixture. An arbitrary old directory is not the current runtime.

## Software validation

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
python3 tools/check-source-hygiene.py
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  :app:lintStandardRelease :app:lintLowRelease
python3 tools/run-host-tests.py --stage runtime
python3 tools/verify-stability.py --stage software
```

On Windows use `gradlew.bat` and a real Python executable instead of an unavailable system alias. When AGP/aapt2 cannot run, `tools/run-unit-tests.py` is a pure-logic fallback, not a replacement for full Android compilation, APK checks, or resource gates. Classify platform skips, unrun items, and Lint warnings honestly rather than calling them passes.

`tools/verify-plugin-upgrade-gate.py` checks automatic commit/enable behavior, user disabling/safe mode, actual dependencies, plugin transactions, and managed assets. Production plugins permit unlocked resolution, lifecycle scripts, and pnpmfile. Do not revive historical “native review/dependency freeze” test criteria as current requirements.

## Release packaging and signing

Explicitly supply existing `DSHA_KEYSTORE`, `DSHA_KEYSTORE_PASSWORD`, and `DSHA_KEY_PASSWORD`; the historical `DSHA_KEY_ALIAS` may be configured. `ci/release-identity.json` defines signing identity. Retain E7E3 rather than substituting debug, A3, or a new certificate. A key filename containing debug does not make a release APK debuggable; verify actual `debuggable=false`.

```bash
./gradlew :app:assembleStandardRelease
```

The delivery scope is Standard. Software validation covers affected tests and Release Lint for both variants, plus actual Standard APK/assets/ELF, signing, licensing, plugin/emergency gates, and exact digests. Record selected JUnit classes, skips, cache reuse, and unrun scope separately, without claiming a clean whole-repository or fully cold-network build. JNI static auditing uses the matching Release APK, not an old Debug product. Preserve old Low bytes; Low software checks are not a new Low delivery or device acceptance.

The local Standard release target is `F:\DSHA_RESTART\release\0.2.1-rc.2\dsha-0.2.1-rc.2.apk`; verify its digest locally and with the release record. Retain historical versions and old Low bytes, and do not present an intermediate `app/build` APK as delivery. The private repository excludes large generated archives. A Release attaches only the release APK and complete source ZIP with fixed build inputs. Put SHA-256 in the Release body; the source ZIP's own README must not contain that ZIP's digest, avoiding self-reference. Do not upload private keys, user data, device screenshots/logs, or historical forensic material. Nine necessary audit metadata files, 27 historical build sources, and one website test fixture remain under the delivery inventory; this does not distribute historical device scenes. 2054, 2330/F9, 0140/F10, and the 1157/F11 source snapshot retain their own historical identity. The linked current report, Release, and independent external receipt bind final software/APK results, source commit, attachments, and remote state. An older APK/report cannot replace that proof.

An unsigned complete `ci-package` build is for software verification only, never phone installation or release. Configuration files do not prove protected environments, online signing execution, or repository protection are configured. Deployment and publication need actual evidence.

## Phones and historical scope

All phone operations are paused: no upgrade installation, start/stop, input, or model conversation, and no reconnecting ADB for evidence. Retain user data, sessions, configuration, plugins, and unknown runtime state. A “normal Stop” message alone does not prove exit. Historical device receipts bind their own APK and time and cannot become new-package acceptance.

Old build130/build142 and early debug/separate-audit APK procedures/results belong to their original dates and artifacts. Exact bytes of the older build guide are preserved at `tools/history/documents/BUILD-build155.md`, with its digest in the [historical preservation inventory](docs/audits/build156/source-docs-retained.json); that original is excluded from deployment and test discovery. Continue protecting historical backups, configuration, old data, and unknown originals without reactivating retired tools.

Linux tmpfs publication tests do not prove persistent-disk/power-loss/Android FUSE behavior. Android 6/7, vendor Root/Shizuku, 16 KiB, and external models require their own receipts. See the [module map](docs/en/module-map.md) and [security model](docs/en/security-model.md) for responsibilities and controls.

Before maintaining source, consult the [project handbook](docs/en/project-architecture-handbook.md) and [documentation index](docs/en/index.md). Engine, port, and DNS pages separate current defaults from historical migrations.

Published F12 source publication preserved twelve exact managed/canonical bytepins. `plugin-manager.py` is verified against its runtime-descriptor digest; Git must retain its packaged bytes. The native plugin-manager test receives only CRLF-to-LF normalization after software acceptance. These source-publication changes do not alter APK inputs.
