# DSHA 网站与插件目录

目标域名为 https://dsha.cc。仓库中的本地站点版本来自**最终签名 APK 发布清单**，并与所选 `DSHA_SOURCE_ROOT` 的应用版本、DSH 锁和 runtimeId 核对；`package.json` 的 0.0.0 只属于私有构建器，不是 DSHA 版本。

2026-09-28 的 build147 部署记录属于[历史官网验收](../docs/website-rc2-20260928.md)，历史说明字节保存在 `data/history/build147-release-notes.txt`。本轮只准备和验证本地网页；没有线上新版本或部署通过声明。

## 构建

使用 Node 24 与 tar。先在仓库根目录运行 `tools/generate-release-manifest.py`，从实际 `release` 两版最终 APK 提取包名、版本、DSH/runtimeId、证书和摘要；提供实际 `--standard`、`--low`、`--build-tools`、`--java` 与本轮 `--notes`。正式通道显式使用 `--channel stable`，不能仅凭版本名中的连字符猜通道。没有最终 APK 时不要为检查编造清单。

```text
npm ci --ignore-scripts
npm run build
npm run check
```

默认清单为 `app/build/release-manifest.json`；可通过 `DSHA_RELEASE_MANIFEST` 指定。默认源为网站父目录；`DSHA_SOURCE_ROOT` 必须指向与目标 APK 一致的源码/描述符。仅更新线上网页时，选择线上 APK 与对应源码快照，不将未发布 APK 的更新接口提前部署。旧清单与新源码不匹配时构建明确失败。

`dist` 是唯一公开产物。构建重新核对两版实际 APK 摘要、复制下载文件及 sidecar，并生成 catalog/releases/updates 与域名关联数据。新清单使用 `--previous-manifest` 保留另一更新通道；旧下载目录由部署步骤保留。

## 内容维护

- `data/catalog.mjs` 保存社区插件实际来源与历史测试；内置版本由对应受管包读取，并说明内容核对不等于设备验证。
- `agent-skills/` 是仓库根的唯一技能源；网站直接复制 SKILL.md 和 MIT 许可。`src/skills/` 仅保留来源提示。技能包文件名跟随实际 APK 版本，当前文档检查与旧设备记录分开。
- `scripts/build.mjs` 生成静态页面与 API；`scripts/site.test.mjs` 检查来源、版本、实际下载内容与内部链接。
- `src/packages` 的社区原包保持固定版本与摘要，不重新打包修改作者字节。仅格式审阅不声称设备实测。

内置条目打开应用管理页；第三方链接进入当前自动解析/检查/提交路径。安装检查和脚本开放不提供恶意插件沙箱。网站不接收桥 token，不伪造手机安装状态。

## 发布与验证范围

本地预览使用 `npm run dev`，只监听 127.0.0.1:4180，不作为服务器。发布/回退见 [PUBLISHING](deploy/PUBLISHING.md)。只上传 dist 及核验过的下载文件；源码、历史私有原件、连接资料、取证、令牌和私钥不部署。

`npm run check` 证明本地生成物，不能证明 Nginx 配置、GitHub 发布、公网 APK Range/HSTS 或 Android 覆盖安装。HTTPS 上的 `scripts/http-check.mjs` 会检查下载安全头和 Range，但本轮不执行线上部署。实际服务器切换与公网下载必须另存绑定版本、buildId 和摘要的回执。
