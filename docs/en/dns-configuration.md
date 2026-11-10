# DNS configuration and resolver boundaries

This page explains Configuration's DNS resolution policy. See [ports and networking](ports-and-networking.md) for Web, the capability bridge, LAN, and ADB endpoints, and [runtime engines](runtime-engines.md) for execution.

DNS settings address name-resolution compatibility. They are not system VPN, proxy, network-permission, or HTTP-retry switches.

## The three modes

`ConfigStore.getDnsMode()` reads the setting, defaulting to `auto`. `ResolverConfig.mode()` recognizes `ipv4` and `native`; other values become `auto`. The UI offers “Automatic (recommended)”, “IPv4”, and “Native”.

| Value | Node lookup compatibility patch | DSHA-owned glibc `no-aaaa` section |
|---|---|---|
| `auto` | Enables a narrow IPv4 resolution retry | Does not add it; removes the previous DSHA section |
| `ipv4` | Enables the same narrow retry | Adds it unless the user already supplied the directive |
| `native` | Does not replace Node lookup | Does not add it; removes the previous DSHA section |

“Native” means the current Ubuntu/process resolver behavior; it does not redirect every query to Android Private DNS. All modes still use the same guest `resolv.conf` reconciliation. They do not automatically change the phone's global DNS, VPN, or Android system settings.

Automatic and IPv4 modes also install a marked IPv4-preferred glibc address precedence table in guest `/etc/gai.conf` when it contains no active user configuration. This can shorten sequential connection attempts, including Python `urllib`, when IPv6 destinations time out; IPv6 addresses remain available as fallback. glibc applies other address-selection rules too, so the table is not a guarantee that every IPv4 address precedes every IPv6 address. It is not Happy Eyeballs and cannot fix a blackholed IPv4 route. Native mode removes the exact unmodified DSHA section. Any active user directive, edited DSHA section, symbolic link, or special file is preserved. Existing processes may cache configuration and should be restarted normally. Non-glibc resolvers, application sorting and proxy resolution may not use this table.

## Automatic mode retries only specific Node lookups

[dns-compat.cjs](../../app/src/main/assets/dns-compat.cjs) is installed through Node preloading. It wraps `dns.lookup`, `dns.promises.lookup`, and `util.promisify(dns.lookup)`, synchronizes built-in ESM exports, and uses a Symbol marker to prevent nested wrapping on repeated loading in one process.

A lookup proceeds as follows:

1. Call the original lookup with its original arguments, retaining normal dual-stack results.
2. Consider compatibility retry only for `EAI_AGAIN` or `EAI_FAIL`.
3. Permit one family `4` retry only if family is unspecified or numeric `0`.
4. Return the IPv4 result on success.
5. If the error is still `EAI_AGAIN` / `EAI_FAIL`, ask the Android system resolver once through the local capability bridge `/app/net/resolve` (see "Host resolver fallback"); otherwise return the original first error.

Explicit family `4` / `6`, and string IPv4 / IPv6 family selections, do not receive another downgrade. `ENOTFOUND`, argument errors, and other errors do not enter this branch. Options such as `all`, `hints`, and `order` survive adding family `4`; the caller's object is not mutated. Successful normal IPv6 lookup causes no additional IPv4 request.

The Node patch does not call `setDefaultResultOrder('ipv4first')` or disable IPv6; the separate guest glibc precedence table can affect system lookup ordering. The patch does not replace `dns.resolve*()`, independent Resolver instances, or application DoH clients. Investigate a plugin's actual calls if it uses a custom lookup, clears its environment, or creates another resolver stack.

