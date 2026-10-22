# Startup and initialization performance investigation (2026-10-22)

Status: **Standard APK, segmented software checks and same-phone cold-flow/two-engine starts verified; complete business coverage and delivery still pending**. The pre-change maintenance baseline is `140c7ba277fc2a91a9b8fd8cb5a24bc35a5c41c5`. The integrated build candidate is `0.2.1-rc.2-20261022.0243-dsh0.2.1-rc.2` / code190 / `dsh.android.client`; this is not final delivery acceptance. A final delivery receipt must separately bind versions, signing, assets and tests. Source changes, passing host fixtures, phone observations and end-to-end gains are distinct evidence. Uploads, PRs and releases to public DSHA remain paused; only the existing private maintenance repository is authorized for this delivery after completion.

[简体中文](../performance-startup-20261022.md) · [Startup diagnostics](startup-and-diagnostics.md) · [Installation/maintenance](install-and-maintenance.md) · [Runtime engines](runtime-engines.md) · [Security model](security-model.md)

## 1. Accessible evidence and the two runtime categories

The lead maintainer used the connected phone's ADB and existing authorization to read accessible app logs, runtime state and selected configuration. This report's author did not independently operate the phone. Historical captures belong to `com.dsh.client` / code190 / the installation state of 2026-10-19, not a controlled test of this round's `dsh.android.client`. Initial phone data report Android13/API33 and USB charging; power, temperature, caches and system load were not fully fixed across runs. Raw phone logs, tokens, cookies, pairing keys, conversations and raw paths are not source-distribution material.

### 1.1 Reconstructed historical sequence

Five relevant records were found, rather than selecting just two. All starts below are local UTC+8 on 2026-10-19; durations are relative to their diagnostic zero and exclude earlier admission/preflight or time before the user starts the operation.

| Start | Requested / actual runtime | Sequence and result | Backend auth | Web-shell event |
|---|---|---|---:|---:|
| 18:23:44 | proroot → proot | proroot exits126 at8.6s; one fallback after exit verification; three old-plugin compatibility warnings | 68.1 s | Service ready, no corresponding shell-mounted event recorded |
| 18:52:35 | proroot → proot | proroot exits126 at9.2s; one fallback; similar plugin warnings | 76.6 s | 455.0 s |
| 19:13:42 | proroot → proroot | No fallback line | 17.8 s | 44.5 s |
| 19:29:18 | proot → proot | Ordinary proot path | 31.6 s | 158.4 s |
| 20:27:49 | proot → proot | Ordinary proot path, final record ready | 32.0 s | 37.7 s |

The first two are not proroot successes. Page events include the user's entry timing, old notice/configuration flow and frontend work; 455s or158s is not pure engine loading. Final elapsed values may include later session lifetime. Plugin compatibility, caches, preparation and actions differ, so17.8s versus31.6s is not a causal engine A/B comparison.

### 1.2 New baseline and observer failure

