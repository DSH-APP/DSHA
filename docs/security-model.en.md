# DSHA security model

This page describes current source policy. Historical documentation is preserved under tools/history. Formal terminals and third-party plugins are open under the current user policy; the independent recovery runtime retains five controlled tools and native write confirmation. The [threat-model ADR 0002](adr/0002-current-threat-model.md) records the assets, actors and scope of individual controls.

## Assets and actors

Protected assets include conversations, attachments, personal projects, plugin sources and dependencies, settings, API keys, device grants, backups and managed runtime assets. Actors include the user, native app, same-UID Ubuntu/Node/plugins, embedded page, recovery process, LAN client, external model and download servers. Selected external services receive request content and attachments according to their actual protocols.

## Trust boundaries

| Boundary | Actual protection | Unsupported inference |
|---|---|---|
| Android app UID | Other ordinary apps' private data remains system-isolated | Guest root is not Android root |
| Ubuntu and plugins | Package, path, actual digest and transaction checks | Same-UID arbitrary code is not an independent sandbox |
| Loopback bridge | Current token, route/capability checks, no replay after unknown execution | Local TCP is not app-exclusive |
| Embedded page | Current origin, actual port, page and generation | No approval of arbitrary pages, stale callbacks or cameras |
| LAN | Explicit enablement, authentication and bounded current-port connections | LAN HTTP is plaintext; no TLS, trusted pairing or guarantee against Android background restrictions |
| User archive | Authentication/digest, scope and bidirectional record checks before preflight | Archive claims do not prove local health or format |

## Terminals and plugins

Formal terminals allow repair shell commands. Link, local-package and update installs automatically commit and enable after package/path/digest/transaction checks; there is no plugin review gate. Dependencies may resolve without a frozen lock and execute lifecycle scripts and pnpmfile. Actual locks and directory digests are recorded for rollback. Explicit user disablement and safe-mode intent remain effective.

Plugins run with the app UID and can access permitted files and execute code. Automatic installation is not independent security certification. Signed system components are rebuilt from the current APK separately from user plugin sources, modifications and dependencies.

## Device and browser capabilities

Root, Shizuku or ADB is selected before dispatch. Native and privileged execution policies check device commands. SMS, virtual screens, screenshots/UI reads, microphone and storage require their respective native grants and actual Android permissions. Confirmation does not prove target identity. Unknown execution must not be replayed through another channel.

Managed device file writes are restricted to allowed shared-storage descendants and `/data/local/tmp/`. The shared root, DCIM, Pictures, Android/data, Android/obb and their ancestors are protected. This boundary governs managed device commands; it is not a file firewall around same-UID terminals or plugins. SMS is disabled by default and requires a separate revocable grant for strict `content query` against the current Android user. Provider writes and direct reads of the telephony private tree are rejected.

Accessibility receives other applications' window events; pairing scans only handle Settings during a short user-enabled window. Active UI reads and screenshots are separate capabilities. Screenshots may be stored in private Pictures/DSHA and returned using content URIs; they are not exclusively memory-resident. Microphone approval is limited to current local-page audio requests; camera and screen-audio requests are rejected.

## Credentials, network and logs

Native API keys use Keystore ciphertext. Temporarily unavailable credentials must not be treated as unconfigured or cause replacement keys or deletion. The bridge uses X-Token headers. A compatibility shim migrates known local fetch calls; it does not enable CORS or claim every HTTP library is supported. External reads and default backups exclude machine tokens; selected personal projects may intentionally contain .env files and are not silently discarded.

Updates enforce HTTPS, redirect and APK identity checks. API 23 and existing user HTTP/LAN behavior retain necessary cleartext compatibility; this is not a global network firewall. Logs redact registered secrets and common credential patterns, with no guarantee of detecting every third-party secret.

## Backups and restore

New exports always use password-free DSHA-data-v5-UUID.tar.gz, with no encryption option. Internal automatic copies remain encrypted using local random keys. Uninstalling may lose those keys, so important copies should be exported. Historical encrypted originals still require full AEAD authentication and their original passwords.

Restore authenticates and checks digests before preflight and writes private numbered slots. Executable settings, unknown plugins and managed scripts are isolated. User archives cannot enable machine state or device grants. Unknown, modified or incompletely committed originals are retained.

## Recovery and process transactions

Recovery has its own HOME and runtime root; it does not substitute a native safe profile or depend on the damaged formal root. Its five controlled tools do not install arbitrary packages or run arbitrary shell. Writes bind native confirmation to candidate/source digests and data generation and retain the stop barrier. The formal terminal remains a separate repair route.

Stopping requires birth identity, session and exit evidence; bare PID, port or name-based killing is prohibited. Host journals govern installation, migration and rollback. Unknown results retain the scene and task barrier rather than requiring users to clear data.

## Delivery and evidence limits

Formal packages retain com.dsh.client, the historical E7E3 certificate and v1/v2/v3 signatures. Release passwords are explicit and another certificate is never substituted. This round performs no phone actions as requested. Software tests, APK/ELF/signature checks and physical overwrite installation are separate evidence. Current results come from the audit and delivery report linked by README; previous reports do not establish the current verification scope.
