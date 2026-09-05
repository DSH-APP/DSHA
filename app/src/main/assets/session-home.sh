#!/bin/bash
# ============================================================
# 会话数据落点管理：sessions 必须是 **App 私有目录里的实体目录**，
# 公开目录只保留一份镜像用于卸载重装后恢复。
#
# 为什么不能再软链到公开目录（实测判据，2026-09-05，真机 V2352A）：
#   dsh 0.1.3 起给每个会话加了跨进程写锁 —— fs-ext 对 <session目录>/session.lock
#   做非阻塞 flock(2)。而它的 lease.ts 只把 EAGAIN/EWOULDBLOCK 当「别人持锁」，
#   其它 errno 一律往上抛。逐个落点实测：
#     /root/…（App 私有，ext4）        → flock 成功、互斥生效、inode 一致
#     /tmp（ext4）                     → 成功
#     /sdcard/Documents/dshdata（FUSE）→ **ENOSYS**
#     /sdcard/Download/DSHA（FUSE）    → **ENOSYS**
#   也就是说：sessions 留在公开目录，0.1.3 一打开会话就抛错，dsh 直接不可用。
#   （proot/proroot 本身不妨碍 flock —— 私有目录那两行就是证据。）
#
# 于是分工改成：
#   · sessions           → 私有实体（锁真的生效，且 ext4 读写比 FUSE 快）
#   · storages/attachments/settings.yaml → 仍然软链到公开目录（它们没有锁文件）
#   · 公开侧 sessions    → 镜像副本，由 sync 增量更新，卸载重装时靠它恢复
#
# 用法：
#   session-home.sh ensure   保证 sessions 是私有实体（必要时搬回/拷回）+ 体检其余三项的软链，幂等
#   session-home.sh sync     把私有 sessions 增量同步到公开镜像
#   session-home.sh links    只做软链体检（storages / attachments / settings.yaml）
#   session-home.sh status   报告当前状态（给自检与 UI 用）
#
# 环境变量（测试用，默认值是真机路径）：
#   DSH_HOME_DIR  默认 /root/.dsh
#   DSH_PUB_DIR   默认 /sdcard/Documents/dshdata
# ============================================================
set -u

HOME_DIR="${DSH_HOME_DIR:-/root/.dsh}"
PUB="${DSH_PUB_DIR:-/sdcard/Documents/dshdata}"
SRC="$HOME_DIR/sessions"
MIRROR="$PUB/sessions"

# 锁文件是本机运行态，不进镜像：它描述的是「哪个进程正在写」，
# 跟着备份/镜像跑到另一台机器上毫无意义（同 .bridge_token 那类判据）。
EXCLUDE_NAMES="session.lock"

nonempty() {
  [ -e "$1" ] || return 1
  if [ -d "$1" ]; then
    [ -n "$(ls -A "$1" 2>/dev/null)" ] && return 0 || return 1
  fi
  [ -s "$1" ] && return 0
  return 1
}

pub_writable() {
  [ -d "$PUB" ] || mkdir -p "$PUB" 2>/dev/null || return 1
  [ -w "$PUB" ] || return 1
  return 0
}

# ---------- ensure ----------
# 五种局面，按优先级判断。任何一步失败都保留现有数据，绝不静默删。
ensure() {
  # ① 已经是实体目录 → 就位，幂等返回
  if [ -d "$SRC" ] && [ ! -L "$SRC" ]; then
    echo "SESSIONS_OK 私有实体目录"
    return 0
  fi

  # ② 现状升级路径：私有是软链（指向公开目录）→ 把内容搬回私有
  #    先拷到临时目录再原子改名 —— 中途失败时软链仍在，数据一份都不少。
  if [ -L "$SRC" ]; then
    target=$(readlink -f "$SRC" 2>/dev/null)
    tmp="$HOME_DIR/.sessions.migrating.$$"
    rm -rf "$tmp"
    if [ -n "$target" ] && [ -d "$target" ]; then
      mkdir -p "$tmp" || { echo "SESSIONS_FAIL 无法建临时目录"; return 1; }
      # -a 保留时间戳；软链指向的内容本身可能还有软链，用 -L 解引用成实体
      if ! cp -aL "$target/." "$tmp/" 2>/dev/null; then
        # 公开目录里可能有 FUSE 不支持的条目（如链接），逐项尽力拷
        cp -a "$target/." "$tmp/" 2>/dev/null || true
      fi
      rm -f "$tmp/$EXCLUDE_NAMES" 2>/dev/null
      rm -f "$SRC" 2>/dev/null || { echo "SESSIONS_FAIL 无法移除旧软链"; rm -rf "$tmp"; return 1; }
      if mv "$tmp" "$SRC" 2>/dev/null; then
        echo "SESSIONS_MIGRATED 已从公开目录搬回私有（镜像保留在 $MIRROR）"
        return 0
      fi
      # 改名失败：把软链恢复回去，数据仍在公开目录
      ln -s "$target" "$SRC" 2>/dev/null
      rm -rf "$tmp"
      echo "SESSIONS_FAIL 搬回失败，已还原软链"
      return 1
    fi
    # 悬空软链 → 直接换成空实体目录
    rm -f "$SRC" 2>/dev/null
    mkdir -p "$SRC" 2>/dev/null && echo "SESSIONS_NEW 旧软链是悬空的，已建空目录" && return 0
    echo "SESSIONS_FAIL 悬空软链无法替换"
    return 1
  fi

  # ③ 私有不存在 + 公开镜像有数据 → 卸载重装场景，拷回私有
  if [ ! -e "$SRC" ] && nonempty "$MIRROR"; then
    tmp="$HOME_DIR/.sessions.restoring.$$"
    rm -rf "$tmp"
    mkdir -p "$tmp" || { echo "SESSIONS_FAIL 无法建临时目录"; return 1; }
    cp -aL "$MIRROR/." "$tmp/" 2>/dev/null || cp -a "$MIRROR/." "$tmp/" 2>/dev/null || true
    rm -f "$tmp/$EXCLUDE_NAMES" 2>/dev/null
    if nonempty "$tmp" && mv "$tmp" "$SRC" 2>/dev/null; then
      echo "SESSIONS_RESTORED 已从公开镜像恢复到私有目录"
      return 0
    fi
    rm -rf "$tmp"
    echo "SESSIONS_FAIL 从镜像恢复失败"
    return 1
  fi

  # ④ 私有是个普通文件（异常）→ 挪开，别让它挡住目录
  if [ -e "$SRC" ] && [ ! -d "$SRC" ]; then
    mv "$SRC" "$SRC.notadir.$(date +%s 2>/dev/null || echo bak)" 2>/dev/null
  fi

  # ⑤ 首次：建空实体目录
  mkdir -p "$SRC" 2>/dev/null && echo "SESSIONS_NEW 已建私有目录" && return 0
  echo "SESSIONS_FAIL 无法创建 $SRC"
  return 1
}

