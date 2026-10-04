import fs from 'node:fs';
import path from 'node:path';

// The APK manifest supplies public versions. The matching checkout is a fence
// against accidentally rebuilding a new site with an older delivery manifest.
export function sourceIdentity(repository) {
  const gradle = fs.readFileSync(path.join(repository, 'app/build.gradle'), 'utf8');
  const version = gradle.match(/^\s*versionName\s+"([^"]+)"/m)?.[1];
  const code = gradle.match(/^\s*versionCode\s+(\d+)\s*$/m)?.[1];
  const runtime = JSON.parse(fs.readFileSync(path.join(repository, 'app/src/main/assets/runtime-descriptor.json'), 'utf8'));
  const packages = JSON.parse(fs.readFileSync(path.join(repository, 'tools/dsh-runtime/package.json'), 'utf8'));
  const dshVersion = packages.dependencies?.['@deepseek-ai/dsh'];
  if (!version || !code || !dshVersion || !/^[a-f0-9]{64}$/.test(runtime.runtimeId || '')) {
    throw new Error('网站缺少明确的源码与受管运行时身份');
  }
  if (runtime.dshVersion !== dshVersion) throw new Error('受管描述符与 DSH 锁定输入不一致');
  return {version, versionCode: Number(code), dshVersion, runtimeId: runtime.runtimeId};
}

export function validateReleaseIdentity(current, source) {
  for (const key of ['version', 'versionCode', 'dshVersion', 'runtimeId']) {
    if (current[key] !== source[key]) {
      throw new Error(`网站发布清单与所选源码不匹配：${key}。请从最终 APK 生成清单，或选择与线上包一致的源码快照。`);
    }
  }
  if (current.artifacts?.length !== 2 || new Set(current.artifacts.map(value => value.flavor)).size !== 2) {
    throw new Error('网站需要实际 Standard/Low 两版 APK');
  }
  for (const artifact of current.artifacts) {
    if (!['standard', 'low'].includes(artifact.flavor) || artifact.versionCode !== current.versionCode ||
        artifact.versionName !== current.version + (artifact.flavor === 'low' ? 'low' : '') ||
        artifact.dshVersion !== current.dshVersion || artifact.runtimeId !== current.runtimeId ||
        !/^[a-f0-9]{64}$/.test(artifact.sha256 || '')) {
      throw new Error('网站 APK 身份与实际发布清单不一致');
    }
  }
}
