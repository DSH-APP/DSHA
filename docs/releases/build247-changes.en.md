# DSHA 0.2.1-rc.3-20261101.1630-dsh0.2.1-rc.5 / build247

- Onboarding step 3, Grant what you need, is now a single list: no categories, one row per permission. Granted rows show only their status; ungranted rows put Allow / Enable / Settings at the end, with a one-line subtitle on what it unlocks.
- Purpose notes, hand-off hints and the remaining buttons move into an expandable area that opens on tap; a row collapses again once its permission changes. Only problems such as denied, background restricted or waiting to connect get an inline note.
- Recommended items come first and the rest fold into More permissions; the page intro and the bottom button are each one line. On a 360dp portrait phone, all six recommended items fit on one screen.
- Verification scope: local unit tests; the test package was overwrite-installed on one OnePlus PJD110 (360dp portrait) and its onboarding permission page checked. Other devices, languages, dark mode, short screens and 1.3× font were not checked. Raises versionCode to 247. The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
