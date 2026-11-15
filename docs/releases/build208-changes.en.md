# DSHA 0.2.1-rc.3-20261029.0741-dsh0.2.1-rc.3 / build208

- - Fixed phone control treating DSHA's own overlay windows (status bar, assistant, confirmation) as target occlusion: unregistered non-focusable own overlays are excluded whole-window by package identity and no longer produce TARGET_WINDOW_OCCLUDED. Coordinate trajectories crossing the excluded region are still refused, and a full cover still leaves no usable area (#210).
- - On devices where link() is rejected, materializing legacy pnpm content links as full copies is no longer killed mid-way by a fixed 120-second timeout; the timeout becomes a 15-minute backstop for genuine hangs, preventing an interrupted copy from leaving the plugin transaction pending and blocking the next start (#209).
- - Retain dsh-web-mobile 3.2.2, DSH rc.3, local and LAN access, and ten languages. Phone acceptance remains paused.
