# DSHA 0.2.1-rc.5-20261104.1930-dsh0.2.1-rc.5 / build309

- Experimental: the release identity is now switchable (Mod identity by default, plus an upstream identity workflow that runs only in a collaborator's private repository); external device confirmation pins the target device, and read-only debug commands validate their arguments; backup reads now open non-blocking with the arm64 directory flag corrected, and failures record their stage (some fixes picked from collaborator ym2025szz).
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 309 (based on build308). The DSHA revision remains 0.2.1-rc.5 and bundled DSH remains 0.2.1-rc.5.
