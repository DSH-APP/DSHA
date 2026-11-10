# Over-the-top upgrades, runtime maintenance, and built-in plugin dependency contracts

> Checked on 2026-11-06. Baseline: build324 (released) / build325 (T61 mobile-shell gear fit) / build326 (T60 display-layer and unresolved-Web fixes) / build327 (T67 maintenance performs the full stop itself) / T91 (overwrite retire of the previous build; next build). This page turns the "white screen, no environment maintenance, duplicated sidebar, and maintenance telling the user to stop Web by hand" incident after an over-the-top upgrade into persistent invariants across the decision layer, display layer, auto-start safety gate, stop-before-maintenance, and Web start admission. It reviews source and existing regressions only; it does not replace device acceptance. Per-version scope remains in the matching [release records](releases/).

## 30-second summary

1. **The decision layer was already correct**: `EnvironmentAccess.requiresMaintenanceBeforeStart` and `EnvironmentIdentity.shouldBlockRuntimeStart` return "maintenance required" on both 322→323 (dsh version change) and 323→324 (same dsh, only managed patches changed). Do not modify these functions and do not attribute the incident to them.
2. **The gap was in the display layer**: `RuntimeInstallationState.isEnvironmentReady()` answers "can the old tree still run", not "is it the current candidate". Rendering maintenance only from it misreads a patch-level overwrite as "nothing to do": the maintenance page promotes "Enter Main" and demotes update; limited entry records no limitation; the Main gate still requires maintenance, so the user is bounced back and forth.
3. **Auto-start may be silently blocked**: if the old proot Web process survives an over-the-top upgrade, `hasUnresolvedWebSession()` is true and `shouldAutoStart` returns false. That is a **required** safety gate (never replay an unknown instance), but the user must still see the full-screen maintenance page and manually start the update.
4. **White-screen mechanism**: `WebLifecycleController` returns `REJECTED` when `!isEnvironmentReady() || requiresMaintenanceBeforeStart()`, preventing the watchdog from starting Web on a "compatible but outdated" runtime. On host recreation / a watchdog restart, "old tree + new APK" never starts Web and the Main Web area stays blank. If the old proot Web process is still alive and the page has already loaded the old tree, the plugin's dependency on the host indicator can also blank the content area and duplicate the sidebar; in the T61 device control only that plugin contract changed and the white screen disappeared, although the direct JS stack was not captured — see [the blank-screen investigation](../releases/v0.2.1-rc.5-build324-blank-screen-20261105.md).
5. **Client plugins must not depend on a host widget existing**: the built-in `dsh-web-mobile` sidebar gear used `:has(...)` to detect whether the host rendered `ConnectionIndicator`. When a managed patch stopped rendering that indicator, the plugin render chain broke; disabling the plugin restored the UI. The fix measures actual widths and writes an idempotent result into the plugin's own `data-mobile-nav-quick-fit` attribute.
6. **The maintenance action must complete the full stop by itself** (build327): after the user taps "Update runtime", the maintenance task stops the old Web using the existing design (sentinel → PID record + birth identity), then takes the barrier and runs the update. If the ordinary stop is unconfirmed, it escalates exactly once to the identity-verified force stop. The user no longer has to detour through "limited Main → Stop → back to maintenance". If the stop cannot be confirmed, the original environment is retained and the page shows an actionable retry message.
7. **Overwrite retire** (T91): only when the installed APK identity differs from the recorded session identity may the maintenance action, after two full birth-identity checks, end the previous guest first and then that record's old proot launcher. Non-overwrite paths, the current instance, terminals, and isolated trials are untouched (I9 remains the default). A trial teardown no longer fails just because the proroot launcher outlives its guest.

## Invariants (do not break)

