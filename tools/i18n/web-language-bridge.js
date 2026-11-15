/* DSHA_UI_LANGUAGES_V3: fixed UI dictionaries only; user text stays verbatim. */
/* DSHA_WEB_ONLY_LANGUAGE_V1: 原生 App 只支持下列语言。网页里单独选的其它上游语言（如 es/de）
 * 只记在页面本地、不回传 App，也不会被同一 App 语言的再次注入覆盖；App 语言改变后恢复跟随 App。 */
const DSHA_NATIVE_LANGUAGES = ['zh', 'zh-Hant', 'en', 'ru', 'ja', 'ko', 'ar', 'pt', 'la', 'fr'];
const DSHA_WEB_LANGUAGE_KEY = 'dsha-web-only-language';
function dshaReadWebOnlyLanguage() {
    try {
        const value = JSON.parse(window.localStorage.getItem(DSHA_WEB_LANGUAGE_KEY) || 'null');
        return value && typeof value.language === 'string' && typeof value.app === 'string' ? value : void 0;
    } catch (_) {
        return void 0;
    }
}
function dshaWriteWebOnlyLanguage(value) {
    try {
        if (value === void 0) window.localStorage.removeItem(DSHA_WEB_LANGUAGE_KEY);
        else window.localStorage.setItem(DSHA_WEB_LANGUAGE_KEY, JSON.stringify(value));
    } catch (_) {}
}
/** 实际应显示的语言：App 注入的语言与选择时相同，就保留网页单独选的非原生语言。 */
function dshaEffectiveLanguage() {
    const app = window.__DSHA_LANGUAGE__;
    if (typeof app !== 'string' || !app) return app;
    const chosen = dshaReadWebOnlyLanguage();
    if (chosen === void 0) return app;
    if (chosen.app === app && !DSHA_NATIVE_LANGUAGES.includes(chosen.language)) return chosen.language;
    dshaWriteWebOnlyLanguage(void 0);
    return app;
}
/** 网页里选定语言：App 支持的照旧回传 App；其余只记在页面本地。 */
function dshaSelectWebLanguage(id) {
    const app = window.__DSHA_LANGUAGE__;
    if (typeof app !== 'string' || !app) return;
    if (!DSHA_NATIVE_LANGUAGES.includes(id)) {
        dshaWriteWebOnlyLanguage({ language: id, app });
        return;
    }
    dshaWriteWebOnlyLanguage(void 0);
    if (app !== id) {
        window.__DSHA_LANGUAGE__ = id;
        window.dispatchEvent(new CustomEvent('dsha-language-selected', { detail: id }));
    }
}
function installDshaLanguageBridge(locale) {
    if (typeof window === 'undefined' || typeof document === 'undefined') return () => {};
    // resolveActive/setLocale 位于模块级 LocaleRuntime，经 window 取用本作用域内的网页单独语言规则。
    window.__dshaEffectiveLanguage = dshaEffectiveLanguage;
    window.__dshaSelectWebLanguage = dshaSelectWebLanguage;
    // 上游自带 zh/en/ja/es/fr/de/ru/pt；DSHA 只补上游没有的语言，绝不重复注册上游语言。
    const languages = [
        ['zh-Hant', '繁體中文', 'zh'], ['ko', '한국어', 'en'], ['ar', 'العربية', 'en'], ['la', 'Latina', 'en']
    ];
    const disposers = [];
    const failures = [];
    // 单个语言或命名空间失败只跳过该项，不回滚其他语言；诊断经页面错误事件进入启动观察。
    const report = (scope, error) => {
        failures.push(scope);
        const issue = new Error('DSHA_UI_LANGUAGES ' + scope + ': ' + (error && error.message || String(error)));
        console.error(issue);
        if (typeof window.reportError === 'function') window.reportError(issue);
        else setTimeout(() => { throw issue; });
    };
    const known = id => locale.getSnapshot().locales.some(entry => entry.id.toLowerCase() === id.toLowerCase());
    const valid = value => typeof value === 'string' && known(value);
    const owned = [];
    for (const [id, label, fallback] of languages) {
        if (known(id)) {
            // 上游已提供该语言：沿用上游文案，DSHA 字典不再注册，避免 locale namespace 冲突。
            console.warn('DSHA_UI_LANGUAGES language ' + id + ' is provided upstream; DSHA dictionaries skipped');
            continue;
        }
        try {
            disposers.push(locale.addLanguage({ id, label, fallback }));
            owned.push(id);
        } catch (error) {
            report('language ' + id, error);
        }
    }
    // Generated from namespaced dictionaries of the exact locked bundles.
    // Each row is [key, [zh-Hant, ko, ar, la]]; null keeps the declared language fallback.
    const catalog = /* DSHA_WEB_CATALOG */;
    const columns = languages.map(([id]) => id);
    for (const [namespace, entries] of catalog) {
        const dictionaries = {};
        for (const id of owned) {
            const index = columns.indexOf(id);
            const values = {};
            for (const [key, translated] of entries) if (typeof translated[index] === 'string') values[key] = translated[index];
            if (Object.keys(values).length) dictionaries[id] = values;
        }
        if (!Object.keys(dictionaries).length) continue;
        try {
            disposers.push(locale.register(namespace, dictionaries));
        } catch (batch) {
            // 整批失败时逐语言重试，只放弃真正冲突的那一项。
            for (const [id, values] of Object.entries(dictionaries)) {
                try {
                    disposers.push(locale.register(namespace, id, values));
                } catch (error) {
                    report('namespace ' + namespace + ' locale ' + id, error);
                }
            }
        }
    }
    const syncDocument = () => {
        const active = locale.getSnapshot().active;
        if (!valid(active)) return;
        document.documentElement.lang = active;
        document.documentElement.dir = active === 'ar' ? 'rtl' : 'ltr';
    };
    const apply = () => {
        const language = dshaEffectiveLanguage();
        if (!valid(language)) return;
        try {
            if (locale.getSnapshot().active !== language) locale.setLocale(language);
        } catch (error) {
            report('select ' + language, error);
        }
        syncDocument();
    };
    try {
        disposers.push(locale.subscribe(syncDocument));
        window.addEventListener('dsha-language', apply);
        disposers.push(() => window.removeEventListener('dsha-language', apply));
        apply();
        syncDocument();
    } catch (error) {
        report('document sync', error);
    }
    window.__DSHA_UI_LANGUAGE_FAILURES__ = failures;
    return () => { for (const dispose of disposers.reverse()) dispose(); };
}
