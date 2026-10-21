# Choosing runtime and network settings

Verification date: 2026-10-21. Source: the complete corresponding Chinese maintenance guide and inspected build190 working-tree implementation. Published F10 `087724c` is a reference baseline. Runtime engines, ports, and DNS each have a full topic page. This guide explains selection and diagnosis without maintaining a second set of version or performance figures. It does not establish final F12 release status.

## Choose the setting according to the failure layer

| Symptom or goal | Check first | Setting and effective timing |
|---|---|---|
| Stable startup | [Runtime engine](runtime-engines.md) and startup diagnostics | proot is the current default. Explicit proroot selection requires a Web restart; cold installation follows its selected mode. |
| A proroot loader compatibility problem | Actual `runtimeMode`, loader output, and exit proof | **Use proroot static loader** defaults on and is read when building the next runtime invocation. Turning it off is not a general speed-up. |
| A proot seccomp compatibility failure on a ROM | Failure stage and actual proot command | **Disable proot seccomp acceleration** defaults off and affects the next launch. No universal performance-loss figure is promised. |
| Port collision or an old URL no longer opens | [This run's actual port and authentication](ports-and-networking.md) | Automatic mode is the default. Manual mode excludes 3081/3090; saved changes require a Web restart. |
| DNS lookup fails | [DNS scope](dns-configuration.md), original error, and actual resolver | auto/ipv4/native is read by a newly started environment. Already-sent HTTP or business operations are not replayed. |
| Access the phone from a computer | [LAN](lan.md) toggle, system permission, and current link | LAN defaults off and uses the 3081 proxy. It does not expose the 3090 bridge. |

Do not switch every option at once. Record the original values, failure stage, and actual runtime mode. Change one relevant setting, stop normally, confirm exit, and then start again. To confirm that a setting took effect, inspect this run's diagnostics or generated command rather than only the saved configuration page.

## Boundaries of the two compatibility toggles

[ContainerRuntime](../../app/src/main/java/com/deepseekharness/app/runtime/ContainerRuntime.java) assembles the real invocation according to runtime mode, system API, and static-loader preference. [RuntimeExecution](../../app/src/main/java/com/deepseekharness/app/runtime/RuntimeExecution.java) selects the mode for this run. The existence of static- and dynamic-loader branches in source does not prove that both are reachable through the current UI. See [runtime engines](runtime-engines.md) for current reachability and vendor-input limits.

Disabling seccomp affects only the corresponding proot launch argument; it does not change proroot's path-translation method. The static loader belongs to proroot and does not turn proot into a zero-ptrace engine. Without a current comparison on the same device, do not promise an amount of acceleration or slowdown, or relabel historical vivo benchmarks as F10 results.

Preauthentication fallback from proroot requires explicit exit, no plugin failure, no business submission, and confirmed guest/launcher exit. It allows one proot retry only. Slow startup, timeouts, and UNKNOWN cannot trigger business replay. Trial receipts must retain the actual `runtimeMode` and `fallbackFrom`. A successful proot fallback is not native proroot success.

## Network settings are not global switches

Automatic Web-port mode selects and captures the actual port for this service run. A fallback port is local memory. Migrating the old default 3080 differs from explicitly selecting fixed 3080. F12's controlled actual Host retained the same HTTP object and port across voice-plugin toggles; the native plugin helper uses guest commands rather than Web's current Connection. Proven synchronous-hash blocking does not establish automatic ports as the freeze's cause, so automatic `0` remains. Only an actual port change invokes separate-origin rules. Saving a setting does not seamlessly move an already-running service to a different port. At the inspected source baseline, the presence of a helper for a bind race does not itself establish an automatic-retry promise in the production chain. See the [ports guide](ports-and-networking.md) for the actual integration boundary.

Both auto and ipv4 perform narrow resolution retries for specific Node lookup errors. ipv4 additionally writes managed glibc `no-aaaa`; it does not disable system IPv6. native exits the Node compatibility path. Third-party resolvers, browsers, ADB, and LAN each have their own actual scope. A new value does not retroactively change old processes. After a failure, first check whether the error belongs to that layer instead of describing every mode as IPv4 preference.

Verify a settings change, cold startup, managed trial, and ordinary Web launch separately. The [engine](runtime-engines.md), [DNS](dns-configuration.md), and [port](ports-and-networking.md) pages provide detailed code, locks, and related tests. This guide adds no device matrix or current performance evidence.
