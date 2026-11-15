# DSHA 0.2.1-rc.3-20261102.1000-dsh0.2.1-rc.5 / build270

- Experimental: Task status island page redrawn. A preview card at the top shows the capsule and notification you will see after returning to the home screen; system status no longer just says it cannot be confirmed, but explains what you can do and links to system settings, with a one-tap shortcut to turn the system switch on when it is off.
- When the main switch is off, the timer switch is greyed out but keeps its saved choice; a new island order list highlights which task level currently holds the island; wide screens use two columns. Existing settings are not affected.
- This is an experimental UI update that has not had a unified visual review on a device.
- build269 was not published separately: its release failed the Lint check because the downloads page used the wrong line-break constant; that is fixed, and the downloads page rewrite ships with this build.
- Verification scope: local unit tests and source gates only; not rendered on a device. Raises versionCode to 270 (based on build269). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