| Object | Observed value | Correct boundary |
|---|---|---|
| Baseline first app-initialization observation | File commit43.763 s | Approximately34.45s of trial observation followed; final trial/health closure was not fully verified in this observation. Separate operation from the isolated probe below. **Not complete initialization or core usability in44s.** |
| Exact baseline APK, isolated empty-root cold probe | Complete flow65.128 s | Real renderer, trial and process exit confirmed; file commit39.316 s and trial recordCloseEnd21.750 s are subsets of that flow, not additions to65.128 s. Actual runtime: proot |
| ca056 baseline first ordinary proot Web start | Auth link at58.9s | Backend only; page, notice saving, configuration, workspace and a real core request remain separate |
| ca056 baseline warm proot normal restart | Real auth at18.2s | Warm OS caches, no dropping; not a repeated two-engine median |
| Original warm observer | Timeout retained | It missed disk-log data; actual auth was checked separately. Do not label the backend hung or erase the false report |
|0243 Standard production proroot, three starts | Native diagnostic auth6.8 /5.2 /5.4 s | Actual proroot each time, no production fallback. Different clock from observer polling/page entry/core requests. Caches/order were not fixed; no causal percentage |
|0243 managed-update isolated trial | Actual proot; recordCloseEnd16.980 s | Selected proroot first exited126, then fell back. Renderer, storage write/reopen and guest/launcher exit passed. **Not a successful proroot trial** |
|0243 Standard, same-entry isolated cold probe | Complete42.772 s, file commit24.465 s | Actual proot, same flow as65.128 s baseline. Both pass complete hashing/publication, real renderer, data/session reopen and guest/launcher exit; not a real model-request completion |
|0243 Standard production proot, three starts | Native diagnostic auth12.8 /12.5 /12.3 s | DSH/plugin loading10.5 /10.0 /9.8 s. Requested/actual proot and Web-shell ready verified. Baseline warm auth18.2 s/loading15.9 s; order/caches not fixed |
|0243 independent rescue | READY→STOPPED, closed persisted | Real independent root, tools/HTTP auth, empty workspace and notice Continue/reload passed. No credentials; AI conversation/formal write repair untested |
|0243 Standard APK |292,159,963 bytes; package checks passed | Node isolated-hook sample in6.1; short resource samples in7.1, no power/thermal improvement conclusion |

The65.128s probe used the exact baseline APK on the same phone, SHA-256 `ca0562e5df373eed4a4546540d05a8c06aafbce5cac15c877b53915fb0444d19`. Its local raw receipt is `baseline-cold-probe.json`; the observer's68.703s outer polling time is a different clock. The probe used a genuinely empty private root and signed assets, preserving existing data. [ColdSetupTiming.Scope](../../app/src/main/java/com/deepseekharness/app/core/ColdSetupTiming.java) has isolated settings: although the user profile currently selects proroot, this record explicitly reports `runtimeMode=proot`. Do not classify it by the user profile. The candidate APK repeated the same entry and completed in42.772s. Undropped caches, fixed baseline-before-candidate order and one sample per package still limit causality; do not extrapolate a general speedup percentage. This endpoint includes isolated browser/read-write verification, not a successful real model request.

The exact0243 Standard APK has passed a same-signature stopped overwrite and managed update. The first production proroot preparation reached actual process creation at about3.4s, including2.4s of plugin registration; later registration took0.8/1.1s. The6.8/5.2/5.4s figures are native-log complete official auth-link events. Outer polling can arrive hundreds of milliseconds later or miss disk-log data; the second run's original observer timeout and later disk readback are preserved. The approximately9.0s baseline proroot capture had different caches, operations and order, so its difference cannot be wholly attributed to these changes. Keep the managed-trial proroot failure separate from successful production proroot.

Only `candidate-proot-corrected-1/2/3` count as the three genuine new-generation proot runs. The earlier script run named `candidate-proot-1` failed to start a new generation and still showed proroot; it is invalid for proot, regardless of filename. An accidental navigation triggered one additional4.9s proroot start, excluded from the planned three samples. Preserve those failures/navigation and corrected runs separately. Three Web-ready observations establish shell events, not successful real conversations.

## 2. Actual call chain and different clocks

[Application](../../app/src/main/java/com/deepseekharness/app/DshaApp.java), [MainActivity](../../app/src/main/java/com/deepseekharness/app/ui/MainActivity.java), [WebLifecycleController](../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java) and [EnvironmentMaintenance](../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java) have different timing boundaries.

