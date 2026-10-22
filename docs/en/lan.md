# LAN access, authentication, and failure diagnosis

Verification date: 2026-10-21. Source: the complete corresponding Chinese maintenance guide and the LAN configuration, listener, and authentication implementation in the current build190 working tree. Published F10 `087724c` is a reference baseline. This page does not record phone or real-network testing for the current round, and does not establish final F12 release status.

F12 addition: if process identity becomes UNKNOWN within the same generation, the final publication gate rejects a late LAN cookie. Existing authentication, listener and connections for that generation are retired, so keep-alive cannot reopen them using an old cookie. A late observation of an older generation cannot retire a newer connection. This sends no signal to the Web process, does not release the unknown-exit barrier and does not clean user data.

## Enabling and revoking access

**Configuration → Allow LAN access** is off by default. After the enabled setting is saved, the current Web run must have authenticated its actual port. Only then does the app establish a LAN proxy to that backend and display a token-bearing access link. Starting with Android API 37, local-network permission is also required; earlier systems do not request this new permission. The DSHA toggle cannot override a system-permission failure.

Share only the app's currently displayed link with trusted browsers that need access. The proxy listens on `0.0.0.0:3081`, with this run's `127.0.0.1:<actual-Web-port>` as its backend. The backend is not fixed to 3080, and the 3090 device bridge is not exposed by enabling LAN. See [ports and networking](ports-and-networking.md).

Disabling the toggle revokes this run's token and closes the external listener and its connections. Revoking LAN does not clear the local DSH cookie. After reenabling or starting a new service run, use the new link rather than an old token from a screenshot or bookmark. The toggle, stored token, and current in-memory service identity are different states. A historical persistent value cannot replace a valid token for this run.

## Reuse verified authentication after strict recovery

F12 host reconstruction checks the private record, APK/descriptor/root, and process identity, performs actual HTTP authentication, then verifies the process in the kernel a second time. Success adopts only the same still-active Web process. LAN publication reuses the cookie verified by that strict recovery. It does not exchange the token again, call the ordinary new-start `afterAuthenticated` path, or replay startup, migration, or business work.

Publication still requires the current generation, original authentication URL, and actual port. `WebLifecycleController.publishRecoveredLan` and `LanAuthBridge.publishVerified` recheck the exact snapshot; a late or mismatched result cannot bind the proxy. LAN disabled means no listener starts. A LAN bind failure records a LAN diagnostic without revoking an already successful local Web adoption. The cookie comes from that actual authentication, not a new grant inferred from old storage. Failed strict adoption preserves UNKNOWN and its original stop barrier; LAN reachability cannot prove recovery is safe.

See [WebSessionRecovery](../../app/src/main/java/com/deepseekharness/app/core/WebSessionRecovery.java), [WebLifecycleController](../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java), and [LanAuthBridge](../../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java). The [F12 record](releases/v0.2.1-rc.2-build190-fix12-20261021.md) describes the batch's known scope. The delivery owner freezes final software/APK counts and publication state; this page does not predeclare them.

## Plaintext and authentication boundaries

[LanProxyService](../../app/src/main/java/com/deepseekharness/app/LanProxyService.java) uses ordinary HTTP without TLS. Traffic, conversations, and tokens may be visible to observers on the same network. Use a trusted network; this is not a secure gateway for exposing the service to the public internet. See the [security model](security-model.md) for platform context and project boundaries.

Each run generates a secure random 32-byte token. The exact `token=` query parameter or `dsha_lan` cookie can authenticate. [LanAuth](../../app/src/main/java/com/deepseekharness/app/LanAuth.java) compares in constant time and does not accept an arbitrary custom header as a bypass. The first valid token link returns 303 and sets an `HttpOnly; SameSite=Strict; Max-Age=43200` cookie. The redirect preserves the original path and other query parameters, removing only the LAN `token`. For example, an OAuth callback's code/state must not be redirected to the root path. The cookie's 12-hour lifetime cannot extend the server-side lifetime of the current token. After stopping or revocation, the old cookie remains invalid.

