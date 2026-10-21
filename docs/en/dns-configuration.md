# DNS configuration and resolver boundaries

Verified: 2026-10-21 against the build190 source underlying the F10 documentation baseline. This page explains Configuration's DNS resolution policy. See [ports and networking](ports-and-networking.md) for Web, the capability bridge, LAN, and ADB endpoints, and [runtime engines](runtime-engines.md) for execution.

DNS settings address name-resolution compatibility. They are not system VPN, proxy, network-permission, or HTTP-retry switches.

## The three modes

`ConfigStore.getDnsMode()` reads the setting, defaulting to `auto`. `ResolverConfig.mode()` recognizes `ipv4` and `native`; other values become `auto`. The UI offers “Automatic (recommended)”, “IPv4”, and “Native”.

| Value | Node lookup compatibility patch | DSHA-owned glibc `no-aaaa` section |
|---|---|---|
| `auto` | Enables a narrow IPv4 resolution retry | Does not add it; removes the previous DSHA section |
| `ipv4` | Enables the same narrow retry | Adds it unless the user already supplied the directive |
| `native` | Does not replace Node lookup | Does not add it; removes the previous DSHA section |

“Native” means the current Ubuntu/process resolver behavior; it does not redirect every query to Android Private DNS. All modes still use the same guest `resolv.conf` reconciliation. They do not automatically change the phone's global DNS, VPN, or Android system settings.

## Automatic mode retries only specific Node lookups

[dns-compat.cjs](../../app/src/main/assets/dns-compat.cjs) is installed through Node preloading. It wraps `dns.lookup`, `dns.promises.lookup`, and `util.promisify(dns.lookup)`, synchronizes built-in ESM exports, and uses a Symbol marker to prevent nested wrapping on repeated loading in one process.

A lookup proceeds as follows:

1. Call the original lookup with its original arguments, retaining normal dual-stack results.
2. Consider compatibility retry only for `EAI_AGAIN` or `EAI_FAIL`.
3. Permit one family `4` retry only if family is unspecified or numeric `0`.
4. Return the IPv4 result on success; otherwise return the original first error.

Explicit family `4` / `6`, and string IPv4 / IPv6 family selections, do not receive another downgrade. `ENOTFOUND`, argument errors, and other errors do not enter this branch. Options such as `all`, `hints`, and `order` survive adding family `4`; the caller's object is not mutated. Successful normal IPv6 lookup causes no additional IPv4 request.

The patch does not call `setDefaultResultOrder('ipv4first')`: automatic mode neither sorts all results IPv4-first nor disables IPv6. It does not replace `dns.resolve*()`, independent Resolver instances, or application DoH clients. Investigate a plugin's actual calls if it uses a custom lookup, clears its environment, or creates another resolver stack.

