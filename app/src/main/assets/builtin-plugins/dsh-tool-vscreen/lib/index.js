import * as McpClient from '@deepseek-ai/dsh-mcp-client';
import { fileURLToPath } from 'node:url';

export const name = 'dsh-tool-vscreen';
export const inject = ['tools'];
export async function apply(ctx) {
  const config = McpClient.Config({transport:'stdio',serverName:'dsha-vscreen',
    command:process.execPath,args:[fileURLToPath(new URL('./server.cjs',import.meta.url))],
    failOnStartupError:true,toolCallTimeoutMs:60000,reconnect:{enabled:false},
    // server.cjs 是 2025 版 stdio 服务（只认 initialize）。legacy 让 SDK 直接 initialize，
    // 不再先起一个 server/discover 探测子进程再杀掉；需运行时补丁 DSHA_MCP_VERSION_NEGOTIATION_V1，
    // 未打补丁时该键被忽略，行为与原来相同。
    versionNegotiation:'legacy'});
  let child;
  ctx.effect(function*(){child=ctx.plugin(McpClient,config);yield child.dispose;},'dsha-vscreen-tools');
  await child.await();
}
