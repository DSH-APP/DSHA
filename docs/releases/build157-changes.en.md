# DSHA 0.2.8 / build157

Retains build156 features and fixes, DSH 0.2.0-rc.2, base environment 10, the original application ID and historical E7E3 signing certificate.

- Qualifies and pins the current rc2 patch set for recovery so identical signed DSH archive bytes are stored once in the APK, removing approximately 122.7 MB of duplicate content.
- Recovery retains its independent lock, extraction directory, HOME and controlled repair tools; it does not depend on the installed main environment or user plugins.
- Main environment contents, base version and runtime identity remain unchanged. Future main runtime updates do not automatically update the recovery pin.

See the build157 verification report for actual APK sizes, software checks and device acceptance. No GitHub publication or website deployment was performed.
