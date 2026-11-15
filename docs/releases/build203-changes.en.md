# DSHA 0.2.1-rc.3-20261027.0700-dsh0.2.1-rc.3 / build203

- Interface: the v5 interaction prototype's page styling now covers settings, plugins, runtime config, install and environment, device grants, data and backups, updates, diagnostics, welcome/onboarding, about and environment recovery; titles, section labels, cards, inputs and buttons share one 26sp scale and one light/night palette.
- Terminal: "New session", "End session" and "Focus input" respond on the first tap; the sidebar System/Light/Dark theme switch is removed and the terminal follows the app theme without recreating sessions or replaying commands.
- Cleanup: the ADB pairing page no longer shows a duplicate title, the link-install confirmation uses the 26sp page scale, and the environment maintenance (extract) page now uses v5 cards and buttons.
- The bundled dsh-web-mobile stays at 3.2.2 and the bundled DSH at rc.3; versionCode is now 203. Test identity is dsh.android.client.SunsetRNE with a local test certificate, not the E7E3 production package.
- Boundary: this build is still a test package. TalkBack, font-scale and full rotation acceptance were not performed; the file browser, remote pairing/workspaces and web preview keep the existing implementation.
