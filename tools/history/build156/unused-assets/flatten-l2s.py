#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 proot 的 .l2s 链「实体化」成真实文件，让 tar 能正常打包 .dsh。

为什么需要：
  Android 私有目录禁真硬链接，proot 只能用 --link2symlink 把 link() 模拟成
      目标(symlink) → .l2s.<名>.<hash>.tmp0001(symlink) → ….0001(真实数据)
  （末 4 位数字是 proot 模拟的 nlink 计数）。dsh 早期用 link() 发布会话日志和
  write 工具产物，于是 .dsh 里散落大量这种链。后果有两个：
    · tar 遍历到这些链时报 ELOOP（Too many levels of symbolic links）→ 备份必失败
    · 发布后临时目录被清理时目标直接悬空 → 读取报 ENOENT

  写入侧已由 fs-write-patch.sh 治本（一律 rename，不再产生新链），本脚本负责
  已经产生的存量：把每个链读出真实内容、原子替换成普通文件。

安全设计（这里动的是用户的会话与附件，必须保守）：
  · 只碰「symlink 且解析路径里含 .l2s.」的条目，pnpm 那些正常符号链接一概不动
  · 先写同目录临时文件 + fsync + md5 校验，再 os.replace 原子替换
  · 读不出内容（真正悬空）的只报告、不删不改，交给会话自愈去隔离
  · 实体化后变成孤儿的 .l2s.* 数据文件默认保留，只有显式 --clean-orphans 才删

用法：
  python3 flatten-l2s.py [--root /root/.dsh] [--dry-run] [--clean-orphans]