| Stage | Actual work / entry | Not yet established |
|---|---|---|
| System creates process | ActivityManager event; DshaApp preferences/language, theme, listeners, tasks and job scheduling | UI displayed or scheduled work complete |
| Native check UI | MainActivity check overlay; new users first see Welcome | Environment/Web usable |
| Existing-run recovery/gate | restoreSession, pending/readiness; kernel→HTTP→kernel verification | Permission to discard UNKNOWN/exit evidence and spawn another guest |
| Required initialization/update | Independent candidate, digests, transactions, actual RuntimeTrial and health receipt | Extracted files mean full environment readiness |
| Main and start dispatch | FirstRunWebStart or LaunchFragment, generation/one-dispatch fences | Main display means backend-ready |
| Admission/preparation | requestStart preflight; StartupPipeline stops old guest/launcher/LAN, tools, transaction recovery, CAS and builtin registration | Diagnostic0.0s includes all earlier admission/click delay |
| Launcher/guest creation | RuntimeExecution/RuntimeLauncher select actual engine, port and environment and spawn fixed entry | Node/HTTP/frontend complete |
| DSH/plugin initialization | CLI installation/config resolution, dependencies/native libraries, Host/plugin composition and listening | Repeated same-name loading lines prove repeated full initialization |
| Backend auth link | Complete official startup line, actual port/current identity | Mounted DOM, configured model or all tools usable |
| Browser auth/document | PreviewAuth/DshAuthSession: token→cookie303, cookie→root200, WebView/Gecko resources | onPageFinished means core usable |
| Web-shell ready | startup.js sees no boot and composer/shell slots | Notice saved or real conversation completed |
| Core usable | Agreed real operation completes in this run with verified result and no unknown replay | Every model/plugin/device is accepted |

Initialization's trial already starts an isolated backend; do not count it again as ordinary first-Web startup. System events establish process creation, while app logs establish only their own zero. Page-ready keeps its original semantics; performance changes do not publish readiness early.

## 3. Approved narrow changes in source

These reduce demonstrated redundant work. Baseline first authentication occurred at58.9s, including a53.8s DSH/plugin-loading stage; the entire58.9s must not be attributed to Node/Host wait. Some device observations now exist; complete matched comparison remains pending.

### 3.1 One owner of browser authentication

- Problem/location: [LaunchFragment.enterWeb](../../app/src/main/java/com/deepseekharness/app/ui/LaunchFragment.java) exchanged a cookie before navigation, then [PreviewAuth](../../app/src/main/java/com/deepseekharness/app/ui/PreviewAuth.java) repeated the exchange. Real production classes plus synthetic loopback HTTP confirmed2 exchanges/4 requests for a new page.
- Change: Launch retains generation/URL, RESUMED/view, single-flight and navigation-error retry, then navigates directly. The existing browser owner still verifies token/cookie/HTTP200, cancellation/late results and generation. No persistent credential cache.
- Benefit: One exchange, two requests and a serial background thread are removed. Earlier browser display merely relocates waiting and is not backend acceleration.
- Measurement/validation: Ten host cases pass; old source fails expected controls under the new contract. Example timing with an artificial60ms per response is roughly362→180ms, not a device ratio or robust median. Actual Activity/Gecko/Back/rotation/background combinations remain separate.
- Risk/rollback: Errors appear earlier in the browser host, which retains error/retry/Back. Restore only Launch and matching debug/test contracts, not other edits or user data.

### 3.2 Prepared-preload reuse inside one launcher builder

- Problem: [RuntimeLauncher.builder](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java) prepared assets separately for bindings and environment.
- Change: One builder locally reuses its fully verified directory. Standalone environment/bindPreloads and the next builder still verify; no global filesystem-fact cache.
- Result: Warm host prepare calls2→1 and preload-content reads4→2; nine targeted and ten related cases including actual TerminalRuntime compilation pass. Device seconds are unmeasured; do not attribute DSH module gaps to this small change.
- Risk/rollback: Content, NOFOLLOW, package proof, root/exit/generation/fallback remain. Same-UID concurrent mutation is still not a hard sandbox. Restore only the corresponding Java/test scope, not runtime data.

### 3.3 Immutable directory-route cache per extraction session

