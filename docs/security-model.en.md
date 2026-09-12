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
| Dialer, SMS, contacts, location, camera, microphone | ❌ No | DSHA never requests these permissions — verify it yourself in system settings |

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

- Requires a rooted device (KernelSU / Magisk). DSHA neither provides nor requests root
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
| DeepSeek API key | App SharedPreferences | Android Keystore encryption (AES/CBC). **The key never leaves the Keystore**; other apps cannot read it |
| API key inside backups | `.dsh/.dsha-apikey` in the archive | Same Keystore key. **Undecryptable after a device change** — by design, not a bug. You can turn off "include key in backup" in Config |
| Conversations | `Documents/dshdata` (public) or private dir | ⚠️ In the public location, **any app with storage permission can read them**. That's the price of surviving uninstall |
| Backup archives | `Download/DSHA/` | ⚠️ Public directory, and they contain **all** your conversations. Think before sharing one |
| dsh's own credentials | `.dsh/.credentials.yaml` | Deliberately kept private, never migrated to the public area — but it **does go into backups** |
| Bridge token (:3090) | `.dsh/.bridge_token` | Private directory, mode 600. **Excluded from backups** — it belongs to this machine |

**We collect nothing.** No telemetry, no analytics, no crash reporting. The only outbound
requests are: GitHub API when you tap check-for-updates, raw.githubusercontent.com when
scripts hot-update, and whichever mirror you picked during installation. API calls go from
dsh straight to DeepSeek and never pass through anything of ours.

---

## Verifying the APK you installed

Sideloading an APK from GitHub makes provenance the thing most worth checking:

```bash
# 1. Hash — proves the file wasn't altered
sha256sum -c deepseekharness-arm64-vX.Y.Z.apk.sha256

# 2. Build provenance — proves it came from this repo's CI at that tag,
#    not from someone else's repackage
gh attestation verify deepseekharness-arm64-vX.Y.Z.apk --repo qiannianhuanxiang/DSHA
```

The signing certificate fingerprint is verified on every release run, and a mismatch
**aborts the release** — a wrongly signed package can't even be installed over an existing
one (Android only allows same-signature upgrades), so shipping it would be worse than
failing the build.

---

## Known weaknesses

Not hidden:

| Weakness | Status |
|---|---|
| `danger-full-access` | Android sepolicy blocks bubblewrap, so dsh has no sandbox. The agent has full control inside the container |
| The gate is bypassable | A denylist over command text: guards against slips, not deliberate evasion |
| Backups sit in a public directory | Every conversation, in plaintext, under `Download/DSHA/`, readable by any app with storage permission |
| `.credentials.yaml` goes into backups | dsh's credential file rides along into the public directory; whether to add an exclusion toggle is undecided |
| `/sdcard` reachable by default | The agent can read your gallery and downloads out of the box; there's no toggle yet |
| Signing key needs rotation | Releases are signed with a debug keystore for historical reasons — replacing it would break upgrades for every existing user. Rotation via APK Signature Scheme v3 is scheduled separately |

Found something else? Open an issue, or bring it to QQ group 975836806. Security reports go first.
