# DSHA 0.2.1-rc.5-20261104.2130-dsh0.2.1-rc.5 / build311

- Experimental: a confirmed Web stop is no longer turned back to unknown by a stale PID, and unreadable processes block maintenance instead of being reported as running; diagnostics show read-only host state while the environment is busy (fixes picked from collaborator ym2025szz).
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 311 (based on build310). The DSHA revision remains 0.2.1-rc.5 and bundled DSH remains 0.2.1-rc.5.