| # | Invariant | Check / regression |
|---|---|---|
| I1 | After an over-the-top upgrade, if the **installed runtime is not the current candidate** (`RuntimeDescriptor.latest` is false), maintenance is required even when the runtime is compatible, healthy, and on the same dsh version. | `OverwriteUpgradeGateTest` (`app/src/test/java/com/deepseekharness/app/backup/OverwriteUpgradeGateTest.java`, T60 / build326) covers 322→323, 323→324, and a synthetic patch-level predecessor |
| I2 | `isEnvironmentReady()` means only "the environment can keep running" (complete install + compatible + health receipt); it does **not** mean "nothing to do". The display layer must separate "runnable" from "current candidate". | `RuntimeUpgradeState.upToDate(ready, latest)`; from build326 the maintenance page readiness check also requires `runtimeLatest` |
| I3 | After an over-the-top upgrade the user must see the **full-screen maintenance page** with a one-tap update. Auto-start may be blocked by an unresolved old Web session, but the entry point must not disappear, and "Enter Main" must not substitute for the repair entry. | `RuntimeUpgradeState.openMaintenancePage`; `MainActivity` entry gate; `OverwriteUpgradeGateTest.unresolvedOldWebStillOpensMaintenancePageButNeverAutoStarts` |
| I4 | An unresolved old Web session (`hasUnresolvedWebSession()`) **only blocks auto-start**, never the maintenance page; the unknown instance is not replayed automatically and is updated only by an explicit user tap. | `EnvironmentIdentity.shouldAutoStart(..., unresolvedWebSession)` still returns false; `MainActivity` no longer requires `!unresolved` to open maintenance |
| I5 | Web start admission must reject "old tree + new APK" until maintenance completes or the user has entered the limited Main under the rules; watchdog/foreground service and Main share the same decision. | `WebLifecycleController.requestStart`'s `!isEnvironmentReady() || requiresMaintenanceBeforeStart` gate; do not weaken it just to remove a white screen |
| I6 | A UI-only APK overwrite **must not** rebuild the environment; the same dsh version with changed managed patches or descriptor inputs **must** be treated as a patch-level update (recompute descriptor, enter maintenance, reinstall the managed tree). | `RuntimeDescriptor.latest` compares `id` and `inputs`; `OverwriteUpgradeGateTest.uiOnlyApkUpdateWithUnchangedManagedInputsNeverNeedsMaintenance` |
| I7 | Auto-start the same candidate at most once; failures, interruptions, rotation, and process recreation never replay it. Limited entry writes no readiness marker, and terminal / Web / plugin execution still pass environment gates. | `EnvironmentIdentity.shouldAutoStart`; `MaintenanceCoordinator.pending`; `ExtractActivity.proceed()`'s `limitedEntry` |
| I8 | Built-in plugins may depend only on **their own attributes plus measured geometry**, never on whether some host widget exists (class names, `data-*`, `role`, `:has()` are all off-limits). Host UI changes must not break the plugin render chain. | [`tools/test-mobile-gear-contract.mjs`](../../tools/test-mobile-gear-contract.mjs) (T61 / build325); [`tools/apply-mobile-client-patches.mjs`](../../tools/apply-mobile-client-patches.mjs)'s `DSHA_GEAR_FIT_V1` |
| I9 | After the user explicitly confirms maintenance, the maintenance action **performs the full stop itself** before taking the barrier and running the update. If the ordinary stop is unconfirmed, it may escalate exactly once to an **identity-verified** force stop (PID, birth time, command line, generation; never port / name / process group). If verification fails or the process still cannot be confirmed exited, the original environment is retained and the failure message offers a retry. An unconfirmed old Web still only blocks auto-start, never the manual tap. Non-overwrite paths (current instance, ordinary stop, trial) are unchanged; see I10 for the overwrite exception. | `MaintenancePreStop` (`core/`) and `WebProofStop` (`util/`); `MaintenancePreStopTest`, `WebProofStopTest`, `WebRecordedStopTest`, `WebForceStopTest`, `WebSessionProofTest` (T67 / build327) |
| I10 | **Overwrite retire (T91)**: only when the installed APK identity differs from the recorded session `appIdentity`, an overwrite stop may, after **two full birth-identity checks** (boot, PID, birth time, command line, UID, generation / instance / nonce), end the recorded guest first and then that record's old proot launcher: SIGTERM, short wait, then SIGKILL if it did not exit. The launcher is never touched while the guest is unconfirmed; any failed / unreadable check, PID reuse, foreign UID, or change between the two observations means **no signal** and the original environment is retained. Never port / name / process-group / scan; never the current instance's launcher, terminals, virtual screens, or isolated trials. | `WebLifecycleController.previousBuildInstance` / `overwriteRetireCandidate`; `WebProcessManager.prepareOverwriteRetire` / `observeRecordedLauncher`; `WebProofStop.runRetiringLauncher`; `WebOverwriteRetireTest`, `WebProofStopTest`, `WebSessionProofTest`, `WebForceStopGateTest` (T91) |

## The four layers

### 1. Decision layer: entry and Web start share one identity

- [`EnvironmentAccess.requiresMaintenanceBeforeStart`](../../app/src/main/java/com/deepseekharness/app/core/EnvironmentAccess.java) first consults `DshVersionPolicy` (installed newer → maintenance required) and returns true; then compares `runtimeLatest` (already current → no maintenance); otherwise it calls [`EnvironmentIdentity.shouldBlockRuntimeStart`](../../app/src/main/java/com/deepseekharness/app/util/EnvironmentIdentity.java) with `candidateAttemptKey` (`runtime:<runtimeId>@<APK lastUpdateTime>`).
- `shouldBlockRuntimeStart` semantics:
  - `latest` true → allow (no maintenance).
  - Bundled descriptor unreadable → block (never let the watchdog start Web on an unknown runtime).
  - Otherwise return `shouldAutoStart(false,false,false,candidate,attempted)`: a **not-yet-attempted new candidate must enter maintenance**; after one attempt, the maintenance page and the healthy predecessor decide whether the old tree may still run.
  - `busy` / `pending` decide only whether maintenance can start right now; they must **not** turn an untried new candidate into "no maintenance needed".
