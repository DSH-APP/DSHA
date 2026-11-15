// Stage only the actual signed install recipe, then perform the same entry imports as RuntimeTrial.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile, copyFile, mkdir, mkdtemp, rm, symlink } from 'node:fs/promises'
import { resolve, join, dirname, sep } from 'node:path'
import { pathToFileURL } from 'node:url'
import { testRuntime } from './test-runtime-fixture.mjs'

const runtime = testRuntime('raw')
const assets = resolve('app/src/main/assets')
const manifest = JSON.parse(await readFile(join(assets, 'managed-runtime-inputs.json'), 'utf8'))
const registry = JSON.parse(await readFile(join(assets, 'builtin-plugins.json'), 'utf8'))
const helperRows = ['builtin-plugins/dsh-status-overlay/lib/session-adapter.js',
  'builtin-plugins/dsh-computer-use-android/lib/task-binding.js']
const builtinRows = manifest.installs.filter(row => row.asset.startsWith('builtin-plugins/') || row.asset.startsWith('app-integration/'))

async function staged(omit, run) {
  const owned = await mkdtemp(resolve('app/build/builtin-installed-import-'))
  assert.ok(owned.startsWith(resolve('app/build') + sep))
  try {
    for (const row of builtinRows) {
      if (row.asset === omit) continue
      const target = join(owned, row.target)
      await mkdir(dirname(target), { recursive: true })
      await copyFile(join(assets,row.asset), target)
    }
    for (const plugin of registry.plugins) {
      const directory = join(owned,plugin.guestDirectory.slice(1))
      await symlink(join(runtime,'node_modules'), join(directory,'node_modules'), process.platform === 'win32' ? 'junction' : 'dir')
    }
    await run(owned)
  } finally { await rm(owned, { recursive: true, force: true }) }
}

test('all actual signed entities import from installed guest recipe without a source-tree fallback', () => staged(null, async root => {
  for (const asset of helperRows) {
    const row = builtinRows.find(row => row.asset === asset)
    assert.ok(row, 'helper must have a signed guest install row: ' + asset)
    assert.deepEqual(await readFile(join(root,row.target)), await readFile(join(assets,asset)))
  }
  for (const plugin of registry.plugins) {
    const entry = join(root,plugin.guestDirectory.slice(1),plugin.entrypoint)
    const loaded = await import(pathToFileURL(entry))
    assert.ok(typeof loaded.apply === 'function' || typeof loaded.default === 'function' || loaded.default?.apply,
      plugin.name + ' must retain its actual plugin export')
  }
}))

test('omitting either new helper reproduces the real trial module-not-found failure', async () => {
  for (const asset of helperRows) await staged(asset, async root => {
    const name = asset.split('/')[1], plugin = registry.plugins.find(plugin => plugin.name === name)
    await assert.rejects(import(pathToFileURL(join(root,plugin.guestDirectory.slice(1),plugin.entrypoint))),
      error => error.code === 'ERR_MODULE_NOT_FOUND' && error.message.includes(asset.split('/').at(-1)))
  })
})
