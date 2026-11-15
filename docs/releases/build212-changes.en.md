# DSHA 0.2.1-rc.3-20261030.1506-dsh0.2.1-rc.3 / build212

- First-run recovery now decides among three states: reattach when the original Web is still alive; retire the old records and continue starting only after both the Web and its launcher are confirmed exited; keep the protection when that cannot be confirmed.
- When an exit is confirmed, the encrypted instance record and the old PID record are retired together, so the next open no longer stops at "pending confirmation".
- Tapping start while the old instance is being checked records this round's intent and continues automatically once when it completes; a stop, a new generation, or an already reattached session never starts twice.
- When identity is confirmed but authentication briefly fails, the link is rechecked at most 3 times (bounded 500ms/1500ms waits); only then is the unconfirmed state kept.
- The launch page shows the reason-specific states and entries — reconnecting, launcher still exiting, unreadable record, authentication temporarily unavailable — during the check the start button stays disabled, while the restart/safe-start entries remain clickable and record this round's start intent, continuing automatically once when the check completes.
- versionCode is raised to 212; build and acceptance scope follows this run and the Release receipt.
