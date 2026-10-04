/** 用独立临时副本与 renameat2 发布完整文件，保留 link 的源文件和 EEXIST 语义。 */
import { constants } from 'node:fs';
import { copyFile, open, link, lstat, rename, rm } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { dirname, join } from 'node:path';
import { getSystemErrorName } from 'node:util';
import { tryLockExclusive } from '@deepseek-ai/node-addon-system/flock';

let native;
async function kernelPublish(source, target) {
  if (!native) {
    const { default: koffi } = await import('koffi');
    try {
      const library = koffi.load(null);
      let renameNoReplace;
      try { renameNoReplace = library.func('renameat2', 'int', ['int', 'str', 'int', 'str', 'uint']); }
      catch {
        // musl 不一定导出包装函数；编号来自 Linux UAPI，Android arm64 与本地 x64 测试共用。
        const number = { arm64: 276, x64: 316 }[process.arch];
        if (number === undefined) throw new Error('未支持的 syscall 架构');
        const syscall = library.func('syscall', 'long', ['long', 'long', 'str', 'long', 'str', 'uint']);
        renameNoReplace = (...args) => syscall(number, ...args);
      }
      native = { koffi, library, rename: renameNoReplace };
    } catch (cause) { throw Object.assign(new Error('当前系统没有 renameat2', { cause }), { code: 'ENOSYS' }); }
  }
  if (native.rename(-100, source, -100, target, 1) === 0) return;
  const errno = native.koffi.errno();
  const code = getSystemErrorName(-errno);
  throw Object.assign(new Error(`${code}: 无法原子发布文件`), { code, errno, syscall: 'renameat2' });
}

async function lockedSessionPublish(source, target) {
  // 一目录一个永久 inode；不按每个文件永久留下锁，也绝不 unlink 有等待者的锁。
  // 普通文件工具不使用此降级，以保留对其他程序的严格 createIfAbsent 语义。
  const name = join(dirname(target), '.dsha-publish.lock');
  const lock = await open(name, constants.O_CREAT | constants.O_RDWR | constants.O_NOFOLLOW, 0o600);
  try {
    const identity = await lock.stat();
    if (!identity.isFile()) throw Object.assign(new Error('发布锁不是普通文件'), { code: 'EINVAL' });
    await syncParent(target);
    const deadline = Date.now() + 10_000;
    for (;;) {
      try { await tryLockExclusive(lock.fd); break; }
      catch (error) {
        if (!['EAGAIN', 'EWOULDBLOCK'].includes(error.code) || Date.now() >= deadline) throw error;
        await new Promise(resolve => setTimeout(resolve, 25));
      }
    }
    const current = await lstat(name);
    if (!current.isFile() || current.dev !== identity.dev || current.ino !== identity.ino)
      throw Object.assign(new Error('发布锁已被替换'), { code: 'ESTALE' });
    try {
      await lstat(target);
      throw Object.assign(new Error('目标会话文件已存在'), { code: 'EEXIST' });
    } catch (error) { if (error.code !== 'ENOENT') throw error; }
    await rename(source, target);
  } finally { await lock.close(); }
}

async function syncParent(target) {
  const parent = await open(dirname(target), constants.O_RDONLY | constants.O_DIRECTORY);
  try { await parent.sync(); } finally { await parent.close(); }
}

/** 依赖注入只用于隔离文件系统回归；部署入口固定使用真实内核操作。 */
export function createPublisher(renameNoReplace = kernelPublish) {
  return async function publish(source, target, privateSession = false, signal) {
    signal?.throwIfAborted();
    if (process.platform !== 'linux') return link(source, target);
    const temporary = target + '.dsha-publish-' + randomUUID() + '.tmp';
    let created = false;
    try {
      await copyFile(source, temporary, constants.COPYFILE_EXCL);
      created = true;
      const file = await open(temporary, 'r+');
      try { await file.sync(); } finally { await file.close(); }
      signal?.throwIfAborted();
      try { await renameNoReplace(temporary, target); }
      catch (error) {
        if (!privateSession || !['ENOSYS', 'EINVAL', 'EOPNOTSUPP'].includes(error.code)) throw error;
        await lockedSessionPublish(temporary, target);
      }
      await syncParent(target);
    } finally { if (created) await rm(temporary, { force: true }); }
  };
}
export const publishExclusive = createPublisher();
export function publishSessionExclusive(source, target) { return publishExclusive(source, target, true); }
// 图片、文件及不可变别名都保留源对象；不支持排他 rename 的内核使用相同协作锁。
export function publishAttachmentExclusive(source, target) { return publishExclusive(source, target, true); }
