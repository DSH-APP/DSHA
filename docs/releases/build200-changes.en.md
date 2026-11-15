# DSHA 0.2.1-rc.3-20261026.1555-dsh0.2.1-rc.3 / build200

- Fixes the native plugins page showing `@deepseek-ai/dsh-experimental-terminal-bundle` as “load state unconfirmed”: one optional child (pwsh) inside its group is disabled by a `!!js` expression and never enters init on Android/Linux, so the sampler treated it as unknown and dragged the whole bundle down. Conditionally disabled optional children no longer decide the bundle state, and sampling never re-evaluates the expression; terminal-bundle should now report loaded, making the list 20/20.
- Keeps the build199 `/data/user/0` → `/data/data` snapshot-path canonicalization; build199 was device-verified to move the list from 20/20 unconfirmed to 19/20, and this build fixes the remaining item.
- Adds a real Cordis Loader regression: a `!!js`-disabled child inside a group no longer makes the bundle unknown/unloaded, and the expression counter stays unchanged while sampling.
- Boundary: the native WebView crash when DSHA is launched into its own secondary virtual display remains out of scope. This round is verified by CI compilation, the JS evidence regression, and unit tests; device installation acceptance follows the build.

Version code moves to 200; package name, historical signature, regular file name, and the bundled DSH rc.3 are unchanged. This branch runs Standard only.
