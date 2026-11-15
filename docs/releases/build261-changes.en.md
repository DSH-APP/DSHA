# DSHA 0.2.1-rc.3-20261102.0530-dsh0.2.1-rc.5 / build261

- Pasting long text into the terminal no longer freezes the app: every typed character used to re-run a full environment check on the UI thread, so a long paste could add up to more than 5 seconds and trigger an App not responding prompt. Terminal input and tab switching now read a cached check result, and a full check still runs during environment maintenance or when the relevant files change.
- Verification scope: the user confirmed pasting works on a test package; local unit tests and source gates passed. Raises versionCode to 261 (based on build260). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
