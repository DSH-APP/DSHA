#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""DSHA 备份恢复整理（在 rootfs 内运行，宽容优先）。

设计原则：**能恢复多少就恢复多少，绝不因为一处不认识就整包失败。**
兼容以下差异（都实测过或按用户报告覆盖）：
  · 包内布局不同：.dsh / root/.dsh / 任意层级/.dsh / stage 本身就是 .dsh 内容
  · 工作目录名不同：备份里 old-wd/.env → 落到本机当前 workdir/.env
  · 跨设备的本机路径插件：link:/root/plugin-src/x 在新机不存在 →
    用备份内联的源码（.dsha-plugin-src）落地并重写路径；找不到则把该 bundle
    摘掉（宁可少一个插件，也要让 dsh web 能启动），并在报告里列出
  · 老备份（无 manifest）：走启发式推断，不报错

用法：
  python3 restore-merge.py --stage /root/.dsha-restore-stage \
      --root /root --workdir deepseek-harness
输出：人话报告；最后一行 RESTORE_OK / RESTORE_PARTIAL / RESTORE_EMPTY。
全量 .dsh 原子提交后额外输出 RESTORE_DSH_COMMITTED，供宿主决定是否可以
轮换本机凭据；校验/rename 失败时绝不输出该标记。
"""
import json
import hashlib
import os
import shutil
import sys
import time
import re

TS = time.strftime("%Y%m%d-%H%M%S")
GLOBAL_NM = (
    "/usr/local/lib/node_modules/@deepseek-ai/dsh/node_modules",
    "/usr/local/lib/node_modules",
)
PLUGIN_SRC_DIRNAME = "plugin-src"
INLINE_DIRNAME = ".dsha-plugin-src"
# 隔离候选属于用户迁移资料，放在 .dsh 隐藏子目录才能随环境重建一起保留；
# dsh 不会读取该名称，插件管理器也不会把它当作 profile/依赖目录。
PLUGIN_MIGRATION_DIRNAME = os.path.join(".dsh", ".dsha-plugin-migration")
report = []
partial = False
# 全量恢复失败时，stage 是唯一可供排查/重试的副本，绝不能在 main() 末尾清掉。
retain_stage = False
restore_committed = False


def say(msg):
    report.append(msg)


def _tree_digest(path):
    """返回源码树摘要，不跟随链接；用于冲突去重和迁移候选身份。"""
    value = hashlib.sha256()

    def visit(current, relative):
        info = os.lstat(current)
        if os.path.islink(current):
            row = {"type": "link", "target": os.readlink(current)}
        elif os.path.isdir(current):
            row = {"type": "directory"}
        elif os.path.isfile(current):
            row = {"type": "file", "sha256": _file_digest(current), "mode": info.st_mode & 0o777}
        else:
            raise ValueError("插件源码含不支持的特殊文件：%s" % relative)
        value.update(json.dumps([relative, row], sort_keys=True, ensure_ascii=True).encode("utf-8"))
        if row["type"] == "directory":
            for name in sorted(os.listdir(current)):
                child = os.path.join(current, name)
                visit(child, relative + "/" + name if relative else name)

    def _file_digest(file):
        digest = hashlib.sha256()
        with open(file, "rb") as stream:
            for chunk in iter(lambda: stream.read(65536), b""):
                digest.update(chunk)
        return digest.hexdigest()

    visit(path, "")
    return value.hexdigest()


def _copy_tree_atomic(source, destination):
    """复制候选后再落位；目标已存在时绝不覆盖。"""
    parent = os.path.dirname(destination)
    os.makedirs(parent, exist_ok=True)
    temporary = destination + ".dsha-stage-%s-%s" % (TS, os.getpid())
    suffix = 0
    while os.path.lexists(temporary):
        suffix += 1
        temporary = destination + ".dsha-stage-%s-%s-%d" % (TS, os.getpid(), suffix)
    try:
        shutil.copytree(source, temporary, symlinks=True)
        if os.path.lexists(destination):
            raise FileExistsError(destination)
        os.rename(temporary, destination)
    except BaseException:
        try:
            if os.path.lexists(temporary):
                if os.path.isdir(temporary) and not os.path.islink(temporary):
                    shutil.rmtree(temporary)
                else:
                    os.unlink(temporary)
        except Exception:
            pass
        raise


def _legacy_preset_bundle(source, destination, preset_id):
    """Generate a disabled rc1 bundle candidate; never install or select it."""
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,63}", preset_id):
        return None
    agent = os.path.join(source, "agent.cordis.yml")
    if not os.path.isfile(agent) or os.path.getsize(agent) > 512 * 1024:
        return None
    try:
        with open(agent, encoding="utf-8") as stream:
            plugin_yaml = stream.read()
    except OSError:
        return None
    if not plugin_yaml.strip():
        return None
    meta = {}
    preset = os.path.join(source, "preset.yml")
    if os.path.isfile(preset) and os.path.getsize(preset) <= 64 * 1024:
        with open(preset, encoding="utf-8") as stream:
            for line in stream:
                match = re.match(r"^\s*(name|description|order)\s*:\s*(.*?)\s*$", line)
                if match:
                    value = match.group(2).strip()
                    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"": value = value[1:-1]
                    meta[match.group(1)] = value.replace("''", "'")
    rows = ["        id: " + preset_id]
    for key in ("name", "description"):
        if meta.get(key): rows.append("        %s: '%s'" % (key, meta[key].replace("'", "''")))
    if meta.get("order", "").lstrip("-").isdigit(): rows.append("        order: " + meta["order"])
    rows.append("        plugins:")
    rows.extend("          " + line if line else "" for line in plugin_yaml.splitlines())
    bundle = os.path.join(destination, "bundle")
    os.makedirs(bundle, exist_ok=True)
    with open(os.path.join(bundle, "package.json"), "w", encoding="utf-8") as out:
        json.dump({"name": "dsha-legacy-preset-" + preset_id,
                   "version": "0.0.0-dsha-migration", "private": True, "type": "module",
                   "dsh": {"bundle": {"patch": "./cordis.patch.yml"}},
                   "dependencies": {"@deepseek-ai/dsh-agent-preset": "0.1.7-rc.2"}},
                  out, ensure_ascii=False, indent=2); out.write("\n")
    with open(os.path.join(bundle, "cordis.patch.yml"), "w", encoding="utf-8") as out:
        out.write("- insert:\n    - id: preset-%s\n      name: '@deepseek-ai/dsh-agent-preset'\n      config:\n%s\n" % (preset_id, "\n".join(rows)))
    return os.path.relpath(bundle, destination).replace(os.sep, "/")


def _write_migration_record(root, kind, payload):
    """把待审阅迁移对象写在 .dsh 隐藏子目录；不让 dsh 把报告当插件加载。"""
    directory = os.path.join(root, PLUGIN_MIGRATION_DIRNAME)
    os.makedirs(directory, exist_ok=True)
    record = {"version": 1, "kind": kind, "createdAt": TS, "payload": payload}
    path = os.path.join(directory, "%s-%s-%s.json" % (kind, TS, os.getpid()))
    suffix = 0
    while os.path.lexists(path):
        suffix += 1
        path = os.path.join(directory, "%s-%s-%s-%d.json" % (kind, TS, os.getpid(), suffix))
    temporary = path + ".tmp"
    try:
        with open(temporary, "w", encoding="utf-8") as stream:
            json.dump(record, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.rename(temporary, path)
    finally:
        if os.path.lexists(temporary):
            try:
                os.unlink(temporary)
            except OSError:
                pass
    return path


def arg(name, default=""):
    key = "--" + name
    if key in sys.argv:
        i = sys.argv.index(key)
        if i + 1 < len(sys.argv):
            return sys.argv[i + 1]
    return default


def alpha_runtime():
    """Alpha restores user data transparently and leaves plugin/profile repair to dsh."""
    return arg("alpha", "") == "1"


def local_path_dep(spec):
    if not isinstance(spec, str):
        return None
    for prefix in ("link:", "file:"):
        if spec.startswith(prefix):
            return spec[len(prefix):]
    return None


# 老版本 dsh 的配置文件名和现在不一样（config.yaml / dsh.yaml），而「刚配好
# API key 还没对话过」的备份里连 sessions 都没有。原先只认
# sessions/profiles/settings.* 四个探针，上面这两类老备份会被判成
# 「备份里没找到 .dsh」→ 配置和 API key 全丢，用户看到的就是「和老版本不兼容」。
DSH_PROBES = ("sessions", "profiles", "settings.yaml", "settings.json",
              "config.yaml", "config.json", "dsh.yaml", "dsh.json",
              ".env", "agents", "skills", "credentials.json", "auth.json",
              "mcp.json", "history", ".dsha-apikey", ".credentials.yaml",
              ".dsha-backup-manifest.json")


def looks_like_dsh(path):
    """目录内容像不像 .dsh 本体。判据故意放宽 —— 漏认的代价是用户配置全丢。"""
    for probe in DSH_PROBES:
        # sessions 等公开数据在跨设备恢复时可能暂时是悬空软链；软链本身仍是
        # 有效的 dsh 布局，不能因为 exists() 跟随失败就误判整份候选为空。
        if os.path.lexists(os.path.join(path, probe)):
            return True
    return False


def count_sessions(dsh_dir):
    """数一下 .dsh 里有多少个会话文件（用于恢复前后对比）"""
    n = 0
    root = os.path.join(dsh_dir, "sessions")
    if not os.path.isdir(root):
        return 0
    for _r, _d, files in os.walk(root):
        n += sum(1 for f in files if f.startswith("session.jsonl"))
    return n


def dir_nonempty(path):
    try:
        return bool(os.listdir(path))
    except OSError:
        return False


def find_dsh_dir(stage):
    """在 stage 里定位 .dsh：优先名为 .dsh 且内容像的最浅路径；
    其次 stage 自身就是 .dsh 内容（老包/手工包）。"""
    best = None
    fallback = None
    best_depth = 10 ** 6
    for root, dirs, _files in os.walk(stage):
        dirs[:] = [d for d in dirs if d != "node_modules"]
        for d in list(dirs):
            if d != ".dsh":
                continue
            p = os.path.join(root, d)
            depth = p.count(os.sep)
            if looks_like_dsh(p) and depth < best_depth:
                best, best_depth = p, depth
            elif fallback is None and dir_nonempty(p):
                # 内容不匹配任何已知探针，但目录名就叫 .dsh 且非空 ——
                # 名字本身已是强信号，宁可多恢复一个目录，也不能漏掉用户的配置。
                fallback = p
    if best:
        return best
    if fallback:
        return fallback
    if looks_like_dsh(stage):
        return stage
    return None


def find_env_file(stage, workdir):
    """定位备份里的 .env：优先同名工作目录下的，其次任意 .env（取最浅）。"""
    cands = []
    for root, dirs, files in os.walk(stage):
        dirs[:] = [d for d in dirs if d not in ("node_modules", ".dsh")]
        for f in files:
            if f == ".env":
                cands.append(os.path.join(root, f))
    if not cands:
        return None
    cands.sort(key=lambda p: (os.path.basename(os.path.dirname(p)) != workdir, p.count(os.sep)))
    return cands[0]


def move_aside(path):
    """把已存在的路径挪成 .pre-restore-<ts> 备份。

    rename 失败必须向上抛出：旧数据仍在原路径，任何调用方都不能用删除或覆盖
    作为兜底。用 lexists 也能保护指向已删除目标的软链。
    """
    if not os.path.lexists(path):
        return None
    bak = "%s.pre-restore-%s" % (path, TS)
    suffix = 0
    while os.path.lexists(bak):
        suffix += 1
        bak = "%s.pre-restore-%s-%d" % (path, TS, suffix)
    try:
        os.rename(path, bak)
        return bak
    except Exception as e:
        # 绝不能 rm -rf path：这里失败时 path 仍是用户唯一的完整副本。
        raise OSError("无法保留原数据（rename %s -> %s 失败）" % (path, bak)) from e


def validate_dsh_candidate(path):
    """在触碰旧 .dsh 之前验证候选目录确实是可读的 dsh 数据目录。

    这是故意的结构校验，而不是猜测/修复会话格式：新版 dsh 可能使用 zstd/packed
    session，恢复端只验证目录、已知探针和 profile JSON，绝不改写会话内容。
    """
    if not os.path.isdir(path) or os.path.islink(path):
        raise ValueError("候选 .dsh 不是独立目录")
    if not looks_like_dsh(path):
        raise ValueError("候选 .dsh 缺少可识别的数据探针")
    try:
        def walk_error(error):
            raise error

        for base, dirs, files in os.walk(path, followlinks=False, onerror=walk_error):
            # lstat 只验证 stage 中的条目本身；sessions 等指向公开目录的软链是合法布局，
            # 不跟随它们，也不因换设备后暂时悬空就改写或删除。
            for name in dirs + files:
                entry = os.path.join(base, name)
                os.lstat(entry)
                if os.path.islink(entry) and not os.readlink(entry):
                    raise ValueError("候选 .dsh 含空软链：%s" % entry)
    except (OSError, ValueError) as e:
        raise ValueError("候选 .dsh 不完整：%s" % e) from e

    profiles = os.path.join(path, "profiles")
    if os.path.isdir(profiles):
        for prof in os.listdir(profiles):
            pkg_path = os.path.join(profiles, prof, "package.json")
            if not os.path.isfile(pkg_path):
                continue
            try:
                with open(pkg_path, encoding="utf-8") as f:
                    pkg = json.load(f)
                if not isinstance(pkg, dict):
                    raise ValueError("根值不是对象")
            except (OSError, ValueError, json.JSONDecodeError) as e:
                raise ValueError("profile %s/package.json 无法读取：%s" % (prof, e)) from e
    return True


def _restore_backup_at_path(backup, dst):
    """回滚旧目录；rename 被注入失败时，以复制保留原目录和备份副本。"""
    if not backup or os.path.lexists(dst):
        return os.path.lexists(dst)
    try:
        os.rename(backup, dst)
        return True
    except BaseException:
        # 这是仅用于回滚的最后一道保险，不删除 backup；即便复制不完整，
        # 带时间戳的备份仍完整留在磁盘上供人工恢复。
        try:
            if os.path.isdir(backup) and not os.path.islink(backup):
                shutil.copytree(backup, dst, symlinks=True)
            else:
                shutil.copy2(backup, dst, follow_symlinks=False)
            return True
        except Exception:
            return False


def _alpha_quarantine_name(root):
    """Return a collision-free sibling name for legacy Alpha profile data."""
    seed = ".dsha-alpha-legacy-%s-%s" % (TS, os.getpid())
    candidate = os.path.join(root, seed)
    suffix = 0
    while os.path.lexists(candidate):
        suffix += 1
        candidate = os.path.join(root, "%s-%d" % (seed, suffix))
    return candidate


def _quarantine_alpha_profile_tree(dsh, quarantine_parent):
    """Remove legacy web/profile patch state from a disposable candidate tree.

    The caller invokes this before the candidate is renamed over the live
    ``.dsh``.  A failure therefore discards only the new copy and cannot leave
    the existing user data without its original profile.  Renaming the profile
    entries (rather than deleting or parsing package.json) preserves every
    byte for manual inspection while keeping the upstream Alpha template free
    to initialize ``profiles/web`` on first launch.
    """
    if not os.path.isdir(dsh) or os.path.islink(dsh):
        return None
    profiles = os.path.join(dsh, "profiles")
    home_patch = os.path.join(dsh, "cordis.patch.yml")
    entries = []
    if os.path.islink(profiles):
        entries.append((profiles, "profiles"))
    elif os.path.isdir(profiles):
        for name, label in (("web", "profiles-web"),
                            ("node_modules", "profiles-node_modules")):
            path = os.path.join(profiles, name)
            if os.path.lexists(path):
                entries.append((path, label))
    if os.path.lexists(home_patch):
        entries.append((home_patch, "cordis.patch.yml"))
    if not entries:
        return None
    quarantine = _alpha_quarantine_name(quarantine_parent)
    os.makedirs(quarantine)
    for source, label in entries:
        # The candidate is disposable, so same-filesystem rename is both
        # atomic and faithful for regular files, directories, and symlinks.
        os.rename(source, os.path.join(quarantine, label))
    return quarantine


def _copy_alpha_profile_quarantine(src, root):
    """Copy a partial-backup profile tree into a timestamped quarantine.

    Partial restore keeps the existing active ``.dsh`` untouched in Alpha;
    this helper still retains the requested plugin/profile bytes and publishes
    the quarantine atomically after the copy has completed.
    """
    if not os.path.lexists(src):
        return None
    final = _alpha_quarantine_name(root)
    temp = final + ".stage"
    try:
        os.makedirs(temp)
        target = os.path.join(temp, "profiles")
        if os.path.isdir(src) and not os.path.islink(src):
            shutil.copytree(src, target, symlinks=True)
        else:
            shutil.copy2(src, target, follow_symlinks=False)
        os.rename(temp, final)
        return final
    except BaseException:
        try:
            if os.path.lexists(temp):
                if os.path.isdir(temp) and not os.path.islink(temp):
                    shutil.rmtree(temp)
                else:
                    os.unlink(temp)
        except Exception:
            pass
        raise


def _copy_candidate_to_parent(src, root, alpha=False):
    """将 stage 中的候选复制到 root 同一文件系统，供最后一步原子 rename。"""
    name = ".dsh.restore-stage-%s-%s" % (TS, os.getpid())
    tmp = os.path.join(root, name)
    suffix = 0
    while os.path.lexists(tmp):
        suffix += 1
        tmp = os.path.join(root, "%s-%d" % (name, suffix))
    try:
        shutil.copytree(src, tmp, symlinks=True)
        quarantine = _quarantine_alpha_profile_tree(tmp, root) if alpha else None
        validate_dsh_candidate(tmp)
        return tmp, quarantine
    except BaseException:
        # tmp 是本次新建的副本，清理它不会触及用户数据；stage 保留。
        try:
            if os.path.lexists(tmp):
                if os.path.isdir(tmp) and not os.path.islink(tmp):
                    shutil.rmtree(tmp)
                else:
                    os.unlink(tmp)
        except Exception:
            pass
        raise


def restore_dsh(stage, root, alpha=False):
    global partial, retain_stage, restore_committed
    src = find_dsh_dir(stage)
    if src is None:
        say("· 备份里没找到 .dsh（配置/对话）——跳过，其余内容照常恢复")
        partial = True
        retain_stage = True
        return False
    dst = os.path.join(root, ".dsh")
    try:
        validate_dsh_candidate(src)
        # 最终 rename 必须发生在 root 下；直接从 stage move 会把解压暂存目录
        # 变成半消费状态，且跨文件系统时会退化为非原子复制。
        staged, quarantine = _copy_candidate_to_parent(src, root, alpha=alpha)
        if quarantine:
            say("· Alpha 已隔离旧 web profile/plugin/bundle 到 %s，未激活旧 patch"
                % os.path.basename(quarantine))
    except BaseException as e:
        say("· .dsh 候选校验/暂存失败：%s（旧数据未改，暂存目录保留）" % e)
        partial = True
        retain_stage = True
        # KeyboardInterrupt 等中断交给上层，不能被伪装成成功恢复。
        if isinstance(e, KeyboardInterrupt):
            raise
        return False

    bak = None
    try:
        # 旧目录只在候选已经验证并复制完成后才离开原路径。
        bak = move_aside(dst)
        # dst 此时不存在，rename 是同一父目录内的原子落位。
        os.rename(staged, dst)
    except BaseException as e:
        # 若新目录落位中断/失败，先恢复旧目录；失败时保留带时间戳副本并尽力复制回
        # 原路径，绝不删除任何一份旧数据。staged 也故意不清理供排查。
        rolled_back = _restore_backup_at_path(bak, dst)
        retain_stage = True
        partial = True
        if rolled_back:
            say("· .dsh 恢复切换失败：%s（旧数据已原样保留，暂存目录保留）" % e)
        else:
            say("· .dsh 恢复切换失败：%s（旧数据备份仍在 %s，暂存目录保留）"
                % (e, os.path.basename(bak) if bak else "原路径"))
        if isinstance(e, KeyboardInterrupt):
            raise
        return False

    restore_committed = True
    say("· 已恢复 .dsh（配置 + 对话记录）%s" % ("，原数据留存在 " + os.path.basename(bak) if bak else ""))
    # 健全性对比：恢复后的会话数远少于恢复前，说明这个备份很可能不完整
    # （选错文件、备份中断都会这样）。数据其实还在 .pre-restore-* 里，
    # 但用户看到 RESTORE_OK 就以为万事大吉，过几天才发现「历史没了」，
    # 那时已经分不清该回退哪个目录了。所以这里必须说出来。
    if bak:
        old_n, new_n = count_sessions(bak), count_sessions(dst)
        if old_n > 0 and new_n * 2 < old_n:
            partial = True
            say("· ⚠ 恢复前有 %d 个对话，恢复后只剩 %d —— 这个备份可能不完整。"
                "原数据完整保留在 %s，要回退就把它改名回 .dsh"
                % (old_n, new_n, os.path.basename(bak)))
    return True


def collect_legacy_agent_presets(dsh, root):
    """保留旧目录型 Agent preset，生成供新版插件组合包审阅的迁移候选。

    dsh 0.1.7 不再扫描 ``$DSH_HOME/.agent-presets``。这里不猜测 YAML
    如何转换，也不自动启用第三方代码；只把原始目录复制到 dsh 外的隔离区，
    并写机器可读待处理记录。重复启动按源码摘要去重，原目录始终留在原位。
    """
    global partial, retain_stage
    source_root = os.path.join(dsh, ".agent-presets")
    if not os.path.isdir(source_root) or os.path.islink(source_root):
        return []
    candidates = []
    records = []
    try:
        names = sorted(os.listdir(source_root))
    except OSError as error:
        partial = True
        retain_stage = True
        say("· 旧 Agent preset 目录无法读取：%s" % error)
        return []
    for name in names:
        source = os.path.join(source_root, name)
        if not os.path.isdir(source) or os.path.islink(source):
            continue
        agent = os.path.join(source, "agent.cordis.yml")
        if not os.path.isfile(agent):
            continue
        try:
            digest = _tree_digest(source)
            destination = os.path.join(root, PLUGIN_MIGRATION_DIRNAME,
                                       "legacy-agent-presets", name, digest[:16])
            if not os.path.lexists(destination):
                _copy_tree_atomic(source, destination)
            bundle = _legacy_preset_bundle(source, destination, name)
            records.append({
                "id": name,
                "source": os.path.join(".dsh", ".agent-presets", name),
                "candidate": os.path.relpath(destination, root).replace(os.sep, "/"),
                "bundle": (os.path.relpath(os.path.join(destination, bundle), root).replace(os.sep, "/")
                           if bundle else ""),
                "sha256": digest,
                "target": "@deepseek-ai/dsh-agent-preset",
                "requiresReview": True,
                "activated": False,
            })
            candidates.append(destination)
        except Exception as error:
            partial = True
            retain_stage = True
            say("· 旧 Agent preset「%s」迁移候选生成失败：%s（原目录保留）" % (name, error))
    if records:
        try:
            _write_migration_record(root, "legacy-agent-presets", {
                "sourceRoot": os.path.join(".dsh", ".agent-presets"),
                "target": "@deepseek-ai/dsh-agent-preset",
                "activated": False,
                "requiresReview": True,
                "presets": records,
            })
            say("· 已隔离 %d 个旧 Agent preset 迁移候选；未自动启用" % len(records))
            partial = True
        except Exception as error:
            partial = True
            retain_stage = True
            say("· 旧 Agent preset 待处理记录写入失败：%s（原目录与候选保留）" % error)
    return candidates


def _link_target_or_self(path):
    """目标是有效软链时给出它指向的真实路径。

    数据要落在公开目录里，保持「主体在 Documents/dshdata、私有目录只留链接」这个
    迁移后的布局 —— 直接把链接换成目录，卸载 App 时数据就又跟着私有目录一起没了。
    """
    if os.path.islink(path) and os.path.exists(path):
        try:
            return os.path.realpath(path)
        except Exception:
            return path
    return path


def _land(src, dst):
    """把 src 原子落到 dst，失败时把旧目标恢复到原路径。

    部分恢复也不能在 rename/copy 失败后把旧子树留在隐藏备份名下；先复制到同父
    目录临时名，再用 rename 落位，和全量 .dsh 采用同一条失败安全路径。
    """
    bak = move_aside(dst)
    parent = os.path.dirname(dst)
    if parent:
        os.makedirs(parent, exist_ok=True)
    tmp = dst + ".dsha-land-%s-%s" % (TS, os.getpid())
    suffix = 0
    while os.path.lexists(tmp):
        suffix += 1
        tmp = dst + ".dsha-land-%s-%s-%d" % (TS, os.getpid(), suffix)
    try:
        if os.path.isdir(src) and not os.path.islink(src):
            shutil.copytree(src, tmp, symlinks=True)
        else:
            shutil.copy2(src, tmp, follow_symlinks=False)
        os.rename(tmp, dst)
        return bak
    except BaseException:
        try:
            if os.path.lexists(tmp):
                if os.path.isdir(tmp) and not os.path.islink(tmp):
                    shutil.rmtree(tmp)
                else:
                    os.unlink(tmp)
        except Exception:
            pass
        if bak and not os.path.lexists(dst):
            _restore_backup_at_path(bak, dst)
        raise


def find_stage_dir(stage, name):
    """在 stage 里找名为 name 的目录（最浅优先）；找不到返回 None。"""
    best, best_depth = None, 10 ** 6
    for root, dirs, _files in os.walk(stage):
        dirs[:] = [d for d in dirs if d != "node_modules"]
        if name in dirs:
            p = os.path.join(root, name)
            depth = p.count(os.sep)
            if depth < best_depth:
                best, best_depth = p, depth
    return best


def restore_pub_snapshot(stage, root, only=None):
    """把包里的 .dsha-pub/<name> 写回真实位置。

    sessions / storages / attachments / settings.yaml 在设备上通常是指向内部存储
    Documents/dshdata 的**符号链接**，而 tar 默认只存链接本身（实测包里只有一行
    lrwxrwxrwx，对话一条都没进去）。同机恢复看不出问题 —— 链接指回公开目录，数据
    还在那儿；换设备恢复就是悬空链接、对话全空。备份端因此额外做了一份解引用快照，
    这里把它落回去，且优先于 .dsh/ 下的同名链接。
    """
    global partial, retain_stage
    src_dir = find_stage_dir(stage, ".dsha-pub")
    if not src_dir:
        return False
    done = []
    try:
        names = sorted(os.listdir(src_dir))
    except OSError:
        return False
    for name in names:
        if only and name not in only:
            continue
        src = os.path.join(src_dir, name)
        dst = _link_target_or_self(os.path.join(root, ".dsh", name))
        try:
            bak = _land(src, dst)
            done.append(name + ("（原数据留存 %s）" % os.path.basename(bak) if bak else ""))
        except Exception as e:
            partial = True
            retain_stage = True
            say("· 从快照恢复 %s 失败：%s" % (name, e))
    if done:
        say("· 已从快照恢复热数据：%s" % "、".join(done))
        return True
    return False


def restore_dsh_subtree(stage, root, subdirs, alpha=False):
    """部分备份：只把指定子树合并进现有 .dsh，其余内容一律不动。

    **绝不能走 restore_dsh** —— 那是「整个 .dsh 挪走再替换」。拿一个只含对话的包
    那么做，等于把用户的配置和插件全换掉；原数据虽然留在 .pre-restore-*，但用户
    看到 RESTORE_OK 就不会去找，等发现时已经分不清该回退哪个目录。
    """
    global partial, retain_stage
    src_dsh = find_dsh_dir(stage)
    if src_dsh is None:
        return False
    dst_dsh = os.path.join(root, ".dsh")
    try:
        os.makedirs(dst_dsh, exist_ok=True)
    except OSError:
        pass
    done = 0
    for sub in subdirs:
        src = os.path.join(src_dsh, sub)
        if not os.path.lexists(src):
            continue
        if alpha and sub == "profiles":
            try:
                quarantine = _copy_alpha_profile_quarantine(src, root)
                if quarantine:
                    say("· Alpha 已将备份里的旧 profiles 隔离到 %s，未覆盖官方 profile"
                        % os.path.basename(quarantine))
                    partial = True
                    done += 1
            except Exception as e:
                partial = True
                retain_stage = True
                say("· Alpha 隔离旧 profiles 失败：%s" % e)
            continue
        dst = _link_target_or_self(os.path.join(dst_dsh, sub))
        try:
            bak = _land(src, dst)
            say("· 已恢复 .dsh/%s%s"
                % (sub, "，原数据留存在 " + os.path.basename(bak) if bak else ""))
            done += 1
        except Exception as e:
            partial = True
            retain_stage = True
            say("· 恢复 .dsh/%s 失败：%s" % (sub, e))
    return done > 0


def restore_env(stage, root, workdir):
    global partial, retain_stage
    src = find_env_file(stage, workdir)
    if src is None:
        say("· 备份里没有 .env（API Key）——跳过（可在配置页重新填）")
        return False
    dst_dir = os.path.join(root, workdir)
    dst = os.path.join(dst_dir, ".env")
    tmp = dst + ".dsha-env-stage-%s-%s" % (TS, os.getpid())
    suffix = 0
    while os.path.lexists(tmp):
        suffix += 1
        tmp = dst + ".dsha-env-stage-%s-%s-%d" % (TS, os.getpid(), suffix)
    bak = None
    try:
        os.makedirs(dst_dir, exist_ok=True)
        # 完整复制到同父目录临时文件并落盘，再切走旧 .env；这样复制或
        # rename 任一步失败都不会把旧 API key 留成半个文件。
        with open(src, "rb") as sf, open(tmp, "xb") as tf:
            shutil.copyfileobj(sf, tf)
            tf.flush()
            os.fsync(tf.fileno())
        bak = move_aside(dst)
        try:
            os.rename(tmp, dst)
        except BaseException:
            if bak and not os.path.lexists(dst):
                _restore_backup_at_path(bak, dst)
            raise
        from_wd = os.path.basename(os.path.dirname(src))
        if from_wd and from_wd != workdir:
            say("· 已恢复 .env（备份里的工作目录是「%s」，已落到本机的「%s」%s）"
                % (from_wd, workdir,
                   "，原数据留存在 " + os.path.basename(bak) if bak else ""))
        else:
            say("· 已恢复 .env（API Key%s）"
                % ("，原数据留存在 " + os.path.basename(bak) if bak else ""))
        return True
    except BaseException as e:
        try:
            if os.path.lexists(tmp):
                if os.path.isdir(tmp) and not os.path.islink(tmp):
                    shutil.rmtree(tmp)
                else:
                    os.unlink(tmp)
        except Exception:
            pass
        say("· .env 恢复失败：%s" % e)
        partial = True
        retain_stage = True
        if isinstance(e, KeyboardInterrupt):
            raise
        return False


def restore_inlined_plugins(stage, root):
    """把备份内联源码落地到 /root/plugin-src。

    同名源码是覆盖更新中最容易误伤用户数据的场景：当前版本可能已经被
    用户修改，或是新包中的版本。现有目录永远不删除；备份候选复制到
    ``.dsha-plugin-migration/conflicts``，并由 ``fix_profiles`` 保留原声明
    等待审阅。只有目标不存在时才返回 landed 供路径重映射。
    """
    global partial, retain_stage
    landed = {}
    conflicts = []
    src_root = None
    for root_dir, dirs, _f in os.walk(stage):
        if os.path.basename(root_dir) == INLINE_DIRNAME:
            src_root = root_dir
            break
        dirs[:] = [d for d in dirs if d != "node_modules"]
    if not src_root:
        return landed
    dst_root = os.path.join(root, PLUGIN_SRC_DIRNAME)
    for name in sorted(os.listdir(src_root)):
        s = os.path.join(src_root, name)
        if not os.path.isdir(s):
            continue
        d = os.path.join(dst_root, name)
        try:
            os.makedirs(dst_root, exist_ok=True)
            if os.path.lexists(d):
                # 当前源码保持在原路径，候选单独隔离。相同字节的重复候选
                # 也不覆盖当前目录，按摘要路径去重。
                incoming_sha = _tree_digest(s)
                quarantine = os.path.join(root, PLUGIN_MIGRATION_DIRNAME,
                                          "conflicts", name, incoming_sha[:16])
                if not os.path.lexists(quarantine):
                    _copy_tree_atomic(s, quarantine)
                try:
                    current_sha = _tree_digest(d)
                except Exception:
                    current_sha = "unreadable"
                conflicts.append({
                    "name": name,
                    "current": os.path.join(PLUGIN_SRC_DIRNAME, name),
                    "candidate": os.path.relpath(quarantine, root).replace(os.sep, "/"),
                    "currentSha256": current_sha,
                    "candidateSha256": incoming_sha,
                    "activated": False,
                })
                partial = True
                say("· 插件源码「%s」存在同名当前版本，已保留当前目录；备份候选隔离到 %s"
                    % (name, os.path.relpath(quarantine, root).replace(os.sep, "/")))
                continue
            _copy_tree_atomic(s, d)
            landed[name] = d
        except Exception as e:
            say("· 插件源码 %s 落地失败：%s" % (name, e))
            partial = True
            retain_stage = True
    if conflicts:
        try:
            _write_migration_record(root, "plugin-conflicts", {
                "activated": False,
                "requiresReview": True,
                "conflicts": conflicts,
            })
        except Exception as error:
            partial = True
            retain_stage = True
            say("· 插件冲突待处理记录写入失败：%s（候选与当前版本均保留）" % error)
    if landed:
        say("· 已从备份还原 %d 个本机插件源码：%s" % (len(landed), "、".join(sorted(landed))))
    return landed


def pkg_dir_ok(path):
    return os.path.isdir(path) and os.path.isfile(os.path.join(path, "package.json"))


def ensure_nm_link(prof_dir, name, target):
    """在 profile 的 node_modules 里补一条指向本机插件目录的符号链接。

    只改 package.json 的 link: 路径还不够：dsh 解析 bundle 走 node 的模块解析，
    node_modules/<name> 没有条目时依然报 cannot resolve profile bundle
    （正常是 pnpm install 建的链接，恢复后还没跑过 install）。
    """
    nm = os.path.join(prof_dir, "node_modules")
    link = os.path.join(nm, name)
    try:
        os.makedirs(nm, exist_ok=True)
        # 用 readlink 而不是 os.path.islink —— proot 下 lstat 被劫持，islink 对
        # 这些链恒返回 False，已经建好的正确链接会被下面的 isdir 分支当成普通目录处理
        try:
            cur = os.readlink(link)
        except OSError:
            cur = None
        if cur is not None:
            if os.path.realpath(link) == os.path.realpath(target):
                return True
            os.unlink(link)
        elif os.path.isdir(link):
            return True  # 已有实体目录（pnpm 装的真包），不动
        elif os.path.exists(link):
            os.remove(link)
        os.symlink(target, link)
        return True
    except Exception as e:
        say("· node_modules 补链失败（%s）：%s" % (name, e))
        return False


def bundle_resolvable(name, prof_dir, deps):
    spec = deps.get(name)
    p = local_path_dep(spec)
    if p and pkg_dir_ok(p):
        return True
    if pkg_dir_ok(os.path.join(prof_dir, "node_modules", name)):
        return True
    for base in GLOBAL_NM:
        if pkg_dir_ok(os.path.join(base, name)):
            return True
    return False


def fix_profiles(root, landed):
    """重映射可确认的 link；缺失声明保留并写入待处理清单。

    旧实现为保证 web 启动直接删掉缺失依赖和 bundle，结果是插件源码与
    用户启停意图一起丢失。新版 dsh 会在启动/插件页给出兼容性错误，恢复
    层应保留原声明和源码，让用户或原生审阅流程决定是否启用。
    """
    global partial
    profiles = os.path.join(root, ".dsh", "profiles")
    if not os.path.isdir(profiles):
        return
    remapped, kept_missing, auto_installable = [], [], []
    pending = []
    for prof in sorted(os.listdir(profiles)):
        prof_dir = os.path.join(profiles, prof)
        pkg_path = os.path.join(prof_dir, "package.json")
        if not os.path.isfile(pkg_path):
            continue
        try:
            with open(pkg_path) as f:
                pkg = json.load(f)
        except Exception as e:
            say("· profile「%s」的 package.json 读不动（%s），跳过修正" % (prof, e))
            partial = True
            continue
        deps = pkg.get("dependencies") or {}
        if not isinstance(deps, dict):
            pending.append({"profile": prof, "reason": "DEPENDENCIES_DECLARATION_INVALID"})
            partial = True
            say("· profile「%s」的 dependencies 不是对象，原声明保留待审阅" % prof)
            continue
        changed = False
        # 1. 本机路径依赖：不存在就换成本机能找到的路径；存在的顺手补 node_modules 链接
        for name in list(deps):
            p = local_path_dep(deps[name])
            if p is None:
                continue
            if pkg_dir_ok(p):
                if not ensure_nm_link(prof_dir, name, p):
                    pending.append({"profile": prof, "dependency": name, "reason": "NODE_MODULE_LINK_FAILED"})
                continue
            cand = landed.get(name) or os.path.join(root, PLUGIN_SRC_DIRNAME, name)
            if pkg_dir_ok(cand):
                deps[name] = "link:" + cand
                if not ensure_nm_link(prof_dir, name, cand):
                    pending.append({"profile": prof, "dependency": name, "reason": "NODE_MODULE_LINK_FAILED"})
                remapped.append("%s→%s" % (name, cand))
                changed = True
            else:
                # 保留 link: 原声明；删除会让用户无法知道旧插件仍可从
                # 隔离候选恢复。新版插件管理器会据此显示待审阅/待补装状态。
                pending.append({"profile": prof, "dependency": name, "reason": "LOCAL_SOURCE_MISSING"})
                if isinstance(deps[name], str):
                    auto_installable.append(name)
        # 2. bundles 预检：解析不了的保留，交给新版插件管理器审阅。
        dsh = pkg.get("dsh")
        prof_node = (dsh or {}).get("profile") if isinstance(dsh, dict) else None
        bundles = prof_node.get("bundles") if isinstance(prof_node, dict) else None
        if isinstance(bundles, list):
            keep = []
            for b in bundles:
                if not isinstance(b, str) or not b:
                    kept_missing.append(str(b))
                    pending.append({"profile": prof, "bundle": b,
                                    "reason": "BUNDLE_DECLARATION_INVALID"})
                    keep.append(b)
                    continue
                if bundle_resolvable(b, prof_dir, deps):
                    keep.append(b)
                else:
                    kept_missing.append(b)
                    # 依赖里还留着 registry 版本号（^1.2.3 / npm:… ）→ 可以自动装回
                    spec = deps.get(b)
                    if isinstance(spec, str) and spec and local_path_dep(spec) is None:
                        auto_installable.append(b)
                    pending.append({"profile": prof, "bundle": b, "reason": "BUNDLE_SOURCE_MISSING"})
            # 旧 bundle 即使当前不可解析也保留；dsh/插件管理器负责后续审阅。
        if changed:
            try:
                # 原子写 + fsync：这是 profile 的核心文件，
                # 半个 JSON 会让 dsh 完全无法加载该 profile
                tmp_pkg = pkg_path + ".dsha-tmp"
                with open(tmp_pkg, "w") as f:
                    json.dump(pkg, f, ensure_ascii=False, indent=2)
                    f.write("\n")
                    f.flush()
                    os.fsync(f.fileno())
                os.replace(tmp_pkg, pkg_path)
            except Exception as e:
                say("· profile「%s」写回失败：%s" % (prof, e))
                partial = True
    if remapped:
        say("· 插件路径已按本机重映射：%s" % "、".join(remapped))
    if kept_missing:
        partial = True
        say("· 以下插件本机缺失，原依赖和启用声明已保留，等待迁移审阅：%s"
            % "、".join(sorted(set(kept_missing))))
    if pending:
        partial = True
        try:
            record = _write_migration_record(root, "plugin-pending", {
                "activated": False,
                "requiresReview": True,
                "items": pending,
                "autoInstallable": sorted(set(auto_installable)),
            })
            say("· 已保留 %d 条缺失插件声明，待处理清单：%s" %
                (len(pending), os.path.relpath(record, root).replace(os.sep, "/")))
        except Exception as error:
            partial = True
            say("· 缺失插件待处理清单写入失败：%s（原声明仍保留）" % error)
    # 机器可读：仍有 registry 版本号（^1.2.3 / npm: 之类）的缺失插件可以自动补装，
    # App 侧据此在后台静默 dsh plugin add 装回；源码彻底丢失的只能人工重装。
    if auto_installable:
        print("MISSING_PLUGINS: %s" % ",".join(sorted(set(auto_installable))))


def read_manifest(stage):
    for root_dir, dirs, files in os.walk(stage):
        dirs[:] = [d for d in dirs if d != "node_modules"]
        for f in files:
            if f == ".dsha-backup-manifest.json" or f == "backup-manifest.json":
                try:
                    with open(os.path.join(root_dir, f)) as fh:
                        return json.load(fh)
                except Exception:
                    return None
    return None


def ensure_workspace_dirs(root):
    """恢复后按 workspace.json 里的工作区路径补建目录，防止 dsh 剪会话。

    dsh 对每个会话做 cwd 校验：cwd 对应的目录必须真实存在，否则该会话会被
    从 workspace.json 注册表除名（文件还在，界面里对话消失）。备份只带 .dsh
    （会话数据），不带工作区工作目录 —— 换机/重装恢复后目录缺失就会触发这个
    剪枝。这里把注册表引用的路径补建出来，让校验通过、会话得以保留。
    """
    ws_path = os.path.join(root, ".dsh", "storages", "workspace.json")
    try:
        with open(ws_path, "r", encoding="utf-8") as fh:
            data = json.load(fh)
    except Exception as e:
        say("· 补建工作区目录跳过（无 workspace.json）：%s" % e)
        return False
    created = []
    workspaces = (data.get("tables") or {}).get("workspaces") or {}
    for _wid, rec in workspaces.items():
        path = (rec or {}).get("path")
        if not path or not path.startswith("/"):
            continue
        try:
            os.makedirs(path, exist_ok=True)
            created.append(path)
        except OSError as e:
            say("· 补建工作区目录失败 %s：%s" % (path, e))
    if created:
        say("· 已补建工作区目录（防 dsh 剪会话）：%s" % "、".join(sorted(set(created))))
        return True
    return False


def main():
    global partial, retain_stage, restore_committed
    # 脚本通常每次只运行一次，但测试/嵌入调用可能复用解释器；状态不能跨恢复串线。
    report.clear()
    partial = False
    retain_stage = False
    restore_committed = False
    stage = arg("stage", "/root/.dsha-restore-stage")
    root = arg("root", "/root")
    workdir = arg("workdir", "deepseek-harness") or "deepseek-harness"
    alpha = alpha_runtime()
    if not os.path.isdir(stage):
        print("恢复失败：解压目录不存在（%s）" % stage)
        print("RESTORE_EMPTY")
        return 1
    # stage 必须是独立解压目录；把 root 或现有 .dsh 当 stage 会让末尾清理误删用户数据。
    stage_real = os.path.realpath(stage)
    root_real = os.path.realpath(root)
    if stage_real in (root_real, os.path.join(root_real, ".dsh")):
        retain_stage = True
        print("恢复失败：解压暂存目录不能指向 root 或现有 .dsh（%s）" % stage)
        print("RESTORE_EMPTY")
        return 1
    man = read_manifest(stage)
    # 备份范围的判定顺序：清单里的 scope 最权威（备份时写下的事实）→ App 从文件名
    # 推断出来的 --scope 兜底（老包没有清单，或用户重命名过文件）→ 最后缺省 full
    # （老备份的语义就是全量）。反过来让 --scope 覆盖清单是不对的：文件名可以被改，
    # 清单不会。
    manifest_scope = (man.get("scope") if man else "")
    if man is not None and manifest_scope is not None:
        manifest_scope = str(manifest_scope).strip()
        if manifest_scope and manifest_scope not in ("full", "sessions", "settings", "plugins"):
            # A manifest is an explicit claim about the archive.  Never turn an
            # unknown claim into a full restore, because that could overwrite
            # unrelated user data.  Keep the isolated stage for diagnosis.
            retain_stage = True
            say("· 备份范围无法识别：%s；现有数据未覆盖" % manifest_scope[:64])
            print("\n".join(report))
            print("RESTORE_SCOPE_UNKNOWN")
            print("RESTORE_EMPTY")
            return 1
    scope = ((manifest_scope if man else "") or arg("scope", "") or "full").strip() or "full"
    if man:
        say("· 备份来自 App %s / dsh %s（格式 v%s）"
            % (man.get("appVersion", "?"), man.get("dshVersion", "?"), man.get("formatVersion", "?")))
    else:
        say("· 老备份（无清单文件），按内容自动识别恢复")

    if scope == "sessions":
        say("· 这是「只对话」备份：只覆盖对话记录，配置与插件保持现状")
        ok_dsh = restore_dsh_subtree(stage, root, ["sessions"], alpha=alpha)
        # 快照后跑：它才是真数据（.dsh/sessions 在设备上多半只是个软链）
        ok_dsh = restore_pub_snapshot(stage, root, only=["sessions"]) or ok_dsh
    elif scope == "settings":
        say("· 这是「只设置」备份：只覆盖 settings.yaml，聊天记录与插件保持现状")
        ok_dsh = restore_dsh_subtree(stage, root, ["settings.yaml"], alpha=alpha)
        ok_dsh = restore_pub_snapshot(stage, root, only=["settings.yaml"]) or ok_dsh
    elif scope == "plugins":
        say("· 这是「只插件」备份：只覆盖插件，对话与配置保持现状")
        ok_dsh = restore_dsh_subtree(stage, root, ["profiles"], alpha=alpha)
        if alpha:
            say("· Alpha 不把旧 profile/plugin/bundle 激活，已保留在隔离目录供排查")
        else:
            landed = restore_inlined_plugins(stage, root)
            fix_profiles(root, landed)
            ok_dsh = ok_dsh or bool(landed)
    else:
        ok_dsh = restore_dsh(stage, root, alpha=alpha)
        if ok_dsh:
            restore_env(stage, root, workdir)
            if alpha:
                say("· Alpha 保留会话/设置原字节；旧 web profile/plugin/bundle 已隔离，交由官方 dsh 初始化")
            else:
                landed = restore_inlined_plugins(stage, root)
                fix_profiles(root, landed)
            # 全量也要落快照：.dsh 里的 sessions 等可能只是软链
            restore_pub_snapshot(stage, root)
        else:
            # 候选校验/切换失败时，旧 .dsh 仍是用户唯一可启动的数据；任何后续
            # merge、插件修补或快照落位都可能改写它，所以这里必须全部跳过。
            say("· 因 .dsh 未安全落位，跳过 .env、插件和快照合并")
    # 无论全量/部分恢复，只要 .dsh 落位了就补建工作区目录：dsh 启动时会按
    # cwd 校验每个会话，工作目录缺失会把恢复的会话从注册表剪掉（文件还在但
    # 界面里消失）。备份只带 .dsh、不带工作目录，这一步必须在 dsh 启动前做。
    if os.path.isdir(os.path.join(root, ".dsh")):
        ensure_workspace_dirs(root)
        # dsh 0.1.7 仅从插件组合包发现 Agent preset。旧目录型 preset
        # 不能直接删除或未经审阅启用，先生成隔离候选和待处理记录。
        collect_legacy_agent_presets(os.path.join(root, ".dsh"), root)
    if not retain_stage:
        try:
            shutil.rmtree(stage, ignore_errors=True)
        except Exception:
            pass
    else:
        say("· 恢复未完成：保留解压暂存目录 %s 供排查/重试" % stage)
    text = "\n".join(report)
    # 失败时旧 .dsh 必须原样保留，不能为了写报告再改动它；把诊断写进 stage。
    try:
        report_dir = os.path.join(stage, ".dsha-restore-report") if retain_stage else os.path.join(root, ".dsh")
        rp = os.path.join(report_dir, "restore-report.txt")
        if retain_stage:
            os.makedirs(report_dir, exist_ok=True)
        if os.path.isdir(report_dir):
            with open(rp, "a") as f:
                f.write("== 恢复 %s ==\n%s\n" % (TS, text))
    except Exception:
        pass
    print(text)
    # 宿主只有在全量 .dsh 已经完成候选校验并原子落位后，才可以轮换旧设备
    # 的本机凭据或做后续迁移。RESTORE_PARTIAL 既可能表示「已提交但部分内容
    # 缺失」，也可能表示「根本没有提交」；不能只看结果级别判断。
    if restore_committed:
        print("RESTORE_DSH_COMMITTED")
    if not ok_dsh:
        # Keep the historical result token for callers; the separate
        # RESTORE_DSH_COMMITTED marker above tells the host whether it is safe
        # to rotate local credentials or run post-restore migration.
        print("RESTORE_PARTIAL" if report else "RESTORE_EMPTY")
    else:
        print("RESTORE_PARTIAL" if partial else "RESTORE_OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
