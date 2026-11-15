# Built-in fix plugin dsh-fs-search-scope (search scope, temporary)

> Status: temporary fix shipped as a DSHA signed built-in; remove once upstream glob prunes nested worktrees itself.
> DSH baseline: 0.2.1-rc.5 and 0.2.2-rc.1 (`dsh-tool-fs-search`, `dsh-tools` with the DSHA required-policy patch `DSHA_REQUIRED_POLICY_V1`).
> Origin: task board T45.

## 1. Problem

DSH `glob` always runs `rg --files --no-ignore --hidden --sort=modified` and excludes only VCS directories such as `.git`. Sessions in this repository work in many worktrees under `.agents/worktrees/`. Globbing `*.java` from the repository root returns about 1,500 paths for the main checkout but more than 26,000 with the worktrees (measured locally on 2026-11-03; about 140,000 at the peak), and `--sort=modified` stats each one. `grep` does not pass `--no-ignore`, so `.gitignore` applies and it is unaffected.

## 2. Behaviour

The [plugin](../../app/src/main/assets/builtin-plugins/dsh-fs-search-scope/lib/index.js) takes over upstream `glob` executions in the host `tools/execute` waterfall and only adds exclude globs to the ripgrep argv:

- `.agents/worktrees` directories at any depth;
- linked worktrees (not the main worktree) that `git worktree list` reports inside the search root;
- no takeover when the search root itself is inside `.agents/worktrees`, so results match upstream exactly;
- one system-prompt line: glob skips nested worktrees; pass a worktree as `path` to search it.

Unchanged: hidden and ignored files are still listed; argument validation, ripgrep execution, result caps, rendering and spill reuse upstream exports and the registered glob definition. Before running, the plugin calls `verifyGlobalConsent` → `resolveExecution` (rc5) or `executable` (0.2.2) → `verifyDispatch` in the same order as the patched `dispatchToolBody`; any failure is returned as an error and nothing runs. If the policy module cannot load, the upstream shape is unknown, nothing needs pruning, the arguments are invalid, the root is not a directory, or the call is nested, it defers to `next()`. DSH itself is not modified.

Known limits: git submodules are not pruned; outside a git repository only the `.agents/worktrees` rule applies; ripgrep matches `--glob` against the traversal path with the cwd prefix removed, so each linked worktree is excluded in both cwd-relative and `**/<absolute>` form. Two ripgrep effects seen locally are unrelated to the plugin: occasional ENOENT gaps with absolute roots under multi-threaded traversal, and ENOENT for a symlinked root.

## 3. Disable and fallback

Disabling or removing `dsh-fs-search-scope` on the plugins page restores upstream glob exactly. The plugin adds one middleware and one prompt section; it changes no upstream row or preset.

## 4. Verification and removal

[test-fs-search-scope.mjs](../../tools/test-fs-search-scope.mjs) runs the real managed `ToolRuntime`, `dsh-subprocess-local` and `dsh-tool-fs-search` against a temporary git repository with linked worktrees. Remove the plugin when upstream glob prunes nested worktrees, or when the upstream-contract or policy-order cases fail after a DSH upgrade. Removal steps match [dsh-fs-read-hint](fs-read-hint-plugin.md); see the [Chinese page](../fs-search-scope-plugin.md) for details.
