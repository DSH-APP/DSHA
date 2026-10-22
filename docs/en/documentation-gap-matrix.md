# Documentation-gap report: reconciliation and completion matrix

Verified: 2026-10-21. Reference baseline: published F10 `087724c`. This page translates the build190/F11 reconciliation of the supplied documentation-gap report. The report audited `d2f883315d75420ab83b5d121f0e14eab9769e0b`, before F10; its source SHA-256 is `5f85f7f64225029f6d42f3585d7d722bc9feaa7893b524e2ce75e8945c04a541`. It is material to check, not product instructions or proof of current delivery.

The report's 33/29 documents, 79 broken references, 56 targets, old versions, and other statistics belong only to its snapshot. Reconciliation began with F10's existing distribution of 114 Markdown files, avoiding duplicate manuals, ADRs, and historical records. File existence, Git/source-ZIP inclusion, and availability of historical local evidence are three separate checks. The final delivery manifest and actual remote state determine publication visibility. F11 remained an unpublished software checkpoint; F12 final identity/results await freeze as explained in the [release introduction](release-introduction.md).

## Actual entry points for all 31 topics

“Existing” means F10 already supplied the core explanation. “Completed” means a focused page or current correction was added during this documentation work, not that a feature, historical rerun, or device matrix was completed. English links below provide the maintained operational counterparts.

| Report topic | Current document | Treatment and limits |
|---|---|---|
| 1 Virtual screen | [Architecture](virtual-screen-architecture.md), [guide](virtual-screen-guide.md), [ADR 0003](adr/0003-virtual-screen-lifecycle-and-input.md) | Existing frame/node/anchor, generation, closure, and failure explanations; the old ten-file/one-test count is not current |
| 2 ADB / Root / Shizuku | [Permission architecture](device-permissions-architecture.md), [channels](device-channels.md) | Centralized operations, 124/125/126, permission and execution identity; no new pairing/device test |
| 3 proot/proroot | [Engines](runtime-engines.md), [selection](runtime-and-network.md) | Existing default proot and bounded fallback; added setting choices, preserving old proroot defaults by date |
| 4 Ports | [Ports](ports-and-networking.md) | Existing dynamic/manual/reserved ports, local fallback and old origins; a helper alone does not prove production retry integration |
| 5 DNS | [DNS](dns-configuration.md) | Existing three scopes and narrow resolution retry; no global IPv6 disablement |
| 6 proroot static loader | [Engines](runtime-engines.md), [choices](runtime-and-network.md) | Actual reachable branches and vendor limits; source branches alone do not establish UI reachability |
| 7 proot seccomp | [Engines](runtime-engines.md), [choices](runtime-and-network.md) | Applies to the next launch's parameters; no invented performance loss without same-device measurements |
| 8 Desktop layout | [Browsers](browser-engines.md) | UA read timing and instance recreation; UA is not viewport or responsive-layout change |
| 9 System WebView / Low Gecko | [Browsers](browser-engines.md) | Actual detection/failure paths; Standard has no Gecko fallback and UA versions do not prove automatic switching |
| 10 PiP | [Picture-in-picture](picture-in-picture.md) | Default-off, OS permission, same-generation instance, new Task, restoration; no transferred old-APK device results |
| 11 Update checks | [Updates](update.md), [verification](download-verification.md) | Once per process, manifest/channel/checks; current ACTION_VIEW does not establish old build173 session fencing |
| 12 Terminals | [Terminals](terminal.md) | PTY/simple modes, tabs, exit identity/state; leaving UI does not prove process exit |
| 13 Six-step installation/maintenance | [Installation](install-and-maintenance.md) | Probing, targeted repair, recheck and log limits; no blind Node/DSH replacement on the component page |
| 14 Plugin management | [Operations](plugins.md), [runtime/permissions](plugin-runtime-and-permissions.md) | Separate provenance, candidates, transactions, deletion, and actual loading; no new review gate |
| 15 Backup/restore | [Backup/restore](backup-and-restore.md), [v5](backup-format-v5.md) | User flow and corrected re-export password wording; new exports are password-free, old originals still authenticate |
| 16 Automatic backup/retention | [Backup/restore](backup-and-restore.md) | Three schedules, deferral while busy, three normal copies; separate from one-click/manual workspace scope |
| 17 Data maintenance/format/rebuild | [Maintenance](install-and-maintenance.md), [data architecture](runtime-data-plugin-architecture.md) | Erasure retired; external workspaces only lose private references; unknown originals retained |
| 18 LAN | [LAN](lan.md), [ports](ports-and-networking.md) | Plain HTTP, 3081, current token/cookie, failure classification; host regression is not Wi-Fi evidence |
| 19 Bridge 3090 | [Bridge/files](bridge-and-files.md) | Protocol 3, 33 exact routes plus two prefixes, X-Token, request budgets/errors; runtime help remains the capability entry |
| 20 Sharing/DocumentsProvider | [Bridge/files](bridge-and-files.md), [Web files](web-file-sharing.md) | Stable IDs, anchor/link/FD, 32 MiB/2 GiB, and chooser completion boundaries |
| 21 Independent recovery DSH | [Recovery](emergency-recovery.md) | Separate root, five tools, native write confirmation, read-only readiness and exit; no dependency on the production rootfs |
| 22 Startup gates/recovery | [Diagnostics](startup-and-diagnostics.md), [maintenance](install-and-maintenance.md) | First request, three readiness layers, recovery ladder; UNKNOWN does not jump to clearing the environment |
| 23 Self-check/diagnostics/logs | [Diagnostics](startup-and-diagnostics.md) | Collection budgets, redaction/export limits; no automatic upload or universal secret detection claim |
| 24 Overlay/confirmation | [Overlays/notifications](overlay-and-notifications.md), [permissions](device-permissions-architecture.md) | Three channels and fallback; OS overlay permission and business approval remain separate |
| 25 Task notification | [Overlays/notifications](overlay-and-notifications.md) | Identity, click handling, notification permission; standard progress notifications, no promised promoted capsule |
| 26 i18n/language latching | [Language](i18n.md) | Process latch and Activity recreation, not background-process restart or a full language matrix |
| 27 Configuration snapshots | [Snapshots](config-snapshots.md), [recovery](configuration-recovery-host.md) | Six-file snapshots, host whitelist, explicit reset have distinct scopes, not a whole-device settings image |
| 28 Build/sign/release | [Build/release](build-and-release.md), [BUILD](../../BUILD.en.md), [CONTRIBUTING](../../CONTRIBUTING.en.md) | Identity, stages, fixed inputs, E7E3, source ZIP; release-identity JSON is not actual APK signing proof |
| 29 Tests/evidence | [Build/release](build-and-release.md), [handbook](project-architecture-handbook.md) | Selection/XML/skips/cache/static counts are separate; 511/45/785 belong to their original times |
| 30 Agent/issue/triage/domain | [Domain](agents/domain.md), [issues](agents/issue-tracker.md), [triage](agents/triage-labels.md) | Newly created in F11; no claim of recovered originals or configured remote labels; glossary on demand |
| 31 Built-ins/protocol/hot reload | [Plugin runtime](plugin-runtime-and-permissions.md) | Distinct 7/8/2/3 sets, policy source and protocol layers; HMR/dynamic-runner effects require matching inputs and execution evidence |

