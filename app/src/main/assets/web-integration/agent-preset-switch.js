/* DSHA_AGENT_PRESET_SWITCH_V1：只有 Host 确认后才发布成功；已有对话的 Agent 组装保持原位。 */
function createDshaAgentPresetSwitcher(scope, seat) {
    let pending = false;
    const message = (zh, en) => scope.locale?.getSnapshot?.().active === 'en' ? en : zh;
    const current = () => {
        return seat.currentSession();
    };
    const selected = session => session?.projectionValues?.agentPreset;
    return async function switchPreset(id) {
        if (pending || seat.store.getSnapshot().busy) return message('上次预设切换尚未完成，请稍候。', 'The previous preset switch is still in progress. Please wait.');
        if (!seat.store.getSnapshot().options.some(option => option.id === id))
            return message('该预设当前不可用，请重新读取预设列表。', 'This preset is unavailable. Reload the preset list.');
        const origin = current(), previous = selected(origin) ?? seat.store.getSnapshot().current;
        if (origin === undefined) { seat.stage(id); return; }
        if (selected(origin) === id) return;
        pending = true; seat.staged.id = undefined; seat.staged.introduce = false; seat.set({ busy: true, error: null });
        try {
            let target = origin.id;
            if (!origin.blank) {
                const workspace = scope.uiWorkspace.workspaces.list.getSnapshot().items
                    .find(item => item.sessionIds.includes(origin.id));
                target = await scope.sessions.create(workspace ? { workspaceId: workspace.workspaceId }
                    : { cwd: origin.cwd });
            }
            const result = await scope.remote.agentPresets.select(target, id);
            if (!result.ok) throw new Error(result.error?.details?.reason ?? result.error?.message ?? message('服务端拒绝切换预设', 'The server refused to switch presets.'));
            if (result.value !== id) throw new Error(message('服务端返回的实际预设与选择不一致', 'The server returned a different preset from the one selected.'));
            if (current()?.id !== origin.id || (scope.sessions.list.getSnapshot().byId[origin.id]?.retainedBy?.mainView ?? 0) === 0)
                throw new Error(message('预设已应用，但当前会话已变化；请从会话列表打开目标会话。', 'The preset was applied, but the current session changed. Open the target session from the session list.'));
            if (target !== origin.id) scope.uiWorkspace.openSession(target);
            seat.set({ busy: false, error: null, current: id });
        } catch (error) {
            const reason = error instanceof Error ? error.message : String(error);
            seat.set({ busy: false, error: reason, current: selected(current()) ?? previous });
            return reason;
        } finally { pending = false; }
    };
}