Node documents that lookup uses system `getaddrinfo`, while resolve functions use the DNS protocol directly; their configuration and behavior differ. See the [Node 24 DNS documentation](https://nodejs.org/download/release/v24.19.0/docs/api/dns.html#implementation-considerations).

## IPv4 mode additionally changes the glibc stub resolver

`ipv4` appends this owned section to guest `/etc/resolv.conf`:

```text
# DSHA managed IPv4 DNS compatibility
options no-aaaa
```

This is an AAAA-query compatibility setting for programs using that guest glibc resolver. It does not disable Android IPv6 globally or constrain every connection's address family. glibc still permits IPv6 nameservers and IPv6 data from `/etc/hosts`; explicit IPv6 address connections do not become IPv4. See the [glibc no-aaaa design discussion](https://sourceware.org/pipermail/libc-alpha/2022-June/139986.html).

Whether curl, Python, git, or another tool is affected depends on its build and resolver. Calls using glibc system resolution may be affected; c-ares, independent DNS libraries, DoH, caches, and remote proxy resolution require separate examination. Direct-query tools such as `dig` are outside the Node lookup patch. Android Java networking and external resolution inside WebView/Gecko are not collectively taken over by it.

Use IPv4 mode carefully on AAAA-only sites, IPv6-dependent networks, and DNS64 environments. One request timeout does not prove an AAAA problem. This setting does not fix certificates, routes, rate limits, provider rejection, model names, or API Keys.

## Generating and preserving guest resolv.conf

[ResolverConfig.java](../../app/src/main/java/com/deepseekharness/app/util/ResolverConfig.java) owns reconciliation; [RuntimeTools.java](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java) performs file operations. Android reads DNS addresses from the active network's `LinkProperties.getDnsServers()`. Only validated numeric IPv4/IPv6 addresses are accepted and deduplicated; this operation performs no hostname lookup. It does not run Android's `dnsresolver` shell command to modify system services.

Existing guest content is processed in this order:

1. Remove DSHA's previous IPv4 compatibility section.
2. Distinguish user nameservers from the marked DSHA device-DNS section.
3. If user nameservers exist, preserve them and remove the owned device-DNS section.
4. Otherwise update the owned section when valid device DNS is available.
5. If device DNS is temporarily unavailable, preserve the last valid owned section.
6. With neither a valid previous section nor current device DNS, do not inject a public fallback resolver.
7. Finally, add the owned compatibility section only in `ipv4` mode and only if the user has not supplied `no-aaaa`.

`search`, other `options`, comments, and user nameservers are not replaced with a template. User-written `options no-aaaa` remains in `auto` / `native`. If AAAA suppression persists after returning to automatic, first establish who owns that directive; returning to automatic does not delete every matching option.

Symlinks and other special files are not followed for writing; a missing parent directory is not created for this path. This prevents guest absolute links from redirecting writes to host or external targets. Only a changed reconciliation result is written; repeated preparation is idempotent.

## Where environment variables enter processes

`RuntimeTools.applyEnvironment` sets `DSHA_DNS_MODE`. If the managed script exists, it prepends the following to existing `NODE_OPTIONS`:

```text
--require=/usr/local/share/dsha/dns-compat.cjs
```

Other `NODE_OPTIONS` are retained, and the same require is not duplicated. Native mode may still contain the require: the script sees `native` and leaves lookup unchanged. A filename in `NODE_OPTIONS` therefore does not prove retry is enabled.

[RuntimeLauncher.java](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeLauncher.java) injects the environment through `ProcessBuilder`; its command entry is `/bin/bash -c`. This is not a login shell and need not read `/etc/profile` or the user's `.profile`. Writing DNS variables only to a profile does not replace this injection.

PTY uses the launcher environment through `TerminalRuntime`, then enters interactive login bash. The simple terminal also starts with launcher environment and creates its own shell session. User scripts can subsequently change either environment; initial injection is not immutable. Node children usually inherit it, but explicit plugin env replacement or removal of `NODE_OPTIONS` must be checked separately. Native executables do not execute a Node CJS preload; only glibc file configuration may affect them.

The Web startup observer also adds a separate `--import`. DNS changes must preserve the observer and bridge preloads. Actual environment/command-chain regression entry points appear below.

## Saving, restarting, and switching networks

Open Settings → Configuration → DNS resolution policy, choose a mode, and save configuration. The local preference is `dns_mode`. Saving does not inject a preload into an existing Node process or restart every terminal. Stop Web normally and start it to verify a new main-Web mode. Normally close and reopen any terminal that requires the new environment.

`RuntimeTools.prepare` reconciles `resolv.conf` before its managed-asset fast-path return. `RuntimeLauncher.builder` also attempts reconciliation when creating a command process. Network-service queries occur outside the asset preparation lock, avoiding a long Binder query under that lock. Network lookup failure does not require connectivity for every startup. File-write failures produce a warning / `DNS_PREPARE` diagnostic.

This is not a promise to monitor network changes continuously and reconfigure every old process. New managed launches read current DNS after a Wi-Fi/mobile-network change. Existing processes, resolver caches, and HTTP pools may retain old state; record process start times during diagnosis. Refreshing a page does not rebuild its backend environment.

## Backup and restore scope

The new portable native-preference projection explicitly includes `dns_mode`; see [PortablePreferenceProjection.java](../../app/src/main/java/com/deepseekharness/app/backup/PortablePreferenceProjection.java). Older `ConfigStore.exportBackupSettings` / `exportPortableSettings` whitelists do not. Do not claim every historical export path contains DNS mode: inspect the actual archive scope and reader.

A portable policy name does not make the old device's DNS addresses appropriate on another device. A new launch reconciles the guest file against its current network and existing user configuration. It does not restore Android Private DNS, VPN, network permission, or authorization credentials from the source phone. Do not clear personal settings, conversations, or Ubuntu to repair DNS.

## Choosing a mode and investigating failures

| Symptom / need | First assessment |
|---|---|
| Normal dual-stack network | Keep automatic; successful IPv6 is not downgraded |
| Node `EAI_AGAIN` / `EAI_FAIL` | Check actual mode, preload, system resolution, and the one IPv4 retry |
| A demonstrated AAAA compatibility issue in glibc queries | Explicitly choose IPv4, then stop and restart normally to verify |
| Observe behavior without the Node wrapper | Choose native and compare while preserving user resolver settings |
| `ENOTFOUND`, HTTP 401, or TLS error | Investigate name, authentication, or certificate respectively; do not automatically change DNS or replay business work |
| One failing plugin | Inspect its resolver library, proxy, and environment inheritance |

Main's launch records and diagnostics distinguish environment preparation, backend exit, and page readiness. Failure before resolution differs from an HTTP operation already sent with an unknown result. The patch retries lookup only; it never replays HTTP, model requests, tool calls, or file writes. Timeouts, restricted networks, and `EXECUTION_UNKNOWN` do not authorize switching policy/channel and executing again. To restore the normal default, explicitly choose automatic, save, and restart normally, keeping the original failure for comparison.

## Maintenance and test map

Mode changes require corresponding updates to ConfigStore, ResolverConfig, RuntimeHostPorts.Settings, and the three configuration choices. File changes must cover owned-section removal, user-DNS priority, temporary offline operation, numeric IPv6, and symlink skipping. Node patch changes must cover callback, Promise, promisify, family, `all` / options, and reload idempotence. One failed resolution must make at most two underlying lookup calls while the business HTTP operation is still sent only once. Launch changes must inspect main Web, ordinary commands, PTY, simple terminals, and children, not just login profiles.

Existing entry points, read during translation without rerunning tests or connecting a device:

- `app/src/test/java/com/deepseekharness/app/util/ResolverConfigTest.java`.
- `app/src/test/java/com/deepseekharness/app/runtime/RuntimePackageCommandsAndroidTest.java`.
- `tools/test-dns-compat.mjs`, including a Linux native getaddrinfo fixture skipped on other platforms.
- `tools/test-dns-compat-device.mjs` and `tools/dns-compat-fixture.c`; a name containing “device” does not establish phone acceptance.
- `tools/test-build190-command-environment.py`, requiring a specified Linux Node and isolated host environment to inspect the actual command chain.

Passing these regressions does not replace real connectivity results for a carrier, VPN, Private DNS configuration, or model provider.
