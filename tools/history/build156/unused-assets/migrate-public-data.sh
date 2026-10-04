#!/system/bin/sh
# DSH_HOME 里的热数据迁移到公开目录（/sdcard/Documents/dshdata），原位留私有符号
# 链接指向公开副本。会话/设置/附件在文件管理器里可见、可备份、卸载重装不丢
# （前提是用户保留 Documents 目录）。
#
# 安全约束（来自 deepcode-lab 踩过的坑，照搬规避）：
#   · DSH_HOME 本身必须留私有 —— profiles/web/node_modules 是 dsh 自己维护的
#     符号链接，公开 FUSE 禁止符号链接，整目录搬出去会炸。
#   · .credentials.yaml 绝不迁移 —— 公开 FUSE 强制 660，dsh 的密钥权限检查会拒绝，
#     且密钥会暴露给其他 App。
#   · 只迁移「纯文件目录 / 单文件配置」，符号链接一律跳过。
#
# 覆盖全周期（迁移 / 重装 / 恢复），按优先级判断：
#   ① 源已是软链              → 跳过（已迁移，幂等）
#   ② 源不存在 + 公开侧在      → 建软链接回（卸载重装：rootfs 被删但 Documents 还在）
#   ③ 源是实体且非空 + 公开侧也非空 → **冲突**：源更新（刚恢复的备份），
#      把公开侧改名成 .conflict-<时间> 保留，再迁移源。绝不静默删源。
#   ④ 公开侧非空 + 源为空/不存在 → 复用公开副本
#   ⑤ 首次迁移                 → cp -a 到公开侧 → 校验非空 → 删源、建链
#
# ③ 是关键：恢复备份时 restore-merge.py 会把整个 .dsh 换成实体目录（软链丢失），
# 此时若无脑「复用公开副本」，会把刚恢复的数据 rm -rf 掉，换成旧的公开副本 ——
# 那是真实的数据丢失。宁可留一份 .conflict 让用户自己选。

set -u
PUB="/sdcard/Documents/dshdata"
HOME_DIR="/root/.dsh"

ITEMS="sessions storages attachments settings.yaml"

mkdir -p "$PUB" 2>/dev/null || { echo "MIG: 无法创建公开目录 $PUB"; exit 0; }

# /sdcard/Documents 必须可写，否则 bind 没生效、/sdcard 指向 App 私有目录，
# 迁过去的数据会落在错误位置 —— 不如保留私有副本。
[ -w "/sdcard/Documents" ] || { echo "MIG: /sdcard/Documents 不可写，跳过迁移"; exit 0; }

nonempty() {
  [ -e "$1" ] || return 1
  [ -d "$1" ] && { [ -n "$(ls -A "$1" 2>/dev/null)" ] && return 0 || return 1; }
  [ -s "$1" ] && return 0
  return 1
}

migrate_one() {
  name="$1"
  src="$HOME_DIR/$name"
  dst="$PUB/$name"

  # ① 已是软链 → 幂等跳过
  [ -L "$src" ] && return 0

  # ② 源不存在 + 公开侧有数据 → 建链接回（重装场景）
  if [ ! -e "$src" ] && nonempty "$dst"; then
    ln -s "$dst" "$src" 2>/dev/null && echo "MIG: $name 接回公开副本（重装恢复）"
    return 0
  fi

  # ③ 源实体非空 且 公开侧也非空 → 冲突，保留公开侧旧数据后用源覆盖
  if nonempty "$src" && nonempty "$dst"; then
    ts=$(date +%Y%m%d-%H%M%S 2>/dev/null || echo conflict)
    mv "$dst" "$dst.conflict-$ts" 2>/dev/null || {
      echo "MIG: $name 冲突且无法改名公开副本，保留私有副本不迁移"
      return 0
    }
    cp -a "$src" "$dst" 2>/dev/null
    if nonempty "$dst"; then
      rm -rf "$src" 2>/dev/null
      ln -s "$dst" "$src" 2>/dev/null
      echo "MIG: $name 用私有数据覆盖（旧公开副本存为 $name.conflict-$ts）"
    else
      # 复制失败 → 把公开侧改回来，源不动
      mv "$dst.conflict-$ts" "$dst" 2>/dev/null
      echo "MIG: $name 覆盖失败，已还原公开副本，保留私有副本"
    fi
    return 0
  fi

  # ④ 公开侧非空、源为空或不存在 → 复用公开副本
  if nonempty "$dst"; then
    rm -rf "$src" 2>/dev/null
    ln -s "$dst" "$src" 2>/dev/null && echo "MIG: $name 复用公开副本"
    return 0
  fi

  # ⑤ 首次迁移
  [ -e "$src" ] || return 0
  cp -a "$src" "$dst" 2>/dev/null
  if ! nonempty "$dst"; then
    echo "MIG: $name 复制失败或为空，保留私有副本"
    return 0
  fi
  rm -rf "$src" 2>/dev/null
  ln -s "$dst" "$src" 2>/dev/null && echo "MIG: $name 已迁移到公开目录"
}

for it in $ITEMS; do
  migrate_one "$it"
done

echo "MIG_DONE"
