# The 3090 bridge, file exchange and DocumentsProvider

Review date: 2026-10-21. Reference baseline: published F10 `087724c`. This page reviews production routes, file policies and related test sources in the current build190 working tree. It is not a receipt for device or endpoint execution during this work. The bridge protocol is defined by the running instance's `/app/version` and `/app/help`.

## Identify the entry point before making a call

3090 is the application's internal device bridge. It listens only on IPv4 `127.0.0.1` and IPv6 `::1`; it is not the externally accessible Web service. DSH chooses its Web port dynamically, and LAN access is provided separately through the 3081 proxy. See [ports](ports-and-networking.md) and [LAN operation](lan.md). Sending a Web request, sending an Android bridge command and opening a file Provider are three separate paths.

[HttpShellService](../../app/src/main/java/com/deepseekharness/app/HttpShellService.java) currently reports `BRIDGE_PROTOCOL=3`. Callers first check the version/capabilities and use protocol compatibility checks; the application's display version cannot establish whether an endpoint exists. The host writes bridge token headers to `/root/.dsh/.bridge_headers`. Keep credentials in the file:

```sh
curl -s -H @/root/.dsh/.bridge_headers http://127.0.0.1:3090/app/version
curl -s -G -H @/root/.dsh/.bridge_headers \
  http://127.0.0.1:3090/app/readfile --data-urlencode 'path=/root/report.md'
```

Authentication accepts **exactly one** `X-Token` header. Empty tokens, duplicate headers and a `token` parameter in the URL are rejected. Cookies are not bridge credentials. [BridgeCredentialAuth](../../app/src/main/java/com/deepseekharness/app/util/BridgeCredentialAuth.java) implements these rules. The bridge does not grant CORS access. That does not guarantee isolation of arbitrary plugins under the same UID: plugins and terminals can use content accessible to that UID. See the [security model](security-model.md). Do not copy tokens, header-file contents or credential-bearing commands into reports.

## Endpoint map and request boundaries

[BridgeRoutes](../../app/src/main/java/com/deepseekharness/app/util/BridgeRoutes.java) defines 33 exact paths and two prefixes. The table groups them by purpose. Check the current runtime's `/app/help` for parameters and return values; the table is not a wildcard permission for arbitrary paths or commands.

| Purpose | Paths | Main restrictions |
|---|---|---|
| Health and version | `/health`, `/app/version`, `/app/help` | Bridge authentication still applies; protocol capability and actual state are separate |
| Device and applications | `/app/device`, `/app/apps`, `/app/launch` | Actual user, target and Android capabilities are still checked |
| Physical and virtual screens | `/app/ui/*`, `/app/vscreen/*` | [Latest observation/node/frame and generation](virtual-screen-architecture.md); unknown outcomes must not be replayed |
| Clipboard, files and user interaction | `/app/clip`, `readfile`, `export`, `share`, `open`, `vibrate`, `ask`, `notify`, `toast` | Each abbreviated name has the `/app/` prefix; foreground, path and size limits apply independently |
| Location, sensors and flashlight | `/app/location`, `sensors`, `sensor`, `torch` | Disabled by default; DSHA switches and system permissions are checked separately |
| Plugins and overlay | `/app/plugins`, `/app/overlay` | A loaded-state report is not installation proof; overlays additionally require system capability |
| Tasks | `/app/tasks/context`, `event`, `claim`, `ack` | Task identity, generation and acknowledgement state; not a general shell interface |
| Device execution and consent | `/device/plan`, `execute`, `vscreen/start`, `vscreen/commit`, `consent`; `/app/native-policy`; `/confirm`, `/exec` | Allowlist, authorization and one-time dispatch identity; an unknown result must not be replayed through another channel |

Unsupported HTTP methods return 405. GET is the bridge default; only virtual-screen POST routes explicitly listed in current code accept their corresponding bodies. A route's existence does not authorize arbitrary POST requests or executable GET bodies. [HttpProtocol](../../app/src/main/java/com/deepseekharness/app/util/HttpProtocol.java) bounds header parsing: a 96 KiB request line, 16 KiB per header, 128 KiB total, 100 headers and a total five-second monotonic-clock deadline. Body and post-authentication execution budgets are separate. The original device-command limit is 8192 characters; a smaller header budget must not truncate a legitimate percent-encoded command.

