# DSHA 0.2.1-rc.3-20261031.0340-dsh0.2.1-rc.5 / build216

- Bundled dsh-web-mobile is synced to upstream main and the current v3.2.2 tag `bb0e5e1`: the fullscreen right panel gets back its sheet band and wallpaper fill (#247), the pet overlay no longer covers sheets/panels or swallows taps (#248), and the files-panel gesture reads `data-sidebar-right-open` and claims the named pet root first (#249).
- All DSHA local patches are replayed on the new upstream bytes: the session-input/drawer-close, search-row, backdrop-press and settings font-family-row differences remain; upstream has not absorbed the font-row patch.
- The upstream lock moves to 232 members and archive SHA-256 `80973622…`; the runtime descriptor, third-party notices, mobile patch and provenance documents are updated, and build190-era package, versionCode and DSH references are corrected.
- versionCode is raised to 216; the DSHA revision stays 0.2.1-rc.3 and the bundled DSH is 0.2.1-rc.5; build and acceptance scope follows this CI run and the Release receipt.
