# Build, validate, and submit

> Current publication identity: **`dsh.android.client`**, retaining version/code190, E7E3 and all features. This supersedes historical package rules below. Old `com.dsh.client` data are separate; use backup export/restore to migrate. This publication is authorized for DSH-APP/DSHA and the existing private maintenance repository. [Current record](docs/en/releases/v0.2.1-rc.2-build190-package-rename-20261021.md).


Current F13 frozen inventory: 2215 source members / 200 Markdown documents; 1029 adopted Java files. Distribution/format proofs and JUnit execution counts are separate. The fixed packet is 738,101,893 bytes, SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`. Originals expand directly at project paths, not as a nested packet. These are not the complete source ZIP's size or digest.

> **F13 `0.2.1-rc.2-20261021.2107-dsh0.2.1-rc.2` / code190.** Directory-service composition, attachment-resolver disposal and recovery after transient background proof failure are fixed. Standard 150 selected classes/952 JUnit cases (3 skipped); Low 150 selected classes/952 cases (76 skipped). Both have zero failures/errors. Release Lint has zero errors and Standard/Low warnings 249/162; eight actual-APK gates passed. Original failures, corrections and negative controls remain separate; this is not one continuous green run. No device/ADB, real-account/model or new endurance acceptance. [Associated Release target](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.2107) · [Scope and evidence](docs/en/releases/v0.2.1-rc.2-build190-fix13-20261021.md). Actual remote availability is established by the Release and independent receipt.

Published F12 retains its original identity: [immutable English record](docs/en/releases/v0.2.1-rc.2-build190-fix12-20261021.md) · [original Release](https://github.com/ym2025szz/DSHA-prerelase/releases/tag/v0.2.1-rc.2-20261021.1413). F11 remains an unpublished local checkpoint.

Read [README](README.en.md), [AGENTS](AGENTS.en.md), and the current [release introduction](docs/en/release-introduction.md). Decisions from another conversation inside historical audit attachments do not override the user's requirements here.

**Previous F11 source snapshot:** `0.2.1-rc.2-20261021.1157-dsh0.2.1-rc.2` / code190 fixed LAN, official Web login, the first-use notice, plugin provenance, and host recovery, and added 21 topic documents. Both variants ran 143 classes / 881 JUnit tests, zero failures/errors (Standard 3 skips, Low 76). Release Lint had zero errors, 249/162 warnings, and eight actual-APK gates passed. Original failures, reruns, and host-port contention limits remain separate. That work excluded device/ADB, real-account/model conversations, and endurance acceptance. The [historical F11 record](docs/releases/v0.2.1-rc.2-build190-fix11-20261021.md) preserves its local scope/evidence. F11 was not uploaded; its prepared 1157 Release URL is not proof of download availability or remote publication. F10 remains historical. Current final version/results come from README and the release introduction, not these previous numbers.

Use the [current handoff guide](docs/en/handoff.md) for project-specific operations, the [module map](docs/en/module-map.md) for ownership, and the [engineering standard](docs/en/engineering-standard.md) / [ADR 0001](docs/en/adr/0001-build154-boundaries.md) for constraints. Retired tools and exact document originals live in `tools/history` with their preservation inventories; they do not enter APKs or automatic test execution.

## Source and tool identity

Use Java 17 and a single `:app` module. Standard uses system WebView and requires API 30. Low requires API 23 and includes Gecko fallback. Both use `com.dsh.client`, arm64 only.

- Application version and fixed filename rules: `app/build.gradle`.
- DSH version and npm content: `tools/dsh-runtime/package.json`, `package-lock.json`.
- Bundled tools: `tools/runtime-tools.lock.json`.
- Built-in plugins: `app/src/main/assets/builtin-plugins.json`.
- Active/specialized/retired runtime patches: `app/src/main/assets/runtime-patches.json`.
- Independent emergency archives: `tools/recovery-runtime/lock.json`. Updating the main environment must not silently replace this lock.
- CI tools and historical release certificate: `ci/toolchain.lock.json`, `ci/release-identity.json`.

Normalizable source uses actual LF. Nineteen necessary small binaries and historical originals retain exact bytes. Do not transform line endings in the twelve managed/canonical bytepins listed by `.gitattributes`, rewrite the whole repository, or use newline-insensitive hashes to conceal packaged-content changes. Corresponding scripts generate Java, descriptors, and proofs. Four UUID call sites restore the shared Ids dependency while preserving the historical UUID format.

## Prepare inputs

The complete Release source ZIP additionally contains original files from `locked-current-build-inputs.zip`, expanded directly into project paths rather than embedding that ZIP. **Historical F11 input:** 738,103,383 bytes, SHA-256 `f482503ac88b71b2f31cee802df557c637589dfeee69aca2ee8900608f8cdba3`, verified by `tools/ci-assets.py --pack` against each SHA. This is not the complete source ZIP's size. Older 738,099,607/738,099,619-byte packages and their 12-byte line-ending difference belong to F10 or earlier and cannot replace a later input identity. After toolchain setup, use the current recipe without a separate bundle. A Git clone supports source-only checks; complete packaging still needs fixed original inputs. APK transformation layers cannot recover the whole original rootfs, and recovery of every build input from an APK is not promised.

The previous F11 Java adoption scope was 1023 members with a complete check record. Final Java leaves received fresh checks at final bytes; other source SHAs matched the full proof. Its source inventory was 2135 members / 135 Markdown files, with 21 actually new documents; the 22-entry document allowlist included the pre-existing F11 release page. Distribution counts, formatter/token proofs, and JUnit execution numbers are separate. The current final frozen receipt defines current inventory; do not copy those historical totals forward.

Historical F8's 999-member Java proof, prior staged reading/style-equivalence proofs, and F7 host-Core rebinding failure/fix positive/negative controls keep their original records. They are not the current adoption scope or test numbers. Old software, APK, and device conclusions belong to their own source/digests; final software and precise APK results are bound by the unified receipt.

The APK's approximately 85 MB offline-rootfs is a transformed packaging layer and cannot reconstruct the approximately 304 MB original rootfs. This work provides no “recover all inputs from APK” helper or guarantee. The final complete source ZIP's SHA-256 belongs in the Release body, not the ZIP's own README.

Complete source retains build-required, already tracked, notice/lock-covered small JNI/vendor/interpreter precompiled dependencies. Disclose unknown-source or proprietary limits; this does not make all vendor native code source-reproducible. Describe provenance, licensing, and build paths from actual material. Do not delete required consumer inputs to advertise “pure source.” The retention inventory also includes 27 necessary historical build sources and one website test fixture, without deploying the website.

Do not upload private keys, user data, device screenshots/raw logs, or historical audit source material. Large generated archives are excluded from Git. Release source ZIPs contain only registered, verified original large inputs. `docs/audits/` allows exactly these nine JSON files at original paths: six consumed by build/CI and three preserving real historical documents/provenance. They are not raw device logs or a general exception for the whole historical audit directory:

- `docs/audits/build154/retired-audit-sources.json`
- `docs/audits/build154/engineering-retired-sources.json`
- `docs/audits/build156/retired-test-control-sources.json`
- `docs/audits/build156/third-party-license-sources.json`
- `docs/audits/build156/retired-asset-sources.json`
- `docs/audits/build156/retired-environment-data-sources.json`
- `docs/audits/build154/history-document-sources.json`
- `docs/audits/build156/shellservice-source-provenance.json`
- `docs/audits/build156/source-docs-retained.json`

Documentation closure follows the exact [documentation-index](docs/en/index.md) inventory: six genuine historical explanations, a recovered Termux historical README checked against its original digest, and five newly created navigation mirrors. Mirrors copy specified source text byte for byte and do not pretend to be recovered historical preservation originals. Do not rewrite old originals or upstream licenses. Other historical JSON ledgers, raw device logs/screenshots, and private forensic material are not included merely because prose refers to them.

Complete generation entry points:

```bash
python3 tools/prepare-dsh-runtime.py
python3 tools/build-standard-runtime.py
python3 tools/prepare-ubuntu-tools.py
python3 tools/prepare-runtime-tools.py
python3 tools/generate-builtin-plugins.py
python3 tools/generate-credential-paths.py
node tools/prepare-web-compat.mjs
python3 tools/prepare-backup-assets.py --write
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/prepare-test-runtime.py
```

The original Ubuntu archive remains a separately fixed input. Installing dependencies or upgrading DSH does not itself upgrade base-environment identity. Preserve historical pinned emergency copies; changed main archives must not automatically relax their digest checks.

## Validation

The fast source stage needs no private key:

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
python3 tools/check-source-hygiene.py
```

