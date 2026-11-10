'use strict';
// 名称解析兼容层：只重试域名解析，不重放 HTTP 请求；正常双栈与显式 IPv6 完全保留。
// 1) AAAA 被拒（EAI_AGAIN/EAI_FAIL 且未指定 family）时重试一次 IPv4；
// 2) 仍为 EAI_AGAIN/EAI_FAIL 时，经本机能力桥 /app/net/resolve 让 Android 系统解析器在当前
//    默认网络上解析一次（覆盖切网后 resolv.conf 未及更新、私有 DNS、DNS64、VPN 分流）。
//    实验性争取性修复，不保证可靠。ENOTFOUND 等明确结果不走兜底；DSHA_DNS_MODE=native 或 DSHA_DNS_HOST_FALLBACK=0 关闭。
const dns = require('node:dns');
const { promisify } = require('node:util');
const marker = Symbol.for('dsha.dns.compat');
if (process.env.DSHA_DNS_MODE !== 'native' && !dns[marker]) {
  const original = dns.lookup;
  const originalPromise = dns.promises.lookup.bind(dns.promises);
  const failed = error => error && (error.code === 'EAI_AGAIN' || error.code === 'EAI_FAIL');
  const familyOf = options => {
    const value = typeof options === 'number' ? options : options && options.family;
    if (value === 'IPv4') return 4;
    if (value === 'IPv6') return 6;
    return value === 4 || value === 6 ? value : 0;
  };
  const retryable = (error, options) => {
    const family = typeof options === 'number' ? options : options && options.family;
    return failed(error) && (family === undefined || family === 0);
  };
  const ipv4 = options => ({ ...(options && typeof options === 'object' ? options : {}), family: 4 });

  // ---- 宿主解析兜底 ----
  const HEADERS_FILE = '/root/.dsh/.bridge_headers';
  const PORT_FILE = '/root/.dsh/.bridge_port';
  const NEGATIVE_MS = 10000, TIMEOUT_MS = 6000, MAX_ACTIVE = 4;
  const inflight = new Map(), negative = new Map();
  let active = 0;
  const hostFallback = process.env.DSHA_DNS_HOST_FALLBACK !== '0';
  function eligible(hostname) {
    if (typeof hostname !== 'string') return null;
    let host = hostname.toLowerCase();
    if (host.endsWith('.')) host = host.slice(0, -1);
    if (host.length < 3 || host.length > 253 || host.indexOf('.') < 0) return null;
    if (host === 'localhost' || host.endsWith('.localhost')) return null;
    if (!/^[a-z0-9_.-]+$/.test(host) || /^[0-9.]+$/.test(host)) return null;
    if (host.split('.').some(label => !label || label.length > 63)) return null;
    return host;
  }
  // 凭据与端口只从宿主写入的私有普通文件读取；不跟随链接，不缓存（桥重启会换端口/凭据）。
  function privateFile(fs, path, limit) {
    const info = fs.lstatSync(path);
    if (!info.isFile() || info.isSymbolicLink() || (info.mode & 0o077) !== 0 || info.size > limit)
      throw Error('BRIDGE_FILE');
    return fs.readFileSync(path, 'latin1');
  }
  function bridge(fs) {
    const token = /^X-Token:[ \t]*([A-Za-z0-9_-]{1,128})[ \t]*\r?$/m.exec(privateFile(fs, HEADERS_FILE, 512));
    if (!token) throw Error('BRIDGE_TOKEN');
    let port;
    try {
      const raw = privateFile(fs, PORT_FILE, 5);
      if (/^[0-9]{1,5}$/.test(raw)) port = Number(raw);
    } catch {}
    if (!(port >= 1 && port <= 65535)) {
      const env = process.env.DSHA_BRIDGE_PORT;
      // 不回退固定端口：3090–3099 可能属于同机另一个 DSHA 安装，不能把本安装 token 发过去。
      if (!/^[0-9]{1,5}$/.test(env || '') || Number(env) > 65535 || Number(env) < 1) throw Error('BRIDGE_PORT_UNAVAILABLE');
      port = Number(env);
    }
    return { token: token[1], port };
  }
  function parse(body) {
    const text = JSON.parse(body).result;
    if (typeof text !== 'string') return null;
    const lines = text.split('\n');
    if (lines[0] !== 'DSHA_RESOLVE_V1' || lines[1] !== 'status=OK') return null;
    const out = [];
    for (const line of lines.slice(2)) {
      const match = /^([46]) (\S+)$/.exec(line);
      if (!match) continue;
      const family = Number(match[1]), address = match[2];
      const valid = family === 4
        ? /^\d{1,3}(\.\d{1,3}){3}$/.test(address)
        : /^[0-9a-fA-F:.]+$/.test(address) && address.includes(':');
      if (valid) out.push({ address, family });
    }
    return out.length ? out : null;
  }
  function askHost(host, family) {
    return new Promise(resolve => {
      let settled = false;
      const done = value => { if (!settled) { settled = true; resolve(value); } };
      try {
        const fs = require('node:fs'), http = require('node:http');
        const { token, port } = bridge(fs);
        // 目标是 IP 字面量，net 不会再调用 lookup，不会递归。
        const request = http.get({
          host: '127.0.0.1', port, agent: false, timeout: TIMEOUT_MS,
          path: '/app/net/resolve?host=' + encodeURIComponent(host) + '&family=' + family,
          headers: { 'X-Token': token },
        }, response => {
          let body = '', size = 0;
          response.setEncoding('utf8');
          response.on('data', chunk => {
            size += chunk.length;
            if (size > 16384) { request.destroy(); done(null); } else body += chunk;
          });
          response.on('end', () => {
            try { done(response.statusCode === 200 ? parse(body) : null); } catch { done(null); }
          });
          response.on('error', () => done(null));
        });
        request.on('timeout', () => { request.destroy(); done(null); });
        request.on('error', () => done(null));
      } catch { done(null); }
    });
  }
  // 同一主机合并并发请求；失败短时负缓存，避免解析风暴打满桥。成功结果不缓存（不知道 TTL）。
  function hostLookup(hostname, options) {
    const host = hostFallback ? eligible(hostname) : null;
    if (!host) return Promise.resolve(null);
    const family = familyOf(options), key = host + '|' + family;
    const until = negative.get(key);
    if (until && until > Date.now()) return Promise.resolve(null);
    if (inflight.has(key)) return inflight.get(key);
    if (active >= MAX_ACTIVE) return Promise.resolve(null);
    active++;
    const pending = askHost(host, family).then(result => {
      active--;
      inflight.delete(key);
      if (result) negative.delete(key); else negative.set(key, Date.now() + NEGATIVE_MS);
      return result;
    });
    inflight.set(key, pending);
    return pending;
  }
  function shape(list, options) {
    const order = options && typeof options === 'object' ? options.order : undefined;
    const sorted = order === 'ipv6first' ? [...list].sort((a, b) => b.family - a.family) : list;
    const all = options && typeof options === 'object' && options.all;
    return all ? sorted.map(item => ({ ...item })) : { ...sorted[0] };
  }

  function lookup(hostname, options, callback) {
    if (typeof options === 'function') { callback = options; options = undefined; }
    // 保留 Node 的参数校验、返回值以及同步抛错行为。
    if (typeof callback !== 'function') return original.apply(dns, arguments);
    const all = options && typeof options === 'object' && options.all;
    const fallback = (error, answer) => {
      if (!failed(error)) return callback(error, ...answer);
      hostLookup(hostname, options).then(list => {
        if (!list) return callback(error, ...answer);
        const value = shape(list, options);
        if (all) callback(null, value);
        else callback(null, value.address, value.family);
      });
    };
    return original.call(dns, hostname, options, function(error, ...answer) {
      if (!error) return callback(error, ...answer);
      if (!retryable(error, options)) return fallback(error, answer);
      original.call(dns, hostname, ipv4(options), (fallbackError, ...fallbackAnswer) => {
        if (fallbackError) fallback(error, answer);
        else callback(null, ...fallbackAnswer);
      });
    });
  }
  async function lookupPromise(hostname, options) {
    let first;
    try { return await originalPromise(hostname, options); }
    catch (error) {
      first = error;
      if (retryable(error, options)) {
        try { return await originalPromise(hostname, ipv4(options)); } catch {}
      }
    }
    if (failed(first)) {
      const list = await hostLookup(hostname, options);
      if (list) return shape(list, options);
    }
    throw first;
  }
  Object.defineProperty(lookup, promisify.custom, { value: lookupPromise });
  dns.lookup = lookup;
  dns.promises.lookup = lookupPromise;
  Object.defineProperty(dns, marker, { value: true });
  require('node:module').syncBuiltinESMExports();
}
