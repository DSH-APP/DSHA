// Only constructed fake credentials enter this probe; the writer receives an empty launch environment.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { spawnSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { testRuntime } from './test-runtime-fixture.mjs';

const root=path.resolve(import.meta.dirname,'..');
const runtime=testRuntime('raw');
const require=createRequire(path.join(runtime,'package.json'));
const credentialsFile=require.resolve('@deepseek-ai/dsh-credentials-local');
const scopedRequire=createRequire(credentialsFile);
const yaml=scopedRequire('yaml');
const yamlPackage=scopedRequire.resolve('yaml/package.json');
const credentials=await import(pathToFileURL(credentialsFile));
const {Context}=await import(pathToFileURL(require.resolve('@deepseek-ai/cordis')));
const {credentialKey,credentialRef}=await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-credentials')));
const {createLaunchEnvironmentSnapshot}=await import(pathToFileURL(require.resolve('@deepseek-ai/dsh-launch-environment')));
const connectionFile=require.resolve('@deepseek-ai/dsh-client-connection');
const source=fs.readFileSync(credentialsFile,'utf8'),connection=fs.readFileSync(connectionFile,'utf8');
const hash=bytes=>createHash('sha256').update(bytes).digest('hex');
const anchors=[
  'function parseCredentialsDocument(text, filename) {',
  'function parseRecord(key, value, filename) {',
  'function mutableDocument(text) {',
  'async modifyRecord(key, mutate) {'
];
for(const anchor of anchors) assert.equal(source.split(anchor).length,2,'locked credentials anchor drift');
assert.equal(connection.split('const AUTH_RECORD_KEY = credentialKey("client-connection", "browser-session");').length,2);
const options=yaml.parseDocument('yes: on\ndate: 2026-10-03\n').schema;
assert.equal(options.name,'core');
assert.equal(yaml.parseDocument('').directives.yaml.version,'1.2');
const schemaProbe=yaml.parse('yes: on\noff: yes\nleading: 01234\ninvalidOctal: 0009\ndate: 2026-10-03\n');
assert.deepEqual(schemaProbe,{yes:'on',off:'yes',leading:1234,invalidOctal:9,date:'2026-10-03'});

const dependency=JSON.parse(fs.readFileSync(path.join(root,'tools/backup-dependencies.lock.json'),'utf8')).dependencies
  .find(item=>item.group==='org.yaml'&&item.name==='snakeyaml'&&item.version==='2.4');
