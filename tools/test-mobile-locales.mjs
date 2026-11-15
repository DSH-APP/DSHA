// Execute the shipped locale modules and their real registration functions.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { createHash } from 'node:crypto';
import { mobileLocaleCatalog, mobileLocaleDictionaries, mobileLocaleSourceFiles, applyMobileLocaleSourcePatch, restoreMobileLiveLocaleSource } from './mobile-locales.mjs';
import { applyMobileClientPatches } from './apply-mobile-client-patches.mjs';

const destination = 'app/src/main/assets/builtin-plugins/dsh-web-mobile';
const bundle = readFileSync(destination + '/lib/client.js', 'utf8');
const upstream = readFileSync('tools/mobile-plugin-upstream/lib/client.js', 'utf8');
function modulesOf(source) {
  const window = { __ModuleLoader__: { load: ({ factory }) => factory(() => ({})) } };
  const context = vm.createContext({ window, document: { documentElement: { lang: 'en' } } });
  vm.runInContext(source.replace('__modules["index.js"](__localRequire, module, module.exports);',
    'window.modules = __modules;'), context);
  return { modules: window.modules, context };
}
function loadModule(modules, name, imports = {}) {
  const result = { exports: {} };
  modules[name](id => imports[id] || {}, result, result.exports);
  return result.exports;
}
const plain = value => JSON.parse(JSON.stringify(value));
const placeholders = value => [...value.matchAll(/\{[^{}]+\}/g)].map(match => match[0]).sort();
const definitions = [
  ['nav', 'i18n/locales.js', 'mobileNavLocales', 'zh', 'en'],
  ['fileShare', 'components/file-share-locale.js', 'fileShareLocales', 'fileShareZh', 'fileShareEn'],
  ['attachments', 'effects/attachment-mention.js', 'attachmentLocales', 'ATTACHMENT_ZH', 'ATTACHMENT_EN'],
];

test('all mobile locale maps preserve upstream zh/en, exact keys and interpolation', () => {
  const current = modulesOf(bundle).modules, original = modulesOf(upstream).modules;
  for (const [section, name, catalogName, zhName, enName] of definitions) {
    const output = loadModule(current, name), before = loadModule(original, name);
    assert.deepEqual(plain(output[zhName]), plain(before[zhName]));
    assert.deepEqual(plain(output[enName]), plain(before[enName]));
    const locales = output[catalogName];
    assert.deepEqual(Object.keys(locales), ['zh', 'en', ...mobileLocaleCatalog.locales]);
    assert.deepEqual(Object.keys(before[enName]), mobileLocaleCatalog[section].keys);
    for (const [locale, dictionary] of Object.entries(locales)) {
      assert.deepEqual(Object.keys(dictionary), Object.keys(before[enName]), section + '/' + locale);
      for (const key of Object.keys(dictionary)) {
        assert.ok(dictionary[key].trim(), section + '/' + locale + '/' + key);
        assert.deepEqual(placeholders(dictionary[key]), placeholders(before[enName][key]), section + '/' + locale + '/' + key);
      }
    }
  }
});

test('actual nav, share and attachment registration functions receive every locale', () => {
  const { modules } = modulesOf(bundle);
  const nav = loadModule(modules, 'i18n/locales.js');
  const sharing = loadModule(modules, 'components/file-share-locale.js');
  const attachments = loadModule(modules, 'effects/attachment-mention.js');
  const root = loadModule(modules, 'index.js', { './i18n/locales.js': nav });
  const fileShare = loadModule(modules, 'effects/file-share.js', { './components/file-share-locale.js': sharing });
  const registered = [];
  const stop = new Error('locale registered');
  const context = {
    effect: run => run(), inject: (_services, run) => run(context),
    locale: { register: (namespace, dictionary) => { registered.push({ namespace, dictionary }); throw stop; } },
  };
  for (const invoke of [() => root.apply(context), () => fileShare.installFileShare(context), () => attachments.installAttachmentMention(context)])
    assert.throws(invoke, error => error === stop);
  assert.deepEqual(registered.map(item => item.namespace), ['dshWebMobileNav', 'dshWebMobileNavFileShare', 'dshWebMobileNav.attachments']);
  for (const item of registered) assert.deepEqual(Object.keys(item.dictionary), ['zh', 'en', ...mobileLocaleCatalog.locales]);
});

