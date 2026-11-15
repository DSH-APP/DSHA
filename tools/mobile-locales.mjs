// DSHA locale extension for the fixed mobile prebuilt and its published sources.
// Keep upstream dictionaries and lock bytes intact; extend only owned exports.
import { readFileSync } from 'node:fs';

export const mobileLocaleCatalog = JSON.parse(readFileSync(new URL('./mobile-locales.json', import.meta.url), 'utf8'));
const expectedLocales = ['zh-Hant', 'ru', 'ja', 'ko', 'ar', 'pt', 'la', 'fr'];
if (mobileLocaleCatalog.schema !== 1 || JSON.stringify(mobileLocaleCatalog.locales) !== JSON.stringify(expectedLocales))
  throw new Error('MOBILE_LOCALE_SCHEMA');

function dictionarySet(section) {
  const { keys, translations } = mobileLocaleCatalog[section];
  const locales = section === 'fileActions' ? ['zh', 'en', ...expectedLocales] : expectedLocales;
  if (new Set(keys).size !== keys.length || JSON.stringify(Object.keys(translations)) !== JSON.stringify(locales))
    throw new Error('MOBILE_LOCALE_KEYS: ' + section);
  return Object.fromEntries(locales.map(locale => {
    const values = translations[locale];
    if (values.length !== keys.length || values.some(value => typeof value !== 'string' || !value.trim()))
      throw new Error('MOBILE_LOCALE_VALUES: ' + section + '/' + locale);
    return [locale, Object.fromEntries(keys.map((key, index) => [key, values[index]]))];
  }));
}
export const mobileLocaleDictionaries = Object.fromEntries(
  ['nav', 'fileShare', 'attachments', 'fileActions'].map(section => [section, dictionarySet(section)]));
const json = value => JSON.stringify(value, null, 2);
const note = '// DSHA_MOBILE_LOCALES_V1: generated from tools/mobile-locales.json; do not edit here.\n';
// The host accepts BCP 47 locale IDs. The document also receives regional IDs
// from Android; native file actions use the same effective language on every call.
const localeResolver = `function nativeFileCopy(language = document.documentElement.lang) {
  const locale = String(language || 'en').replace(/_/g, '-').toLowerCase();
  const base = locale.split('-')[0];
  const key = base === 'zh'
    ? (/^zh-(?:hant|tw|hk|mo)(?:-|$)/.test(locale) ? 'zh-Hant' : 'zh')
    : (Object.prototype.hasOwnProperty.call(nativeFileActionLocales, base) ? base : 'en');
  return nativeFileActionLocales[key];
}`;

