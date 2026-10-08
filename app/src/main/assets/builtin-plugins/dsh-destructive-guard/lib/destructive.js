/**
 * DSHA 破坏性命令识别：纯函数、零依赖、零 I/O。
 *
 * 文件系统事实（路径是否存在、是文件还是目录、通配展开成什么、脚本文件内容）
 * 全部由调用方以 `probe` / `glob` / `readFile` 注入 —— 同一份逻辑既能在容器里
 * 真跑，也能在单测里用假 FS 打桩，不需要 JUnit、不需要构建。
 *
 * 结论只用于"多问用户一句确认"，绝不用来自动放行。识别器看不见的动作不在本模块
 * 职责内（覆盖边界写在 docs/security-model.md），不会因为"看起来无害"被静默放过 ——
 * 放行决定始终由宿主审批服务按 `ask`/`never` 策略做，且 `never` 在本守卫里是硬拒绝。
 *
 * @module dsh-destructive-guard/destructive
 */

/** 严重度排序。 */
const SEVERITY_RANK = { none: 0, medium: 1, high: 2, critical: 3 };

/** 一旦被 `rm -rf` 打中就等于毁掉运行环境的根（或它的通配前缀）。 */
const PROTECTED_ROOTS = [
  '/', '/root', '/root/.dsh', '/home', '/usr', '/usr/local', '/etc', '/var', '/bin',
  '/sbin', '/lib', '/lib64', '/boot', '/opt', '/srv', '/tmp', '/data', '/system',
  '/vendor', '/sdcard', '/storage', '/proc', '/sys', '/dev',
];

/** 写到这些目标等于丢弃内容，不算破坏。 */
const VOID_TARGETS = new Set([
  '/dev/null', '/dev/zero', '/dev/stdout', '/dev/stderr', '/dev/tty', '/dev/fd/1', '/dev/fd/2',
]);

/** 指向块设备/裸分区的目标：覆盖即不可逆，直接 critical。 */
const DEVICE_PATTERN = /^\/dev\/(?:sd|mmcblk|nvme|disk|block|vd|hd|loop|mapper\/)/;

/** 临时区/黑洞：`mv` 到这些位置等于再也找不回来。 */
const TEMP_PREFIXES = ['/tmp', '/var/tmp', '/dev/shm', '/run'];

/** 需要确认的程序（含 `git`/`find`/`rsync` 这类靠子命令生效的）。 */
export const DESTRUCTIVE_PROGRAMS = new Set([
  'rm', 'rmdir', 'unlink', 'shred', 'truncate', 'dd', 'mv', 'cp', 'tee', 'rsync', 'find', 'git',
]);

/** 吞掉参数、真正命令在后面的包装器。 */
const WRAPPERS = new Set([
  'sudo', 'doas', 'nice', 'ionice', 'stdbuf', 'setsid', 'nohup', 'time', 'env',
  'command', 'builtin', 'exec', 'timeout', 'busybox', 'toybox', 'xargs',
]);

/** 交互式 shell：`-c '...'` 里的正文要递归识别。 */
const SHELLS = new Set(['sh', 'bash', 'dash', 'ash', 'zsh', 'ksh', 'mksh']);

/** 包装器自己需要吃一个值的选项。 */
const WRAPPER_VALUE_FLAGS = {
  nice: new Set(['-n']),
  ionice: new Set(['-c', '-n', '-p']),
  stdbuf: new Set(['-i', '-o', '-e']),
  sudo: new Set(['-u', '-g', '-p', '-C', '-h', '-r', '-t', '-U', '-D', '-R']),
  timeout: new Set(['-k', '-s', '--signal', '--kill-after']),
  xargs: new Set(['-n', '-I', '-i', '-P', '-s', '-E', '-d', '--max-args', '--replace', '--delimiter']),
  env: new Set(['-u', '--unset']),
};

/** 空结果。 */
function emptyResult() {
  return { destructive: false, severity: 'none', findings: [], targets: [], total: 0, unknownTargets: false };
}

/** 取 basename（不 import node:path，保持模块在任何 JS 运行时可用）。 */
function baseName(value) {
  const text = String(value);
  const cut = text.lastIndexOf('/');
  return cut < 0 ? text : text.slice(cut + 1);
}

/** POSIX 路径归一：折叠 `//` 与 `.`/`..`，保留结尾斜杠。 */
export function normalizePosix(value) {
  const text = String(value);
  const trailing = text.length > 1 && text.endsWith('/');
  const absolute = text.startsWith('/');
  const parts = [];
  for (const part of text.split('/')) {
    if (part === '' || part === '.') continue;
    if (part === '..') {
      if (parts.length > 0 && parts[parts.length - 1] !== '..') parts.pop();
      else if (!absolute) parts.push('..');
      continue;
    }
    parts.push(part);
  }
  const body = (absolute ? '/' : '') + parts.join('/');
  if (body === '') return absolute ? '/' : '.';
  return trailing && body !== '/' ? body + '/' : body;
}

