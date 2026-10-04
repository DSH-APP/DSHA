#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 会话自愈主程序（Python 实现，避开 proot/find/link2symlink 的 bash 坑）。

职责：
1. os.walk 遍历 /root/.dsh/sessions 下所有 session.jsonl.zstd（或 .jsonl 明文），
   不依赖 find/bash glob，符号链接目录也能穿透（os.walk followlinks=False 已够）。
2. 对每个文件调用“修复判定”：
   - zstd 缺失 → 记日志并跳过（由 heal-session.sh 负责装）
   - 缺 id/role/source/content 可安全补 → 补并写回（原文件备份 .corrupt-<ts>）
   - 结构无法安全修复（assistant 缺 model source / tool 缺 callId 或结果块）→ 隔离到 corrupt-backup
3. 全程写 /root/.dsh/heal.log（无条件创建），供排查。
"""
import datetime
import io
import json
import os
import shutil
import sys
import time

# 路径允许用环境变量覆盖：不然没法拿造出来的损坏会话验证「它到底修对了没有」，
# 只能在真实数据上试 —— 而这个脚本会改写用户的历史记录，拿真数据试是不可接受的
_DSH = os.environ.get("DSHA_DSH_HOME", "/root/.dsh")
SESSIONS_ROOT = os.path.join(_DSH, "sessions")
CORRUPT_ROOT = os.path.join(_DSH, "corrupt-backup")
LOG_PATH = os.path.join(_DSH, "heal.log")
# 会话文件名（精确匹配，不含任何备份/隔离产物）
SESSION_FILENAMES = ("session.jsonl", "session.jsonl.zstd")

try:
    import zstandard as zstd
    HAVE_ZSTD = True
except ImportError:
    HAVE_ZSTD = False

ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"


def zstd_load(raw, max_size=512 * 1024 * 1024):
    """流式全量解压（与 dump 命令同款）。dsh 的流式 zstd 帧头不带 content size，
    且崩溃后追加可产生多帧拼接；decompressobj 只解第一帧/半路崩会误导修复判定。
    这里唯一使用 stream_reader 累积读取：能读多少读多少，坏尾巴保留。
    """
    if raw[:4] != ZSTD_MAGIC:
        return raw  # 明文 JSONL
    # 注意：绝不能用 decompress() 短路——dsh 文件是多帧拼接，decompress 只解
    # 首帧（首帧带 content size 时会“成功”返回 1 行），必须 stream_reader 跨帧全读。
    out = io.BytesIO()
    try:
        r = zstd.ZstdDecompressor().stream_reader(io.BytesIO(raw), read_size=131072)
        while True:
            try:
                ch = r.read(262144)
            except Exception:
                break  # torn 尾部：保留已读内容
            if not ch:
                break
            out.write(ch)
            if out.tell() > max_size:
                break
    except Exception:
        pass
    return out.getvalue()

def scan_zstd_frames(buf):
    """结构扫描拼接的 zstd 帧（照 dsh 的 scanZstdFrames 算法，不解压 payload）。

    返回 (frames, torn_start)：frames=[(start, end)] 完整帧区间，
    torn_start=末尾不完整帧的起点（无则 None）。结构非法抛 ValueError。
    """
    frames = []
    off = 0
    n = len(buf)
    while off < n:
        start = off
        if n - off < 4:
            return frames, start
        if buf[off:off + 4] != ZSTD_MAGIC:
            raise ValueError("invalid frame magic at %d" % off)
        off += 4
        if off >= n:
            return frames, start
        desc = buf[off]
        off += 1
        if desc & 0x18:
            raise ValueError("reserved frame-header bit at %d" % (off - 1))
        csize_flag = desc >> 6
        single = bool(desc & 0x20)
        checksum = bool(desc & 0x04)
        dict_flag = desc & 0x03
        dict_bytes = 4 if dict_flag == 3 else dict_flag
        csize_bytes = (1 if single else 0) if csize_flag == 0 else (1 << csize_flag)
        rest = (0 if single else 1) + dict_bytes + csize_bytes
        if n - off < rest:
            return frames, start
        off += rest
        while True:
            if n - off < 3:
                return frames, start
            bh = int.from_bytes(buf[off:off + 3], "little")
            off += 3
            last = bh & 1
            btype = (bh >> 1) & 0x03
            bsize = bh >> 3
            if btype == 0x03:
                raise ValueError("reserved block type at %d" % (off - 3))
            payload = 1 if btype == 0x01 else bsize
            if n - off < payload:
                return frames, start
            off += payload
            if last:
                break
        if checksum:
            if n - off < 4:
                return frames, start
            off += 4
        frames.append((start, off))
    return frames, None


def header_frame_ok(raw, frames):
    """dsh 的 assertZstdHeaderFrame：第 1 帧解压后必须恰好一行（唯一 \\n 在末尾）。"""
    if not frames:
        return False
    s, e = frames[0]
    try:
        head = zstd.ZstdDecompressor().stream_reader(io.BytesIO(raw[s:e])).read()
    except Exception:
        return False
    return len(head) > 0 and head.find(b"\n") == len(head) - 1


def log(msg):
    try:
        with open(LOG_PATH, "a") as f:
            f.write("[%s] %s\n" % (datetime.datetime.now().strftime("%F %T"), msg))
    except Exception:
        pass
    print(msg)


def has_model_source(src):
    return (isinstance(src, dict)
            and isinstance(src.get("kind"), str) and src.get("kind") == "model"
            and isinstance(src.get("provider"), str) and len(src.get("provider", "")) > 0
            and isinstance(src.get("model"), str) and len(src.get("model", "")) > 0)


def tool_result_block_ok(msg, call_id):
    c = msg.get("content")
    if not isinstance(c, list) or len(c) != 1:
        return False
    b = c[0]
    return (isinstance(b, dict)
            and b.get("type") == "tool-result"
            and isinstance(b.get("content"), list)
            and b.get("toolCallId") == call_id)


def handle_event(ev, kept_len):
    t = ev.get("type", "")
    if t not in ("user/message", "assistant/message", "tool/result"):
        return True, 0
    d = ev.get("data")
    msg = d if t == "user/message" else (d.get("message") if isinstance(d, dict) else None)
    if not isinstance(msg, dict):
        return False, 0
    seq = ev.get("seq", kept_len)
    fixed = 0
    src = msg.get("source")
    if t == "assistant/message":
        if not has_model_source(src):
            return False, fixed
    elif t == "tool/result":
        if (not isinstance(src, dict) or src.get("kind") != "tool"
                or not isinstance(src.get("callId"), str) or not src.get("callId")):
            return False, fixed
        if not tool_result_block_ok(msg, src.get("callId")):
            return False, fixed
    else:
        if not (isinstance(src, dict) and isinstance(src.get("kind"), str) and src.get("kind")):
            msg["source"] = {"kind": "plugin", "plugin": "dsha-fixer"}
    mid = msg.get("id")
    if not isinstance(mid, str) or mid == "":
        msg["id"] = "dsha-fixed-" + str(seq)
        fixed += 1
    expected_role = "assistant" if t == "assistant/message" else "user"
    if msg.get("role") != expected_role:
        msg["role"] = expected_role
        fixed += 1
    if t == "user/message" and not isinstance(msg.get("content"), list):
        msg["content"] = []
        fixed += 1
    return True, fixed


def fix_file(path):
    """返回 (action, detail)：action ∈ fixed/isolate/no_fix/decode_fail/no_zstd"""
    sz = 0
    try:
        sz = os.path.getsize(path)
    except Exception:
        sz = 0
    if 0 < sz < 50:
        return "isolate", "极小文件 <%dB" % sz
    if not HAVE_ZSTD:
        return "no_zstd", "zstandard 未安装"
    try:
        raw = open(path, "rb").read()
        is_zstd = raw[:4] == ZSTD_MAGIC
        data = zstd_load(raw)
        if is_zstd and data == b"":
            return "decode_fail", "zstd 流式解码无内容"
    except Exception:
        return "decode_fail", "zstd 解码失败"
    try:
        text = data.decode("utf-8", errors="replace")
    except Exception:
        return "decode_fail", "文本解码失败"
    kept, fixed, need_isolate = [], 0, False
    diag_missing_id = 0
    diag_msg_events = 0
    diag_bad_lines = 0
    all_lines = text.split("\n")
    for _idx, line in enumerate(all_lines):
        s = line.strip()
        if not s:
            continue
        try:
            ev = json.loads(s)
        except Exception:
            # 旧实现这里直接 continue —— 非法行既不计数也不触发重写，于是最常见的
            # 损坏（写入中途被杀留下的半行）会被报成「无需修复」，而那行还在文件里。
            # dsh 解析到它就断了，后面的历史用户全看不见。
            #
            # 但要区分位置：**末尾**那一行的半截是正常现象（dsh 正在写、或上次
            # 被杀），dsh 按「已提交前缀」语义自己会容忍，重写它反而多事。
            # 中间出现非法行才是真损坏，必须丢掉并重写。
            if any(l.strip() for l in all_lines[_idx + 1:]):
                diag_bad_lines += 1
            continue
        t = ev.get("type", "")
        if t in ("user/message", "assistant/message", "tool/result"):
            diag_msg_events += 1
            d = ev.get("data")
            m = d if t == "user/message" else (d.get("message") if isinstance(d, dict) else None)
            if isinstance(m, dict) and (not isinstance(m.get("id"), str) or not m["id"]):
                diag_missing_id += 1
        ok, n = handle_event(ev, len(kept))
        if not ok:
            need_isolate = True
            break
        fixed += n
        kept.append(json.dumps(ev, ensure_ascii=False))
    if need_isolate:
        return "isolate", "存在无法安全修复的事件"
    # 是否需要「格式规范化重写」：只在文件真的不符合 dsh 期望的多帧布局时才重写。
    # 关键：绝不能因为「没找到可修问题」就重写健康会话 —— 旧实现每次启动都
    # 重写全部会话 + 留一份备份，磁盘和扫描量都会滚雪球。
    need_format_fix = False
    fmt_reason = ""
    if is_zstd:
        try:
            frames, torn = scan_zstd_frames(raw)
        except ValueError as e:
            return "isolate", "zstd 帧结构损坏（%s）" % e
        if len(frames) < 2 and len(kept) > 1:
            need_format_fix = True
            fmt_reason = "单帧多行（dsh 要求第 1 帧只含 header）"
        elif not header_frame_ok(raw, frames):
            need_format_fix = True
            fmt_reason = "首帧不是恰好一行 header"
        # 末尾撕裂帧（torn）不重写：dsh 自己会按「已提交前缀」语义容忍并续写
    if fixed == 0 and not need_format_fix and diag_bad_lines == 0:
        return "no_fix", "无需修复 (检=%d行 消息事件=%d 缺id=%d)" % (len(kept), diag_msg_events, diag_missing_id)
    if diag_bad_lines and not fmt_reason:
        fmt_reason = "%d 行无法解析（丢弃后重写，保住后面的历史）" % diag_bad_lines
    # 备份放到 sessions 目录之外（corrupt-backup 下）：留在原地会被下一轮
    # 扫描当成会话再修再备份 → 文件数指数膨胀。
    ts = time.strftime("%Y%m%d-%H%M%S")
    try:
        rel = os.path.relpath(path, SESSIONS_ROOT)
    except Exception:
        rel = os.path.basename(path)
    bak = os.path.join(CORRUPT_ROOT, rel + ".pre-fix-" + ts)
    try:
        os.makedirs(os.path.dirname(bak), exist_ok=True)
        os.rename(path, bak)
    except Exception:
        return "bake_fail", "备份失败"
    try:
        # dsh v0.1.1-rc.2+ 会话文件必须是多帧 zstd：第 1 帧 = 仅 header 行，
        # 后续帧 = 剩余事件行（assertZstdHeaderFrame 要求第一帧解压后恰好一行）。
        if is_zstd:
            # write_checksum 与 dsh 的 compressZstdFrame（ZSTD_c_checksumFlag=1）一致
            cctx = zstd.ZstdCompressor(write_checksum=True)
            out_frames = []
            # 帧 1: header（第一行）
            if kept and kept[0]:
                out_frames.append(cctx.compress((kept[0] + "\n").encode("utf-8")))
            # 帧 2: 剩余事件行
            if len(kept) > 1:
                rest = "\n".join(kept[1:]) + "\n"
                out_frames.append(cctx.compress(rest.encode("utf-8")))
            new_data = b"".join(out_frames)
        else:
            new_data = ("\n".join(kept) + "\n").encode("utf-8")
        # 原子写：已有 .bak 回滚兜底，但中途被杀仍会留下截断的会话文件，
        # 那种文件 dsh 读起来是「历史莫名少了一半」，比报错更难查
        tmp_s = path + ".dsha-tmp"
        with open(tmp_s, "wb") as f:
            f.write(new_data)
            f.flush()
            os.fsync(f.fileno())
        os.replace(tmp_s, path)
    except Exception:
        try:
            os.rename(bak, path)
        except Exception:
            pass
        return "write_fail", "写回失败（已还原）"
    what = []
    if fixed:
        what.append("补 %d 处" % fixed)
    if need_format_fix:
        what.append("规范化帧格式(%s)" % fmt_reason)
    return "fixed", "%s（备份 %s）" % ("、".join(what) or "重写", os.path.basename(bak))


def isolate_file(path, reason):
    try:
        rel = os.path.relpath(path, SESSIONS_ROOT)
        dst = os.path.join(CORRUPT_ROOT, rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.move(path, dst)
        log("已隔离 (%s): %s" % (reason, rel))
        return True
    except Exception as e:
        log("隔离失败(%s): %s err=%s" % (reason, path, e))
        return False


def migrate_legacy_backups():
    """把老版本留在 sessions 目录里的备份挪走（升级兼容）。

    旧版 heal 会把修复前的副本写成 session.jsonl.zstd.corrupt-<ts> 就地存放，
    新版按精确文件名扫描已经不认它们 —— 但留在原地白占空间，用户看目录也容易
    误会「会话变多了」。一次性搬到 corrupt-backup/legacy/，幂等。
    """
    if not os.path.isdir(SESSIONS_ROOT):
        return 0
    moved = 0
    for root, dirs, files in os.walk(SESSIONS_ROOT):
        if root == CORRUPT_ROOT or root.startswith(CORRUPT_ROOT + os.sep):
            dirs[:] = []
            continue
        for fn in files:
            if fn in SESSION_FILENAMES or not fn.startswith("session.jsonl"):
                continue
            src = os.path.join(root, fn)
            try:
                rel = os.path.relpath(src, SESSIONS_ROOT)
                dst = os.path.join(CORRUPT_ROOT, "legacy", rel)
                os.makedirs(os.path.dirname(dst), exist_ok=True)
                shutil.move(src, dst)
                moved += 1
            except Exception as e:
                log("老备份搬运失败: %s (%s)" % (src, e))
    if moved:
        log("升级兼容：把 %d 个老版本遗留的会话备份挪到 corrupt-backup/legacy" % moved)
    return moved


def main():
    try:
        os.makedirs(LOG_PATH.rpartition("/")[0], exist_ok=True)
    except Exception:
        pass
    log("== 会话自愈开始 zstd=%s paths=%s ==" % (HAVE_ZSTD, SESSIONS_ROOT))
    migrated = migrate_legacy_backups()
    scanned = fixed = isolated = 0
    if os.path.isdir(SESSIONS_ROOT):
        for root, dirs, files in os.walk(SESSIONS_ROOT):
            # 跳过隔离区（万一它被配置到 sessions 内部）
            if root == CORRUPT_ROOT or root.startswith(CORRUPT_ROOT + os.sep):
                dirs[:] = []
                continue
            for fn in files:
                # 精确匹配会话文件名：旧实现用 startswith("session.jsonl")，
                # 会把自己产生的备份（session.jsonl.zstd.corrupt-*）当会话反复
                # 重修再备份 → 每次启动文件数翻倍。
                if fn in SESSION_FILENAMES:
                    p = os.path.join(root, fn)
                    scanned += 1
                    try:
                        action, detail = fix_file(p)
                    except Exception as e:
                        action, detail = "error", repr(e)
                    if action == "fixed":
                        fixed += 1
                        log("已修复会话(%s): %s" % (detail, p))
                    elif action == "isolate":
                        if isolate_file(p, detail):
                            isolated += 1
                    elif action in ("no_fix",):
                        log("无需修复，保留: %s (%s)" % (p, detail))
                    elif action == "no_zstd":
                        log("跳过(缺 zstandard): %s" % p)
                    else:
                        log("异常(%s): %s %s" % (action, detail, p))
    log("== 会话自愈结束 scanned=%d fixed=%d isolated=%d migrated=%d =="
        % (scanned, fixed, isolated, migrated))
    if fixed > 0 or isolated > 0:
        print("SESSION_HEALED (scanned=%d fixed=%d isolated=%d)" % (scanned, fixed, isolated))
    elif scanned == 0:
        print("SESSION_OK")
    else:
        print("SESSION_HEALED_NONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())