- `OverwriteUpgradeGateTest` asserts for both 322→323 and 323→324 that `compatible()` is true, `latest()` is false, and `shouldBlockRuntimeStart` is true. This proves the **decision layer needs no change**.

> Do not "save the user a bounce" by changing `requiresMaintenanceBeforeStart` or `shouldBlockRuntimeStart`. The fixes belong to the display layer and to whether the entry opens.

### 2. Display layer: a patch-level update also counts as unfinished

The real gap was rendering and limited entry:

- [`RuntimeInstallationState.isEnvironmentReady()`](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeInstallationState.java) = `isEnvironmentInstalled() && rootfsVersionMatches()`.
- `rootfsVersionMatches()` uses only [`RuntimeDescriptor.compatible()`](../../app/src/main/java/com/deepseekharness/app/backup/RuntimeDescriptor.java) (compares `baseVersion` / `launcherContract` / `bridgeProtocol` / `dataRead` containing `dataWrite`) plus a health receipt. **It ignores `runtimeId` and `inputs`.** So a same-dsh-version, patch-only overwrite looks "ready".
- If the UI trusts that "ready":
  - [`MaintenanceFooterPolicy.decide(..., ready=true)`](../../app/src/main/java/com/deepseekharness/app/util/MaintenanceFooterPolicy.java) returns `Primary.DEMOTED` + `Entry.PRIMARY`: **"Enter Main" becomes the primary action and update/retry is demoted**;
  - `ExtractActivity.proceed()` does not count "not the current candidate" in `limitedEntry`, while the Main gate `requiresMaintenanceBeforeStart` still requires maintenance, so the user is sent back repeatedly;
  - [`EnvironmentUiStatus`](../../app/src/main/java/com/deepseekharness/app/ui/EnvironmentUiStatus.java) checks only `isEnvironmentReady()`, so the limited Main has no route back to maintenance;
  - the maintenance page has no "managed patches are not in place yet" message, so the user cannot tell whether patches were updated.
- From build326 (T60) the pure logic `RuntimeUpgradeState` (`app/src/main/java/com/deepseekharness/app/util/RuntimeUpgradeState.java`) separates the two dimensions:
  - `upToDate(environmentReady, installedLatest)`: only "can keep running **and** the installed runtime is the current candidate" counts as nothing to do;
  - `maintenanceRequired(...)`: an unfinished patch-level update also requires maintenance;
  - `limitedEntry(ready, latest, busy, pending)`: entering Main must record limited entry when the environment is unready, the runtime is not current, maintenance is busy, or a transaction is pending;
  - `openMaintenancePage(gateFailed, maintenance, restoring)`: an unresolved old Web session does not prevent opening maintenance.

> The decision layer and the display layer must share one source of truth: entry, maintenance page, Main banner, and Web admission all derive from `EnvironmentAccess` / `RuntimeUpgradeState`. Any place that substitutes `isEnvironmentReady()` for "done" will recreate this incident.

### 3. Auto-start safety gate: blocks auto-start, not the entry

- An over-the-top upgrade changes `StoredWebSession.appIdentity()` (it includes the APK `lastUpdateTime`). If the old proot Web process is still alive, the new package observes `LIVE` with an incompatible identity; recovery can only be UNKNOWN, so `HarnessController.hasUnresolvedWebSession()` is true.
- The full overload of [`EnvironmentIdentity.shouldAutoStart`](../../app/src/main/java/com/deepseekharness/app/util/EnvironmentIdentity.java) explicitly includes `!unresolvedWebSession`: with an unconfirmed original instance, auto-start returns false. **This is a correct safety gate and must stay.**
- The user's symptom is "maintenance did not start automatically". The correct product behavior is not to force auto-start, but to:
  1. still open the full-screen maintenance page;
  2. explain prominently that the installed runtime still belongs to the previous build and this build's managed patches are not in place;
  3. offer "Update runtime" as the primary action, started only by an explicit tap;
  4. allow the limited Main for logs/configuration while Web and plugin execution remain gated.
- Failed/interrupted old candidates follow the `previousStatus` / `previousRuntimeHealthy` conditions; a repair APK upgrade does not turn an unknown scene into something safe to replay.
- This gate only decides whether to start **automatically**. A user tap on the maintenance page is not blocked by it; the maintenance action then performs the full stop itself (next section).

### 4. Web start admission: the actual cause of the white screen

