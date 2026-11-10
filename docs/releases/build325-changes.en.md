# DSHA 0.2.1-rc.5-20261105.2135-dsh0.2.2-rc.1 / build325

- Fixes the white screen after a build324 overwrite install: the mobile shell sidebar footer gear now yields based on its measured width instead of depending on whether the host draws a "connection status" indicator; the sidebar no longer overlaps and the workbench no longer turns blank.
- Overwrite upgrades now trigger environment maintenance and rebuild normally, instead of treating "enter the main screen" as the main action.
- Verification scope: host tests plus source and runtime descriptor gates; the user confirmed on a device with the test package that the white screen is gone and overwrite upgrades can trigger environment rebuild normally; gear visibility/tappability, the first-connection notice and the skill approval dialog remain pending user confirmation and are not written as passed. Raises versionCode to 325 (based on build324).