assert.ok(dependency,'exact SnakeYAML dependency lock required');
const jar=path.join(root,'app/build/backup-dependencies/snakeyaml-2.4.jar');
assert.equal(hash(fs.readFileSync(jar)),dependency.sha256,'SnakeYAML artifact bytes must match lock');
const jdk=process.env.JAVA_HOME|| (process.platform==='win32'?'F:/DSHA/_toolchains/jdk-17':undefined);
const executable=name=>jdk?path.join(jdk,'bin',name+(process.platform==='win32'?'.exe':'')):name;
const build=fs.realpathSync(path.join(root,'app/build'));
const jvm=spawnSync(executable('java'),['-XshowSettings:properties','-version'],{encoding:'utf8',timeout:10000});
assert.match(jvm.stderr,/java\.specification\.version\s*=\s*17\b/,'PROJECTION_JDK17_REQUIRED');
const temporary=fs.mkdtempSync(path.join(build,'credential-projection-probe-'));
let checked=0,rejected=0,legacy=0;
const machine='client-connection/';
const metadata={
  schema:1,dshVersion:JSON.parse(fs.readFileSync(path.join(runtime,'node_modules/@deepseek-ai/dsh/package.json'),'utf8')).version,
  nodeYamlVersion:JSON.parse(fs.readFileSync(yamlPackage,'utf8')).version,nodeYamlSchema:'core 1.2',
  credentialModuleSha256:hash(fs.readFileSync(credentialsFile)),connectionModuleSha256:hash(fs.readFileSync(connectionFile)),
  projectionSourceSha256:hash(fs.readFileSync(path.join(root,'app/src/main/java/com/deepseekharness/app/backup/CredentialProjection.java'))),
  snakeYamlJarSha256:dependency.sha256,
  anchors:anchors.map(anchor=>({anchor,line:source.slice(0,source.indexOf(anchor)).split('\n').length})),
  authRecordAnchor:{anchor:'credentialKey("client-connection", "browser-session")',line:connection.slice(0,connection.indexOf('const AUTH_RECORD_KEY')).split('\n').length},
  cases:[]
};
function projection(input){
  const bytes=Buffer.isBuffer(input)?input:Buffer.from(input,'utf8');
  const before=hash(bytes);
  const result=spawnSync(executable('java'),['-cp',temporary+path.delimiter+jar,'ProjectionProbe'],
    {input:bytes,maxBuffer:3*1024*1024,timeout:15000});
  assert.equal(hash(bytes),before,'projection must not mutate its source bytes');
  if(result.error) throw result.error;
  return result;
}
function expectedView(text){
  const parsed=credentials.parseCredentialsDocument(text,'isolated-fake-credentials.yaml');
  return {refs:Object.fromEntries(parsed.refs),records:Object.fromEntries([...parsed.records].filter(([key])=>!key.startsWith(machine)))};
}
function valid(name,text,{unchanged=false}={}){
  const expected=expectedView(text),result=projection(text);
  assert.equal(result.status,0,name+': '+result.stderr.toString());
  const output=result.stdout.toString('utf8'),actual=expectedView(output);
  assert.deepEqual(actual,expected,name+': rc2 parsed provider/reference semantics changed');
  const parsed=credentials.parseCredentialsDocument(output,'isolated-fake-credentials.yaml');
  assert.equal([...parsed.records.keys()].some(key=>key.startsWith(machine)),false,name+': machine record survived');
  const before=yaml.parse(text),after=yaml.parse(output);
  if(before&&typeof before==='object'&&!Array.isArray(before)){
    if(before.records&&typeof before.records==='object') for(const key of Object.keys(before.records))if(key.startsWith(machine))delete before.records[key];
    assert.deepEqual(after,before,name+': YAML tree changed beyond exact machine namespace');
  }
  if(unchanged) assert.equal(output,text,name+': byte identity lost on no-removal path');
  metadata.cases.push({name,status:'passed',unchanged});checked++;
}
function invalid(name,text){
  const result=projection(text);
  assert.equal(result.status,65,name+': unknown input must fail closed');
  assert.equal(result.stdout.length,0,name+': failure published bytes');
  assert.match(result.stderr.toString(),/^CREDENTIAL_PROJECTION_[A-Z_]+\r?\n$/);
  metadata.cases.push({name,status:'rejected',code:result.stderr.toString().trim()});rejected++;
}
const header='version: 1\nrecords:\n  client-connection/browser-session: {kind: grant, payload: {version: 1, secret: fake-probe-only}}\n';
try {
  const driver=`import java.io.*;
import java.util.Arrays;
import com.deepseekharness.app.backup.CredentialProjection;
public final class ProjectionProbe {
  public static void main(String[] args) throws Exception {
    byte[] source=System.in.readNBytes(CredentialProjection.LIMIT+1);
    byte[] original=source.clone();
    try {
      byte[] projected=CredentialProjection.portable(source);
      if(!Arrays.equals(original,source))throw new IOException("CREDENTIAL_PROJECTION_SOURCE_MUTATED");
      System.out.write(projected);
    } catch(IOException error) {
      System.err.println(Arrays.equals(original,source)?error.getMessage():"CREDENTIAL_PROJECTION_SOURCE_MUTATED");
      System.exit(65);
    }
  }
}\n`;
  const driverFile=path.join(temporary,'ProjectionProbe.java');fs.writeFileSync(driverFile,driver);
  const compile=spawnSync(executable('javac'),['--release','17','-encoding','UTF-8','-cp',jar,'-sourcepath',
    path.join(root,'app/src/main/java')+path.delimiter+path.join(root,'app/build/generated/uiLanguage'),'-d',temporary,driverFile],
    {encoding:'utf8',timeout:60000,maxBuffer:1024*1024});
  assert.equal(compile.status,0,'narrow compile of actual CredentialProjection: '+compile.stderr);
  valid('core-scalars-api-env-and-json-grant',`version: 1
refs:
  PROBE_YES: yes
  PROBE_ON: on
  PROBE_OFF: off
  PROBE_DATE: 2026-10-03
  PROBE_QUOTED_ZERO: "01234"
records:
  client-connection/browser-session: {kind: grant, payload: {version: 1, secret: fake-probe-only}}
  provider/probe-api:
    kind: api-key
    key: on
    env: {PROBE_YES: yes, PROBE_ZERO: "0009", PROBE_UNICODE: "雪☃é🙂"}
  provider/probe-grant:
    kind: grant
    payload:
      yes: yes
      on: on
      off: off
      decimal-leading-zero: 01234
      non-octal-leading-zero: 0009
      quoted-leading-zero: '01234'
      date: 2026-10-03
      timestamp-looking: 2026-10-03T12:34:56Z
      true-value: true
      false-value: FALSE
      decimal: 1.25e+3
      octal: 0o17
      hexadecimal: 0x2a
      binary-looking: 0b101
      sexagesimal-looking: 1:20
      Unicode值: "雪☃é🙂"
      nested: [null, true, false, 01234, on, {date: 2026-10-03, quote: "a: b # c"}]
  client-connectionish/probe: {kind: grant, payload: {keep: on}}
  provider/client-connection-probe: {kind: api-key, key: yes}
`);
  valid('block-folded-comment-and-document-marker',`---
# fake comment must not affect semantic types
version: 1
refs:
  PROBE_MULTILINE: |+
    on
    yes
    雪☃é🙂

records:
  client-connection/browser-session: {kind: grant, payload: {version: 1, secret: fake-probe-only}}
  provider/probe-api:
    kind: api-key
    key: >-
      fake first
      fake second
    env: {PROBE_QUOTE: 'a: b # c'}
  provider/probe-grant:
    kind: grant
    payload:
      literal: |-
        true
        01234
      folded: >+
        yes
        on

`);
  valid('quoted-machine-key-flow-and-standard-tags',`{version: 1, refs: {PROBE_TEXT: !!str on}, records: {"\\u0063lient-connection/browser-session": {kind: grant, payload: {secret: fake-probe-only}}, provider/probe-grant: {kind: grant, payload: {number: !!int 01234, string: !!str 01234, flag: !!bool true, float: !!float 1.2, "null": null}}}}\n`);
  valid('no-machine-keeps-exact-bytes','version: 1\r\nrefs: {PROBE_TEXT: yes}\r\nrecords: {provider/probe-api: {kind: api-key, key: "01234"}}\r\n',{unchanged:true});
  valid('empty-mapping','{}\n',{unchanged:true});valid('empty-text',' \n\t',{unchanged:true});
  valid('null-root','null\n',{unchanged:true});valid('null-sections','version: 1\nrefs: null\nrecords: null\n',{unchanged:true});
  const flat='PROBE_TEXT: yes\nPROBE_ZERO: "01234"\n';
  const flatResult=projection(flat);assert.equal(flatResult.status,0);assert.equal(flatResult.stdout.toString(),flat);
  assert.equal(credentials.renderFlatLayoutMigration(flatResult.stdout.toString()),credentials.renderFlatLayoutMigration(flat));
  metadata.cases.push({name:'recognized-legacy-flat-byte-identity',status:'passed'});legacy++;
  for(const [name,text] of [
    ['unknown-top-level',header+'future: {opaque: fake-only}\n'],
    ['unknown-version',header.replace('version: 1\n','version: 2\n')],
    ['unknown-record-kind',header+'  provider/probe: {kind: future-record, payload: fake-only}\n'],
    ['unknown-record-field',header+'  provider/probe: {kind: api-key, key: fake-only, future: fake-only}\n'],
    ['non-string-api-key',header+'  provider/probe: {kind: api-key, key: 01234}\n'],
    ['non-string-env',header+'  provider/probe: {kind: api-key, env: {PROBE_TEXT: true}}\n'],
    ['non-finite-json',header+'  provider/probe: {kind: grant, payload: [.inf, .nan]}\n'],
    ['duplicate-root',header+'version: 1\n'],
    ['duplicate-nested',header+'  provider/probe: {kind: grant, payload: {same: yes, same: off}}\n'],
    ['alias',header+'  provider/probe: {kind: grant, payload: [&alias fake-only, *alias]}\n'],
    ['custom-tag',header+'  provider/probe: {kind: api-key, key: !custom fake-only}\n'],
    ['non-string-yaml-collection-key',header+'  provider/probe: {kind: grant, payload: {null: fake-only}}\n'],
    ['yaml-11-directive','%YAML 1.1\n---\n'+header],
    ['yaml-12-explicit-directive','%YAML 1.2\n---\n'+header],
    ['multiple-documents',header+'---\nversion: 1\n'],
    ['wrong-record-namespace',header+'  Uppercase/probe: {kind: api-key, key: fake-only}\n'],
    ['invalid-ref-name','version: 1\nrefs: {"NOT-ENV": fake-only}\nrecords: {client-connection/browser-session: {kind: grant, payload: {}}}\n'],
    ['deep-collection',header+'  provider/probe: {kind: grant, payload: '+ '['.repeat(34)+'null'+']'.repeat(34)+'}\n'],
    ['malformed-utf8',Buffer.from([0xc3,0x28])],
    ['byte-budget',Buffer.alloc(1024*1024+1,32)]
  ]) invalid(name,text);
  // Actual rc2 writer creates the source and actual reader re-opens the projected provider data.
  const fake=path.join(temporary,'fake-writer.credentials.yaml');
  const ctx=new Context();
  try {
    ctx.provide('launchEnvironment',createLaunchEnvironmentSnapshot([]));
    await ctx.plugin(credentials.LocalCredentialProvider,{path:fake,watch:false});
    await ctx.credentials.set(credentialRef('PROBE_WRITER_TEXT'),'on');
    await ctx.credentials.modifyRecord(credentialKey('provider','probe-api'),async()=>({kind:'api-key',key:'yes',env:{PROBE_WRITER_ZERO:'01234',PROBE_WRITER_UNICODE:'雪☃é🙂'}}));
    await ctx.credentials.modifyRecord(credentialKey('provider','probe-grant'),async()=>({kind:'grant',payload:{on:'on',leading:'01234',date:'2026-10-03',multiline:'yes\non\n雪',nested:[true,false,null,1234,{off:'off'}]}}));
    const fakeMachineSecret=Buffer.alloc(32,17).toString('base64url');
    await ctx.credentials.modifyRecord(credentialKey('client-connection','browser-session'),async()=>({kind:'grant',payload:{version:1,secret:fakeMachineSecret}}));
    valid('actual-rc2-writer-and-parser',fs.readFileSync(fake,'utf8'));
  } finally {await ctx.fiber.dispose();}
  assert.equal(hash(fs.readFileSync(path.join(root,'app/src/main/java/com/deepseekharness/app/backup/CredentialProjection.java'))),metadata.projectionSourceSha256,'projection source changed during differential check');
  metadata.counts={preserved:checked,legacy,rejected};
  fs.writeFileSync(path.join(root,'app/build/credential-yaml-projection-receipt.json'),JSON.stringify(metadata,null,2)+'\n');
  console.log(`PASS credential projection differential: ${checked} current cases, ${legacy} byte-identical legacy case, ${rejected} explicit refusals; actual rc2 writer/parser and locked yaml ${metadata.nodeYamlVersion} core 1.2.`);
} finally {
  const actual=fs.realpathSync(temporary);
  if(path.dirname(actual)!==build||!path.basename(actual).startsWith('credential-projection-probe-')||fs.lstatSync(temporary).isSymbolicLink())throw Error('PROJECTION_PROBE_CLEANUP_BOUNDARY');
  fs.rmSync(temporary,{recursive:true,force:true});
}
