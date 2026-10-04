import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { applyMobileServerPatch, upstreamServerHashes } from './apply-mobile-client-patches.mjs';

for(const [name,hash] of Object.entries(upstreamServerHashes)) {
  test(`mobile server ${name} canonical output is idempotent and input drift is rejected`,()=>{
    const output=readFileSync(join('app/src/main/assets/builtin-plugins/dsh-web-mobile/lib',name));
    assert.deepEqual(applyMobileServerPatch(name,output),output);
    assert.deepEqual(applyMobileServerPatch(name,applyMobileServerPatch(name,output)),output);
    assert.throws(()=>applyMobileServerPatch(name,Buffer.concat([output,Buffer.from('\n// drift')])) ,/fixed commit\/anchor/);
    const upstream=join(process.env.DSHA_MOBILE_UPSTREAM_DIR??'app/build/mobile-upstream-a094/lib',name);
    if(existsSync(upstream)) {
      const bytes=readFileSync(upstream);assert.equal(createHash('sha256').update(bytes).digest('hex'),hash);
      assert.deepEqual(applyMobileServerPatch(name,bytes),output);
    }
  });
}
