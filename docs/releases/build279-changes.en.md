# DSHA 0.2.1-rc.3-20261102.1430-dsh0.2.1-rc.5 / build279

- Experimental: the startup recovery page is reworked; a status card at the top states the current conclusion, and failures are grouped by cause with a matching next step.
- build278 was not published separately: durations in the background task list could show the wrong translation after a language switch. This build fixes that and ships the rest of build278.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 279 (based on build278). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
