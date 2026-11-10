# DSHA 0.2.1-rc.3-20261102.1100-dsh0.2.1-rc.5 / build272

- Experimental: Launch log area rebuilt. The overview groups entries by stage and shows when each step started, finished and how long it took; a step with a problem moves to the top and expands on its own, so you no longer have to search a long log.
- The raw view keeps the full log with toggles for problems only, hiding timings and line wrapping; while you scroll up, an n new lines badge appears and one tap returns to the latest. Copy and download still use the full original text.
- This is an experimental UI update that has not had a unified visual review on a device.
- Verification scope: local unit tests, Lint and source gates only; not rendered on a device. Raises versionCode to 272 (based on build271). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
