# Independent emergency DSH

> Translation verified: 2026-10-21. Source baseline: released F10 `087724c` and the current Chinese build190 emergency-runtime, tool, and native-confirmation guide. Translation adds no device, model-conversation, start/stop, or regular-data repair validation. See the [English README](../../README.en.md) and [release introduction](release-introduction.md) for final identity and executed scope.

Emergency DSH provides independent diagnosis when the regular environment cannot install, undergo maintenance, or start normally. It has its own pinned rootfs, HOME, empty profile, workspace, and sessions. It neither boots from the regular rootfs nor first runs regular migration, data binding, or the global startup lock. Regular “safe startup” still uses the regular environment and is a different path. See [runtime, data, and plugins](runtime-data-plugin-architecture.md) for the overall boundaries.

## Open and use it

The native [RecoveryActivity](../../app/src/main/java/com/deepseekharness/app/ui/RecoveryActivity.java) provides “Start emergency DSH,” “Open emergency Web,” “Stop emergency DSH,” native diagnosis, repairs without a model, logs, and backups. It can be opened from environment recovery and does not require regular Web readiness.

1. Optionally enter a temporary API key for this session only. Leaving it blank attempts an existing readable credential. The input does not save instance state or participate in autofill.
2. Start and wait for packaged-asset verification, tool self-checks, and this generation's Web authentication. Native diagnosis remains available without model credentials; model conversation needs a usable temporary credential.
3. Open emergency Web and use the five controlled tools to read relevant diagnosis and propose candidates. Raw diagnostic contents may be sent to the configured model service. The host hides known credential fields, without guaranteeing recognition of all private information in user files.
4. Return to the native emergency page, inspect each candidate and its differences, and confirm or reject it individually. A tool-generated proposal is not an executed repair.
5. Check the candidate result, retry regular startup to establish actual usability, then stop emergency DSH normally. To change the temporary key, stop before starting again.

Leaving the page or pressing Back does not prove shutdown. Stop can be requested during preparation/startup. The 60-second slow-start message is informational, not a force-kill deadline. Entry into the UI follows [RecoveryController](../../app/src/main/java/com/deepseekharness/app/recovery/RecoveryController.java)'s current state, generation, and instance identity.

## Five tools and proposal boundaries

[recovery-agent.js](../../app/src/main/assets/recovery-agent.js) registers only these tools. It does not expose arbitrary shell execution, path read/write, or direct model overwrites of regular plugins/sessions.

| Tool | Input/output boundary | Writes regular data? |
|---|---|---|
| `dsha_diagnostics` | Reads redacted startup/maintenance state without running the regular environment | No |
| `dsha_targets` | Lists host-approved target IDs and actions; accepts no arbitrary paths | No |
| `dsha_read` | Reads relevant contents, source digest, and data generation by target ID, with credentials hidden | No |
| `dsha_propose` | Creates a candidate bound to target ID, previously read source SHA-256, and data generation | No; waits for native confirmation |
| `dsha_result` | Queries pending review, rejection, completion, or failure | No; cannot confirm |

Ordinary settings candidates must pass the current schema. Managed-runtime and CLI-dependency repairs can invoke only fixed maintenance actions from the current signed APK and the existing trial flow; they cannot let a model freely edit `node_modules` or install unlocked packages. Targets may also expose host actions such as interrupted-maintenance recovery, a new basic Web profile, or a new global patch. `dsha_targets` returns the actual available set; the tool count is not the count of executable repair actions.

[RecoveryRepairBroker](../../app/src/main/java/com/deepseekharness/app/recovery/RecoveryRepairBroker.java)'s local HTTP protocol exposes only diagnostics, targets, read, propose, result, and consent, with no confirm route. Requests require fixed POST, exact `127.0.0.1:<port>` Host, one Bearer token, no Origin, and a bounded body. Authentication allows that session to access controlled interfaces; it does not replace native user confirmation. [RecoveryBrokerProtocol](../../app/src/main/java/com/deepseekharness/app/util/RecoveryBrokerProtocol.java) validates the protocol.

## Native confirmation, transactions, and shutdown

Native confirmation binds the emergency instance, generation, nonce, candidate, current source digest, and regular-data generation. Confirmation checks the source again. `REPAIR_SOURCE_CHANGED` blocks an old candidate after a change. If maintenance recovery is pending, perform the explicit recovery action first rather than bypassing its journal and updating directly.

