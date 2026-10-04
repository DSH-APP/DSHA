// Exercise the locked rc2 frontend's exported Tooltip with its actual React.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { browserFixture } from './rc1-browser-fixture.mjs';
import { testRuntime } from './test-runtime-fixture.mjs';

const runtime = testRuntime('raw');
const recipe = JSON.parse(readFileSync('app/src/main/assets/tooltip-interaction-patch.json','utf8'));
const entry = JSON.parse(readFileSync('app/src/main/assets/tooltip-entry-patch.json','utf8'));
const entrySource = readFileSync(join(runtime,'node_modules',entry.module),'utf8');
assert.equal(entrySource.split(entry.patches[0].before).length,2);
assert.equal(entrySource.replace(entry.patches[0].before,entry.patches[0].after).split(entry.patches[0].after).length,2);
const sha = module => createHash('sha256').update(readFileSync(join(runtime,'node_modules',module))).digest('hex');
console.log('TOOLTIP_INPUTS '+JSON.stringify({module:recipe.module,moduleSha256:sha(recipe.module),entry:entry.module,entrySha256:sha(entry.module)}));
const fixture = await browserFixture(runtime,[recipe]);
const { page } = fixture;
try {
  await page.evaluate(() => {
    const React = auditModules.react;
    const { createRoot } = auditModules['react-dom/client'];
    const { Tooltip } = auditModules['@deepseek-ai/dsh-client-ui-primitives'];
    let setDisabled;
    let setDelay;
    function Demo() {
      const [label, setLabel] = React.useState('Before');
      const [disabled, updateDisabled] = React.useState(false);
      const [delayMs, updateDelay] = React.useState(0);
      setDisabled = updateDisabled;
      setDelay = updateDelay;
      return React.createElement('div', { style: { padding: 40, height: 1600 } },
        React.createElement(Tooltip, { label, disabled, delayMs }, React.createElement('button', {
          id: 'trigger', onClick: () => setLabel('After'),
        }, 'Action')),
        React.createElement('button', { id: 'outside', style: { marginLeft: 20 } }, 'Outside'),
        React.createElement(Tooltip, { label: 'Pinned', openOnClick: true },
          React.createElement('button', { id: 'pinned', style: { marginLeft: 20 } }, 'Details')));
    }
    const root = createRoot(document.getElementById('root'));
    root.render(React.createElement(Demo));
    globalThis.disposeTooltipDemo = () => root.unmount();
    globalThis.disableTooltip = value => setDisabled(value);
    globalThis.delayTooltip = value => setDelay(value);
  });
  await page.locator('#trigger').waitFor();
  await page.locator('#trigger').tap();
  assert.equal(await page.locator('[role="tooltip"]').count(), 0, 'touch click must not leave an updated tooltip');
  await page.locator('#trigger').tap();
  assert.equal(await page.locator('[role="tooltip"]').count(), 0);
  await page.mouse.move(1, 1);
  await page.locator('#trigger').hover();
  await page.locator('[role="tooltip"]').waitFor();
  assert.equal(await page.locator('[role="tooltip"]').textContent(), 'After');
  await page.locator('#trigger').click();
  await page.locator('[role="tooltip"]').waitFor({ state: 'detached' });
  await page.locator('#outside').click();
  await page.keyboard.press('Shift+Tab');
  await page.locator('[role="tooltip"]').waitFor();
  await page.evaluate(() => disableTooltip(true));
  await page.locator('[role="tooltip"]').waitFor({ state: 'detached' });
  await page.evaluate(() => disableTooltip(false));
  assert.equal(await page.locator('[role="tooltip"]').count(), 0, 'enabling must not resurrect a tooltip');
  await page.locator('#pinned').tap();
  await page.locator('[role="tooltip"][data-pinned]').waitFor();
  await page.locator('#outside').tap();
  await page.locator('[role="tooltip"]').waitFor({ state: 'detached' });
  await page.locator('#pinned').tap();
  await page.locator('[role="tooltip"][data-pinned]').waitFor();
  await page.keyboard.press('Escape');
  await page.locator('[role="tooltip"]').waitFor({ state: 'detached' });
  await page.locator('#trigger').hover();
  await page.locator('[role="tooltip"]').waitFor();
  await page.evaluate(() => document.dispatchEvent(new Event('scroll')));
  assert.equal(await page.locator('[role="tooltip"]').count(), 0, 'scroll must dismiss stale tooltip position');
  await page.mouse.move(1, 1); await page.locator('#trigger').hover();
  await page.locator('[role="tooltip"]').waitFor();
  await page.evaluate(() => window.dispatchEvent(new Event('blur')));
  await page.locator('[role="tooltip"]').waitFor({ state: 'detached' });
  await page.mouse.move(1,1); await page.locator('#trigger').hover();
  await page.locator('[role="tooltip"]').waitFor();
  assert.equal(await page.evaluate(() => {
    const event = new KeyboardEvent('keydown',{key:'Escape',bubbles:true,cancelable:true});
    document.dispatchEvent(event); return event.defaultPrevented;
  }),false,'default hover tooltip must not swallow Escape');
  await page.locator('[role="tooltip"]').waitFor({ state: 'detached' });
  await page.mouse.move(1,1); await page.locator('#trigger').hover();
  await page.locator('[role="tooltip"]').waitFor();
  await page.evaluate(() => {
    Object.defineProperty(document,'hidden',{configurable:true,value:true});
    document.dispatchEvent(new Event('visibilitychange'));delete document.hidden;
  });
  await page.locator('[role="tooltip"]').waitFor({ state: 'detached' });
  await page.evaluate(() => delayTooltip(100));
  await page.mouse.move(1,1); await page.locator('#trigger').hover();await page.mouse.move(1,1);
  await page.waitForTimeout(150);
  assert.equal(await page.locator('[role="tooltip"]').count(),0,'pointer leave must cancel the delay');
  await page.locator('#trigger').hover();await page.evaluate(() => disposeTooltipDemo());
  await page.waitForTimeout(150);
  assert.equal(await page.locator('[role="tooltip"]').count(),0,'unmount must cancel a pending tooltip');
  assert.deepEqual(fixture.errors, []);
  console.log('PASS: actual rc2 Tooltip touch/click, hover, keyboard, disable, pinned outside-close/Escape, scroll/blur/visibility, non-swallowing Escape and delayed leave/unmount.');
} finally { await fixture.close(); }
