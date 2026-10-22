# Verify an APK download and its signing certificate

Verification date: 2026-10-21. Source: the complete current Chinese download-verification guide. Published F10 `087724c` is a reference baseline; the example release below is historical and does not identify the current downloadable APK. This translation does not add a new remote-download, package, signature, or device check.

A release's `.apk.sha256` contains the SHA-256 of **the entire APK file's bytes**. A file manager's signature-check page displays the SHA-256 of **the signing certificate**. These are different hashes and cannot be compared. Standard and Low APK files have different whole-file hashes while sharing the historical release certificate.

The screenshot in [historical issue #97](https://github.com/DSH-APP/DSHA/issues/97) shows this certificate fingerprint:

```text
e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5
```

It matches this project's historical E7E3 release certificate. The screenshot does not show the APK's whole-file SHA-256, so it does not establish that the APK download is complete.

For the historical [v0.1.7-rc2 release](https://github.com/DSH-APP/DSHA/releases/tag/v0.1.7-rc2):

| File | Bytes | APK file SHA-256 |
|---|---:|---|
| `dsha-0.1.7-rc2.apk` | 273,621,504 | `f8115ddffa119479f6378653b78e9adb1b23b5abf819a3f0f2602463f64d9b75` |
| `dsha-0.1.7-rc2low.apk` | 349,864,634 | `b7b778291239a09149c712327c52cb5257001df4448bfb04d5b1ea04db43e64b` |

On a phone, select **File checksum / Calculate hash / SHA-256** for the downloaded APK. Compare the result with the **contents of the `.apk.sha256` for the same version and flavor**. Do not select **Signature SHA-256**, and do not calculate the hash of the small `.sha256` file itself.

Windows PowerShell:

```powershell
Get-FileHash -LiteralPath '.\dsha-0.1.7-rc2.apk' -Algorithm SHA256
```

Linux / macOS, from the directory containing the APK and its matching sidecar:

```sh
sha256sum -c dsha-0.1.7-rc2.apk.sha256
# macOS can also use:
# shasum -a 256 -c dsha-0.1.7-rc2.apk.sha256
```

If the APK file hash differs, download the APK and matching `.apk.sha256` again from that version's GitHub Release. Keep the existing app data. File verification does not require uninstalling DSHA or clearing storage.
