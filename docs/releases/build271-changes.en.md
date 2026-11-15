# DSHA 0.2.1-rc.3-20261102.1030-dsh0.2.1-rc.5 / build271

- Experimental: new External device capability, off by default. Once you turn it on separately in the channels group of Settings → Device permissions, it can control another phone over USB or wireless debugging and install an APK, APKS, XAPK or module on it after you confirm; every install asks first.
- Flashing is limited to read-only preflight and a dry run and never writes anything; the capability needs authorized root and does not inherit this phone's ADB or global permissions.
- This is an experimental feature that has not been verified with a connected device and may not work.
- Verification scope: local unit tests and source gates only; not verified on a device. Raises versionCode to 271 (based on build270). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
