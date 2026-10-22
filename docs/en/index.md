# Documentation index and maintenance rules

> **Current publication (2026-10-23):** [v0.2.1-rc.2 official release](releases/v0.2.1-rc.2-notes.md), exact2100 Standard APK / `dsh.android.client`. The user authorized direct contributor publication to public DSH-APP/DSHA, without a PR. This supersedes earlier public-pause instructions; older private-only entries below describe their original dates, not the current publication policy. No application implementation or APK bytes change in this publication.

Previous startup-maintenance record, verified2026-10-22. Its identity: **`0.2.1-rc.2-20261022.0243-dsh0.2.1-rc.2` / code190**. [Scope and artifacts](releases/v0.2.1-rc.2-build190-performance-20261022.md), [startup/initialization investigation](performance-startup-20261022.md), [sanitized evidence](../evidence/performance-20261022.json). F13 inventory and fixed-packet counts below are historical baseline facts. Public publication remains paused; private maintenance delivery only.

Current [UI-maintenance evidence](ui-performance-20261022.md): 2100 Standard passed selected software, overwrite/recovery and both-engine screen-off startup/retained-conversation checks. Valid toggles remain around three seconds and page timing varies, without a universal speed claim. Rejected 2020 and 1817/1325 history remain separate; PiP stays default OFF and its local anomaly, Doze and endurance limits remain open.

Verified: 2026-10-21. Current build190/F13 identity is `0.2.1-rc.2-20261021.2107-dsh0.2.1-rc.2` /190. Current F13 frozen inventory: 2215 source members / 200 Markdown documents; 1029 adopted Java files. Distribution/format proofs and JUnit execution counts are separate. The fixed packet is 738,101,893 bytes, SHA-256 `da3a87e9782469136f08d37143582fec845af1a69406b9a534f8cb7f68249ed8`. Originals expand directly at project paths, not as a nested packet. These are not the complete source ZIP's size or digest. See [F13 scope and evidence](releases/v0.2.1-rc.2-build190-fix13-20261021.md) and [release introduction](release-introduction.md). Published F12 keeps its historical identity; F11 remains unpublished. Platform sources, current locks/code and executed evidence are separate.

This index leads to English operational and architectural pages. Immutable historical acceptance/release reports remain in their original language and retain their date, source, and APK identity. Evidence has three layers: official platform/upstream documentation, current code/version locks, and actual execution records. None substitutes for another. The [gap matrix](documentation-gap-matrix.md) maps the old report's 31 topics and its 24/21 discrepancy lists without adopting the old counts as present conclusions.

## Current features and architecture

| Document | Purpose |
|---|---|
| [Project architecture handbook](project-architecture-handbook.md) | Main features, framework layers, entry classes, and change/test map |
| [ADB / Root / Shizuku permission architecture](device-permissions-architecture.md) | OS permissions, privileged channels, accessibility, application approval, services, revocation, and diagnosis |
| [Runtime, data, and plugin architecture](runtime-data-plugin-architecture.md) | Extraction/startup, normal/safe/recovery modes, managed updates, pnpm/plugin transactions, credentials, backup/restore |
| [Runtime engines](runtime-engines.md) | Current proot/proroot, historical DSHA v1.1.6, vendor versions, loaders/shims, exit supervision, and compatibility |
| [Ports and networking](ports-and-networking.md) | Automatic/manual ports, local fallback memory, separate bridge/ADB/LAN endpoints, and retry integration boundaries |
| [DNS configuration](dns-configuration.md) | Actual auto/ipv4/native differences, narrow Node lookup retry, glibc no-aaaa, and user choices |
| [Virtual-screen architecture](virtual-screen-architecture.md) | Core/Manager/tool routes, frame/node/anchor evidence, capture/input, closure/recovery, and regression map |
| [ADR 0001](adr/0001-build154-boundaries.md), [ADR 0002](adr/0002-current-threat-model.md), [ADR 0003](adr/0003-virtual-screen-lifecycle-and-input.md) | Architectural choices, permission/data boundaries, virtual-screen lifecycle, and consequences |
| [References and provenance](references-and-provenance.md) | Primary platform sources, upstream relationships, pinned versions/digests, licensing, and source limits |

## User and developer entry points