[`WebLifecycleController`](../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java) checks before enqueuing:

```java
if (!proot.isEnvironmentReady() || EnvironmentAccess.requiresMaintenanceBeforeStart(owner)) {
  // "The runtime is missing or needs an update. Open installation and repair."
  return StartAdmission.REJECTED;
}
```

The source comment states the reason: after an over-the-top upgrade the foreground `START_STICKY` service may be recreated before `MainActivity`; allowing start would let the watchdog bring Web back on a **compatible but outdated** runtime, before this APK's patches and system plugins are in place.

Therefore, with "old tree + new APK":

- on host recreation or a watchdog restart, Web is not allowed to start and the Main Web area is blank;
- this is not a WebView failure, a network failure, or a general plugin crash: **do not** touch WebView, clear caches, reinstall the APK, or weaken the admission gate;
- if the old proot Web process is still alive and the page has already loaded the old tree, interface evidence is UNKNOWN; combined with DSH 0.2.2 no longer rendering the first-connection indicator, the old plugin dependency fails and the user also sees a blank content area and a duplicated sidebar (next section). [The blank-screen investigation](../releases/v0.2.1-rc.5-build324-blank-screen-20261105.md) preserves the uncaptured direct JS stack and follow-up evidence steps; [the build325 record](../releases/v0.2.1-rc.5-build325-mobile-gear-fit-20261105.md) records the device control result.

### 5. Stop before maintenance: the maintenance action does the full stop

After the user taps "Update runtime", the maintenance task must complete the stop itself before taking the barrier; the stop is no longer handed back to the user:

- **Entry**: the confirmation button in [`RuntimeMaintenanceStarter.showRetryDialog`](../../app/src/main/java/com/deepseekharness/app/ui/RuntimeMaintenanceStarter.java) calls `BackupTask.updateEnvironment(true)` / `rebuild()` / `recoverMaintenance()`. Only after the user taps "Continue" in the confirmation dialog does the task carry `allowForceStop=true`.
- **Stop order**: `MaintenanceCoordinator.exclusiveUserConfirmed` → close terminals → [`MaintenancePreStop`](../../app/src/main/java/com/deepseekharness/app/core/MaintenancePreStop.java):
  1. request the ordinary stop (the stop queue writes the sentinel first, then verifies the PID record and birth identity; only the same verified process is signalled);
  2. wait for the existing exit proof from `WebProcessManager` / `WebProcessSession` (guest **and** launcher);
  3. only if the ordinary stop is unconfirmed, call `HarnessController.prepareForceStop()` to prepare an **identity-verified** candidate (PID, birth time, command line, UID, generation) and `forceStop` the same guest;
  4. if the guest still cannot be confirmed exited, fail with a message that states the original environment was not changed and asks the user to tap "Retry runtime update".
- **Residual process after an over-the-top install**: the app may have been killed while the guest kept running, and `/root/.dsha-web.pid` / `.dsha-web.identity` may be missing. The session record already persists the birth identity (`StoredWebSession.Record.proof`: boot, generation, instance, nonce, guest and launcher PID + birth time + command line). [`WebProofStop`](../../app/src/main/java/com/deepseekharness/app/util/WebProofStop.java) uses it as a fallback: it signals only after two full identity checks agree, SIGTERM first, and (when force is allowed) a second verification before SIGKILL. PID reuse, changed birth time/command line, or an unreadable record means **no signal at all**. The container launcher is never signalled.
- **Automatic paths never force**: auto-start after an overwrite and post-upgrade cleanup still call `MaintenanceCoordinator.exclusive` without `allowForceStop`; the unconfirmed old Web only blocks auto-start (I4), and only the manual tap may force.
- **Trial teardown (T91)**: the proroot launcher may exit late or linger after the trial guest has exited (observed on Android 16). `WebProcessManager.confirmTrackedTrialStopped` therefore confirms only the guest's exit evidence (retired PID record + no owned Web) and no longer treats a live launcher object as a failed trial; `RuntimeTrial` makes one bounded cleanup attempt on the exact `Process` handle it owns (timeout / failure only records a `TRIAL_LAUNCHER_RETENTION` diagnostic and does not change the guest-exit conclusion). When `TrialRecovery` finds a `launched` record without `.dsha-web.pid` / `.stale`, it now requires the platform to confirm that the trial profile derived from that record id has **no process** (same UID, two matching birth identities; still present / unreadable / identity change stays fail-closed) instead of unconditionally retaining `TRIAL_NO_PID_OR_BOUND_SESSION_EXIT_EVIDENCE`.
- **Half-committed recovery and the maintenance page exit (T91)**: a trial failure can leave a `files-committed / settings-committed` transaction that only `EnvironmentMaintenance.recover()` can roll back. Recovery failures are now also written to the task state and the exportable operation record (`MAINTENANCE_TASK_START / MAINTENANCE_TASK_FAILED`) so the page can show them. Once recovery has completed externally, the page drops a stale failed snapshot whenever the environment is runnable and no maintenance is busy / pending (`RuntimeUpgradeState.clearStaleFailure`), returning to the standard "previous runtime still needs updating" state with an update CTA instead of keeping `TRIAL_PROCESS_UNCONFIRMED` in the primary slot. Emergency DSH, the maintenance record, the readable-copy export, and the limited Main entry stay clickable in every failure state; a progress bar alone is never a dead end.
- **The three-step detour is obsolete**: a failure message no longer tells the user to "stop in the limited Main first". The Main → Stop button remains the existing manual stop / force-stop entry, but it is no longer a mandatory step for finishing runtime maintenance.

