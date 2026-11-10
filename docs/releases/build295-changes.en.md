# DSHA 0.2.1-rc.3-20261103.1430-dsh0.2.1-rc.5 / build295

- Fixes Web startup after an in-place update getting stuck at "a known Web launcher could not be confirmed as exited", which could only be cleared by force-stopping the app.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 295 (based on build294). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
