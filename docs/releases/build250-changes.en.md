# DSHA 0.2.1-rc.3-20261101.2030-dsh0.2.1-rc.5 / build250

- Configuration snapshots can be deleted again. Interrupted writes no longer leave partial directories, and old snapshots are deleted in two steps. A damaged or manually deleted snapshot no longer makes pre-repair snapshots, configuration creation and plugin removal fail; unverifiable leftovers move to quarantine and no longer fill the snapshot limit.
- Startup recovery page: damaged snapshots show the reason and can be deleted; tapping an ordinary snapshot offers Restore or Delete; quarantine can be emptied at once. Deleting removes only the snapshot copy; current configuration is unchanged.
- Verification scope: local unit tests and static checks only; not installed on a device, and UI tests and Lint are left to CI. Raises versionCode to 250 (based on build249). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
