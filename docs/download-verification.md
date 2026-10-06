# APK 下载校验与签名证书

发布页的 `.apk.sha256` 校验的是**整个 APK 文件的字节**。文件管理器“校验签名”页面的“签名 SHA-256”校验的是**签名证书**，两者不能互相比较。Standard 与 Low 的 APK 文件摘要不同；它们使用同一张历史发布证书。

[#97](https://github.com/DSH-APP/DSHA/issues/97) 的截图显示的是证书指纹，值为：

```text
e7e3a31a75946f2669194c972b3dd0c9aea3fc7c50a8b885d2dee710b22a53f5
```

这与本项目历史 E7E3 发布证书一致。截图没有展示 APK 文件 SHA-256，因此不能据此判断 APK 文件已完整下载。

以 [v0.1.7-rc2](https://github.com/DSH-APP/DSHA/releases/tag/v0.1.7-rc2) 为例：

| 文件 | 字节数 | APK 文件 SHA-256 |
|---|---:|---|
| `dsha-0.1.7-rc2.apk` | 273,621,504 | `f8115ddffa119479f6378653b78e9adb1b23b5abf819a3f0f2602463f64d9b75` |
| `dsha-0.1.7-rc2low.apk` | 349,864,634 | `b7b778291239a09149c712327c52cb5257001df4448bfb04d5b1ea04db43e64b` |

手机上请对下载的 APK 选择“文件校验 / 计算哈希 / SHA-256”，将结果与**同一个版本、同一个 flavor 的 `.apk.sha256` 文件内容**比较；不要选择“签名 SHA-256”，也不要计算 `.sha256` 小文件本身的哈希。

Windows PowerShell：

```powershell
Get-FileHash -LiteralPath '.\dsha-0.1.7-rc2.apk' -Algorithm SHA256
```

Linux / macOS（在 APK 和对应侧车文件所在目录执行）：

```sh
sha256sum -c dsha-0.1.7-rc2.apk.sha256
# macOS 也可使用：shasum -a 256 -c dsha-0.1.7-rc2.apk.sha256
```

若 APK 文件摘要不一致，重新从该版本的 GitHub Release 下载 APK 和同名 `.apk.sha256`。保留现有应用数据；不需要卸载或清除存储来做文件校验。
