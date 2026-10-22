import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';

/** Rebuild the real current inventory dependency beside a proven raw fixture.
 * Callers must verify raw with testRuntime('raw') first. Only the manager's one
 * package import is adapted; neither raw bytes nor production recipe text is
 * edited, and no missing export is synthesized for the fixture.
 */
export function preparePrivatePluginInventory({source,raw,directory,managerSource}) {
  const assets=path.join(source,'app/src/main/assets');
  const moduleName='@deepseek-ai/dsh-host-plugin-inventory/lib/index.js';
  const sha=value=>createHash('sha256').update(value).digest('hex');
  const canonical=fs.readFileSync(path.join(raw,'node_modules',moduleName),'utf8').replaceAll('\r\n','\n');
  let patched=canonical;
  const registry=JSON.parse(fs.readFileSync(path.join(assets,'runtime-patches.json')));
  assert.equal(registry.schema,1);assert.equal(registry.dshVersion,'0.2.1-rc.2');
  const recipes=[];
  for(const row of registry.active){
    const file=path.join(assets,row.asset),recipe=JSON.parse(fs.readFileSync(file));
    if((row.module??recipe.module)!==moduleName)continue;
    assert.equal(recipe.dshVersion,registry.dshVersion,row.asset);
    if(recipe.sourceSha256!==undefined)assert.equal(recipe.sourceSha256,sha(canonical),row.asset+' raw source identity');
    recipes.push({asset:row.asset,sha256:sha(fs.readFileSync(file)),markers:row.markers??[]});
    for(const patch of recipe.patches){
      assert.equal(patched.split(patch.before).length,2,row.asset+' canonical anchor');
      const after=patch.prependAsset===undefined?patch.after:
        fs.readFileSync(path.join(assets,patch.prependAsset),'utf8')+'\n'+patch.after;
      patched=patched.replace(patch.before,after);
    }
  }
  const needle='from "@deepseek-ai/dsh-host-plugin-inventory"';
  assert.equal(managerSource.split(needle).length,2,'exactly one inventory package import must be adapted');
  const file=path.join(directory,'dsha-current-plugin-inventory.mjs');
  fs.writeFileSync(file,patched,{flag:'wx'});
  const specifier=pathToFileURL(file).href;
  const adapted=managerSource.replace(needle,'from '+JSON.stringify(specifier));
  assert.equal(adapted.replace('from '+JSON.stringify(specifier),needle),managerSource,
    'fixture import adaptation must not change manager behavior');
  return {
    source:adapted,
    evidence:{module:moduleName,rawSha256:sha(canonical),patchedSha256:sha(patched),recipes,
      importAdaptation:{from:'@deepseek-ai/dsh-host-plugin-inventory',to:specifier,count:1},
      productionManagerSha256:sha(managerSource),fixtureManagerSha256:sha(adapted)}
  };
}
