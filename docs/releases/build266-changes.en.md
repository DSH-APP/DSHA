# DSHA 0.2.1-rc.3-20261102.0800-dsh0.2.1-rc.5 / build266

- Floating assistant redesigned: it is now a draggable capsule with a conversation card, and expanding, collapsing or switching sessions no longer cancels a running conversation. You can type right in the window; while the AI is operating the phone it waits in place until you send, then folds your message into the current turn. Tapping ✕ hides it, and replies continue through the notification.
- Mistouch protection: stopping a task asks for confirmation, and only the status row drags the window; its position is remembered and kept on screen.
- Phone action confirmations add Allow this app for this turn, so the same app is not confirmed again and again within one turn; sensitive apps still ask again every 5 actions.
- Verification scope: checked on a device that expanding no longer cancels the conversation, the new card in day and night, per-turn approval and launch confirmation; keyboard handoff for in-window typing, notification replies and island replies were not checked on a device. Raises versionCode to 266 (based on build265). The DSHA revision remains 0.2.1-rc.3 and bundled DSH remains 0.2.1-rc.5.
