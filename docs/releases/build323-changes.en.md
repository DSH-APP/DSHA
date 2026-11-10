# DSHA 0.2.1-rc.5-20261105.1930-dsh0.2.2-rc.1 / build323

- DSH 0.2.2 upgrade step one: the bundled DSH and the standalone emergency DSH both switch to 0.2.2-rc.1, with the host executor and configuration migration kept compatible.
- Managed runtime inputs are rebuilt for 0.2.2 and pinned back to the fixed digests; plugins, sessions and personal data stay in place.
- Known display issue: the first time a page opens, "reconnecting" flashes briefly and then so does "connected". This is how the upstream first-connection notice renders; it does not affect connections or conversations, and a fix is scheduled for the next version.
- Verification scope: host tests, runtime descriptor checks and source gates; device conclusions follow what the corresponding record actually confirmed and are not widened into a device matrix. Raises versionCode to 323 (based on build322). The DSHA revision remains 0.2.1-rc.5 and bundled DSH is 0.2.2-rc.1.
