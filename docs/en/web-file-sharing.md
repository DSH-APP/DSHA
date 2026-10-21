# WebView file sharing and saving

Review date: 2026-10-21. Reference baseline: published F10 `087724c` and the current build190 working tree. This page is not a receipt for system-chooser or recipient-application testing during this work.

Since build164, DSHA's production chat page has provided file APIs. The bundled mobile UI 3.0.5 integrated them at that time; the current locked mobile UI is 3.2.1. Tapping Share in the file tree or preview opens the Android chooser flow. See [bridge and files](bridge-and-files.md) for the native bridge, DocumentsProvider, and file read/export boundaries.

Third-party Web plugins can pass a `File` or `Blob`:

```js
const file = new File(['文件内容'], 'report.md', { type: 'text/markdown' });
if (window.DSHA?.canShareFile(file)) {
  await window.DSHA.shareFile(file);
} else if (window.DSHA?.saveFile) {
  await window.DSHA.saveFile(file);
}
```

- `canShareFile(file)`: the single-file sharing limit supported by this API is 32 MiB.
- `shareFile(file)`: after a complete transfer, opens Android's share chooser and returns `{status: 'chooser-opened'}`. The user must still complete sharing in the chooser and recipient application; this result does not mean a file has been sent to anyone.
- `saveFile(file)`: after a complete transfer, lets the user choose a destination. It returns `{status: 'saved'}` only after writing and successful read-back verification. Cancellation rejects the Promise with the error name `AbortError`; write or verification failures also reject. The maximum file size follows the 2 GiB download limit.
- `File.name` is retained as the display name, with path characters removed by the native side. A plain `Blob` uses `download.bin`, so constructing a named `File` is recommended.

Blob/Data downloads from `a[download]` in the current top-level same-origin page also retain the filename from the `download` attribute and use the same native save flow.

The interface is injected only into the current production DSH top-level page in a system WebView that supports WebMessageListener. It is bound to the actual loopback port and runtime instance. Ordinary browsers and Gecko should detect it first, then fall back to Web Share or their own download capabilities. The interface accepts existing file bytes from the page, not local paths. Sharing uses a content URI in the application's cache and temporary read permission. The share cache remains available for recipient applications to read; the next share cleans up temporary files created by this API that are older than 24 hours.

Related: [issue99](https://github.com/DSH-APP/DSHA/issues/99).
