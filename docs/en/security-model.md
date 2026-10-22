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

## Terminals and plugins

Formal terminals allow repair shell commands. Link, local-package, and update installs automatically commit and enable after package-format, path, digest, and transaction checks. The old plugin-review gate is no longer required. Dependencies may resolve without a frozen lock, run lifecycle scripts, and use pnpmfile. Actual locks and directory digests are recorded to support rollback. Explicit user disablement and safe-mode intent remain effective.

Plugins run with the app UID, can read and write accessible files, and can execute code. Automatic installation is not independent security certification. Managed system components are separated from user plugins and rebuilt from the current signed APK. User modifications and personal dependencies must not be removed as though they were caches.

## Device and browser capabilities

Root, Shizuku, or ADB is selected before dispatch. Native and privileged execution policies check device commands. SMS, virtual screens, screenshots/UI reads, microphone, and storage require their respective native grants and actual Android permissions. Confirmation of an unknown command cannot replace device identity verification. Unknown execution must not be replayed through a different channel.

Managed device file writes are restricted to policy-approved shared-storage descendants and `/data/local/tmp/`. The shared root, DCIM, Pictures, Android/data, Android/obb and their ancestors are protected against writes. This boundary governs managed device commands; it is not a file firewall around same-UID terminals or plugins. SMS queries are off by default and require a separate revocable grant for strict `content query` against the current Android user. Provider writes are refused; file-read guards reject recognized telephony paths and canonical targets.

**Known coverage gap**: review of `140c7ba` on 2026-10-22 found that recursive READ validates top-level arguments and canonical paths, not nested symlink targets encountered during traversal. Reads such as `grep -R` that follow links cannot therefore be claimed unable to reach the entire telephony tree. This remains an implementation issue requiring repair. Isolated policy checks establish the missing pre-dispatch validation; no real phone SMS was read and no ROM exploit was demonstrated. The FILE adapter's NOFOLLOW behavior does not replace the READ guard. See section 6.2 of [permission architecture](device-permissions-architecture.md) for code entry points and repair requirements.

Metadata discovery of su, manager authorization and execution as UID 0 are separate facts. DSHA does not implement a Root manager in its APK. Section 5.1 of [references and provenance](references-and-provenance.md) records the pinned upstream review and compatibility limits. Root-manager identity authorization does not implement DSHA's command, SMS or virtual-screen policy, or turn same-UID arbitrary plugin code into a strong sandbox.

Accessibility can receive window events from other apps. Pairing-event scans handle Settings only during a short user-enabled window. Active UI reads and screenshots are separate capabilities. Screenshots may be saved in private Pictures/DSHA and returned through a content URI; they are not exclusively memory-resident. Microphone approval is limited to audio-only requests from the current local page. Camera and screen-audio requests are rejected.

## Credentials, networking, and logs

Native API keys use Keystore ciphertext. Temporary unreadability must not be treated as an unconfigured value, and must not cause automatic ciphertext deletion or replacement decryption keys. The bridge uses the `X-Token` request header. A compatibility shim migrates known local fetch calls; it does not enable CORS or claim support for arbitrary HTTP libraries. External file access and default backups exclude machine tokens. User-selected projects may intentionally contain `.env` files, which are not silently discarded.

Update URLs are subject to HTTPS, redirect, and APK identity checks. API23 and existing user HTTP/LAN behavior retain necessary cleartext compatibility. The app therefore must not be described as a global cleartext-blocking network firewall. Logs redact known secrets and common credential patterns; arbitrary third-party output is not guaranteed to have every secret detected.

## Backups and restoration

New exports always use password-free `DSHA-data-v5-UUID.tar.gz`, with no optional encryption mode. Internal automatic copies remain encrypted with a locally generated random key. Uninstalling may lose the local key, so important copies should be exported. Historical encrypted originals still require full AEAD authentication and their original passwords.

Restoration completes authentication/digest checks and preflight before writing private numbered slots. Managed scripts, executable configuration, and unknown plugins are isolated. Device grants and machine state cannot be activated from a user archive. Unknown, modified, and incompletely committed originals remain; they are not automatically deleted.

## Recovery and process transactions

Recovery has its own HOME and runtime root. It does not depend on the formal environment or substitute a native safe profile while claiming independent rescue. Its five controlled repair tools cannot install arbitrary packages or run arbitrary shell. Writes require native confirmation bound to candidate/source digests and the data generation, plus the stop barrier. The formal terminal remains a separate user repair route.

Stopping depends on birth identity, session, and exit evidence. Killing by port, name, or bare PID is prohibited. Host transaction journals govern installation, migration, and rollback. Unknown results retain the scene and runtime-task barrier rather than requiring users to clear app data to escape.

## Delivery and evidence limits

Release packages retain `dsh.android.client`, the historical E7E3 certificate, and v1/v2/v3 signatures. Build tooling does not supply a default release password or substitute a different certificate. Historical F13/package-rename records paused phone operations. This performance round has new device-acceptance authorization; actual results require records bound to its final APK. Software tests, APK/ELF/signature checks, and a physical overwrite installation are separate evidence categories. Current results come from the audit and delivery reports linked by [README](../../README.en.md); earlier reports do not establish the current verification scope.