## Resolving the original 24 and added 21 discrepancies

Numbers below refer to report sections 6.1 and 6.5. A withdrawn allegation is not reinstated as a defect. Historical records retain their original time and identity; present navigation/facts can be added separately.

| Report numbers | Finding / action |
|---|---|
| 6.1 #1–4, #11–12 | F10 already added download verification, fix1, handoff, ADRs, and available history; current links target real files. Missing original receipts are not invented. |
| 6.1 #5 | A glossary is demand-driven, not a mandatory gap; domain guidance explains the current choice. |
| 6.1 #6 | Check `agent-skills` and publication visibility against the final stage. Directory existence and attachment inclusion differ; an old shallow clone does not prove present absence. |
| 6.1 #7–8, #9 | Old Standard/Low pages and v1.1.6 defaults are historical; present browser/engine pages own current rules. |
| 6.1 #10, #14–15, #18, #20 | Current AGENTS/plugins are synchronized by maintainers. Old directory counts, frozen dependencies, mobile versions, and fixed-3080 statements are not present facts. |
| 6.1 #13 | Installation documents actual component probes, log budgets, and integrity scope, without promising unimplemented certificate/npm checks or complete logs. |
| 6.1 #16, #21 | Restart prompts and explicit selection among candidates require interaction clarification; do not infer promised automatic restart or an in-app attachment picker. |
| 6.1 #17 | Disabling official core components may prevent page startup. Recovery uses native plugin management/explicit maintenance; undeletable does not mean impossible to disable. |
| 6.1 #19 | Preserve the withdrawal; a port helper alone is neither a production guarantee nor proof of a reproduced failure. |
| 6.1 #22–23 | Distinguish required package patches from optional entry declarations, and ordinary failed temporary candidates from UNKNOWN retained scenes. Not every candidate is retained forever. |
| 6.1 #24 | Dependency/export failure states require the actual chain and sample evidence. A missing standalone field proves neither universal breakage nor complete success. |
| 6.5 #1, #3–5, #9 | F9/2330 is not the present state. F10 `087724c` and its APK `922b325a…` are references. README/current Release/final receipts determine publication; historical tables remain unchanged. |
| 6.5 #2, #6–8, #13–16, #19 | Align current counting, dependencies/passwords, retired formatting, ports/bridge, and automatic enablement, without reviving old entry points or review rules. |
| 6.5 #10 | Concrete ADRs are reachable; agent pages were newly created. Current gitignore lacks the claimed old rule, so do not invent “unignoring” history. |
| 6.5 #11 | Separate small derived assets, exact byte pins, and large generated archives in BUILD/CONTRIBUTING. A non-ignored file is not thereby committed or a violation. |
| 6.5 #12 | Verify Star assets/workflows in the final stage. Old absence is not current absence; do not invent stars or workflow results. |
| 6.5 #17–18 | DocumentsProvider supports readable path encoding and caller-opaque IDs. Web-file integration 3.0.5 is historical; this round separately identifies 3.2.1. |
| 6.5 #20–21 | Built-in counts describe distinct purposes. Separate writing `patchReload` from consuming it; retain uncertainty rather than claiming HMR broken or device-verified. |

