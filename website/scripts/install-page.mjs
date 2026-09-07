export function installLink(entry) {
  const params = entry.kind === 'builtin' ? {builtin:entry.packageName} : {
    url:entry.installSource || entry.download.url, sha256:entry.download.sha256, name:entry.packageName, version:entry.version
  };
  return '/install/?' + new URLSearchParams(params).toString();
}

export const installPage = `<div class="prose"><header class="page-head"><p class="eyebrow">INSTALL WITH DSHA</p><h1>在手机上确认并安装插件。</h1><p class="lede">DSHA 会读取实际插件包，显示名称、作者、版本和兼容范围。确认安装后准备依赖，完成后重启 Web。</p></header>
<section class="panel" data-install-page><h2>交给 DSHA 继续</h2><p data-install-status role="status">粘贴插件链接，然后选择“在 DSHA 中打开”。需要 DSHA rc1.3 或更新版本。</p>
<form data-install-form class="js-only"><label for="plugin-source">插件链接或 npm 包名</label><input id="plugin-source" name="url" type="text" maxlength="6000" autocomplete="off" placeholder="https://github.com/作者/插件 或 @作者/插件" required>
<div class="actions"><button class="button primary" type="submit">生成安装入口</button></div></form>
<div class="actions" data-install-actions hidden><a class="button primary" data-open-app>在 DSHA 中打开</a><a class="button" data-open-scheme>备用唤起</a></div>
<div class="install-sharing" data-install-sharing hidden><p class="small-text muted" data-install-qr-note></p><div class="install-qr" data-install-qr></div><label for="install-share-url">完整安装链接</label><div class="copy-row"><input id="install-share-url" readonly><button class="button" type="button" data-copy-target="install-share-url">复制到手机</button></div></div>
<p class="small-text muted" data-install-note></p><noscript><p>请启用 JavaScript 生成唤起链接，或将插件链接粘贴到 App 的插件市场。</p></noscript></section>
<section class="panel"><h2>还没安装 DSHA？</h2><p>先下载适合手机的 APK，完成首次初始化，再返回此页打开插件。若浏览器没有唤起 App，可点“备用唤起”或在 App 中粘贴原链接。</p><div class="actions"><a class="button primary" href="/download/">下载 DSHA</a><a class="button" href="/guide/#plugin">安装指南</a></div></section></div>`;
