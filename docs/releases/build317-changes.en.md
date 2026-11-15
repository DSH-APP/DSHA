# DSHA 0.2.1-rc.5-20261105.0330-dsh0.2.1-rc.5 / build317

- gh adapter: the wrapper script and GitHub forwarding proxy are now written into the managed runtime with the package. Forwarding only allows GitHub domains on port 443, and an occupied port is replaced with a temporary one automatically.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 317 (based on build316). The DSHA revision remains 0.2.1-rc.5 and bundled DSH remains 0.2.1-rc.5.