Node documents that lookup uses system `getaddrinfo`, while resolve functions use the DNS protocol directly; their configuration and behavior differ. See the [Node 24 DNS documentation](https://nodejs.org/download/release/v24.19.0/docs/api/dns.html#implementation-considerations).

## Host resolver fallback (experimental)

> Experimental: the network-switch refresh, host resolver fallback and `/app/net` diagnosis below are a best-effort fix to reduce "no container internet after switching to mobile data". Reliability is not guaranteed across devices, ROMs, carriers, VPNs or private-DNS setups; verified scope is recorded per release.

A guest resolv.conf can only name plain-DNS servers. After a network switch the old servers may be unreachable, and Android private DNS (DoT), carrier DNS64 or VPN split routing cannot be expressed there; guest glibc then times out while Android itself still resolves. The fallback covers only that case:

- It triggers only on `EAI_AGAIN` / `EAI_FAIL`; definite results such as `ENOTFOUND` are not second-guessed.
- It sends one host request and never replays HTTP, model requests or tool calls; IP literals, `localhost` and single-label names are not sent.
- An explicit family is requested as-is, never downgraded; `all` and `order: 'ipv6first'` keep the result shape.
- Concurrent lookups of one host are coalesced, failures are negatively cached for 10 s, at most 4 host requests run at once, and successes are not cached.
- Credentials and port are read on every call from the private regular files `/root/.dsh/.bridge_headers` and `.bridge_port` without following links. An older App (the endpoint returns `[NO_CMD]`) or an unavailable bridge yields the original error.
- The host side queries `DnsResolver` on the current default network with a 4 s limit and at most 2 concurrent lookups, returns numeric addresses only, opens no connection and does not log host names.

`native` mode or `DSHA_DNS_HOST_FALLBACK=0` disables it. curl, git, Python and other programs that do not use Node lookup are not covered and rely on the resolv.conf refresh below.

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

[ResolverConfig.java](../../app/src/main/java/com/deepseekharness/app/util/ResolverConfig.java) owns reconciliation; [RuntimeTools.java](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeTools.java) performs file operations. Android DNS addresses come from the default network's `LinkProperties.getDnsServers()` through `device/NetworkStateMonitor`: queried directly at launch, then followed through the default-network callback. Scoped IPv6 link-local addresses (`%iface`) are not written. Only validated numeric IPv4/IPv6 addresses are accepted and deduplicated; this operation performs no hostname lookup. It does not run Android's `dnsresolver` shell command to modify system services.

The legacy bundled rootfs contains exactly two lines, `8.8.8.8` followed by `223.5.5.5`. After removing DSHA-owned sections, only a byte-for-byte match to that default is migrated to device DNS, and only when a valid device server is available. Otherwise it is retained. Any other content, including a comment, different line endings, or reordered servers, remains a user configuration. To deliberately keep those two servers, add a comment to distinguish the file from the original default. Using device DNS addresses does not reproduce Android Private DNS's encrypted resolver behavior.

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

Experimental: on a Wi-Fi / mobile-network switch, `runtime/ResolverRefresher` subscribes to the same monitor and, once device DNS becomes a new non-empty list (coalesced over 400 ms), rewrites each guest resolv.conf this process prepared using the same reconciliation rules:

- Only the DSHA-managed block is replaced; user nameservers, symlinks and special files are untouched.
- An empty DNS list during a brief disconnect writes nothing and keeps the last valid block; an identical list is not rewritten.
- Exclusive data maintenance defers the write and retries after 5 s; the write runs under the asset-preparation lock.
- resolv.conf is not part of the managed-asset stamp, so a switch does not force a full asset preparation on the next launch.

Guest glibc (2.26+) rereads the file after it changes, so running Node, curl, git and pip need no restart. Established TCP/WebSocket connections still drop on a switch; that is transport behavior and applications must reconnect. The refresh ensures the reconnect can resolve. Application-level caches, c-ares `dns.resolve*()` and DoH clients are out of scope.

## Backup and restore scope

The new portable native-preference projection explicitly includes `dns_mode`; see [PortablePreferenceProjection.java](../../app/src/main/java/com/deepseekharness/app/backup/PortablePreferenceProjection.java). Older `ConfigStore.exportBackupSettings` / `exportPortableSettings` whitelists do not. Do not claim every historical export path contains DNS mode: inspect the actual archive scope and reader.

A portable policy name does not make the old device's DNS addresses appropriate on another device. A new launch reconciles the guest file against its current network and existing user configuration. It does not restore Android Private DNS, VPN, network permission, or authorization credentials from the source phone. Do not clear personal settings, conversations, or Ubuntu to repair DNS.

## Choosing a mode and investigating failures

| Symptom / need | First assessment |
|---|---|
| Normal dual-stack network | Automatic prefers IPv4 through the guest glibc table; select native to restore the system's ordering |
| Node `EAI_AGAIN` / `EAI_FAIL` | Check actual mode, preload, system resolution, and the one IPv4 retry |
| A demonstrated AAAA compatibility issue in glibc queries | Explicitly choose IPv4, then stop and restart normally to verify |
| Observe behavior without the Node wrapper | Choose native and compare while preserving user resolver settings |
| `ENOTFOUND`, HTTP 401, or TLS error | Investigate name, authentication, or certificate respectively; do not automatically change DNS or replay business work |
| One failing plugin | Inspect its resolver library, proxy, and environment inheritance |

Main's launch records and diagnostics distinguish environment preparation, backend exit, and page readiness. Failure before resolution differs from an HTTP operation already sent with an unknown result. The patch retries lookup only; it never replays HTTP, model requests, tool calls, or file writes. Timeouts, restricted networks, and `EXECUTION_UNKNOWN` do not authorize switching policy/channel and executing again. To restore the normal default, explicitly choose automatic, save, and restart normally, keeping the original failure for comparison.

## One-command diagnosis (experimental)

```sh
curl -s -H @/root/.dsh/.bridge_headers "http://127.0.0.1:$(cat /root/.dsh/.bridge_port)/app/net"
```

The `DSHA_NET_V1` block reports the default network transport, VPN, validation, whether the system blocks DSHA on it, IP stack, device DNS, private DNS, DNS mode, the last refresh result, and for each rootfs `resolv_source` (managed / user / legacy / none) and `resolv_state` (current / stale / user / empty). It contains no local IP, SSID or carrier name. Comparing `getent hosts NAME` with `/app/net/resolve?host=NAME` separates a stale container DNS from a host without connectivity. `blocked=true` means the system has cut DSHA off that network (mobile-data permission, data saver and similar) and must be fixed in system settings.

## Maintenance and test map

Mode changes require corresponding updates to ConfigStore, ResolverConfig, RuntimeHostPorts.Settings, and the three configuration choices. File changes must cover owned-section removal, user-DNS priority, temporary offline operation, numeric IPv6, and symlink skipping. Node patch changes must cover callback, Promise, promisify, family, `all` / options, and reload idempotence. One failed resolution must make at most two underlying lookup calls plus one host request while the business HTTP operation is still sent only once. DeviceBridgeService and ResolverRefresher share NetworkStateMonitor and must not register their own callbacks. Launch changes must inspect main Web, ordinary commands, PTY, simple terminals, and children, not just login profiles.

Test locations follow; results of any particular run are recorded with that run:

- `app/src/test/java/com/deepseekharness/app/util/ResolverConfigTest.java`, including the Wi-Fi → mobile → offline → Wi-Fi sequence and describe.
- `NetworkSnapshotTest`, `ResolverRefreshPolicyTest` and `HostLookupTest` in the same directory.
- `app/src/test/java/com/deepseekharness/app/runtime/RuntimePackageCommandsAndroidTest.java`.
- `tools/test-dns-compat.mjs`, including a Linux native getaddrinfo fixture skipped on other platforms.
- `tools/test-dns-compat-device.mjs` and `tools/dns-compat-fixture.c`; a name containing “device” does not establish phone acceptance.
- `tools/test-build190-command-environment.py`, requiring a specified Linux Node and isolated host environment to inspect the actual command chain.

Passing these regressions does not replace real connectivity results for a carrier, VPN, Private DNS configuration, or model provider.
