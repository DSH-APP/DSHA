# DSHA 0.2.1-rc.5-20261105.0230-dsh0.2.1-rc.5 / build316

- Plugin dependency checks: the CAS scan fast path rechecks entry types and inodes, so swapping a file for a link and restoring its modification time within the same tick is no longer missed. The document snapshot now releases its permit when SQLite initialization fails, so later browsing no longer stalls.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 316 (based on build315). The DSHA revision remains 0.2.1-rc.5 and bundled DSH remains 0.2.1-rc.5.
