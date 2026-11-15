# DSHA 0.2.1-rc.5-20261104.2330-dsh0.2.1-rc.5 / build313

- Experimental: uploads, downloads and microphone access are reclaimed after the WebView renderer exits, and old pages still have an owner to clean up during rotation; repeated IME frames, picture-in-picture layouts and color probes are no longer sent twice (fixes picked from collaborator ym2025szz).
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 313 (based on build312). The DSHA revision remains 0.2.1-rc.5 and bundled DSH remains 0.2.1-rc.5.
