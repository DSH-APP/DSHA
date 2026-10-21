# Opening and using the virtual screen

Accessibility reads controls and supports Chinese text input. **After enabling accessibility, you must still create the virtual screen separately.** `VSCREEN_NOT_RUNNING` means no virtual screen is currently running; it does not mean the environment needs to be reinstalled.

1. Use the **Standard edition** on a phone running **Android 11 or later**. The Low compatibility edition does not currently support virtual screens.
2. Open DSHA **Settings → Device capability authorization** and connect **one of ADB, Shizuku or Root**. Without Root, enable **Wireless debugging** in Android's developer options, then complete pairing and connection on DSHA's ADB pairing page. Confirm that the channel is connected. Merely enabling the system wireless-debugging switch does not connect it to DSHA.
3. Under **Device capability authorization**, tap **Open virtual screen**, then **Create portrait screen** or **Create landscape screen**. Wait for an image and for the page to show that the virtual screen is active. **Usage instructions** are available at the top of the page at any time.
4. Tap **Applications → Choose**, select the application to operate, then tap **Launch**. It appears on the virtual screen, where you can tap and swipe in the preview.
5. To read the control tree or enter Chinese text, enable **DSHA Accessibility**. To let the assistant operate it, also confirm DSHA's screen-reading and action authorization prompts for this attempt, and choose the virtual screen as the target.

If `VSCREEN_NOT_RUNNING` appears, first confirm that the device channel remains connected, then tap the creation button. After closing the screen, losing the channel or revoking authorization, reconnect and create it again. Clearing application data or extracting the environment again is unnecessary.

If the creation button reports another error, open **Settings → Self-check and diagnostics**, inspect the records and export logs. Enabling accessibility cannot replace the device channel or remove vendor-system compatibility limitations.

When finished, tap **Close** on the virtual-screen page to release the temporary virtual screen. Leaving the preview and closing the virtual screen are separate operations; use **Close** when you want it to end.
