# DSHA 0.2.1-rc.3-20261101.1700-dsh0.2.1-rc.5 / build248

- Plugin cards now share one card across the list, the tablet left pane and the detail pane. Source is a badge, version sits under the title, configuration and current Web sit on one row, and version / location / source collapse into a compact property table.
- More actions uses a card sheet: on phones it docks above the floating nav; on tablets in portrait and landscape it is centered and width-capped, not stretched edge to edge.
- Verification scope: local unit tests for this change; the test package was overwrite-installed on one OnePlus PJD110 (360dp portrait) and one Lenovo TB321FU tablet (portrait and landscape). Other devices, languages, dark mode, short screens and 1.3× font were not checked. Raises versionCode to 248. The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
