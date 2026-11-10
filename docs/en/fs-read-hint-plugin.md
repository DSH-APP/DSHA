# Built-in fix plugin dsh-fs-read-hint (read hint, temporary)

> Status: temporary fix shipped as a DSHA signed built-in; remove once upstream fixes it or the plugin stops working.
> Upstream tracking: [deepseek-harness discussions #11977](https://github.com/deepseek-ai/deepseek-harness/discussions/11977).
> DSH baseline: 0.2.1-rc.5 (`dsh-tool-fs`, `dsh-fs-observation-policy`, `dsh-tool-str-replace-editor`).

## 1. Problem

DSH mounts `fs-observation-policy` by default: `edit`, an overwriting `write` and `str_replace_editor` are refused with `FS_NOT_OBSERVED` unless the same session read **the same path** with the `read` tool:

```text
cannot modify "<path>": file has not been read — read the file, then retry
```

Sessions in this repository work in separate worktrees under `.agents/worktrees/<type>/<name>/`, which share many file names with the main checkout. Models often read the main checkout and edit the worktree copy. The error names only the target, so the model cannot tell it read another copy. AGENTS.md already states the rule, but only agents that read it follow it.

## 2. Behavior

The [plugin source](../../app/src/main/assets/builtin-plugins/dsh-fs-read-hint/lib/index.js) does one thing:

- listens to `fs/observed` and remembers, per session, the paths observed by `read` (and successful mutations), at most 4096 per session with oldest eviction;
- wraps `edit` / `write` / `str_replace_editor` in `tools/execute`: when the result is `FS_NOT_OBSERVED` and this session read a file with **the same name at a different path**, it appends up to two candidates (longest common path suffix first) to the error.

Unchanged: the guard still refuses and still requires reading the target itself; nothing is read, retried or allowed automatically; error codes are preserved and `FS_STALE_VERSION` is not rewritten; with no candidate, an unrecognized result shape, or the plugin disabled or removed, the error text is identical to upstream; sessions never see each other's paths.

## 3. Registration

Registered like `dsh-launch-target`: [builtin-plugins.json](../../app/src/main/assets/builtin-plugins.json) and `dsha-builtin.txt` (registry source; `BuiltinPluginRegistry` is generated), the signed name lists in `plugin-manager-policy-patch.json` and `auto-review-native-disabled-patch.json`, three install rows in `managed-runtime-inputs.json`. The signed list lives in a runtime-archive patch, so `dsh-runtime.bin` must be rebuilt from the lock and its digests synchronized.

## 4. Verification

[test-fs-read-hint.mjs](../../tools/test-fs-read-hint.mjs) composes the real rc.5 `ToolRuntime`, `dsh-fs-local`, `fs-observation-policy`, `dsh-tool-fs` and `str_replace_editor`, reading and writing only under a temporary `app/build` directory. It covers the upstream error-shape contract, hints for all three tools with the file unchanged, normal success after reading the right path, unchanged text without candidates, ranking and session isolation, no rewrite for `FS_STALE_VERSION` or unknown shapes, no double append, and the record cap.

## 5. Removal criteria

Remove the plugin, without leaving a stub, when either holds:

1. **No longer needed**: upstream ships an equivalent hint (in #11977 or a later release), or the observation policy becomes optional and DSHA stops mounting it;
2. **Broken by code changes**: after a DSH upgrade the upstream-contract case of `test-fs-read-hint.mjs` fails, or the plugin can no longer append the hint. The plugin passes results through unchanged in that case, but it should be removed rather than patched.

To remove: delete the plugin directory and its test, drop the registration rows listed above, regenerate `BuiltinPluginRegistry`, restore the built-in counts in the affected tests, rebuild the runtime archive from the lock and synchronize digests, and record the removal build and reason at the top of the Chinese page.
