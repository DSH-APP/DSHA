# DSHA 0.2.1-rc.3-20261103.2100-dsh0.2.1-rc.5 / build302

- Experimental: stale outbound connections are recycled after switching networks or going to the background, so model requests no longer hang for 300 seconds (not verified on a device); adds the screen guard and device command confirmation design documents.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 302 (based on build301). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