## Built-in plugins must not depend on host UI

### Incident: the `dsh-web-mobile` sidebar gear

- Upstream's `drawer-quick-settings-chip-guard` (from 3.2.2, still present in 3.3.1) used `:has(> button[data-phase], > [role="status"])` to decide whether the footer gear must yield. That made plugin rendering **depend on a host child node existing**.
- build324's managed patch `DSHA_FIRST_CONNECT_SILENT_V1` stops rendering `ConnectionIndicator` on first entry. The `:has()` guard then always concluded "fits", nothing measured the row's real width, the plugin render chain broke, and the user saw a duplicated sidebar footer; disabling the built-in plugin restored normal rendering.
- Fix (T61, `DSHA_GEAR_FIT_V1`):
  - export a pure predicate `dshaGearFits(rowWidth, usedWidth)`: the gear is shown only when `row content width − other visible children width ≥ 40px`; unusable layout (width ≤ 0 or non-finite) means "does not fit", conservatively keeping the account-menu entry;
  - write the conclusion idempotently into the plugin's own `data-mobile-nav-quick-fit` attribute; CSS reads only that attribute;
  - the account-menu settings entry and gear visibility share the same conclusion, so exactly one settings entry exists at any time;
  - the product lives in [`app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js`](../../app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js); the patch is maintained by [`tools/apply-mobile-client-patches.mjs`](../../tools/apply-mobile-client-patches.mjs).
- Contract test: [`tools/test-mobile-gear-contract.mjs`](../../tools/test-mobile-gear-contract.mjs) (T61, merged with build325). Using the real product plus a minimal DOM it covers: gear injection without an indicator, yielding/restoring by measured width as the indicator appears/disappears, conservative yield in a narrow row, and idempotent repeated `ensure`.

### Rules for plugin and host UI changes

1. **Never** use host node existence — class names, `data-phase`, `role="status"`, `:has()` — to decide whether the plugin renders. One removed host child can break the whole chain.
2. To sense layout, measure only `getBoundingClientRect()` / `getComputedStyle()` geometry and write the result into a **plugin-owned** attribute (for example `data-mobile-nav-quick-fit`).
3. When sizes are unreadable or layout is not ready, choose **conservative degradation** (drop one entry) instead of "assume it exists and render".
4. Every such dependency needs a Node-runnable contract test against the real product plus a minimal DOM, not just a hand-written function test.
5. Host UI changes (adding/removing the connection indicator, update bar, and so on) must trigger a review of every built-in plugin patch's CSS selectors and DOM assumptions.
6. **`dsh-web-mobile` is default-enabled again from build336**: upstream 3.3.1 (4e71eb67) is adapted to host 0.2.2; plugin bytes, lock and patch selection are on the [plugins page](plugins.md) and in the [build336 record](releases/v0.2.1-rc.5-build336-web-mobile-3.3.1-20261106.md), while the build327 default-disable and incident cause stay in the build327 record. The restore cites the original gate: the build325 gear contract and the build324 blank-screen path regressions are green, and `generate-builtin-plugins` / `prepare-runtime-descriptor` plus the pre-release gates were rerun; **device over-the-top acceptance (no blank screen, no duplicated sidebar, no duplicated footer settings entry, default-enabled after upgrade) must still be appended before build336 is released** — CI or unit tests alone do not prove device success.

## Pre-release checklist

After changing managed patches, built-in plugins, managed inputs under `app/src/main/assets/**`, or Java related to upgrades/maintenance, at minimum run the following from the repository root:

