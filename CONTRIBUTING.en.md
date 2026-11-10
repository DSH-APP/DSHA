# Build, validate, and submit

> Current publication identity: `DSH-A-Su` / `io.github.sunsetrne.dsha` / `0.2.1-rc.5-20261110.1700-dsh0.2.2-rc.1` / versionCode 356 / single Standard; the Mod-owned release certificate. Full identity and signing entry points are in [README](README.en.md), [AGENTS](AGENTS.en.md) and [ci/release-identity.json](ci/release-identity.json).

Read [README](README.en.md), [AGENTS](AGENTS.en.md), [BUILD](BUILD.en.md) and the [documentation index](docs/en/index.md) first. F10–F13 and build190 records describe only their own source, APK, tests and device state at that time; they neither override current requirements nor prove this build.

Current fixed inputs are pinned by [ci/sunset-inputs.lock.json](ci/sunset-inputs.lock.json): public pre-release asset `fixed-inputs-20261105e`, 716,787,167 B, SHA-256 `be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5`, restored by `tools/ci-assets.py`. Large archives and the offline rootfs are not committed; a source-only checkout cannot build a complete APK.

Use the [handoff guide](docs/en/handoff.md) for project-specific operations, the [module map](docs/en/module-map.md) for ownership, and the [engineering standard](docs/en/engineering-standard.md) / [ADR 0001](docs/en/adr/0001-build154-boundaries.md) for constraints. `tools/history` retains retired tools and exact originals only; it does not enter APKs or automatic test execution.

## Source and tool identity

Use Java 17 and a single `:app` module. There is one Standard variant: system WebView, API 30 minimum, arm64-v8a; package `io.github.sunsetrne.dsha`, test variant `io.github.sunsetrne.dsha.test`. The Low/GeckoView line has been removed, and Android 10 or below is no longer supported.

- Application version and fixed filename rules: `app/build.gradle`.
- DSH version and npm content: `tools/dsh-runtime/package.json`, `package-lock.json`.
- Bundled tools: `tools/runtime-tools.lock.json`.
- Built-in plugins: `app/src/main/assets/builtin-plugins.json`.
- Active/specialized/retired runtime patches: `app/src/main/assets/runtime-patches.json`.
- Independent emergency archives: `tools/recovery-runtime/lock.json`. Updating the main environment must not silently replace this lock.
- CI tools and Mod release identity: `ci/toolchain.lock.json`, `ci/release-identity.json`.

Normalizable source uses actual LF. Nineteen necessary small binaries and historical originals retain exact bytes. Do not transform line endings in the twelve managed/canonical bytepins listed by `.gitattributes`, rewrite the whole repository, or use newline-insensitive hashes to conceal packaged-content changes. Corresponding scripts generate Java, descriptors, and proofs. Four UUID call sites restore the shared Ids dependency while preserving the historical UUID format.

## Prepare inputs

The complete Release source ZIP additionally contains original files from `locked-current-build-inputs.zip`, expanded directly into project paths rather than embedding that ZIP. Current fixed inputs are `fixed-inputs-20261105e`, 716,787,167 B, SHA-256 `be97d9f0a45f408d3a7de83ae55a00a85a5154343589571c6d159a0fe58f08f5`, verified by `tools/ci-assets.py --pack` against each SHA. This is not the complete source ZIP's size. After toolchain setup, use the current recipe without a separate bundle. A Git clone supports source-only checks; complete packaging still needs fixed original inputs. APK transformation layers cannot recover the whole original rootfs.

The build190 F13 inventory, the older 738,101,893 B input package, and F10/F11 records are historical. They cannot replace the current input identity or transfer old software/device conclusions to this build. Current software, APK and publication results are bound by this CI run and the Release receipt.

Historical F8's 999-member Java proof, prior staged reading/style-equivalence proofs, and F7 host-Core rebinding failure/fix positive/negative controls keep their original records. They are not the current adoption scope or test numbers.

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
./gradlew :app:testStandardDebugUnitTest :app:lintStandardRelease
python3 tools/verify-stability.py --stage software
python3 tools/run-host-tests.py --stage runtime
```

A host unable to run AGP/aapt2 may use `tools/run-unit-tests.py` as a pure-logic fallback. It compiles test support classes but does not replace an Android build. Test entry points reject `-O`, and release checks do not disappear under Python optimization.

`ci-fast` runs source/protocol checks. Complete `ci-package` needs verified binary inputs and produces unsigned packages only; never install them. Record repository protection, completed online workflows, and actual Linux/Android matrices from real execution evidence, not configuration files.

## Release packaging

Explicitly configure `DSHA_KEYSTORE`, `DSHA_KEYSTORE_PASSWORD`, `DSHA_KEY_PASSWORD`, and optionally `DSHA_KEY_ALIAS` (default `dsha-a-su`). Missing passwords no longer fall back to defaults. A release package may only use the Mod certificate recorded in `ci/release-identity.json`; never substitute debug, E7E3 or a temporary certificate.

```bash
./gradlew :app:assembleStandardRelease
```

Only Standard is published now; the actual package must verify `io.github.sunsetrne.dsha`, versionCode 219, `debuggable=false`, minSdk 30, arm64, assets, licenses, ELF, plugins and the independent emergency gates. Low has been removed; do not build or publish it, and do not claim Android 10 or below support.

Formal CI publication uses [`.github/workflows/release-production.yml`](.github/workflows/release-production.yml): `workflow_dispatch` binds an exact commit, restores fixed inputs from the public pre-release asset, runs complete unit tests / Java formatting / Lint / documentation gates, signs with the repository Secrets certificate, then creates a tag + prerelease and uploads the APK, evidence ZIP, `build-info.json` and `SHA256SUMS`; by default the same run also attaches the co-installable `.debug` / `.dev` test packages and their public test certificate (`test_packages=none` omits them), and `dry_run=true` builds and verifies without creating the tag or Release. See [CI build and release](docs/en/ci-release.md) for parameters and verification.

Do not upload private keys, user data, device screenshots or raw logs; do not install, inject faults or generate extra audit APKs unless that task explicitly authorizes it. Bind the final APK, source commit, attachments and evidence precisely; record selectors, cache/incremental reuse, warnings, skips and unrun scope truthfully. Do not claim publication complete before an actual run, tag and upload result.

## Changes and review

Use the `codex/` branch prefix by default. Preserve old SharedPreferences keys, data-format readers, document IDs, originals, and stop barriers for unknown processes. State the changed responsibilities, persisted fields, protocols, and validation evidence.

Reproduce fixes through the real production entry point or isolated real dependencies. Record source-constraint checks separately from device behavior. Original data is read-only, and historical records state version/time. Without hardware evidence, do not say a device path is verified. Every audit item needs a disposition and remaining limits; an allowlist or uncalibrated score cannot establish that all debt is gone.

Before editing, use the [documentation index](docs/en/index.md) to locate implementation, platform references, and tests. Changes to defaults, permissions, or networking must update the topic page and migration explanation, not just code comments.