- Problem: [AndroidTrustedAssetFileSystem.directoryHandle](../../app/src/main/java/com/deepseekharness/app/runtime/AndroidTrustedAssetFileSystem.java) repeatedly splits the same parents and constructs prefixes, creating transient allocation.
- Change: A64-entry LRU per filesystem instance stores immutable parts/prefixes only; parallel workers have separate instances. verifyRoot/lstat/type/inode/NOFOLLOW open/fstat order remains. No stat, FD, digest or authorization cache.
- Host result: Actual archive-order83,098 routes, five warmups/ten measurements: pure JDK parsing median41.16→7.32ms and cumulative allocation151,868,144→10,098,504 bytes. Roughly141.8MB is cumulative transient allocation, not phone peak-memory or extraction seconds.
- Validation/risk: Nineteen actual before/after method-body fake-OS outcomes/traces match; three independent weakened root/NOFOLLOW/inode controls are rejected; five route tests pass. Up to64 live routes retain bounded objects; fake OS is not Android syscall/race evidence.
- Rollback: Restore this Java file and new tests only; archives, full digests, transactions and recovery formats remain.

### 3.4 Avoid idle task-notification bridge traffic

- Problem: [dsh-task-notifier](../../app/src/main/assets/builtin-plugins/dsh-task-notifier/lib/index.js) read credentials and sent context/claim each tick without live tasks or pending terminal reports.
- Change: Check real work first and fetch context when needed; preserve active heartbeat, control claiming, terminal retry and owner/sequence/HMR.
- Host result: Same600 logical ticks: initial idle HTTP/credential reads601→0, completed/aborted idle600→0. Active event600/claim600 remain, with one context call when first needed. Not600 seconds of device use.
- Validation/risk: Full progress fixture and four independent adversarial concurrency/lifecycle cases pass; old bytes fail the new idle assertion. The lightweight1Hz timer and memory loop remain; no zero-wakeup, measured battery/cooling claim or power-policy change.
- Rollback: Restore plugin/tests and let the single build owner regenerate/verify assets; task data stays.

## 4. Initialization scale and five scenarios

Six major archives in exact baseline APK `ca0562e5…` contain48,796 entries/45,328 regular files and1,021,556,198 expanded regular-file bytes; overwrites slightly reduce net size. DSH has34,327 files/595,400,166 bytes, including18,501≤4KiB; base has7,222 files/294,712,627 bytes. The file counts are actual scans; host scanning time is not Android extraction speed.

The cold chain is [ColdBundleTransaction](../../app/src/main/java/com/deepseekharness/app/runtime/ColdBundleTransaction.java)→[ColdBundleExtractor](../../app/src/main/java/com/deepseekharness/app/runtime/ColdBundleExtractor.java). Base/DSH already extract in parallel to independent candidates, then Python/support/pnpm, tools/groups. Ubuntu configured-overlay-v1 uses a signed configured overlay and actual container probe. Two full digests at prepared/publish protect different switching boundaries; trial and health follow. Do not remove checks or replace commit proof with stat caching.

| Scenario | Work and boundary |
|---|---|
| First install/start | eligibleFresh accepts an empty private environment only; extraction/full digests/publication/trial/health all count. Files committed is intermediate |
| Start after initialization | Layout/markers, entry and descriptor/health; normal readiness reads receipts, not every archive again. Existing-run recovery and new-guest start are separate groups |
| First start after upgrade | Compare actual managed/attempted identity; same-base10 usually stages DSH/plugins with personal data/Ubuntu in place. Keep transaction/trial/exit checks; APK version alone does not force reinstall |
| Android cache cleared | Files-based environment/health should survive; temporary files rebuild under their rules. Check OEM cleaner scope; do not clear production data for a benchmark |
| Failure/interruption recovery | Retain cold intent/launch/publishing/committed. Recover transactions/prove guest exit first, never overwrite a nonempty failed tree as fresh. Old failed slots stay; a new slot may repeat extraction. No general resumable-extraction guarantee |