```bash
# 1. Recompute the managed runtime descriptor (required after managed patch / built-in plugin product / runtime input changes)
python3 tools/prepare-runtime-descriptor.py --write
python3 tools/verify-runtime-descriptor-source.py

# 2. Third-party notices (patch chain or built-in plugin digests change them; both 324 and 325 hit this)
python3 tools/generate-third-party-notices.py --check
python3 tools/check-third-party-notices.py
# If --check fails, run the generator without --check to update THIRD_PARTY_NOTICES.md, then rerun both

# 3. Whole contracts group (same as .github/workflows/_node-contracts.yml)
python3 tools/prepare-runtime-tools.py --check
python3 tools/verify-runtime-descriptor-source.py
python3 tools/generate-third-party-notices.py --check
python3 tools/check-third-party-notices.py
python3 tools/generate-builtin-plugins.py --verify
python3 tools/generate-credential-paths.py --check
python3 tools/prepare-semver-reference.py
python3 tools/run-host-tests.py --check-manifest
python3 tools/check-source-hygiene.py
python3 tools/check-ui-i18n.py
python3 tools/generate-changelog.py --check
python3 tools/check-document-links.py

# 4. Direct regressions for this incident
node tools/test-mobile-gear-contract.mjs          # built-in mobile-shell gear contract (T61 / build325)
dsha-gradle :app:testStandardDebugUnitTest --tests '*OverwriteUpgradeGateTest'
dsha-gradle :app:testStandardDebugUnitTest --tests '*RuntimeUpgradeStateTest'
dsha-gradle :app:testStandardDebugUnitTest --tests '*MaintenancePreStopTest'   # build327: stop before maintenance
dsha-gradle :app:testStandardDebugUnitTest --tests '*WebProofStopTest'         # build327: residual-process identity
```

Additional rules:

- **Fixed inputs**: if one of the twelve fixed inputs changes (`offline-rootfs.bin`, `dsh-runtime.bin`, `ubuntu-tools.bin`, and so on), repack/upload the prerelease asset, update `ci/sunset-inputs.lock.json`, and sync related locks/docs as described in [fixed build inputs](../fixed-build-inputs.md). APK-only assets (such as the built-in plugin `client.js`) do not require repacking the fixed-input bundle, but still require recomputing the runtime descriptor and third-party notices.
- **A UI-only APK** must not rebuild Ubuntu and must not make the maintenance page suggest a rebuild; only managed input changes enter patch-level maintenance.
- **External PR / external merge**: a dedicated AI review session must first read the entire diff line by line and give APPROVE / REQUEST_CHANGES under [the external PR and external merge review process](external-pr-review.md); green CI and a resolved conflict do not make it mergeable, and the review session neither merges nor pushes the author's branch.
- Before reporting "mergeable / releasable", list the commands actually run and their results. If device acceptance was not performed, say so; never present host tests as device conclusions.

## Device acceptance (over-the-top upgrade)

Release acceptance must perform one real "**previous build → this build**" overwrite and record it in the version receipt:

1. **Prepare the predecessor**: install the previous released build (same package, same signing, non-debuggable) on a dedicated acceptance device, finish onboarding, start Web once, and confirm a healthy environment; record the version, `runtimeId`, and connection state.
2. **Overwrite with the old Web alive**: without stopping DSH/Web, install the new build over it (in-app update or same-signature APK). Then check:
   - the **full-screen maintenance page** appears (not Main, not a blank Web);
   - it states that the installed runtime still belongs to the previous build / managed patches are not in place;
   - "Update runtime / Retry" is the primary action and clickable;
   - if auto-start is blocked by an unresolved old Web session, the page still offers an explicit manual entry (this is correct behavior, not a failure);
   - after tapping "Update runtime", the maintenance task stops the old Web **by itself** and continues, instead of requiring the user to enter the limited Main and press Stop.
3. **Complete maintenance**: tap update on the maintenance page; **you do not need to enter the limited Main and press Stop first**. Confirm that the maintenance task stops the old Web itself (escalating once to the identity-verified force stop when the ordinary stop is unconfirmed), then prepares the candidate, runs the trial, and commits. Then verify Web starts, plugins load, and the sidebar has exactly one settings entry. Use diagnostics/environment info to confirm `runtimeId` now matches the current APK candidate. If the stop stage fails, confirm the page shows the reason plus "Retry runtime update", and that the original environment was not changed.
4. **Cold overwrite**: normally stop DSH and terminals, overwrite the same pair, and confirm maintenance starts automatically once, then Main opens with a working Web.
5. **Plugin regression**: inspect the sidebar footer both when no connection indicator is shown on first entry and after the indicator appears: exactly one gear, no overlap with the account button, and the account menu still opens settings.
6. **UI-only counter-check**: a UI-only build with an unchanged descriptor must not trigger an environment rebuild or maintenance.
7. **Receipt**: record "previous → this build, install method, whether the old Web survived, which page appeared, whether auto-start was blocked, whether the manual tap succeeded, Web/plugin results, and the before/after `runtimeId`" in the matching record under `docs/releases/`. Mark uncovered devices, ROMs, and external-model matrices honestly. Device screenshots and logs do not enter the repository.

