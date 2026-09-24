# DSHA 网站与插件目录

目标域名：https://dsha.cc。默认页面为插件市场，当前本地产物为 DSHA 0.1.6-alpha2 / dsh 0.1.6-alpha.2，公开部署尚未完成；线上版本以公开更新清单为准。社区插件与技能保留各自的历史测试版本。

## 构建

使用 Node.js 24 与 tar。在 `website` 目录执行，安装锁定的构建和测试依赖：

```text
npm ci --ignore-scripts
npm run build
npm run check
```

构建前先在仓库根目录用 `tools/generate-release-manifest.py` 核验 `release` 中的两个最终 APK，指定 `--standard`、`--low`、`--build-tools`、`--java` 和 `--notes`。当前 alpha1 版本使用 `data/current-release-notes.txt`，按已确认的正式发布要求显式传入 `--channel stable`：文件名修正不改变既有发布通道，不能仅凭连字符判断通道。默认输出 `app/build/release-manifest.json` 及 GitHub 发布正文；以后发版时用 `--previous-manifest` 提供上一份线上清单，或保留现有输出供自动读取，避免丢失另一更新通道。

网站读取这份清单，核对两个 APK 的摘要后复制到 `dist/downloads`，同时生成 App 的 `/api/updates.json`。默认源码目录是当前项目父目录，可通过 `DSHA_SOURCE_ROOT` 指定；清单路径可用 `DSHA_RELEASE_MANIFEST` 指定。

`dist` 是唯一公开部署目录。不要发布 `original`、取证目录或部署连接信息。

## 内容维护

- `data/catalog.mjs`：插件目录、测试范围和兼容说明；应用版本由实际 APK 清单注入。
- `src/skills`：随网站分发的 rc1.1 适配技能，保留 MIT 许可。
- `src`：样式、主题及搜索/复制/投稿增强。
- `scripts/build.mjs`：生成静态页面、目录 API、APK/技能校验文件和 sitemap。
- `original`：只在本机保留的原网站备份，不提交或部署。

内置条目打开 App 管理页；技能下载后放入 dsh 技能目录。rc1.3 支持网页安装深链，App 解析实际包后由用户确认安装；未安装 App 时提供 APK 下载入口。网站不伪造安装状态，不接收本机桥 token。

社区插件通过 GitHub Issue 人工审核。新增 `kind: 'plugin'` 条目须包含 HTTPS 固定版本下载地址、SHA-256、入口契约、使用前提和真实测试记录。仅格式校验不得宣称设备实测通过。

## 发布与验证

上传 `dist` 完整内容，包括两个 APK 与对应 `.sha256`。服务器使用独立站点目录与 HTTPS，保留上一版以便回退。发布前后检查首页、详情、技能下载、APK HEAD/Range、错误页面、目录 API 与 `health.json`。

具体流程见[发布与回退](deploy/PUBLISHING.md)。连接凭据、既有服务器操作记录和原始取证材料留在本机。

本地预览：`npm run dev`，仅监听 127.0.0.1:4180。远端部署不使用这个开发服务器。
