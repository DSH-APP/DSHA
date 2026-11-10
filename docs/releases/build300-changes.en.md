# DSHA 0.2.1-rc.3-20261103.2000-dsh0.2.1-rc.5 / build300

- Adds the temporary fix plugin dsh-fs-read-hint: when an edit is rejected because the file was not read first, it points to an already-read path with the same name; the runtime archive is rebuilt and the emergency environment stays in sync.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 300 (based on build299). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
