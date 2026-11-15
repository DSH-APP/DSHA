# DSHA 0.2.1-rc.3-20261101.0053-dsh0.2.1-rc.5 / build220

- Fixes the race between the phone-task overlay and screen authorization: after the user expands the status window, screen control is paused and the old authorization is revoked; a later `turn/end` or `/app/ui/task-end` no longer unconditionally closes the paused control window.
- The same task remains attached when the user expanded and paused it. An explicit Continue requests a fresh screen observation, while an explicit Stop retires the task. Old authorization and unknown actions are never replayed or approved automatically. Ordinary completion still closes the window and retires the service.
- Cleanup rechecks task ownership and pause state on the main thread. Android regression coverage now includes expanded-pause retention, explicit-stop retirement, and ordinary completion retirement; the device-operation guide is updated accordingly.
- Adds the mobile ScreenSense recognition/action performance evaluation and staged refactoring proposal. OCR, the compact protocol, and latency budgets remain design evaluation rather than implemented features in this build.
- Raises versionCode to 220. The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5. Complete build, unit-test, signing, and physical-device acceptance scope follows this CI / Release receipt.