After confirmation, [MaintenanceCoordinator](../../app/src/main/java/com/deepseekharness/app/core/MaintenanceCoordinator.java) normally stops regular Web, terminals, and writers before entering the relevant host transaction. Unconfirmed regular guest/launcher exit retains the barrier and blocks writing. Settings candidates use [ProfileSettingsTransaction](../../app/src/main/java/com/deepseekharness/app/backup/ProfileSettingsTransaction.java), basic configuration uses [StartupRepairs](../../app/src/main/java/com/deepseekharness/app/core/StartupRepairs.java), and managed runtime uses [EnvironmentMaintenance](../../app/src/main/java/com/deepseekharness/app/core/EnvironmentMaintenance.java).

An already confirmed native repair lease remains protected by the emergency foreground service. Stopping emergency chat revokes later proposals/confirmations and closes the broker, but does not release an in-flight native repair lease early. The service finishes after active repairs end. `APPLIED` establishes completion of that native transaction only; retry regular Web to verify readiness. Failures retain candidates and originals for native inspection and do not replay business tasks automatically.

[RecoveryRuntime.stop](../../app/src/main/java/com/deepseekharness/app/recovery/RecoveryRuntime.java) writes a stop sentinel and sends SIGTERM only to the recorded guest whose birth identity matches twice. It does not kill by bare PID/port/name or kill the proot launcher first. It writes closed only after both guest and launcher exit are confirmed. Unknown exit is shown as unconfirmed stopping and retains records, allowing a normal stop retry. Deleting `recovery-active` cannot manufacture closure.

## `READY` and `READY_READ_ONLY`

Readiness requires at least pinned-asset verification, authentication exchange, five-tool self-checks, and a launcher that has not exited. A full recovery instance is registered only after process identity is verified. If the system denies identity access but other readiness evidence holds, state may be `READY_READ_ONLY`: emergency chat and read-only diagnosis are usable, while the broker blocks regular-data writes.

`READY_READ_ONLY` does not exempt stopping proof, register a process that regular stop barriers may skip, or gain write rights through “Allow all.” Unreadable `/proc`, a reachable token-protected endpoint, or existing tools alone cannot establish trust in an arbitrary emergency process.

## Independent assets, sessions, and storage

[tools/recovery-runtime/lock.json](../../tools/recovery-runtime/lock.json) is independent of the regular DSH lock. APK `recovery-asset-locations.json` describes physical locations of pinned archives only. Packaged bytes are shared only if the regular archive's SHA exactly matches the independent emergency lock. Sharing compressed bytes does not share the extracted runtime root or automatically promote a new regular DSH into the emergency version. See the [emergency asset build note](../../tools/recovery-runtime/README.md).

`files/recovery-capsules/<runtimeId>/` stores the independent runtime capsule; `files/recovery-sessions/<instanceId>/` stores each independent HOME and scene. Archives and managed files are verified during creation. Originals from failed re-preparation remain, and sessions are not automatically deleted. `recovery-active` tracks unclosed instances, not a permanent limit based on historical session count. Prepared reuse accepts only candidates not yet launched, with inode/digest matching prepare-proof.

`RecoveryRuntime.prepareProfile` generates `dsha-recovery-agent/package.json` with valid `name` and `version`. The locked rc2 model-request extension reads active-plugin versions; a missing version can throw `REQUEST_EXTENSION` before model HTTP starts. An open Web page therefore does not prove emergency requests can be sent.

[RecoveryStoragePlan](../../app/src/main/java/com/deepseekharness/app/recovery/RecoveryStoragePlan.java) reports apparent file bytes without following links, with unknown on errors. It does not delete emergency originals automatically. On low storage, inspect native errors and protect readable data first rather than clearing regular Ubuntu or user sessions to make space.

## Modification and verification entry points

- Fixed assets, publication, and storage: [RecoveryPublicationTest](../../app/src/test/java/com/deepseekharness/app/recovery/RecoveryPublicationTest.java), [RecoveryStoragePlanTest](../../app/src/test/java/com/deepseekharness/app/recovery/RecoveryStoragePlanTest.java), and [RecoveryArchiveSourceTest](../../app/src/test/java/com/deepseekharness/app/util/RecoveryArchiveSourceTest.java).
- Protocol, confirmation, and state: [RecoveryBrokerProtocolTest](../../app/src/test/java/com/deepseekharness/app/util/RecoveryBrokerProtocolTest.java), [RecoveryRepairPlanTest](../../app/src/test/java/com/deepseekharness/app/util/RecoveryRepairPlanTest.java), and [RecoveryStatusTextTest](../../app/src/test/java/com/deepseekharness/app/util/RecoveryStatusTextTest.java).
- Real model-extension/tool preparation: regressions must call the locked DSH's actual `deepseekLlmApiExtensions.prepare()` and check the emergency package, not merely search for `toolNames`. Shutdown regressions must cover guest and launcher separately.

These are entry points for related checks. This page does not claim that every model service, Android FD/identity restriction, background-service behavior, or historical repair path has been tested on a device in this round. Historical records apply only to their specified version and scene.