Handle common failures at the correct layer: for 401, check the current bridge identity; for 405, check the method. `DISABLED`/`NO_PERMISSION` require user settings or Android authorization. `[POLICY_BLOCKED]`/126 is a rejection that a confirmation button cannot override. `[EXECUTION_UNKNOWN]`/125 means execution may already have happened; observe the real state first. [Device channels](device-channels.md) explains 124/125/126 and the no-replay rule.

## Reading, exporting and sharing files

[BridgePathPolicy](../../app/src/main/java/com/deepseekharness/app/util/BridgePathPolicy.java) checks the original path, normalized path and resolved actual target. `/root/.dsh`, `.ssh`, `.android`, cloud credentials and `.dsha-` runtime state cannot be read/exported through the bridge. Host private areas under `/data/data` and `/data/user` are also protected. The rootfs being located in application-private storage neither blocks the entire rootfs by mistake nor exempts credentials inside it. Symbolic links cannot bypass revalidation.

| Operation | Input and limit | Interpreting the result |
|---|---|---|
| `/app/readfile` | Absolute path; text up to 256 KiB; directory output has an aggregate bound | `FORBIDDEN` does not mean the file is absent; `SOURCE_CHANGED` means identity changed during collection, so the result is not a stable snapshot |
| `/app/export` | Work product up to 64 MiB; display names reject `/` and `..` | Saves to `Download/DSHA`; `TOO_LARGE`/`BAD_NAME` require changing the target or name, not expanding permissions |
| `/app/share` | Text or an allowed external-storage file path; files up to 2 GiB | Opening Android's share flow does not prove the recipient sent or saved the file |

These limits are separate from v5 data backups. To export conversations and configuration, use [backup and restore](backup-and-restore.md). Do not treat the bridge credential guard as a filesystem sandbox around the entire guest, or use an ordinary terminal to bypass a device operation the user refused.

## Existing Web File/Blob objects and the system document entry point

The Web APIs `window.DSHA.shareFile/saveFile` accept bytes from an existing `File`/`Blob`, not a local path. They are injected only in a system WebView that supports WebMessageListener, for the current runtime instance, actual loopback port and top-level same-origin page. Sharing is limited to 32 MiB; saving to 2 GiB. `chooser-opened` proves only that the chooser opened. Saving must write the file and verify its digest by reading it back. Cancellation is `AbortError`. See [Web file sharing](web-file-sharing.md) for names/download attributes, temporary read permission and the 24-hour share cache. Gecko and ordinary browsers must test for the interface instead of assuming the native bridge exists.

[DshaDocumentsProvider](../../app/src/main/java/com/deepseekharness/app/DshaDocumentsProvider.java) is a separate entry point in system document pickers/file managers. Its root is `dsha-root`, and the root document ID is `root`. Treat document IDs as stable opaque identifiers; do not construct or parse them to infer physical paths. [DocumentPaths](../../app/src/main/java/com/deepseekharness/app/util/DocumentPaths.java) retains compatibility between old path IDs and new logical locations.

Core anchors, including `linux/ubuntu/root/.dsh` and `user-data-v5/dsh`, cannot be renamed/deleted; this does not make every descendant read-only. Guest absolute links resolve against the selected rootfs, up to 40 levels, with out-of-root targets rejected. Tree deletion does not follow links to delete their targets. For writes, the FD is opened and its `/proc/self/fd` identity checked before truncation. Provider access follows Android's `MANAGE_DOCUMENTS` contract. It is not a general path API exported to arbitrary Web pages. Deliberate file-manager edits can still affect working data; follow the application's instructions during maintenance.

SAF streams share [DocumentStreams](../../app/src/main/java/com/deepseekharness/app/backup/DocumentStreams.java), preserving AFD offset/length and cancellation behavior. Do not wait for a remote Provider on the main thread. External-workspace maintenance only disconnects private references; it does not traverse and delete, overwrite or rename external content.

## Modification and validation entry points

Bridge authentication/routes/paths have `BridgeCredentialAuthTest`, `BridgeRoutesTest`, `BridgePathPolicyTest` and `BridgeFileSnapshotTest`; document IDs have `DocumentPathsTest`, and Web transfer has `WebTransferPolicyTest`, in the [pure-logic test directory](../../app/src/test/java/com/deepseekharness/app/util/). These are related entry points. This page did not rerun Android, Provider, share-recipient or all-endpoint matrices. For microphone origins and system permission, see [browser engines](browser-engines.md); for screenshot scope, see the [permissions architecture](device-permissions-architecture.md).
