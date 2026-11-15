import * as McpClient from '@deepseek-ai/dsh-mcp-client';
import { ComputerUseProviderName } from '@deepseek-ai/dsh-computer-use/brand';
import { fileURLToPath } from 'node:url';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';
import { executionTask } from './task-binding.js';

export const name = 'dsh-computer-use-android';
export const inject = ['computerUse', 'tools'];
export async function apply(ctx) {
  // Use the locked rc3 public adapter, while retaining actual execution identity
  // in each MCP request. The generic rc3 client drops this context on tools/call.
  // Resolve SDK dependencies from their owning package, including pnpm layouts.
  const ownedRequire = createRequire(import.meta.resolve('@deepseek-ai/dsh-mcp-client'));
  const { Client } = await import(pathToFileURL(ownedRequire.resolve('@modelcontextprotocol/client')));
  const { StdioClientTransport } = await import(pathToFileURL(ownedRequire.resolve('@modelcontextprotocol/client/stdio')));
  const { scrubbedParentEnv } = await import(pathToFileURL(ownedRequire.resolve('@deepseek-ai/dsh-subprocess')));
  const client = new Client({name:'dsha-android-task-client',version:'0.1.1'},{capabilities:{}});
  // The SDK default is 10 MiB, smaller than base64-encoded native screenshot
  // responses (16 MiB maximum).
  const transport = new StdioClientTransport({command:process.execPath,
    args:[fileURLToPath(new URL('./server.cjs',import.meta.url))],env:scrubbedParentEnv(),
    maxBufferSize:24*1024*1024});
  const disposers=[],active=new Set();let closing=false;
  ctx.effect(function* () {
    yield ctx.computerUse.register(ComputerUseProviderName('dsha-android'));
    yield async () => { closing=true;for(const dispose of disposers)dispose();
      await client.close();await Promise.allSettled([...active]); };
  },'dsha-android-computer-use');
  await client.connect(transport);
  const {tools} = await client.listTools();
  for(const tool of tools){
    const publicName='mcp__dsha-android__'+tool.name;
    if(!/^android_[a-z_]+$/.test(tool.name)||publicName.length>64)throw Error('ANDROID_TOOL_ID_INVALID');
    disposers.push(ctx.tools.register(McpClient.createMcpToolDefinition(ctx,{
      name:publicName,rawName:tool.name,description:tool.description||'',inputSchema:tool.inputSchema,
      outputSchema:tool.outputSchema,taskRequired:tool.execution?.taskSupport==='required',
      call(args,execution){
        if(closing)throw Error('SCREEN_TRANSPORT_CLOSED');
        const task=executionTask(execution);
        const pending=client.callTool({name:tool.name,arguments:args,_meta:{'dsha/phone-task':task}},
          {signal:execution.signal,timeout:180000,toolDefinition:tool});
        active.add(pending);pending.finally(()=>active.delete(pending)).catch(()=>{});return pending;
      },
    })));
  }
  const instructions=client.getInstructions();
  if(instructions)ctx.inject(['systemPrompt'],scope=>scope.systemPrompt.section({
    name:'dsha:android-mcp',order:150,text:instructions,
  }));
}