> Do **not** overwrite-install a production build on the shared build machine that hosts DSH; device acceptance happens on a dedicated device.

## Counterexample: what actually happened from 323 to 324

Context: build323 and build324 bundled the same dsh version `0.2.2-rc.1`; 324 added two managed patches (`DSHA_FIRST_CONNECT_SILENT_V1`, `DSHA_USER_ONLY_SKILL_APPROVAL_V1`). The patch chain was fine: 324's `dsh-runtime.bin` already contained both markers at build time, and `verify-runtime-descriptor-source` agreed. The user's impression that "patches were not updated" came from "**the environment was never updated at all**". For the full patch-chain evidence see [the build324 blank-screen investigation](../releases/v0.2.1-rc.5-build324-blank-screen-20261105.md) and [the build325 gear-fit record](../releases/v0.2.1-rc.5-build325-mobile-gear-fit-20261105.md).

Device control (2026-11-05): after installing the test package built from the same main content plus the T61 gear fix, the **white screen disappeared and environment rebuild was triggered normally**; the other device items for that build were still awaiting user confirmation. T60's display-layer and entry fixes merge with build326 and are **not yet device-verified** — do not skip build326's overwrite acceptance just because build325 passed on device.

| Layer | Behavior at the time | What the user saw |
|---|---|---|
| Decision | `compatible()` true (same dsh / same base contract), `latest()` false; `requiresMaintenanceBeforeStart` / `shouldBlockRuntimeStart` correctly returned "maintenance required" | Correct decision, but no usable UI |
| Display | Maintenance page treated `isEnvironmentReady()` as "nothing to do", promoted "Enter Main", demoted update, recorded no limited-entry identity, and the Main banner had no route back | "Skip maintenance and enter Main" sat on the primary action; maintenance never started; entering Main bounced back |
| Auto-start | Old proot Web alive → `hasUnresolvedWebSession()` true → `shouldAutoStart` false | "Maintenance did not start automatically", with no obvious manual entry |
| Web admission | `!isEnvironmentReady() || requiresMaintenanceBeforeStart` → `REJECTED` | Blank Web area; with the old Web still alive plus the broken plugin `:has()` dependency, a duplicated sidebar appeared; disabling the built-in plugin restored it |

The fix has four parts, all required:

1. **Display layer (T60 / build326)**: `RuntimeUpgradeState` separates "can keep running" from "is current candidate"; maintenance `ready` also requires `runtimeLatest`; `limitedEntry` includes unfinished patch-level updates; the Main banner keeps a route back to maintenance; the maintenance page explains that managed patches are not in place.
2. **Entry (T60 / build326)**: an unresolved old Web session only blocks auto-start and no longer prevents opening the maintenance page, so the only repair entry is always reachable.
3. **Plugin dependency (T61 / build325)**: gear yielding is based on measured width plus a plugin-owned attribute, no longer on the host indicator existing.
4. **Stop before maintenance (T67 / build327)**: after the manual tap the maintenance action performs the full stop itself; an unconfirmed ordinary stop escalates once to the identity-verified force stop, a failure keeps the original environment and offers a retry, and the user no longer needs to detour through the limited Main (I9).

Common misdiagnoses:

- blaming `requiresMaintenanceBeforeStart` / `shouldBlockRuntimeStart` → **wrong**, both paths decided correctly;
- blaming the patch not being baked into `dsh-runtime.bin` → **wrong**, both markers were present at build time;
- blaming WebView / network / a general plugin crash → **wrong**: Web may never have been allowed to start, or, if the old page loaded, the visible trigger was the plugin's dependency on host-widget existence;
- using "disable the built-in plugin" as a long-term fix → only hides the plugin's dependency on host-widget existence;
- weakening the Web admission gate to remove the white screen → lets the watchdog start Web on an outdated runtime, a more severe regression.

## Troubleshooting quick reference

| Symptom | Check first | Do not |
|---|---|---|
| Main opens after an overwrite and Web is blank | whether the maintenance page opened; `EnvironmentAccess.requiresMaintenanceBeforeStart`; `RuntimeDescriptor.latest(installed, expected)` | touch WebView, clear caches, reinstall the APK, or weaken the admission gate |
| Maintenance page shows "Enter Main" as primary and update as secondary | whether the `ready` passed in uses only `isEnvironmentReady()`; whether `RuntimeUpgradeState.upToDate` includes `latest` | change only button text or styling; the inputs are wrong |
| Main from maintenance bounces back | whether `limitedEntry` recorded a limited identity; whether patch-level incompleteness is part of the condition | disable the Main gate; that only hides the problem |
| "Maintenance did not start automatically" | `hasUnresolvedWebSession()`; whether the previous task FAILED/INTERRUPTED; whether this candidate was already attempted | replay an unknown old instance automatically; offer an explicit manual start |
| Maintenance fails saying Web did not stop / user is forced to stop in the Main first | whether the maintenance action goes through `MaintenanceCoordinator.exclusiveUserConfirmed`; whether `MaintenancePreStop` escalated; the stop error code and `WebProofStop` verification result | treat "Main → Stop" as a mandatory maintenance step; weaken identity checks or kill by port/name |
| Duplicated sidebar footer / gear covering the avatar | whether the plugin still uses host `:has(button[data-phase], [role="status"])`; whether `data-mobile-nav-quick-fit` is written | treat "disable the built-in plugin" as the fix |
| "The patch was not updated" | first compare whether `runtimeId` / `inputs` equal the current candidate; then check `dsh-runtime.bin` markers and `verify-runtime-descriptor-source` | suspect the patch chain first |

