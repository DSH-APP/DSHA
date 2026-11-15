/*
 * 弹层模板：四类原生弹层在各档位的形态。几何全部来自 kit popupGeom（原样 / 提案两套）。
 * 来源：ui/CardSheet.java（create / show / choices / show(title, body)）、ui/DshaDialogBuilder.java（sizeOnce）、
 *       ui/AutomaticBackupActivity.java（「备份时机」choices、「间隔 1–168 小时」输入框）、
 *       ui/DeviceGrantsFragment.java（「设备通道状态」说明）、ui/ExtraKeysDialog.java（长表单，见终端页）。
 * 背后页面固定画「自动备份」CardPage，只作遮罩下的背景。
 */
(function () {
  const { h } = DSHA;
  const back = () => `<div class="scroll"><div class="pg">${h.title('自动备份', '到期仍在运行时延后，不强停 Web 或终端。')}` +
    h.entries([
      { title: '备份时机', value: '每天指定时间', icon: 'ui2_box' },
      { title: '时间', value: '03:00', icon: 'ui2_box' },
      { title: '保留份数', value: '3', icon: 'ui2_box' }]) +
    `<div class="card">${h.kv('上次自动备份', '今天 03:00')}${h.kv('副本', '3 份 · 已验证')}</div></div></div>`;
  const STATES = {
    choices: () => ({ kind: 'sheet', size: 's', title: '备份时机',
      body: h.entries([{ title: '每天指定时间', icon: 'ui2_box' }, { title: '每隔一段时间', icon: 'ui2_box' }, { title: '停止 DSH 后', icon: 'ui2_box' }], true),
      actions: [{ t: '取消' }] }),
    info: () => ({ kind: 'sheet', size: 'm', title: '设备通道状态',
      body: `<p class="pp-sub" style="padding-top:0;font-size:13px;line-height:1.7">Root：未启用。授权后经 root 管理器确认，受同一套设备命令策略检查。\nShizuku：未授权。安装并启动 Shizuku 后点「请求授权」；回调核对管理器 UID 与单次请求。\nADB：未配对。无线调试配对后可在没有 Root 与 Shizuku 时执行受管命令。\n三个通道共用白名单，拦截返回 [POLICY_BLOCKED]，没有「仍然允许」。</p>`.replace(/\n/g, '<br>'),
      actions: [{ t: '关闭' }] }),
    form: () => ({ kind: 'dialog', title: '间隔 1–168 小时',
      body: `<div class="field focus">24</div><div class="hint">到期仍在运行时延后执行。</div>`,
      actions: [{ t: '取消' }, { t: '保存', pri: true }] }),
    restore: () => ({ kind: 'sheet', size: 'm', title: '自动副本 · 今天 03:00',
      sub: '已验证 · 61.8 MB · 应用数据、对话与用户插件',
      body: h.entries([{ title: '预检恢复', sub: '先核对内容与范围，不写入', icon: 'ui2_box' }, { title: '导出无密码备份', sub: 'DSHA-data-v5-*.tar.gz', icon: 'ui2_box' }], true),
      actions: [{ t: '取消' }] }),
  };
  DSHA.page({
    id: 'system-popups', group: '任务与系统', title: '弹层模板', shell: 'sub',
    source: 'CardSheet（create / show / choices）· DshaDialogBuilder.sizeOnce · AutomaticBackupActivity · DeviceGrantsFragment',
    states: [{ id: 'choices', title: '选择列表（短）' }, { id: 'info', title: '说明文字（中）' }, { id: 'form', title: '输入对话框' }, { id: 'restore', title: '副本操作（标题 + 副标题 + 列表）' }],
    render(env) {
      return back() + DSHA.popup(env, STATES[env.state]());
    },
  });
})();