A historical old-package empty-root record totals115.042s: base/DSH43.135s, Python/pnpm2.149s, tools1.063s, Ubuntu overlay4.459s, probe→extraction-complete0.558s, prepared digest10.391s, publish digest/switch10.222s, trial record35.753s and7.225s unclassified. These are stage-event wall intervals, not function CPU time. Trial native-ready→storage26.727s precedes the plugin-initialize entry marker; it is not26.7s of measured disk writing. New43.763s file commit and old115.042s full initialization have different endpoints and must not be subtracted as a speedup. The same-phone exact-baseline65.128s isolated cold probe is the reference for the upcoming candidate, not a direct speedup against the old115.042s record. File commit39.316s and trial21.750s are nested stages; subtraction alone does not attribute remaining work to a function.

### 4.1 Exact baseline and candidate through the same cold entry

| Timing boundary | Exact baseline APK | Exact0243 APK | Meaning |
|---|---:|---:|---|
| Complete isolated cold probe |65.128 s |42.772 s | Observed22.356 s less; includes complete commit, trial/real renderer and exit, not just a hidden waiting page |
| base/DSH extraction stage |23.913 s |13.177 s | Wall interval between stage events; Python and later work remain separate |
| File-commit point |39.316 s |24.465 s | Cumulative from the full-flow zero, including extraction and both hash/publication passes; do not add extraction again |
| Trial recordCloseEnd |21.750 s |15.844 s | Trial-local clock includes renderer/exit and sits inside the whole flow; do not add it to the total |

Both retain full content hashing/publication, real browser verification, data/session reopen and managed guest/launcher exit. The candidate really removes19,322 files to extract, the route cache reduces repeated allocation, and production proot/proroot authentication samples are shorter than earlier observations. Together these support less actual work and shorter observed time for this device/flow. Fixed order, warm caches and no repeated cold samples prevent separating each change's independent contribution; do not attribute all22.356s to one optimization or promise it for every phone. See6.1 for the isolated Node-hook sample; it does not cover every trial wait or establish a unique cause before storage.

## 5. Reduced, background, deferred and hidden work

| Category | Applies here | Supported interpretation |
|---|---|---|
| Truly reduced work | One less auth/prepare, repeated route parsing and idle bridge calls | Host call/allocation counts support it; whole-device gain separately measured |
| Moved to background | Web after Main was already background behavior | Not a new improvement; concurrency still competes for CPU/I/O |
| Deferred until use | Context fetched for real tasks | Idle avoids it, active use still does it; not completely deleted work |
| Waiting UI relocated | Launch navigates early while browser authenticates | Navigation speed is not backend/core readiness |

Extraction buffers, short-file JNI, fixed FDs/directory caches, batched small-file SHA and parallel base/DSH extraction already existed. A configured10s probe budget is not proof of a10s startup wait: open-in-app is lazy on relevant requests. A loop or duplicate-looking code is not a measured critical-path bottleneck.

## 6. Dependency and inactive-code decisions

The lead has **included Zod consolidation in the exact0243 Standard APK; package verification and some production-device paths passed, with complete acceptance still pending**: root4.6.7→4.4.3 merges57 nested4.4.3 copies. Semver satisfiability is not semantic equivalence. Archive SHA-256: `85b108dd8017dcfdc8bbfb7c87773ea78b00ada320fb64ae4105de959e6250b5`. Actual candidate counts remove19,322 regular files,155,077,846 expanded bytes and21,246,550 gzip bytes; non-Zod members are individually unchanged. This reduces real extraction work. Archive deltas differ from final APK-size deltas and measured phone time. The exact APK is292,159,963 bytes versus314,315,600 baseline bytes, a real reduction of22,155,637 bytes. Code, other assets and ZIP layout also changed; do not equate the entire package delta with the Zod gzip delta.

Selected real schema/codec, ToolRuntime, CLI/profile and guard fixtures pass; original obsolete pipeline failures/preparation timeout remain recorded. Module identity, root-version change, user plugins and independent recovery still require final gates. The earlier isolated review found that the old recovery lock would force two DSH archives, potentially adding roughly127.3MB. The lead has now synchronized the [recovery lock](../../tools/recovery-runtime/lock.json), and shared-asset generation passed, addressing that build-level risk. The exact APK recovery/full-asset checks now passed, binding the actual shared mapping and archives. Independent rescue read-only startup/page/stop now has separate device evidence in8.2; AI conversation and formal write repair remain untested. Generation, package checks and functional evidence remain distinct.

