# 网页内核、桌面 UA 与加载失败

核验日期：2026-10-21。参考基线：已发布 F10 `087724c`，并对照本轮工作树源码。软件机制说明不表示本轮 WebView/Gecko 手机验收或帧率测试已完成。

## 当前选择

| flavor / 设置 | 实际选择 |
|---|---|
| Standard | 系统 WebView；不携带 Gecko 回退实现 |
| Low：显式 Gecko | 随包 GeckoView |
| Low：自动，API<26 | 优先 Gecko |
| Low：自动，API≥26 | 有可识别系统 WebView provider 时先用 WebView；provider 缺失/查询失败时优先 Gecko |

入口在 [ConfigFragment](../app/src/main/java/com/deepseekharness/app/ui/ConfigFragment.java)。“网页内核”在 Standard 只显示 System WebView，Low 提供“自动选择内核 / Gecko”；默认 Gecko 偏好为 false。保存改变后续选择，不在已载入页面中替换活着的内核。实际分支见 [Standard PreviewFallback](../app/src/standard/java/com/deepseekharness/app/ui/PreviewFallback.java) 与 [Low PreviewFallback](../app/src/low/java/com/deepseekharness/app/ui/PreviewFallback.java)。Low 的系统 WebView 路径按实际页面所需能力检查，不用 UA 版本号推断页面必然失败。

[WebPreviewActivity](../app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java) 实现系统 WebView；[GeckoPreviewActivity](../app/src/low/java/com/deepseekharness/app/ui/GeckoPreviewActivity.java) 仅编入 Low。宿主/容器引擎 proot、proroot 与网页内核是独立选择，换网页内核不代表替换 Ubuntu 或 DSH。

## “使用桌面版网页布局”到底改变什么

此项默认关闭，保存到 `desktopMode`。创建 WebView 时，[WebPreviewPolicy.desktopUserAgent](../app/src/main/java/com/deepseekharness/app/util/WebPreviewPolicy.java) 替换 Android 平台标识并移除 Mobile 标识，保留真实 Chrome 版本；Gecko 创建会话时选择 desktop user-agent mode。它影响网页对 UA 的响应，不创建桌面分辨率、不关闭移动插件、不承诺改写网页 viewport 或 CSS 布局。

UA 在创建网页实例时读取；保留中的同代网页继续使用原创建设置。改变后需重新创建网页实例才可用该 UA 核验，单纯从隐藏状态恢复不应被写成已切换。网页布局仍由页面 CSS、视口、移动插件与系统窗口共同决定。

## 鉴权、同源与恢复

正式预览只接受当前 DSH 的 `http://127.0.0.1:<实际端口>` 服务身份。Cookie 注入先完成异步结果，再继续导航，避免首次页面未认证；不向任意外部网站注入本机 Cookie。代次、实例、View 和导航票共同保护回调，旧页面/旧结果不能批准新页操作。

WebView 开启 JavaScript/DOM storage，关闭 file access、第三方 Cookie 和 mixed content；文件只能经受限 chooser、FileProvider、流桥处理。只接管顶层外部导航，插件 iframe 保持浏览器原行为。附件、分享保存与访问边界见[网页文件交互](web-file-sharing.md)和[安全模型](security-model.md)。

同代实例在旋转及全屏/PiP/隐藏切换时保留；停止或更换 DSH 身份后不继续使用旧页。系统回收应用进程或 renderer 后可能需要重新打开，不能承诺完整未发送表单始终恢复。PiP 详见[系统画中画](picture-in-picture.md)。

## DeepSeek 浏览器账号授权

F11对锁定DSH `0.2.1-rc.2`的BrowserLogin添加原生专用facade，避免同步预开`about:blank`后异步popup导航在两种内嵌浏览器里失配。异步目标由当前本机页的原生导航处理，只对固定 `https://platform.deepseek.com[:443]/dsh/authorize`免用户手势；其他外链仍遵守原手势要求。原生独立核对当前页、实际本机源和精确授权目标，不接受任意相似域名或路径。

Gecko仅在收到带有效`observationNonce`的原生port消息后启用nativeOAuth标记，网页自行出现同名标记不能替代原生检查。callback仍由同一动态Web端口的`/oauth/callback`处理，DSH原有state常时比较与PKCE不因本次导航适配放宽；重复参数、错误方法/路径和过期/取消都需按真实服务拒绝，未知结果不重放。LAN callback的路径/参数保留由[LAN代理](lan.md)另行约束。

入口为[DeepSeekOAuthNavigation](../app/src/main/java/com/deepseekharness/app/util/DeepSeekOAuthNavigation.java)及[固定运行时补丁](../app/src/main/assets/deepseek-native-oauth-patch.json)，关联回归为[Java导航检查](../app/src/test/java/com/deepseekharness/app/util/DeepSeekOAuthNavigationTest.java)和[锁定官方模块浏览器/HTTP检查](../tools/test-deepseek-native-oauth.mjs)。F11 Chromium/Firefox各8组及Java10项通过，覆盖错误state/重复参数/方法/路径、断开watch、PKCE单次兑换、取消/过期与兑换失败；保留过宽诊断边界的初轮失败及修正。它们没有使用真实平台账号凭据，也没有手机、系统外部浏览器或完整移动OAuth验收，不能断言修复是某张历史截图的唯一因果解释。

## 失败的含义

| 现象 | 处理边界 |
|---|---|
| WebView 初始化失败 / 必需能力检查失败 | Low 可尝试 Gecko；Standard 提示启用或更新 Android System WebView/Chrome，或外部浏览器打开 |
| 当前 WebView renderer 崩溃 | 先销毁死页面和资源；Low 可一次尝试 Gecko，Standard 显示重试提示 |
| renderer 因内存被系统回收 | 显示重新打开提示，不按崩溃自动归因 |
| HTTP401/403 | 有界重取当前鉴权；仍失败则返回启动页重新进入，不能沿用旧 token 无限循环 |
| 普通连接错误、服务慢启动 | 查看启动页与日志；不是所有网络错误都触发换内核 |

自动回退要求仍是本轮 startup identity、有效同源地址且页面宿主未结束；[openGeckoAfterWebViewFailure](../app/src/main/java/com/deepseekharness/app/ui/WebPreviewActivity.java) 标记一次尝试，先取消文件/麦克风请求并销毁旧 View。Standard 的回退实现返回 false，不能把 Low 机制宣传成标准包自带备用内核。

麦克风只对当前本机页面的纯音频请求按需申请，`RECORD_AUDIO` 与 `MODIFY_AUDIO_SETTINGS` 分工不同；不在普通启动时预授权。摄像头/屏幕音频不放行，旧页面的权限回调不能批准新页。实现见 [BrowserMicrophone](../app/src/main/java/com/deepseekharness/app/ui/BrowserMicrophone.java)。

核验入口包括 [WebPreviewPolicyTest](../app/src/test/java/com/deepseekharness/app/util/WebPreviewPolicyTest.java)、[PreviewPageSessionTest](../app/src/test/java/com/deepseekharness/app/util/PreviewPageSessionTest.java)、[BrowserUploadRequestStateTest](../app/src/test/java/com/deepseekharness/app/util/BrowserUploadRequestStateTest.java) 与 [BrowserDiagnosticTest](../app/src/test/java/com/deepseekharness/app/util/BrowserDiagnosticTest.java)。本轮只核对实现；系统 provider、媒体权限和 renderer 行为仍需设备证据。
