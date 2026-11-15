# DSHA 0.2.1-rc.3-20261103.0700-dsh0.2.1-rc.5 / build291

- Fixes one-tap backup failing every time with a data-location change: the app now stops writes before confirming data locations; device-specific state files no longer take part in the location check, and backup still stops if user data is replaced.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 291 (based on build290). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
