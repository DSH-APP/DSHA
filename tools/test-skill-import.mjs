import { testRuntime } from './test-runtime-fixture.mjs';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '..');
const sha = bytes => createHash('sha256').update(bytes).digest('hex');
const args = process.argv.slice(2);
let fixture, ownedFixture, fixtureParent, importerProof, dependencyCache;

async function removeOwnedFixture() {
  if (ownedFixture === undefined) return;
  const relative = path.relative(fixtureParent, ownedFixture);
  assert(relative && !relative.startsWith('..') && !path.isAbsolute(relative)
    && path.basename(ownedFixture).startsWith('skill-import-'));
  await fs.rm(ownedFixture, { recursive: true, force: true });
  ownedFixture = undefined;
}

async function lockedJar(dependency, destination) {
  assert(/^[a-z0-9.-]+$/.test(dependency.name) && /^[0-9.]+$/.test(dependency.version));
  assert(/^[a-f0-9]{64}$/.test(dependency.sha256));
  const filename = `${dependency.name}-${dependency.version}.jar`;
  const candidates = [path.join(root, 'app/build/backup-dependencies', filename)];
  const cache = path.join(dependencyCache, 'caches/modules-2/files-2.1', dependency.group,
    dependency.name, dependency.version);
  try {
    for (const entry of await fs.readdir(cache, { withFileTypes: true }))
      if (entry.isDirectory() && /^[a-f0-9]+$/.test(entry.name))
        candidates.push(path.join(cache, entry.name, filename));
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  let bytes;
  for (const candidate of candidates) {
    try {
      bytes = await fs.readFile(candidate);
      break;
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
    }
  }
  if (bytes === undefined) {
    const url = dependency.artifact ?? `https://repo.maven.apache.org/maven2/${dependency.group.replaceAll('.', '/')}/${dependency.name}/${dependency.version}/${filename}`;
    assert.equal(new URL(url).hostname, 'repo.maven.apache.org');
    const response = await fetch(url, { signal: AbortSignal.timeout(15000), redirect: 'error' });
    assert(response.ok, `SKILL_FIXTURE_JAR_HTTP_${response.status}`);
    const reader = response.body.getReader(), chunks = [];
    let size = 0;
    try {
      while (true) {
        const next = await reader.read();
        if (next.done) break;
        size += next.value.byteLength;
        assert(size <= 2 * 1024 * 1024, 'SKILL_FIXTURE_JAR_LIMIT');
        chunks.push(Buffer.from(next.value));
      }
    } finally {
      await reader.cancel();
    }
    bytes = Buffer.concat(chunks);
  }
  assert.equal(sha(bytes), dependency.sha256, `SKILL_FIXTURE_JAR_SHA256:${filename}`);
  const installed = path.join(destination, filename);
  await fs.writeFile(installed, bytes, { flag: 'wx' });
  return installed;
}

async function generateFixture() {
  const options = new Map();
  for (let index = 0; index < args.length; index += 2) {
    assert(['--jdk', '--fixture'].includes(args[index]) && index + 1 < args.length
      && !options.has(args[index]), 'SKILL_FIXTURE_ARGUMENTS');
    options.set(args[index], args[index + 1]);
  }
  fixtureParent = options.has('--fixture') ? path.resolve(options.get('--fixture'))
    : path.join(root, 'app/build');
  if (!options.has('--fixture')) await fs.mkdir(fixtureParent, { recursive: true });
  assert((await fs.lstat(fixtureParent)).isDirectory(), 'SKILL_FIXTURE_PARENT');
  fixtureParent = await fs.realpath(fixtureParent);
  ownedFixture = await fs.mkdtemp(path.join(fixtureParent, 'skill-import-'));
  const classes = path.join(ownedFixture, 'classes'), jars = path.join(ownedFixture, 'jars');
  await fs.mkdir(classes);
  await fs.mkdir(jars);
  const jdk = options.get('--jdk') ?? process.env.JAVA_HOME;
  const executable = name => jdk ? path.join(jdk, 'bin', name + (process.platform === 'win32' ? '.exe' : '')) : name;
  const subprocess = { cwd: root, encoding: 'utf8', windowsHide: true, timeout: 15000,
    maxBuffer: 2 * 1024 * 1024 };
  const probe = spawnSync(executable('java'), ['-XshowSettings:properties', '-version'], subprocess);
  assert.equal(probe.status, 0, 'SKILL_FIXTURE_JDK_MISSING');
  assert.match(probe.stderr, /java\.specification\.version\s*=\s*17\b/);
  const profile = /user\.home\s*=\s*([^\r\n]+)/.exec(probe.stderr)?.[1];
  assert(profile, 'SKILL_FIXTURE_JDK_PROFILE');
  dependencyCache = process.env.GRADLE_USER_HOME ?? path.join(profile.trim(), '.gradle');
  const lock = JSON.parse(await fs.readFile(path.join(root, 'tools/backup-dependencies.lock.json'), 'utf8'));
  assert.equal(lock.version, 1);
  const dependencies = ['snakeyaml', 'gson'].map(name => {
    const dependency = lock.dependencies.find(row => row.name === name);
    assert(dependency, `SKILL_FIXTURE_LOCK_MISSING:${name}`);
    return dependency;
  });
  const artifacts = await Promise.all(dependencies.map(dependency => lockedJar(dependency, jars)));
  const generated = path.join(ownedFixture, 'generated');
  // Keep this JVM fixture independent from AndroidBackupFileSystem. The production
  // UserDataLayout is Android-adjacent through its wider source closure; this
  // minimal host-only facade exercises the same stable data root used by imports.
  const hostLayout = path.join(generated, 'com/deepseekharness/app/backup/UserDataLayout.java');
  await fs.mkdir(path.dirname(hostLayout), { recursive: true });
  await fs.writeFile(hostLayout, [
    'package com.deepseekharness.app.backup;',
    'import java.io.File;',
    'import java.io.IOException;',
    'public final class UserDataLayout {',
    '  private final File files;',
    '  public UserDataLayout(BackupFileSystem fs, File files) { this.files = files.getAbsoluteFile(); }',
    '  public File current() throws IOException { return new File(files, "user-data-v5/dsh"); }',
    '}',
    ''
  ].join('\n'), 'utf8');
  execFileSync(process.env.DSHA_PYTHON ?? (process.platform === 'win32' ? 'python' : 'python3'),
    ['-B', path.join(root, 'tools/prepare-ui-languages.py'), '--output', generated], subprocess);
  const driver = path.join(root, 'tools/fixtures/skill-import/SkillImportFixture.java');
  const classpath = artifacts.join(path.delimiter);
  execFileSync(executable('javac'), ['-J-Dfile.encoding=UTF-8', '-J-Duser.language=en',
    '-J-Duser.country=US', '-encoding', 'UTF-8', '-source', '17', '-target', '17',
    '-cp', classpath, '-sourcepath', [generated, path.join(root, 'app/src/main/java'),
      path.join(root, 'app/src/test/java')].join(path.delimiter), '-d', classes, driver], subprocess);
  fixture = path.join(ownedFixture, 'app-files');
  assert.equal(execFileSync(executable('java'), ['-Dfile.encoding=UTF-8', '-cp',
    [classes, ...artifacts].join(path.delimiter), 'SkillImportFixture', fixture], subprocess).trim(),
    'PASS_JVM_SKILL_IMPORT');
  importerProof = { mechanism: 'portable JDK17 actual SkillImports + JVM file layer',
    importedFiles: 3, dependencies: dependencies.map(({ name, version, sha256 }) => ({ name, version, sha256 })),
    sourceSha256: Object.fromEntries(await Promise.all([
      'app/src/main/java/com/deepseekharness/app/skills/SkillImports.java',
      'app/src/main/java/com/deepseekharness/app/util/SkillDocument.java',
      'app/src/test/java/com/deepseekharness/app/backup/JvmBackupFileSystem.java',
      'tools/fixtures/skill-import/SkillImportFixture.java'].map(async file => [file,
        sha(await fs.readFile(path.join(root, file)))]))) };
}

// 旧参数路径仍接受真实 JVM 单测已导入的夹具；正式 host 模式会自己生成，不读旧产物。
if (args.length === 1 && !args[0].startsWith('--')) {
  fixture = path.resolve(args[0]);
  const relative = path.relative(path.join(root, 'app/build'), fixture);
  assert(relative && !relative.startsWith('..') && !path.isAbsolute(relative));
} else {
  try {
    await generateFixture();
  } catch (error) {
    await removeOwnedFixture();
    throw error;
  }
}
try {
const runtime = testRuntime('managed');
const require = createRequire(pathToFileURL(path.join(runtime, 'package.json')));
const load = name => import(pathToFileURL(require.resolve(name)));
const expectedRuntimeVersion = JSON.parse(await fs.readFile(
  path.join(root, 'tools/dsh-runtime/package.json'), 'utf8')).dependencies['@deepseek-ai/dsh'];
for (const name of ['@deepseek-ai/dsh-skill', '@deepseek-ai/dsh-skill-filesystem', '@deepseek-ai/dsh-tool-skill'])
  assert.equal(JSON.parse(await fs.readFile(require.resolve(name + '/package.json'), 'utf8')).version,
    expectedRuntimeVersion);
const base = await fs.readFile(path.join(runtime,
  'node_modules/@deepseek-ai/dsh-base/cordis.patch.yml'), 'utf8');
assert(base.includes("name: '@deepseek-ai/dsh-skill-filesystem'"));
assert(base.includes("name: '@deepseek-ai/dsh-tool-skill'"));
const { Context } = await load('@deepseek-ai/cordis');
const { SkillRegistry } = await load('@deepseek-ai/dsh-skill');
const filesystem = await load('@deepseek-ai/dsh-skill-filesystem');
const toolSkill = await load('@deepseek-ai/dsh-tool-skill');
const home = path.join(fixture, 'user-data-v5/dsh');
const ctx = new Context();
const checks = [];
try {
  await ctx.plugin(SkillRegistry);
  await ctx.plugin(filesystem, { dshHome: home, agentsHome: path.join(fixture, 'agents'), watch: false });
  const signal = new AbortController().signal;
  const skills = await ctx.skills.list({ signal });
  assert.deepEqual(skills.map(skill => skill.name), ['fully-disabled', 'hello-world', 'user-only']);
  assert(skills.every(skill => skill.source === 'user-dsh' && skill.provider === 'filesystem'));
  checks.push('official default $DSH_HOME/skills provider discovers invocation policy variants');
  const skill = await ctx.skills.get('hello-world', { signal });
  assert.equal(skill.description, 'A real imported skill\n用于验证实际发现\n');
  assert.equal(skill.content, '# Body\nDo not run: touch should-never-exist');
  assert.equal(skill.resourceBase.path, path.join(home, 'skills/hello-world'));
  checks.push('official get returns full body and actual resource directory');
  let registered;
  const events = new Map();
  const approvalRequests = [];
  let approvalOutcome = 'rejected';
  const approval = {
    async request(request) {
      approvalRequests.push(request);
      return approvalOutcome;
    }
  };
  const agent = { session: { surface: { nodes: [] }, seq: 0, eventAt() {} } };
  const workingDirectory = { ensure: async () => fixture };
  const tools = {
    register(tool) { registered = tool; },
    get(name) { return name === 'skill' ? registered : undefined; }
  };
  toolSkill.apply({
    skills: ctx.skills,
    tools,
    workingDirectory,
    get(name) { return name === 'approval' ? approval : undefined; },
    on(name, handler) {
      const handlers = events.get(name) ?? [];
      handlers.push(handler);
      events.set(name, handlers);
    }
  });
  assert.equal(registered.name, 'skill');
  const execution = { signal, agent, callId: 'call-hello-world' };
  const loaded = await registered.execute({ name: 'hello-world' }, execution);
  assert.equal(loaded.content, skill.content);
  assert.equal(loaded.provider, 'filesystem');
  checks.push('official model-facing skill tool reads actual imported content');

  const catalogDecision = await events.get('agent/pre-step')[1]({ agent, signal },
    async () => ({ kind: 'continue', messages: [] }));
  const catalog = catalogDecision.messages.find(message => message.source?.kind === 'skill-catalog');
  assert(catalog, 'user-only skill must remain visible in the model catalog');
  assert.match(catalog.content[0].text, /user-only/);
  assert.match(catalog.content[0].text, /\[approval required\]/);
  assert.doesNotMatch(catalog.content[0].text, /fully-disabled/);
  checks.push('model catalog advertises user-only skills with an approval requirement and hides fully-disabled skills');

  await assert.rejects(() => registered.execute({ name: 'fully-disabled' }, execution),
    /not available for model invocation/);
  assert.equal(approvalRequests.length, 0);
  checks.push('fully-disabled skills cannot bypass invocation policy through approval');

  await assert.rejects(() => registered.execute({ name: 'user-only' }, {
    ...execution, callId: 'call-user-only-rejected'
  }), /user rejected loading user-only skill/);
  assert.equal(approvalRequests.length, 1);
  assert.equal(approvalRequests[0].toolName, 'skill');
  assert.equal(approvalRequests[0].callId, 'call-user-only-rejected');
  assert.equal(approvalRequests[0].reason, 'load user-only skill "user-only"');
  assert.equal(approvalRequests[0].displayReason.zh,
    '是否允许模型在本次调用中加载仅限用户调用的技能“user-only”？');
  checks.push('user-only model calls ask through the approval service and reject closed outcomes');

  approvalOutcome = 'allowed-once';
  const approved = await registered.execute({ name: 'user-only' }, {
    ...execution, callId: 'call-user-only-allowed'
  });
  assert.equal(approved.content, 'User body');
  assert.equal(approvalRequests.length, 2);
  assert.equal(approvalRequests[1].callId, 'call-user-only-allowed');
  assert.equal((await ctx.skills.get('user-only', { signal })).invocation.userInvocable, true);
  checks.push('allowed-once releases only the current user-only skill call');
  assert.equal(await fs.stat(path.join(fixture, 'should-never-exist')).catch(error => {
    if (error.code === 'ENOENT') return undefined;
    throw error;
  }), undefined);
  assert.equal(await fs.stat(path.join(home, 'should-never-exist')).catch(error => {
    if (error.code === 'ENOENT') return undefined;
    throw error;
  }), undefined);
  checks.push('skill import and reads never execute command-like body text');
} finally {
  await ctx.fiber.dispose();
}
console.log(JSON.stringify({ status: 'PASS', dshVersion: expectedRuntimeVersion, checks,
  ...(importerProof === undefined ? {} : { importer: importerProof }) }, null, 2));
} finally {
  await removeOwnedFixture();
}
