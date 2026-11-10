# DSHA 0.2.1-rc.3-20261101.2100-dsh0.2.1-rc.5 / build251

- Plugin page header redesign: the title and Market / Manage share one row, and the segments wrap to a full-width row when space runs out. Manage shows a plugin count badge with distinct states for checking, unconfirmed list and refresh failure.
- Market: the community ecosystem card becomes a link beside the card title, paste and clear sit at the end of the input, and the hint changes colour with the recognition result. Manage: search and Check plugins share a row; filter, sort and download source form one row.
- Progress, status text, Cancel and Check updates merge into one status strip with a wave progress animation, and its height stays fixed when tasks start or finish. The safe-mode restore button moves to the top banner and the download-source help moves into the picker dialog.
- Verification scope: local plugin unit tests and static checks only; not installed on a device, and UI tests and Lint are left to CI. Raises versionCode to 251 (based on build250). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
