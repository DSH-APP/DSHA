/* 原生语言通过实际 locale 服务应用；只处理应用 UI，不改聊天或文件内容。 */
function installDshaLanguageBridge(locale) {
    if (typeof window === 'undefined' || typeof document === 'undefined') return () => {};
    const valid = value => value === 'zh' || value === 'en' || value === 'ar';
    const apply = () => {
        const language = window.__DSHA_LANGUAGE__;
        if (!valid(language)) return;
        if (locale.getSnapshot().active !== language) locale.setLocale(language);
        document.documentElement.lang = language;
        // 阿拉伯语要整体镜像：dir=rtl 让 WebView 内的文本、图标与滚动条换边，
        // 中文/英文写回 ltr，来回切换不会残留方向。
        document.documentElement.dir = language === 'ar' ? 'rtl' : 'ltr';
    };
    window.addEventListener('dsha-language', apply);
    apply();
    return () => window.removeEventListener('dsha-language', apply);
}
