# DSHA security model

Original verification date: 2026-10-21, using the Chinese security model and published F10 `087724c` as references. The Root boundary supplement dated 2026-10-22 compares maintenance source `140c7ba277fc2a91a9b8fd8cb5a24bc35a5c41c5` and the pinned upstream files listed in references and provenance. Historical documents remain under `tools/history` and do not define current authorization boundaries. Formal terminals and third-party plugins are open under the current user policy; the independent recovery runtime retains five controlled tools and native write confirmation. The [threat-model ADR0002](adr/0002-current-threat-model.md) records the assets, actors, and scope of individual controls.

## Assets and actors

Protected assets include conversations, attachments, personal projects, plugin sources and dependencies, settings, API keys, device grants, backups, and managed runtime assets. Actors include the user, native app, same-UID Ubuntu/Node/plugins, embedded page, independent recovery process, LAN clients, external model services, and download servers. When the user selects an external model, request content and attachments are sent according to that service's actual protocol.

## Trust boundaries

| Boundary | Actual protection | Unsupported inference |
|---|---|---|
| Android application UID | Android isolates private data from other ordinary applications. | Guest root is not Android root. |
| Ubuntu and plugins | Package, path, actual digest, and transaction checks | Same-UID arbitrary code is not an independent sandbox. |
| Loopback bridge | Current token, route/capability constraints, and no replay after unknown execution | Local TCP is not exclusive to this app. |
| Embedded page | Current origin, actual port, page, and runtime generation | Arbitrary pages, stale callbacks, and cameras cannot be approved. |
| LAN | Separate enablement and authentication, current Web port, and bounded connections | LAN HTTP is plaintext. There is no TLS, trusted pairing, or guarantee against Android background restrictions. |
| User archive | Full authentication/digest, scope, and bidirectional record checks before preflight | Archive claims about health or format do not become local evidence. |

## Cross-app file access (SAF)

The user grants individual folders through the system document picker under Settings → Device permissions → Cross-app files; the assistant reads and writes them through `/app/saf/*`. A grant records the tree URI, app label, read/write bits and only the permission bits newly acquired by this feature. Revocation releases only those newly acquired bits, so permissions held by backup or other features on the same folder are not disturbed. The bridge never accepts a raw URI or authority; it only accepts short IDs from `/app/saf/trees`.

- **The Provider is a trust boundary**: display names, sizes, children and write results all come from the target Provider. DSHA enforces path/size limits, URI containment and local policy, but cannot replace the Provider's own authorization checks. If a Provider hangs, ignores cancellation or throws, the call fails closed with `TIMEOUT`/`PROVIDER_ERROR`/`SCAN_LIMIT`; DSHA does not guess locally.
- **Anchored container identity**: container paths use `O_NOFOLLOW|O_NONBLOCK`, `lstat`/`fstat` identity and `readlink /proc/self/fd/<fd>` real-path verification. Directories are opened level by level with `O_PATH|O_NOFOLLOW`; pull publishes atomically through the parent directory fd, and `AnchoredDirectory` owns the dup'ed PFD and closes it. Hard links (`nlink>1`) and proot `.l2s` entities are rejected; a swapped ancestor directory fails closed.
- **Streams own their fd**: streams returned by `openRead`/`openWrite`/`createInDirectory` are responsible for closing the underlying fd. Writes verify identity/real path before `ftruncate`, so a pre-existing hard link or unexpected file is never truncated first.
- **Write and delete**: writes/deletes require the local "allow assistant writes" switch for that folder. A write-only tree is rejected when added (`WRITE_ONLY`); legacy write-only records cannot be read and must be removed and granted again. `rm` is a two-step, one-time-token flow: the first call only returns `NEEDS_CONFIRM` (target, child count and a 60-second token bound to tree/path/target identity), and the second call must present `confirm=<token>`, which is consumed on use. `confirm=1` always returns `CONFIRM_REQUIRED` and an unknown token is rejected, so a single request cannot skip confirmation. A confirmed delete first preflights every directory level; any level with more than 500 children fails with `SCAN_LIMIT` before anything is deleted (paged recursive delete is future work). There is no "list first to decide empty/non-empty" branch, which removes the check-then-delete race.
- **Limits and concurrency**: one read is capped at 256 KiB and one write/push/pull at 32 MiB; at most two SAF transfers run at once; every public operation gets its own 15-second deadline (cancellation signal plus watchdog). Uninterruptible Provider calls are checked against the deadline after they return and mapped to `TIMEOUT`.
- **Known limitations**: `cp/mv` creates a new inode, so DSHA cannot recognize "copied credential content" by inode; the boundary is misuse/direct-read prevention, not content-level copy prevention. `push` truncates an existing target with `"wt"` first, so a Provider timeout/cancel/length mismatch may leave a partial target; when `COLUMN_SIZE` is unknown, only the write call itself can be verified. `saf/` is included in `launcherTrees` of `managed-runtime-inputs.json` (launchers 465→469); it is part of the bridge/security layer and must be pinned by `runtime-descriptor.json`.

