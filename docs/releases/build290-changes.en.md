# DSHA 0.2.1-rc.3-20261103.0630-dsh0.2.1-rc.5 / build290

- Experimental: the diagnostics page adds a network health check that finds problems such as the system blocking the app's network, a missing foreground service, stale container DNS, a network that cannot reach out, and a process that was frozen; it only detects and reports, with no automatic repair.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 290 (based on build289). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
