# Current engineering standard

This standard adopts the parts of the external build150 audit that were checked against the current source and the user's requirements. The original material and its first review belong to the local build154 audit history; distribution boundaries are described in the [documentation index](index.md#reading-historical-material). Use the current item-by-item ledger linked by [README](../../README.en.md) for current dispositions. Decisions from another conversation attached to an audit do not directly become repository rules.

Start with the [current handoff guide](handoff.md), use the [module map](module-map.md) for ownership, consult [historical ADR 0001](adr/0001-build154-boundaries.md) for the origin of constraints, and follow [CONTRIBUTING](../../CONTRIBUTING.en.md) for builds and submissions. The [historical document inventory](../audits/build154/history-document-sources.json) records the original bytes of old guides and acceptance reports; their old commands must not be reintroduced into deployment.

## Layers and state ownership

The UI reads state, renders it, and forwards user actions. One production controller owns the Web generation, queue, and stop intent. Actual collaborators perform startup preparation, output observation, stop coordination, installation, and platform execution. Collaborators report through explicit, narrow ports; they must not create a second owner of production state.

Pure logic in `util` must not import Android. Platform file and process operations use the existing safe file layer and identity checks. Moving a class to another package does not prove that a dependency cycle has disappeared. The composition root may assemble platform and UI adapters; runtime code must not look up an Activity or the core maintenance authority directly.

Use Java 17; do not introduce Kotlin. Name new classes by their concrete responsibility, and prefer explicit imports, immutable values, and bounded collections. Preserve read compatibility for existing public component names, preference keys, protocol text, and on-disk formats. Review broad formatting, renaming, and protocol changes separately.

## Files, transactions, and processes

- For external or user paths, first check the owning root, object type, device, inode, and link boundaries. Only normalize application-directory aliases supplied by the Android framework; do not relax NOFOLLOW within guest subtrees.
- Atomic writes require exclusive temporary creation, complete writes and synchronization, publication, and directory synchronization. Reuse the verified file layer rather than building another record writer that truncates files.
- Signed runtime assets and user archives have different link semantics. Trusted Ubuntu must retain valid absolute guest links; user packages must not use links to overwrite external files. Test the two boundaries separately rather than weakening either for a single parser.
- Do not signal a bare PID, port, or process name. Stop, recovery, and commit depend on this invocation's birth identity, session, and exit evidence. Unknown results retain the scene and stop barrier.
- Do not perform network operations, IPC, or external callbacks while holding locks. Tasks need an explicit owner, cancellation entry point, and bounded waits. Do not replay writes or dependency lifecycle scripts when the outcome is unknown.
- Use stable IDs for state markers. Map old language labels only when reading historical data. Localize at the display boundary; never infer environment readiness from success text.

## Input, credentials, and protocols

Validate structured input at the boundary: version, type, size, and duplicate values. Retain and report unknown data instead of interpreting it as new local authorization. Apply appropriate strictness to trusted generated JSON and user input, migrating specific call sites gradually rather than replacing every parser mechanically.

Separate groups in `credential-paths.json` define credential paths, machine state, and session-trash exclusions. Restrictions on external reads and writes must not accidentally prevent deletion of project content explicitly selected by the user. Native API keys are excluded from exports by default. New user backups are password-free tar.gz files; internal automatic copies retain local protection, and historical ciphertext reads verify complete authentication.

Bridge credentials belong in request headers only. Existing legitimate Node fetch plugins receive a limited migration adapter for specified local GET requests; do not claim automatic compatibility for unknown curl or HTTP libraries. Do not forcibly rewrite installed source. Do not send credentials when they conflict or are ambiguous. HTTP protocol responses and actual commands or user content do not change with the UI language.

Plugins follow the current open policy: resolve dependencies, inspect the actual directory and digests, then commit and enable transactionally. Do not restore the former review gate. Keep user disabling, safe mode, load failures, dependency-author claims, and local verification distinct.

## Text and diagnostics

Use complete templates from the message catalog and Chinese/English resources. Do not concatenate fragments that cannot be translated. Preserve dynamic parameters, filenames, user content, pairing codes, and third-party descriptions. Existing APIs remain during migration; renaming functions alone does not create consistency.

Record only bounded application events and redacted diagnostics. Do not silently swallow real operation errors in empty catches. Best-effort cleanup may explicitly become a warning; cancellation and core data errors must not be downgraded. Logging must not truncate and overwrite a complete old history, and crash handlers must not recursively log their own failures.

## Sources of truth and generated output

| Fact | Authoritative source |
|---|---|
| Application version / filename | `app/build.gradle` |
| DSH npm tree | `tools/dsh-runtime/package.json` and its lockfile |
| pnpm / CA assets | `tools/runtime-tools.lock.json` |
| Signed built-in plugins | `assets/builtin-plugins.json` |
| Credential / machine-state groups | `assets/credential-paths.json` |
| Active / retired patches | `assets/runtime-patches.json` |
| Independent emergency runtime | `tools/recovery-runtime/lock.json` |
| Release signing identity | `ci/release-identity.json` |
| Actual generator inputs | `managed-runtime-inputs.json` and the runtime descriptor |
| Host-test entry points | `tools/host-tests.manifest.json` |
| APK deployment members and dynamic-read contract | `tools/asset-deployment.json` |

Text uses actual LF bytes; do not replace line endings in binaries. Generators use deterministic inputs and must not embed host paths or temporary timestamps. Regenerate and verify after changing an input; descriptors must not ignore real byte changes.

`tools/history` preserves exact original bytes of retired sources and documents, exempt from newline rewriting and automatic deployment/execution. Explicit deployment inventories and structured read contracts retain licenses, complete built-in plugin trees, and extension members. Converting a source archive to physical packaged members requires an explicit mapping; do not remove resources with broad regular expressions. `UnusedResources` is a build error. First inspect actual references and Lint findings; R8 or resource shrinking must not conceal lifecycle, reflection, or native-dependency risks.

Python checks must not use assertions that `-O` can remove; test entry points reject optimization. Spawn subprocesses with argument arrays and explicit environments, keeping secrets out of argv. Paths and temporary slots must belong to the current task. Node, Shell, and JSON retain syntax supported by their real execution environments. Do not mix a formatter's broad changes into a functional fix.

## Validation and evidence

Every new pure-logic class needs tests. Demonstrate fixes through the actual entry point, real locked upstream modules, failure boundaries, or an isolated filesystem; source checks prove structural constraints only. Important fixtures first validate version, content, platform, and input fingerprints. Reading an old directory does not make it the current runtime.

Run the current release Standard/Low unit tests, Release Lint, APK/ELF, signing, and plugin/emergency gates. Record device, power-loss, FUSE, hidden-API, and vendor process-access restrictions separately. Retired manual-audit source may remain as history, but do not generate or install extra audit APKs or inject failures into real user data.

For each audit item, record the actual implementation, checks, and remaining limits. Keep software changes separate from missing device or external evidence. Reconstruct the current remaining set from current itemized receipts rather than copying historical `residualActionIds`. User-required open plugin/terminal behavior, historical reads, unknown-exit barriers, and integrity checks are contracts, not unresolved bugs. Consolidate maintainability changes around actual responsibilities; line counts, coverage, allowlists, and uncalibrated scores cannot replace engineering judgment.
