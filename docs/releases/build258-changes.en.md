# DSHA 0.2.1-rc.3-20261102.0400-dsh0.2.1-rc.5 / build258

- Boot loading screen and launcher icon to Web: in Settings → Run configuration, or in the new builtin plugin dsh-launch-target in the Web plugin details, choose whether the app icon opens the main screen or Web (main screen by default). With Web selected, a boot loading screen shows first and Web opens once the environment is ready; backing out of Web returns to the home screen.
- Maintenance, startup recovery and pending plugin links still open the main screen first, and returning from a notification or the task card never jumps straight to Web. The choice is stored only on this device and is not backed up.
- Verification scope: local targeted unit tests and source gates only; the emergency DSH and this release package were not installed on a device. Raises versionCode to 258 (based on build257). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
