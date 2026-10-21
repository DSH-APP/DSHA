# Installation, component checks, and environment maintenance

> Checked on 2026-10-21 against build190/F12 working-tree behavior. F10 `087724c` and F11 retain their historical reference attribution. This page update ran no builds, software tests, device actions, or endurance acceptance. [README](../../README.en.md) and the [F12 record](releases/v0.2.1-rc.2-build190-fix12-20261021.md) define final identity and formal execution counts. Historical results are not transferred to this work.

Installation answers whether the offline environment can run, component checks answer whether a particular tool responds, and environment maintenance answers whether managed files or the environment need transactional repair. Their success criteria differ. See [runtime, data, and plugins](runtime-data-plugin-architecture.md) for the entry-point overview and [startup and diagnostics](startup-and-diagnostics.md) for states after installation.

## First preparation

A first-time user goes from the welcome screen to preparation, where the complete Ubuntu and DSH assets in the APK are extracted and verified. The Ubuntu base identity is base10 and DSH is `0.2.1-rc.2`. A source checkout without the fixed offline archives cannot be delivered as a complete release installation package. The default engine is proot; proroot is an explicit option. See [runtime engines](runtime-engines.md).

A genuinely empty first environment uses a cold-install transaction: prepare the environment, verify it by running it, and confirm the actual runtime mode and environment identity. The component page does not automatically erase or re-extract an existing environment merely because Bash or a marker is damaged. On completion, Main appears first, then submits one generation-fenced background Web startup request. Models and API keys are configured in Web settings. [EnvironmentMaintenance](../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java) and [FirstRunWebStart](../../app/src/main/java/com/deepseekharness/app/ui/FirstRunWebStart.java) are the respective entry points.

## The six-step component page

In “Installation and environment,” “Check all components” first performs read-only probes. “Repair as needed” writes only selected items that failed their probe and are currently eligible for repair, then probes them again. Individual and complete checks share [InstallPipeline](../../app/src/main/java/com/deepseekharness/app/runtime/InstallPipeline.java) and [InstallProbe](../../app/src/main/java/com/deepseekharness/app/util/InstallProbe.java).

| Step | What is checked | Repair boundary |
|---|---|---|
| 1 Linux environment | Environment identity, extraction marker, and actual Bash/loader readiness | Extract only a confirmed empty first-install directory with an offline bundle; retain existing files |
| 2 Basic tools | curl, git, Python, and ssl/sqlite3/readline/tarfile/zipfile | Restore failed offline tools, then verify them again |
| 3 Node.js | Exit status from an actual `node --version` | This page does not overwrite user-upgraded Node; it directs the user to data-preserving environment maintenance |
| 4 pnpm | An actual `pnpm --version` | Check and restore bundled pnpm, then verify again |
| 5 deepseek-harness | Executable entry point and a valid version in the managed package | This page does not directly overwrite user-upgraded DSH; back up first and review the maintenance plan |
| 6 Runtime patches | DNS, session-write compatibility, LAN settings, and Android groups | Repair only failed patches/groups and recheck; a version string alone does not prove every item passed |

Each check needs an exit status for a known key from this invocation. Missing, duplicate, malformed, or prematurely terminated probe results are not successes. If Linux is not ready, the other components are explicitly unverified. A pass proves only the listed probes, not every plugin, network service, model request, or Android capability.

| Action | When it takes effect | Cancellation and failure |
|---|---|---|
| Check all / one item | Runs in the background after obtaining the environment-task ticket | A competing task or pending maintenance rejects it; no false running state is published first |
| Repair as needed | After the user clicks, writes only failed items | Later unstarted repairs do not run; completed repairs remain |
| Stop checking | Requests probe-process termination, or waits for safe completion of a write stage | Destroying the page does not release the task lock; unconfirmed exit keeps reclamation active and blocks new environment tasks |
| Leave the page / rotate | Changes UI observers only | The repository owns the task and results within the app process; resumption after system process death is not guaranteed |

[InstallRepository](../../app/src/main/java/com/deepseekharness/app/core/InstallRepository.java) owns task lifetime; [InstallTask](../../app/src/main/java/com/deepseekharness/app/util/InstallTask.java) maintains thread-safe snapshots and cancellation. Check logs retain a recent budget of about 48 Ki characters. Multiline private-key blocks are hidden; a line longer than 4096 characters is hidden in full. Control sequences are removed and common credential forms are redacted. This is not a complete audit trail and cannot guarantee recognition of every secret format.

