# DSHA 0.2.1-rc.3-20261103.0200-dsh0.2.1-rc.5 / build281

- Experimental: with ADB enabled, reopening DSHA after unlocking now makes the background ADB probe yield first, so it is less likely to stay on "Checking environment" for a long time. This is a best-effort fix; the original problem could not be reproduced.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 281 (based on build280). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