| Classification | Example and decision |
|---|---|
| Confirmed inactive | Empty cleanupCompleted; removing a call has negligible speed benefit, not a reason to break interface/recovery |
| Highly suspected | Old full-format writer/helpers; persisted pending readers still matter, no bulk delete/reset |
| Further verification | ScreenSerialExecutor/ScreenTargetEvidence without ordinary callers; still inspect tests/reflection/JNI/external entry |
| Still useful | DeviceFileCore string CLI, generated policy copies, old transaction readers, flavor-specific dependencies; retain |

### 6.1 Isolated Node-hook sample on the real phone

The lead captured same-phone native proroot runs in the `untrusted_app` domain, on the same boot/script with the same three hook modules and no invalid arguments. Both use fresh core-only profiles, exclude the native bridge/user plugins, and start after native Terminal enters the container. Do not compare them directly with production58.9/18.2/9.0s or health trials. Baseline/candidate backend authentication took5.065/2.705s; plugin-tree activation loader await took4.483/2.157s. These are instrumented samples, not ordinary production starts or full frontend readiness. They support a substantial loading-wait difference in this sample, without proving that Zod alone caused the whole difference.

The candidate1,597 versus baseline5,010 load counts are **cumulative hook events including a five-second observation window after authentication**. They are not unique modules, total files or pure-startup module counts. There is no plain unhooked control. Hooks/process orchestration have overhead, the baseline precedes the candidate, caches are warm and samples are limited. Do not generalize causality or a universal percentage, or equate these events with the541 name-based BFS manifest reads.

The local `runtime/isolated-proroot-paired-analysis.json` identifies aggregate plugin-tree activation waiting: module resolution/read/parse/evaluation, service injection and asynchronous activation. It does not isolate exclusive CPU/I/O or blame one plugin. Load/resolve durations can be inclusive/reentrant and cannot be summed as disjoint stages. Child exit0 is evidenced, but does not by itself establish every guest/launcher closure; the central receipt must bind the exact APK/descriptors.

## 7. CPU, memory, power and heat

Source behavior, host counts and roughly25s foreground/background phone samples of this package exist; **no controlled phone power/temperature before/after comparison is available**. USB charging, system/other apps, ambient temperature and ADB sampling can interfere. Source alone does not identify or solve a heating cause.

| Phase | Confirmed source behavior / missing measurement |
|---|---|
| Startup | Node/Host resolution, modules/plugins and guest preparation; host sample541 manifests/~1.06MB/~1362 exists checks. Measure CPU/I/O; Zod does not reduce name-based BFS |
| Idle/tasks | No-task bridge calls removed;1Hz timer remains, with native1Hz maintenance after task-UI initialization. Not zero wakeups |
| Virtual-screen core | Active display checks ImageReader about33ms and may allocate bitmaps for fresh frames; shared preview/frame caching already exists. Cost depends on actual frames; no sampling/flicker/input fix claimed |
| Background | eco=false default; policy may hold CPU/WiFi locks; eco needs fresh idle/other gates; separate ADB has bounded probes/backoff. Keep continuous-service/UNKNOWN/maintenance safety |
| Logs/integration | Bounded history rewrites and roughly2s activity snapshots exist; no measured dominant contention/I/O/heat attribution, so no blind rewrite |

Measure CPU, RSS/PSS, threads/processes, read/write bytes, GC, thermal and charging across startup/idle/core/background. Match device/options and repeat for medians/ranges where possible. Low battery does not cancel authorized acceptance; shutdown/lost permission must retain interrupted results, never invented completion.

### 7.1 Short foreground/background samples of the exact current APK

