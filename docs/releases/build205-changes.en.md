# DSHA 0.2.1-rc.3-20261027.1312-dsh0.2.1-rc.3 / build205

- Terminal paste uses a bounded FIFO input queue, with bracketed paste submitted as one entry. When the queue fills, later input pauses for confirmation; unsent input is not automatically retried.
- Preserve allowlisted diagnostics when a virtual-screen start has an unknown outcome. A diagnostic does not prove remote exit or release the unknown-outcome fence.
- Bind phone-control actions to the target of the latest successful screen observation. A changed target or a new observation requires fresh evidence; an older observation cannot restore action permission.
- When device DNS is available, migrate only the byte-for-byte legacy default DNS configuration to device DNS. Preserve user-modified configurations and the legacy default when device DNS is unavailable.
- Extend regression fixtures for session and workspace stream recovery, download probing and compressed responses. Fixture results do not establish phone or real-server restart acceptance.
- Retain dsh-web-mobile 3.2.2, DSH rc.3, local and LAN access, and ten languages. Phone acceptance remains paused.
