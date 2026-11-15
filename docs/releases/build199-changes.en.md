# DSHA 0.2.1-rc.3-20261026.1356-dsh0.2.1-rc.3 / build199

- Fixes `READ=PARENT_LINK` when the native plugins page reads the Web runtime evidence snapshot. Android's `/data/user/0` is a system alias of `/data/data`; the per-component NOFOLLOW walk in `currentSnapshot` rejected that alias as a parent link. The runtime root is canonicalized before the reader applies its NOFOLLOW and size checks to the final file; unreadable snapshots still remain unverified rather than being reported as loaded.
- Keeps the build198 identity normalization, Auto review version evidence, 30-second freshness window, and reason-code logging; this build only changes snapshot path resolution.
- Adds regression coverage for a symlinked runtime root and a missing root; the current version-note assertions follow build199.
- Boundary: the native WebView crash observed when DSHA is launched into its own secondary virtual display is out of scope for this build. This round is verified by CI compilation and unit tests, with no device installation acceptance.

Version code moves to 199; package name, historical signature, regular file name, and the bundled DSH rc.3 are unchanged. This branch runs Standard only.
