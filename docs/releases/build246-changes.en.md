# DSHA 0.2.1-rc.3-20261101.1600-dsh0.2.1-rc.5 / build246

- Fixes the floating assistant window not being isolated from the keyboard: the collapsed assistant sits at the bottom of the screen, and when you tapped a text field in another app it moved up with that app's keyboard and covered content near the input area.
- The floating assistant never takes text input (editing happens on the separate assistant page). Its window now fits only the system bars and ignores other apps' keyboards, and the exclusion bounds it registers for screen observation no longer shift when a keyboard opens or closes.
- Verification scope: local/CI unit tests only; **not tested on a real device**. This fix may not take effect on some Android versions or ROMs (the assistant may still move up with the keyboard); follow-up user feedback is needed to confirm it. Raises versionCode to 246. The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
