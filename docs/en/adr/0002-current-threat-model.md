# ADR 0002: Current threat model and scope of controls

Status: source-boundary review. This record describes the implementation and user choices. It is not a legal conclusion, independent security certification, or proof of testing across all devices. The decision to open production plugins, dependency scripts, and terminals is recorded in [ADR 0001](0001-build154-boundaries.md). See the [English security model](../security-model.md); the [Chinese original](../../security-model.md) remains available as the source-language counterpart.

## Assets and actors

Assets include API Keys / Keystore ciphertext, bridge tokens, LAN tokens, conversations, attachments, projects, plugin source/dependencies, configuration, backups, SMS, screen contents, shared storage, and managed runtime assets. Actors include the user, native application, same-UID guest/Node/third-party plugins, embedded pages, independent recovery process, other local applications, LAN clients, model services, and download/update sources.

Requests and attachments may be sent to an external model service configured by the user. Model services and download sources are not local authorization authorities. An archive, plugin, or external page cannot obtain local capability permission by claiming a healthy state, format, or origin.

## Threats, controls, and limits

| Threat and entry | Current control | Nature and limits |
|---|---|---|
| Another ordinary app accesses private files | Android UID and private directories | OS isolation; same-UID guests, plugins, and terminals share permissions. proot does not isolate malicious code. |
| A local app connects to the loopback bridge | Current X-Token, bounded routes, capability/generation checks, no open CORS | Authentication; other apps can connect to local TCP, and same-UID code may read local credentials. |
| LAN eavesdropping or leaked tokens | Explicit enablement, authentication, connection/stream budgets, token redaction | Authentication and availability; LAN HTTP is plaintext, without TLS or trusted pairing. |
| Untrusted plugin/install scripts execute | Package format, paths, actual digests, dependency locks/tree proof, host transactions | Content and recovery controls; production lifecycle scripts/pnpmfile and automatic enablement remain open. No malicious-plugin sandbox is claimed. |
| Managed device commands accidentally overwrite system/media data | Native and privileged sides parse the same command policy and validate targets; protect shared roots, DCIM/Pictures/Android private media directories | Accidental-operation protection at managed entry points; it does not constrain arbitrary same-UID code already authorized by the user. Confirmation cannot fabricate device identity. |
| SMS disclosure | Separate, revocable, default-off permission; strict content queries for the current Android user; reject provider writes and recognized telephony paths/canonical targets | Sensitive capability control; a backup cannot restore the grant or replace actual OS permission. Recursive READ has the known nested-link validation gap below; the whole private tree cannot be claimed unreachable. |
| Old pages/callbacks obtain microphone or screen access | Current origin, actual port, page, generation/authorization revision, and tool checks; reject camera/screen audio | Capability control; sensitive-window classification is heuristic and cannot identify every private page. |
| Screens/logs leave the device | Read on request; ordinary screenshots may be saved in private Pictures/DSHA, MCP may return images; known-secret redaction and no raw output in reports by default | Data minimization; screenshots are not guaranteed to remain off disk, regex cannot detect arbitrary secrets, and sharing requires the user's choice. |
| An untrusted archive overwrites settings/grants | Full authentication/digest before preflight, bidirectional scope/record checks, private numbered slots, link boundaries, quarantine | Integrity and path controls; archives do not activate machine state or device grants. Unknown historical originals remain. |
| Emergency chat arbitrarily modifies production data | Independent root/HOME/workspace and five tools; native confirmation binds the candidate, source digest, and data generation before the existing stop barrier | Controlled repair; HTTP cannot confirm writes, and an open production terminal does not remove this boundary. |
| PID reuse or an unknown guest causes a wrong kill/lost scene | Birth identity, session, exit evidence; retain locks and scene until exit is confirmed | Accidental-operation and transaction controls; no force-kill by bare PID/port/name, and OS read denial does not imply exit. |
| An update source falsely describes a version | HTTPS/redirect restrictions, complete APK SHA, package/version/flavor/installed-signature checks | Source and installation controls; the manifest has no independent signature, so a compromised server may still misdescribe an official release. |

Supplemental review on 2026-10-22 (maintenance source `140c7ba`): the SMS-protection intent remains, but [DeviceShellExecutor.validateRootReadPaths](../../../app/src/main/java/com/deepseekharness/app/DeviceShellExecutor.java) and corresponding ADB checks validate command-line paths/canonical targets, not nested links encountered after recursive READ dispatch. Isolated checks confirmed dispatch of a link-following READ after top-level validation; no real phone SMS was read and no ROM exploit was demonstrated. This is an unresolved implementation-coverage gap, not a documentation-only issue. FILE NOFOLLOW does not repair READ. See [permission architecture](../device-permissions-architecture.md), section 6.2, for repair requirements and [references and provenance](../references-and-provenance.md), section 5.1, for the boundary between upstream authorization and local guards.

## Verification scope

Source and host tests establish check logic and bounded-input behavior. They do not establish an Android OEM/Root/Shizuku matrix, real FUSE behavior, 16 KiB pages, long-term background operation, Keystore behavior, or model-service coverage. Atomic-publication regression on Linux tmpfs is not proof of durable disk synchronization or power-loss recovery. Record current passing items and gaps in the round's item ledger and final artifact receipts.

Unclear external-source rights and historical digests without a trustworthy original remain unknown; this ADR does not invent their provenance. Update the record when authorization, protocols, or recovery boundaries change, and test the actual entry points rather than only their wording.
