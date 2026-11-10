# DSHA 0.2.1-rc.3-20261024.0345-dsh0.2.1-rc.3 / build194

The app and both bundled main and independent recovery DSH runtimes use 0.2.1-rc.3. The Ubuntu base remains at version 10.

- Update checks: fixes Android reading the bundled certificate collection as empty and failing HTTPS verification. The original certificates are read individually; certificate-chain and hostname checks remain enabled. Stable and preview channels show “No updates available” when no newer package suits this device and application identity. Genuine network, timeout and certificate failures retain a short explanation and retry option.
- Release notes: the row and opened notes use the installed version name and version code. The old “Plugins enable automatically after installation” subtitle and old release notes are no longer presented as the current release.
- File downloads: fixes download preparation responses being disconnected by conflicting compression handlers. The 5 GB streaming path is retained.

These are the changes in this build. See its delivery receipt for actual software checks, published files and device acceptance scope. These notes do not claim recovery of the reported phone failure or a completed device matrix.
