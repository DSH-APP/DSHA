import assert from 'node:assert/strict';
import { readFile, unlink, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const assets = path.join(root, 'app', 'src', 'main', 'assets');
const runtimeRoot = path.resolve(process.env.DSHA_TEST_RUNTIME
  || path.join(root, 'app', 'build', 'locked-dsh-runtime-138'));
const runtime = path.join(runtimeRoot, 'node_modules');
const moduleFile = path.join(runtime, '@deepseek-ai', 'dsh-llm-deepseek', 'lib', 'index.js');
const archiveFile = path.resolve(process.env.DSHA_RUNTIME_ARCHIVE
  || path.join(assets, 'dsh-runtime.bin'));
const archiveInputsFile = path.join(assets, 'dsh-runtime.inputs.json');
const recipe = JSON.parse(await readFile(path.join(assets, 'deepseek-messages-compat-patch.json'), 'utf8'));
const packageVersion = JSON.parse(await readFile(path.join(runtime, '@deepseek-ai', 'dsh-llm-deepseek', 'package.json'), 'utf8')).version;
assert.equal(packageVersion, recipe.dshVersion, 'patch must stay pinned to the locked DSH package');

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function archiveText(entry) {
  try {
    return execFileSync('tar', ['-xOf', archiveFile, entry], {
      encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, windowsHide: true
    });
  } catch (error) {
    throw new Error(`cannot read ${entry} from the generated dsh runtime`, { cause: error });
  }
}

function buildScriptPatchedSource() {
  const script = [
    'import importlib.util, pathlib, sys',
    'spec=importlib.util.spec_from_file_location("dsha_runtime_builder", sys.argv[1])',
    'module=importlib.util.module_from_spec(spec)',
    'spec.loader.exec_module(module)',
    'source=pathlib.Path(sys.argv[3]).read_bytes()',
    'sys.stdout.buffer.write(module.patched_content(pathlib.PurePosixPath(sys.argv[2]), source))'
  ].join(';');
  const bundled = process.platform === 'win32' && process.env.USERPROFILE
    ? path.join(process.env.USERPROFILE, '.cache', 'codex-runtimes',
      'codex-primary-runtime', 'dependencies', 'python', 'python.exe')
    : undefined;
  const candidates = [...new Set([process.env.DSHA_PYTHON, bundled, 'python3', 'python'].filter(Boolean))];
  let unavailable;
  for (const python of candidates) {
    try {
      return execFileSync(python, ['-B', '-c', script,
        path.join(root, 'tools', 'build-dsh-runtime.py'), recipe.module, moduleFile], {
        encoding: 'utf8', maxBuffer: 32 * 1024 * 1024, windowsHide: true
      });
    } catch (error) {
      if (error.status !== null || !['ENOENT', 'EPERM'].includes(error.code)) throw error;
      unavailable = error;
    }
  }
  throw new Error('Python 3 is required to execute the real runtime build recipe', { cause: unavailable });
}

function occurrences(value, part) {
  return value.split(part).length - 1;
}

async function applyRecipe(source) {
  let patched = source;
  for (const entry of recipe.patches) {
    assert.equal(occurrences(patched, entry.before), 1, 'locked source anchor changed');
    assert.equal(occurrences(patched, entry.after), entry.after === entry.before ? 1 : 0,
      'locked source unexpectedly already contains replacement');
    const after = entry.prependAsset === undefined
      ? entry.after
      : `${await readFile(path.join(assets, entry.prependAsset), 'utf8')}\n${entry.after}`;
    patched = patched.replace(entry.before, after);
  }
  return patched;
}

async function loadSerialize(source, label) {
  const testFile = path.join(path.dirname(moduleFile), `.dsha-messages-${label}-${process.pid}-${Date.now()}.mjs`);
  await writeFile(testFile, `${source}\nexport { serialize as __dshaSerializeForTest };\n`, 'utf8');
  try {
    return (await import(`${pathToFileURL(testFile).href}?v=${Date.now()}`)).__dshaSerializeForTest;
  } finally {
    await unlink(testFile).catch(() => {});
  }
}

const source = await readFile(moduleFile, 'utf8');
const patchedSource = await applyRecipe(source);
const archiveInputs = JSON.parse(await readFile(archiveInputsFile, 'utf8'));
const archiveSha256 = sha256(await readFile(archiveFile));
assert.equal(archiveSha256, archiveInputs.archive_sha256,
  'dsh-runtime.inputs.json must identify the runtime under test');
for (const relative of [
  'app/src/main/assets/deepseek-messages-compat-patch.json',
  'app/src/main/assets/dsha-deepseek-messages-compat.js'
]) {
  assert.equal(archiveInputs.inputs?.[relative], sha256(await readFile(path.join(root, relative))),
    `generated runtime must bind the current ${relative}`);
}
assert.match(archiveInputs.inputs?.['tools/build-dsh-runtime.py'] ?? '', /^[a-f0-9]{64}$/,
  'generated runtime must record the builder input');
const archivePrefix = 'usr/local/lib/node_modules/@deepseek-ai/dsh';
const archiveModule = `${archivePrefix}/node_modules/@deepseek-ai/dsh-llm-deepseek/lib/index.js`;
const archivePackage = JSON.parse(archiveText(
  `${archivePrefix}/node_modules/@deepseek-ai/dsh-llm-deepseek/package.json`));
const archiveDshPackage = JSON.parse(archiveText(`${archivePrefix}/package.json`));
const archiveSource = archiveText(archiveModule);
assert.equal(archivePackage.name, '@deepseek-ai/dsh-llm-deepseek');
assert.equal(archivePackage.version, recipe.dshVersion,
  'generated runtime must contain the locked DeepSeek Messages adapter');
assert.equal(archiveDshPackage.name, '@deepseek-ai/dsh');
assert.equal(archiveDshPackage.version, recipe.dshVersion,
  'generated runtime must contain DSH 0.1.6-alpha.2');
assert.equal(archiveSource, patchedSource,
  'generated runtime module must be the recipe-patched locked module, not a test fixture');
assert.equal(archiveSource, buildScriptPatchedSource(),
  'the current build script must patch the real locked DeepSeek adapter into the generated runtime');
assert.equal(occurrences(archiveSource, 'DSHA_DEEPSEEK_MESSAGES_PROJECTED_TOOL_CALL_V1'), 1,
  'generated runtime must contain exactly one compatibility marker');
assert.equal(occurrences(archiveSource,
  'if (block.type === "tool-call") return [dshaMessagesProjectedToolCall(block)];'), 1,
  'generated runtime must route nested tool calls through the compatibility projection');
const unpatchedSerialize = await loadSerialize(source, 'upstream');
// Execute the bytes read from dsh-runtime.bin while resolving the locked
// alpha.2 dependencies beside the fixture module.
const serialize = await loadSerialize(archiveSource, 'archive');
const connection = {
  models: [{ id: 'deepseek-test', maxTokens: 4096, inputModalities: ['text', 'image'] }],
  defaults: { thinking: 'disabled', reasoningEffort: 'off' },
  maxTokens: 4096
};
const base = {
  provider: 'deepseek-official',
  model: 'deepseek-test',
  maxTokens: 1024
};
const projectedCall = {
  type: 'tool-call',
  id: 'child-call-7',
  name: 'bash',
  arguments: '{"cmd":"pwd"}'
};
const projectedText = `[Projected tool call] ${JSON.stringify({
  id: projectedCall.id,
  name: projectedCall.name,
  arguments: projectedCall.arguments
})}`;
const teamProjection = [{
  role: 'user',
  content: [
    { type: 'text', text: 'Team message team-message-4 from reviewer:' },
    projectedCall
  ],
  source: { kind: 'team-message', teamId: 'lead', messageId: 'team-message-4' }
}];

assert.throws(
  () => unpatchedSerialize(base, connection, teamProjection, new Map(), () => undefined),
  (error) => error?.code === 'UNSUPPORTED_CONTENT'
    && error.message === 'DeepSeek Messages cannot represent user/tool-result content tool-call',
  'the fixture must reproduce the Agent Team screenshot failure against locked alpha.2'
);

const originalTeamProjection = structuredClone(teamProjection);
const teamRequest = serialize(base, connection, teamProjection, new Map(), () => undefined);
assert.deepEqual(teamProjection, originalTeamProjection, 'old durable sessions must be projected without mutation');
assert.deepEqual(teamRequest.messages, [{
  role: 'user',
  content: [
    { type: 'text', text: 'Team message team-message-4 from reviewer:' },
    { type: 'text', text: projectedText }
  ]
}]);

const subagentProjection = [{
  role: 'user',
  content: [
    { type: 'text', text: 'Agent child-session-2 sent a message: ' },
    projectedCall
  ],
  source: { kind: 'agent-message', form: 'relay', senderSessionId: 'child-session-2' }
}];
const originalSubagentProjection = structuredClone(subagentProjection);
const subagentRequest = serialize(base, connection, subagentProjection, new Map(), () => undefined);
assert.deepEqual(subagentProjection, originalSubagentProjection,
  'subagent durable delivery must not be mutated');
assert.deepEqual(subagentRequest.messages, [{
  role: 'user',
  content: [
    { type: 'text', text: 'Agent child-session-2 sent a message: ' },
    { type: 'text', text: projectedText }
  ]
}]);

// Agent Team relays can wrap a display-only call in another tool-result
// transcript.  The wrapper is still display content and must not reach the
// Messages adapter as a tool-call block.
const nestedRelay = [{
  role: 'assistant',
  content: [{ type: 'tool-call', id: 'relay-wrapper', name: 'relay', arguments: '{}' }],
  source: { kind: 'model', provider: 'deepseek-official', model: 'deepseek-test' }
}, {
  role: 'user',
  content: [{
    type: 'tool-result',
    toolCallId: 'relay-wrapper',
    content: [{
      type: 'tool-result',
      toolCallId: 'relay-child',
      content: [projectedCall]
    }]
  }],
  source: { kind: 'agent-message', form: 'relay' }
}];
const nestedRelayRequest = serialize(base, connection, nestedRelay, new Map(), () => undefined);
assert.deepEqual(nestedRelayRequest.messages[1], {
  role: 'user',
  content: [{
    type: 'tool_result',
    tool_use_id: 'relay-wrapper',
    content: [{ type: 'text', text: projectedText }]
  }]
}, 'nested relay tool-result content is projected recursively');

const toolHistory = [
  {
    role: 'assistant',
    content: [
      { type: 'text', text: 'running two tools' },
      { type: 'tool-call', id: 'direct-a', name: 'read', arguments: '{"path":"a"}' },
      { type: 'tool-call', id: 'direct-b', name: 'write', arguments: '{"path":"b"}' }
    ],
    source: { kind: 'model', provider: 'deepseek-official', model: 'deepseek-test' }
  },
  {
    role: 'user',
    content: [
      {
        type: 'tool-result',
        toolCallId: 'direct-a',
        content: [{ type: 'text', text: 'a-content' }]
      },
      {
        type: 'tool-result',
        toolCallId: 'direct-b',
        isError: true,
        content: [
          { type: 'text', text: 'child transcript: ' },
          projectedCall,
          { type: 'text', text: ' failed' }
        ]
      },
      { type: 'text', text: 'continue after tools' }
    ],
    source: { kind: 'tool', callId: 'direct-a' }
  }
];
const toolRequest = serialize(base, connection, toolHistory, new Map(), () => undefined);
assert.deepEqual(toolRequest.messages[0].content.slice(1).map((block) => [block.type, block.id]), [
  ['tool_use', 'direct-a'],
  ['tool_use', 'direct-b']
], 'assistant tool calls retain ids and order');
assert.deepEqual(toolRequest.messages[1].content.slice(0, 2), [
  { type: 'tool_result', tool_use_id: 'direct-a', content: [{ type: 'text', text: 'a-content' }] },
  {
    type: 'tool_result',
    tool_use_id: 'direct-b',
    content: [
      { type: 'text', text: 'child transcript: ' },
      { type: 'text', text: projectedText },
      { type: 'text', text: ' failed' }
    ],
    is_error: true
  }
], 'direct tool results retain ids, order, nested text, and error semantics');
assert.deepEqual(toolRequest.messages[1].content[2], { type: 'text', text: 'continue after tools' });

const oldDurableHistory = structuredClone([...teamProjection, ...subagentProjection, ...toolHistory]);
const oldDurableSnapshot = structuredClone(oldDurableHistory);
const firstReplay = serialize(base, connection, oldDurableHistory, new Map(), () => undefined);
const secondReplay = serialize(base, connection, oldDurableHistory, new Map(), () => undefined);
assert.deepEqual(oldDurableHistory, oldDurableSnapshot,
  'replaying an old durable session must not rewrite stored content or source metadata');
assert.deepEqual(secondReplay, firstReplay,
  'replaying an old durable session must produce a deterministic request');

const compactRequest = serialize({ ...base, purpose: 'compaction' }, connection,
  [...teamProjection, { role: 'user', content: [{ type: 'text', text: 'compact this history' }], source: { kind: 'user' } }],
  new Map(), () => undefined);
assert.equal(compactRequest.messages[0].content[1].text, projectedText,
  'manual and automatic compaction replay uses the same safe projection');
assert.equal(compactRequest.messages[0].content[2].text, 'compact this history');

const ref = {
  attachmentId: 'image-1',
  mediaType: 'image/png',
  bytes: 4,
  width: 1,
  height: 1,
  name: 'pixel.png'
};
const version = {
  attachment: ref,
  variantId: 'request-v1',
  mediaType: 'image/png',
  data: new Uint8Array([137, 80, 78, 71]),
  bytes: 4,
  width: 1,
  height: 1,
  hasAlpha: true
};
const imageRequest = serialize(base, connection, [
  {
    role: 'assistant',
    content: [{ type: 'tool-call', id: 'vision-call', name: 'screenshot', arguments: '{}' }],
    source: { kind: 'model', provider: 'deepseek-official', model: 'deepseek-test' }
  },
  {
    role: 'user',
    content: [{
      type: 'tool-result',
      toolCallId: 'vision-call',
      content: [projectedCall, { type: 'image', attachment: ref }]
    }],
    source: { kind: 'tool', callId: 'vision-call' }
  }
], new Map([[ref.attachmentId, version]]), () => '/tmp/pixel.png', undefined,
new Map([[ref.attachmentId, 'file-image-1']]));
assert.equal(imageRequest.messages[1].content[0].tool_use_id, 'vision-call');
assert.equal(imageRequest.messages[1].content[0].content[0].text, projectedText);
assert.equal(imageRequest.messages[1].content[0].content.at(-1).type, 'image');
assert.deepEqual(imageRequest.messages[1].content[0].content.at(-1).source,
  { type: 'file', file_id: 'file-image-1' });

assert.throws(
  () => serialize(base, connection, [{
    role: 'user',
    content: [{ type: 'reasoning', text: 'invalid user reasoning' }],
    source: { kind: 'user' }
  }], new Map(), () => undefined),
  (error) => error?.code === 'UNSUPPORTED_CONTENT',
  'the compatibility projection must not swallow unrelated protocol errors'
);

console.log(JSON.stringify({
  status: 'PASS',
  runtimeArchive: path.relative(root, archiveFile).replaceAll('\\', '/'),
  archiveSha256,
  dshVersion: archiveDshPackage.version,
  module: recipe.module,
  scenarios: ['agent-team', 'subagent-relay', 'compact', 'direct-tools',
    'error-tool-result', 'image-tool-result', 'old-session-replay', 'unsupported-content-guard']
}));
