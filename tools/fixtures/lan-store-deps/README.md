# LAN settings persistence test dependencies

The official 0.2.1 client-store Node entry imports Zustand/Immer declared as
`devDependencies`. The production browser receives these through the frontend
bundle. This test loads the exact installed store body and rewrites only its four
external import specifiers to the independently locked libraries in this fixture.
It never changes the raw or managed runtime tree.

Prepare once from the repository root (PowerShell):

```powershell
New-Item -ItemType Directory -Force app/build/runtime190-store-deps | Out-Null
Copy-Item tools/fixtures/lan-store-deps/package.json,tools/fixtures/lan-store-deps/package-lock.json app/build/runtime190-store-deps/
npm ci --prefix app/build/runtime190-store-deps --ignore-scripts --no-audit --no-fund
$env:DSHA_TEST_RUNTIME = '<current verified Windows/Linux host raw runtime>'
node tools/test-lan-settings-host.mjs
```

`DSHA_LAN_STORE_DEPS` can select another prepared directory. Its package lock must
match this fixture. The script also performs the existing full runtime content
proof and checks the exact LAN source SHA and all retirement-contract anchors.
The actual upstream client controller/mirror and Host SettingsForms execute;
only configEditor publishes to an owned temporary JSON file. It tests local and
LAN identity, revision forwarding, client and Host recreation, and invalid-value
rejection. It does not claim an Android FD, browser, or actual LAN network test.