The proxy uses the backend DSH token/cookie only against the verified local target. It does not directly expose the 3090 `X-Token` or backend credentials as browser-forwardable credentials. Request/response handling, WebSocket upgrades, chunking, and connection quotas remain governed by the bounded HTTP-processing chain.

F11 preserved an unknown backend port of 0 as not ready rather than silently falling back to 3080. If authentication within the same generation supplies a new actual port, the corresponding proxy target is replaced. An ordinary business 401/403 is forwarded for that request; an arbitrary business denial does not invalidate the entire BrowserAuth run. Idle header-reading connections and short requests have separate limits, preventing slow headers from filling the business-dispatch pool.

Interface/address-set changes are checked roughly once per second. Old connections and their companions are closed while the wildcard listener and current LAN token remain. A rapid network replacement with the same interfaces and addresses is not guaranteed to be observed. [LanAddressPolicy](../../app/src/main/java/com/deepseekharness/app/util/LanAddressPolicy.java) chooses an up, non-loopback, non-P2P RFC1918 display candidate, preferring wlan/wifi and then eth. Without a suitable candidate, it does not fall back to another private address. The displayed address is a conservative candidate, not proof of reachability.

## Diagnose failures in this order

| Symptom | Meaning and response |
|---|---|
| No shareable LAN link | Check the toggle, system local-network permission, and whether this Web run is genuinely ready and authenticated. |
| 401 with `X-Dsha-Lan-Reason` | The token is absent, stale, or wrong. The response clears the old cookie. Reopen the app's current link. |
| 503 | The backend is not authenticated/ready or its target is unavailable. Reentering an old token cannot resolve it. |
| A browser remains on an old page | An old cookie or service link does not identify the new run. Reopen the current link. |
| Connection fails despite being on the same network | Check the phone IP, Wi-Fi isolation, VPN, firewall, and system permission. A network fault is not necessarily an authentication failure. |
| Local Web works but LAN fails | These entry points are independent. Preserve the working local state and investigate the proxy and network. |

The service redacts LAN logs before writing its dedicated log and logcat. Do not publish complete token-bearing links in logs or issue screenshots. Verify external-listener closure, connection shutdown, and backend-generation binding separately. A changed toggle color alone does not prove revocation took effect.

## Source and verification

Configuration entry points are [ConfigFragment](../../app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java) and [LocalNetworkAccess](../../app/src/main/java/com/deepseekharness/app/bridge/LocalNetworkAccess.java). Authentication state is managed by [LanAuthBridge](../../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java) and [LanTokenState](../../app/src/main/java/com/deepseekharness/app/util/LanTokenState.java). Failure responses are defined by [LanFailureResponse](../../app/src/main/java/com/deepseekharness/app/util/LanFailureResponse.java). Related tests are [LanAuthTest](../../app/src/test/java/com/deepseekharness/app/LanAuthTest.java), [LanAuthBridgeTest](../../app/src/test/java/com/deepseekharness/app/core/LanAuthBridgeTest.java), and [LanTokenStateTest](../../app/src/test/java/com/deepseekharness/app/util/LanTokenStateTest.java). Source-level tests do not replace real Wi-Fi, system-permission, network-isolation, or long-stream device validation.

Historical F11 evidence for [test-lan-proxy.py](../../tools/test-lan-proxy.py) reports 49 passing cases on JDK 17, combining the actual production proxy, host loopback sockets, and logic regressions. The pre-fix comparison with five failures among 44 cases is retained. Android Context, permissions, and logging were host stubs. The checks used no device, ADB, real Wi-Fi switching, or running APK. Unknown OEM, hotspot, and VPN compatibility remains unverified. The delivery receipt must bind the exact final execution scope; this paragraph is historical host software evidence, not phone LAN acceptance or a final F12 release result.
