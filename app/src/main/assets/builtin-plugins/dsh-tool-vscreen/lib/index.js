import * as McpClient from '@deepseek-ai/dsh-mcp-client';
import { fileURLToPath } from 'node:url';

export const name = 'dsh-tool-vscreen';
export const inject = ['tools'];
export async function apply(ctx) {
  const config = McpClient.Config({transport:'stdio',serverName:'dsha-vscreen',
    command:process.execPath,args:[fileURLToPath(new URL('./server.cjs',import.meta.url))],
    failOnStartupError:true,toolCallTimeoutMs:60000,reconnect:{enabled:false}});
  let child;
  ctx.effect(function*(){child=ctx.plugin(McpClient,config);yield child.dispose;},'dsha-vscreen-tools');
  await child.await();
}
