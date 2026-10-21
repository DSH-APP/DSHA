// Test-only module resolution. Production keeps the signed launcher SDK path.
// No package or link is written into the read-only baseline runtime.
import {registerHooks} from 'node:module';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const sdk=process.env.DSHA_BUILTIN_TEST_SDK;
const runtime=process.env.DSHA_BUILTIN_TEST_RUNTIME;
registerHooks({resolve(specifier,context,nextResolve){
  if(specifier==='dsha-destructive-policy'&&process.env.DSHA_BUILTIN_TEST_POLICY)
    return {url:pathToFileURL(path.resolve(process.env.DSHA_BUILTIN_TEST_POLICY)).href,format:'module',shortCircuit:true};
  if(specifier==='/run/dsha-launcher/bridge-connection.cjs'&&sdk)
    return {url:pathToFileURL(path.resolve(sdk)).href,format:'commonjs',shortCircuit:true};
  if(specifier.startsWith('@deepseek-ai/')&&runtime)
    return nextResolve(specifier,{...context,parentURL:pathToFileURL(path.resolve(runtime,'package.json')).href});
  return nextResolve(specifier,context);
}});
