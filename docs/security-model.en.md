# DSHA Security Model — what it can reach on your phone

You installed a 370 MB APK that lets an AI run shell commands on your device, and you may
have handed it ADB access on top of that. This document states **where the boundaries are**:
what the agent can reach, what it cannot, what needs your explicit approval, and what each
permission actually exposes once granted.

If a line doesn't make sense to you, treat it as dangerous. You can run DSHA in its most
restricted configuration — no ADB, no file permission, no LAN access — and dsh still works fine.

> 中文版：[security-model.md](security-model.md)

---

## In one sentence

**By default the agent can only touch DSHA's own private directory.** Every step beyond that
is opt-in, granted explicitly by you, and individually revocable.

---

## What the agent reaches by default

Fresh install, no permissions granted:

| Scope | Reachable | Notes |
|---|---|---|
| The Ubuntu container | ✅ Fully | This is its workspace. `apt install`, compiling, running services — all here |
| DSHA's private directory | ✅ Read/write | The rootfs lives here (`/data/data/com.dsh.client/`); Android's app sandbox keeps everyone else out |
| Shared storage (`/sdcard`) | ✅ Read/write | `/sdcard` inside the container maps to shared storage. **This is on by default** — photos, downloads, documents are all readable |
| Other apps' data | ❌ No | Android app sandbox isolation; `/data/data/<other.package>` is unreachable |
| System partition | ❌ Not writable | Without root, `/system` cannot be modified |
| Location | Off by default | Coarse/fine location is declared, but the DSHA capability switch and Android runtime permission must both allow access. Declaration is not authorization |
| Dialer, contacts, camera and microphone | No automatic access | Access depends on actual Android declarations, grants and the selected channel, not on container root identity |
| SMS | Off by default in the device bridge | Requires separate capability confirmation and actual channel permission |

> ⚠️ **`/sdcard` is reachable by default** and this is the easiest one to overlook. If you don't
> want the agent seeing your gallery and downloads, right now your options are convention
> (tell it so in `AGENTS.md`) or not keeping sensitive files on this phone.
> Mount points live in `ContainerRuntime.BINDS` if you build it yourself.

---

## Capabilities that need your approval

Each one is **off by default**, toggled in-app, revocable at any time.

### Wireless ADB (Workspace page)

The ADB connection has `shell` privileges (uid 2000). Bundled device command entry points allow recognized queries, ordinary file operations and stopping verified user applications. They reject package installation/removal, clearing application data and changing system settings. App UI operations use separate endpoints and authorization. This parser does not isolate arbitrary container code from stored ADB credentials.

- The pairing code is used once; a keypair maintains the connection afterwards. DSHA never stores your pairing code
- To revoke: turn off Wireless debugging in system settings, or revoke all debugging authorizations
- What ADB cannot do: read other apps' private data, or obtain root

### Root shell (Config page, off by default)

Bundled device commands still apply the same policy when Root is enabled. The Ubuntu guest's root identity does not grant Android Root.

- Requires an already rooted device (KernelSU / Magisk). After enabling the channel, the existing Root manager decides whether to grant DSHA `su` access. DSHA does not obtain or install Root
- The flag is `allow_root_shell`, default false
- Don't enable it unless you know exactly why you are

### SMS reads (Settings → Device capability permissions, added in 0.1.5-rc1.1)

Strict `content query --uri content://sms` requests for the current Android user require confirmation by default. Explicit native preauthorization allows assistants and plugins to query numbers, message bodies and timestamps through ADB, potentially including verification codes. Turning it off restores confirmation for subsequent queries. This permission is excluded from Android backup and device transfer.

It does not allow sending, changing or deleting messages, reading other users or querying other content providers. Android can still deny access. Shizuku does not execute these sensitive queries. The policy applies to bundled entry points and is not a separate UID sandbox for arbitrary container code.

### "All files access" (prompted on first launch)

**What granting it means**: DSHA can read and write all of shared storage. It uses this to
move conversation data into `Documents/dshdata`, which is what makes **data survive uninstall**.