## Regression entries and related documents

| Item | Entry |
|---|---|
| Upgrade gate (322→323, 323→324, synthetic patch-level predecessor, UI-only, unresolved old Web) | `app/src/test/java/com/deepseekharness/app/backup/OverwriteUpgradeGateTest.java` (T60 / build326) |
| `RuntimeUpgradeState` pure logic | `app/src/test/java/com/deepseekharness/app/util/RuntimeUpgradeStateTest.java` (T60 / build326) |
| Stop-before-maintenance and force escalation order | `app/src/test/java/com/deepseekharness/app/core/MaintenancePreStopTest.java` (T67 / build327) |
| Overwrite retire identity checks and trial teardown | `app/src/test/java/com/deepseekharness/app/runtime/WebOverwriteRetireTest.java` (T91), `TrialRecoveryTest` (teardown without PID evidence), `ManagedRuntimeTransactionTest` (half-commit rollback) |
| Maintenance-page stale failure clearing | `app/src/test/java/com/deepseekharness/app/util/RuntimeUpgradeStateTest.java` (T91) |
| Residual Web recovered by birth identity (PID reuse / missing record never signals) | `app/src/test/java/com/deepseekharness/app/util/WebProofStopTest.java` (T67 / build327), `WebSessionProofTest` |
| Stop algorithm and force-stop identity gate | `app/src/test/java/com/deepseekharness/app/runtime/WebRecordedStopTest.java`, `app/src/test/java/com/deepseekharness/app/util/WebForceStopTest.java`, `app/src/test/java/com/deepseekharness/app/core/WebForceStopGateTest.java` |
| Mobile-shell gear contract | [`tools/test-mobile-gear-contract.mjs`](../../tools/test-mobile-gear-contract.mjs) (T61 / build325) |
| Maintenance transaction and stop barriers | [`MaintenanceGateTest`](../../app/src/test/java/com/deepseekharness/app/util/MaintenanceGateTest.java) |
| Maintenance page implementation | [`ExtractActivity`](../../app/src/main/java/com/deepseekharness/app/ui/ExtractActivity.java), [`MaintenanceFooterPolicy`](../../app/src/main/java/com/deepseekharness/app/util/MaintenanceFooterPolicy.java) |
| Web admission and old-session recovery | [`WebLifecycleController`](../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java), [startup and diagnostics](startup-and-diagnostics.md) |
| Managed update transactions and trials | [`EnvironmentMaintenance`](../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java), [installation and maintenance](install-and-maintenance.md) |
| Application updates and signing | [application updates](update.md), [download verification](download-verification.md) |
| Fixed inputs and descriptor | [fixed build inputs](../fixed-build-inputs.md), [runtime, data, and plugin architecture](runtime-data-plugin-architecture.md) |
| Mobile Web patch notes (Chinese) | [mobile Web adaptation patches](../web-mobile-patches.md) |
| build324 first-connection record | [build324 record](../releases/v0.2.1-rc.5-build324-first-connect-indicator-20261105.md) |
| build323 upgrade record | [build323 record](../releases/v0.2.1-rc.5-build323-dsh022-s1-connection-display-20261105.md) |
| build324 blank-screen investigation | [build324 blank-screen record](../releases/v0.2.1-rc.5-build324-blank-screen-20261105.md) |
| build325 gear-fit record | [build325 mobile gear record](../releases/v0.2.1-rc.5-build325-mobile-gear-fit-20261105.md) |
| build326 overwrite-maintenance record | [build326 record](../releases/v0.2.1-rc.5-build326-overwrite-maintenance-20261105.md) |
| build327 stop-before-maintenance record | [build327 record](../releases/v0.2.1-rc.5-build327-maintenance-self-stop-20261105.md) |
| External PR / external merge review | [external PR and external merge review process](external-pr-review.md) |
| Whole contracts workflow | [`_node-contracts.yml`](../../.github/workflows/_node-contracts.yml) |
