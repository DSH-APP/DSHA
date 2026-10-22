# Ports and networking maintenance

Verified: 2026-10-21. This page translates the build190 source review associated with the F10 documentation baseline. It explains how to use, diagnose, and maintain port handling. Current delivery identity and subsequent changes belong to the [release introduction](release-introduction.md).

The application runs DSH 0.2.1-rc.2. See [runtime engines](runtime-engines.md) and [DNS configuration](dns-configuration.md) for those separate concerns. A port, authentication credentials, and a Web generation are three distinct fields; none proves either of the others.

## Identify the endpoint first

| Purpose | Current address or source | Owner |
|---|---|---|
| DSH Web backend | `127.0.0.1:<actual port for this run>` | Automatic/manual Web port in Configuration |
| DeepSeek OAuth callback | `/oauth/callback` at the origin recorded when login starts; a temporary route on the current DSH WebServer, forwarded through the proxy for LAN access | Official account login state machine |
| Android capability bridge | `127.0.0.1:3090`, with an additional attempt at `::1:3090` | `HttpShellService` |
| LAN Web proxy | `0.0.0.0:3081` | LAN access switch in Configuration |
| Wireless ADB pairing | Temporary port shown by Android's pairing dialog | Device capability authorization / ADB pairing page |
| Wireless ADB connection | Android's wireless debugging page and discovery results | ADB connection management |
| ADB server on another computer | Local TCP 5037 by default; part of the computer's ADB connection | Outside the DSH Web service |

