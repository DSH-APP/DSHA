# DSHA 0.2.1-rc.3-20261030.0214-dsh0.2.1-rc.3 / build210

- Bundled dsh-web-mobile is synced to the latest upstream content (main @ 04dcd19, still version 3.2.2): tablet touch profile, file download entry and drawer quick settings; local patches are realigned to the new upstream.
- Fixed false failures in production release verification: newer aapt2 prints the minimum API as minSdkVersion, so release APKs are no longer rejected as a minSdk mismatch.
- The test variant versionName suffix is back to -SunsetTest, so the update page recognizes the local test identity again.
- Release workflow gates now use the current repository name and main, so a renamed repository no longer skips the job.
- Source gates completed: LAN strings entered the ten-language catalog, accessibility attributes added for the language entry and DNS dropdown, and muted text and pressed-state contrast now reach 4.5:1.
- HarnessController facade split: packaged asset reads moved to PackagedAssets, and one-click backup no longer depends on core configuration directly.
- All 14 host regressions now pass; build and release results follow the repository receipts.