## Terminals and plugins

Formal terminals allow repair shell commands. Link, local-package, and update installs automatically commit and enable after package-format, path, digest, and transaction checks. The old plugin-review gate is no longer required. Dependencies may resolve without a frozen lock, run lifecycle scripts, and use pnpmfile. Actual locks and directory digests are recorded to support rollback. Explicit user disablement and safe-mode intent remain effective.

Plugins run with the app UID, can read and write accessible files, and can execute code. Automatic installation is not independent security certification. Managed system components are separated from user plugins and rebuilt from the current signed APK. User modifications and personal dependencies must not be removed as though they were caches.

For skills with `disable-model-invocation: true`, the model-facing catalog keeps the user-only skill visible but marks the entry `[approval required]`. Loading its body requires `dsh-user-approval` first; only the exact `allowed-once` outcome permits this call. Rejection, cancellation, a missing or failing approval service, or a policy change that makes the loaded definition unavailable fails closed. Skills with both `modelInvocable` and `userInvocable` false stay out of the model catalog and cannot be bypassed through approval. An explicit `/name` in a user message still follows the user-invocable injection path; it does not substitute for model approval or grant a cross-call model permission.

### AI tool destructive policy (DSH 0.2.2)

DSH 0.2.2 moves tool execution into `dsh-tools/lib/pipeline.js`; the native and PTC executors share that pipeline. The signed `guard-tools-patch.json` and `guard-tools-pipeline-patch.json` hooks keep destructive bash and existing-file write/edit/replace operations behind the configured consent boundary. The new `apply_patch` tool remains disabled by default (`Config.applyPatch=false`) and is not covered by that policy; a profile or plugin that enables it can create, rewrite, delete, or move files without the same approval or read-before-write checks. The 0.2.2 `fs-observation-policy` is also disabled by default, so documentation must not imply that a pre-read observation boundary is active.

## Device and browser capabilities

Root, Shizuku, or ADB is selected before dispatch. Native and privileged execution policies check device commands. SMS, virtual screens, screenshots/UI reads, microphone, and storage require their respective native grants and actual Android permissions. Confirmation of an unknown command cannot replace device identity verification. Unknown execution must not be replayed through a different channel.

Managed device file writes are restricted to policy-approved shared-storage descendants and `/data/local/tmp/`. The shared root, DCIM, Pictures, Android/data, Android/obb and their ancestors are protected against writes. This boundary governs managed device commands; it is not a file firewall around same-UID terminals or plugins. SMS queries are off by default and require a separate revocable grant for strict `content query` against the current Android user. Provider writes are refused; file-read guards reject recognized telephony paths and canonical targets.

**Known coverage gap**: review of `140c7ba` on 2026-10-22 found that recursive READ validates top-level arguments and canonical paths, not nested symlink targets encountered during traversal. Reads such as `grep -R` that follow links cannot therefore be claimed unable to reach the entire telephony tree. This remains an implementation issue requiring repair. Isolated policy checks establish the missing pre-dispatch validation; no real phone SMS was read and no ROM exploit was demonstrated. The FILE adapter's NOFOLLOW behavior does not replace the READ guard. See section 6.2 of [permission architecture](device-permissions-architecture.md) for code entry points and repair requirements.

