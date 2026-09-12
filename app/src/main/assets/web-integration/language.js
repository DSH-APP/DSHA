/* 原生语言通过实际 locale 服务应用；只处理应用 UI，不改聊天或文件内容。 */
function installDshaLanguageBridge(locale) {
    if (typeof window === 'undefined' || typeof document === 'undefined') return () => {};
    const valid = value => value === 'zh' || value === 'en';
    const apply = () => {
        const language = window.__DSHA_LANGUAGE__;
        if (!valid(language)) return;
        if (locale.getSnapshot().active !== language) locale.setLocale(language);
        document.documentElement.lang = language;
    };
    window.addEventListener('dsha-language', apply);
    apply();
    return () => window.removeEventListener('dsha-language', apply);
}
