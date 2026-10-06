# Verify an APK download and its signing certificate

A release's `.apk.sha256` contains the SHA-256 of **the entire APK file**. A signature checker displays the SHA-256 of **the signing certificate**. These are different hashes. Standard and Low APK files have different hashes and share the historical release certificate.

The screenshot in [#97](https://github.com/DSH-APP/DSHA/issues/97) shows this certificate fingerprint:

```text
e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5
```

It matches the historical E7E3 release certificate. The screenshot does not show the whole-file APK hash, so it does not establish whether the download is complete.

For [v0.1.7-rc2](https://github.com/DSH-APP/DSHA/releases/tag/v0.1.7-rc2):

| File | Bytes | APK file SHA-256 |
|---|---:|---|
| `dsha-0.1.7-rc2.apk` | 273,621,504 | `f8115ddffa119479f6378653b78e9adb1b23b5abf819a3f0f2602463f64d9b75` |
| `dsha-0.1.7-rc2low.apk` | 349,864,634 | `b7b778291239a09149c712327c52cb5257001df4448bfb04d5b1ea04db43e64b` |

On a phone, choose **file checksum / hash / SHA-256** for the downloaded APK. Compare it with the **contents of the `.apk.sha256` for the same release and flavor**. Do not compare the certificate hash or the hash of the small sidecar file.

On Windows:

```powershell
Get-FileHash -LiteralPath '.\dsha-0.1.7-rc2.apk' -Algorithm SHA256
```

On Linux, run `sha256sum -c dsha-0.1.7-rc2.apk.sha256` in the download directory. On macOS, use `shasum -a 256 -c dsha-0.1.7-rc2.apk.sha256`.

If the APK file hash differs, download the APK and matching sidecar again from that release. File verification does not require uninstalling DSHA or clearing its data.
