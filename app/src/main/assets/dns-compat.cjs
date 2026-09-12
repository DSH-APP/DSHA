'use strict';
// AAAA 被拒时仅重试域名解析，不重放 HTTP 请求；正常双栈与显式 IPv6 完全保留。
const dns = require('node:dns');
const { promisify } = require('node:util');
const marker = Symbol.for('dsha.dns.compat');
if (process.env.DSHA_DNS_MODE !== 'native' && !dns[marker]) {
  const original = dns.lookup;
  const originalPromise = dns.promises.lookup.bind(dns.promises);
  const retryable = (error, options) => {
    const family = typeof options === 'number' ? options : options && options.family;
    return error && (error.code === 'EAI_AGAIN' || error.code === 'EAI_FAIL')
      && (family === undefined || family === 0);
  };
  const ipv4 = options => ({ ...(options && typeof options === 'object' ? options : {}), family: 4 });
  function lookup(hostname, options, callback) {
    if (typeof options === 'function') { callback = options; options = undefined; }
    // 保留 Node 的参数校验、返回值以及同步抛错行为。
    if (typeof callback !== 'function') return original.apply(dns, arguments);
    return original.call(dns, hostname, options, function(error, ...answer) {
      if (!retryable(error, options)) return callback(error, ...answer);
      original.call(dns, hostname, ipv4(options), (fallbackError, ...fallbackAnswer) => {
        if (fallbackError) callback(error, ...answer);
        else callback(null, ...fallbackAnswer);
      });
    });
  }
  async function lookupPromise(hostname, options) {
    try { return await originalPromise(hostname, options); }
    catch (error) {
      if (!retryable(error, options)) throw error;
      try { return await originalPromise(hostname, ipv4(options)); }
      catch { throw error; }
    }
  }
  Object.defineProperty(lookup, promisify.custom, { value: lookupPromise });
  dns.lookup = lookup;
  dns.promises.lookup = lookupPromise;
  Object.defineProperty(dns, marker, { value: true });
  require('node:module').syncBuiltinESMExports();
}
