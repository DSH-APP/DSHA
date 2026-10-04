"""One-time current website copy fixes; public version comes from actual APKs."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[3]
WEB = ROOT / "website"

def write(relative, value):
    (WEB / relative).write_text(value, encoding="utf-8", newline="\n")

for relative in ("package.json", "package-lock.json"):
    document = json.loads((WEB / relative).read_text(encoding="utf-8"))
    document["version"] = "0.0.0"
    if relative == "package-lock.json":
        document["packages"][""]["version"] = "0.0.0"
    else:
        document["description"] = "Private site builder; public DSHA versions are supplied by the verified APK manifest."
    write(relative, json.dumps(document, ensure_ascii=False, indent=2) + "\n")

notes = WEB / "data/current-release-notes.txt"
history = WEB / "data/history/build147-release-notes.txt"
if notes.exists():
    history.parent.mkdir(parents=True, exist_ok=True)
    original = notes.read_bytes()
    if history.exists() and history.read_bytes() != original:
        raise RuntimeError("historical release notes differ; do not overwrite originals")
    if not history.exists():
        history.write_bytes(original)
    if history.read_bytes() != original:
        raise RuntimeError("historical release note byte check failed")
    notes.unlink()

catalog = (WEB / "data/catalog.mjs").read_text(encoding="utf-8")
catalog = catalog.replace("    testedDsha: '0.1.7-rc2', testedDsh: '0.1.7-rc.2', checkedAt: '2026-09-28',\n", "")
catalog = catalog.replace("DSHA 0.1.7-rc2，内置 dsh 0.1.7-rc.2", "当前同签名 DSHA")
catalog = catalog.replace("DSHA 0.1.7-rc2", "当前同签名 DSHA")
# Builtin version/source/verification fields are hydrated from the actual APK
# identity and its corresponding checkout. Community historical rows stay intact.
for version in ("3.0.3", "0.1.19", "0.1.4"):
    catalog = catalog.replace(f"icon: 'layout', version: '{version}',", "icon: 'layout',")
    catalog = catalog.replace(f"icon: 'terminal', version: '{version}',", "icon: 'terminal',")
    catalog = catalog.replace(f"icon: 'bell', version: '{version}',", "icon: 'bell',")
    catalog = catalog.replace(f"icon: 'layers', version: '{version}',", "icon: 'layers',")
catalog = catalog.replace("当前版本内置 3.0.3，支持手机快捷键搜索", "内置版本由 APK 清单和对应受管包核对，支持手机快捷键搜索")
catalog = catalog.replace("verification: '已与本次两版正式 APK 的内置包版本核对；Android 13 上的 DSH 启动、网页与插件列表检查通过，不表示每项通知、悬浮或设备能力都在本轮逐项复验。'", "verification: '构建时核对实际签名 APK 身份与对应受管包版本；不以历史 Android 13 记录代替本轮设备验收。'")
start = catalog.index("  {\n    id: 'device-shell',")
skills = '''  {
    id: 'device-shell', name: '手机命令操作', packageName: 'device-shell',
    kind: 'skill', category: 'device', icon: 'terminal', version: 'current-interface-doc',
    testedDsha: '当前设备未复验', testedDsh: 'unknown', checkedAt: '2026-10-03',
    historicalVerification: {testedDsha: '1.2.0-rc1.1', testedDsh: '0.1.2-rc.1', checkedAt: '2026-09-06'},
    summary: '先核对设备通道和实际身份，再执行授权命令并验证结果。',
    description: '使用 DSHA 受管设备入口的 Agent Skill；Root、Shizuku 或 ADB 在原生层发送前选择。它是可读、可复用的工作流文档，不通过插件包导入器安装。',
    author: 'DSHA 项目', source: 'agent-skills/device-shell', license: 'MIT',
    requirements: ['支持 Agent Skills 的当前环境', '设备能力授权中 Root、Shizuku 或 ADB 至少一个已就绪', '先用 id 核验实际身份和目标，不假设 uid=2000'],
    permissions: ['使用用户已授权的设备通道', '设备策略与短信/屏幕等独立能力授权继续生效；技能不自行授予权限'],
    steps: ['下载技能并保留 device-shell/SKILL.md 目录结构。', '将目录放入当前 Agent 的技能搜索目录。', '先查询设备身份、型号和 Android 版本；结果未知时不换通道重放。'],
    example: '“核对这台手机的身份、型号和 Android 版本，只读取信息。”',
    limitations: ['不是 dsh bundle，不能从“导入插件包”安装。', '同 UID 的 guest 和插件不是独立恶意代码沙箱。', '当前文档只做源码/内容检查；历史设备记录不适用于修改后的字节。'],
    verification: '技能源与网站分发字节一致；现行说明按受管接口校对。旧 rc1.1 测试记录单独保留，未执行本轮手机验证。',
    tags: ['Agent Skill', '设备通道', '可下载']
  },
  {
    id: 'screen-ocr-operator', name: '屏幕识别与操作', packageName: 'screen-ocr-operator',
    kind: 'skill', category: 'device', icon: 'scan', version: 'current-interface-doc',
    testedDsha: '当前设备未复验', testedDsh: 'unknown', checkedAt: '2026-10-03',
    historicalVerification: {testedDsha: '1.2.0-rc1.1', testedDsh: '0.1.2-rc.1', checkedAt: '2026-09-06'},
    summary: '用当前授权的读屏与截图结果核对界面、方向和坐标，再小步操作。',
    description: '使用实际原生截图/读屏接口；是否可用取决于系统、服务和本次授权。核对目标、画面与遮挡后操作，每个关键步骤重新验证。',
    author: 'DSHA 项目', source: 'agent-skills/screen-ocr-operator', license: 'MIT',
    requirements: ['当前 DSHA 设备通道与屏幕能力已授权', '需要视觉模型时使用用户选择且支持图像的服务', '发送截图前核对其中的私人内容'],
    permissions: ['可接触当前授权的屏幕与控件树', '普通文件截图可保存到应用私有目录；只清理本次创建的临时文件', '上传内容依用户选择的模型服务协议处理'],
    steps: ['下载技能并放入 Agent 技能搜索目录。', '先核对方向、目标应用、PiP/键盘遮挡及本轮屏幕授权。', '小步操作后重新核对；目标已改变或无法确认时停止。'],
    example: '“描述当前页面有哪些按钮，先不要点击或提交。”',
    limitations: ['不以旧 XML 坐标或裸 adb 绕过受管入口。', '活动配对期间不使用 uiautomator dump，以免抑制其他服务。', '当前设备、Agent 与模型组合尚未逐一复验。'],
    verification: '技能源与网站分发字节一致；文档按现行接口校对。旧 rc1.1 测试记录单独保留，不冒充当前设备结果。',
    tags: ['Agent Skill', '屏幕授权', '截图']
  }
];
'''
catalog = catalog[:start] + skills
write("data/catalog.mjs", catalog)

builder = (WEB / "scripts/build.mjs").read_text(encoding="utf-8")
replacements = {
    "DSHA rc1.1 适配版": "当前受管接口文档",
    "rc1.1 适配版": "当前接口文档",
    "打开 DSHA 查看实际插件信息，确认后安装，完成后重启 Web。": "打开 DSHA 解析实际包，完成检查后自动安装启用，再按提示重启 Web。",
    "本次已核对 rc1.1 两个 APK 中的 dsh 与内置插件版本。": "版本与受管内容来自实际两版 APK 清单，并核对所选源码及运行时身份。",
    "本站技能是 rc1.1 适配文档，具体效果依赖 Agent、设备授权与外部服务。": "本站技能文档按现行受管接口校对；历史测试记录单独保留，当前设备兼容性未逐项复验。",
    "`${e.id}-dsha-rc1.1.tar.gz`": "`${e.id}-dsha-${site.version}.tar.gz`",
    "e.testedDsha || '1.2.0-rc1.1'": "e.testedDsha || 'unknown'",
    "e.testedDsh || '0.1.2-rc.1'": "e.testedDsh || 'unknown'",
    "ADB 或 Shizuku：可执行设备命令": "Root、Shizuku 或 ADB：在发送前选择已授权的实际通道，可执行设备命令",
}
for old, new in replacements.items():
    builder = builder.replace(old, new)
write("scripts/build.mjs", builder)

write("README.md", '''# DSHA 网站与插件目录

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
''')

write("deploy/PUBLISHING.md", '''# 网站发布与回退

本文件是未来部署流程；本轮不上传、切换线上链接或重载服务器。源码文件存在与本地检查不构成部署证据。

1. 选择实际最终 Standard/Low APK 及对应源码快照，从 APK 生成发布清单；提供本轮说明与上一份线上清单，保持已有 stable/preview 通道。仅改线上网页时使用线上 APK 对应的 source root，不能用旧清单配新受管源码。
2. 在 website 执行 `npm ci --ignore-scripts`、`npm run build`、`npm run check`，再运行 `node scripts/package.mjs`。检查 `artifacts/deployment-manifest.json` 的实际文件摘要。
3. 在获得部署授权后，只上传网页归档和两版核验 APK 至服务器本次 uploads 编号槽位。dist 是唯一公开网页目录，不上传源码、tools/history、取证、连接信息或密钥。
4. 按真实服务器核对 `nginx-dsha-https.conf`。下载 location 有自己的 add_header，必须同时声明 HSTS、nosniff、Referrer-Policy 与 CSP；否则不会继承 server 安全头。`/.well-known/assetlinks.json` 使用精确规则，其余隐藏文件禁止访问。保存原配置，执行 `nginx -t` 成功后再重载。
5. 按实际目录执行 `bash update-web-release.sh BUILD_ID ARCHIVE_SHA256 VERSION`。脚本校验内容后切换 current，保留旧下载目录，健康失败恢复之前链接。首次部署须按真实服务根完成配置，不能盲目覆盖已有站点。
6. 执行 `node scripts/http-check.mjs https://dsha.cc`，核对 health/buildId、API、APK HEAD/Range、HSTS/CSP/nosniff/Referrer-Policy 和 Content-Disposition。再从公网下载两 APK，核对完整摘要及 sidecar；仅 HEAD 或 nginx -t 不代替实际下载。

实际部署记录写明源快照、manifest/APK/归档摘要、前后链接、配置语法检查、公网响应和回退结果。过去 build147 上线回执只属于该版。没有执行对应服务器操作时，状态保持“本地准备/检查，未部署”。
''')

print("website current copy and identity inputs updated; no deployment performed")
