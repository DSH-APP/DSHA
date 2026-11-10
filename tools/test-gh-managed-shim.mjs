import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, rm, writeFile, chmod, stat } from 'node:fs/promises'
import { createConnection, createServer } from 'node:net'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('../', import.meta.url))
const proxy = join(root, 'app/src/main/assets/gh-proxy.mjs')
const wrapper = join(root, 'app/src/main/assets/gh-wrapper.sh')
const lockFile = join(root, 'tools/gh-cli/packages.lock.json')
const VERSION = '2.103.3'
const MEMBER = `gh_${VERSION}_linux_arm64/bin/gh`

const sha256 = data => createHash('sha256').update(data).digest('hex')

const run = (command, args, options = {}) => new Promise(resolve => {
  const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'], ...options })
  const out = [], err = []
  child.stdout.on('data', data => out.push(data.toString()))
  child.stderr.on('data', data => err.push(data.toString()))
  child.on('error', error => resolve({ code: -1, stdout: out.join(''), stderr: err.join('') + error.message }))
  child.on('close', code => resolve({ code, stdout: out.join(''), stderr: err.join('') }))
})

const startProxy = async (stateDir, requestedPort = 0) => {
  const child = spawn(process.execPath, [proxy, '--port', String(requestedPort), '--state-dir', stateDir], {
    stdio: ['ignore', 'pipe', 'pipe']
  })
  const output = []
  child.stdout.on('data', data => output.push(data.toString()))
  child.stderr.on('data', data => output.push(data.toString()))
  const portFile = join(stateDir, 'port')
  for (let i = 0; i < 100; i++) {
    try {
      const port = Number((await readFile(portFile, 'utf8')).trim())
      if (port) return { child, port, output }
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 10))
  }
  child.kill()
  throw new Error(output.join(''))
}

const request = (port, text) => new Promise((resolve, reject) => {
  const socket = createConnection({ host: '127.0.0.1', port })
  let body = ''
  socket.setTimeout(1000, () => { socket.destroy(); reject(new Error('timeout')) })
  socket.on('error', reject)
  socket.on('data', data => { body += data.toString('latin1') })
  socket.on('end', () => resolve(body))
  socket.on('connect', () => socket.end(text))
})

test('proxy rejects non-GitHub targets before opening an upstream socket', async () => {
  const state = await mkdtemp(join(tmpdir(), 'dsha-gh-proxy-'))
  const { child, port } = await startProxy(state)
  try {
    const response = await request(port, 'CONNECT example.com:443 HTTP/1.1\r\n\r\n')
    assert.match(response, /^HTTP\/1\.1 403 Forbidden/)
  } finally {
    child.kill()
    await rm(state, { recursive: true, force: true })
  }
})

test('proxy rejects non-TLS GitHub ports', async () => {
  const state = await mkdtemp(join(tmpdir(), 'dsha-gh-proxy-'))
  const { child, port } = await startProxy(state)
  try {
    const response = await request(port, 'CONNECT api.github.com:80 HTTP/1.1\r\n\r\n')
    assert.match(response, /^HTTP\/1\.1 403 Forbidden/)
  } finally {
    child.kill()
    await rm(state, { recursive: true, force: true })
  }
})

