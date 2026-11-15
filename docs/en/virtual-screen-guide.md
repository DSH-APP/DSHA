# Opening and using the virtual screen

Accessibility reads controls and supports Chinese text input. **After enabling accessibility, you must still create the virtual screen separately.** `VSCREEN_NOT_RUNNING` means no virtual screen is currently running; it does not mean the environment needs to be reinstalled.

1. Use the **Standard edition** on a phone running **Android 11 or later**. The Low compatibility edition does not currently support virtual screens.
2. Open DSHA **Settings → Device capability authorization** and connect **one of ADB, Shizuku or Root**. Without Root, enable **Wireless debugging** in Android's developer options, then complete pairing and connection on DSHA's ADB pairing page. Confirm that the channel is connected. Merely enabling the system wireless-debugging switch does not connect it to DSHA.
3. Under **Device capability authorization**, tap **Open virtual screen**, then **Create portrait screen** or **Create landscape screen**. Wait for an image and for the page to show that the virtual screen is active. **Usage instructions** are available at the top of the page at any time.
4. Tap **Applications → Choose**, select the application to operate, then tap **Launch**. It appears on the virtual screen, where you can tap and swipe in the preview.
5. To read the control tree or enter Chinese text, enable **DSHA Accessibility**. To let the assistant operate it, also confirm DSHA's screen-reading and action authorization prompts for this attempt, and choose the virtual screen as the target.

### Seeing the virtual-screen preview

While the virtual screen runs, DSHA shows a **blue breathing line** at the top of the screen by default, naming the application currently being operated. Tap it to open the virtual-screen page; tap **×** on its right to collapse it. The strip only displays: it never forwards your touch to the virtual screen and does not cover the status bar.

A collapsed strip does not come back when you create another virtual screen or restart the app. To change the form or reopen it: **Settings → Device capability authorization → Virtual screen preview**, with three modes:

- **Strip (default)**: a breathing line at the top naming the operated application, taking no screen space.
- **Full preview**: the old in-page window; drag it and tap the picture to open the control page.
- **Off**: no preview at all.

The system floating preview is a separate switch that needs overlay permission; while it shows, the in-page strip and window yield automatically. The strip is hidden while the virtual screen is stopped or while you are on the virtual-screen page itself.

### Virtual-screen display size

The virtual-screen page has a **Display size** row. The default `1008×1792 @320` (logical 504×896 dp, 16:9) **stays unchanged**. The only other whitelist choice is **720×1600 @320** (logical 360×800 dp, 20:9), a 360 dp compatibility baseline; some applications may letterbox at that ratio.

The choice only applies to the **next create or rotate**. A running display is not resized by itself; to change it, close and create again, or tap **Rotate** to rebuild at the new size. Sizes outside the whitelist (including 1440×2560) are not offered because two originals would exceed the 24 MiB observation budget, which must be adjusted first. Arbitrary sizes or dpi values are not accepted.

Target-app layout, IME behavior, screenshot size and multi-window behavior at 720×1600 are not yet verified on a real device. The default keeps the existing baseline.

### When the assistant sees multiple windows or split screen

When the virtual screen shows more than one application window (split screen or floating windows), the
assistant does not pick one and continue. Semantic reads return `NO_UNIQUE_APPLICATION_WINDOW` with
`ASK_USER_TO_CLOSE_SPLIT_SCREEN`; close split screen or keep a single application, then let the assistant
observe again. When there is no application window (only system/IME windows), the hint is
`LAUNCH_APP_FIRST`: start the application first. Failure diagnostics carry only controlled window fields such
as type, bounds, title and package name, never control text, and a window id is not treated as a long-term
identity.

If `VSCREEN_NOT_RUNNING` appears, first confirm that the device channel remains connected, then tap the creation button. After closing the screen, losing the channel or revoking authorization, reconnect and create it again. Clearing application data or extracting the environment again is unnecessary.

If the creation button reports another error, open **Settings → Self-check and diagnostics**, inspect the records and export logs. Enabling accessibility cannot replace the device channel or remove vendor-system compatibility limitations.

When finished, tap **Close** on the virtual-screen page to release the temporary virtual screen. Leaving the preview and closing the virtual screen are separate operations; use **Close** when you want it to end.
