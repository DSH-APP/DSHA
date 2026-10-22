# Taking over DSHA: builds, data boundaries, and validation entry points

Use the [project handbook](project-architecture-handbook.md) and [documentation index](index.md) for the complete feature, permission, and change map, and [runtime engines](runtime-engines.md) for lower-level details.

First read [README](../../README.en.md), [AGENTS](../../AGENTS.en.md), [CONTRIBUTING](../../CONTRIBUTING.en.md), and the [engineering standard](engineering-standard.md). This page describes the maintained source entry points. Complete delivery status comes from the current audit and release records linked by README; source configuration cannot prove phone upgrade installation or publication.

The old handoff guide's exact bytes are preserved in the [historical original](../../tools/history/documents/docs/接手指南.md), with its digest in the [preservation inventory](../audits/build154/history-document-sources.json). Its old task names, test counts, audit APKs, and “push a tag to publish automatically” steps are not the current workflow.

## Runtime structure

The APK extracts a complete Ubuntu base into the application's private directory and runs Node 24, locked pnpm, and `@deepseek-ai/dsh` **0.2.1-rc.2** inside proot/proroot. The native application uses Java 17, Material3, and a single `:app` module, without Kotlin. Both variants are arm64-only and share `dsh.android.client`: Standard requires API 30 and uses system WebView; Low requires API 23 and additionally supplies Gecko 143.

Web uses an automatically allocated port by default (`--port 0`); startup may reuse an actual local port that is still available. Only an explicit manual setting requests a fixed port. Backup port memory does not migrate to another device, and the actual port is confirmed by the current authenticated startup output. The 3090 host bridge and default 3081 LAN entry have different roles; see [ports](ports-and-networking.md) and [DNS](dns-configuration.md). Never infer a PID from a port.

`HarnessController` owns the sole production startup generation and stop intent. Actual collaborators perform startup, output, exit, cold installation, and platform actions. The UI displays state and requests operations; success text does not replace readiness checks. Terminal and Web lifecycles survive page changes, rotation, and language changes independently.

## Find authoritative inputs

| Content | Entry point |
|---|---|
| Toolchain and build parameters | [BUILD](../../BUILD.en.md), `ci/toolchain.lock.json`, `app/build.gradle` |
| DSH version and dependency content | `tools/dsh-runtime/package.json` and `package-lock.json` |
| Built-in plugins and runtime patches | `builtin-plugins.json`, `runtime-patches.json` |
| APK asset members and dynamic-read contract | `tools/asset-deployment.json`, `tools/verify-asset-deployment.py` |
| Managed content and generator inputs | `managed-runtime-inputs.json`, generated runtime descriptor |
| Independent emergency archives | `tools/recovery-runtime/lock.json` |
| Host-test entry points | `tools/host-tests.manifest.json` |
| Historical release certificate | `ci/release-identity.json` |

The Ubuntu base retains an independent identity. Ordinary DSH or UI updates must not increment it. `offline-rootfs.bin` and `dsh-runtime.bin` use `split-runtime-v1`: cold installation reads both, and managed updates read the DSH overlay. Do not package the same large archive twice. Offline archives and large generated output are not committed; use CONTRIBUTING, BUILD, and current CI tools for generation and verification order.

Asset generation uses explicit deployment inventories. Complete licenses, built-in plugins, and extension directories retain dynamic members. Use an explicit mapping when a source archive becomes a packaged `.bin`. Register each new file's purpose and reader before adding it; string regular expressions must not remove licenses, Workers, or dynamic resources. `tools/history` preserves exact bytes, is excluded from APK deployment, and must not be reactivated by automatic test discovery.

## Validation after a change

The fast source stage needs no phone, model credentials, release key, or complete offline environment:

```bash
python3 tools/run-host-tests.py --check-manifest
python3 tools/run-host-tests.py --stage fast
python3 tools/check-source-hygiene.py
```

