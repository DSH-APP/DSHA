# DSHA 0.2.1-rc.3-20261029.1845-dsh0.2.1-rc.3 / build209

- 应用名改为 DSH-A-Su（Sunset Mod），applicationId 改为 io.github.sunsetrne.dsha；与原版 dsh.android.client 并行安装、数据完全隔离，不覆盖旧版数据。
- 发布签名新建：不再使用历史 E7E3 证书，改用 Mod 自有发布证书；verifyPublishCertificate 改为校验新证书与新包名，期望哈希记录在 ci/release-identity.json。
- 上岛变体并入正式包：上岛能力进入常驻包，不再单独出包，也不再有独立包名。
- 放弃 Android 10 及以下：删除 low / GeckoView 兼容线，最低 Android 11（API 30），仅 arm64-v8a。
- 无障碍声明改为多变体共用：声明放在 main manifest，不限定 packageNames，也不再由 flavor 覆盖；标签与描述更新为「DSH-A-Su 屏幕控制」并精简。
- 仍保留 zh、zh-Hant、en、ru、ja、ko、ar、pt、la、fr 十种语言。
- fr、pt、ru、ar、la 的界面短标签进一步精简，减少占屏。
- 修复拉丁语（la）「下一步」的损坏文案。
- 固定构建输入改为公开仓库的预发布 Release 资产，以 SHA-256 锁定；公开仓库可直接还原完整构建输入（见[固定构建输入文档](../../docs/fixed-build-inputs.md)）。
- 包名与证书相关的发布工具、CI 断言同步更新；构建与门禁结果以仓库回执为准。
