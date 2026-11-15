# DSHA 0.2.1-rc.3-20261027.1149-dsh0.2.1-rc.3 / build204

- Interface: the v5 interaction prototype's page styling now covers settings, plugins, runtime config, install and environment, device grants, data and backups, updates, diagnostics, welcome/onboarding, about and environment recovery; titles, section labels, cards, inputs and buttons share one 26sp scale and one light/night palette.
- Terminal: "New session", "End session" and "Focus input" respond on the first tap; the sidebar System/Light/Dark theme switch is removed and the terminal follows the app theme without recreating sessions or replaying commands.
- Cleanup: the ADB pairing page no longer shows a duplicate title, the link-install confirmation uses the 26sp page scale, and the environment maintenance (extract) page now uses v5 cards and buttons.
- Merge Sunset-3090-Q plugin management improvements, reducing redundant refreshes while retaining confirmed operation state.
- Fix confirmation policy changes leaving later screen reads permanently paused. Explicit denials, user pauses and Android permissions remain effective.
- Remove PC workspaces, QR pairing, PC connectors and relay components. Keep phone control and local/LAN access.
- Give native and web content one top safe-area owner, keeping the status bar visible. Retain ten languages and system-follow mode.
- Virtual-screen launch accepts an optional generation and checks status before launch; the native launch request carries only coreInstance and package. This status precheck is not an atomic generation fence.
- This source merge includes dsh-web-mobile 3.2.2 and DSH rc.3. Phone acceptance remains paused; device results for earlier branches or other packages do not verify this build.
