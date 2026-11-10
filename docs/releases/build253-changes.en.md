# DSHA 0.2.1-rc.3-20261101.2200-dsh0.2.1-rc.5 / build253

- Terminal UI polish: the two rows of extra keys now have gaps between keycaps, function keys use a darker tone, arrow keys are larger, and ^C / ^D / ^Z and symbol keys use a monospace font; Ctrl and Alt locks show as a bottom indicator bar. Custom key layouts and long-press customisation are unchanged.
- Simpler sidebar: PTY and simple-terminal sessions share one list ordered by creation, each session's status is a small dot after its name, and End all is hidden when no session exists and still asks for confirmation.
- New session becomes three cards (PTY terminal, simple terminal, DSH CLI), and the help dialog shows four key points with a status legend.
- Verification scope: local terminal unit tests and static checks only; not installed on a device, and UI tests and Lint are left to CI. Raises versionCode to 253 (based on build252). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