- [README](../../README.en.md), [development constraints](../../AGENTS.en.md), [build instructions](../../BUILD.en.md), [contribution workflow](../../CONTRIBUTING.en.md), and [handoff](handoff.md).
- [Browser/flavor behavior](browser-engines.md), [plugins](plugins.md), [virtual-screen setup](virtual-screen-guide.md), [Web files](web-file-sharing.md), [v5 backup format](backup-format-v5.md), and [configuration recovery](configuration-recovery-host.md).
- [Engineering standard](engineering-standard.md), [module map](module-map.md), [security model](security-model.md), [source provenance](source-provenance.md), [third-party notices](third-party-notices.md), and [download verification](download-verification.md).
- [Historical Standard notes](../android-standard.md) and [historical Low notes](../android-low.md) remain original records; use the current English browser/engine pages for present behavior.

## Operations and focused guides

| Scenario | English guides |
|---|---|
| First installation, component probing, targeted repair, maintenance, interrupted operations | [Installation and maintenance](install-and-maintenance.md), [startup and diagnostics](startup-and-diagnostics.md), [application/managed updates](update.md) |
| Engine/static-loader/seccomp, DNS, and port diagnosis | [Runtime/network choices](runtime-and-network.md), [engines](runtime-engines.md), [DNS](dns-configuration.md), [ports](ports-and-networking.md) |
| Device channels, 3090 protocol, LAN, files, system documents | [Device channels](device-channels.md), [bridge and files](bridge-and-files.md), [LAN](lan.md), [Web files](web-file-sharing.md) |
| One-click/manual/automatic backup, restore, snapshots, independent recovery | [Backup and restore](backup-and-restore.md), [configuration snapshots](config-snapshots.md), [independent recovery](emergency-recovery.md), [v5 format](backup-format-v5.md) |
| Plugin installation, system/user separation, protocol, permissions, restart | [Plugin operations](plugins.md), [plugin runtime and permissions](plugin-runtime-and-permissions.md) |
| PTY/simple terminals, WebView/Gecko, desktop UA, PiP | [Terminals](terminal.md), [browser engines](browser-engines.md), [picture-in-picture](picture-in-picture.md) |
| Language latching, overlays, approval, task notifications | [Language](i18n.md), [overlays and notifications](overlay-and-notifications.md) |
| Source, pinned inputs, signing, release, and test evidence | [Build and release](build-and-release.md), [domain boundaries](agents/domain.md), [issue workflow](agents/issue-tracker.md), [triage vocabulary](agents/triage-labels.md) |

New focused guides record verification date, baseline, actual activation timing, and failure/verification limits. The three agent-workflow pages were newly written during F11 maintenance; they do not claim recovered historical originals or prove remote labels, permissions, or workflow configuration. A glossary is created when terminology actually requires it.

Current terminology: proot is default; proroot is explicitly selectable. Web ports are automatic by default; fallback memory is local. DNS auto is not global IPv4-first behavior. Production native model editors have been removed; model settings are in Web. PiP defaults off. One-click backup safely stops DSH/tasks before writing a password-free archive; external workspace contents need separate backup. Refer to the focused page and code for precise definitions.

## Reading historical material

The [changelog](../../CHANGELOG.md) preserves former defaults/performance observations and adds build190 migration information. DSHA v1.1.6 is not the upstream proroot version; the current vendor lock is v1.2.8. An old build's fallback, installation, certificate, package, or test count does not establish a later delivery's result.

Source distribution includes ordinary maintenance Markdown and necessary historical release explanations, including real fix1, build156, and old handoff originals. Their temporal attribution is unchanged. Referenced historical `app/build` receipts, local APKs, raw device logs/screenshots, and private audit attachments are not uploaded merely to repair navigation. They were local material at that time, not present repository downloads or evidence of rerunning the current build. Start maintenance here; old scripts do not authorize another audit APK or deletion of user data.

Publication checks distinguish current-guide links, available historical explanations, and undistributed local evidence. A recovered historical original is digest-checked before inclusion; missing receipts are not reconstructed. Lists below locate reports, without promising their entire original attachment sets.

## Documentation updates and reproducibility

The F11 closure work checked the actual distribution stage, rather than treating the developer machine's complete tree as a closed delivery. The following exact historical files are explicitly allowed. They prove their own time only and do not revive retired formatting, review, version, or acceptance rules.

