# DSHA 0.2.1-rc.3-20261103.0130-dsh0.2.1-rc.5 / build280

- When the device bridge port is not published or cannot be read, the request now fails instead of falling back to port 3090 with this installation's token, so credentials are not sent to another DSHA installation on the same device.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 280 (based on build279). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
