# DSHA 0.2.1-rc.5-20261105.2020-dsh0.2.2-rc.1 / build324

- Skill-call approval: when the model calls a skill marked "user-only", a dialog asks the user to allow or deny; an allow applies to that single call only, and a deny does not let the call through.
- No more flicker on first entry: opening a page no longer briefly flashes "reconnecting" and then "connected"; after a real disconnect, the recovery notice still appears.
- Verification scope: host tests, runtime descriptors and source gates; device conclusions follow what the corresponding record actually confirmed and are not widened into a device matrix. Raises versionCode to 324 (based on build323).
