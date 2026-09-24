// Current DSH test runtime selection. Old historical fixtures must opt in explicitly.
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
const root=path.resolve(import.meta.dirname,'..');
const sha=file=>createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const json=file=>JSON.parse(fs.readFileSync(file,'utf8'));
export function testRuntime(kind='raw', environment=kind==='raw'?'DSHA_TEST_RUNTIME':'DSHA_TEST_RUNTIME') {
  const expected=json(path.join(root,'tools/dsh-runtime/package.json')).dependencies['@deepseek-ai/dsh'];
  let directory=process.env[environment];
  if(!directory){const pointer=path.join(root,'app/build/test-runtimes/current.json');if(!fs.existsSync(pointer))throw Error('Run python tools/prepare-test-runtime.py to prepare current test inputs');directory=json(pointer)[kind];}
  directory=path.resolve(directory);
  const actual=json(path.join(directory,'node_modules/@deepseek-ai/dsh/package.json')).version;
  if(actual!==expected)throw Error(`Wrong runtime generation: expected ${expected}, found ${actual} at ${directory}`);
  const proofFile=path.join(directory,'dsha-test-runtime.json');
  if(!fs.existsSync(proofFile)) {
    if(process.env.DSHA_ALLOW_UNVERIFIED_RUNTIME!=='1')throw Error('Missing reproducible fixture provenance: '+directory);
    return directory;
  }
  const proof=json(proofFile);
  if(proof.kind!==kind||proof.lockSha256!==sha(path.join(root,'tools/dsh-runtime/package-lock.json')))throw Error('Stale or wrong fixture kind: '+directory);
  for(const [file,digest] of Object.entries({...proof.archiveRecipeInputs,...proof.overlayInputs}))if(sha(path.join(root,file))!==digest)throw Error('Fixture patch inputs changed: '+file);
  if(!proof.moduleHashes)throw Error('Missing per-module fixture fingerprints: '+directory);
  for(const [file,digest] of Object.entries(proof.moduleHashes))if(sha(path.join(directory,file))!==digest)throw Error('Fixture module bytes changed: '+file);
  return directory;
}
export function requireNativeHost(runtime) {
  const proof=JSON.parse(fs.readFileSync(path.join(runtime,'dsha-test-runtime.json'),'utf8'));
  if(proof.platform.os!==process.platform||proof.platform.cpu!==process.arch)throw Error(`Native test platform mismatch: fixture ${proof.platform.os}/${proof.platform.cpu}, host ${process.platform}/${process.arch}`);
}