The local `resource-observation-summary.json` summarizes25.000s/six points in foreground and25.015s/six points in background. Both retain the same five app-UID process identities, with matching birth evidence and no model/tool request. Only this APK was sampled; this is not baseline/candidate power A/B.

| Raw metric | Foreground idle | Background idle | Boundary |
|---|---:|---:|---|
| CPU-tick delta, same-birth processes |253 |228 | Raw ticks; no assumed CLK_TCK/HZ or conversion to CPU seconds/utilization |
| read_bytes delta |40,960 |8,192 | Kernel I/O counters for these UID processes, not all system/renderer I/O |
| write_bytes delta |831,488 |958,464 | Real background writes remain; not zero background work |
| Summed VmRSS range, KiB |776,524–802,256 |786,904–788,056 | Shared mappings may be counted twice; not unique physical use or whole-app PSS |
| Summed thread-count range |107–107 |106–106 | Only these five processes in a short window, not evidence of long-term leak freedom |
| nativeMain PSS first/last, KiB |223,699→194,182 |190,506→190,369 | nativeMain only, not the whole tree; a first/last decrease is not an optimization gain |
| Battery temperature / level |35.9–36.0°C /27% |35.9–36.0°C /27% | Battery temperature during USB charging, not CPU die/room temperature; no energy/cooling conclusion |

A browser renderer on a separate UID is excluded. Other apps, system load, charging and sampling overhead were uncontrolled. The app-UID process set stayed unchanged in this window, without proving leak freedom, endurance or a solved heating cause. The full matrix of actual core work, screen-off/long background, power conditions and longer CPU/GC/thread/I/O observations remains untested.

## 8. Independent checks and rollback

Independent review accepts four narrow changes in their host scope; device conclusions remain limited to completed paths and outstanding checks: auth/401/cancel and old-source controls; task owner/HMR/terminal retry/adversarial concurrency; actual route-method traces/three weakened-guard controls; launcher content mutation/link facts/next-builder revalidation. Fake OS, classes rebuilt on historical classpaths and loopback HTTP do not establish Android full-stack acceptance.

Final coverage needs requested/actual proot/proroot, first/subsequent start, upgrade identity, cache invalidation, interruption, notice persistence, configuration access, directory picking, plugins, normal stop and a real core request. UNKNOWN does not authorize business retries/cross-channel replay. Process creation is not core usability; retain failures and corrections separately.

Rollback exact file/test scopes after hash checks, then regenerate assets centrally. The integrated dependency archive requires coordinated locks/recipe/descriptor, recovery lock and verified managed transactions, not manually replacing the user root or rolling back conversations. Keep historical APKs/Low172/data; no uninstall, clear-data or external-workspace deletion.

### 8.1 Final software and exact-package snapshot

The local `software-summary.json` reports **PASS_SCOPED_SEGMENTED**, not an uninterrupted all-green run: each flavor totals184 classes/1,143 tests with zero failures/errors. Standard skips3, Low skips76. The original two stale-fixture failures remain preserved; after correction, the10-test class was rerun for both flavors. The merged result does not rewrite the original attempt. Release Lint has zero errors/fatals, with249/162 warnings respectively; warnings and skips are not zero.

The32 host gates comprise28 fresh executions and4 reused results after exact source and raw/managed byte matching. They are not32 new runs. APK identity, manifest, signature, recovery, licenses and full-assets gates passed. The original stale-pointer input failure remains recorded; an exact raw-proof rerun established the corrected result. Native bytes were individually unchanged, supporting byte-identity reuse rather than a claim that every native device matrix reran. Builds were incremental. Low received software checks only and was not installed on the phone.

Exact Standard APK: SHA-256 `d77bf985a5f9ec49be0667625894bcd16c5f3a23df2210a837527af8a172a947`,292,159,963 bytes; `dsh.android.client` / code190 / `0.2.1-rc.2-20261022.0243-dsh0.2.1-rc.2` / E7E3 / non-debuggable. The final receipt must still bind the source ZIP/private upload. Package checks do not establish every feature, long-duration behavior or power use. The recursive Root READ nested-symlink inspection gap remains unfixed; see the [security model](security-model.md). No real sensitive-file exploit was attempted this round.

