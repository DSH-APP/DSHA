# Issues, pull requests, and evidence flow

Verification date: 2026-10-21. Source: the corresponding Chinese issue-workflow guide, using published F10 `087724c` as a reference baseline. This workflow was first documented in F11. It does not recover an old workflow original or prove that repository permissions, labels, or branch-protection rules are configured.

## Begin with current authorization and actual state

Confirm the repository, branch, open items, and objective specified by the user. Then read each issue/PR's current content, commits, and discussion. Current maintenance handles only still-open items within the authorized scope. Closed items are not pursued again, and plugin-directory submissions are skipped. Do not turn historical item states, another repository's requests, or an old conversation into a new task.

[README](../../../README.en.md) distinguishes the current private prerelease from the historical original repository. A repository name is not write authorization. Use an existing GitHub connector or `gh` for reading when available. Read-only inspection, code changes, posting comments, opening PRs, and publishing are different actions. Sending an external message requires the appropriate authorization. If access is unavailable, retain locally reviewable materials; do not upload credentials or raw device logs to fill an evidence gap.

## What an issue needs to retain

1. A user-observable trigger and expected result, source/APK/platform identity, minimal reproduction, and actual outcome.
2. The real production call chain, failure layer, and data/execution state. UNKNOWN, timeout, and cancellation must not be rewritten as not executed.
3. Change scope, regression cases, and unverified scope. Separate a documentation error, implementation defect, design choice, and missing validation.
4. The relationship between commit/PR, test receipts, and artifact digests. Historical evidence keeps its original time attribution.

An issue is an entry point for requirements, reproductions, and dependency discussion. A PR is an entry point for a concrete change and review. Reported testing or repair clues in a PR can be evidence, but unmerged code is not current production behavior. Set native sub-issue or dependency relationships only when the platform actually supports them, the user has authorized the action, and the target is clear. Do not write prose that pretends an unsupported native relationship is active.

## Completion and handoff

Describe the final behavior, changed files, executed checks, and limitations before updating status within authorization. Passing code checks does not establish device validation or an uploaded Release. A local branch name, prepared manifest, or draft PR does not prove the user can download anything. If publication is not currently authorized, deliver a concrete local result for review.

See [triage labels](triage-labels.md) for common decision terms, [handoff domains](domain.md) for ownership boundaries, and the [documentation gap matrix](../documentation-gap-matrix.md) for cross-version documentation coverage.
