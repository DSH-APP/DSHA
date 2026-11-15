# DSHA 0.2.1-rc.3-20261102.0900-dsh0.2.1-rc.5 / build268

- Experimental: Device permissions page compacted. A fixed overview sits at the top, and tapping one of its four status capsules jumps to that group; Root, Shizuku and ADB share one channel card; the Root blocking level and the screen confirmation mode are now three-way choices; wide screens such as tablets use two columns.
- Risk confirmations are unchanged: relaxing a level still needs the red confirmation (three checkboxes and a 10-second wait), and tapping outside does not close it.
- This is an experimental UI update that has not had a unified visual review on a device, so some layouts may look uneven.
- Verification scope: local unit tests and source gates only; not rendered on a device. Raises versionCode to 268 (based on build267). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