- If you decline: data stays in the private directory and **dies with uninstall** (the self-check tells you which state you're in)
- It grants the agent no new reach — `/sdcard` was already available

### Overlay window (the streaming floating bar, off by default)

**What granting it means**: DSHA can draw on top of other apps.

- It's used only to display model output and the approval buttons for dangerous commands. It does not read or capture the screen
- Content appears on your screen — **anyone nearby can read it**, which is why it ships disabled

### LAN access (off by default)

**What enabling it means**: devices on the same Wi-Fi can reach the dsh Web UI on your phone.

- Token authentication is **fail-closed**: a missing or wrong token is always rejected; there is no "empty token means allow" path
- On first successful hit the token becomes a `SameSite=Strict` cookie and disappears from the URL — so it can't leak through outbound links
- Don't enable it on public Wi-Fi. The token is strong, but you're exposing a port on the network

---

## What happens when the agent tries something dangerous

`dsh-guard.sh` intercepts these and hands the decision to you:

- Overwriting or deleting critical paths (`/`, `/root`, `/etc`, `/data`, …)
- Recursive deletion (`rm -rf`)
- Writing directly to block devices, modifying partitions
- Uninstalling apps or factory-reset-class operations over ADB

Once intercepted, approve through **any of three channels**: the notification, the in-app
dialog, or the button on the floating bar. All three share one decision — first tap wins —
and 60 seconds of silence counts as a refusal.

> ⚠️ **The gate is not a sandbox.** It's a denylist over command text, and it isn't hard to
> get around — it defends against an AI slipping, not against someone deliberately writing a
> command that evades it. Android has no bubblewrap, so dsh runs with `danger-full-access`.
> There is exactly one real isolation boundary: **the Android app sandbox**.

---

## Where your keys and data live

| Item | Location | Protection |
|---|---|---|
| Native API key | App SharedPreferences | Android Keystore + AES/GCM, a prefixed 12-byte IV, 128-bit authentication tag and Base64 encoding. The existing format is retained. Missing, temporarily unavailable and unreadable credentials have distinct states; reading never creates a replacement decryption key |
| Native API key in manual exports | Excluded by default; optionally included in the password-protected v5 archive | The current device must decrypt it successfully before export. Historical records containing only another device's Keystore ciphertext may remain unreadable |
| Conversations | `Documents/dshdata` (public) or private dir | ⚠️ In the public location, **any app with storage permission can read them**. That's the price of surviving uninstall |
| New v5 archives | Destination chosen through the Android document picker, plus a private verified copy | Password-derived encryption with AES-GCM; authentication must finish before restore. A destination that cannot be read back is not reported as verified |
| Runtime/project credentials | Files such as `.credentials.yaml` and `.env` in the selected data locations | May contain plaintext and may be included in the encrypted export scope. Excluding the native API key does not scan all files for secrets |
| Bridge/LAN grants and maintenance state | Device-private records and memory | A user archive cannot grant current-device authorization. Executable declarations and unknown plugin data are quarantined; authorized code sharing the app UID is not isolated from other app data |

This change adds no telemetry or log uploads. Updates and dependency installation contact the selected repository, registry or mirror. dsh contacts the configured model provider, and enabled plugins can make their own network requests. The updater's destinations are not a network sandbox for container code.

The device bridge listens on loopback `:3090` and still requires a token because other local apps can connect. Optional LAN sharing listens on `0.0.0.0:3081` with its own authentication and applicable local-network permission. It uses HTTP, not TLS; a token does not prevent traffic capture on an untrusted network. It does not provide CONNECT or arbitrary-destination proxying.

Request headers and connection admission have separate finite budgets. SSE/WebSocket resources are separated from short requests, and payloads remain streaming. Stopping LAN or entering maintenance closes the current connections. See the [stability acceptance record](stability-acceptance.md) for measured limits and remaining device gaps.

Plugin review checks metadata, content and actual dependencies without importing the plugin. Dependency lifecycle scripts and pnpmfile hooks are disabled. Explicit review is required before activation. A quarantine directory or separate profile is not a malicious-code sandbox, and successful loading is not a security certification.

---

## Verifying the APK you installed

Sideloading an APK from GitHub makes provenance the thing most worth checking:

```bash
# Use the actual filename in the corresponding delivery record
sha256sum -c dsha-0.1.5-rc2.1.apk.sha256
apksigner verify --verbose --print-certs dsha-0.1.5-rc2.1.apk
```

Local delivery checks the historical certificate fingerprint `e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5`. A checksum establishes byte identity, not publisher identity by itself. Local builds do not automatically have a GitHub attestation; check the actual artifact rather than assuming one exists.

---

## Known weaknesses

Not hidden:

| Weakness | Status |
|---|---|
| `danger-full-access` | Android sepolicy blocks bubblewrap, so dsh has no sandbox. The agent has full control inside the container |
| The device gate is not an OS sandbox | The supplied bridge uses an allowlist; arbitrary container code and custom clients remain governed by Android's actual permissions |
| Historical plaintext archives | Old tar.gz archives and manually copied data may remain public. New encrypted exports do not modify or delete those files |
| Credentials in use | Keystore protects the stored native record; credentials passed into runtime environments or configuration may be readable by authorized code sharing the app UID |
| `/sdcard` mount | A mount does not grant storage access. After a grant, container code can access the permitted shared-storage scope |
| Signing key needs rotation | Releases are signed with a debug keystore for historical reasons — replacing it would break upgrades for every existing user. Rotation via APK Signature Scheme v3 is scheduled separately |

Found something else? Open an issue, or bring it to QQ group 975836806. Security reports go first.