function catalogCode(section, name, zh, en, source = false) {
  const type = { nav: 'MobileNavKey', fileShare: 'FileShareKey', attachments: 'AttachmentMentionKey' }[section];
  return note + (source ? `export const ${name}: Record<string, Record<${type}, string>> = ` : `exports.${name} = `)
    + `{ zh: ${source ? zh : 'exports.' + zh}, en: ${source ? en : 'exports.' + en}, ...${json(mobileLocaleDictionaries[section])} }` + (source ? '\n' : ';\n');
}
function fileActionCode(source = false) {
  return note + (source ? 'export const nativeFileActionLocales: Record<string, Record<string, string>> = ' : 'const nativeFileActionLocales = ')
    + json(mobileLocaleDictionaries.fileActions) + (source ? '\n' : ';\n')
    + (source ? 'export ' : '') + (source ? localeResolver.replace('return nativeFileActionLocales[key];', 'return nativeFileActionLocales[key]!;') : localeResolver) + '\n'
    + (source ? '' : 'exports.nativeFileActionLocales = nativeFileActionLocales;\nexports.nativeFileCopy = nativeFileCopy;\n');
}
function replaceOnce(source, before, after) {
  if (source.split(before).length !== 2) throw new Error('MOBILE_LOCALE_ANCHOR: ' + before.slice(0, 100));
  return source.replace(before, after);
}
function appendModule(source, name, code) {
  const marker = `__modules["${name}"] = function (require, module, exports) {`;
  const start = source.indexOf(marker);
  const end = source.indexOf('\n};\n__modules[', start + marker.length);
  if (source.split(marker).length !== 2 || end < 0) throw new Error('MOBILE_LOCALE_MODULE: ' + name);
  return source.slice(0, end) + '\n' + code + source.slice(end);
}
function patchModule(source, name, transform) {
  const marker = `__modules["${name}"] = function (require, module, exports) {`;
  const start = source.indexOf(marker);
  const end = source.indexOf('\n};\n__modules[', start + marker.length);
  if (source.split(marker).length !== 2 || end < 0) throw new Error('MOBILE_LOCALE_MODULE: ' + name);
  return source.slice(0, start) + transform(source.slice(start, end)) + source.slice(end);
}
// The 3.2.2 drawer nodes are owned by reconciler tasks rather than React.
// Keep their live labels in sync with the host locale and retire subscriptions
// alongside the owning task; never reopen a menu or replay an action.
function liveLocalePatches(name, typed) {
  const indent = typed ? '      ' : '                ';
  const end = typed ? '' : ';';
  const declaration = typed ? '  let unsubscribeLocale: (() => void) | null = null\n' : '    let unsubscribeLocale = null;\n';
  if (name === 'drawer-session-log') {
    const anchor = typed ? '  let unsubscribe: (() => void) | null = null\n' : '    let unsubscribe = null;\n';
    const subscribe = typed
      ? '        unsubscribe = ctx.sessions.list.subscribe(() => { syncDisabled() })'
      : '                unsubscribe = ctx.sessions.list.subscribe(() => { syncDisabled(); });';
    const callback = `\n${indent}  // DSHA_MOBILE_LIVE_LOCALE_V1\n${indent}  unsubscribeLocale = ctx.locale.subscribe?.(() => {\n${indent}    const label = t('downloadMenu')${end}\n${indent}    if (button !== null) { button.setAttribute('aria-label', label); button.title = label; }\n${indent}    if (menu !== null) {\n${indent}      menu.setAttribute('aria-label', label)${end}\n${indent}      const exportLabel = menu.querySelector('[data-mobile-nav="session-log-export"] span')${end}\n${indent}      const downloadLabel = menu.querySelector('[data-mobile-nav="session-log-downloads"] span')${end}\n${indent}      if (exportLabel !== null) exportLabel.textContent = t('sessionLog')${end}\n${indent}      if (downloadLabel !== null) downloadLabel.textContent = t('downloads')${end}\n${indent}    }\n${indent}  }) ?? null${end}`;
    const dispose = typed ? '      unsubscribe?.()\n      unsubscribe = null' : '            unsubscribe?.();\n            unsubscribe = null;';
    return [[anchor, anchor + declaration], [subscribe, subscribe + callback],
      [dispose, dispose + (typed ? '\n      unsubscribeLocale?.()\n      unsubscribeLocale = null' : '\n            unsubscribeLocale?.();\n            unsubscribeLocale = null;')]];
  }
  if (name === 'drawer-quick-settings') {
    const anchor = typed ? '  let pending: number | null = null\n' : '    let pending = null;\n';
    const build = typed ? '      if (button === null || wrap === null) build()' : '            if (button === null || wrap === null)\n                build();';
    const replacement = typed
      ? `      if (button === null || wrap === null) {\n        build()\n        unsubscribeLocale?.()\n        // DSHA_MOBILE_LIVE_LOCALE_V1\n        unsubscribeLocale = ctx.locale.subscribe?.(() => {\n          if (button === null) return\n          const label = t('quickSettings')\n          button.setAttribute('aria-label', label)\n          button.title = label\n        }) ?? null\n      }`
      : `            if (button === null || wrap === null) {\n                build();\n                unsubscribeLocale?.();\n                // DSHA_MOBILE_LIVE_LOCALE_V1\n                unsubscribeLocale = ctx.locale.subscribe?.(() => {\n                    if (button === null) return;\n                    const label = t('quickSettings');\n                    button.setAttribute('aria-label', label);\n                    button.title = label;\n                }) ?? null;\n            }`;
    const dispose = typed ? '    dispose: () => {\n      window.clearTimeout(pressTimer)' : '        dispose: () => {\n            window.clearTimeout(pressTimer);';
    return [[anchor, anchor + declaration], [build, replacement],
      [dispose, dispose + (typed ? '\n      unsubscribeLocale?.()\n      unsubscribeLocale = null' : '\n            unsubscribeLocale?.();\n            unsubscribeLocale = null;')]];
  }
  throw new Error('MOBILE_LOCALE_LIVE_SOURCE: ' + name);
}
function applyLiveLocalePatch(name, source, typed, reverse = false) {
  const patches = liveLocalePatches(name, typed);
  for (const [before, after] of reverse ? patches.reverse() : patches)
    source = replaceOnce(source, reverse ? after : before, reverse ? before : after);
  return source;
}
export function applyMobileLocaleBundlePatch(source) {
  source = appendModule(source, 'i18n/locales.js', catalogCode('nav', 'mobileNavLocales', 'zh', 'en'));
  source = appendModule(source, 'components/file-share-locale.js', catalogCode('fileShare', 'fileShareLocales', 'fileShareZh', 'fileShareEn') + fileActionCode());
  source = appendModule(source, 'effects/attachment-mention.js', catalogCode('attachments', 'attachmentLocales', 'ATTACHMENT_ZH', 'ATTACHMENT_EN'));
  source = replaceOnce(source, 'ctx.locale.register(locales_ts_1.NS, { zh: locales_ts_1.zh, en: locales_ts_1.en })',
    'ctx.locale.register(locales_ts_1.NS, locales_ts_1.mobileNavLocales)');
  source = replaceOnce(source, 'scope.locale.register(file_share_locale_ts_1.FILE_SHARE_NS, { zh: file_share_locale_ts_1.fileShareZh, en: file_share_locale_ts_1.fileShareEn })',
    'scope.locale.register(file_share_locale_ts_1.FILE_SHARE_NS, file_share_locale_ts_1.fileShareLocales)');
  source = replaceOnce(source, 'ctx.locale.register(exports.ATTACHMENT_NS, { zh: exports.ATTACHMENT_ZH, en: exports.ATTACHMENT_EN })',
    'ctx.locale.register(exports.ATTACHMENT_NS, exports.attachmentLocales)');
  for (const name of ['drawer-session-log', 'drawer-quick-settings'])
    source = patchModule(source, `effects/${name}.js`, module => applyLiveLocalePatch(name, module, false));
  return source;
}
export const mobileLocaleSourceFiles = [
  'src/client/i18n/locales.ts',
  'src/client/components/file-share-locale.ts',
  'src/client/effects/attachment-mention.ts',
  'src/client/index.tsx',
  'src/client/effects/file-share.ts',
  'src/client/effects/drawer-session-log.ts',
  'src/client/effects/drawer-quick-settings.ts',
  'lib/types/client/i18n/locales.d.ts',
  'lib/types/client/components/file-share-locale.d.ts',
  'lib/types/client/effects/attachment-mention.d.ts',
];
export function applyMobileLocaleSourcePatch(name, source) {
  switch (name) {
    case 'src/client/i18n/locales.ts':
      return source + '\n' + catalogCode('nav', 'mobileNavLocales', 'zh', 'en', true);
    case 'src/client/components/file-share-locale.ts':
      return source + '\n' + catalogCode('fileShare', 'fileShareLocales', 'fileShareZh', 'fileShareEn', true) + '\n' + fileActionCode(true);
    case 'src/client/effects/attachment-mention.ts':
      return replaceOnce(source, 'ctx.locale.register(ATTACHMENT_NS, { zh: ATTACHMENT_ZH, en: ATTACHMENT_EN })',
        'ctx.locale.register(ATTACHMENT_NS, attachmentLocales)') + '\n' + catalogCode('attachments', 'attachmentLocales', 'ATTACHMENT_ZH', 'ATTACHMENT_EN', true);
    case 'src/client/index.tsx':
      return replaceOnce(replaceOnce(source, "import { NS, en, zh } from './i18n/locales.ts'", "import { NS, mobileNavLocales } from './i18n/locales.ts'"),
        'ctx.locale.register(NS, { zh, en })', 'ctx.locale.register(NS, mobileNavLocales)');
    case 'src/client/effects/file-share.ts':
      return replaceOnce(replaceOnce(source, 'import { FILE_SHARE_NS, fileShareEn, fileShareZh }', 'import { FILE_SHARE_NS, fileShareLocales }'),
        'scope.locale.register(FILE_SHARE_NS, { zh: fileShareZh, en: fileShareEn })', 'scope.locale.register(FILE_SHARE_NS, fileShareLocales)');
    case 'src/client/effects/drawer-session-log.ts':
      return applyLiveLocalePatch('drawer-session-log', source, true);
    case 'src/client/effects/drawer-quick-settings.ts':
      return applyLiveLocalePatch('drawer-quick-settings', source, true);
    case 'lib/types/client/i18n/locales.d.ts':
      return source + '\n' + note + 'export declare const mobileNavLocales: Record<string, Record<MobileNavKey, string>>;\n';
    case 'lib/types/client/components/file-share-locale.d.ts':
      return source + '\n' + note + 'export declare const fileShareLocales: Record<string, Record<FileShareKey, string>>;\n'
        + 'export declare const nativeFileActionLocales: Record<string, Record<string, string>>;\n'
        + 'export declare function nativeFileCopy(language?: string): Record<string, string>;\n';
    case 'lib/types/client/effects/attachment-mention.d.ts':
      return source + '\n' + note + 'export declare const attachmentLocales: Record<string, Record<AttachmentMentionKey, string>>;\n';
    default:
      throw new Error('MOBILE_LOCALE_SOURCE: ' + name);
  }
}
export function restoreMobileLiveLocaleSource(name, source) {
  if (!name.startsWith('src/client/effects/drawer-') || !name.endsWith('.ts'))
    throw new Error('MOBILE_LOCALE_LIVE_SOURCE: ' + name);
  return applyLiveLocalePatch(name.slice('src/client/effects/'.length, -3), source, true, true);
}
