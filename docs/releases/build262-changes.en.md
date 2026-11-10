# DSHA 0.2.1-rc.3-20261102.0600-dsh0.2.1-rc.5 / build262

- New read-only compact screen preview: AI assistants can read the full structure of the current screen through /app/sense/state with less text, under the same permissions and task isolation as the existing screen reader. The k*** labels in the preview are for viewing only and cannot be used to tap; taps still go through the existing node actions.
- The actions a node supports are now read once per observation and checked again before running, which reduces mistaken actions after the screen changes.
- build261 was not published separately: its Source checks and release both failed because three new files were missing from the format list; the terminal paste fix from build261 ships with this build.
- Verification scope: a read-only check on a system settings page on a device; local unit tests and source gates passed. Raises versionCode to 262 (based on build261). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
