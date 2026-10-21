#!/usr/bin/env python3
"""Generate #66 recipes from the canonical locked rc2 npm tree; no runtime execution."""
import argparse, hashlib, json
from pathlib import Path
from source_text import write_text
ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'app/src/main/assets'

def main():
    ap = argparse.ArgumentParser(); ap.add_argument('modules', type=Path); args = ap.parse_args()
    modules = args.modules.resolve()
    expected = json.loads((ROOT/'tools/dsh-runtime/package.json').read_text())['dependencies']['@deepseek-ai/dsh']
    if expected != '0.2.1-rc.2': raise ValueError('TOOL_IDENTITY_DSH_VERSION')
    def recipe(asset, module, patches):
        file = modules/module; source = file.read_text(encoding='utf8')
        package = json.loads((modules/Path(module).parts[0]/Path(module).parts[1]/'package.json').read_text())
        probe = source
        for before, after in patches:
            if probe.count(before) != 1: raise ValueError('TOOL_IDENTITY_ANCHOR:'+asset)
            probe = probe.replace(before, after)
        value = dict(dshVersion=expected, module=module, packageVersion=package['version'],
                     canonicalSourceSha256=hashlib.sha256(file.read_bytes()).hexdigest(),
                     patches=[dict(before=a, after=b) for a,b in patches])
        write_text(ASSETS/asset, json.dumps(value, ensure_ascii=False, indent=2)+'\n', encoding='utf8')
    valid = 'typeof block.id === "string" && block.id.trim() !== "" && typeof block.name === "string" && block.name.trim() !== ""'
    recipe('tool-stream-identity-patch.json', '@deepseek-ai/dsh-llm-pi-ai/lib/index.js', [
      ('\t\t\tconst known = toolIds.get(event.contentIndex);', '''\t\t\t// DSHA_TOOL_LATE_IDENTITY_RC2_V1: providers may populate identity after start.
\t\t\tconst previous = toolIds.get(event.contentIndex);
\t\t\tconst partial = event.partial?.content?.[event.contentIndex];
\t\t\tconst current = partial?.type === "toolCall" ? partial : undefined;
\t\t\tconst known = {
\t\t\t\tid: typeof current?.id === "string" && current.id.trim() ? current.id : previous?.id ?? "",
\t\t\t\tname: typeof current?.name === "string" && current.name.trim() ? current.name : previous?.name ?? ""
\t\t\t};
\t\t\ttoolIds.set(event.contentIndex, known);'''),
      ('function assertSupportedHistory(messages) {\n\tfor (const message of messages) {', '''function assertSupportedHistory(messages) {
\t// DSHA_TOOL_HISTORY_READ_ONLY_RC2_V1: reject malformed legacy protocol before HTTP; never rewrite it.
\tfor (const message of messages) {
\t\tif (message.role === "assistant" && message.content.some(block => block.type === "tool-call" && !('''+valid+''')))
\t\t\tthrow new LlmError("DSHA_INVALID_TOOL_HISTORY: incomplete tool identity; retained history is read only. Start a new session.", "INVALID_REQUEST");
\t\tif (message.role === "tool" && (typeof message.toolCallId !== "string" || !message.toolCallId.trim()))
\t\t\tthrow new LlmError("DSHA_INVALID_TOOL_HISTORY: missing result call id; retained history is read only.", "INVALID_REQUEST");''')])
    recipe('tool-block-identity-patch.json', '@deepseek-ai/dsh-llm/lib/types/assembler.js', [
      ('                partial.toolCallId = chunk.id;', "                // DSHA_TOOL_ASSEMBLER_IDENTITY_RC2_V1: empty deltas must not erase a late valid identity.\n                if (typeof chunk.id === 'string' && chunk.id.trim()) partial.toolCallId = chunk.id;"),
      ('                if (chunk.name)', "                if (typeof chunk.name === 'string' && chunk.name.trim())"),
      ('                partial.block = chunk.block;', '''                partial.block = chunk.block.type === 'tool-call' ? {
                    ...chunk.block,
                    id: typeof chunk.block.id === 'string' && chunk.block.id.trim() ? chunk.block.id : partial.toolCallId ?? '',
                    name: typeof chunk.block.name === 'string' && chunk.block.name.trim() ? chunk.block.name : partial.toolCallName ?? '',
                } : chunk.block;'''),
      ('id: partial.toolCallId ?? brandString(`call-${index}`),', "id: partial.toolCallId ?? '',")])
    recipe('tool-dispatch-validation-patch.json', '@deepseek-ai/dsh-agent-loop/lib/index.js', [
      ('\tconst planned = toolCalls.map((block) => ({', '''\t// DSHA_TOOL_DISPATCH_IDENTITY_RC2_V1: preflight the whole group before the first side effect.
\tif (toolCalls.some(block => !('''+valid+'''))) throw new LlmError("DSHA_INVALID_TOOL_CALL: missing name/id; this step was not dispatched. No automatic replay.", "INVALID_RESPONSE");
\tconst planned = toolCalls.map((block) => ({'''),
      ('\t\t\t\tconst message = createAssistantMessage({\n\t\t\t\t\tcontent: live.blocks(),', '''\t\t\t\t// DSHA_TOOL_FINISH_IDENTITY_RC2_V1: preserve valid structure, render malformed calls as non-executable diagnostics.
\t\t\t\tconst assembledBlocks = live.blocks();
\t\t\t\tconst invalidIdentity = assembledBlocks.some(block => block.type === "tool-call" && !('''+valid+'''));
\t\t\t\tconst message = createAssistantMessage({
\t\t\t\t\tcontent: assembledBlocks.map(block => block.type === "tool-call" && !('''+valid+''') ? { type: "text", text: "[DSHA_INVALID_TOOL_CALL] Missing tool name/id in this step. No tool in this step was executed; no automatic replay." } : block),'''),
      ('\t\t\t\t\t\t...live.replayState !== void 0 ? { replayState: live.replayState } : {}', '\t\t\t\t\t\t...!invalidIdentity && live.replayState !== void 0 ? { replayState: live.replayState } : {}'),
      ('\t\t\t\tif (finish.kind === "max-tokens") return { kind: "max-tokens" };', '''\t\t\t\tif (invalidIdentity) {
\t\t\t\t\tfor (const block of message.content) if (block.type === "tool-call") appendSkippedToolCall(this.session, turn, step, block);
\t\t\t\t\tthrow new LlmError("DSHA_INVALID_TOOL_CALL: missing name/id; this step was not dispatched. No automatic replay.", "INVALID_RESPONSE");
\t\t\t\t}
\t\t\t\tif (finish.kind === "max-tokens") return { kind: "max-tokens" };''')])
    recipe('tool-messages-history-patch.json', '@deepseek-ai/dsh-llm-deepseek/lib/index.js', [
      ('\tconst model = connection.models.find((entry) => entry.id === options.model);\n\tconst inHistory =', '''\t// DSHA_MESSAGES_TOOL_HISTORY_READ_ONLY_RC2_V1: validation precedes request serialization/HTTP.
\tfor (const message of history) {
\t\tif (message.role === "assistant" && message.content.some(block => block.type === "tool-call" && !('''+valid+''')))
\t\t\tthrow new LlmError("DSHA_INVALID_TOOL_HISTORY: incomplete tool identity; retained history is read only. Start a new session.", "INVALID_REQUEST");
\t\tif (message.role === "tool" && (typeof message.toolCallId !== "string" || !message.toolCallId.trim()))
\t\t\tthrow new LlmError("DSHA_INVALID_TOOL_HISTORY: missing result call id; retained history is read only.", "INVALID_REQUEST");
\t}
\tconst model = connection.models.find((entry) => entry.id === options.model);
\tconst inHistory =''')])
    recipe('tool-history-display-patch.json', '@deepseek-ai/dsh-client-ui-tool/lib/client.js', [
      ('\t\t\treturn call.phase === "result" ? call.block.call?.name ?? "" : call.block.name;', '''\t\t\t// DSHA_TOOL_HISTORY_DISPLAY_RC2_V1: no named tool view/action for malformed retained rows.
\t\t\tconst name = call.phase === "result" ? call.block.call?.name ?? "" : call.block.name;
\t\t\tconst id = call.block.callId;
\t\t\treturn typeof name === "string" && name.trim() && typeof id === "string" && id.trim() ? name : "DSHA_INVALID_TOOL_CALL (read only)";''')])

if __name__ == '__main__': main()
