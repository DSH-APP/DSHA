// 真实提示组装：动作说明继承当前语言，不覆盖用户规则或改写历史内容。
import fs from 'node:fs';
import path from 'node:path';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const runtime=path.resolve(process.env.DSHA_TEST_RUNTIME||'app/build/locked-dsh-runtime');
const require=createRequire(path.join(runtime,'package.json'));
const load=name=>import(pathToFileURL(require.resolve(name)).href);
const {Context}=await load('@deepseek-ai/cordis');
const {default:SystemPrompt}=await load('@deepseek-ai/dsh-system-prompt');
const {createScope}=await load('@deepseek-ai/dsh-scope');
const file=path.resolve('app/src/main/assets/builtin-plugins/dsh-device-shell-guide/lib/index.js');
const source=fs.readFileSync(file,'utf8')
 .replace("'@deepseek-ai/dsh-llm'",JSON.stringify(pathToFileURL(require.resolve('@deepseek-ai/dsh-llm')).href))
 .replace("'./bridge-port.cjs'",JSON.stringify(pathToFileURL(path.join(path.dirname(file),'bridge-port.cjs')).href));
const guide=await import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
const previousPort=process.env.DSHA_BRIDGE_PORT;
process.env.DSHA_BRIDGE_PORT="12345";
const ctx=new Context();
try{
 await ctx.plugin(SystemPrompt,{});
 const policy=ctx.systemPrompt.section({name:'action-language',order:150,text:guide.languageInstruction('ru')});
 const custom='User chooses English for this task; preserve Bash and /tmp/example.txt.';
 ctx.systemPrompt.section({name:'user-custom',order:160,text:custom});
 const check=assembly=>{
  const text=assembly.sections.find(s=>s.name==='action-language').text;
  assert.match(text,/language: ru \(Russian\)/);
  assert.match(text,/progress\/tool descriptions, task\/subagent labels and displayed summaries/);
  assert.match(text,/pass it to subagents/);
  assert.match(text,/Explicit user requests, custom prompts and user-owned AGENTS.md override/);
  assert.match(text,/Bash\/Grep\/Glob\/PowerShell\/UI\/API/);
  assert.match(text,/raw output and tool IDs; never translate executable content/);
  assert.equal(assembly.sections.find(s=>s.name==='user-custom').text,custom);
 };
 check(await ctx.systemPrompt.assemble());
 const key={},child=createScope(ctx,key);
 try{check(await ctx.systemPrompt.assemble({scope:key}));}finally{await child.dispose();}
 const complete=ctx.systemPrompt.section({name:'user-complete',order:0,text:custom,complete:true});
 assert.deepEqual((await ctx.systemPrompt.assemble()).sections.map(s=>s.text),[custom]);complete();
 policy();assert.ok(!(await ctx.systemPrompt.assemble()).sections.some(s=>s.name==='action-language'));
 for(const language of ['zh','zh-Hant','en','ru','ja','ko','ar','pt','la','fr']){
  const text=guide.promptText(language);assert.ok(text.length<=3000,language+':'+text.length);
  assert.ok(text.includes('language: '+language+' ('));
 }
 console.log('PASS actual prompt assembly and child-scope language guidance, user/complete overrides, disposal, 10-language compact prompt budget');
}finally{await ctx.fiber.dispose();if(previousPort===undefined)delete process.env.DSHA_BRIDGE_PORT;else process.env.DSHA_BRIDGE_PORT=previousPort;}