### 8.2 Exact new independent rescue: device scope

The new rescue runtimeId is `41c4ba0813eabf310cba14a5020d80bc7e01a7604bdca8aea02de961eef1fb20`. The local `candidate-ready-recovery-summary.json` records READY. The lead verified the independent runtime root, tool checks, HTTP authentication and an empty RecoveryWeb workspace on the phone. Continue dismissed the new welcome notice, and reloading that rescue page did not show the preview notice again. This covers this rescue profile/page reload, not every device or ordinary production profile.

Without model credentials, the page still requests an API key. That is a configuration prompt, **not successful AI-conversation acceptance**. No formal-environment write repair was confirmed. After normal stop, `candidate-stopped-recovery-summary.json` records STOPPED with closed persisted, keeping the session/diagnostic originals. Read-only rescue startup/page/stop evidence does not replace the transaction/authorization matrix for confirmed write repairs.

### 8.3 Limited coverage of three foreground returns

The local `candidate-background-return.json` records three paced Home→launcher returns. Actual `topResumedActivity` was WebPreviewActivity each time; app-UID process membership and identities stayed the same, without evidence of a new Web generation. This covers those three returns, not killed-process recovery, every ROM/PiP/rotation combination or endurance, and contains no model request.

The first helper incorrectly read `mResumedActivity`, absent on this system, causing an empty assertion. The original helper/failure is retained; all three runs passed after reading actual `topResumedActivity`. This was an observer-field mistake, not an app crash that was subsequently fixed. Observation intervals contain scheduling/pacing and are not precise warm-start latency.

### 8.4 Normal stop and bounded file comparison

The phone was finally stopped normally, leaving only the new package's native main process, with proot still selected. The installed APK read back from the phone retained the local exact `d77bf985…` SHA-256. Exit was not manufactured by force-kill or clearing data. This round did not modify the old package.

The local `data-comparison-summary.json` checks only49 selected managed helper/cache regular files in the new package's fresh formal `.dsh` HOME. Both snapshots contain49,42 byte-identical, zero added/deleted, and seven classified changes. Two registry/proof files match the new signed APK; three pyc files change only their first16-byte headers, with identical bodies; the repair log strictly appends; the dependency-link cache changes only managedAssetId/stamp. These are derived maintenance files, **not a complete integrity comparison of personal configuration, conversations, plugins or external workspaces**. Do not broaden this to all user data being unchanged.

## 9. Pending final acceptance

The local exact Standard APK is verified in the scopes above. Private delivery is bound by the [same-day release report](releases/v0.2.1-rc.2-build190-performance-20261022.md) and central receipt; public uploads remain paused. These unfinished scopes do not mean a fault disappeared:

- Exact APK identity and software scope are confirmed in8.1; final delivered copies, source ZIP, private commit and attachment availability still require a receipt.
- Three actual starts and shell-ready observations exist for each engine; strict order/cache matching, admission/browser detail and real core results still have gaps.
- Same-entry complete fresh flows are compared, with file commit/trial separate; the full interruption/upgrade/cache-invalidation matrix is incomplete.
- Independent rescue read-only startup/page/stop and the isolated Node-hook sample are covered; rescue AI conversation/write repair and other plugin/security/business paths remain gaps. Retain8.1's segmented, skipped, original-failure and evidence-reuse boundaries.
- CPU/memory/I/O coverage is limited to7.1's short windows. Core-work, endurance/interruption and power matrices are incomplete; power/thermal improvements remain unmeasured without controlled comparison.
- Private final commit and complete-source ZIP/APK availability/digests. Public publication stays paused.

Local detail is indexed under build190-perf-20261022 architecture/runtime/initialization/energy/dedup/validation and lead device receipts. Distribute appropriate summaries/source/tests, not phone captures.
