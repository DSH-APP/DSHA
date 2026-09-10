import { SessionFormatUnsupportedMigrationError } from '@deepseek-ai/dsh-session-format';

function guideSource(message) {
  const source = message?.source;
  if (message?.role !== 'user' || source?.kind !== 'dsh-device-guide'
      || source.plugin !== 'dsh-device-shell-guide'
      || Object.keys(source).some(key => !['kind', 'plugin'].includes(key))) return message;
  // rc.1 随包插件的确定来源；只修正分类，原消息身份、正文与时间均保持原样。
  return { ...message, source: { ...source, kind: 'plugin' } };
}

function normalizeSource(event) {
  if (event.type === 'user/message') return { ...event, data: guideSource(event.data) };
  if (event.type === 'agent/inbox/spliced' && Array.isArray(event.data?.inserted))
    return { ...event, data: { ...event.data, inserted: event.data.inserted.map(guideSource) } };
  if (event.type === 'session/title-llm-request' && Array.isArray(event.data?.messages))
    return { ...event, data: { ...event.data, messages: event.data.messages.map(guideSource) } };
  return event;
}

/**
 * 给官方 V2→V3 阶段补上 rc.1 的两种已知形状，不改变磁盘上的旧代际文件。
 * 旧循环先记认领的用户消息，再记 step/start；只把这一个延后的步骤标记前移。
 * 消息之间的顺序、消息内容、所有时间戳不变，序号引用用官方 remapEvent 同步换算。
 * 不跨分叉边界移动，不猜测未识别的事件，也不放宽官方的目标格式校验。
 */
export function wrapDshaLegacyStage(stage, input, remapEvent) {
  let prefix = [], started = false, received = 0, written = 0;
  const mapping = [];
  const emit = (event, context) => {
    mapping[event.seq] = written;
    stage.transformEvent(remapEvent(event, written++, mapping), context);
  };
  const flush = (context, hasStep) => {
    started = true;
    let ordered = prefix;
    if (hasStep) {
      const end = prefix.length - 1;
      const first = prefix.findIndex(event => ['user/message', 'assistant/message', 'tool/result'].includes(event.type));
      if (first >= 0 && first < end) {
        const step = prefix[end];
        const cut = input.sourceInheritedEventCount;
        if (!prefix.slice(first, end).every(event => event.type === 'user/message')
            || step.sourceEventSeqs !== undefined || step.surfaceOp !== undefined
            || (cut !== undefined && cut > prefix[first].seq && cut <= step.seq)) {
          throw new SessionFormatUnsupportedMigrationError('旧会话首步结构或分叉边界无法安全规范化，原始文件已保留');
        }
        ordered = [...prefix.slice(0, first), step, ...prefix.slice(first, end)];
      }
    }
    for (let index = 0; index < ordered.length; index++) mapping[ordered[index].seq] = written + index;
    for (const event of ordered) emit(event, context);
    prefix = [];
  };
  return {
    get headerInheritedEventCount() { return stage.headerInheritedEventCount; },
    transformEvent(event, context) {
      if (event.seq !== received++) throw new SessionFormatUnsupportedMigrationError('旧会话序号不连续，原始文件已保留');
      const normalized = normalizeSource(event);
      if (started) return emit(normalized, context);
      prefix.push(normalized);
      if (prefix.length > 20000) throw new SessionFormatUnsupportedMigrationError('旧会话首步前记录过多，原始文件已保留');
      if (event.type === 'step/start') flush(context, true);
    },
    transformRun(run, context) { for (const event of run.expand()) this.transformEvent(event, context); },
    finish(context) { if (!started) flush(context, false); return stage.finish(context); },
  };
}