After complete inputs are ready, run unit tests and Release Lint for the current production source set:

```bash
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  :app:lintStandardRelease :app:lintLowRelease
python3 tools/verify-stability.py --stage software
python3 tools/run-host-tests.py --stage runtime
```

A host unable to run AGP/aapt2 may use `tools/run-unit-tests.py` as a pure-logic fallback. It compiles test support classes but does not replace an Android build. Test entry points reject `-O`, and release checks do not disappear under Python optimization.

`ci-fast` runs source/protocol checks. Complete `ci-package` needs verified binary inputs and produces unsigned packages only; never install them. Record repository protection, completed online workflows, and actual Linux/Android matrices from real execution evidence, not configuration files.

## Release packaging

Explicitly configure `DSHA_KEYSTORE`, `DSHA_KEYSTORE_PASSWORD`, `DSHA_KEY_PASSWORD`, and optionally the historical `DSHA_KEY_ALIAS`. Missing passwords no longer fall back implicitly. Use only verified historical E7E3, never debug, A3, or temporary CI certificates.

```bash
./gradlew :app:assembleStandardRelease
```

The delivery scope is Standard. Check the actual package for `com.dsh.client`, code190, E7E3, `debuggable=false`, minSdk30, arm64, assets, licensing, ELF, plugins, and independent emergency gates. Record affected tests and Release Lint for both variants separately. A separately requested future Low build still needs explicit v1/v2/v3 and minSdk23 checks. The current work does not establish a new Low release or phone acceptance.

