# ADB, Root and Shizuku: operation and failure semantics

Review date: 2026-10-21. Reference baseline: published F10 `087724c`. This page follows the device-channel call chain in the current build190 working tree. See the [permissions architecture](device-permissions-architecture.md) for the complete architecture, permissions and state machines. This page does not claim that a device was connected during this work.

## Choose the capability you need

Screen reading, text-based tapping and ordinary physical-screen observation use the accessibility service; they do not require ADB to be configured first. A working privileged channel is needed for capabilities such as device shell commands, installing or stopping supported applications, and creating a separate virtual screen. Being shown as root inside the container does not mean Android is rooted. Enabling accessibility alone does not create a virtual screen.

Configure and revoke Root, Shizuku and ADB separately under **Settings → Device capability authorization**. The three channels do not share one master switch. DSHA authorization, Android system permission, the actual execution identity and approval of an individual command are also checked separately.

| Channel | User preparation | What confirms success | Failure and revocation |
|---|---|---|---|
| Direct wireless ADB | Enable wireless debugging in Android; enter the system-provided pairing port/code in DSHA, then use the connection port | Pairing and connection are separate results; the actual identity returned by local `id` and the current connection state govern execution | Debugging may need to be enabled again after a system restart or after it is turned off; revoking the DSHA capability does not mean Android has revoked pairing |
| Shizuku | Start Shizuku, allow DSHA in its manager, and wait for the execution-service Binder connection | Manager permission does not mean the execution service is ready | An unavailable Binder or unready service returns a failure; losing contact after dispatch can leave an UNKNOWN result |
| Root | Have an existing su/manager and explicitly allow DSHA; discovery must not silently request permission | Finding su, user approval and a successful real command are three separate steps | Without su the channel is unavailable; a user refusal is not bypassed through another interpreter |

Android supplies both the ADB pairing port and connection port. Do not enter the Web port or 3090. The application prepares dependencies offline. `PAIR_OK`, `CONNECT_OK`, `CONNECT_WARN` and `DEPS_MISSING` have different meanings. Having paired is no reason to report a connection warning as success.

## How one device command is dispatched

Automatic device execution in [HttpShellService](../../app/src/main/java/com/deepseekharness/app/HttpShellService.java) selects **one** available channel in Root → Shizuku → ADB order. `--su` is for explicitly authorized operations that require root. [DeviceShellPolicy](../../app/src/main/java/com/deepseekharness/app/util/DeviceShellPolicy.java) first recognizes and constrains the complete command, which then enters [DeviceShellExecutor](../../app/src/main/java/com/deepseekharness/app/DeviceShellExecutor.java). The same policy applies to all three channels; root cannot allow a rejected command.

Only a single, fully recognized argv is accepted. The channel does not execute caller-supplied shell programs, pipes, redirections or unknown commands. Writes involving block devices, partitions, mounts, SELinux, flashing and system properties are rejected. `[POLICY_BLOCKED]` has no “allow anyway” option. Stopping an application also checks the complete package name, application inventory and user/system classification; it does not force-kill by bare PID or an approximate name.

| Result | Meaning | Next action |
|---|---|---|
| 124 / `*_UNAVAILABLE` / `*_NOT_READY` | The channel was definitively unavailable or unready before dispatch | Repair the corresponding permission or service; do not interpret this as the business command's exit code |
| 125 / `[EXECUTION_UNKNOWN]` | The command may have been sent or executed, but its result cannot be determined | Observe the real target state and retain the evidence; do not switch channels or replay automatically |
| 126 / `[POLICY_BLOCKED]` | Execution policy rejected the operation | Report the reason; ordinary user confirmation cannot override this policy |
| Tool action accepted | Android accepted the action/submission | This does not prove the target application achieved the intended effect; observe again |

A timeout does not automatically mean “not executed.” Some calls have independent waiting budgets. Shizuku service readiness, ADB connection, device commands and virtual-screen startup do not share one budget. After an error, switching channels must not conceal an unknown result.

## Screen access, SMS and tool approval are separate

Screen permission is bound to the current runtime generation, actual target, and latest screenshot or node identity. **Allow all** only skips DSHA confirmation. It does not supply missing system capabilities or remove fresh-frame/node checks. Stop, revocation and a rule revision change invalidate old queued requests. See the [security model](security-model.md) and [virtual-screen architecture](virtual-screen-architecture.md).

SMS reading is a separate sensitive capability: confirmation is required for each request by default, and only a read-only `content query --uri content://sms` for the current Android user is allowed. Shizuku does not execute this sensitive query. Sending, modifying and deleting are not allowed; Android may still refuse even when DSHA allows the read. This authorization does not migrate with a backup.

The native terminal and third-party plugins remain open to execution under the application's UID. Device-tool policy does not form a strong sandbox around arbitrary code. DSH tool approval, Android capability permission and channel identity cannot substitute for one another.

## Maintenance and validation entry points

Channel implementations: [RootShell](../../app/src/main/java/com/deepseekharness/app/RootShell.java), [ShizukuShell](../../app/src/main/java/com/deepseekharness/app/ShizukuShell.java), [AdbBridge](../../app/src/main/java/com/deepseekharness/app/bridge/AdbBridge.java), and [DeviceBridgeService](../../app/src/main/java/com/deepseekharness/app/DeviceBridgeService.java). Choose related tests and diagnostic paths from the [permissions regression map](device-permissions-architecture.md). Code and host tests do not prove that every ROM, Root manager, Shizuku version or wireless network works. No ADB device operation was performed to supplement evidence during this work.
