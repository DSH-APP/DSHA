# DSHA 0.2.1-rc.3-20261103.0430-dsh0.2.1-rc.5 / build286

- Experimental: the device bridge adds /app/install to hand an APK from the container to the system installer; when DSHA is not in the foreground it posts a notification you tap to open the installer, and you still confirm the install in the system screen.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 286 (based on build285). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
