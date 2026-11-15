# DSHA 0.2.1-rc.3-20261102.0630-dsh0.2.1-rc.5 / build263

- Closing a terminal tab no longer freezes the app: closing the last terminal tab used to make the UI wait until the terminal session fully exited, which could exceed 5 seconds and trigger an App not responding prompt; choosing Close app then also ended DSH. Waiting for the session to exit no longer blocks the UI, so DSH is not taken down with it.
- Verification scope: the user confirmed on a test package that closing a terminal tab no longer freezes the app and DSH stays connected; local unit tests and source gates passed. Raises versionCode to 263 (based on build262). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