test('proxy falls back to an ephemeral port when the requested port is occupied', async () => {
  const blocker = createServer().listen(0, '127.0.0.1')
  await new Promise(resolve => blocker.once('listening', resolve))
  const blockedPort = blocker.address().port
  const state = await mkdtemp(join(tmpdir(), 'dsha-gh-proxy-'))
  const { child, port } = await startProxy(state, blockedPort)
  try {
    assert.notEqual(port, blockedPort)
  } finally {
    child.kill()
    await new Promise(resolve => blocker.close(resolve))
    await rm(state, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// Managed gh provision contract: the wrapper's pinned values must be the same
// ones the APK asset and the fixed-input lock carry.
// ---------------------------------------------------------------------------

test('wrapper pin matches the managed gh tool lock', async () => {
  const source = await readFile(wrapper, 'utf8')
  const lock = JSON.parse(await readFile(lockFile, 'utf8'))
  assert.equal(lock.schema, 1)
  assert.match(lock.version, /^[0-9]+\.[0-9]+\.[0-9]+$/)
  assert.equal(lock.url,
    `https://github.com/cli/cli/releases/download/v${lock.version}/gh_${lock.version}_linux_arm64.tar.gz`)
  assert.equal(lock.member, `gh_${lock.version}_linux_arm64/bin/gh`)
  assert.match(lock.sha256, /^[a-f0-9]{64}$/)
  assert.match(lock.binarySha256, /^[a-f0-9]{64}$/)
  assert.ok(Number.isInteger(lock.bytes) && lock.bytes > 0)
  assert.ok(Number.isInteger(lock.binaryBytes) && lock.binaryBytes > 0)
  const value = name => {
    const match = source.match(new RegExp(`^${name}="([^"]*)"$`, 'm'))
    assert.ok(match, `wrapper is missing ${name}`)
    return match[1]
  }
  assert.equal(value('GH_VERSION'), lock.version)
  assert.equal(value('GH_TARBALL_SHA256'), lock.sha256)
  assert.equal(value('GH_BINARY_SHA256'), lock.binarySha256)
  assert.equal(value('GH_BINARY_BYTES'), String(lock.binaryBytes))
  assert.ok(source.includes('https://github.com/cli/cli/releases/download/v${GH_VERSION}'),
    'wrapper download URL is not the pinned release URL')
  assert.ok(source.includes('DSHA_GH_BOOTSTRAP'))
  assert.ok(!source.includes('install the bundled gh binary first'),
    'misleading bundled-binary message must be gone')
})

test('wrapper keeps the managed binary and proxy paths explicit', async () => {
  const source = await readFile(wrapper, 'utf8')
  assert.match(source, /DSHA_GH_HOST_ROOT/)
  assert.match(source, /gh-proxy\.mjs/)
  assert.match(source, /DSHA_GH_RAW/)
  assert.match(source, /prorot adapter|proroot adapter/)
  assert.match(source, /^DEFAULT_GH="\/opt\/gh\/bin\/gh"$/m)
  // Android 的两个数据视图都要试；缺 CA 时不得静默回退旧系统 CA（T73）。
  assert.match(source, /\/data\/user\/0\/\*/)
  assert.match(source, /\/data\/data\/\*/)
  assert.ok(
    !source.includes('CA_FILE="$ROOT_HOST/etc/ssl/certs/ca-certificates.crt"'),
    'silent stale system CA fallback must be gone'
  )
})

// ---------------------------------------------------------------------------
// Bootstrap behavior, exercised with a fake curl and a local fixture tarball.
// No network egress: the wrapper only ever sees our fake curl on PATH.
// ---------------------------------------------------------------------------

const buildFixture = async dir => {
  const pkg = join(dir, 'pkg', `${MEMBER.split('/')[0]}/bin`)
  await mkdir(pkg, { recursive: true })
  const binary = join(pkg, 'gh')
  await writeFile(binary, '#!/bin/sh\nprintf "STUB-GH %s\\n" "$*"\n')
  await chmod(binary, 0o755)
  const tarball = join(dir, 'gh.tar.gz')
  const tar = await run('tar', ['-czf', tarball, '-C', join(dir, 'pkg'), MEMBER])
  assert.equal(tar.code, 0, tar.stderr)
  const binaryBytes = await readFile(binary)
  return {
    tarball,
    tarballSha: sha256(await readFile(tarball)),
    binarySha: sha256(binaryBytes),
    binaryBytes: binaryBytes.length
  }
}

const installWrapper = async (dir, overrides) => {
  let source = await readFile(wrapper, 'utf8')
  const replace = (pattern, value) => {
    assert.match(source, pattern)
    source = source.replace(pattern, value)
  }
  replace(/^DEFAULT_GH=.*$/m, `DEFAULT_GH="${overrides.defaultPath}"`)
  replace(/^GH_TARBALL_SHA256=.*$/m, `GH_TARBALL_SHA256="${overrides.tarballSha}"`)
  replace(/^GH_BINARY_SHA256=.*$/m, `GH_BINARY_SHA256="${overrides.binarySha}"`)
  replace(/^GH_BINARY_BYTES=.*$/m, `GH_BINARY_BYTES="${overrides.binaryBytes}"`)
  const copy = join(dir, 'gh-wrapper.sh')
  await writeFile(copy, source, { mode: 0o755 })
  await chmod(copy, 0o755)
  return copy
}

const installFakeCurl = async (dir, { fail = false } = {}) => {
  const bin = join(dir, 'bin')
  await mkdir(bin, { recursive: true })
  const curl = join(bin, 'curl')
  const body = fail
    ? '#!/bin/sh\nexit 7\n'
    : `#!/bin/sh
out=""; prev=""
for a in "$@"; do [ "$prev" = "-o" ] && out="$a"; prev="$a"; done
[ -n "$out" ] || exit 22
cp "$DSHA_GH_TEST_TARBALL" "$out" || exit 7
printf 'call\\n' >> "$DSHA_GH_TEST_CALLS"
`
  await writeFile(curl, body)
  await chmod(curl, 0o755)
  return bin
}

const environment = (dir, extra = {}) => ({
  PATH: `${join(dir, 'bin')}:/usr/local/bin:/usr/bin:/bin`,
  TMPDIR: dir,
  HOME: dir,
  DSHA_GH_IDENTITY_NOTICE: '0',
  DSHA_GH_PROXY_SCRIPT: join(dir, 'proxy-stub.js'),
  DSHA_GH_PROXY_STATE_DIR: join(dir, 'proxy-state'),
  DSHA_GH_BOOTSTRAP_LOCK: join(dir, 'bootstrap.lock'),
  DSHA_GH_TEST_TARBALL: join(dir, 'gh.tar.gz'),
  DSHA_GH_TEST_CALLS: join(dir, 'calls'),
  ...extra
})

const setup = async () => {
  const dir = await mkdtemp(join(tmpdir(), 'dsha-gh-shim-'))
  const fixture = await buildFixture(dir)
  await writeFile(join(dir, 'proxy-stub.js'), 'process.exit(3)\n')
  const defaultPath = join(dir, 'runtime/opt/gh/bin/gh')
  const wrapperCopy = await installWrapper(dir, {
    defaultPath,
    tarballSha: fixture.tarballSha,
    binarySha: fixture.binarySha,
    binaryBytes: fixture.binaryBytes
  })
  const calls = async () => {
    try {
      return (await readFile(join(dir, 'calls'), 'utf8')).trim().split('\n').filter(Boolean).length
    } catch {
      return 0
    }
  }
  return { dir, fixture, defaultPath, wrapperCopy, calls }
}

const bootstrap = async ({ dir, wrapperCopy }, extra = {}) =>
  run('bash', [wrapperCopy, '--version'], { env: environment(dir, extra) })

test('bootstrap installs the pinned member on first use and runs it', async () => {
  const context = await setup()
  try {
    await installFakeCurl(context.dir)
    const result = await bootstrap(context)
    assert.equal(result.code, 0, result.stderr)
    assert.equal(result.stdout.trim(), 'STUB-GH --version')
    assert.match(result.stderr, /installing pinned GitHub CLI/)
    const installed = await readFile(context.defaultPath)
    assert.equal(sha256(installed), context.fixture.binarySha)
    assert.ok((await stat(context.defaultPath)).mode & 0o111)
    assert.equal(await context.calls(), 1)
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

test('an installed pinned binary is reused without downloading again', async () => {
  const context = await setup()
  try {
    await installFakeCurl(context.dir)
    assert.equal((await bootstrap(context)).code, 0)
    assert.equal(await context.calls(), 1)
    const second = await bootstrap(context)
    assert.equal(second.code, 0, second.stderr)
    assert.equal(second.stdout.trim(), 'STUB-GH --version')
    assert.equal(await context.calls(), 1)
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

test('pre-installed pinned binary never touches the downloader', async () => {
  const context = await setup()
  try {
    await installFakeCurl(context.dir)
    await mkdir(join(context.dir, 'runtime/opt/gh/bin'), { recursive: true })
    await writeFile(context.defaultPath, await readFile(join(context.dir, 'pkg', MEMBER)))
    await chmod(context.defaultPath, 0o755)
    const result = await bootstrap(context)
    assert.equal(result.code, 0, result.stderr)
    assert.equal(result.stdout.trim(), 'STUB-GH --version')
    assert.equal(await context.calls(), 0)
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

test('a damaged default binary is repaired from the pinned archive', async () => {
  const context = await setup()
  try {
    await installFakeCurl(context.dir)
    await mkdir(join(context.dir, 'runtime/opt/gh/bin'), { recursive: true })
    await writeFile(context.defaultPath, 'truncated')
    await chmod(context.defaultPath, 0o755)
    const result = await bootstrap(context)
    assert.equal(result.code, 0, result.stderr)
    assert.equal(sha256(await readFile(context.defaultPath)), context.fixture.binarySha)
    assert.equal(await context.calls(), 1)
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

test('archive checksum mismatch installs nothing and exits 127', async () => {
  const context = await setup()
  try {
    await installFakeCurl(context.dir)
    const copy = await installWrapper(context.dir, {
      defaultPath: context.defaultPath,
      tarballSha: '0'.repeat(64),
      binarySha: context.fixture.binarySha,
      binaryBytes: context.fixture.binaryBytes
    })
    const result = await run('bash', [copy], { env: environment(context.dir) })
    assert.equal(result.code, 127)
    assert.match(result.stderr, /archive checksum mismatch/)
    assert.match(result.stderr, /curl -fsSLO --proto '=https'/)
    assert.match(result.stderr, /DSHA_GH_BIN/)
    await assert.rejects(stat(context.defaultPath))
    assert.equal(await context.calls(), 1)
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

test('binary checksum mismatch installs nothing and exits 127', async () => {
  const context = await setup()
  try {
    await installFakeCurl(context.dir)
    const copy = await installWrapper(context.dir, {
      defaultPath: context.defaultPath,
      tarballSha: context.fixture.tarballSha,
      binarySha: 'f'.repeat(64),
      binaryBytes: context.fixture.binaryBytes
    })
    const result = await run('bash', [copy], { env: environment(context.dir) })
    assert.equal(result.code, 127)
    assert.match(result.stderr, /extracted binary checksum mismatch/)
    await assert.rejects(stat(context.defaultPath))
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

test('network failure installs nothing and prints the exact recovery command', async () => {
  const context = await setup()
  try {
    await installFakeCurl(context.dir, { fail: true })
    const result = await bootstrap(context)
    assert.equal(result.code, 127)
    assert.match(result.stderr, /could not download/)
    assert.match(result.stderr, /gh_2\.103\.3_linux_arm64\.tar\.gz/)
    assert.match(result.stderr, /sha256sum -c -/)
    await assert.rejects(stat(context.defaultPath))
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

test('a custom DSHA_GH_BIN is never written to and never downloads', async () => {
  const context = await setup()
  try {
    await installFakeCurl(context.dir)
    const custom = join(context.dir, 'custom/gh')
    const result = await bootstrap(context, { DSHA_GH_BIN: custom })
    assert.equal(result.code, 127)
    assert.match(result.stderr, /DSHA_GH_BIN/)
    assert.equal(await context.calls(), 0)
    await assert.rejects(stat(custom))
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

test('DSHA_GH_BOOTSTRAP=0 disables the automatic install', async () => {
  const context = await setup()
  try {
    await installFakeCurl(context.dir)
    const result = await bootstrap(context, { DSHA_GH_BOOTSTRAP: '0' })
    assert.equal(result.code, 127)
    assert.match(result.stderr, /DSHA_GH_BOOTSTRAP/)
    assert.equal(await context.calls(), 0)
    await assert.rejects(stat(context.defaultPath))
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

// ---------------------------------------------------------------------------
// CA resolution (T73): use the managed bundle from whichever Android data view
// the guest can see; never silently reuse the stale system bundle.
// ---------------------------------------------------------------------------

const installCaProbe = async dir => {
  const binary = join(dir, 'custom/gh')
  await mkdir(join(dir, 'custom'), { recursive: true })
  await writeFile(binary, '#!/bin/sh\nprintf "SSL_CERT_FILE=%s\\n" "${SSL_CERT_FILE-<unset>}"\n')
  await chmod(binary, 0o755)
  return binary
}

const caEnvironment = async (dir, root, extra = {}) =>
  environment(dir, { DSHA_GH_HOST_ROOT: root, DSHA_GH_BIN: await installCaProbe(dir), ...extra })

const runWithCa = async (context, root, extra = {}) =>
  run('bash', [context.wrapperCopy], { env: await caEnvironment(context.dir, root, extra) })

test('the managed CA bundle under the runtime root is exported to gh', async () => {
  const context = await setup()
  try {
    const root = join(context.dir, 'runtime/linux/ubuntu')
    const ca = join(root, 'usr/local/share/dsha/ca-certificates.crt')
    await mkdir(join(root, 'usr/local/share/dsha'), { recursive: true })
    await writeFile(ca, 'PEM')
    const result = await runWithCa(context, root)
    assert.equal(result.code, 0, result.stderr)
    assert.equal(result.stdout.trim(), `SSL_CERT_FILE=${ca}`)
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

test('a missing managed CA bundle is a hard error instead of a stale fallback', async () => {
  const context = await setup()
  try {
    const root = join(context.dir, 'runtime/linux/ubuntu')
    await mkdir(root, { recursive: true })
    const result = await runWithCa(context, root)
    assert.equal(result.code, 1)
    assert.match(result.stderr, /managed CA bundle is missing for this runtime root/)
    assert.match(result.stderr, /refusing to fall back to the stale system CA bundle/)
    assert.equal(result.stdout.trim(), '')
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})

test('an explicit DSHA_GH_CA wins and is never silently ignored', async () => {
  const context = await setup()
  try {
    const root = join(context.dir, 'runtime/linux/ubuntu')
    await mkdir(root, { recursive: true })
    const custom = join(context.dir, 'custom-ca.pem')
    await writeFile(custom, 'PEM')
    const used = await runWithCa(context, root, { DSHA_GH_CA: custom })
    assert.equal(used.code, 0, used.stderr)
    assert.equal(used.stdout.trim(), `SSL_CERT_FILE=${custom}`)
    const missing = await runWithCa(context, root, { DSHA_GH_CA: join(context.dir, 'gone.pem') })
    assert.equal(missing.code, 1)
    assert.match(missing.stderr, /DSHA_GH_CA points to a missing CA bundle/)
  } finally {
    await rm(context.dir, { recursive: true, force: true })
  }
})