| Genuine historical file | Distribution purpose |
|---|---|
| [build154 POLICY](../audits/build154/POLICY.md) | Constraints of the old audit; current rules remain in AGENTS and maintained topics |
| [rc2 architecture assessment](../audits/2026-09-27-rc2-full-architecture/final-assessment.md) | Build147 architecture/delivery scope, not this round's device receipt |
| [build155 architecture](../audits/build155/architecture-assessment.md), [remaining work](../releases/build155-remaining-work-20261003.md) | Recursive document dependencies of corresponding release/build156 assessments |
| [build156 architecture](../audits/build156/architecture-assessment.md), [remaining work](../releases/build156-remaining-work-20261003.md) | Original historical scope and unverified items |
| [Historical retention list](../audits/build154/history-document-sources.json), [ShellService provenance](../audits/build156/shellservice-source-provenance.json), [retained BUILD record](../audits/build156/source-docs-retained.json) | Three added explicit JSON metadata files, taking the allowed metadata set from six to nine, without opening all audit JSON |
| [Historical Termux README](../../tools/history/documents/tools/termux-jni/README.md) | Genuine 3547-byte original found on F:, matching retention-list SHA `c29a880a9d7014d58b125bd57e6f7a52a4bae4bda64e76dd22a2819382f521ca` |

A retention list does not make every listed historical object part of this source package. Execution ledgers, raw device images/logs, and similar material retain their own privacy/distribution boundaries. Unknown rights in provenance JSON do not become established rights because the JSON can be downloaded.

Five **navigation copies** were newly created during F11 so original-byte release pages' relative links work in GitHub and the source ZIP. Each is byte-identical to the stated canonical source. They are new navigation copies, not five rediscovered historical saved originals. Existing historical release originals were not edited or given a new interpretation of their relative paths. The fifth page was a necessary recursive target of the first copies.

| New navigation copy | Byte-for-byte source |
|---|---|
| [build156 architecture copy](../../tools/history/documents/docs/audits/build156/architecture-assessment.md) | [Canonical original](../audits/build156/architecture-assessment.md) |
| [build156 remaining-work copy](../../tools/history/documents/docs/releases/build156-remaining-work-20261003.md) | [Canonical original](../releases/build156-remaining-work-20261003.md) |
| [build155 remaining-work copy](../../tools/history/documents/docs/releases/build155-remaining-work-20261003.md) | [Canonical original](../releases/build155-remaining-work-20261003.md) |
| [build155 architecture copy](../../tools/history/documents/docs/audits/build155/architecture-assessment.md) | [Canonical original](../audits/build155/architecture-assessment.md) |
| [build155 release-page copy](../../tools/history/documents/docs/releases/v0.2.6-build155-20261003.md) | [Canonical original](../releases/v0.2.6-build155-20261003.md) |

Two unchanged pinned upstream originals contain eight **upstream-relative references**, not promises of local APK assets. Mobile README SHA `852f84c7a58695bfca8730e003155c2a061d58e31f648e88d10e0528499b63ab` references four images and AGENTS at commit `10e58ce0c4ed424c7fbd9c91d48db06c6061b4e4`. Termux license SHA `a8992ad867b1b4ea442693acb2e1ee829a814758e79a099bd8243a04ae383304` references two directories and a shared license at `6e2689f55295fa444be8ac8592c527c2c5ef3253`. The checker outputs their exact upstream URLs as `upstream-source-relative` warnings. `--strict-history` still fails. A changed source path, source digest, or literal target cannot reuse the classification; other missing targets remain errors. Neither upstream original nor APK assets were modified for this classification.

1. When changing defaults, settings, environment variables, authorization, or ports, inspect ConfigStore, the configuration entry, and the actual call chain. Document visible scope, next activation point, and migration.
2. Synchronize focused guides, the handbook, README, and changelog. Preserve historical attribution instead of overwriting old results with present state.
3. Target existence and inclusion in Git/the complete source ZIP are separate checks. New maintained pages must enter the final delivery list.
4. Platform API documentation describes platform contracts. Hidden APIs, OEM behavior, and actual permission require current code and explicit device evidence. Primary-source links are not upstream endorsement of this branch.
5. Record test subjects, failures/skips, incremental/cache reuse, and unexecuted scope. Separate source CI, host, browser, actual APK, and phone acceptance; device acceptance is paused for this work.
6. Run source, lock/descriptor, and license checks. One delivery owner verifies package/signature/attachment hashes. Private keys, user credentials, raw device logs, and screenshots are not documentation uploads.

