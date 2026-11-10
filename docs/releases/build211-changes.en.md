# DSHA 0.2.1-rc.3-20261030.1245-dsh0.2.1-rc.3 / build211

- The plugin progress bar now uses scheme F: breathing when indeterminate, flowing light when determinate, and progress follows only the real percent.
- Success shows green at 100% and holds for 700ms; failure shows red at the maximum progress seen in the task, holds for 900ms, then fades out.
- The long download-source note moved into the round exclamation-button popup, which also shows the latest task result or error.
- Plugin results now carry an explicit success/failure state, so pre-dispatch failures such as a full queue, unavailable background executor, or file-selection errors also appear in that popup.
- Both download-source placements share the same progress bar; dark/light tokens, reduced-motion fallback and TalkBack progress semantics are covered.
- Build, unit-test and device acceptance scope is limited to this run and the Release receipt.
