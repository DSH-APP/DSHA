# DSHA 0.2.1-rc.3-20261029.1845-dsh0.2.1-rc.3 / build209

- App name is now DSH-A-Su (Sunset Mod) and applicationId is io.github.sunsetrne.dsha; it installs alongside the original dsh.android.client with fully isolated data and never overwrites existing data.
- Release signing is rebuilt: the historical E7E3 certificate is retired in favor of the Mod's own release certificate, and verifyPublishCertificate now checks the new certificate and package name, with the expected hash recorded in ci/release-identity.json.
- The Island variant is merged into the regular package: promoted-notification support ships in the normal build, with no separate package or package name.
- Android 10 and older are dropped: the low / GeckoView compatibility line is removed; the minimum is Android 11 (API 30), arm64-v8a only.
- The accessibility declaration is shared by all variants: declared in the main manifest, with no packageNames restriction and no flavor override; its label and description are updated to "DSH-A-Su screen control" and shortened.
- All ten languages remain: zh, zh-Hant, en, ru, ja, ko, ar, pt, la, fr.
- Short UI labels in fr, pt, ru, ar and la are shortened further to take less screen space.
- Fixed the corrupted Latin (la) text for "Next".
- Fixed build inputs now come from prerelease Release assets in the public repository, pinned by SHA-256; the public repository can restore the complete build inputs (see the [fixed build inputs document](../../docs/fixed-build-inputs.md)).
- Release tooling and CI assertions for the package name and certificate are updated together; build and gate results are reported by repository receipts.
