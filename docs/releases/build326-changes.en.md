# DSHA 0.2.1-rc.5-20261105.2200-dsh0.2.2-rc.1 / build326

- Maintenance decision after an overwrite install: an overwrite that keeps the same DSH version and only replaces bundled managed patches now correctly opens the runtime maintenance page and puts "update runtime" back as the primary action; "enter the main screen" no longer overrides the update.
- After an overwrite install, an unconfirmed old web session only blocks automatic update start; the maintenance page is still shown and a manual one-tap update stays available — unknown instances are not replayed automatically.
- Verification scope: host tests, the full contracts group, 11 targeted test classes and source/descriptor gates; device conclusions follow the actual confirmation record for that run and no pass is pre-written. Raises versionCode to 326 (based on build325).
