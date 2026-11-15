# DSHA 0.2.1-rc.3-20261101.2300-dsh0.2.1-rc.5 / build255

- Maintenance page footer reworked: the three secondary actions become separate outlined buttons with space between them, icons move above the text with large fonts or narrow screens, and the disabled primary button is easier to tell apart.
- After a failed or interrupted update, Emergency DSH is highlighted in red; when readiness checks fail, Maintenance records uses the warning colour. Tap behaviour and maintenance gates are unchanged.
- Verification scope: local unit tests and static checks only; not installed on a device, and UI tests and Lint are left to CI. Raises versionCode to 255 (based on build254). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
