# DSHA 0.2.1-rc.5-20261105.0830-dsh0.2.1-rc.5 / build322

- Plugin uninstall: fixes third-party plugins failing to uninstall with a file-not-found error under the proroot engine. A failed uninstall still rolls back completely and never leaves a half-removed plugin.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 322 (based on build321). The DSHA revision remains 0.2.1-rc.5 and bundled DSH remains 0.2.1-rc.5.