All phone operations are paused: no installation, startup, stop, input, conversation, or ADB reconnection for evidence. Historical same-signature upgrade records retain their original APK attribution, not a new APK's. Do not uninstall, clear data, inject faults, or create extra audit APKs.

The local Standard release target is `F:\DSHA_RESTART\release\0.2.1-rc.2\dsha-0.2.1-rc.2.apk`; retain other historical versions and original Low bytes. Bind final APK, source commit/attachment, and software evidence exactly. Record selectors, cache/incremental reuse, warnings, skips, and unrun scope; do not claim a fully cold-network build. Use the existing private repository, attaching only the release APK and complete source ZIP. Do not claim publication until actual tag/upload results exist. Leave the original repository and dsha.cc unchanged.

## Changes and review

Use the `codex/` branch prefix by default. Preserve old SharedPreferences keys, data-format readers, document IDs, originals, and stop barriers for unknown processes. State the changed responsibilities, persisted fields, protocols, and validation evidence.

Reproduce fixes through the real production entry point or isolated real dependencies. Record source-constraint checks separately from device behavior. Original data is read-only, and historical records state version/time. Without hardware evidence, do not say a device path is verified. Every audit item needs a disposition and remaining limits; an allowlist or uncalibrated score cannot establish that all debt is gone.

Before editing, use the [documentation index](docs/en/index.md) to locate implementation, platform references, and tests. Changes to defaults, permissions, or networking must update the topic page and migration explanation, not just code comments.
