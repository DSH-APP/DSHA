# DSHA 0.2.1-rc.3-20261029.1500-dsh0.2.1-rc.3 / build207

- Add three root command interception modes: Strict (default, allow-list only), Relaxed (any command, but irreversible operations such as formatting, flashing, reboot or clearing app data stay blocked) and Full (no command-content checks); switch them in Settings → Device permissions.
- Relaxed and Full apply only to the root channel. The generic Shizuku/ADB executor stays strict, and SMS reads still require native authorization in all three modes.
- Root raw commands run in the privileged process with timeout, 256 KiB output truncation and execution-identity checks. Strict mode now also blocks recursive deletion of storage roots and first-level storage directories.
- This is the SunsetRNE test identity (not E7E3) with a fixed test signature. Verification covers this phone installation and the three root modes only; all ROMs and device models are not covered.
