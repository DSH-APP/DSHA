# 网站发布与回退

本文件是未来部署流程；本轮不上传、切换线上链接或重载服务器。源码文件存在与本地检查不构成部署证据。

1. 选择实际最终 Standard/Low APK 及对应源码快照，从 APK 生成发布清单；提供本轮说明与上一份线上清单，保持已有 stable/preview 通道。仅改线上网页时使用线上 APK 对应的 source root，不能用旧清单配新受管源码。
2. 在 website 执行 `npm ci --ignore-scripts`、`npm run build`、`npm run check`，再运行 `node scripts/package.mjs`。检查 `artifacts/deployment-manifest.json` 的实际文件摘要。
3. 在获得部署授权后，只上传网页归档和两版核验 APK 至服务器本次 uploads 编号槽位。dist 是唯一公开网页目录，不上传源码、tools/history、取证、连接信息或密钥。
4. 按真实服务器核对 `nginx-dsha-https.conf`。下载 location 有自己的 add_header，必须同时声明 HSTS、nosniff、Referrer-Policy 与 CSP；否则不会继承 server 安全头。`/.well-known/assetlinks.json` 使用精确规则，其余隐藏文件禁止访问。保存原配置，执行 `nginx -t` 成功后再重载。
5. 按实际目录执行 `bash update-web-release.sh BUILD_ID ARCHIVE_SHA256 VERSION`。脚本校验内容后切换 current，保留旧下载目录，健康失败恢复之前链接。首次部署须按真实服务根完成配置，不能盲目覆盖已有站点。
6. 执行 `node scripts/http-check.mjs https://dsha.cc`，核对 health/buildId、API、APK HEAD/Range、HSTS/CSP/nosniff/Referrer-Policy 和 Content-Disposition。再从公网下载两 APK，核对完整摘要及 sidecar；仅 HEAD 或 nginx -t 不代替实际下载。

实际部署记录写明源快照、manifest/APK/归档摘要、前后链接、配置语法检查、公网响应和回退结果。过去 build147 上线回执只属于该版。没有执行对应服务器操作时，状态保持“本地准备/检查，未部署”。
