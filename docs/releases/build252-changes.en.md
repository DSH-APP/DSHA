# DSHA 0.2.1-rc.3-20261101.2130-dsh0.2.1-rc.5 / build252

- Self-check and diagnostics page simplified: on phones the status card, check results, feedback and tools follow in order, merging the former six groups into three sections; tablets put status, feedback and tools on the left and results on the right.
- Items needing attention come first, unverified checks are marked separately with a grey dot, and healthy ones collapse into one "N OK" row, so unverified checks no longer report a problem while DSH is not running. Wide screens use two columns; 1.3x font falls back to one.
- Export becomes a sheet: pick a diagnostic bundle or a text report first; optional reproduction steps are attached only to that export and never saved. The last saved file appears only after an export.
- Verification scope: local unit tests and static checks only; not installed on a device, and UI tests and Lint are left to CI. Raises versionCode to 252 (based on build251). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
