# DSHA 0.2.1-rc.3-20261101.2330-dsh0.2.1-rc.5 / build256

- Data and backup sub-pages reworked: restore becomes an in-page four-step wizard (choose file, scope and options, preflight check, confirm restore) that replaces the chain of dialogs; Confirm locks after one tap, and the flow continues after rotation.
- Custom export offers five scopes as single choices and warns that the export is unencrypted when it includes API keys; rescue and maintenance tools are grouped by purpose, and each action still asks for confirmation.
- The storage page shows total usage and a breakdown by category; retained copies can be filtered by backup copies, old environments, plugins and more without losing checked items; the storage guide becomes Q&A that expands when you tap a title.
- build255 was not published on its own: one release-notes test did not match the shipped wording, so its build and release failed. Its maintenance-page button changes ship in this build, and the test is fixed.
- Verification scope: local build and related unit tests only; not installed on a device, and UI tests and Lint are left to CI. Raises versionCode to 256 (based on build255). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