test('native file action language resolution handles script, region, case and fallback', () => {
  const { modules, context } = modulesOf(bundle);
  const sharing = loadModule(modules, 'components/file-share-locale.js');
  for (const [tag, expected] of [['zh', 'zh'], ['zh-CN', 'zh'], ['zh-Hans-SG', 'zh'], ['zh-Hant', 'zh-Hant'],
    ['zh-Hant-TW', 'zh-Hant'], ['ZH_hk', 'zh-Hant'], ['zh-MO', 'zh-Hant'], ['RU-ru', 'ru'], ['ja-JP', 'ja'],
    ['ko-KR', 'ko'], ['ar-EG', 'ar'], ['pt-BR', 'pt'], ['la-VA', 'la'], ['fr-CA', 'fr'], ['en-GB', 'en'], ['de-DE', 'en'], ['', 'en']]) {
    context.document.documentElement.lang = tag;
    assert.deepEqual(plain(sharing.nativeFileCopy()), mobileLocaleDictionaries.fileActions[expected], tag);
    assert.equal(sharing.nativeFileCopy(), sharing.nativeFileActionLocales[expected]);
  }
  const expectedKeys = mobileLocaleCatalog.fileActions.keys;
  for (const dictionary of Object.values(sharing.nativeFileActionLocales)) assert.deepEqual(Object.keys(dictionary), expectedKeys);
});

test('mobile source and bundle outputs are deterministic transformations of locked upstream', () => {
  assert.equal(applyMobileClientPatches(Buffer.from(upstream)), bundle);
  const lock = JSON.parse(readFileSync('tools/mobile-plugin-upstream.lock.json', 'utf8'));
  for (const name of mobileLocaleSourceFiles) {
    const output = readFileSync(destination + '/' + name, 'utf8');
    let pristine = output.split('\n// DSHA_MOBILE_LOCALES_V1:')[0];
    if (name === 'src/client/index.tsx') pristine = pristine
      .replace("import { NS, mobileNavLocales } from './i18n/locales.ts'", "import { NS, en, zh } from './i18n/locales.ts'")
      .replace('ctx.locale.register(NS, mobileNavLocales)', 'ctx.locale.register(NS, { zh, en })');
    if (name === 'src/client/effects/file-share.ts') pristine = pristine
      .replace('import { FILE_SHARE_NS, fileShareLocales }', 'import { FILE_SHARE_NS, fileShareEn, fileShareZh }')
      .replace('scope.locale.register(FILE_SHARE_NS, fileShareLocales)', 'scope.locale.register(FILE_SHARE_NS, { zh: fileShareZh, en: fileShareEn })');
    if (name === 'src/client/effects/attachment-mention.ts') pristine = pristine
      .replace('ctx.locale.register(ATTACHMENT_NS, attachmentLocales)', 'ctx.locale.register(ATTACHMENT_NS, { zh: ATTACHMENT_ZH, en: ATTACHMENT_EN })');
    if (name === 'src/client/effects/drawer-session-log.ts' || name === 'src/client/effects/drawer-quick-settings.ts')
      pristine = restoreMobileLiveLocaleSource(name, pristine);
    assert.equal(createHash('sha256').update(pristine).digest('hex'), lock.files[name].sha256, name);
    assert.equal(output, applyMobileLocaleSourcePatch(name, pristine), name);
  }
  assert.match(readFileSync(destination + '/src/client/index.tsx', 'utf8'), /locale\.register\(NS, mobileNavLocales\)/);
  assert.match(readFileSync(destination + '/src/client/effects/file-share.ts', 'utf8'), /locale\.register\(FILE_SHARE_NS, fileShareLocales\)/);
  assert.match(readFileSync(destination + '/src/client/effects/attachment-mention.ts', 'utf8'), /locale\.register\(ATTACHMENT_NS, attachmentLocales\)/);
});

test('published TypeScript dictionary sources execute with the same maps as the shipped bundle', async () => {
  const nav = await import('../' + destination + '/src/client/i18n/locales.ts');
  const files = await import('../' + destination + '/src/client/components/file-share-locale.ts');
  const { modules } = modulesOf(bundle);
  assert.deepEqual(nav.mobileNavLocales, plain(loadModule(modules, 'i18n/locales.js').mobileNavLocales));
  const shipped = loadModule(modules, 'components/file-share-locale.js');
  assert.deepEqual(files.fileShareLocales, plain(shipped.fileShareLocales));
  assert.deepEqual(files.nativeFileActionLocales, plain(shipped.nativeFileActionLocales));
});