function hasGlob(value) {
  return /[*?[{]/.test(value);
}

function isProtected(path) {
  const clean = path.length > 1 ? path.replace(/\/+$/, '') : path;
  return PROTECTED_ROOTS.includes(clean);
}

/**
 * 通配模式（或普通路径）是否落在受保护的根上：`/`、`/*`、`/root`、`/root/*`。
 * 只看第一个通配符之前的前缀；`/root/work/*` 不算（那是用户自己的工作区）。
 */
function isProtectedPattern(path) {
  if (!hasGlob(path)) return isProtected(path);
  const cut = path.search(/[*?[{]/);
  const prefix = (cut < 0 ? path : path.slice(0, cut)).replace(/\/+$/, '');
  return prefix === '' ? true : isProtected(prefix);
}

function isVoidTarget(path) {
  return VOID_TARGETS.has(path) || path.startsWith('/dev/fd/');
}

function isDevice(path) {
  return DEVICE_PATTERN.test(path);
}

function isTempPath(path) {
  if (path === '/tmp' || path === '/dev/shm' || path === '/run') return true;
  return TEMP_PREFIXES.some((prefix) => path.startsWith(prefix + '/'));
}

/** 是不是 cwd 本身或 cwd 的祖先（删它等于把工作区连根拔掉）。 */
function isAncestorOfCwd(path, cwd) {
  if (hasGlob(path) || cwd === '' || path === '') return false;
  return cwd === path || cwd.startsWith(path.endsWith('/') ? path : path + '/');
}

/** `--size=0` → `['--size','0']`；`--force` → `['--force', null]`。 */
function splitLongOption(text) {
  const cut = text.indexOf('=');
  return cut < 0 ? [text, null] : [text.slice(0, cut), text.slice(cut + 1)];
}

/**
 * 把一条简单命令的单词切成 短选项 / 长选项 / 操作数，`--` 之后全是操作数。
 * @param {Array<object>} args - 单词 token。
 * @param {Set<string>} valueFlags - 需要吃值的短选项字母。
 */
function parseFlags(args, valueFlags = new Set()) {
  const short = new Set();
  const shortValues = new Map();
  const long = new Map();
  const operands = [];
  let endOfOptions = false;
  for (const token of args) {
    const text = String(token.value);
    if (endOfOptions || text === '' || text === '-') {
      operands.push(token);
      continue;
    }
    if (text === '--') {
      endOfOptions = true;
      continue;
    }
    if (token.dynamic) {
      operands.push(token);
      continue;
    }
    if (text.startsWith('--')) {
      const [name, value] = splitLongOption(text);
      long.set(name, value);
      continue;
    }
    if (text.startsWith('-') && text.length > 1) {
      const body = text.slice(1);
      for (let index = 0; index < body.length; index += 1) {
        const char = body[index];
        short.add(char);
        if (valueFlags.has(char) && index + 1 < body.length) {
          shortValues.set(char, body.slice(index + 1));
          break;
        }
      }
      continue;
    }
    operands.push(token);
  }
  return { short, shortValues, long, operands };
}

/** `-s 0` / `-s0` / `--size=0` / `--size 0` 四种写法的取值；缺省返回 undefined。 */
function optionValue(args, shortName, longName) {
  let shortAttached;
  let longAttached;
  let shortStandalone = false;
  let longStandalone = false;
  for (const token of args) {
    if (token.dynamic) continue;
    const text = String(token.value);
    if (text === '-' + shortName) shortStandalone = true;
    else if (text.startsWith('-' + shortName) && !text.startsWith('--') && text.length > shortName.length + 1) {
      shortAttached = text.slice(shortName.length + 1);
    } else if (text === longName) longStandalone = true;
    else if (text.startsWith(longName + '=')) longAttached = text.slice(longName.length + 1);
  }
  if (shortAttached !== undefined) return shortAttached;
  if (longAttached !== undefined) return longAttached;
  const names = [];
  if (shortStandalone) names.push('-' + shortName);
  if (longStandalone) names.push(longName);
  for (const name of names) {
    const index = args.findIndex((token) => String(token.value) === name);
    if (index >= 0 && index + 1 < args.length) return String(args[index + 1].value);
  }
  return undefined;
}

// #region 词法：只做"够用的 shell 切词"，不是完整 shell 解析器

/** 从命令替换/反引号正文里提取"程序名提示"：`$(which rm)` 的正文点名了 rm。 */
function programHints(body) {
  const hints = [];
  for (const token of String(body).split(/[\s;&|()<>]+/)) {
    const name = baseName(token.replace(/^['"]|['"]$/g, ''));
    if (DESTRUCTIVE_PROGRAMS.has(name) || name === 'sh' || name === 'bash') hints.push(name);
  }
  return hints;
}

/** ANSI-C 转义（`\x6d` / `\155`）：用来抓 `$'\x72\x6d'` 这类混淆。 */
function decodeAnsiC(text, start) {
  const char = text[start];
  if (char === 'x') {
    const hex = /^[0-9A-Fa-f]{1,2}/.exec(text.slice(start + 1));
    if (hex !== null) return { value: String.fromCharCode(parseInt(hex[0], 16)), end: start + 1 + hex[0].length };
  }
  if (char !== undefined && /[0-7]/.test(char)) {
    const octal = /^[0-7]{1,3}/.exec(text.slice(start));
    if (octal !== null) return { value: String.fromCharCode(parseInt(octal[0], 8)), end: start + octal[0].length };
  }
  const simple = { n: '\n', t: '\t', r: '\r', '\\': '\\', "'": "'", '"': '"', e: '\u001b', a: '\u0007', b: '\b', f: '\f', v: '\v' };
  if (char !== undefined && simple[char] !== undefined) return { value: simple[char], end: start + 1 };
  return { value: char ?? '', end: start + 1 };
}

/**
 * 预扫一遍原文，收集字面量赋值与 alias —— 让 `r=rm; $r -rf /x` 这类写法
 * 在切词阶段就能解出真实程序名。只接受"安全的字面量"值（无空白/引号/`$`/分隔符），
 * 含命令替换或引号的值一律忽略，避免把 `x="$(rm -rf /y)"` 误当程序名。
 */
function preScan(text) {
  const vars = new Map();
  const aliases = new Map();
  const assignment = /(?:^|[\s;&|(])(?:export\s+|readonly\s+|local\s+)?([A-Za-z_][A-Za-z0-9_]*)=([\w@%+,./:=~^{}[\]*-]+)/g;
  let match = assignment.exec(text);
  while (match !== null) {
    vars.set(match[1], match[2]);
    match = assignment.exec(text);
  }
  const alias = /(?:^|[\s;&|(])alias\s+([A-Za-z_][A-Za-z0-9_-]*)=(['"]?)([\w@%+,./:=~^{}[\]* -]*)\2/g;
  let aliasMatch = alias.exec(text);
  while (aliasMatch !== null) {
    aliases.set(aliasMatch[1], aliasMatch[3].split(/\s+/).filter((part) => part !== ''));
    aliasMatch = alias.exec(text);
  }
  return { vars, aliases };
}

/**
 * 切词 + 切简单命令。
 * @param {string} text - 原始命令文本。
 * @param {object} [variables] - `{ vars, home }`，供 `$VAR` / `~` 展开。
 * @returns {Array<Array<object>>} 简单命令列表（单词与重定向 token）。
 */
export function splitSimpleCommands(text, variables = {}) {
  const vars = variables.vars instanceof Map ? variables.vars : new Map();
  const home = typeof variables.home === 'string' ? variables.home : '';
  const tokens = [];
  let index = 0;
  const length = text.length;
  /** 挂起的 here-doc 定界符：正文只是文字，不是要执行的命令。 */
  const heredocs = [];

  const readDollar = (start) => {
    const next = text[start + 1];
    if (next === '(') {
      if (text[start + 2] === '(') {
        let depth = 0;
        let cursor = start + 1;
        while (cursor < length) {
          if (text[cursor] === '(') depth += 1;
          else if (text[cursor] === ')') {
            depth -= 1;
            if (depth === 0) break;
          }
          cursor += 1;
        }
        return { value: '', dynamic: true, glob: false, hints: [], subs: [], end: Math.min(cursor + 1, length) };
      }
      let depth = 0;
      let cursor = start + 1;
      while (cursor < length) {
        const char = text[cursor];
        if (char === "'") {
          const close = text.indexOf("'", cursor + 1);
          cursor = close < 0 ? length : close + 1;
          continue;
        }
        if (char === '(') depth += 1;
        else if (char === ')') {
          depth -= 1;
          if (depth === 0) break;
        }
        cursor += 1;
      }
      const body = text.slice(start + 2, cursor);
      return { value: '', dynamic: true, glob: false, hints: programHints(body), subs: [body], end: Math.min(cursor + 1, length) };
    }
    if (next === "'" || next === '"') {
      const quote = next;
      let cursor = start + 2;
      let value = '';
      while (cursor < length && text[cursor] !== quote) {
        if (quote === "'" && text[cursor] === '\\') {
          const decoded = decodeAnsiC(text, cursor + 1);
          value += decoded.value;
          cursor = decoded.end;
          continue;
        }
        if (quote === '"' && text[cursor] === '\\' && '$`"\\'.includes(text[cursor + 1] ?? '')) {
          value += text[cursor + 1];
          cursor += 2;
          continue;
        }
        value += text[cursor];
        cursor += 1;
      }
      return { value, dynamic: false, glob: hasGlob(value), hints: [], subs: [], end: cursor + 1 };
    }
    if (next === '{') {
      const close = text.indexOf('}', start + 2);
      const end = close < 0 ? length : close + 1;
      const body = text.slice(start + 2, close < 0 ? length : close);
      const name = body.split(/[:#%/^,]/)[0].replace(/[+-]$/, '');
      const known = vars.get(name);
      if (known !== undefined) return { value: known, dynamic: false, glob: false, hints: [], subs: [], end };
      const fallback = /:-([^}]*)$/.exec(body);
      if (fallback !== null && fallback[1] !== '') return { value: fallback[1], dynamic: false, glob: false, hints: [], subs: [], end };
      return { value: '', dynamic: true, glob: false, hints: [], subs: [], end };
    }
    const nameMatch = /^[A-Za-z_][A-Za-z0-9_]*/.exec(text.slice(start + 1));
    if (nameMatch !== null) {
      const name = nameMatch[0];
      const known = vars.get(name);
      const end = start + 1 + name.length;
      if (known !== undefined) return { value: known, dynamic: false, glob: false, hints: [], subs: [], end };
      return { value: '', dynamic: true, glob: false, hints: [], subs: [], end };
    }
    return { value: '', dynamic: false, glob: false, hints: [], subs: [], end: start };
  };

  const readWord = (start) => {
    let cursor = start;
    let value = '';
    let dynamic = false;
    let glob = false;
    const hints = [];
    const subs = [];
    while (cursor < length) {
      const char = text[cursor];
      if (char === '\\') {
        const next = text[cursor + 1];
        if (next === '\n') {
          cursor += 2;
          continue;
        }
        if (next === undefined) {
          value += '\\';
          cursor += 1;
          continue;
        }
        value += next;
        cursor += 2;
        continue;
      }
      if (char === "'") {
        const close = text.indexOf("'", cursor + 1);
        const end = close < 0 ? length : close;
        value += text.slice(cursor + 1, end);
        cursor = close < 0 ? length : end + 1;
        continue;
      }
      if (char === '"') {
        cursor += 1;
        while (cursor < length && text[cursor] !== '"') {
          const inner = text[cursor];
          if (inner === '\\') {
            const next = text[cursor + 1];
            if (next === undefined) break;
            if ('$`"\\\n'.includes(next)) {
              if (next !== '\n') value += next;
              cursor += 2;
              continue;
            }
            value += '\\';
            cursor += 1;
            continue;
          }
          if (inner === '$') {
            const expanded = readDollar(cursor);
            if (expanded.end === cursor) {
              value += inner;
              cursor += 1;
              continue;
            }
            value += expanded.value;
            dynamic = dynamic || expanded.dynamic;
            hints.push(...expanded.hints);
            subs.push(...expanded.subs);
            cursor = expanded.end;
            continue;
          }
          if (inner === '`') {
            const close = text.indexOf('`', cursor + 1);
            const end = close < 0 ? length : close;
            hints.push(...programHints(text.slice(cursor + 1, end)));
            subs.push(text.slice(cursor + 1, end));
            dynamic = true;
            cursor = close < 0 ? length : end + 1;
            continue;
          }
          value += inner;
          cursor += 1;
        }
        cursor += 1;
        continue;
      }
      if (char === '$') {
        const expanded = readDollar(cursor);
        if (expanded.end === cursor) {
          value += char;
          cursor += 1;
          continue;
        }
        value += expanded.value;
        dynamic = dynamic || expanded.dynamic;
        glob = glob || (expanded.glob && hasGlob(expanded.value));
        hints.push(...expanded.hints);
        subs.push(...expanded.subs);
        cursor = expanded.end;
        continue;
      }
      if (char === '`') {
        const close = text.indexOf('`', cursor + 1);
        const end = close < 0 ? length : close;
        hints.push(...programHints(text.slice(cursor + 1, end)));
        subs.push(text.slice(cursor + 1, end));
        dynamic = true;
        cursor = close < 0 ? length : end + 1;
        continue;
      }
      if (char === '~' && cursor === start) {
        value += home;
        cursor += 1;
        continue;
      }
      if (/[*?[{]/.test(char)) {
        glob = true;
        value += char;
        cursor += 1;
        continue;
      }
      if (' \t\r\n;&|()<>'.includes(char)) break;
      value += char;
      cursor += 1;
    }
    return { value, dynamic, glob, hints, subs, start, end: cursor };
  };

  const current = [];
  const commands = [];
  const flush = () => {
    if (current.length > 0) commands.push(current.slice());
    current.length = 0;
  };
  /** 重定向目标前可以有空隙：`> file` 与 `>file` 等价。 */
  const skipBlanks = (start) => {
    let cursor = start;
    while (cursor < length && (text[cursor] === ' ' || text[cursor] === '\t')) cursor += 1;
    return cursor;
  };

  while (index < length) {
    const char = text[index];
    if (char === ' ' || char === '\t' || char === '\r') {
      index += 1;
      continue;
    }
    if (char === '#') {
      const cut = text.indexOf('\n', index);
      index = cut < 0 ? length : cut;
      continue;
    }
    if (char === '\n') {
      if (heredocs.length > 0) {
        index = skipHeredocs(text, index + 1, heredocs);
        heredocs.length = 0;
      } else {
        index += 1;
      }
      flush();
      continue;
    }
    if (char === ';' || char === '(' || char === ')') {
      flush();
      index += 1;
      continue;
    }
    if (char === '&') {
      if (text[index + 1] === '>') {
        const append = text[index + 2] === '>';
        index = skipBlanks(index + (append ? 3 : 2));
        const target = readWord(index);
        index = target.end;
        current.push({ kind: 'redirect', op: append ? '&>>' : '&>', append, fd: null, target });
        continue;
      }
      flush();
      index += text[index + 1] === '&' ? 2 : 1;
      continue;
    }
    if (char === '|') {
      flush();
      index += text[index + 1] === '|' ? 2 : 1;
      continue;
    }
    if (char === '<' || char === '>') {
      let fd = null;
      const previous = current[current.length - 1];
      if (previous !== undefined && previous.kind === 'word' && previous.end === index && /^\d+$/.test(previous.value) && !previous.dynamic) {
        fd = Number(previous.value);
        current.pop();
      }
      let op;
      if (char === '>') {
        if (text[index + 1] === '>') {
          op = '>>';
          index += 2;
        } else if (text[index + 1] === '|') {
          op = '>|';
          index += 2;
        } else if (text[index + 1] === '&') {
          op = '>&';
          index += 2;
        } else {
          op = '>';
          index += 1;
        }
      } else if (text[index + 1] === '<') {
        if (text[index + 2] === '<') {
          op = '<<<';
          index += 3;
        } else {
          op = text[index + 2] === '-' ? '<<-' : '<<';
          index += op === '<<-' ? 3 : 2;
        }
      } else if (text[index + 1] === '>') {
        op = '<>';
        index += 2;
      } else {
        op = '<';
        index += 1;
      }
      const target = readWord(skipBlanks(index));
      index = target.end;
      if (op === '<<' || op === '<<-') heredocs.push({ delimiter: target.value, stripTabs: op === '<<-' });
      current.push({ kind: 'redirect', op, append: op === '>>' || op === '&>>', fd, target });
      continue;
    }
    const word = readWord(index);
    if (word.end === index) {
      index += 1;
      continue;
    }
    index = word.end;
    current.push({ kind: 'word', ...word });
  }
  flush();
  return commands;
}

/** 跳过 here-doc 正文。 */
function skipHeredocs(text, start, heredocs) {
  let index = start;
  for (const heredoc of heredocs) {
    while (index <= text.length) {
      const cut = text.indexOf('\n', index);
      const line = text.slice(index, cut < 0 ? text.length : cut);
      const compared = heredoc.stripTabs ? line.replace(/^\t+/, '') : line;
      index = cut < 0 ? text.length : cut + 1;
      if (compared === heredoc.delimiter) break;
    }
  }
  return index;
}

// #endregion

// #region 调用解析：剥包装器，找出真正的程序和参数

/** 剥掉包装器（sudo/env/timeout/xargs/...），返回真正的程序名与参数。 */
function resolveInvocation(words) {
  let index = 0;
  let stdinTargets = false;
  let guard = 0;
  while (index < words.length && guard < 16) {
    guard += 1;
    const token = words[index];
    const name = baseName(String(token.value));
    if (index === 0 && token.dynamic && token.hints.length > 0) {
      // `$(which rm) -rf /x`：替换结果未知，但正文点名了 rm。
      return { program: token.hints[0], args: words.slice(1), stdinTargets };
    }
    if (token.dynamic || !WRAPPERS.has(name)) break;
    index += 1;
    if (name === 'xargs') stdinTargets = true;
    if ((name === 'command' || name === 'builtin') && words[index] !== undefined && /^-[vV]$/.test(String(words[index].value))) {
      return { program: null, args: [], stdinTargets: false };
    }
    index = skipWrapperOptions(name, words, index);
  }
  if (index >= words.length) return { program: null, args: [], stdinTargets };
  const token = words[index];
  let program = baseName(String(token.value));
  const args = words.slice(index + 1);
  if (token.dynamic && token.hints.length > 0) program = token.hints[0];
  if (program === '') return { program: null, args, stdinTargets };
  return { program, args, stdinTargets };
}

/** 吃掉某个包装器自己的选项（含吃值的选项），返回真正命令的下标。 */
function skipWrapperOptions(name, words, start) {
  const valueFlags = WRAPPER_VALUE_FLAGS[name] ?? new Set();
  let index = start;
  if (name === 'timeout' && words[index] !== undefined && !String(words[index].value).startsWith('-')) index += 1;
  while (index < words.length) {
    const text = String(words[index].value);
    if (words[index].dynamic) break;
    if (name === 'env' && /^[A-Za-z_][A-Za-z0-9_]*=/.test(text)) {
      index += 1;
      continue;
    }
    if (text === '--') {
      index += 1;
      break;
    }
    if (!text.startsWith('-') || text === '-') break;
    const [long] = splitLongOption(text);
    index += valueFlags.has(text) || valueFlags.has(long) ? 2 : 1;
  }
  return index;
}

// #endregion

// #region 目标解析与探测

/** 把一个单词变成"要删/要覆盖的目标"：解析路径、探测存在性、展开通配计数。 */
function buildTarget(token, state) {
  const text = String(token.value);
  if (text === '') return null;
  let raw = text;
  if (raw === '~') raw = state.home;
  else if (raw.startsWith('~/')) raw = state.home + raw.slice(1);
  if (!raw.startsWith('/')) raw = (state.cwd === '' ? '/' : state.cwd) + '/' + raw;
  const path = normalizePosix(raw);
  const pattern = hasGlob(path);
  const target = { text, path, glob: pattern, count: null, kind: 'unknown', samples: [] };
  if (pattern) {
    let matches = null;
    if (state.glob !== null && !/[{}]/.test(path)) {
      try {
        const found = state.glob(path);
        if (Array.isArray(found)) matches = found.slice(0, state.maxExpanded);
      } catch {
        matches = null;
      }
    }
    if (matches === null) {
      target.kind = 'glob-unknown';
    } else {
      target.count = matches.length;
      target.samples = matches.slice(0, state.maxSamples);
      target.kind = matches.length === 0 ? 'glob-empty' : 'glob';
    }
  } else if (state.probe !== null) {
    try {
      const value = state.probe(path);
      target.kind = value === true ? 'file'
        : value === false ? 'missing'
          : value === 'missing' || value === 'file' || value === 'dir' || value === 'other' ? value : 'unknown';
    } catch {
      target.kind = 'unknown';
    }
  }
  return target;
}

function buildTargets(tokens, state) {
  const targets = [];
  for (const token of tokens) {
    const target = buildTarget(token, state);
    if (target !== null) targets.push(target);
  }
  return targets;
}

function unlisted(text) {
  return { text, path: null, glob: false, count: null, kind: 'unlisted', samples: [] };
}

function allMissing(targets) {
  return targets.length > 0 && targets.every((target) => target.kind === 'missing' || target.kind === 'glob-empty');
}

/** 目标里最坏的一个是否触及受保护根 / 裸设备 / cwd 及其祖先。 */
function worstTargetRank(targets, state) {
  let rank = 1;
  for (const target of targets) {
    const path = target.path;
    if (typeof path !== 'string') continue;
    if (isDevice(path) || isProtectedPattern(path) || isAncestorOfCwd(path, state.cwd)) rank = 3;
  }
  return rank;
}

// #endregion

// #region 各程序的判别

function addFinding(state, finding) {
  const targets = finding.targets ?? [];
  const rank = worstTargetRank(targets, state);
  const severity = rank >= 3 ? 'critical' : finding.severity;
  state.findings.push({
    kind: finding.kind,
    program: finding.program ?? '',
    raw: finding.raw ?? '',
    severity,
    recursive: Boolean(finding.recursive),
    force: Boolean(finding.force),
    unknownTargets: Boolean(finding.unknownTargets) || targets.length === 0,
    note: finding.note ?? '',
    noteEn: finding.noteEn ?? '',
    targets,
  });
}

/** 删除类程序（rm/rmdir/unlink/shred）的公共处理。 */
function inspectDeleter(program, args, invocation, state, fallback) {
  const flags = parseFlags(args);
  const long = (name) => flags.long.has('--' + name);
  const recursive = flags.short.has('r') || flags.short.has('R') || long('recursive');
  const force = flags.short.has('f') || long('force');
  if (program === 'rm' && (flags.short.has('n') || long('dry-run'))) return;
  let targets = buildTargets(flags.operands, state);
  let unknown = false;
  if (targets.length === 0) {
    // 目标来自管道/xargs 的 stdin，或来自 find -exec 的匹配项：数量不可预知，必须问。
    if (invocation.stdinTargets) {
      targets = [unlisted('<标准输入 / xargs 展开项>')];
      unknown = true;
    } else if (fallback !== undefined) {
      targets = [unlisted(fallback)];
      unknown = true;
    } else {
      return;
    }
  }
  const missing = allMissing(targets);
  // rm 即使目标当前不存在也照样问：这正是"AI 幻觉删东西"最典型的形状。
  // 其余删除类程序对"确认不存在"的目标只报无操作，不值得打扰用户。
  if (missing && program !== 'rm') return;
  let severity = program === 'rmdir' ? 'medium' : 'high';
  if (missing) severity = 'medium';
  let note = '';
  let noteEn = '';
  if (missing) {
    note = '目标当前不存在（这条命令可能是幻觉产物）';
    noteEn = 'the target(s) do not exist right now (this command may be a hallucination)';
  } else if (program === 'rmdir') {
    note = '只能删除空目录';
    noteEn = 'rmdir only removes empty directories';
  } else if (program === 'shred') {
    note = flags.short.has('u') || long('remove') ? '-u 会在覆写后删除文件' : '会覆写文件内容';
    noteEn = flags.short.has('u') || long('remove') ? '-u removes the file after overwriting' : 'overwrites the file content';
  } else if (flags.short.has('i') || long('interactive')) {
    note = '带 -i，但工具调用是非交互的，交互确认不会生效';
    noteEn = '-i is present, but this tool call is non-interactive, so that prompt never appears';
  }
  addFinding(state, {
    kind: program === 'rmdir' ? 'rmdir' : program === 'unlink' ? 'unlink' : program === 'shred' ? 'shred' : 'rm',
    program,
    raw: [program, ...args.map((token) => token.value)].join(' ').trim(),
    recursive,
    force,
    severity,
    unknownTargets: unknown,
    note,
    noteEn,
    targets,
  });
}

function inspectTruncate(args, state) {
  const size = optionValue(args, 's', '--size');
  if (size === undefined || size === null) return;
  const numeric = Number(size);
  if (!Number.isFinite(numeric) || numeric > 0) return;
  const targets = buildTargets(parseFlags(args).operands, state);
  if (targets.length === 0 || allMissing(targets)) return;
  addFinding(state, {
    kind: 'truncate',
    program: 'truncate',
    raw: 'truncate ' + args.map((token) => token.value).join(' ').trim(),
    severity: 'high',
    note: '把文件长度截为 ' + String(size),
    noteEn: 'truncates the file to ' + String(size) + ' bytes',
    targets,
  });
}

function inspectDd(args, state) {
  let output = null;
  const kept = [];
  for (const token of args) {
    const text = String(token.value);
    if (text.startsWith('of=')) {
      output = text.slice(3);
      continue;
    }
    kept.push(token);
  }
  if (output === null || output === '') return;
  const target = buildTarget({ value: output, dynamic: false, glob: hasGlob(output) }, state);
  if (target === null || target.path === null || isVoidTarget(target.path)) return;
  // 目标不存在 = 只是在新建文件，不算破坏（裸设备除外：设备节点在我们的探测里
  // 常常是 missing，但写上去就是不可逆的）。
  if (target.kind === 'missing' && !isDevice(target.path)) return;
  addFinding(state, {
    kind: 'dd',
    program: 'dd',
    raw: 'dd ' + kept.map((token) => token.value).join(' ').trim() + ' of=' + output,
    severity: 'high',
    note: 'dd 会从偏移 0 覆写 of= 指定的目标',
    noteEn: 'dd overwrites the of= target starting at offset 0',
    targets: [target],
  });
}

function inspectCopyLike(program, args, state) {
  const flags = parseFlags(args);
  const long = (name) => flags.long.has('--' + name);
  if (flags.short.has('n') || long('no-clobber') || flags.short.has('b')) return;
  if (flags.operands.length < 2) return;
  const destination = buildTarget(flags.operands[flags.operands.length - 1], state);
  if (destination === null) return;
  const raw = [program, ...args.map((token) => token.value)].join(' ').trim();
  if (program === 'mv' && destination.path !== null && isTempPath(destination.path)) {
    addFinding(state, { kind: 'mv-void', program, raw, severity: 'high', note: '移动到临时区/黑洞，之后无法找回', noteEn: 'moved into a temporary/void location and cannot be recovered later', targets: [destination] });
    return;
  }
  if (destination.kind !== 'file') return;
  const sourceGlob = flags.operands.slice(0, -1).some((token) => hasGlob(String(token.value)));
  addFinding(state, {
    kind: program === 'mv' ? 'mv-clobber' : 'cp-clobber',
    program,
    raw,
    severity: 'medium',
    note: '目标已存在且不是目录，会被覆盖' + (sourceGlob ? '（源含通配符，可能覆盖多个）' : ''),
    noteEn: 'the destination exists and is not a directory, so it will be overwritten' + (sourceGlob ? ' (a glob source may overwrite several files)' : ''),
    targets: [destination],
  });
}

function inspectTee(args, state) {
  const flags = parseFlags(args);
  if (flags.short.has('a') || flags.long.has('--append')) return;
  const targets = buildTargets(flags.operands, state).filter((target) => target.kind !== 'missing' && target.path !== null && !isVoidTarget(target.path));
  if (targets.length === 0) return;
  addFinding(state, {
    kind: 'tee',
    program: 'tee',
    raw: 'tee ' + args.map((token) => token.value).join(' ').trim(),
    severity: 'medium',
    note: 'tee 会先截断目标文件',
    noteEn: 'tee truncates the destination file first',
    targets,
  });
}

function inspectRsync(args, state) {
  const flags = parseFlags(args);
  if (!flags.long.has('--delete') && !flags.long.has('--delete-excluded')) return;
  if (flags.short.has('n') || flags.long.has('--dry-run')) return;
  const destination = flags.operands.length >= 2 ? buildTarget(flags.operands[flags.operands.length - 1], state) : null;
  addFinding(state, {
    kind: 'rsync-delete',
    program: 'rsync',
    raw: 'rsync ' + args.map((token) => token.value).join(' ').trim(),
    severity: 'high',
    note: '--delete 会删除目标目录里"源没有"的文件',
    noteEn: '--delete removes files in the destination that the source does not have',
    targets: destination === null ? [] : [destination],
  });
}

function inspectFind(args, state, depth) {
  const paths = [];
  let index = 0;
  while (index < args.length) {
    const text = String(args[index].value);
    if (text.startsWith('-') || text === '!' || text === '(') break;
    paths.push(args[index]);
    index += 1;
  }
  const expressions = args.slice(index);
  if (expressions.length === 0) return;
  const pathTokens = paths.length > 0 ? paths : [{ value: '.', dynamic: false, glob: false }];
  const raw = 'find ' + args.map((token) => token.value).join(' ').trim();
  if (expressions.some((token) => token.value === '-delete')) {
    addFinding(state, {
      kind: 'find-delete',
      program: 'find',
      raw,
      severity: 'high',
      unknownTargets: true,
      note: '-delete 会删除所有匹配项，匹配数量无法静态枚举',
      noteEn: '-delete removes every match; the match count cannot be enumerated statically',
      targets: buildTargets(pathTokens, state),
    });
  }
  for (let cursor = 0; cursor < expressions.length; cursor += 1) {
    const text = String(expressions[cursor].value);
    if (text !== '-exec' && text !== '-execdir' && text !== '-ok' && text !== '-okdir') continue;
    const inner = [];
    let brace = false;
    let scan = cursor + 1;
    for (; scan < expressions.length; scan += 1) {
      const value = String(expressions[scan].value);
      if (value === ';' || value === '\\;' || value === '+') break;
      if (value === '{}') {
        brace = true;
        continue;
      }
      inner.push(expressions[scan]);
    }
    cursor = scan;
    if (inner.length > 0) analyzeWordTokens(inner, state, depth + 1, brace ? '<find 的每个匹配项>' : undefined);
  }
}

function inspectGit(args, state) {
  const first = args[0];
  if (first === undefined) return;
  const subcommand = String(first.value);
  const rest = args.slice(1);
  const flags = parseFlags(rest);
  const raw = 'git ' + args.map((token) => token.value).join(' ').trim();
  const dryRun = flags.short.has('n') || flags.long.has('--dry-run');
  if (subcommand === 'clean') {
    if (!(flags.short.has('f') || flags.long.has('--force')) || dryRun) return;
    addFinding(state, {
      kind: 'git-clean',
      program: 'git',
      raw,
      severity: 'high',
      unknownTargets: true,
      note: '-f 下未跟踪的文件/目录被直接删除，无法恢复',
      noteEn: 'with -f, untracked files and directories are deleted and cannot be recovered',
      targets: buildTargets([{ value: '.', dynamic: false, glob: false }], state),
    });
    return;
  }
  if (subcommand === 'reset' && flags.long.has('--hard')) {
    addFinding(state, { kind: 'git-reset-hard', program: 'git', raw, severity: 'medium', unknownTargets: true, note: '--hard 丢弃未提交的工作区改动', noteEn: '--hard discards uncommitted working-tree changes', targets: [] });
    return;
  }
  if ((subcommand === 'checkout' || subcommand === 'restore') && !dryRun) {
    const discards = subcommand === 'restore'
      ? !flags.long.has('--staged') || flags.long.has('--worktree')
      : rest.some((token) => {
        const value = String(token.value);
        return value === '--' || value === '.' || hasGlob(value);
      });
    if (discards) {
      addFinding(state, { kind: 'git-discard', program: 'git', raw, severity: 'medium', unknownTargets: true, note: '会丢弃工作区里未提交的改动', noteEn: 'discards uncommitted changes in the working tree', targets: [] });
    }
    return;
  }
  if (subcommand === 'branch' && (flags.short.has('D') || flags.long.has('--delete'))) {
    addFinding(state, { kind: 'git-branch-delete', program: 'git', raw, severity: 'medium', unknownTargets: true, note: '删除分支会丢掉该分支上未合并的提交', noteEn: 'deleting the branch loses its unmerged commits', targets: [] });
    return;
  }
  if (subcommand === 'stash' && (rest[0]?.value === 'clear' || rest[0]?.value === 'drop')) {
    addFinding(state, { kind: 'git-stash-drop', program: 'git', raw, severity: 'medium', unknownTargets: true, note: '丢弃 stash 条目', noteEn: 'drops the stash entry', targets: [] });
  }
}

// #endregion

// #region 重定向与脚本递归

/**
 * `> file` / `>|` / `&>` 截断（`>>` 追加不算）。
 * 只对"当前存在"的目标报，避免把新建文件也当成破坏；存在性未知时保守上报。
 */
function inspectRedirects(tokens, state) {
  for (const token of tokens) {
    if (token.kind !== 'redirect') continue;
    const op = token.op;
    if (op !== '>' && op !== '>|' && op !== '&>') continue;
    const raw = String(token.target?.value ?? '');
    if (raw === '' || raw === '-' || raw === '&' || /^\d+$/.test(raw)) continue;
    const target = buildTarget(token.target, state);
    if (target === null || target.path === null) continue;
    if (isVoidTarget(target.path)) {
      if (isDevice(target.path)) {
        addFinding(state, { kind: 'redirect-device', program: '>', raw: '> ' + raw, severity: 'critical', note: '重定向到裸设备', noteEn: 'redirection into a raw device', targets: [target] });
      }
      continue;
    }
    if (target.kind === 'missing' || target.kind === 'glob-empty') continue;
    addFinding(state, {
      kind: 'redirect-truncate',
      program: '>',
      raw: '> ' + raw,
      severity: 'medium',
      note: state.probe === null ? '重定向会先截断目标（存在性未知，按保守处理）' : '重定向会先截断已存在的目标文件',
      noteEn: state.probe === null ? 'the redirection truncates the target first (existence unknown, handled conservatively)' : 'the redirection truncates the existing target file first',
      targets: [target],
    });
  }
}

/** `bash script.sh` / `./deploy.sh`：把脚本正文读进来重新识别（深度有上限）。 */
function inspectScriptFile(program, args, state, depth) {
  if (state.readFile === null || depth >= state.maxDepth) return false;
  const isShell = SHELLS.has(program);
  let candidate = null;
  if (isShell) {
    const positional = args.find((token) => !String(token.value).startsWith('-'));
    candidate = positional === undefined ? null : String(positional.value);
  } else if (program.endsWith('.sh') || program.endsWith('.bash')) {
    candidate = program;
  }
  if (candidate === null || candidate === '' || candidate === '-' || /[*?[{]/.test(candidate)) return false;
  let path = candidate;
  if (!path.startsWith('/')) path = normalizePosix(state.cwd + '/' + path);
  if (state.seenScripts.has(path)) return false;
  state.seenScripts.add(path);
  let content = null;
  try {
    content = state.readFile(path);
  } catch {
    content = null;
  }
  if (typeof content !== 'string' || content === '') return false;
  analyzeText(content, state, depth + 1);
  return true;
}

// #endregion

// #region 主流程

/** 处理一条简单命令的单词序列（也被 `find -exec` / 递归复用）。 */
function analyzeWordTokens(words, state, depth, fallback) {
  let index = 0;
  while (index < words.length) {
    const match = /^([A-Za-z_][A-Za-z0-9_]*)=([\w@%+,./:=~^{}[\]*-]*)$/.exec(String(words[index].value));
    if (match === null) break;
    state.vars.set(match[1], match[2]);
    index += 1;
  }
  const rest = words.slice(index);
  if (rest.length === 0) return;
  const invocation = resolveInvocation(rest);
  if (invocation.program === null) return;
  const program = invocation.program;
  const args = invocation.args;

  const aliased = state.aliases.get(program);
  if (aliased !== undefined && !DESTRUCTIVE_PROGRAMS.has(program)) {
    analyzeWordTokens([...aliased.map((value) => ({ value, dynamic: false, glob: false })), ...args], state, depth);
    return;
  }
  if (program === 'eval') {
    analyzeText(args.map((token) => String(token.value)).join(' '), state, depth + 1);
    return;
  }
  if (SHELLS.has(program)) {
    const cIndex = args.findIndex((token) => /^-[a-zA-Z]*c[a-zA-Z]*$/.test(String(token.value)));
    if (cIndex >= 0 && args[cIndex + 1] !== undefined) {
      analyzeText(String(args[cIndex + 1].value), state, depth + 1);
      return;
    }
    inspectScriptFile(program, args, state, depth);
    return;
  }
  switch (program) {
    case 'rm':
    case 'rmdir':
    case 'unlink':
    case 'shred':
      inspectDeleter(program, args, invocation, state, fallback);
      return;
    case 'truncate':
      inspectTruncate(args, state);
      return;
    case 'dd':
      inspectDd(args, state);
      return;
    case 'mv':
    case 'cp':
      inspectCopyLike(program, args, state);
      return;
    case 'tee':
      inspectTee(args, state);
      return;
    case 'rsync':
      inspectRsync(args, state);
      return;
    case 'find':
      inspectFind(args, state, depth);
      return;
    case 'git':
      inspectGit(args, state);
      return;
    default:
      if (inspectScriptFile(program, args, state, depth)) return;
  }
}

function analyzeText(text, state, depth) {
  if (depth > state.maxDepth + 1) return;
  let commands;
  try {
    commands = splitSimpleCommands(text, { vars: state.vars, home: state.home });
  } catch {
    return;
  }
  for (const command of commands) {
    try {
      inspectRedirects(command, state);
      // 命令替换的正文本身也是要执行的命令（`echo $(rm -rf x)` / `A=$(rm -rf x)`）。
      for (const token of command) {
        if (token.kind !== 'word' || !Array.isArray(token.subs)) continue;
        for (const body of token.subs) if (body.trim() !== '') analyzeText(body, state, depth + 1);
      }
      analyzeWordTokens(command.filter((token) => token.kind === 'word'), state, depth);
    } catch {
      // 单条命令识别失败不连累整条命令串；上层还有一道兜底启发式。
    }
  }
}

function dedupe(findings) {
  const seen = new Set();
  const out = [];
  for (const finding of findings) {
    const key = finding.kind + '\u0000' + finding.raw + '\u0000' + finding.targets.map((target) => String(target.text)).join(',');
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(finding);
  }
  return out;
}

function summarize(state) {
  const findings = dedupe(state.findings);
  if (findings.length === 0) return emptyResult();
  let severity = 'none';
  let total = 0;
  let unknownTargets = false;
  const paths = new Set();
  for (const finding of findings) {
    if (SEVERITY_RANK[finding.severity] > SEVERITY_RANK[severity]) severity = finding.severity;
    if (finding.targets.length === 0) unknownTargets = true;
    for (const target of finding.targets) {
      total += typeof target.count === 'number' && target.count > 1 ? target.count : 1;
      if (target.kind === 'glob-unknown' || target.kind === 'unlisted') unknownTargets = true;
      if (typeof target.path === 'string') paths.add(target.path);
    }
  }
  return { destructive: true, severity, findings, targets: [...paths], total, unknownTargets };
}

/**
 * 识别一条 bash 命令里的破坏性动作。
 *
 * @param {string} command - 原始命令文本。
 * @param {object} [options] - `{ cwd, home, env, probe, glob, readFile, maxDepth, maxExpanded }`。
 *   `probe(path)` 返回 `'missing'|'file'|'dir'|'other'|'unknown'`；`glob(pattern)` 返回匹配到的
 *   绝对路径数组；`readFile(path)` 返回脚本正文。三者都可省略 —— 省略时按"事实未知"保守处理。
 * @returns {{destructive:boolean,severity:string,findings:Array<object>,targets:Array<string>,total:number,unknownTargets:boolean}}
 */
export function analyzeCommand(command, options = {}) {
  if (typeof command !== 'string' || command.trim() === '') return emptyResult();
  const env = options.env !== null && typeof options.env === 'object' ? options.env : {};
  const cwd = typeof options.cwd === 'string' && options.cwd !== '' ? normalizePosix(options.cwd) : '/';
  const home = typeof options.home === 'string' && options.home !== '' ? options.home
    : typeof env.HOME === 'string' && env.HOME !== '' ? env.HOME : '/root';
  const scanned = preScan(command);
  const vars = new Map();
  for (const [key, value] of Object.entries(env)) if (typeof value === 'string') vars.set(key, value);
  vars.set('HOME', home);
  vars.set('PWD', cwd);
  for (const [key, value] of scanned.vars) vars.set(key, value);
  const state = {
    cwd,
    home,
    probe: typeof options.probe === 'function' ? options.probe : null,
    glob: typeof options.glob === 'function' ? options.glob : null,
    readFile: typeof options.readFile === 'function' ? options.readFile : null,
    maxDepth: Number.isSafeInteger(options.maxDepth) && options.maxDepth >= 0 ? options.maxDepth : 2,
    maxExpanded: Number.isSafeInteger(options.maxExpanded) && options.maxExpanded > 0 ? options.maxExpanded : 200,
    maxSamples: 3,
    findings: [],
    vars,
    aliases: new Map(scanned.aliases),
    seenScripts: new Set(),
  };
  analyzeText(command, state, 0);
  return summarize(state);
}

export { SEVERITY_RANK };