Pure Java logic uses JUnit. PR source checks may generate current resources and run JVM tests for both variants. This is compilation and JVM verification, not generation or installation of an audit APK. Once complete inputs are prepared, run:

```bash
./gradlew :app:testStandardDebugUnitTest :app:testLowDebugUnitTest \
  :app:lintStandardRelease :app:lintLowRelease
python3 tools/run-host-tests.py --stage runtime
python3 tools/verify-stability.py --stage software
```

A host without AGP/aapt2 may use `tools/run-unit-tests.py` as an explicitly identified fallback. It needs real dependency caches and current R resources and does not replace a full Android build. On Windows, point `DSHA_PYTHON` at a real Python executable rather than launching an unavailable `python3` through a system alias. If a source/resource check skips archive generators, list those skips; do not call it a complete delivery gate.

Runtime fixtures first check version, source inputs, module bytes, and host platform. Behavior regressions use the real locked DSH or an isolated filesystem. New pure logic belongs in Android-free `util` and needs independent assertions. Record source checks, host behavior, Android device results, and external model requests separately. An unrun test, platform skip, or mere field presence is not a pass.

## Data, plugins, and recovery

Host v5 manages sessions, attachments, configuration, user plugins, and personal projects. New user exports are always password-free `DSHA-data-v5-<UUID>.tar.gz`; there is no new encryption toggle. Internal automatic copies remain protected by the local key, with scheduling enabled by default. Historical encrypted originals still require their original password and complete authentication. Local copies and keys are not guaranteed to survive uninstall, so delivery UI should guide users to export.

Preserve user-disabled and safe-mode intent first. Links, local packages, and updates are automatically committed and enabled after real format, path, dependency-lock, directory-digest, and transaction checks. Production plugins permit unlocked re-resolution, lifecycle scripts, pnpmfile, and repair shells. Do not restore the old review gate or turn an author's declaration into complete local dependency verification.

Managed system plugins are separate from user data; current APK proofs reconstruct system bytes. The host continues reading supported old archives, configuration snapshots, and data formats. Historical fixtures for retired Python tools remain in `tools/history`; their removal from the APK does not authorize deletion of old-data readers.

Stop and commit require this invocation's birth identity, session, and exit evidence. Data maintenance first closes PTY and simple terminals, then obtains the existing barrier. Unknown outcomes retain originals, locks, and the scene. Do not force-kill by bare PID, port, or name or clear data to evade a stop barrier. Failed and canceled transactions must not replay writes through another channel automatically.

The independent emergency runtime has its own root, HOME, and workspace and does not depend on the main rootfs. Five tools are controlled; repair writes still need native confirmation. Without model credentials, diagnose first and enter a temporary key for conversation. An open production terminal does not relax emergency writes or unknown-process constraints.

## Release artifacts and evidence

Release APKs use the original package name, historical E7E3 certificate, an increasing versionCode, `debuggable=false`, and v1/v2/v3 signing. Passwords must be supplied explicitly. Do not substitute a temporary, A3, or debug certificate.

```bash
./gradlew :app:assembleStandardRelease :app:assembleLowRelease
```

After the relevant software, asset, plugin, emergency, ELF, and signature gates, use the version directory `release/0.2.1-rc.2/dsha-0.2.1-rc.2.apk` and `.apk.sha256`. The present delivery scope is Standard only; preserve original Low172 bytes. Replace the same version and flavor while retaining other historical versions. Bind source snapshots and verification records to the final bytes.

Phone operations are paused. Do not generate extra debug/audit APKs or inject failures into production data. If future device acceptance is authorized, use only the same-signature release APK for an in-place upgrade and retain user data. Android 6/7, real 16 KiB devices, FUSE, vendor `/proc` restrictions, and external model services require their own real evidence.

Current CI separates source checks, unsigned complete packages, and protected signed release entry points. A workflow file does not prove that it ran online or that publication succeeded. Unsigned packages are for software checks; do not install them on phones or put them in release. Unless explicitly requested, do not upload to GitHub or deploy the website.
