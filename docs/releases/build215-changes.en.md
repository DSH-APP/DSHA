# DSHA 0.2.1-rc.3-20261031.0300-dsh0.2.1-rc.5 / build215

- Smaller package: the independent emergency DSH moves from 0.2.1-rc.3 to the same 0.2.1-rc.5 as the main environment. Both runtime archives are now byte-identical, so the APK keeps a single copy (one fewer DSH archive of about 127.7 MB); the emergency environment still extracts into its own directory with its own lock, identity, sessions and startup flow.
- Fixed first-time preparation of the independent emergency DSH failing because its overlay input list did not match the descriptor (since build193): the recipes are now read for the emergency lock's version, with a new unit test and a build-side cross-check.
- Removed BouncyCastle resources the app never loads (Picnic post-quantum constant tables and certificate-path review messages, about 1.2 MB); the AES-GCM, SHA-256 and PBKDF2 used for backup encryption are unaffected.
- versionCode is raised to 215; the DSHA revision stays 0.2.1-rc.3 and the bundled-DSH suffix is 0.2.1-rc.5; build and acceptance scope follows this run and the Release receipt.
