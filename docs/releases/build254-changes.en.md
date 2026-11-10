# DSHA 0.2.1-rc.3-20261101.2230-dsh0.2.1-rc.5 / build254

- Install and environment page compacted: one status card replaces the version table, with a one-line verdict, three version labels you can tap to copy and a progress bar; Check all and Repair as needed sit side by side.
- The six steps are shorter and expand inline with details, Recheck and Repair as needed instead of opening a dialog. First-install speed test and Back up and rebuild environment move into Maintenance and tools, and rebuilding requires ticking an acknowledgement first. The log starts collapsed to one line and expands automatically while running or after a failure.
- First-install speed test results get their own card; the environment repair and recovery page groups its buttons and adds status dots to recent startup records.
- Verification scope: local unit tests and static checks only; not installed on a device, and UI tests and Lint are left to CI. Raises versionCode to 254 (based on build253). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
