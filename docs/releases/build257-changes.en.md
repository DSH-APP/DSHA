# DSHA 0.2.1-rc.3-20261102.0200-dsh0.2.1-rc.5 / build257

- proroot file lock fix: the proroot engine's runtime reported every fcntl failure as EPERM (permission denied), even when another process simply held the lock, so tools that rely on cross-process file locks, such as Gradle, failed. The compatibility layer now returns the correct error code, so a held lock reports try again. The default proot engine is not affected.
- Verification scope: the test package was checked under proroot on one Android 16 tablet with a lock probe and a Web conversation; the release package was not installed on a device. Raises versionCode to 257 (based on build256). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
