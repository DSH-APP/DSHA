/* DSHA_DEEPSEEK_MESSAGES_PROJECTED_TOOL_CALL_V1
 * Agent Team and subagent delivery can retain a display-only tool-call block
 * inside a user message or inside another tool's result. DeepSeek Messages
 * reserves tool_use for assistant turns, so keep the projection as stable text
 * instead of replaying it as a new call or silently discarding it.
 */
function dshaMessagesProjectedToolCall(block) {
	return {
		type: "text",
		text: `[Projected tool call] ${JSON.stringify({
			id: block.id,
			name: block.name,
			arguments: block.arguments
		})}`
	};
}