## Historical explanations included with source

The following original-language documents are historical evidence, not the English operating path:

- [ROADMAP.md](../ROADMAP.md)
- [adb-flow-audit.md](../adb-flow-audit.md)
- [backup-upgrade-acceptance.md](../backup-upgrade-acceptance.md)
- [backup-upgrade-baseline.md](../backup-upgrade-baseline.md)
- [device-acceptance-2026-09-05.md](../device-acceptance-2026-09-05.md)
- [extract-startup-speed-2026-09-10.md](../extract-startup-speed-2026-09-10.md)
- [functional-audit-rc1.4.md](../functional-audit-rc1.4.md)
- [issue67-recovery.md](../issue67-recovery.md)
- [maintenance-rc1.4-verification.md](../maintenance-rc1.4-verification.md)
- [optimization-backup-verification.md](../optimization-backup-verification.md)
- [optimization-implementation.md](../optimization-implementation.md)
- [optimization-runtime-verification.md](../optimization-runtime-verification.md)
- [optimization-site-verification.md](../optimization-site-verification.md)
- [optimization-transfer-verification.md](../optimization-transfer-verification.md)
- [optimization-web-verification.md](../optimization-web-verification.md)
- [pid-stop-fix-2026-09-10.md](../pid-stop-fix-2026-09-10.md)
- [plugin-download-npm-fix-2026-09-05.md](../plugin-download-npm-fix-2026-09-05.md)
- [plugin-layout-install-fix-2026-09-05.md](../plugin-layout-install-fix-2026-09-05.md)
- [release-rc1.2-2026-09-06.md](../release-rc1.2-2026-09-06.md)
- [release-rc1.4-2026-09-07.md](../release-rc1.4-2026-09-07.md)
- [releases/v0.1.5-rc1-notes.md](../releases/v0.1.5-rc1-notes.md)
- [releases/v0.1.5-rc1.md](../releases/v0.1.5-rc1.md)
- [releases/v0.1.5-rc2-build129.md](../releases/v0.1.5-rc2-build129.md)
- [releases/v0.1.5-rc2-notes.md](../releases/v0.1.5-rc2-notes.md)
- [releases/v0.1.5-rc2.1-build130.md](../releases/v0.1.5-rc2.1-build130.md)
- [releases/v0.1.5-rc2.1-build131.md](../releases/v0.1.5-rc2.1-build131.md)
- [releases/v0.1.6-alpha1-build134.md](../releases/v0.1.6-alpha1-build134.md)
- [releases/v0.1.6-alpha1-build135.md](../releases/v0.1.6-alpha1-build135.md)
- [releases/v0.1.6-alpha2-build136.md](../releases/v0.1.6-alpha2-build136.md)
- [releases/v0.1.6-alpha2-build137.md](../releases/v0.1.6-alpha2-build137.md)
- [releases/v0.1.6-alpha2-build140.md](../releases/v0.1.6-alpha2-build140.md)
- [releases/v0.1.6-alpha2-build141.md](../releases/v0.1.6-alpha2-build141.md)
- [releases/v0.1.7-rc1-build146.md](../releases/v0.1.7-rc1-build146.md)
- [releases/v0.1.7-rc2-apk-size.md](../releases/v0.1.7-rc2-apk-size.md)
- [releases/v0.1.7-rc2-build147.md](../releases/v0.1.7-rc2-build147.md)
- [releases/v0.1.7-rc2-emergency-recovery.md](../releases/v0.1.7-rc2-emergency-recovery.md)
- [releases/v0.1.7-rc2-microphone.md](../releases/v0.1.7-rc2-microphone.md)
- [releases/v0.1.7-rc2-notes.md](../releases/v0.1.7-rc2-notes.md)
- [releases/v0.1.7-rc2-plugin-network-issues79-80.md](../releases/v0.1.7-rc2-plugin-network-issues79-80.md)
- [releases/v0.1.7-rc2-web-identity-recovery.md](../releases/v0.1.7-rc2-web-identity-recovery.md)
- [releases/v0.1.7-rc2-web-mobile-3.0.3.md](../releases/v0.1.7-rc2-web-mobile-3.0.3.md)
- [releases/v0.1.7-rc2.1-build148.md](../releases/v0.1.7-rc2.1-build148.md)
- [releases/v0.1.7-rc2.1-maintenance-20260930.md](../releases/v0.1.7-rc2.1-maintenance-20260930.md)
- [releases/v0.2.0-rc2-20260930.md](../releases/v0.2.0-rc2-20260930.md)
- [releases/v0.2.0-rc2-notes.md](../releases/v0.2.0-rc2-notes.md)
- [releases/v0.2.1-rc.2-build190-20261019.md](../releases/v0.2.1-rc.2-build190-20261019.md)
- [releases/v0.2.1-rc.2-build190-fix1-20261019.md](../releases/v0.2.1-rc.2-build190-fix1-20261019.md)
- [releases/v0.2.1-rc.2-build190-fix2-20261020.md](../releases/v0.2.1-rc.2-build190-fix2-20261020.md)
- [releases/v0.2.1-rc.2-build190-fix3-20261020.md](../releases/v0.2.1-rc.2-build190-fix3-20261020.md)
- [releases/v0.2.21-build170-20261009.md](../releases/v0.2.21-build170-20261009.md)
- [releases/v0.2.5-build154-20261003.md](../releases/v0.2.5-build154-20261003.md)
- [releases/v0.2.6-build155-20261003.md](../releases/v0.2.6-build155-20261003.md)
- [releases/v0.2.7-build156-20261003.md](../releases/v0.2.7-build156-20261003.md)
- [releases/v1.2.0-rc1.2.md](../releases/v1.2.0-rc1.2.md)
- [releases/v1.2.0-rc1.4.md](../releases/v1.2.0-rc1.4.md)
- [releases/v1.5-alpha.2.md](../releases/v1.5-alpha.2.md)
- [source-updates/20261019-build190-vscreen-port.md](../source-updates/20261019-build190-vscreen-port.md)
- [stability-acceptance.md](../stability-acceptance.md)
- [terminal-process-reaping-handoff.md](../terminal-process-reaping-handoff.md)
- [ui-layout-plan-rc1.4.md](../ui-layout-plan-rc1.4.md)
- [ui-layout-rc1.4-verification.md](../ui-layout-rc1.4-verification.md)
- [update-flow-rc1.4-verification.md](../update-flow-rc1.4-verification.md)
- [upgrade-dsh-0.1.5-alpha.2.md](../upgrade-dsh-0.1.5-alpha.2.md)

