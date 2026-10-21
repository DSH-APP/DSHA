# References, upstream provenance and verifiable scope

This page is a build190/F10 fact index. The source-reference review date is 2026-10-21. Its purpose is to help maintainers find primary references, version locks and verification entry points, not to imply official endorsement or security certification. Writing the source page involved reading source, lockfiles and upstream references; it did not rerun builds, tests, device operations or publishing.

## 1. Project and upstream relationships

| Project | Role and evidence in this branch | What this does not establish |
|---|---|---|
| [ym2025szz/DSHA-prerelase](https://github.com/ym2025szz/DSHA-prerelase) | Private maintenance and prerelease distribution repository linked by the current README; the repository's actual spelling is retained | An official DeepSeek Android client or officially certified branch |
| [DSH-APP/DSHA](https://github.com/DSH-APP/DSHA) | Origin of the DSHA project; local AGENTS records migration of later changes onto the build170 base | Identity between this maintenance branch and every branch, Release or website of that repository |
| [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) | Official DeepSeek Harness upstream; this project installs its npm runtime into a Linux environment inside Android | Upstream maintenance or approval of DSHA's native shell, authorization policy, container patches or local APK |
| [mexiaosqwq/dsh-web-mobile](https://github.com/mexiaosqwq/dsh-web-mobile) | Third-party mobile Web-plugin upstream; imported at an exact commit, then locally adapted | An official DeepSeek mobile UI or an entirely unmodified upstream package |

The relationships are described jointly by [README](../../README.en.md), [AGENTS](../../AGENTS.en.md), locks and actual differences. Public project descriptions establish public statements, not the source commit of a particular local binary. Anonymous access to a private repository may return 404; that neither proves its absence nor replaces authenticated permission/attachment checks. This page did not inspect the private repository's remote commit or attachment state. A concrete delivery needs separate verification of the exact Release and digests. Maintainer attribution, upstream organization names, use of their APIs and retained licenses do not amount to organizational authorization or audit endorsement.

## 2. What the three evidence categories answer

| Evidence | Can answer | Cannot replace |
|---|---|---|
| Official APIs/upstream project documentation | Platform interfaces, constraints, upstream statements and license entry points | Proof that this project implemented them correctly or that a ROM is compatible |
| Current source, locks and actual assets | Selected versions, paths, digests and local implementation contracts | Real-device behavior or complete provenance for every dependency |
| Tests/acceptance records bound to an APK | Results actually observed in the recorded environment and steps | Device, business, long-duration or compatibility matrices that were not run |

First identify the category of a claim, then choose evidence accordingly. Official VirtualDisplay support does not mean an ordinary application may inject arbitrary input. A JUnit rejection-branch check does not establish successful native touch on a phone. F11 continues to pause device acceptance. Historical packages or software results must not be reassigned as F11 phone acceptance. Consult the [F11 record and its receipt (original-language evidence)](../releases/v0.2.1-rc.2-build190-fix11-20261021.md) for the recorded software and artifact identity. This page has no evidence of an independent third-party security audit, Google Play review or institutional certification.

## 3. Current versions and fixed inputs

The following values come from current files. Update the lock before this page when upgrading dependencies. Versions identify components; corresponding SHA/integrity/descriptor values identify final bytes. This documentation work did not download npm packages, source archives or original toolchains again to recompute digests. Where a deep commit/tag page could not be read, exact values are stated from local locks without claiming remote-byte verification.

| Component | Current local selection | Original lock/configuration |
|---|---|---|
| Main DSH package | `@deepseek-ai/dsh` `0.2.1-rc.2` | [package.json](../../tools/dsh-runtime/package.json), [package-lock.json](../../tools/dsh-runtime/package-lock.json) |
| Mobile plugin | `3.2.1`; actual main input commit below | [mobile-plugin-upstream.lock.json](../../tools/mobile-plugin-upstream.lock.json) |
| Managed environment | Ubuntu 24.04 arm64; DSHA `baseVersion=10` | [runtime-descriptor.json](../../app/src/main/assets/runtime-descriptor.json), [Ubuntu package lock](../../tools/ubuntu-tools/packages.lock.json) |
| Runtime tools | pnpm `10.34.5`, certifi `2026.7.22` | [runtime-tools.lock.json](../../tools/runtime-tools.lock.json) |
| Default/optional engine | proot/proroot; the latter locally declared v1.2.8 | [AGENTS](../../AGENTS.en.md), [THIRD_PARTY_NOTICES](../../THIRD_PARTY_NOTICES.md) |
| Shizuku API/provider | Standard `13.1.5`; Low `12.2.0` | [app/build.gradle](../../app/build.gradle) |
| Low GeckoView | `143.0.20251003115653`, arm64-v8a | [app/build.gradle](../../app/build.gradle) |
| Android identity | `dsh.android.client`, versionCode `190`; see build configuration for the dated versionName | [app/build.gradle](../../app/build.gradle) |
| Independent recovery environment | DSH `0.2.1-rc.2` / base `10`, separate archive digests | [recovery-runtime/lock.json](../../tools/recovery-runtime/lock.json) |

### DSH package provenance

The main package's resolved URL in the current npm lock is the [exact 0.2.1-rc.2 tarball at registry.npmjs.org](https://registry.npmjs.org/@deepseek-ai/dsh/-/dsh-0.2.1-rc.2.tgz). Its locked integrity value is:

```text
sha512-tvY+yhfKRifthbqPe/OK0AYxM5ZkvqfYq/jMYgJcEUxiRRL1Vwra3Ti5TB6AXgSWQdiPHYh/Vp6SJa9yHwMNuw==
```

The lock also retains resolved URLs, integrity and declared licenses throughout the actual dependency graph. Checking only the main package name is insufficient. DSHA manages adaptations to installation, bridging, Web behavior and storage; its runtime archive is not an untouched official installation. Modification entry points are [prepare-dsh-runtime.py](../../tools/prepare-dsh-runtime.py) and [runtime-patches.json](../../app/src/main/assets/runtime-patches.json).

### Mobile-plugin provenance

The current lock distinguishes the release tag from later main inputs:

- The recorded release tag is `v3.2.1`, with releaseCommit `1844bf21db47a9b942275b8118988e3f6718cf4b`.
- The imported commit is [`10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4`](https://github.com/mexiaosqwq/dsh-web-mobile/tree/10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4).
- The upstream source-archive SHA-256 in the lock is `9629e585127dd8dd2149bb9f188cef957e5d0f5d4ae57d07f6f881748940e1e5`.
- Upstream client and final local client are recorded separately; final member digests are in the current THIRD_PARTY_NOTICES and descriptor.

An internal package version of 3.2.1 does not mean it contains only that tag's original bytes. [apply-mobile-client-patches.mjs](../../tools/apply-mobile-client-patches.mjs) and [mobile-server](../../tools/mobile-server) manage local differences. Check individually whether an upstream upgrade absorbed local patches; do not apply the entire old patch set again.

## 4. Licenses and unresolved provenance boundaries

[LICENSE](../../LICENSE) provides MIT licensing for DSHA-owned code and retains original copyright attribution. [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) is generated from current locks and actual assets, documenting sources/licenses per component. Bundled license copies are under [assets/licenses](../../app/src/main/assets/licenses); the mobile plugin also retains its LICENSE. A top-level MIT license does not make every third-party component MIT. Full license texts, package locks and binary provenance are separate materials; completing one does not replace the other two.

| Component/source | Direct primary reference and local evidence | Known boundary |
|---|---|---|
| proot | [Termux proot](https://github.com/termux/proot); [Low build script](../../tools/build-low-proot.py) | Low pins v5.1.107.92; the original Standard binary's exact revision remains unverified |
| proroot | [coderredlab/proroot](https://github.com/coderredlab/proroot); local THIRD_PARTY_NOTICES | Local v1.2.8 is declared an upstream proprietary binary; do not claim a complete public-source rebuild or unrestricted redistribution |
| DSHA proroot compatibility layer | [Independent compatibility-layer README](../../tools/proroot-compat/README.md), [ABI lock](../../tools/proroot-compat/abi.lock.json) | Owned shim/entry source and locks do not provide vendor source or provenance for its five libraries |
| Termux terminal JNI | [Termux app](https://github.com/termux/termux-app); [local upstream license exception](../../tools/termux-jni/LICENSE.upstream.md) | A terminal-specific exception cannot be extended to the whole Termux project |
| Ubuntu packages | [Ubuntu Security Notices](https://ubuntu.com/security/notices); local package locks and rootfs copyright files | USN is a verification entry point, not proof that the current archive has no vulnerabilities |
| Other original JNI | THIRD_PARTY_NOTICES section on actual checked-in JNI members and provenance boundaries | Original provenance/license of libandroidshmem and exact versions of some Standard proot/talloc inputs remain unverified |

The local fixed SHA-256 for the Low proot source ZIP is `29385d1ddb619a9c4449ab512bfd55032034b22f724ddf98fc95ff300ea32135`. It identifies a particular input; it cannot establish that the original Standard proot library came from the same build. DSHA `v1.1.6` in CHANGELOG is a historical shell-project version, not an upstream proroot version. Keep unknown provenance explicit with the materials still needed; do not invent a license or commit. Use/distribution of closed-source components depends on their actual license terms. This page does not provide a legal authorization conclusion.

## 5. Official Android and Shizuku reference map

Each direct link is maintained by the relevant platform/project and supports only the claim in its row. Android documentation changes over time. Actual behavior also depends on device API, targetSdk, permissions and system implementation. Citing a public SDK does not turn internal members accessed through reflection into stable public APIs.

| Topic and primary reference | Supported claim | DSHA limitation/check |
|---|---|---|
| [VirtualDisplay](https://developer.android.com/reference/android/hardware/display/VirtualDisplay) | A virtual display has display/Surface lifecycle and requires release | Does not guarantee arbitrary application launch or input; inspect Core/DisplaySession |
| [DisplayManager](https://developer.android.com/reference/android/hardware/display/DisplayManager) | Platform contract for display creation and flags | A flag's existence does not grant the caller's UID permission; inspect VirtualScreenPolicy |
| [InputManager](https://developer.android.com/reference/android/hardware/input/InputManager) | Public input-device-management API boundaries | Does not endorse privileged event injection; reflection and input commands require separate checks |
| [Non-SDK interface restrictions](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces) | Nonpublic interfaces can be restricted and change | Successful reflection lookup is not successful invocation or general OEM compatibility |
| [ADB](https://developer.android.com/tools/adb) | client/server/adbd, shell, device connections and wireless pairing | Shell does not imply Root; pairing/connection ports cannot be derived from the Web port |
| [Shizuku setup guide](https://shizuku.rikka.app/guide/setup/) | Conditions for Root, wireless-debugging and computer-assisted startup | Restart/system restrictions affect service availability; no permanent-survival or all-permission guarantee |
| [Shizuku API/developer guide](https://github.com/RikkaApps/Shizuku-API) | Binder arrival/death, permission requests and UserService integration | A loaded SDK does not mean live Binder or user authorization; inspect ShizukuShell |
| [AccessibilityService](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService) | User-enabled accessibility services, window retrieval and interaction | Returned trees may be incomplete; Canvas, passwords and secure windows have additional limits |
| [SAF / DocumentsProvider](https://developer.android.com/guide/topics/providers/document-provider) | Document access through user selection and URI grants | A URI is not an ordinary absolute path; a Provider can be remote, cancelled or reject access; inspect DocumentStreams |
| [MediaStore](https://developer.android.com/training/data-storage/shared/media) | Shared-storage collections, ContentResolver and version-specific access rules | Not all-files permission; export must verify actual URI, write and publication result |
| [MediaRecorder / microphone permission](https://developer.android.com/media/platform/mediarecorder) | Audio capture needs declarations, runtime permission and system conditions | Web requests also check same origin/current page; no permission for camera or screen audio |
| [Foreground services](https://developer.android.com/develop/background-work/services/fgs) | User-visible tasks, notifications and background-start restrictions | FGS does not guarantee survival; Stop still requires actual process-exit proof |
| [PiP](https://developer.android.com/develop/ui/views/picture-in-picture) | Activity PiP entry/exit and lifecycle adaptation | Does not prove cached page scaling was reset; check current PiP state and Insets |
| [Software keyboard control and animation](https://developer.android.com/develop/ui/views/layout/sw-keyboard) | IME visibility and Insets-animation coordination | Does not establish phone keyboard frame rate; cover cancellation, focus loss and navigation |
| [WindowInsetsAnimation.Callback](https://developer.android.com/reference/android/view/WindowInsetsAnimation.Callback) | Animation callback/progress contracts | A callback does not remove final layout-commit or page-identity requirements |
| [WebView](https://developer.android.com/develop/ui/views/layout/webapps/webview) | Embedded pages in an Activity and basic WebView capability | System-provider versions vary by device; JavaScript bridges need their own origin checks |
| [Mozilla GeckoView](https://mozilla.github.io/geckoview/) | An embeddable Gecko engine distributable with the application | Its version, permission callbacks and behavior cannot be accepted by testing system WebView, or vice versa |

Current code defines DSHA's combined contracts. Entry points are [virtual-screen architecture](virtual-screen-architecture.md), [device-permissions architecture](device-permissions-architecture.md), the [project handbook](project-architecture-handbook.md) and [runtime/data/plugin architecture](runtime-data-plugin-architecture.md). Latest-frame requirements, Core UUID, one-time nodes and no UNKNOWN replay are additional DSHA constraints, not official Android protocol claims. System acceptance of an event or node action does not verify an application's business result.

## 6. Runtime and toolchain primary references

| Reference | Supported claim | Local anchor and limitation |
|---|---|---|
| [DeepSeek Harness upstream](https://github.com/deepseek-ai/deepseek-harness) | Official project and usage entry | Exact installed content follows npm locks/local patches, not floating main |
| [Node.js Releases](https://nodejs.org/en/about/previous-releases) | Major-version lifecycles and support policy | CI's choice of 24 pins only the major version; original archives still need actual input digests |
| [pnpm 10.x settings](https://pnpm.io/10.x/settings) | CLI/environment/configuration sources and package-import options | Read the relevant version; the default site may show another major release |
| [Gradle Wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html) | Wrapper distribution version and digest verification | The current Web-page version is not this project's version; properties pins 9.3.1 |
| [Android build-tool releases](https://developer.android.com/build/releases) | Official AGP/build-tool version and compatibility reference entry | Compatibility still depends on current configuration; a link alone does not establish it |

Current [ci/toolchain.lock.json](../../ci/toolchain.lock.json) declares:

```text
Java 17        Node 24        Python 3.12
Gradle 9.3.1   AGP 9.1.1
android-37.0   build-tools 36.0.0   NDK 26.3.11579264
```

[ci-toolchain.py](../../tools/ci-toolchain.py) cross-checks the lock against Gradle, compile/targetSdk, NDK and Wrapper configuration. The distributionSha256Sum in [gradle-wrapper.properties](../../gradle/wrapper/gradle-wrapper.properties) is `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`. It pins the Gradle distribution ZIP, not every byte of the SDK, Maven cache or local JDK. [ci-fast](../../.github/workflows/ci-fast.yml) and [ci-package](../../.github/workflows/ci-package.yml) use fixed action commits. Workflow configuration can be reviewed, but its presence does not prove a particular remote commit ran and passed. The complete package workflow produces unsigned verification artifacts. The final APK's formal E7E3 signing identity needs independent verification. Signing keys, keystores, tokens and developer local.properties must not enter published source materials.

## 7. Git source, complete source ZIP and APK

Git retains application source, resources, tests, locks, necessary scripts and explicitly documented small precompiled dependencies. Large generated rootfs, DSH and recovery archives stay outside Git; APKs are distributed as Release attachments. A complete source ZIP, as described in [BUILD](../../BUILD.en.md), includes fixed original inputs required by the current recipe and expands directly to project paths. “Complete source ZIP” means the buildable project and fixed inputs. It does not claim full source for every closed-source/vendor binary. Unpacking a generated APK alone cannot recover every original rootfs/build input: a compressed/split packaged offline-rootfs and its original rootfs input are different files.

| Local material | What it constrains | What it cannot replace |
|---|---|---|
| [ci-assets.py](../../tools/ci-assets.py) | Input inventory and per-item digests during packaging/acquisition | Guessing missing archives or mixing versions |
| [ci-build-inputs.lock.json](../../tools/ci-build-inputs.lock.json) | Fixed original build inputs outside descriptor coverage | Vendor-source provenance |
| [managed-runtime-inputs.json](../../app/src/main/assets/managed-runtime-inputs.json) | Actual managed installation inputs and archive recipe | User data or authorization snapshots |
| [runtime-descriptor.json](../../app/src/main/assets/runtime-descriptor.json) | Current managed inputs, launcherInputs and runtimeId | Inferring data-format compatibility from APK versionName alone |
| [Recovery lock](../../tools/recovery-runtime/lock.json) | Independent recovery-environment input digests | Using the current production rootfs as independent rescue dependencies |
| [backup-dependencies.lock.json](../../tools/backup-dependencies.lock.json) | Backup dependency versions and JAR digests | Backup integrity, successful restoration or validation of every data format |

Keep digests/provenance separately for source content, input packages and final APKs. A version string does not connect all those proofs. Unchanged input bytes may inherit existing content evidence after item-by-item comparison, but that is not rerun testing or device acceptance. Public source materials must exclude device logs, screenshots, sessions, credentials and historical private audit bundles. Unknown provenance still needs honest disclosure.

## 8. Reviewing a specific delivery

1. Obtain source ZIP, APK and digests from the actual authorized Release, then verify the complete files first.
2. Check repository/Release identity and the selected version, distinguishing this maintenance branch from the original DSHA repository.
3. Read current BUILD and check the toolchain lock, Wrapper distribution digest and actual tool versions.
4. Check that complete inputs expand to the correct project paths. Report missing archives instead of bypassing complete-asset checks.
5. Check npm resolved/integrity, the exact mobile commit, local patches and original license copies.
6. Recompute descriptor/notices against current source, check real inputs/generated results and retain independent output.
7. Inspect the final APK's package name, version, non-debuggable flag, signature, actual archives and DEX/manifest, not only source files.
8. Record software, actual-APK and device acceptance separately, retaining failures, skips, unexecuted work and inherited scope.

This is a maintainer review procedure. Writing this page did not execute those build, installation or device steps. Machine checks improve inspectability but do not guarantee vulnerability-free upstream code or stable hidden APIs, and cannot supply missing provenance materials. A public “passed” claim should identify its object, environment and scope rather than broadly claiming safety, certification or complete compatibility.

## 9. Updating references and versions

Recheck this page when upgrading upstream, changing system target/API, engines, licenses or distribution. Prefer locks, immutable commits and exact original packages for version claims. Date mutable documentation and identify applicable versions. For redirects/404s, check official navigation; do not replace primary evidence with search snippets, reposts or unofficial tutorials. If official documentation moves to Compose, verify the interfaces used by the current Java/View implementation instead of copying new examples. When a citation's support changes, update the claim too, not just the URL.

Record these separately in change notes:

- Upstream source URL, version/commit, original download digest and license changes.
- Retained/retired local patches and changes to final assets/input recipes.
- Affected code/regression entry points, actual results, skip reasons and device scope awaiting validation.
- Whether known unknowns were resolved. Without new evidence, preserve existing provenance and compatibility limitations.

Architecture changes should also update relevant decision records such as [ADR 0003](adr/0003-virtual-screen-lifecycle-and-input.md). Documentation additions do not change APK bytes or retrospectively establish unperformed tests or third-party audits.
