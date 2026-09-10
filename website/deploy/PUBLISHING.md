# 网站发布与回退

1. 在 `website` 目录执行 `npm ci --ignore-scripts` 安装锁定的构建/测试依赖。从最终标准版与兼容版 APK 生成统一发布清单，运行 `npm run build`、`npm run check` 和 `node scripts/package.mjs`。仅改网页时使用当前线上 APK 对应的清单；新 APK 未发布前，不把更新接口提前切到新版本。
2. 读取 `artifacts/deployment-manifest.json`，将网页归档和两个 APK 上传到服务器 `/srv/dsha.cc/uploads/BUILD_ID`。只部署 `dist` 产物，不上传源码、取证目录、连接信息或私钥。
3. 按实际服务器配置核对 Nginx。`nginx-dsha-https.conf` 是 dsha.cc 的配置参考，其中 `/.well-known/assetlinks.json` 使用精确规则，其他隐藏文件禁止访问。配置变更前保留原文件，执行 `nginx -t` 后再重载。
4. 执行 `bash update-web-release.sh BUILD_ID ARCHIVE_SHA256 VERSION`。脚本校验全部文件后原子切换 `current`，保留旧版本下载目录；健康检查失败时还原之前的链接。
5. 运行 `node scripts/http-check.mjs https://dsha.cc`，核对 `/health.json` 的 buildId 和 `/api/updates.json`。从公网下载 APK 并核对摘要，确认页面、APK Range 请求及错误页面可用。

脚本要求服务器已配置 HTTPS，并已有 `/srv/dsha.cc/current` 指向该目录下的一个版本。首次部署需根据实际服务器完成配置；不要盲目覆盖现有站点。

后续生成清单时以 `--previous-manifest` 提供上一份线上 `/api/updates.json`，保留另一更新通道。清单的首个版本是官网当前展示版本；旧下载地址必须继续可用。

发布已验收的 `0.1.5-rc1` 正式版时显式使用 `--channel stable`，并传入 `website/data/current-release-notes.txt`。该通道与 GitHub 正式 Release 一致；保留的旧预览条目和旧下载文件不改写。内置插件版本随 APK 更新，社区插件和技能仍显示各自实际核对的 dsh / DSHA 版本和日期。