Metadata discovery of su, manager authorization and execution as UID 0 are separate facts. DSHA does not implement a Root manager in its APK. Section 5.1 of [references and provenance](references-and-provenance.md) records the pinned upstream review and compatibility limits. Root-manager identity authorization does not implement DSHA's command, SMS or virtual-screen policy, or turn same-UID arbitrary plugin code into a strong sandbox.

Accessibility can receive window events from other apps. Pairing-event scans handle Settings only during a short user-enabled window. Active UI reads and screenshots are separate capabilities. Screenshots may be saved in private Pictures/DSHA and returned through a content URI; they are not exclusively memory-resident. Microphone approval is limited to audio-only requests from the current local page. Camera and screen-audio requests are rejected.

## Credentials, networking, and logs

Native API keys use Keystore ciphertext. Temporary unreadability must not be treated as an unconfigured value, and must not cause automatic ciphertext deletion or replacement decryption keys. The bridge uses the `X-Token` request header. A compatibility shim migrates known local fetch calls; it does not enable CORS or claim support for arbitrary HTTP libraries. External file access and default backups exclude machine tokens. User-selected projects may intentionally contain `.env` files, which are not silently discarded.

With DSH 0.2.2, Web Fetch follows upstream behavior and does not reject private or internal network addresses by default. This is an explicit network boundary choice, not evidence that the guest is isolated from the local network.

`/app/net/resolve` is gated by the same `X-Token` and only maps a domain name to numeric addresses: it rejects IP literals, `localhost` and single-label names, opens no connection, does not log host names, and is bounded in concurrency and time. The guest can already send DNS queries over the same default network, so it adds no egress capability, though queries then follow the Android private-DNS / VPN path. `/app/net` is read-only and omits local IPs, SSIDs and carrier names.

Update URLs are subject to HTTPS, redirect, and APK identity checks. API23 and existing user HTTP/LAN behavior retain necessary cleartext compatibility. The app therefore must not be described as a global cleartext-blocking network firewall. Logs redact known secrets and common credential patterns; arbitrary third-party output is not guaranteed to have every secret detected.

## Backups and restoration

New exports always use password-free `DSHA-data-v5-UUID.tar.gz`, with no optional encryption mode. Internal automatic copies remain encrypted with a locally generated random key. Uninstalling may lose the local key, so important copies should be exported. Historical encrypted originals still require full AEAD authentication and their original passwords.

Restoration completes authentication/digest checks and preflight before writing private numbered slots. Managed scripts, executable configuration, and unknown plugins are isolated. Device grants and machine state cannot be activated from a user archive. Unknown, modified, and incompletely committed originals remain; they are not automatically deleted.

## Recovery and process transactions

Recovery has its own HOME and runtime root. It does not depend on the formal environment or substitute a native safe profile while claiming independent rescue. Its five controlled repair tools cannot install arbitrary packages or run arbitrary shell. Writes require native confirmation bound to candidate/source digests and the data generation, plus the stop barrier. The formal terminal remains a separate user repair route.

Stopping depends on birth identity, session, and exit evidence. Killing by port, name, or bare PID is prohibited. Host transaction journals govern installation, migration, and rollback. Unknown results retain the scene and runtime-task barrier rather than requiring users to clear app data to escape.

## Delivery and evidence limits

Release packages use `io.github.sunsetrne.dsha` and the Mod-owned certificate recorded in `ci/release-identity.json` (v1/v2/v3). Build tooling does not supply a default release password or substitute a different certificate. The build356 CI run proves only that the exact commit compiles, passes gates, unit tests, signing and publication on the hosted runner; software tests, APK/ELF/signature checks and a physical overwrite installation are separate evidence categories. The local release-preparation scope covers the full host contracts group, the whole-tree format check, the full unit tests (including 10 aarch64 environment baseline failures), lint, T145's `QuickActionsTest` / `QuickActionsBubbleTest` / launch-entry layout regressions and T146's `DeviceGrantsLayoutTest` / `GrantsAccordionTest` / screen-mode and sensitive-screen tests / layout resource compilation, plus `UpdateVersionPageTest` (see the [build356 notes](../releases/build356-changes.en.md)); device conclusions await the user's confirmation against the records and are not pre-written as passed. Earlier reports do not establish the current verification scope.