# ---------- sync ----------
# 私有 → 公开，只增量更新，**刻意不删**公开侧多出来的东西：
# 删除是不可逆的，而「重装后多回来几个已删会话」远比「同步逻辑误删」轻。
sync_out() {
  [ -d "$SRC" ] && [ ! -L "$SRC" ] || { echo "SYNC_SKIP sessions 不是私有实体目录"; return 0; }
  pub_writable || { echo "SYNC_SKIP 公开目录不可写（$PUB）"; return 0; }
  mkdir -p "$MIRROR" 2>/dev/null || { echo "SYNC_SKIP 无法创建镜像目录"; return 0; }

  n=0
  # -u 只在源更新时覆盖；会话日志是 append-only，mtime 变了就整份重传（几百 KB 级，可接受）
  # 逐项拷而不是 cp -a 整目录：整目录一失败就全丢，逐项能报出「哪几个没同步」
  for entry in "$SRC"/*; do
    [ -e "$entry" ] || continue
    base=$(basename "$entry")
    [ "$base" = "$EXCLUDE_NAMES" ] && continue
    if cp -aLu "$entry" "$MIRROR/" 2>/dev/null || cp -aL "$entry" "$MIRROR/" 2>/dev/null; then
      n=$((n + 1))
    else
      echo "SYNC_PARTIAL 未同步: $base"
    fi
  done
  # 镜像里的锁文件是历史遗留，清掉
  find "$MIRROR" -name "$EXCLUDE_NAMES" -delete 2>/dev/null
  echo "SYNC_OK 已同步 $n 项到 $MIRROR"
}

# ---------- links ----------
# 其余三项（storages / attachments / settings.yaml）仍然软链到公开目录，
# 但**悬空的软链会让 dsh 直接起不来**：storage 后端对它 mkdir(recursive) 得到的是
# ENOENT（对悬空软链 mkdir 就是这个错），插件树加载失败、启动中断。
# 真实触发：恢复备份带回一条指向公开目录的软链，而这台机器访问不到那个目标 ——
# 换设备、删了 Documents、存储权限被撤、内测包用的是另一个公开目录名。
#
# 这段刻意与 migrate-public-data.sh 重复：那个脚本受 patch 时间戳门槛控制，
# 启动路径上可能整批跳过；而这里是无条件跑的。同一个故障有两道防线不算冗余，
# 因为它的后果是「dsh 完全起不来」。
LINK_ITEMS="storages attachments settings.yaml"

heal_links() {
  healed=0
  for name in $LINK_ITEMS; do
    p="$HOME_DIR/$name"
    [ -L "$p" ] || continue          # 只管软链；实体目录与不存在都交给 dsh 自己
    [ -e "$p" ] && continue          # 有效
    target="$PUB/$name"
    if nonempty "$target"; then
      ln -sf "$target" "$p" 2>/dev/null \
        && { echo "LINK_REPAIRED $name 重新指向公开副本"; healed=$((healed + 1)); }
      continue
    fi
    # 目标不可达：删掉悬空软链，dsh 启动时会自己建实体目录。
    # 留着它 = 永远起不来；删掉最坏只是这一项从空开始。
    rm -f "$p" 2>/dev/null \
      && { echo "LINK_CLEARED $name 悬空软链已清除（目标不可达，dsh 会自建）"; healed=$((healed + 1)); }
  done
  echo "LINKS_OK 处理 $healed 项"
}

# ---------- status ----------
status() {
  if [ -L "$SRC" ]; then
    echo "kind=symlink target=$(readlink "$SRC" 2>/dev/null)"
  elif [ -d "$SRC" ]; then
    echo "kind=dir count=$(ls -A "$SRC" 2>/dev/null | grep -cv "^$EXCLUDE_NAMES\$")"
  elif [ -e "$SRC" ]; then
    echo "kind=file"
  else
    echo "kind=missing"
  fi
  if [ -d "$MIRROR" ]; then
    echo "mirror=dir count=$(ls -A "$MIRROR" 2>/dev/null | wc -l)"
  else
    echo "mirror=missing"
  fi
  pub_writable && echo "pub=writable" || echo "pub=readonly"
}

case "${1:-ensure}" in
  ensure) ensure; heal_links ;;
  sync)   sync_out ;;
  links)  heal_links ;;
  status) status ;;
  *) echo "用法: session-home.sh ensure|sync|links|status"; exit 2 ;;
esac
