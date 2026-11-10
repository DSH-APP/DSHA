# DSHA 0.2.1-rc.3-20261103.1630-dsh0.2.1-rc.5 / build299

- The virtual screen MCP plugin declares version negotiation, so server.cjs starts once instead of twice; the runtime archive is rebuilt and the emergency environment stays in sync.
- Verification scope: local targeted unit tests, Lint and source gates only; not checked on a device. Raises versionCode to 299 (based on build298). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
