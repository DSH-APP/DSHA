# DSHA 0.2.1-rc.3-20261031.2130-dsh0.2.1-rc.5 / build219

- Closes out the build218 onboarding redesign: the four-step guide (welcome → purpose → grants → done), the background deployment capsule and the full-screen runtime maintenance page; first deployment and over-the-top updates share one maintenance entry.
- On-device feedback fixes: an in-guide background deployment reclaimed by the system returns to the same step; the "purpose" choice now drives the recommendations; the battery card follows the system "allow background usage" state; Stellar clone setups show grants for the actual manager.
- When a Stellar manager is detected, the device-grant page and the onboarding cards use the actual manager name instead of the old hardcoded one.
- The battery-optimization entry opens this app's battery page directly; card and button copy now uses "allow background usage" semantics.
- Over-the-top updates return to the full-screen maintenance page; when the disk holds an unfinished isolated trial or maintenance transaction, the app first verifies the old trial process has exited and closes the site, then enters maintenance/update instead of starting an update bound to fail.
- Wide touch screens (tablet landscape / CSS ≥1024px) fold archive/pin into the ⋯ menu: fixes the 280px sidebar squeezing the title to about 55px, leaving only "time + ⋯" in the row. The bundled dsh-web-mobile carries a local early patch; remove it once upstream PR #256 lands.
- versionCode is raised to 219; the DSHA revision stays 0.2.1-rc.3 and the bundled DSH is 0.2.1-rc.5; build and acceptance follow this CI run and the Release receipt, and this round claims no on-device overwrite-install acceptance.
