#!/bin/bash
# ============================================================
# 兜底补丁：落点不支持 flock(2) 时，把它当成「锁已拿到」而不是抛错。
#
# 背景（真机实测 V2352A，2026-09-05）：dsh 0.1.3 起每个会话目录里有 session.lock，
# 由 fs-ext 做非阻塞 flock。它的 lease.ts 只把 EAGAIN/EWOULDBLOCK 当「别人持锁」，
# **其它 errno 一律往上抛** → 写打开 session 直接失败、dsh 不可用。而 FUSE
# （/sdcard 一侧）上 flock 返回 ENOSYS。
#
# 正主是 session-home.sh：它保证 sessions 落在 App 私有目录（ext4，flock 实测正常）。
# 这个补丁是**第二道**，管的是那些兜不住的路径：迁移中间态、从镜像恢复的过程、
# 用户手改配置把会话指到别处、别家 ROM 的挂载差异。没有它，上述任一情况都是
# 「dsh 整个起不来」；有了它，最坏也只是退化成没有跨进程锁 —— 而 0.1.1 本来就没有锁。
#
# 这不是我们发明的取舍：上游 lease.ts 自己的注释写着
#   "The browser worker deployment stubs fs-ext to immediate success:
#    it is single-process, so the in-process write claim already excludes every writer."
# DSHA 的形态与那个部署一致 —— dsh web 是唯一的写者。
#
# 幂等；找不到目标就明确说「没命中」而不是假装成功（这个仓库的 patch 层
# 曾长期 fail-soft，结果补丁失配几个月没人发现）。
# ============================================================
set -u

MARK="DSHA_FLOCK_ENOSYS_PATCH_V1"
# 搜索根。默认容器里的 /root；测试用 DSHA_PATCH_ROOT 指到沙箱。
SEARCH_ROOT="${DSHA_PATCH_ROOT:-/root}"
found=0
patched=0
already=0
failed=0

# fs-ext 可能同时存在于 pnpm 的 .pnpm 扁平目录与各 profile 的 node_modules 下，
# 逐个都要打 —— 漏一个就是「某个 profile 起不来」这种最难查的现象。
for f in $(find "$SEARCH_ROOT" -maxdepth 9 -type f -name 'fs-ext.js' -path '*fs-ext*' 2>/dev/null); do
  found=$((found + 1))
  if grep -q "$MARK" "$f" 2>/dev/null; then
    already=$((already + 1))
    continue
  fi
  # 只认真正的 fs-ext 入口（导出 flock 的那个），避免误改同名文件
  grep -q '^exports\.flock' "$f" 2>/dev/null || { echo "SKIP 不像 fs-ext 入口: $f"; continue; }

  cp -f "$f" "$f.dsha-bak" 2>/dev/null

  cat >> "$f" <<'PATCH'

// ===== DSHA_FLOCK_ENOSYS_PATCH_V1 =====
// 落点不支持 flock 时视为「已持锁」。dsh 的 session lease 只把 EAGAIN 当竞争，
// 其它 errno 会一路抛到「打不开会话」，而 Android 的 FUSE 存储上 flock 是 ENOSYS。
// 单进程部署下进程内的写声明已经足够互斥（上游 browser worker 部署同样把 fs-ext
// stub 成立即成功）。真正的锁仍然生效在 App 私有目录（ext4）上 —— 那是默认落点。
(function () {
  var UNSUPPORTED = { ENOSYS: 1, EOPNOTSUPP: 1, ENOTSUP: 1, EINVAL: 1, EPERM: 1, EACCES: 1 };
  var origFlock = exports.flock;
  var origFlockSync = exports.flockSync;
  if (typeof origFlock === 'function') {
    exports.flock = function (fd, flags, callback) {
      if (typeof callback !== 'function') return origFlock(fd, flags, callback);
      return origFlock(fd, flags, function (err) {
        if (err && UNSUPPORTED[err.code]) {
          // 解锁请求同样当成功：调用方只在意「没抛错」
          return callback(null);
        }
        return callback(err);
      });
    };
  }
  if (typeof origFlockSync === 'function') {
    exports.flockSync = function (fd, flags) {
      try {
        return origFlockSync(fd, flags);
      } catch (err) {
        if (err && UNSUPPORTED[err.code]) return undefined;
        throw err;
      }
    };
  }
})();
PATCH

  # 语法自检：坏掉的 fs-ext 会让 dsh 连启动都做不到，宁可回退
  if node --check "$f" 2>/dev/null; then
    patched=$((patched + 1))
    echo "PATCHED $f"
  else
    cp -f "$f.dsha-bak" "$f" 2>/dev/null
    failed=$((failed + 1))
    echo "REVERTED 语法自检失败，已还原: $f"
  fi
done

echo "FLOCK_PATCH found=$found patched=$patched already=$already failed=$failed"
if [ "$found" -eq 0 ]; then
  # 0.1.1/0.1.2 没有 fs-ext（会话锁是 0.1.3 才有的）——
  # 这不是错误，但必须说出来，否则以后升级失配了也看不出区别。
  echo "FLOCK_PATCH_NOTARGET 这个 dsh 版本里没有 fs-ext，无需打补丁"
fi
