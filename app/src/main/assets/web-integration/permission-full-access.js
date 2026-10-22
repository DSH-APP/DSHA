// DSHA_PERMISSION_FULL_CURRENT_DEFAULT_V1
// Only an explicit current-session picker action calls this coordinator.
// Official Session permission events remain data, not native device grants.
function createDshaPermissionSubmit(ctx, describeFace) {
  const full = 'danger-full-access', pending = new Map();
  let disposed = false;
  function begin(sessionId, preset) {
    const binding = ctx.sessions.binding(sessionId), live = binding?.session;
    const generation = ctx.connection.generation.getSnapshot()?.id;
    let current = 'unchanged', next = 'unchanged';
    const summary = () => '当前会话：' + ({ unchanged: '未更改', unknown: '结果待核对', confirmed: '完全访问' })[current]
      + '；新会话默认值：' + ({ unchanged: '未更改', unknown: '结果待核对', confirmed: '完全访问' })[next] + '。';
    const error = code => new Error('权限设置未完成（' + code + '）。' + summary() + '请核对后重新选择，不会自动重试。');
    const check = () => {
      if (disposed || !live || generation === undefined || ctx.sessions.binding(sessionId) !== binding
          || binding.session !== live || ctx.connection.generation.getSnapshot()?.id !== generation) throw error('CONTEXT_CHANGED');
    };
    const valueOf = (response, code) => {
      if (!response?.ok) throw error(code);
      return response.value;
    };
    const namespace = value => value?.namespaces?.find(entry => entry.ns === 'permission');
    async function readCurrent() {
      check(); current = 'unknown';
      const value = valueOf(await ctx.remote.session.projections({ sessionId }), 'CURRENT_READ');
      check();
      if (value?.kind !== 'sequenced' || value.values?.permissions?.currentValue !== full) throw error('CURRENT_NOT_FULL');
      current = 'confirmed';
    }
    return (async () => {
      check();
      if (preset !== full) {
        const result = valueOf(await live.command('/permission ' + preset), 'CURRENT_COMMAND');
        check();
        if (!result?.matched) throw error('COMMAND_UNAVAILABLE');
        return true;
      }
      // Read the actual provider revision before either write. An unwritable
      // profile must not silently turn a two-part selection into partial success.
      const before = valueOf(await ctx.remote.settings.describe(), 'DEFAULT_READ');
      check();
      const permission = namespace(before);
      if (!before?.writable || !permission || !Number.isSafeInteger(permission.revision)) throw error('DEFAULT_NOT_WRITABLE');
      current = 'unknown';
      const result = valueOf(await live.command('/permission ' + full), 'CURRENT_COMMAND');
      check();
      if (!result?.matched) throw error('COMMAND_UNAVAILABLE');
      await readCurrent();
      if (permission.value?.defaultPreset !== full) {
        check(); next = 'unknown';
        const written = valueOf(await ctx.remote.settings.mutate('permission', [{ op: 'set', path: ['defaultPreset'], value: full }], permission.revision), 'DEFAULT_WRITE');
        check();
        if (namespace(written)?.value?.defaultPreset !== full) throw error('DEFAULT_WRITE_NOT_FULL');
        describeFace.acceptView(written);
      }
      check();
      const after = valueOf(await ctx.remote.settings.describe(), 'DEFAULT_READBACK');
      check();
      if (namespace(after)?.value?.defaultPreset !== full) throw error('DEFAULT_READBACK_NOT_FULL');
      next = 'confirmed';
      describeFace.acceptView(after);
      await readCurrent();
      return true;
    })().catch(failure => {
      if (failure instanceof Error && failure.message.startsWith('权限设置未完成（')) throw failure;
      throw error('RESULT_UNKNOWN');
    });
  }
  return {
    submit(sessionId, preset) {
      if (preset === 'guarded-full-access') return Promise.reject(new Error('历史权限配置仅兼容读取，不再提供新选择；DSHA 安全确认由设备页的全局权限控制。'));
      if (pending.has(sessionId)) return Promise.reject(new Error('该会话的权限选择仍在处理，请等待结果；不会重复提交。'));
      let started;
      try { started = begin(sessionId, preset); }
      catch { return Promise.reject(new Error('权限上下文不可用；未确认任何更改，请核对后重新选择。')); }
      const operation = started.finally(() => {
        if (pending.get(sessionId) === operation) pending.delete(sessionId);
      });
      pending.set(sessionId, operation);
      return operation;
    },
    dispose() { disposed = true; },
  };
}