## Original bytes and readable views

The following historical files originally used CRLF. Their usual paths provide views with only line endings normalized to the current LF standard. Full original bytes are retained in tools/history under existing Git attributes; retired material is not deployed or discovered as tests. No acceptance facts were added. View and original hashes are recorded separately.

| Readable view | Original bytes | Original SHA-256 |
|---|---|---|
| [build190](../releases/v0.2.1-rc.2-build190-20261019.md) | [Original](../../tools/history/documents/docs/releases/v0.2.1-rc.2-build190-20261019.md) | `f172ce28b9cbc8933e8ae3bc7ad0f01f39441e3c0e9eecbbcf955b06ea432d37` |
| [build190 fix1](../releases/v0.2.1-rc.2-build190-fix1-20261019.md) | [Original](../../tools/history/documents/docs/releases/v0.2.1-rc.2-build190-fix1-20261019.md) | `d34ca245134ce842f404c52fe52060c9255bd31d4ecee2bc7c5966a0017c3c0c` |
| [build156](../releases/v0.2.7-build156-20261003.md) | [Original](../../tools/history/documents/docs/releases/v0.2.7-build156-20261003.md) | `8cc70cb1720736e0f9c7427b6b5399155b1983d4bfe51d755ece02dc0196cd55` |
| [build190 virtual-screen/port source update](../source-updates/20261019-build190-vscreen-port.md) | [Original](../../tools/history/documents/docs/source-updates/20261019-build190-vscreen-port.md) | `7a37fa7c1155553d0fa767e629a5a437ccf33fcabb9d0e41b976fe7841890bc4` |
