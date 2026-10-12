#!/usr/bin/env python3
"""把 dsh-desktop 插件移植成 DSHA 插件的**分析与脚手架**工具。

为什么需要它：`anywhere-labs/dsh-desktop` 的插件是按 Electron 写的，直接放进 DSHA
会在加载期就炸（没有 Electron），或者静默停在 PENDING（`desktopRuntime` 服务不存在）。
人工逐个文件判断既慢又容易漏，而这个判断的规则是确定的、可自动化的。

本工具做三件事，**刻意不做第四件**：

  1. `port`   —— 按硬门槛把源包逐文件分类（可移植 / 需适配 / 不可移植），并给出理由；
  2. `port`   —— 为选定的移植单元生成符合 DSHA 契约的插件骨架；
  3. `verify` —— 校验一个插件目录是否满足 DSHA 的加载契约。
  4. （不做）**不生成插件业务代码**。哪些 API 能映射、哪些必须丢弃，取决于业务语义，
     机器改写只会产出看起来能跑、实际行为错误的包。工具改为输出「需要人工适配的
     调用点清单」（file:line + 具体 API），把人力放在真正需要判断的地方。

分类用的硬门槛来自真实移植中踩到的失效模式，不是猜测：

  * `import ... from 'electron'`  → DSHA 容器里没有 Electron，加载期直接抛错。
  * 运行时访问 `ctx.desktopRuntime.*` → 该服务由 Electron launcher 在 Loader 挂载**之前**
    注册，DSHA 里不存在。用到它的插件在 cordis 里会永远停在 PENDING —— **不报错、
    也不干活**，是最隐蔽的一种失效。
  * **只看值导入，不看 `import type`**：类型导入编译期即擦除，不构成运行时依赖。
    早期把两者混为一谈，会把 21 个本来可移植的文件误判成不可移植。

用法：
    # 分析整个包
    python3 tools/dsh-desktop-port.py port <源包目录> --report out/port-report.md

    # 生成插件骨架（--module 可重复，指定要移植的模块）
    python3 tools/dsh-desktop-port.py port <源包目录> --name dsh-my-port \\
        --module src/notifications.ts --module src/jobs-bridge.ts --out out/

    # 校验一个插件目录
    python3 tools/dsh-desktop-port.py verify <插件目录> [--runtime <dsh安装目录>]
"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

# ── 分类桶 ────────────────────────────────────────────────────────────────

A1 = 'A1 直接可移植'
A2 = 'A2 需适配可移植'
CLIENT = 'C 客户端Web侧'
B1 = 'B1 Electron原生外壳'
B2 = 'B2 平台专属(Win/macOS)'
B3 = 'B3 打包与模块解析'
B4 = 'B4 桌面应用自更新'

BUCKET_ORDER = [A1, A2, CLIENT, B1, B2, B3, B4]

BUCKET_NOTE = {
    A1: '纯 Node/cordis，无 Electron 依赖，可原样搬进 DSHA 插件',
    A2: '宿主逻辑可复用，但依赖 native adapter 或需替换为 DSHA 桥适配器',
    CLIENT: '浏览器代码，可挂到 DSHA 的 DSH Web UI',
    B1: '依赖 Electron 窗口/托盘/进程模型，Android 无对等物',
    B2: 'Windows ACL/PowerShell/安装器专属',
    B3: 'Electron asar/打包运行时模块解析专属',
    B4: '对象是桌面安装包（dmg/exe），DSHA 是 APK 更新体系',
}

# 目录/文件名前缀 → 固定归属的桶（顺序敏感：先匹配到的赢）
FIXED_BUCKETS = [
    ('native-ui/', B1),
    ('client/', CLIENT),
    ('windows-', B2),
    ('desktop-installer-quit', B2),
    ('asar-', B3),
    ('module-resolution', B3),
    ('packaged-', B3),
    ('bin.ts', B3),
    ('update-', B4),
    ('updates.ts', B4),
    ('desktop-installation-id', B4),
]

# 值导入这些适配器 = 运行时真的依赖 Electron 侧能力
ADAPTER_MODULES = (
    'electron-runtime', 'electron-platform', 'electron-reveal',
    'electron-shell-generation', 'host-process', 'host-runtime-bridge',
    'runtime',
)

# native adapter 的公开方法名，用于定位人工适配点
DESKTOP_RUNTIME_METHODS = (
    'notifyAttention', 'registerTrayItem', 'openTerminal', 'reloadRenderer',
    'toggleDeveloperTools', 'exportDiagnostics', 'pickDirectory',
    'openProfileCreateWindow', 'platformLogin', 'validateDirectory',
    'reportRendererBoot', 'setLocalePreference', 'setThemeSource',
    'requestRestart', 'requestRecoveryRestart', 'prepareToQuit',
    'requestQuit', 'requestModeChange', 'mountScheduled', 'schedule', 'show',
)

ELECTRON_IMPORT = re.compile(r"""from\s+['"]electron['"]|require\(\s*['"]electron['"]\s*\)""")
# 值导入：`import X from './runtime.ts'` / `import { a } from './runtime.ts'`
# 类型导入：`import type ...` —— 编译期擦除，不算运行时依赖
# 匹配一条 `import <子句> from '<模块>'`，子句里不含引号与分号
IMPORT_FROM = re.compile(r"""import\s+(?P<clause>[^;'"]*?)\s+from\s+['"](?P<module>[^'"]+)['"]""")


def is_type_only_clause(clause):
    """判断 import 子句是否**纯类型**（编译期擦除，不构成运行时依赖）。

    三种写法都要认：
      * `import type { A } from ...`        —— 整条 type
      * `import { type A } from ...`        —— 内联 type 说明符（早期漏判，误杀）
      * `import { type A, type B } from ...`
    而 `import D, { type A } from ...` 里的 D 是值，不算纯类型。
    """
    clause = clause.strip()
    if clause.startswith('type ') or clause == 'type':
        return True
    named = re.fullmatch(r'\{(.*)\}', clause, re.S)
    if not named:
        return False
    members = [m.strip() for m in named.group(1).split(',') if m.strip()]
    return bool(members) and all(m.startswith('type ') or m == 'type' for m in members)


def adapter_import(text):
    """本文件对 Electron 侧适配器是哪种导入：'value' / 'type' / None。"""
    verdict = None
    for match in IMPORT_FROM.finditer(text):
        module = match.group('module')
        if not module.startswith('./'):
            continue
        stem = re.sub(r'\.tsx?$', '', module[2:])
        if stem not in ADAPTER_MODULES:
            continue
        if is_type_only_clause(match.group('clause')):
            verdict = verdict or 'type'
        else:
            return 'value'
    return verdict


RUNTIME_ACCESS = re.compile(r"""\bctx\.desktopRuntime\.|(?<![\w.])runtime\.(?:"""
                             + '|'.join(DESKTOP_RUNTIME_METHODS) + r""")\(""")


def strip_comments(text):
    r"""去掉注释，供「代码里是否真的引用了 X」这类检查使用。

    **必须字符串感知**：早期版本直接用 `//[^\n]*` 匹配，`const u = "http://x"`
    里的 `//` 会把同一行后面的内容整段吃掉 —— 于是
    `const u = "http://x"; ctx.desktopRuntime.show()` 这种写法里的真实引用
    会被静默漏掉，正是这个校验器本该抓的东西。

    仍然不是完整词法分析：**正则字面量**里的 `//` 不处理（如 `/a\/\/b/`）。
    残留风险是漏报而非误报，可接受。
    """
    out = []
    i, n = 0, len(text)
    quote = None
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ''
        if quote is None:
            if ch in '"\'`':
                quote = ch
                out.append(ch)
                i += 1
                continue
            if ch == '/' and nxt == '/':
                while i < n and text[i] != '\n':
                    i += 1
                continue
            if ch == '/' and nxt == '*':
                i += 2
                while i < n and not (text[i] == '*' and i + 1 < n and text[i + 1] == '/'):
                    i += 1
                i += 2
                continue
            out.append(ch)
            i += 1
            continue
        # 字符串内部
        if ch == '\\':
            out.append(ch)
            if i + 1 < n:
                out.append(text[i + 1])
            i += 2
            continue
        if ch == quote:
            quote = None
        out.append(ch)
        i += 1
    return ''.join(out)


def read_text(path):
    """读源码；解码失败不静默吞掉，明确回报。"""
    try:
        return path.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        return path.read_text(encoding='utf-8', errors='replace')


# 客户端里实现「桌面自有窗口外壳」的信号：拖动区/标题栏/自定义 frame。
# 这类文件虽然是浏览器代码，但脱离 Electron 外壳就没有意义。
DESKTOP_CHROME_SIGNAL = re.compile(
    r'-webkit-app-region|data-dsh-desktop-frame|dragRegion|titlebarInset|dshDesktop_sidebarCol'
)


def detect_source_root(root):
    """探测源包实际存放源码的顶层目录名。

    写死 `src/lib/dist` 是不够的：源码放在 `source/` 之类的目录时前缀规则会全部失效，
    `source/windows-acl-runner.ts` 会被误判成「直接可移植」。
    """
    counts = {}
    for path in iter_sources(root):
        parts = path.relative_to(root).parts
        if len(parts) > 1:
            counts[parts[0]] = counts.get(parts[0], 0) + 1
    if not counts:
        return None
    name, count = max(counts.items(), key=lambda kv: kv[1])
    total = sum(counts.values())
    # 只有明显集中在一个目录下才认定它是源码根，避免把普通子目录误当根
    return name if count > 1 and count * 2 >= total else None


def strip_source_root(rel, source_root=None):
    """源包内路径去掉自己的根目录（如 `src/`），让前缀规则能匹配到目录名。"""
    parts = rel.split('/')
    if len(parts) <= 1:
        return rel
    if source_root is not None:
        return '/'.join(parts[1:]) if parts[0] == source_root else rel
    return '/'.join(parts[1:]) if parts[0] in ('src', 'lib', 'dist') else rel


def classify_file(rel, text, source_root=None):
    """把一个源文件判到某个桶，并给出理由与人工适配点。"""
    reasons = []
    inner = strip_source_root(rel, source_root)

    # 桌面自有窗口外壳的信号与目录无关：只要在实现拖动区/标题栏/自定义 frame，
    # 脱离 Electron 外壳就没有意义。早期只在 client/ 下判，别处的同样代码会漏判。
    if DESKTOP_CHROME_SIGNAL.search(text):
        reasons.append('实现桌面自有窗口外壳（拖动区/标题栏/自定义 frame）')
        return B1, reasons, []

    for prefix, bucket in FIXED_BUCKETS:
        if inner.startswith(prefix):
            reasons.append(f'路径前缀 {prefix}')
            return bucket, reasons, []

    if ELECTRON_IMPORT.search(text):
        reasons.append('值导入 electron')
        return B1, reasons, []

    adapter = adapter_import(text)
    if adapter == 'value':
        reasons.append('值导入 Electron 侧适配器')
        return B1, reasons, []
    if adapter == 'type' and not RUNTIME_ACCESS.search(text):
        # 类型导入编译期擦除；且没有运行时访问 → 不构成依赖
        reasons.append('仅类型导入适配器（编译期擦除），无运行时访问')

    hot = []
    for lineno, line in enumerate(text.splitlines(), start=1):
        for match in re.finditer(r"""\bctx\.desktopRuntime\.([A-Za-z_]\w*)""", line):
            hot.append((lineno, f'ctx.desktopRuntime.{match.group(1)}'))
        for match in re.finditer(r"""(?<![\w.])(?:runtime|desktopRuntime)\.(""" +
                                 '|'.join(DESKTOP_RUNTIME_METHODS) + r""")\(""", line):
            hot.append((lineno, match.group(1)))
    if hot:
        # 去重同一行的重复命中
        seen, unique = set(), []
        for lineno, api in hot:
            if (lineno, api) not in seen:
                seen.add((lineno, api))
                unique.append((lineno, api))
        reasons.append(f'{len(unique)} 处 native adapter 调用需人工映射')
        return A2, reasons, unique

    return A1, reasons, []


def iter_sources(root):
    for path in sorted(root.rglob('*')):
        if not path.is_file():
            continue
        if path.suffix not in ('.ts', '.tsx'):
            continue
        if any(part in ('node_modules', '.git', 'tests', 'scripts') for part in path.parts):
            continue
        # 包根的构建配置不是插件源码
        if re.fullmatch(r'(?:tsdown|vite[\w.-]*|vitest|rollup)\.config\.tsx?', path.name):
            continue
        yield path


LOCAL_RELATIVE = re.compile(r'^\.{1,2}/')


def local_value_imports(text):
    """本文件**值**导入了哪些同包文件（保留相对路径原样，不做归一）。

    必须支持 `../`：客户端文件普遍用 `../renderer-boot-contract.ts` 引用上级目录，
    早期只匹配 `./` 会漏掉整类跨目录引用。也必须排除纯类型导入 ——
    类型导入编译期擦除，不构成运行时依赖。
    """
    found = set()
    for match in IMPORT_FROM.finditer(text):
        module = match.group('module')
        if not LOCAL_RELATIVE.match(module):
            continue
        if is_type_only_clause(match.group('clause')):
            continue
        found.add(module)
    return found


def resolve_local(spec, base_dir, known):
    """把一条相对导入解析成包内路径；解析不到返回 None。

    先按完整路径匹配（含补全 .ts/.tsx），再退回基名 —— 基名只有在**唯一**时才接受，
    否则不同目录下的同名文件会互相误挂。
    """
    joined = os.path.normpath(os.path.join(base_dir, spec)).replace(os.sep, '/')
    for candidate in (joined, joined + '.ts', joined + '.tsx'):
        if candidate in known:
            return candidate
    stem = os.path.basename(joined)
    hits = [k for k in known if os.path.basename(k) == stem]
    return hits[0] if len(hits) == 1 else None


def analyze(root):
    """返回逐文件分析结果与汇总。"""
    rows = []
    texts = {}
    for path in iter_sources(root):
        rel = str(path.relative_to(root)).replace(os.sep, '/')
        texts[rel] = read_text(path)

    source_root = detect_source_root(root)

    # 第一轮：按文件自身特征分类
    for rel, text in texts.items():
        bucket, reasons, hot = classify_file(rel, text, source_root)
        rows.append({
            'file': rel,
            'lines': len(text.splitlines()),
            'bucket': bucket,
            'reasons': reasons,
            'hotspots': hot,
            'shell_coupled': False,
        })

    # 第二轮：被 Electron 外壳文件值引用的，也是「需适配」——
    # 它们本身不碰适配器，但只服务于桌面外壳，单独搬过来没有宿主。
    known = set(texts)
    shell_rows = [r for r in rows if r['bucket'] == B1]
    referenced = set()
    for shell in shell_rows:
        base = os.path.dirname(shell['file'])
        for spec in local_value_imports(texts[shell['file']]):
            target = resolve_local(spec, base, known)
            if target and target != shell['file']:
                referenced.add(target)

    # 只打标记、不改桶：`mask-secrets.ts` 这类纯函数被 B1 的 logger 引用，
    # 但它本身不需要任何适配，判成 A2 是错的。标记交给人工判断。
    for row in rows:
        row['shell_coupled'] = row['file'] in referenced

    summary = {b: {'files': 0, 'lines': 0} for b in BUCKET_ORDER}
    for row in rows:
        summary[row['bucket']]['files'] += 1
        summary[row['bucket']]['lines'] += row['lines']
    return rows, summary


def render_report(root, rows, summary):
    total_files = len(rows)
    total_lines = sum(r['lines'] for r in rows)
    portable = [A1, A2, CLIENT]
    port_files = sum(summary[b]['files'] for b in portable)
    port_lines = sum(summary[b]['lines'] for b in portable)

    out = []
    out.append(f'# dsh-desktop 移植分析：`{root.name}`\n')
    out.append(f'源包 {total_files} 个 `.ts/.tsx`，{total_lines:,} 行\n')
    out.append('## 汇总\n')
    out.append('| 判定 | 文件 | 行数 |')
    out.append('|---|---:|---:|')
    for bucket in BUCKET_ORDER:
        out.append(f"| {bucket} | {summary[bucket]['files']} | {summary[bucket]['lines']:,} |")
    pct = (port_lines * 100 // total_lines) if total_lines else 0
    out.append(f'\n**可移植 {port_files} 文件 / {port_lines:,} 行（约 {pct}%）**；'
               f'其余 {total_files - port_files} 文件结构性不可移植。\n')

    for bucket in BUCKET_ORDER:
        rows_b = [r for r in rows if r['bucket'] == bucket]
        if not rows_b:
            continue
        out.append(f'## {bucket}\n')
        out.append(f'{BUCKET_NOTE[bucket]}\n')
        out.append('| 文件 | 行数 | 判定理由 | 人工适配点 |')
        out.append('|---|---:|---|---|')
        for row in sorted(rows_b, key=lambda r: -r['lines']):
            reason = '；'.join(row['reasons']) or '—'
            if row.get('shell_coupled'):
                reason += '；⚠ 被 Electron 外壳引用，单独使用前确认无隐藏耦合'
            hot = '、'.join(f"L{n} `{api}`" for n, api in row['hotspots'][:4]) or '—'
            if len(row['hotspots']) > 4:
                hot += f" 等 {len(row['hotspots'])} 处"
            out.append(f"| `{row['file']}` | {row['lines']} | {reason} | {hot} |")
        out.append('')

    out.append('## 使用提示\n')
    out.append('- 分类只解决「能不能搬」，不解决「搬过来干什么」。动手前先确认 DSHA '
               '是否已有等价实现 —— 本项目里设置页、目录选择、LAN 地址过滤都属于'
               '「DSHA 已覆盖」，移植过去只是重复。\n')
    out.append('- `A2` 的人工适配点应集中替换成一个 DSHA 桥适配器，而不是逐处改写业务逻辑。\n')
    out.append('- `A1` 里带 ⚠ 的文件本身无依赖，但只被 Electron 外壳引用过；'
               '单独搬运前确认它不隐含桌面语义。\n')
    out.append('- 生成的骨架**不含业务代码**，`lib/index.js` 里的 TODO 是分类结论的落地清单。\n')
    return '\n'.join(out) + '\n'


# ── 骨架生成 ──────────────────────────────────────────────────────────────

DEFAULT_COMPAT = '^0.1.7-rc.2 || ^0.2.0-rc.2'


def scaffold(args, rows, summary):
    name = args.name
    out_dir = Path(args.out) / name
    (out_dir / 'lib').mkdir(parents=True, exist_ok=True)

    selected = []
    for rel in args.module or []:
        norm = rel.replace(os.sep, '/').lstrip('./')
        match = next((r for r in rows if r['file'] == norm), None)
        if match is None:
            raise SystemExit(f'ERROR: --module 指定的文件不在源包里：{rel}')
        selected.append(match)
    if not selected:
        # 未指定时，取可移植桶里最大的几个作为默认建议，但不直接生成
        selected = sorted((r for r in rows if r['bucket'] in (A1, A2)),
                          key=lambda r: -r['lines'])[:5]

    wants_client = args.client or any(r['bucket'] == CLIENT for r in selected)

    exports = {
        '.': './lib/index.js',
        './package.json': './package.json',
        './cordis.patch.yml': './cordis.patch.yml',
    }
    if wants_client:
        exports['./client'] = './lib/client.js'

    manifest = {
        'name': name,
        'version': '0.1.0',
        'description': args.description or f'移植自 dsh-desktop 的插件（{name}）',
        'type': 'module',
        'main': 'lib/index.js',
        'exports': exports,
        'dsh': {
            'bundle': {'patch': './cordis.patch.yml'},
            **({'client': {'platform': 'web'}} if wants_client else {}),
        },
        'peerDependencies': {
            # DSHA 的兼容性判据读这个字段；只声明实测过的范围。
            '@deepseek-ai/dsh': DEFAULT_COMPAT,
            '@deepseek-ai/cordis': '^4.0.1',
        },
        'license': 'MIT',
    }
    (out_dir / 'package.json').write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    patch = [
        f'# {name} bundle patch: one host-side plugin row.',
        '#',
        '# 模块级不声明 inject：缺服务时只让本插件的子作用域不激活，不会把整棵插件树',
        '# 拖成 PENDING（那是一种不报错、也不干活的静默失效）。',
        '#',
        '# 若需要用户可配置项，在 lib/index.js 里声明带 .volatile() 的 Config ——',
        '# Loader entry id 就是设置命名空间，官方「插件」页会自动渲染表单，',
        '# 不需要自己写路由和客户端界面。',
        '- insert:',
        f"    - id: {name}",
        f"      name: '{name}'",
        '',
    ]
    (out_dir / 'cordis.patch.yml').write_text('\n'.join(patch), encoding='utf-8')

    todos = []
    for row in selected:
        if row['bucket'] == A1:
            todos.append(f"- [ ] `{row['file']}`（{row['lines']} 行）：纯逻辑，可直接搬运。")
        elif row['bucket'] == A2:
            spots = '、'.join(f"L{n} `{api}`" for n, api in row['hotspots'][:6]) or '（见报告）'
            todos.append(f"- [ ] `{row['file']}`（{row['lines']} 行）：需把 native adapter "
                         f"替换成 DSHA 桥适配器。调用点：{spots}")
        else:
            todos.append(f"- [ ] `{row['file']}`（{row['lines']} 行）：{row['bucket']} —— "
                         f"需重写为 DSHA 侧实现，不能直接搬。")
    todo_lines = todos or ['- [ ] （未指定 --module，先跑一次分类报告）']
    # 逐行加注释前缀：TODO 清单会被嵌进 apply() 的注释里，
    # 只给首行加 `//` 会让生成的文件变成语法错误。
    todo_text = '\n'.join('  // ' + line for line in todo_lines)

    index_js = f'''/**
 * {name}
 *
 * 由 tools/dsh-desktop-port.py 生成的**骨架** —— 不含业务代码。
 * 分类结论与人工适配点见随附的移植报告。
 */

/** 与 package.json 的 name 一致，也是 cordis.patch.yml 里的 id。 */
export const name = '{name}'

/**
 * 模块级不声明 inject。缺服务时只让本插件的子作用域不激活，
 * 不会把整棵插件树拖成 PENDING。确有硬依赖时再按需加。
 */
export const inject = []

// 需要用户可配置项时，声明带 .volatile() 的 Config（Loader entry id 即设置命名空间）：
//
//   import z from '@deepseek-ai/schemastery'
//   export const Config = z.object({{
//     enabled: z.boolean().default(true).volatile(),
//   }})
//
// 用户改动通过 loader/volatile-update 到达，字段是活引用，读时用 config.x.get()：
//
//   ctx.on('loader/volatile-update', () => {{ /* 重新读 config */ }})

/**
 * 插件入口。
 * @param {{import('@deepseek-ai/cordis').Context}} ctx
 * @param {{object}} [config]
 */
export function apply(ctx, config) {{
  // TODO 移植清单：
{todo_text}
  // TODO 结束
}}
'''
    (out_dir / 'lib' / 'index.js').write_text(index_js, encoding='utf-8')

    if wants_client:
        client_js = f'''/**
 * {name} —— 浏览器半边。
 *
 * 客户端模块必须用 window.__ModuleLoader__.load({{ id, factory }}) 注册格式，
 * 宿主按 /plugins/{name}/client.js 提供给浏览器。factory 收到的 require
 * 按包名解析其它客户端模块。
 */
window.__ModuleLoader__.load({{
  id: '{name}',
  factory: (require) => {{
    var module = {{ exports: {{}} }};
    var exports = module.exports;
    Object.defineProperty(exports, Symbol.toStringTag, {{ value: 'Module' }});

    /** 声明本模块依赖的客户端服务（如 'loader'、'configForms'、'slots'）。 */
    const inject = [];

    /** @param {{object}} ctx 客户端 Cordis 上下文 */
    function apply(ctx) {{
      // TODO 浏览器侧逻辑
    }}

    exports.apply = apply;
    exports.inject = inject;
    return module.exports;
  }},
}});
'''
        (out_dir / 'lib' / 'client.js').write_text(client_js, encoding='utf-8')

    return out_dir, selected, wants_client


# ── 契约校验 ──────────────────────────────────────────────────────────────

def find_runtime(explicit):
    if explicit:
        return Path(explicit)
    env = os.environ.get('DSH_RUNTIME')
    if env:
        return Path(env)
    for candidate in ('/usr/local/lib/node_modules/@deepseek-ai/dsh',
                      '/root/.dsh/node_modules/@deepseek-ai/dsh'):
        if Path(candidate).is_dir():
            return Path(candidate)
    return None


def accepts_version(runtime, version, requirement):
    """用 DSHA 自己的 semver 实现判定，避免自造语义。"""
    for rel in ('app/src/main/assets/plugin-semver.cjs',):
        candidate = Path(__file__).resolve().parents[1] / rel
        if candidate.is_file():
            semver_path = candidate
            break
    else:
        semver_path = None
    if semver_path is None or runtime is None:
        return None
    dsh_pkg = runtime / 'package.json'
    if not dsh_pkg.is_file():
        return None
    script = (
        "const s=require(process.argv[1]);const f=s.acceptsVersion||s.accepts||s.satisfies;"
        "process.stdout.write(String(f(process.argv[2],process.argv[3])))"
    )
    try:
        out = subprocess.run(
            ['node', '-e', script, str(semver_path), version, requirement],
            capture_output=True, text=True, timeout=30)
    except (OSError, subprocess.SubprocessError):
        return None
    return out.stdout.strip() == 'true' if out.returncode == 0 else None


def verify(plugin_dir, runtime):
    """校验插件目录是否满足 DSHA 的加载契约。返回 (errors, warnings, notes)。"""
    errors, warnings, notes = [], [], []
    manifest_path = plugin_dir / 'package.json'
    if not manifest_path.is_file():
        return [f'缺少 package.json：{manifest_path}'], warnings, notes

    try:
        manifest = json.loads(manifest_path.read_text(encoding='utf-8'))
    except ValueError as exc:
        return [f'package.json 不是合法 JSON：{exc}'], warnings, notes

    name = manifest.get('name', '')
    if not re.fullmatch(r'(?:@[a-z0-9][a-z0-9._-]*/)?[a-z0-9][a-z0-9._-]*', name or ''):
        errors.append(f'包名不是合法 npm 名称：{name!r}')
    elif len(name) > 214:
        # DSHA 的 valid_name 上限；超了会被原生侧拒绝，工具不该放过
        errors.append(f'包名超过 214 字符上限（{len(name)}）：DSHA 会拒绝该名称')
    if not manifest.get('version'):
        errors.append('缺少 version')
    if manifest.get('type') != 'module':
        warnings.append('type 不是 "module"；DSHA 插件按 ESM 加载')

    entry = manifest.get('main')
    if not entry:
        errors.append('缺少 main（入口）')
    elif not (plugin_dir / entry).is_file():
        errors.append(f'main 指向的入口不存在：{entry}')

    patch_rel = (manifest.get('dsh') or {}).get('bundle', {}).get('patch')
    if not patch_rel:
        errors.append('缺少 dsh.bundle.patch —— DSHA 靠它把插件行插进 profile 树')
    elif not (plugin_dir / patch_rel).is_file():
        errors.append(f'dsh.bundle.patch 指向的文件不存在：{patch_rel}')

    # exports 可以是字符串（包只有一个入口）或对象，两种都是合法 npm 写法。
    # 早期直接当对象用，字符串形式会让 verify 抛 AttributeError 而整体挂掉。
    raw_exports = manifest.get('exports')
    if isinstance(raw_exports, str):
        exports = {'.': raw_exports}
        warnings.append('exports 是字符串形式，未导出 ./package.json 与 ./cordis.patch.yml；'
                        'DSHA 工具链按子路径解析时会失败')
    elif isinstance(raw_exports, dict):
        exports = raw_exports
        for required in ('./package.json', './cordis.patch.yml'):
            if required not in exports:
                warnings.append(f'exports 未导出 {required}；工具链按子路径解析时会失败')
    else:
        exports = {}
        warnings.append('未声明 exports；DSHA 工具链按子路径解析时会失败')

    client_decl = (manifest.get('dsh') or {}).get('client')
    if client_decl:
        if client_decl.get('platform') != 'web':
            errors.append('dsh.client.platform 必须是 "web"')
        client_rel = exports.get('./client')
        if isinstance(client_rel, dict):
            client_rel = client_rel.get('default')
        if not client_rel:
            errors.append('声明了 dsh.client 但 exports 里没有 "./client"')
        elif not (plugin_dir / client_rel).is_file():
            errors.append(f'exports["./client"] 指向的文件不存在：{client_rel}')
        else:
            head = read_text(plugin_dir / client_rel)[:400]
            if 'window.__ModuleLoader__.load(' not in head:
                errors.append(f'{client_rel} 未使用 window.__ModuleLoader__.load({{id, factory}}) '
                              f'注册格式；宿主会报 "loaded without registering"')
            else:
                notes.append('客户端模块注册格式正确')

    # 入口必须能被 Node 解析 —— 生成器/手改都可能产出语法错误的文件，
    # 而语法错误在 DSH 里表现为 "failed to import"，很难从日志定位到行。
    for rel in [entry] + ([exports.get('./client')] if client_decl else []):
        if isinstance(rel, dict):
            rel = rel.get('default')
        if not rel or not (plugin_dir / rel).is_file():
            continue
        try:
            check = subprocess.run(['node', '--check', str(plugin_dir / rel)],
                                   capture_output=True, text=True, timeout=60)
        except (OSError, subprocess.SubprocessError):
            notes.append(f'未能对 {rel} 做语法检查（node 不可用）')
            continue
        if check.returncode != 0:
            first = (check.stderr or '').strip().splitlines()
            detail = next((l for l in first if 'Error' in l or 'error' in l), first[0] if first else '')
            errors.append(f'{rel} 语法检查失败：{detail}')

    # 入口里不该出现 Electron
    if entry and (plugin_dir / entry).is_file():
        text = read_text(plugin_dir / entry)
        if ELECTRON_IMPORT.search(text):
            errors.append(f'{entry} 值导入了 electron —— DSHA 容器里没有 Electron，加载期即抛错')
        inject_match = re.search(r'export\s+const\s+inject\s*=\s*\[([^\]]*)\]', text)
        if inject_match and inject_match.group(1).strip():
            warnings.append('入口声明了非空模块级 inject；缺任一服务时整条 entry 会停在 '
                            'PENDING（不报错也不干活）。确有硬依赖才保留')
        # 只看代码，不看注释：移植时通常会在注释里记录「原版用的是 desktopRuntime」，
        # 那是说明而不是依赖。
        code = strip_comments(text)
        if re.search(r'\bdesktopRuntime\b', code):
            errors.append(f'{entry} 在代码里引用了 desktopRuntime —— DSHA 里不存在该服务')

    # 兼容范围：用 DSHA 自己的 semver 判定
    compat = (manifest.get('peerDependencies') or {}).get('@deepseek-ai/dsh') \
        or (manifest.get('engines') or {}).get('dsh')
    if not compat:
        warnings.append('未声明 dsh 兼容范围（peerDependencies["@deepseek-ai/dsh"]）；'
                        'DSHA 会显示"兼容性需要确认"')
    else:
        installed = None
        if runtime is not None and (runtime / 'package.json').is_file():
            installed = json.loads((runtime / 'package.json').read_text(encoding='utf-8')).get('version')
        if installed:
            verdict = accepts_version(runtime, installed, compat)
            if verdict is True:
                notes.append(f'兼容范围 {compat} 接受当前 dsh {installed}')
            elif verdict is False:
                errors.append(f'兼容范围 {compat} 不接受当前 dsh {installed}')
            else:
                notes.append(f'兼容范围 {compat}（未能判定，缺 semver 实现）')

    return errors, warnings, notes


# ── 命令行 ────────────────────────────────────────────────────────────────

def cmd_port(args):
    root = Path(args.source).resolve()
    if not root.is_dir():
        raise SystemExit(f'ERROR: 源包目录不存在：{root}')

    rows, summary = analyze(root)
    if not rows:
        raise SystemExit(f'ERROR: 源包里没有 .ts/.tsx 文件：{root}')

    report = render_report(root, rows, summary)
    if args.report:
        report_path = Path(args.report)
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(report, encoding='utf-8')
        print(f'已写出分析报告：{report_path}')
    elif not args.name:
        print(report)

    if args.json:
        json_path = Path(args.json)
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_path.write_text(json.dumps(
            {'source': str(root), 'summary': summary, 'files': rows},
            ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(f'已写出分析数据：{json_path}')

    if args.name:
        out_dir, selected, wants_client = scaffold(args, rows, summary)
        print(f'已生成插件骨架：{out_dir}')
        print(f'  移植单元 {len(selected)} 个；客户端半边：{"是" if wants_client else "否"}')
        print('  下一步：')
        print(f'    1) 按报告里的适配点填 lib/index.js')
        print(f'    2) python3 tools/dsh-desktop-port.py verify {out_dir}')
        print(f'    3) 把骨架打包后用 dsha-plugin import 安装（或本地导入）')
    return 0


def cmd_verify(args):
    plugin_dir = Path(args.plugin).resolve()
    if not plugin_dir.is_dir():
        raise SystemExit(f'ERROR: 插件目录不存在：{plugin_dir}')
    runtime = find_runtime(args.runtime)
    errors, warnings, notes = verify(plugin_dir, runtime)

    print(f'校验：{plugin_dir}')
    if runtime:
        print(f'运行时：{runtime}')
    for note in notes:
        print(f'  ok   {note}')
    for warning in warnings:
        print(f'  warn {warning}')
    for error in errors:
        print(f'  FAIL {error}')
    print()
    if errors:
        print(f'{len(errors)} 项不满足 DSHA 加载契约')
        return 1
    print(f'满足 DSHA 加载契约（{len(warnings)} 项警告）')
    return 0


def main():
    parser = argparse.ArgumentParser(
        description='把 dsh-desktop 插件移植成 DSHA 插件：分类 + 脚手架 + 契约校验')
    sub = parser.add_subparsers(dest='command', required=True)

    port = sub.add_parser('port', help='分析源包并（可选）生成插件骨架')
    port.add_argument('source', help='dsh-desktop 里的源包目录')
    port.add_argument('--name', help='生成骨架时的插件名（省略则只出报告）')
    port.add_argument('--module', action='append', default=[],
                      help='要移植的模块（相对源包，可重复）；省略则取可移植桶里最大的几个')
    port.add_argument('--client', action='store_true', help='强制生成客户端半边')
    port.add_argument('--description', help='插件描述')
    port.add_argument('--out', default='.', help='骨架输出目录（默认当前目录）')
    port.add_argument('--report', help='把 Markdown 报告写到这个路径')
    port.add_argument('--json', help='把分析数据写到这个路径')
    port.set_defaults(func=cmd_port)

    check = sub.add_parser('verify', help='校验插件目录是否满足 DSHA 加载契约')
    check.add_argument('plugin', help='插件目录')
    check.add_argument('--runtime', help='DSH 安装目录（默认自动探测）')
    check.set_defaults(func=cmd_verify)

    args = parser.parse_args()
    return args.func(args)


if __name__ == '__main__':
    sys.exit(main())
