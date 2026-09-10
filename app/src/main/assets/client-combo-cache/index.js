/** 换行只需搜索 LF；避免为整份网页脚本逐个创建 Unicode 字符。 */
export function countNewlines(value) {
  let count = 0, offset = -1;
  while ((offset = value.indexOf('\n', offset + 1)) !== -1) count++;
  return count;
}

/** 仅复用上一轮图中相同的脚本与映射；本轮未使用的结果随旧图释放。 */
export function createComboCache(build, previous = new Map()) {
  const next = new Map();
  function cached(records, revision) {
    const key = JSON.stringify([revision, records.map(record => [record.entry.id, record.entry.rev])]);
    let saved = next.get(key) || previous.get(key);
    if (!saved || saved.inputs.length !== records.length || !records.every((record, index) =>
      saved.inputs[index].bundle === record.bundle && saved.inputs[index].sourceMap === record.sourceMap)) {
      saved = { inputs: records.map(record => ({bundle: record.bundle, sourceMap: record.sourceMap})),
        artifact: build(records, revision) };
    }
    next.set(key, saved);
    return saved.artifact;
  }
  cached.cache = next;
  return cached;
}