## Checking method and distribution scope

[check-document-links.py](../../tools/check-document-links.py) defaults to maintained entries/current topics. `--paths-file` accepts the exact Markdown list of a final source stage. Missing current targets and missing Markdown, including historical explanation pages, fail. Only eight registered targets in two exact pinned upstream originals are classified as nonlocal upstream references. Missing historical non-document local evidence is reported separately, without claiming it ships. `--strict-history` also fails such evidence and those eight references. The checker is offline: it does not establish URL availability, fragment meaning, or prose accuracy.

```sh
python tools/test-document-links.py
python tools/check-document-links.py --output app/build/build190-fix11-docs/current-links.json
# Also run with --root STAGE --paths-file EXACT_MARKDOWN_LIST against final staging.
# Verify that every new Markdown file is included in Git and the complete ZIP.
```

Tool regressions use isolated directories and exact upstream originals. They cover current broken links, reference links/HTML images/encoded paths, fenced-code exclusion, missing historical Markdown, local-evidence classification, escaping paths, missing source, and new unregistered pages remaining current. They also test exact upstream path/digest/target binding, strict mode, changed bytes, and copies at another path. Local receipts do not replace a fact review, actual APK validation, or phone acceptance.

## Physical closure of the F11 distribution stage

A readable developer tree did not establish distribution closure. The 135-Markdown F11 candidate stage produced 40 errors normalized to 19 targets. Those were resolved using genuine originals and actual click paths, without transferring the earlier C:-tree zero-error result to that stage.

The closure adds six genuine historical explanations (build154 POLICY, the rc2 global assessment, build155/156 architecture and remaining work), three source/retention JSON files, and the historical Termux README found on F: with its exact retention-list SHA. Build155 remaining work was a recursive dependency. Explicit JSON scope grows from six to nine files; the whole audit directory is not permitted. Historical prose retains its time; old data/capability policies and results do not become present promises.

Original-byte build156 release links and their recursive targets require five physical navigation copies: `tools/history/documents/docs/audits/build156/architecture-assessment.md`, `tools/history/documents/docs/releases/build156-remaining-work-20261003.md`, `tools/history/documents/docs/releases/build155-remaining-work-20261003.md`, `tools/history/documents/docs/audits/build155/architecture-assessment.md`, and `tools/history/documents/docs/releases/v0.2.6-build155-20261003.md`. The first four-copy check found the last missing page; that failure was retained before adding it. Each copy preserves bytes from its canonical document and is explicitly a new F11 navigation copy, without modifying originals or claiming rediscovery. Clickable paths are in the [index](index.md).

Eight remaining references belong to the pinned mobile README and Termux license. Complete source SHA, exact source path, and literal target bind each fixed-commit URL. `upstream-source-relative` warnings identify nonlocal targets; strict mode rejects them. Changed bytes, paths, or targets do not inherit the classification. There is no blanket exemption for vendored/history directories. Upstream prose/licenses and APK assets remain unchanged.

The delivery owner incorporates all 15 added distribution paths, full hashes, and copy relationships into the frozen manifest, then reruns links/privacy/ZIP membership against the actual stage and recomputes totals. Recursive large historical JSON ledgers, raw device images/logs, and private evidence are not uploaded to manufacture zero errors; unavailable evidence remains separately identified. This page does not predeclare a final stage result.

Documentation completion does not edit raw historical device logs/receipts, upload missing private evidence, operate ADB/phones, generate extra audit APKs, restore formatting, or turn primary-source links into official endorsement. The delivery owner coordinates final source/software/publication actions and state.
