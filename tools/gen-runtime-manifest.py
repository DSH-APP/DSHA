#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 runtime 增量更新清单（发版时跑一次，产物提交进仓库）。

为什么要有增量更新：这次修的一批问题里，fs-write-patch.sh、flatten-l2s.py、
webserver-auth-patch.sh、adb-shell.py、引导插件……**全都是几 KB 的脚本**，
却要用户重下 384MB 的 APK 才能拿到。清单驱动的增量更新让「改脚本」这类修复
可以当天到用户手上。

定位（重要，别把它做成另一套安装器）：
- **不替代内置离线包**。装机仍然是 APK 自带 306MB rootfs、离线可用 ——
  网不好的用户一次装完就能跑，这是我们相对同类项目的优势，不能拿去换体积。
- 增量只覆盖**脚本层**：assets 里的 .py/.sh/.js 与内置插件源码。
  .so、rootfs、APK 本体一概不在范围内 —— 那些必须走正常发版，
  因为它们要么涉及签名，要么体积根本不适合增量。
- 纯可选：默认不自动下载，用户手动点「检查脚本更新」。全部失败也只是维持现状。

用法：
  python3 tools/gen-runtime-manifest.py            # 写到 runtime-manifest.json
  python3 tools/gen-runtime-manifest.py --check     # 只校验现有清单是否与文件一致
"""
import hashlib
import json
import os
import sys
import time

ASSETS = "app/src/main/assets"
OUT = "runtime-manifest.json"
# 只有这些后缀进清单：脚本是纯文本、幂等注入、出问题也能被下一次覆盖修好。
# 二进制与 rootfs 不进 —— 增量更新一个 306MB 的包毫无意义，
# 而 .so 涉及签名与 ABI，只能随 APK 发。
EXTS = (".py", ".sh", ".js", ".json")
# 这些不发：要么是本地缓存，要么体积巨大
SKIP_DIRS = ("__pycache__",)
SKIP_NAMES = ("offline-rootfs.bin",)
# 与 RuntimeUpdater 一样只指向 stable：main 是 1.2 alpha 线，不能让 1.1.x 清单下载它的资产。
URL_TEMPLATES = (
    "https://raw.githubusercontent.com/qiannianhuanxiang/DSHA/stable/{path}",
    "https://cdn.jsdelivr.net/gh/qiannianhuanxiang/DSHA@stable/{path}",
    "https://ghproxy.net/https://raw.githubusercontent.com/qiannianhuanxiang/DSHA/stable/{path}",
)


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def assert_lf(path):
    """行尾必须是 LF，否则拒绝生成清单。

    清单里的 size/sha256 是按**仓库字节**算的，客户端从 raw.githubusercontent 下到的
    也是仓库字节。工作树被检出成 CRLF 时，每一项都会偏大（每个换行 +1 字节）——
    清单自己的签名照样验得过，客户端却因为逐文件 sha256 对不上而**整批拒绝**，
    界面上只剩一句「清单签名验证失败」。issue #45 里 alpha2 就是这么坏掉的：
    36 个条目全部与线上不符。这里 fail-closed，比默默生成一份坏清单好得多。
    """
    with open(path, "rb") as f:
        data = f.read()
    if b"\r\n" in data:
        raise SystemExit(
            "拒绝生成清单：%s 的行尾是 CRLF。\n"
            "仓库根的 .gitattributes 已强制 LF —— 重新检出一次再跑：\n"
            "  git rm --cached -r . && git reset --hard" % path)


def collect():
    items = []
    for root, dirs, files in os.walk(ASSETS):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in sorted(files):
            if name in SKIP_NAMES or not name.endswith(EXTS):
                continue
            full = os.path.join(root, name)
            assert_lf(full)
            rel = os.path.relpath(full, ".").replace(os.sep, "/")
            items.append({
                "path": rel,
                # 装进 rootfs 后的落点由 App 决定，清单只描述「仓库里的哪个文件」
                "asset": os.path.relpath(full, ASSETS).replace(os.sep, "/"),
                "size": os.path.getsize(full),
                "sha256": sha256_of(full),
                "urls": [t.format(path=rel) for t in URL_TEMPLATES],
            })
    return items


def main():
    if not os.path.isdir(ASSETS):
        print("请在仓库根目录运行（找不到 %s）" % ASSETS)
        return 1
    items = collect()
    manifest = {
        "schema": 1,
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "note": "只含脚本层增量；rootfs 与 .so 随 APK 发布，不在此清单内",
        "files": items,
    }

    if "--check" in sys.argv:
        if not os.path.isfile(OUT):
            print("MANIFEST_MISSING")
            return 1
        with open(OUT, encoding="utf-8") as f:
            old = json.load(f)
        oldmap = {i["asset"]: (i.get("sha256"), i.get("size"), tuple(i.get("urls", [])))
                  for i in old.get("files", [])}
        newmap = {i["asset"]: (i["sha256"], i["size"], tuple(i["urls"])) for i in items}
        stale = [k for k, v in newmap.items() if oldmap.get(k) != v]
        gone = [k for k in oldmap if k not in newmap]
        if stale or gone:
            print("MANIFEST_STALE 需要重新生成：%d 个变更、%d 个已删除"
                  % (len(stale), len(gone)))
            for k in (stale[:8] + gone[:4]):
                print("  %s" % k)
            return 1
        print("MANIFEST_OK 与工作区一致（%d 个文件）" % len(items))
        return 0

    total = sum(i["size"] for i in items)

    # 内容没变就不重写 —— 只有 generated 时间戳会变的话，重写只会制造噪声 diff。
    #
    # 这不是洁癖：build.sh 每次构建都跑本脚本，于是清单**每次构建都变脏**。
    # 脏得太频繁，它的脏就不再是信号，最后必然被当成「构建产物，不用管」而漏提交。
    # 2026-08-25 就是这么漏的：commit 952c3e1 提交了 selftest.py 却没带清单，
    # main 上清单记的还是旧 sha256 —— 客户端下到新文件、校验对不上、整批丢弃，
    # 界面上什么都不说。脚本热更新对所有用户静默失效。
    #
    # 改成内容不变则原样保留（含旧时间戳），清单一变脏就是真有脚本改动，
    # 那时的脏必须跟着这一轮 commit 走。
    if os.path.isfile(OUT):
        try:
            with open(OUT, encoding="utf-8") as f:
                old = json.load(f)
            if {i["asset"]: (i["sha256"], i["size"]) for i in old.get("files", [])} \
                    == {i["asset"]: (i["sha256"], i["size"]) for i in items}:
                print("MANIFEST_UNCHANGED %s：%d 个文件与清单一致，未改写"
                      % (OUT, len(items)))
                return 0
        except Exception as e:      # 清单坏了/格式变了 → 照常重写
            print("（现有清单读不出来，重新生成：%s）" % e)

    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("MANIFEST_WRITTEN %s：%d 个文件，共 %.1f KB"
          % (OUT, len(items), total / 1024.0))
    print("⚠ 清单已变更，请与本轮脚本改动**一起 commit**"
          "（清单落后于文件 = 客户端热更新校验失败且无提示）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
