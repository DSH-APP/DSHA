// Render the shipped controls with the pinned rc3 React, switch language while
// mounted, and inspect the real native file rows, preview and action sheet.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { browserFixture } from './rc1-browser-fixture.mjs';
import { mobileLocaleDictionaries } from './mobile-locales.mjs';

const fixture = await browserFixture();
try {
  const page = fixture.page;
  await page.evaluate(() => {
    window.DSHA = { downloadArtifact: async () => ({ status: 'queued', taskId: 'fixture-task' }), openDownloads: async () => {} };
    document.getElementById('root').innerHTML = '<div data-files-state="tree"><div id="toolbar"></div><ul data-files-body><li data-files-entry="file" data-files-path="image.png"><button>image.png</button></li></ul></div><div id="preview"></div>';
  });
  await page.addScriptTag({ content: readFileSync('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib/client.js', 'utf8')
    .replace('var __cache = {};', 'window.mobileRequire = __localRequire; var __cache = {};') });
  await page.evaluate(() => {
    const React = auditModules.react, ReactDOM = auditModules['react-dom/client'];
    const controls = mobileRequire('./components/file-share-controls.js');
    window.fileRoots = [ReactDOM.createRoot(document.getElementById('toolbar')), ReactDOM.createRoot(document.getElementById('preview'))];
    fileRoots[0].render(React.createElement(controls.FilesRowShare, { sessionId: 'locale-fixture' }));
    fileRoots[1].render(React.createElement(controls.FilePreviewShareButton, { sessionId: 'locale-fixture', absolutePath: 'image.png' }));
    document.documentElement.lang = 'zh';
    const frame = document.createElement('div'); frame.dataset.mobileNav = 'frame';
    frame.innerHTML = '<div class="fixture_sidebarCol"><div class="fixture_logoRow"><button>Brand</button><button>Toggle</button></div><div class="fixture_triggerRow"><button aria-haspopup="menu">Account</button></div></div>';
    document.body.appendChild(frame);
    window.localeListeners = new Set();
    const ctx = {
      locale: {
        bind: () => key => mobileRequire('./i18n/locales.js').mobileNavLocales[document.documentElement.lang][key],
        subscribe: fn => { localeListeners.add(fn); return () => localeListeners.delete(fn); },
      },
      sessions: { list: { getSnapshot: () => ({ byId: {} }), subscribe: () => () => {} } },
      sessionLogDownload: { download: () => {} },
    };
    window.localeTasks = [
      mobileRequire('./effects/drawer-session-log.js').createDrawerSessionLogTask(ctx),
      mobileRequire('./effects/drawer-quick-settings.js').createDrawerQuickSettingsTask(ctx),
    ];
    localeTasks.forEach(task => task.ensure());
    document.querySelector('[data-mobile-nav="session-log"]').click();
  });
  const results = [];
  for (const [locale, copy] of Object.entries(mobileLocaleDictionaries.fileActions)) {
    await page.evaluate(locale => {
      if (!document.querySelector('[data-mobile-nav="session-log-menu"]'))
        document.querySelector('[data-mobile-nav="session-log"]').click();
      document.documentElement.lang = locale;
      for (const notify of localeListeners) notify();
    }, locale);
    const navigation = await page.evaluate(locale => {
      const copy = mobileRequire('./i18n/locales.js').mobileNavLocales[locale];
      const download = document.querySelector('[data-mobile-nav="session-log"]');
      const settings = document.querySelector('[data-mobile-nav="quick-settings"]');
      const menu = document.querySelector('[data-mobile-nav="session-log-menu"]');
      return {
        actual: [download.getAttribute('aria-label'), download.title, settings.getAttribute('aria-label'), settings.title,
          menu.getAttribute('aria-label'), menu.querySelector('[data-mobile-nav="session-log-export"] span').textContent,
          menu.querySelector('[data-mobile-nav="session-log-downloads"] span').textContent],
        expected: [copy.downloadMenu, copy.downloadMenu, copy.quickSettings, copy.quickSettings,
          copy.downloadMenu, copy.sessionLog, copy.downloads],
      };
    }, locale);
    assert.deepEqual(navigation.actual, navigation.expected, 'drawer language switch: ' + locale);
    await page.waitForFunction(copy => {
      const row = document.querySelector('[data-dsha-row-action="downloads"]');
      const preview = document.querySelector('[data-dsha-preview-actions] [data-dsha-file-actions] button');
      return row?.getAttribute('aria-label') === copy.download
        && preview?.getAttribute('aria-label') === copy.download
        && document.querySelector('#toolbar [data-dsha-downloads]')?.textContent === copy.records
        && document.querySelector('[data-dsha-preview-actions] > button')?.textContent === copy.gallery;
    }, copy);
    await page.locator('[data-dsha-row-action="more"]').click();
    const labels = await page.locator('[data-dsha-file-sheet] [role="dialog"] button').allTextContents();
    assert.deepEqual(labels, [copy.download + ' · Download/DSHA', copy.saveas, copy.share, copy.gallery, copy.close], locale);
    await page.locator('[data-dsha-file-sheet] [role="dialog"] button').last().click();
    results.push({ locale, download: copy.download, records: copy.records, menu: labels });
  }
  const remainingListeners = await page.evaluate(() => {
    fileRoots.forEach(root => root.unmount());
    localeTasks.forEach(task => task.dispose());
    return localeListeners.size;
  });
  assert.equal(remainingListeners, 0, 'locale listeners retire with the drawer tasks');
  assert.deepEqual(fixture.errors, []);
  console.log(JSON.stringify({ engine: process.env.DSHA_BROWSER || 'chromium', locales: results.length, results, errors: [] }, null, 2));
} finally { await fixture.close(); }