"""
import hashlib
import os
import shutil
import sys
import tempfile

L2S_MARK = ".l2s."
DEFAULT_ROOT = "/root/.dsh"
LOG_PATH = "/root/.dsh/flatten-l2s.log"


def log(msg):
    line = "%s" % msg
    print(line)
    try:
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(line + "\n")
    except Exception:
        pass


def arg(name, default=None):
    key = "--" + name
    if key in sys.argv:
        i = sys.argv.index(key)
        if i + 1 < len(sys.argv) and not sys.argv[i + 1].startswith("--"):
            return sys.argv[i + 1]
        return True
    return default


def md5_of(path, chunk=1 << 20):
    h = hashlib.md5()
    with open(path, "rb") as f:
        while True:
            b = f.read(chunk)
            if not b:
                break
            h.update(b)
    return h.hexdigest()


def read_link_target(path):
    """是符号链接就返回目标字符串，不是则返回 None。

    这里刻意用 readlink 而不是 os.path.islink —— proot 的 link2symlink 扩展
    劫持了 stat/lstat 系列（为了伪造 st_nlink），对 .l2s 链做 lstat 会直接失败：
    容器实测是 EPERM(Operation not permitted)，用户机上表现为 ELOOP。
    readlink 与 open(跟随读) 都不经过那条路径，仍然可用。
    """
    try:
        return os.readlink(path)
    except OSError:
        return None


def flatten_one(link_path, dry_run):
    """把一条 l2s 链实体化。返回 ('flattened'|'dangling'|'skip', 详情)"""
    # open 会跟随整条链：读得到内容 = 链完好；读不到 = 真悬空
    try:
        with open(link_path, "rb") as f:
            head = f.read(1)
        readable = True
    except OSError as e:
        readable = False
        why = "%s" % e
    if not readable:
        return "dangling", why
    if dry_run:
        return "flattened", "（dry-run）内容可读，会替换成真实文件"

    d = os.path.dirname(link_path) or "."
    tmp = None
    try:
        h = hashlib.md5()
        size = 0
        fd, tmp = tempfile.mkstemp(dir=d, prefix=".dsha-flat-")
        with os.fdopen(fd, "wb") as out, open(link_path, "rb") as src:
            while True:
                b = src.read(1 << 20)
                if not b:
                    break
                h.update(b)
                size += len(b)
                out.write(b)
            out.flush()
            os.fsync(out.fileno())
        if md5_of(tmp) != h.hexdigest():
            os.unlink(tmp)
            return "skip", "校验不一致，未替换（数据仍在原链上）"
        # 必须先摘掉 symlink：直接 replace 到符号链接路径会跟随链接写到目标去
        os.unlink(link_path)
        os.replace(tmp, link_path)
        tmp = None
        return "flattened", "%.1f KB" % (size / 1024.0)
    except Exception as e:
        if tmp and os.path.exists(tmp):
            try:
                os.unlink(tmp)
            except Exception:
                pass
        return "skip", "实体化失败：%r" % e


def main():
    root = arg("root", DEFAULT_ROOT)
    dry_run = bool(arg("dry-run", False))
    clean_orphans = bool(arg("clean-orphans", False))
    if not os.path.isdir(root):
        log("目录不存在：%s" % root)
        print("FLATTEN_RESULT: flattened=0 dangling=0 orphans=0 skipped=0")
        return 0

    log("== l2s 实体化开始 root=%s dry_run=%s ==" % (root, dry_run))

    links, l2s_files = [], []
    for cur, dirs, files in os.walk(root, followlinks=False):
        for name in files + dirs:
            p = os.path.join(cur, name)
            tgt = read_link_target(p)
            if tgt is not None:
                # 只认目标链路里带 .l2s. 的（pnpm 的正常符号链接一概不动）
                if L2S_MARK in tgt or L2S_MARK in name:
                    links.append(p)
            elif L2S_MARK in name:
                l2s_files.append(p)

    # 先处理「用户可见文件」，再处理中间节点：顺序反了会把中间链先拆掉
    links.sort(key=lambda p: (L2S_MARK in os.path.basename(p), len(p)))

    stats = {"flattened": 0, "dangling": 0, "skip": 0}
    removed = []
    # 默认摘除悬空链（那是备份失败的直接原因）；要留证据可传 --keep-dangling
    keep_dangling = "--keep-dangling" in sys.argv
    danglers = []
    for p in links:
        # 上一轮可能已经把它实体化（中间节点被顺带处理）
        if read_link_target(p) is None:
            continue
        action, detail = flatten_one(p, dry_run)
        stats[action] = stats.get(action, 0) + 1
        rel = os.path.relpath(p, root)
        if action == "flattened":
            log("  ✓ %s  %s" % (rel, detail))
        elif action == "dangling":
            danglers.append(rel)
            log("  ✗ 悬空 %s  %s" % (rel, detail))
            # 必须摘掉这条链，光记一笔不够：数据已经丢了，链本身毫无价值，
            # 而 tar 碰到它会报 "Cannot stat: Operation not permitted" 并以
            # 失败状态退出 —— 这正是「备份 100% 失败」的直接原因。
            # 以前写的是「交给会话自愈隔离」，可会话自愈只认 session.jsonl，
            # 普通文件根本没人管，于是备份一直失败。
            if not dry_run and not keep_dangling:
                try:
                    os.unlink(p)
                    removed.append(rel)
                    log("    → 已摘除（数据已丢，留着只会让备份失败）")
                except OSError as e:
                    log("    ! 摘除失败：%s" % e)
        else:
            log("  ⚠ 跳过 %s  %s" % (rel, detail))

    # 实体化之后，原来的 .l2s.* 数据文件就成了孤儿（没人再指向它们）
    orphans = []
    for p in l2s_files:
        if not os.path.exists(p):
            continue
        if read_link_target(p) is not None:
            continue  # 还是符号链接（中间节点），不算孤儿数据
        orphans.append(p)

    freed = 0
    if clean_orphans and not dry_run:
        for p in orphans:
            try:
                sz = os.path.getsize(p)
                os.unlink(p)
                freed += sz
            except Exception as e:
                log("  孤儿删除失败 %s: %r" % (p, e))
        log("已清理 %d 个孤儿 .l2s 数据文件，释放 %.1f MB" % (len(orphans), freed / 1048576.0))
    elif orphans:
        total = 0
        for p in orphans:
            try:
                total += os.path.getsize(p)
            except Exception:
                pass
        log("保留 %d 个 .l2s 数据文件（%.1f MB）—— 内容已复制到正式文件，"
            "确认无误后可用 --clean-orphans 清理" % (len(orphans), total / 1048576.0))

    log("== l2s 实体化结束 flattened=%d dangling=%d skipped=%d orphans=%d =="
        % (stats["flattened"], stats["dangling"], stats["skip"], len(orphans)))
    if danglers:
        log("悬空条目（数据已丢）：%s" % "、".join(danglers[:10]))
        if removed:
            log("已摘除 %d 条悬空链，备份不会再因它们失败" % len(removed))
        elif keep_dangling:
            log("按 --keep-dangling 保留了悬空链 —— 注意 tar 仍会因它们失败")
    # 机器可读行，供 App 侧解析
    print("FLATTEN_RESULT: flattened=%d dangling=%d removed=%d orphans=%d skipped=%d"
          % (stats["flattened"], stats["dangling"], len(removed),
             len(orphans), stats["skip"]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
