// Actual Linux kernel probe for the current C launcher; called by the host controller.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { readFile, copyFile, chmod, mkdtemp, rm } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { createInterface } from 'node:readline';
import { once } from 'node:events';
import { createHash } from 'node:crypto';

assert.equal(process.platform, 'linux', 'a real Linux kernel is required');
assert.equal(process.arch, 'x64', 'the prepared cross-compiler fixture is x86_64');
assert.ok(Number(process.versions.node.split('.')[0]) >= 24);
const [inputBinary, source, expectedBinary, expectedSource, libraries] = process.argv.slice(2);
assert.equal(process.argv.length, 7);
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
assert.equal(hash(await readFile(inputBinary)), expectedBinary);
assert.equal(hash(await readFile(source)), expectedSource);
const root = await mkdtemp(join(resolve(tmpdir()), 'dsha-native-handshake-'));
const binary = join(root, 'session');
await copyFile(inputBinary, binary);
await chmod(binary, 0o700);
assert.equal(hash(await readFile(binary)), expectedBinary);
const env = { ...process.env, LD_LIBRARY_PATH: libraries };
const kernel = async pid => {
  const text = await readFile(`/proc/${pid}/stat`, 'utf8');
  const fields = text.slice(text.lastIndexOf(')') + 1).trim().split(/\s+/);
  return { pid: Number(text.slice(0, text.indexOf(' '))), parent: Number(fields[1]),
    group: Number(fields[2]), session: Number(fields[3]), birth: fields[19], state: fields[0] };
};
const guest = 'IFS= read -r start; [ "$start" = DSHA_START ] || exit 125; echo GUEST_STARTED:$$; kill -STOP $$';
const child = spawn(binary, ['--birth-handshake', '/bin/sh', '-c', guest], { env });
const closed = once(child, 'close');
const lines = [];
let errors = '', birth, guestPid, cleanupConfirmed = false;
child.stderr.on('data', bytes => errors = (errors + bytes).slice(-4096));
const reader = createInterface({ input: child.stdout });
reader.on('line', line => lines.push(line));
async function until(predicate, description) {
  const deadline = Date.now() + 5000;
  while (!await predicate()) {
    assert.ok(Date.now() < deadline, `${description}: ${errors}`);
    await new Promise(resolve => setTimeout(resolve, 10));
  }
}
try {
  await until(() => lines.length > 0, 'self birth handshake');
  assert.match(lines[0], /^DSHA_SESSION_STAT_V1 /);
  const stat = lines[0].slice('DSHA_SESSION_STAT_V1 '.length);
  const fields = stat.slice(stat.lastIndexOf(')') + 1).trim().split(/\s+/);
  const pid = Number(stat.slice(0, stat.indexOf(' ')));
  assert.equal(pid, child.pid);
  assert.equal(Number(fields[1]), process.pid);
  assert.equal(Number(fields[2]), pid);
  assert.equal(Number(fields[3]), pid);
  birth = fields[19];
  const current = await kernel(pid);
  assert.deepEqual([current.pid, current.parent, current.group, current.session, current.birth],
    [pid, process.pid, pid, pid, birth]);
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.equal(lines.length, 1, 'guest cannot execute before DSHA_START');
  child.stdin.write('DSHA_START\n');
  await until(() => lines.length > 1, 'guest after confirmation');
  assert.match(lines[1], /^GUEST_STARTED:[1-9][0-9]*$/);
  guestPid = Number(lines[1].slice('GUEST_STARTED:'.length));
  const guestState = await kernel(guestPid);
  assert.equal(guestState.parent, pid);
  assert.equal(guestState.group, pid);
  assert.equal(guestState.session, pid);
  await until(async () => (await kernel(guestPid)).state === 'T', 'guest stopped within owned session');
} finally {
  if (child.exitCode === null && child.signalCode === null) {
    const current = await kernel(child.pid);
    assert.ok(birth !== undefined, 'never signal without the announced birth identity');
    assert.deepEqual([current.pid, current.parent, current.group, current.session, current.birth],
      [child.pid, process.pid, child.pid, child.pid, birth]);
    assert.equal(child.kill('SIGTERM'), true);
  }
  let timer;
  try { await Promise.race([closed, new Promise((_, reject) => {
    timer = setTimeout(() => reject(Error('native leader exit was not confirmed')), 5000);
  })]); } finally { clearTimeout(timer); }
  if (guestPid !== undefined) await until(async () => {
    try { return (await kernel(guestPid)).state === 'Z'; }
    catch (error) { if (error.code === 'ENOENT') return true; throw error; }
  }, 'owned guest can no longer execute after leader cleanup');
  cleanupConfirmed = true;
  reader.close();
}
try {
  const missing = spawn(binary, ['--birth-handshake'], { env });
  let output = '', diagnostic = '';
  missing.stdout.on('data', bytes => output += bytes);
  missing.stderr.on('data', bytes => diagnostic += bytes);
  const [code] = await once(missing, 'close');
  assert.equal(code, 125);
  assert.match(diagnostic, /DSHA_SESSION_USAGE/);
  assert.equal(output.includes('DSHA_SESSION_STAT_V1'), false);
  assert.equal(hash(await readFile(source)), expectedSource);
  assert.equal(hash(await readFile(inputBinary)), expectedBinary);
  console.log(JSON.stringify({ status: 'PASS', platform: process.platform, arch: process.arch,
    sourceSha256: expectedSource, binarySha256: expectedBinary, cleanupConfirmed,
    checks: ['self-pid-parent-group-session-birth', 'kernel-birth-reread', 'guest-waits-for-DSHA_START',
      'confirmed-guest-in-owned-session', 'birth-verified-SIGTERM-cleanup', 'missing-command-125'],
    scope: 'Linux x86_64 C and kernel behavior; separate from Android arm64 and physical-device evidence' }));
} finally {
  if (cleanupConfirmed) await rm(root, { recursive: true, force: true });
}
