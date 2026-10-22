# Application updates and managed runtime updates

> Checked on 2026-10-21 against actual build190 update code, using published F10 `087724c` as a reference baseline. This page did not inspect the live remote feed, install an APK, or perform device acceptance. Current final identity is documented by [README](../../README.en.md) and the [release introduction](release-introduction.md).

APK updates and managed-component updates inside Ubuntu are different chains. An APK passes download and package-identity checks before the system installer opens. Managed components require plan review, normal task shutdown, candidate preparation/trial, and transactional switching. Both retain specific failures; version text does not replace actual outcomes.

## Check for updates at startup

“Check for updates at startup” defaults to enabled in [ConfigStore](../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java). Main calls [UpdateEngine.checkOnStartup](../../app/src/main/java/com/deepseekharness/app/core/UpdateEngine.java). Each application process checks automatically at most once; rotation, theme recreation, and returning from Web do not repeat it. Disabling the option prevents this automatic check on subsequent startup entry; manual checks remain available. The option does not cancel an active request or automatically download/install an APK.

The check reads the fixed HTTPS feed `https://dsha.cc/api/updates.json`, requires `schemaVersion=1`, and limits the feed to 1 MiB. [UpdateLocations](../../app/src/main/java/com/deepseekharness/app/util/UpdateLocations.java) defines source policy. A private prerelease link in README does not automatically change the updater to that repository.

The feed request uses `DSHA/<VERSION_NAME>` as User-Agent, and the receiving service sees normal network-request information. This chain attaches no conversation, terminal command, or API key. It is not automatic diagnostic reporting. The live service's privacy policy, availability, and feed contents are outside this source review.

## Channels and candidates

| Option / action | When it takes effect | Rules and failure |
|---|---|---|
| Stable / preview channel | Switches while idle and checks immediately | Stable accepts stable only; preview accepts stable/preview. Select by larger versionCode, not rc filename ordering |
| Default channel | Initialized when no preference exists | A version name containing `-` defaults to preview; otherwise stable |
| Check again | User click, or first entry to an update page with no task | Network failure retains the channel's last result; an old candidate with unknown source cannot install directly |
| Download / resume | Starts an application-scoped foreground download after selection | Leaving/rotating does not restart it; cancel retains partial progress for a later resume request |
| Install update | A foreground page claims the fully reverified result once | Rejects channel/candidate changes during verification; process-recreation interruption requires a user retry |

[UpdatePolicy](../../app/src/main/java/com/deepseekharness/app/util/UpdatePolicy.java) also validates flavor, minSdk, `arm64-v8a`, versionCode, allowed HTTPS source, 64-character hexadecimal SHA-256, and size up to 1 GiB. A saved candidate's checked channel is separate from the release's own channel; current preference cannot establish its origin.

F10/F11 revisions that share code190 are not selected as a newer version even with a later version name: candidates require `versionCode > BuildConfig.VERSION_CODE`. Use [README](../../README.en.md), the [release introduction](release-introduction.md), the [historical F11 record](../releases/v0.2.1-rc.2-build190-fix11-20261021.md), and the actual Release for private publication, attachment digests, and manual downloads. Attachment status requires actual Release and independent external receipts. “No update” does not prove there are no remote prerelease attachments.

## Download, verification, and system installation

Allowed sources are dsha.cc download paths and GitHub Release APK paths under DSH-APP/DSHA. Redirects are checked at every hop, with restricted exceptions for GitHub asset CDNs. A domain allowlist does not trust every same-named package. [ResumableDownload](../../app/src/main/java/com/deepseekharness/app/util/ResumableDownload.java) checks actual bytes and SHA-256; installation checks them again, then verifies:

- The package matches the installed application and has `debuggable=false`.
- Actual versionCode matches the feed, is greater, and supports this Android version.
- versionName matches the current Standard/Low variant.
- The candidate's complete current signer set exactly matches the installed application, not just a subset.

Release delivery retains `dsh.android.client` and E7E3. The implementation compares the actual installed signer set; a shortened fingerprint in documentation does not authorize installation. A digest establishes byte equality, and a matching signature establishes continuity of installation identity. Neither proves plugin code harmless. See [download verification](download-verification.md) for manual APK checks.

On Android 8+, if this application lacks unknown-source installation permission, the page opens system authorization settings first and verifies after the user returns. On Android 13+, download first requests notification permission and continues requesting the download after the callback; notification permission is not installation permission. The system installer still requires user confirmation; installation is not silent.

**The maintained build170-based implementation dispatches a FileProvider URI with `ACTION_VIEW`, as it did through F10/F11.** [UpdateActivity.dispatchInstall](../../app/src/main/java/com/deepseekharness/app/ui/UpdateActivity.java) has neither historical build173 R09's PackageInstaller session fence nor a maintenance-stop call here. Before upgrading, normally stop DSH and terminals in the application and confirm completion. External ADB/file-manager upgrades likewise do not inherit managed-component stop proofs. Historical build173 installer acceptance cannot be transferred to this build170-based source.

Canceling system installation leaves the original application installed; opening the page does not prove replacement completed. Check actual version and startup state afterward. A signature mismatch is not a reason to uninstall the original: uninstall/clear-data can lose private data, local keys, and copies. Export important data using [backup and restore](backup-and-restore.md) first.

## Managed updates inside Ubuntu

“Review update plan” opens environment maintenance, compares installed and bundled components, then runs [EnvironmentMaintenance](../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java) after confirmation. Within the same Ubuntu base, only managed components update; personal data stays in place, and UI-only APK changes should not rebuild the environment directly. An unsuitable base requires a data-preserving rebuild plan.

Managed commit first uses [MaintenanceCoordinator](../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java) to stop Web/terminals normally and enforce the exit barrier. It prepares a candidate, records digests/predecessor, and runs [RuntimeTrial](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTrial.java), checking actual port/authentication, sessions/storage, browser handshake, and exit before commit. Failure retains originals. Unknown exit cannot switch, clean, or replay. A compatibility rollback tries the predecessor with the current launcher and does not roll back conversation snapshots. See [installation and maintenance](install-and-maintenance.md).

## Change and validation entry points

Selection/source/signature regressions start with [UpdatePolicyTest](../../app/src/test/java/com/deepseekharness/app/util/UpdatePolicyTest.java) and [UpdateRedirectsTest](../../app/src/test/java/com/deepseekharness/app/util/UpdateRedirectsTest.java); stop/transaction boundaries start with [MaintenanceGateTest](../../app/src/test/java/com/deepseekharness/app/util/MaintenanceGateTest.java). This page did not rerun these tests or claim revalidation of complete in-app install confirmation/cancellation, the remote feed, external replacement, or every Android version.
