# DSHA 0.2.1-rc.3-20261024.0645-dsh0.2.1-rc.3 / build195

- Added phone and computer workspaces; remote access does not require starting the local Ubuntu environment.
- Added QR pairing, trusted computer management, connection route status, and an isolated remote browser session.
- Added an expandable floating assistant with conversation input, system speech input, and phone-control pause, resume, and stop actions.
- Phone control distinguishes local and remote owners; switching, pausing, disconnecting, and revoking trust retire old action permits.
- Computer connections use end-to-end encrypted streams with direct and configured relay paths. Computer DSH conversations and files stay on the computer.
- Added one plugin reference document that distinguishes current interfaces, proposed extensions, and actual permission boundaries.

This release is validated through Standard software builds and host integration tests. No physical device acceptance was performed. Accessibility, overlays, camera, speech recognition, and background execution depend on the device capabilities and permissions actually available.
