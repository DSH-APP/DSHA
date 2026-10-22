/** Mandatory Agent tool approval. Same-UID plugins and native terminals remain outside this policy. */
import { classifyCommand } from './destructive.js';
import { resolveEffectiveWorkdir } from './workdir.js';
import { statSync, readFileSync } from 'node:fs';
import { posix } from 'node:path';
import { createRequire } from 'node:module';
const runtimes = new WeakMap(), executions = new WeakMap();
const require = createRequire(import.meta.url);
const encoded = value => JSON.stringify(value);
const envFacts = value => Object.entries(value).sort(([a],[b]) => a.localeCompare(b));
// Locked Cordis proxies expose their providing object through this symbol.
// Each get() creates a fresh context proxy; comparing proxies would reject valid approvals.
const serviceIdentity = value => value?.[Symbol.for('cordis.original')] ?? value;
const fail = message => { throw new Error('DSHA_DESTRUCTIVE_BLOCKED: ' + message); };
// This is the signed launcher SDK, not a tool argument, env boolean, or HTTP
// address supplied by the model. Its authenticated current-run proof is required.
async function globalConsent(signal) {
  let timeout;
  let stage = 'SDK_UNAVAILABLE';
  const unavailable = (reason, cause, httpStatus) => ({ kind: 'unavailable', reason, cause, httpStatus });
  try {
    const sdk = require('/run/dsha-launcher/native-consent.cjs');
    stage = 'DESCRIPTOR_UNAVAILABLE';
    const connection = sdk.readConnection();
    stage = 'QUERY_FAILED';
    timeout = AbortSignal.timeout(1500);
    const response = await sdk.bridgeFetch('/device/consent', {
      expectedConnectionGeneration: connection.generation,
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    });
    if (!response.ok) return unavailable('HTTP_ERROR', undefined, response.status);
    stage = 'INVALID_REPLY';
    const envelope = await response.json();
    if (typeof envelope.result !== 'string' || Buffer.byteLength(envelope.result) > 4096) return unavailable('INVALID_REPLY');
    const value = JSON.parse(envelope.result);
    const uuid = /^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/;
    if (value?.schema === 1 && value.available === false) return unavailable('NOT_AVAILABLE');
    if (!value || value.schema !== 1 || value.available !== true || typeof value.globalAllow !== 'boolean'
        || !Number.isSafeInteger(value.revision) || value.revision < 0
        || !uuid.test(value.installationId) || !uuid.test(value.bridgeRunId)
        || !Number.isSafeInteger(value.webGeneration) || value.webGeneration < 1
        || typeof value.runtimeInstanceId !== 'string' || value.runtimeInstanceId.length > 128
        || !uuid.test(value.runtimeInstanceId.split(':')[0])
        || value.runtimeInstanceId !== value.runtimeInstanceId.split(':')[0] + ':' + value.webGeneration) return unavailable('INVALID_REPLY');
    stage = 'DESCRIPTOR_UNAVAILABLE';
    const current = sdk.readConnection();
    if (['applicationId','generation'].some(key => current[key] !== connection[key])) return unavailable('DESCRIPTOR_CHANGED');
    return { kind: 'available', facts: { applicationId: connection.applicationId, installationId: value.installationId,
      bridgeRunId: value.bridgeRunId, bridgeGeneration: connection.generation,
      webGeneration: value.webGeneration, runtimeInstanceId: value.runtimeInstanceId,
      revision: value.revision, globalAllow: value.globalAllow } };
  } catch (cause) {
    // Keep the original failure available to diagnostics, without copying its
    // message, reply body, descriptor path or credentials into the guard text.
    const reason = signal?.aborted || cause?.name === 'AbortError' ? 'CANCELLED'
      : timeout?.aborted || cause?.name === 'TimeoutError' ? 'TIMEOUT' : stage;
    return unavailable(reason, cause);
  }
}
function consentFailure(code, detail, cause) {
  const error = new Error('DSHA_DESTRUCTIVE_BLOCKED: ' + code + ': ' + detail, cause === undefined ? undefined : { cause });
  error.code = code;
  throw error;
}
function availableConsent(reading) {
  if (reading.kind === 'unavailable') {
    const status = Number.isInteger(reading.httpStatus) ? ' HTTP ' + reading.httpStatus : '';
    consentFailure('DSHA_NATIVE_CONSENT_UNAVAILABLE', reading.reason + status + '; current native consent could not be verified.', reading.cause);
  }
  return reading.facts;
}
async function checkConsent(record, exec) {
  check(record, exec);
  const reading = await globalConsent(exec.signal);
  check(record, exec);
  const current = availableConsent(reading);
  if (encoded(current) === record.consentFacts) return;
  if (record.nativeConsent === undefined) consentFailure('DSHA_NATIVE_CONSENT_AVAILABILITY_CHANGED',
    'Native consent became available after an unavailable capture; this execution is not upgraded.', record.consentRead?.cause);
  consentFailure('DSHA_NATIVE_CONSENT_FACTS_CHANGED', 'Validated native consent facts differ from the captured facts.');
}
const exempt = record => record.nativeConsent?.globalAllow === true || record.fullAccess;
// A user-selected official session preset is authority; a global never policy,
// tool argument or model claim is not. Missing/ambiguous services keep the guard.
function fullAccessProof(record) {
  if (!record.agent || !record.session) return;
  try {
    const presets = record.ctx.get('permissionPresets'), projections = record.ctx.get('sessionProjections');
    const sandbox = record.ctx.get('sandboxPolicy'), approval = record.ctx.get('approval');
    if (typeof presets?.current !== 'function' || typeof projections?.stateOf !== 'function'
        || typeof sandbox?.resolve !== 'function' || typeof approval?.effectivePolicy !== 'function') return;
    const raw = projections.stateOf(record.session, 'permissions');
    const preset = presets.current(record.session), mode = sandbox.resolve({ session: record.session }).mode;
    const policy = approval.effectivePolicy(record.session);
    if (raw?.preset !== 'danger-full-access' || raw.sandbox !== 'danger-full-access' || raw.approval !== 'never'
        || preset !== 'danger-full-access' || mode !== 'danger-full-access' || policy !== 'never') return;
    return { providers: [presets, projections, sandbox, approval].map(serviceIdentity),
      facts: encoded([raw.preset, raw.sandbox, raw.approval, raw.seeded, preset, mode, policy]) };
  } catch { /* Never infer full access when a live authority cannot be read. */ }
}
export function attachPolicy(runtime, ctx) { runtime=serviceIdentity(runtime); runtimes.set(runtime, { ctx }); ctx.effect(() => () => runtimes.delete(runtime)); }
export function captureExecution(runtime, exec, tool) {
  const actualRuntime=serviceIdentity(runtime), owner=runtimes.get(actualRuntime); if (!owner) fail('Mandatory host policy is missing.');
  const workingDirectory = runtime.ctx.get('workingDirectory');
  if (exec.agent && (!workingDirectory || typeof workingDirectory.get !== 'function' || typeof workingDirectory.ensure !== 'function')) fail('The current working-directory service is missing.');
  executions.set(exec, { ...owner, ctx:runtime.ctx, runtime:actualRuntime, exec, name:exec.name, arguments:exec.arguments, token:exec.token, callId:exec.callId, agent:exec.agent, agentCtx:exec.agent?.ctx, session:exec.agent?.session, header:exec.agent?.session?.header, headerCwd:exec.agent?.session?.header?.cwd, cwd:exec.agent ? workingDirectory.get(exec.agent.session) : undefined, workingDirectory:serviceIdentity(workingDirectory), tool, toolExecute:tool?.execute, dispatched:false });
}
function check(record, exec) {
  if (exec.signal?.aborted) consentFailure('DSHA_EXECUTION_CANCELLED', 'Execution cancellation arrived.', exec.signal.reason);
  if (!record || record.exec !== exec || exec.name !== record.name || exec.arguments !== record.arguments || exec.token !== record.token || exec.callId !== record.callId || exec.agent !== record.agent || exec.agent?.session !== record.session) fail('Execution identity changed or cancellation arrived.');
  if (record.session?.header?.cwd !== record.headerCwd) fail('Session header workdir changed.');
  if (exec.agent?.ctx !== record.agentCtx || record.session?.header !== record.header) fail('Agent scope or session header identity changed.');
  if (!runtimes.has(record.runtime) || serviceIdentity(record.ctx.get('tools')) !== record.runtime) fail('Mandatory tools service identity changed.');
  if (record.agent) {
    const directory = record.ctx.get('workingDirectory');
    if (serviceIdentity(directory) !== record.workingDirectory || typeof directory?.get !== 'function' || directory.get(record.session) !== record.cwd) fail('Current working-directory identity or value changed.');
  }
  if (record.environment && encoded(envFacts(process.env)) !== record.processEnvironment) fail('Process environment changed.');
  if (record.bashServices && ['shell','shellEnv','sandboxPolicy'].some(name => serviceIdentity(record.ctx.get(name)) !== record.bashServices[name])) fail('Bash execution services changed.');
  if (record.fullAccess) {
    const current = fullAccessProof(record);
    if (!current || current.facts !== record.fullAccess.facts
        || current.providers.some((provider, index) => provider !== record.fullAccess.providers[index])) fail('Current full-access permission identity or value changed.');
  }
}
function consumeLease(record, facts) {
  const approval = record.ctx.get('approval');
  if (!record.lease || record.lease.facts !== facts || record.lease.consumed || serviceIdentity(approval) !== record.lease.approval || approval?.effectivePolicy(record.session) === 'never') fail('Matching one-time approval is missing.');
  record.lease.consumed=true;
}
async function approve(record, exec, reason, facts) {
  await checkConsent(record, exec);
  const approval = record.ctx.get('approval');
  if (!approval || !record.agent || typeof approval.request !== 'function' || approval.effectivePolicy(record.session) === 'never') fail('No usable one-time approval channel.');
  const response = await approval.request({ agent: record.agent, toolName: record.name, callId: record.callId, reason, displayReason: { en: reason, zh: '此调用可能删除或覆盖已有数据。' + reason }, signal: exec.signal });
  await checkConsent(record, exec);
  if (response !== 'allowed-once') fail('This call was not approved once.');
  record.lease = { facts, consumed: false, approval: serviceIdentity(approval) };
}
export async function prepareOperation(runtime, exec) {
  const owner = runtimes.get(serviceIdentity(runtime));
  if (!owner) fail('Mandatory host policy is missing.');
  const record = executions.get(exec); check(record,exec);
  record.consentRead = await globalConsent(exec.signal);
  check(record,exec);
  record.nativeConsent = availableConsent(record.consentRead);
  record.consentFacts = encoded(record.nativeConsent);
  check(record,exec);
  record.fullAccess = record.nativeConsent?.globalAllow ? undefined : fullAccessProof(record);
  if (exec.name !== 'bash') return;
  // DSH 0.2.1 resolves Bash from the live projection, not the immutable header
  // or the sandbox's workspaceRoot. Settle that authority before asking once.
  if (record.agent) {
    const directory = record.ctx.get('workingDirectory');
    const cwd = await directory.ensure(record.agent, exec.signal);
    if (typeof cwd !== 'string' || !posix.isAbsolute(cwd)) fail('The effective Bash directory is unavailable.');
    record.cwd = cwd;
    await checkConsent(record,exec);
  }
  record.bashServices = Object.fromEntries(['shell','shellEnv','sandboxPolicy'].map(name=>[name,serviceIdentity(record.ctx.get(name))]));
  const collected = record.ctx.get('shellEnv')?.collect(exec) || {};
  record.environment = Object.freeze({ ...collected });
  record.processEnvironment = encoded(envFacts(process.env));
  record.workdir = resolveEffectiveWorkdir({ workdir: exec.arguments?.workdir, sessionCwd: record.cwd });
  const base=record.cwd, explicit=exec.arguments?.workdir;
  // Match the locked 0.2.1 Bash resolveWorkdir(), including an agent-less default.
  record.actualWorkdir=explicit===undefined?base:base!==undefined&&!posix.isAbsolute(explicit)?base+'/'+explicit:explicit;
  const analysis = classifyCommand(exec.arguments?.command, { cwd: record.workdir.dir, cwdKnown: record.workdir.exact, env: { ...process.env, ...record.environment }, probe: path => { try { const s=statSync(path); return s.isDirectory()?'dir':s.isFile()?'file':'other'; } catch(error) { return ['ENOENT','ENOTDIR'].includes(error.code)?'missing':'unknown'; } }, sizeProbe: path => statSync(path).size, readFile: path => { const s=statSync(path); return s.isFile()&&s.size<=65536?readFileSync(path,'utf8'):null; } });
  record.analysis = analysis;
  const facts = encoded([record.name, exec.arguments, record.workdir, record.actualWorkdir, envFacts(record.environment)]);
  record.facts = facts;
  if (analysis.requiresApproval && !exempt(record)) await approve(record, exec, 'DSHA ' + analysis.classification + ': ' + (analysis.opaqueReason || analysis.findings.map(row=>row.kind).join(', ')) + '; workdir=' + record.workdir.dir + '; targets=' + (analysis.unknownTargets ? 'unknown; count unknown' : analysis.targets.join(', ')) + '. Verify the original command; allow this call once.', facts);
}
/** Awaited by signed tool recipes immediately before resolving/dispatching a body or spawning Bash. */
export async function verifyGlobalConsent(exec) { await checkConsent(executions.get(exec), exec); }
export function verifyDispatch(runtime, exec, tool) {
  const record=executions.get(exec); check(record,exec);
  if (record.runtime !== serviceIdentity(runtime) || !runtimes.has(record.runtime) || record.tool !== tool || record.toolExecute !== tool.execute || record.dispatched) fail('Tool identity changed, policy missing, or execution was reused.');
  if (record.analysis?.requiresApproval && !exempt(record)) {
    consumeLease(record, record.facts);
  }
  record.dispatched=true;
}
export function preparedEnvironment(exec) { const record=executions.get(exec); check(record,exec); if (!record.dispatched || !record.environment) fail('No approved actual Bash environment.'); return record.environment; }
export function verifyBashRequest(exec, workdir) { const record=executions.get(exec); check(record,exec); const normalize=value=>typeof value==='string'?posix.normalize(value):value; if (!record.dispatched || normalize(workdir)!==normalize(record.actualWorkdir)) fail('Actual Bash workdir differs from approved facts.'); }
/** Runs after actual FS resolution/observation intent, preserving the backend CAS contract. */
export async function guardIntent(ctx, exec, target, intent, operation) {
  const record=executions.get(exec); check(record,exec);
  if (!record.dispatched || !['write','edit','str_replace_editor'].includes(record.name)) fail('Unprepared file mutation.');
  const fs=ctx.fs, info=await fs.stat(target,exec.signal); await checkConsent(record,exec);
  if (serviceIdentity(ctx.fs)!==serviceIdentity(fs)) fail('Filesystem service changed during consent verification.');
  if (operation==='write' && info===undefined) {
    if (intent && intent.kind !== 'createIfAbsent') fail('Creation intent conflicts with the actual absent target.');
    return { kind:'createIfAbsent' };
  }
  if (!info || info.type!=='file' || info.version===undefined) fail('Mutation target/version cannot be verified.');
  if (intent?.kind==='createIfAbsent') fail('Create cannot overwrite an existing file.');
  const version=intent?.version ?? info.version;
  if (encoded(version)!==encoded(info.version)) fail('Target changed after the observed intent.');
  const facts=encoded([target.targetKey,target.displayPath,version,operation,record.arguments]);
  if (!exempt(record)) await approve(record,exec,'DSHA overwrite: '+target.displayPath+'; one existing file. Verify the proposed edit; allow this call once.',facts);
  const latest = await fs.stat(target,exec.signal); await checkConsent(record,exec);
  if (serviceIdentity(ctx.fs)!==serviceIdentity(fs) || encoded(latest?.version)!==encoded(version)) fail('Filesystem service or target changed during approval.');
  if (!exempt(record)) consumeLease(record, facts);
  return operation==='edit'?{version}:{kind:'replaceIfVersion',version};
}