Component probe time limits still come from `InstallPipeline/InstallProcess` according to the selected checks. Plugin lists and short enable/disable scripts separately use [GuestScriptBudget](../../app/src/main/java/com/deepseekharness/app/runtime/GuestScriptBudget.java)'s 30-second cooperative total budget across queueing, preparation checkpoints, and execution. It is not a hard limit for all installation, diagnostics, or maintenance and cannot preempt an individual system I/O, monitor, or Binder wait. No timeout replaces guest and launcher exit proof. Unknown exit still retains task locks, journals, and the maintenance barrier.

## Plans, updates, and data-preserving rebuilds

“Protect data and repair runtime,” “Review update plan,” and similar entry points first show actual differences and recovery state. When the Ubuntu base matches and managed update is available, prepare an APK-managed candidate first; not every UI update rebuilds Ubuntu. [ExtractActivity](../../app/src/main/java/com/deepseekharness/app/ui/ExtractActivity.java) controls the page flow, while [EnvironmentMaintenance](../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java) enforces maintenance order.

Commit uses [MaintenanceCoordinator](../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java) and [MaintenanceGate](../../app/src/main/java/com/deepseekharness/app/util/MaintenanceGate.java): obtain environment execution ownership, stop Web and terminals normally, confirm running tasks and managed processes have exited, then switch data or runtime. An unconfirmed guest or launcher exit retains the original environment and logs. A timeout, cancellation, or UNKNOWN must not be interpreted as “nothing executed.”

[ManagedRuntimeTransaction](../../app/src/main/java/com/deepseekharness/app/backup/ManagedRuntimeTransaction.java) retains the predecessor and commits managed updates. [RuntimeTrial](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java) checks real candidate startup, port/authentication, storage/session behavior, browser handshake, and exit. A compatibility rollback tries the predecessor with the current APK launcher rather than restoring old conversation snapshots. Failed updates retain the original runtime and personal data. Failed rollback must retain the scene too; deleting logs cannot release the barrier.

A data-preserving environment rebuild has its own data snapshot and old/new-tree transaction; it is not clearing data. Maintenance still checks environment readiness after reporting completion. If readiness fails, remain on the native page to review the cause or explicitly enter a limited interface, without an automatic navigation loop. Rotation of verified older runtimes must recheck each root's digest and members. Modified, unreadable, extra-file, or unknown originals are retained.

Complete formatting, destructive reinstallation, and erasure backends are retired from production. External-workspace maintenance may only disconnect private application references; it must not traverse, delete, overwrite, or rename external files. Old recovery logs must not replay external writes/deletions either. Legitimate private temporary cleanup still requires a controlled target and NOFOLLOW checks, without inheriting arbitrary erasure from old formatting code.

## Handling interruption

1. Keep the error and scene. Inspect the transaction type through “Recover interrupted maintenance.” Do not delete pending files, force-kill a bare PID, or uninstall to evade the gate.
2. The recovery entry distinguishes cold installation, isolated trials, plugin transactions, environment rebuilds, configuration transactions, managed runtimes, host data, and old maintenance journals. It checks uniqueness and process exit before applying the owning contract.
3. Multiple recoverable transactions, changed object identity, or live tasks cause a stop and explicit report; originals remain. Run the appropriate component/readiness checks after recovery.
4. When Web cannot start, host backups, read-only rescue, and [independent emergency DSH](emergency-recovery.md) remain available. Ordinary recovery should not require damaged guest tools to run first.

## Change and validation map

| Change | Related test entry points |
|---|---|
| Six-step read-only checks, selective repair, and revalidation | [InstallPipelineTest](../../app/src/test/java/com/deepseekharness/app/runtime/InstallPipelineTest.java) |
| Task ownership, contention, cancellation, and cleanup | [InstallRepositoryTest](../../app/src/test/java/com/deepseekharness/app/core/InstallRepositoryTest.java), [InstallTaskTest](../../app/src/test/java/com/deepseekharness/app/util/InstallTaskTest.java) |
| Stop order and maintenance fencing | [MaintenanceGateTest](../../app/src/test/java/com/deepseekharness/app/util/MaintenanceGateTest.java) |
| The single post-setup startup request | [FirstRunWebStartTest](../../app/src/test/java/com/deepseekharness/app/ui/FirstRunWebStartTest.java) |

These links guide regression selection; this page did not rerun the tests. Bind both variants' software results, the actual APK, signatures, and phone evidence to their exact versions and digests separately. Tool checks do not establish a complete device matrix or item-by-item data comparison.