3081 and 3090 are reserved and cannot be selected as manual Web ports. `5555` is a historical ADB connection fallback, not a fixed port for modern Android wireless debugging. The computer's ADB server and the phone's adbd pairing/connection services are separate endpoints. See the [Android ADB architecture](https://developer.android.com/tools/adb#howadbworks) for the role of 5037.

DSHA device scripts use the `adb_shell_wifi` TLS client; they do not pass the Web port to ADB. Changing a Web port neither completes ADB pairing nor turns a pairing port into a Web address. In [Constants.java](../../app/src/main/java/com/deepseekharness/app/util/Constants.java), `DSH_WEB_PORT=3080` remains for compatibility with old configuration; it does not mean the current default is a fixed listener on 3080.

OAuth does not open a separate temporary listening port. The locked `@deepseek-ai/dsh-deepseek-account-platform` registers its callback through the current `webServer.register()` and places the origin recorded at login start into that attempt's `redirect_uri`. Stopping, restarting or changing the actual origin during login can invalidate the old callback address. Start a new login from the current page; do not rewrite the old callback port, replay its code or bypass state/PKCE. See the [dependency lock](../../tools/dsh-runtime/package-lock.json) and [real dynamic-port callback test](../../tools/test-deepseek-native-oauth.mjs). Plugin-list blocking and an invalid old callback are separate conditions; a connection-timeout screenshot alone cannot identify which occurred.

## Selection in automatic mode

Web port configuration supports `auto` and `fixed`. Automatic mode passes request value `0` to the selector:

1. Try the previously successful actual port remembered locally for this request, if it is valid, unreserved, and locally bindable.
2. Otherwise pass `--port 0` to DSH.
3. DSH lets the operating system allocate a port when it actually listens.
4. Record the actual port only after a valid official authentication startup line from this process is read.

Automatic mode promises neither a different nor an identical port on each run. It does not choose a random number, close a probing socket, and assume continued ownership. Probing a remembered port is also not proof that the later listener will succeed: another process can win the race. The selector only attempts a local bind; it does not identify the occupying process or send credentials to it.

Implementation:

- [WebPortSettings.java](../../app/src/main/java/com/deepseekharness/app/util/WebPortSettings.java): policy and migration.
- [WebPortPolicy.java](../../app/src/main/java/com/deepseekharness/app/util/WebPortPolicy.java): availability and authenticated-port checks.
- [WebLifecycleController.java](../../app/src/main/java/com/deepseekharness/app/core/WebLifecycleController.java): selection, launch, and publication of this run's result.

## Manual ports, legacy 3080, and fallback memory

Manual mode accepts 1–65535 except 3081 and 3090. It stores a preference, not a guarantee that the port can listen. If the preferred port is occupied before launch, selection first tries the locally remembered successful fallback associated with that preference. If that is unavailable too, the selector returns `0` for dynamic listening. Success on a fallback does not overwrite the user's preferred value.

Old configuration without an explicit `web_port_mode` is interpreted as follows:

- Legacy 3080, a missing/invalid value, or a reserved port becomes automatic mode.
- Any other valid legacy port remains manual.
- A valid explicit `auto` or `fixed` is respected.

An explicit new choice of `fixed` + 3080 is still allowed. Migrating the old default to automatic does not prohibit 3080. Save, stop Web normally, and start it again to apply the policy to a new listener. Editing the field does not move an already running page.

## Bind authentication to this run's actual port

The production launch command includes `--host 127.0.0.1 --port <selected value>`. `0` requests allocation; it is not a browsable port. The actual address comes from this process's complete official `dsh web: ...` startup line. It must parse as exactly `http://127.0.0.1:<port>/?token=...`. With a fixed launch value, the returned port must also match that value.

Reserved ports, forged URLs in plugin output, partial token lines, or conflicting official lines cannot become a ready address. Do not send an old token to a sequence of unknown ports looking for a service that resembles DSH. An open port, HTTP 200, or a still-visible old page does not prove that a new Web process is ready. Waiting for authentication is not itself failure, and slow startup does not authorize replaying business work. Main distinguishes authenticated backend readiness from actual page readiness.

Related implementation:

- [WebLaunchCommand.java](../../app/src/main/java/com/deepseekharness/app/util/WebLaunchCommand.java).
- [DshAuthUrl.java](../../app/src/main/java/com/deepseekharness/app/util/DshAuthUrl.java).
- [WebOutputSession.java](../../app/src/main/java/com/deepseekharness/app/core/WebOutputSession.java).
- [LanAuthBridge.java](../../app/src/main/java/com/deepseekharness/app/core/LanAuthBridge.java).

## Bind races and the recorded retry boundary

Pre-launch fallback selection and dispatching another process after a bind failure are different operations. In the reviewed F10 source, `WebPortPolicy.retryBindRace` defines these conditions:

- The current listen request is greater than zero and has not already been retried.
- Authentication has not completed, and the conflict refers exactly to this requested port.
- This run's guest has been confirmed to have exited.
- `onlyBindConflict` additionally requires a single explicit official bind conflict without a plugin failure.

**The F10 production Java reviewed for the original page did not call these two retry predicates.** Unit tests for the predicates therefore did not establish an automatic production retry. That baseline had `choose` pre-launch probing and fallback/dynamic selection. Preserve an actual `EADDRINUSE` diagnostic and follow recovery and normal stopping before a user starts again. The current delivery's automatic-port changes must be read with the [release introduction](release-introduction.md) once their final scope is frozen.

Any integration of retry must include the lifecycle call, exit proof, and actual regression coverage. A timeout, unknown exit, or plugin error never authorizes replaying a launch or committed business work. Never kill an occupant by port, process name, or bare PID.

## F12 plugin-delay investigation and automatic ports

The native plugin helper executes guest commands; it does not use Web HTTP or WebRPC's current Connection. Web Plugin Manager uses the current Connection. In the controlled actual Host, enabling and disabling the official voice plugin retained the same HTTP object and port. Existing evidence identifies expensive synchronous content hashing blocking Node's event loop. It does not explain every phone symptom or prove that automatic ports caused the plugin freeze.

Automatic `--port 0` and reuse of a still-available local successful port remain. This symptom does not justify removing automatic mode. An actual port change creates a different browser origin; draft/image recovery, the temporary OAuth callback route, the LAN proxy, and the current generation retain their own identity rules. A plugin toggle alone does not establish a changed port or new permission. See [plugin runtime and permissions](plugin-runtime-and-permissions.md) for asynchronous content proof and independent native refresh, and the [F12 record](releases/v0.2.1-rc.2-build190-fix12-20261021.md) for the batch. Host timings do not establish phone performance.

## Local memory and backup scope

`ConfigStore` stores a successful fallback in `web_fallback_for` + `web_fallback_port`, and records `web_origin_ports` for explicit old-origin draft recovery. These values are saved only after valid authentication is published when automatic mode or a fallback was used. They are not a complete audit of historical listening ports.

Portable `port` / `portMode` configuration can express a preference. Local fallback memory, old-origin lists, the current generation, and credentials do not thereby gain authority on another device. Restoring “manual 45678” means a preference, not availability or authentication. Recheck LAN, the capability bridge, and ADB under their own local rules. Do not put token-bearing URLs in public reports, command arguments, or synchronized configuration.

## LAN listens on 3081 and forwards to the actual backend

After LAN is enabled, its proxy starts only with valid BrowserAuth for the current generation. It listens on `0.0.0.0:3081` and forwards to `127.0.0.1:<actual Web port for this run>`. A listener captures the backend port and generation together; late callbacks cannot mix snapshots. An enabled switch is not proof of successful binding. Network changes, OS local-network permission, and routing isolation may still prevent access.

LAN uses its own token/cookie and does not expose the DSH startup token or backend BrowserAuth to remote clients. The proxy authenticates internally using this run's in-memory BrowserAuth; stopping or changing generation invalidates old snapshots. Browser Host, Origin, Cookie, and related fields are rewritten for backend loopback semantics. Initial token-link redirection removes only the LAN token, preserving the path and other query parameters. Treat the complete LAN URL as sensitive.

This is an HTTP proxy without TLS termination. Its use must match the network's trust boundary. Both HTTP and WebSocket pass authentication and current-generation checks. Only a verified 101 WebSocket handshake can open a bidirectional tunnel. Zero-length bodies, chunks/trailers, redirects, and long streams require their own framing; connection closure is not a universal boundary. Connection registration, short requests, long streams, and reverse pumps have separate limits; stopping closes this run's sockets. LAN Web access does not expose the 3090 Android capability bridge.

The F11 changes retain unknown backend port `0` as not ready, without a hidden 3080 fallback. New authentication for a different actual port in the same generation replaces the old proxy target. A business 401/403 affects that request without automatically destroying the whole BrowserAuth session. Interface/address changes retire old connections while retaining the current token and listener; a fast network replacement with the same interface/address may be unobservable. See [LAN](lan.md) for operation, address candidates, and the limits of host verification.

When strict recovery adopts the same active Web process, F12 reuses that recovery's verified cookie after the second kernel identity check and adoption. Publication again binds the current generation, exact URL, and actual port, without another exchange or the ordinary new-start `afterAuthenticated`. Disabling LAN starts no listener, and a bind failure does not revoke successful local adoption. See [strict LAN recovery](lan.md).

Maintenance code: [LanProxyService.java](../../app/src/main/java/com/deepseekharness/app/LanProxyService.java), [LanAuth.java](../../app/src/main/java/com/deepseekharness/app/LanAuth.java), and [HttpProtocol.java](../../app/src/main/java/com/deepseekharness/app/util/HttpProtocol.java).

## Old-origin data and Web generations

A browser origin includes scheme, host, and port. A changed port may leave drafts in the old origin without deleting backend conversations or attachments. Configuration's “Recover drafts from an old port” first requires current DSH readiness, then selects a locally recorded old port and browser engine.

Recovery closes the existing preview and waits for its resources to release, avoiding concurrent page/recovery writes. An occupied old port is not taken over: source drafts and candidates remain. Only supported, validated draft/image records are recovered; this is not an arbitrary localStorage transfer. Conflicting destination content is retained. Publication uses an allowed blank destination and verifies readback. Cookies, authentication tokens, and another page's image leases are excluded; the source is not deleted.

See [BrowserOriginRestore.java](../../app/src/main/java/com/deepseekharness/app/ui/BrowserOriginRestore.java) and [browser-origin-state.js](../../app/src/main/assets/app-integration/browser-origin-state.js). A later process may reuse the same port: generation and page identity still require separate validation. WebView and Gecko storage cannot be substituted merely because their port matches.

## Troubleshooting order

1. Read Main's current launch stage, actual port, and error; do not use an old bookmarked authentication URL.
2. While waiting for authentication, inspect this run's output before calling the wait a port conflict.
3. If a manual port is occupied, check whether a fallback succeeded; the preference remains saved.
4. If local Web works but LAN fails, inspect its switch, local-network permission, actual 3081 bind, and current URL.
5. For ADB failure, use current wireless-debugging pairing/connection information instead of changing the Web port.
6. For missing drafts, distinguish backend data from old-origin data and use explicit recovery.
7. For lookup failures, read [DNS configuration](dns-configuration.md); changing a port does not repair external DNS.

## Maintenance and regression map

Port selection changes require configuration migration, actual authentication, LAN capture, and backup projection checks. Cover automatic `0`, an unavailable remembered port, manual conflicts, reserved ports, and forged startup lines. Proxy changes require body framing, WebSocket handshake, old generations, and sockets after stop. Origin recovery changes require conflict retention, preview closure, credential exclusion, and failed readback.

Existing entry points, listed here without rerunning them during translation:

- `WebPortSettingsTest`, `WebPortPolicyTest`, `DshAuthUrlTest`, `WebOutputSessionTest`.
- `LanAuthBridgeTest`, `LanAuthTest`, `LanTokenStateTest`, `HttpProtocolTest`, `SocketDispatchTest`.
- `tools/test-lan-settings-host.mjs`.
- `tools/test-browser-origin-restore.mjs`, `tools/test-browser-origin-server.py`, and `tools/fixtures/browser-origin/BrowserOriginRestoreServerTest.java`.

Test classes live in their corresponding packages below `app/src/test/java/com/deepseekharness/app/`. See also `tools/test-build190-command-environment.py` for launch environment and shell inheritance. Software tests do not replace actual release-APK port, LAN-permission, or device-network verification.
