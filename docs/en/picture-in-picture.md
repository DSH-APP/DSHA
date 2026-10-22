# System picture-in-picture and the browser host

Verification date: 2026-10-21. Source: the corresponding Chinese maintenance guide and current working-tree implementation, using published F10 `087724c` as a reference baseline. This documentation adds no phone validation. Historical PiP results apply only to the APK identified by each record.

## Enabling PiP and when it takes effect

Picture-in-picture is off by default. [ConfigStore](../../app/src/main/java/com/deepseekharness/app/core/ConfigStore.java) records the user's explicit choice in a local key. An old default-on value, `pictureInPicture=true` in an archive, or system permission does not prove a local user choice. Restoring settings therefore cannot enable PiP by itself.

Entry also requires Android API 26 or later, device support, system permission for this app, ready browser content, a valid and active DSH generation, an unlocked screen, and a host that is not finishing. Standard already has minSdk30; Low has no system PiP below API26. [PictureInPictureActivity](../../app/src/main/java/com/deepseekharness/app/ui/PictureInPictureActivity.java) and [PictureInPicturePolicy](../../app/src/main/java/com/deepseekharness/app/util/PictureInPicturePolicy.java) centralize these checks. A running host observes saved preference changes. If Android rejects entry, the ordinary page remains; saving a preference does not prove that a small window opened.

| User action | Current behavior |
|---|---|
| Return Home or switch apps from the browser | When eligible, Android8–11 explicitly enters PiP; Android12+ uses gated auto-entry. |
| Return from the browser to native Main | The conversation host is hidden and the native page opens; this does not implicitly enter PiP. |
| Open a file picker, system browser, or another app-initiated activity | Auto-entry is suspended to avoid leaving an additional small window during the explicit navigation. |
| Turn off app PiP or revoke system permission | The host rechecks permission. If already in PiP, it requests that the current conversation be hidden. |
| Stop DSH while in PiP | The generation becomes invalid and the small-window monitor retires the empty host. The next launch uses a valid new host. |

## One page instance, generations, and tasks

Full-screen, PiP, and hidden presentation use the same browser instance within a generation. A PiP transition must not recreate the DSH conversation. Browser identity includes generation and instance. After stopping, restarting, or changing engines, the old page cannot receive operations intended for the new identity. [BrowserPresentationState](../../app/src/main/java/com/deepseekharness/app/util/BrowserPresentationState.java) owns presentation state; [BrowserConversationRoute](../../app/src/main/java/com/deepseekharness/app/ui/BrowserConversationRoute.java) routes the current identity.

After Home, tapping the launcher icon can restore a still-valid background browser. An explicit return to Main or a native destination such as Terminal or Plugins cancels that pending return. [BrowserTasks](../../app/src/main/java/com/deepseekharness/app/ui/BrowserTasks.java) first routes to the original task, then checks content and identity before resuming it. An entire empty task is removed only when its browser host is confirmed to be the sole activity. Native pages in a legacy mixed task must remain. Once an ended empty host retires, a new generation must not reuse its old PiP geometry.

PiP's **Landscape / Portrait / Automatic** choice changes the browser's logical viewport dimensions so the page can reflow. It does not rotate the device or text. The aspect ratio is clamped to `1:2.39` through `2.39:1`; unusually tall content uses the clamped ratio with margins. Android and the ROM still control the actual small-window dimensions.

## Full-screen restoration and troubleshooting

On full-screen return, [PictureInPictureFrame](../../app/src/main/java/com/deepseekharness/app/ui/PictureInPictureFrame.java) synchronizes compact state with the system's current `isInPictureInPictureMode()`. It idempotently restores child scales to1 and translations to0. A geometry cache is not evidence that transforms have been cleared. The browser continues rendering through the transition so `onPause/onStop` preceding the PiP callback does not create a black frame.

If no small window appears, check the local toggle, system PiP permission, page readiness, and whether the host is navigating away, locked, or stopping. If the restored page remains reduced, record the system PiP state, runtime generation, and whether an ended host was reused. Clearing app data or rebuilding Ubuntu is not a routine troubleshooting step. PiP may cover controls; device checks must use the actual visible region rather than coordinates taken only from the underlying layout.

Related regression entry points are [PictureInPictureOptInTest](../../app/src/test/java/com/deepseekharness/app/ui/PictureInPictureOptInTest.java), [PictureInPicturePolicyTest](../../app/src/test/java/com/deepseekharness/app/util/PictureInPicturePolicyTest.java), [BrowserPresentationStateTest](../../app/src/test/java/com/deepseekharness/app/util/BrowserPresentationStateTest.java), and [BrowserTasksTest](../../app/src/test/java/com/deepseekharness/app/ui/BrowserTasksTest.java). Software gates do not replace device checks of SystemUI, tasks, and both browser engines. See [browser engines](browser-engines.md) for their differences.
