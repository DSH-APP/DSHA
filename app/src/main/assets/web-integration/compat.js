/* 继承 1.1.10 的提前补齐策略；保留原生实现，取消时转发源 reason 并回收监听。 */
(function (g) {
  'use strict';
  function define(target, name, value) {
    Object.defineProperty(target, name, { configurable: true, writable: true, value: value });
  }
  function failure(message, name) {
    if (typeof g.DOMException === 'function') return new g.DOMException(message, name);
    var error = new Error(message); error.name = name; return error;
  }
  if (typeof g.AbortController === 'function' && typeof g.AbortSignal === 'function') {
    var Type = g.AbortSignal;
    var readAborted = Object.getOwnPropertyDescriptor(Type.prototype, 'aborted').get;
    var add = g.EventTarget.prototype.addEventListener, remove = g.EventTarget.prototype.removeEventListener;
    if (typeof Type.any !== 'function') define(Type, 'any', function any(signals) {
      if (signals == null || typeof signals[Symbol.iterator] !== 'function') throw new TypeError('Expected an iterable of AbortSignals');
      var sources = Array.from(signals), controller = new g.AbortController(), bindings = [], done = false;
      // 先验证所有成员，非法成员不会留下半套监听。
      sources.forEach(function (signal) { readAborted.call(signal); });
      function finish(signal) {
        if (done || !readAborted.call(signal)) return;
        done = true;
        bindings.forEach(function (binding) { remove.call(binding[0], 'abort', binding[1]); });
        bindings.length = 0;
        controller.abort(signal.reason);
      }
      for (var i = 0; i < sources.length; i++) {
        if (readAborted.call(sources[i])) { finish(sources[i]); return controller.signal; }
      }
      sources.forEach(function (signal, index) {
        if (sources.indexOf(signal) !== index) return;
        var callback = function () { finish(signal); };
        bindings.push([signal, callback]);add.call(signal, 'abort', callback);
      });
      return controller.signal;
    });
    if (typeof Type.timeout !== 'function') define(Type, 'timeout', function timeout(milliseconds) {
      var delay = +milliseconds;
      if (!Number.isFinite(delay)) throw new TypeError('Timeout must be finite');
      delay = delay < 0 ? Math.ceil(delay) : Math.floor(delay);
      if (delay < 0 || delay > Number.MAX_SAFE_INTEGER) throw new TypeError('Timeout is out of range');
      var controller = new g.AbortController();
      function schedule(remaining) {
        var step = Math.min(remaining, 2147483647);
        g.setTimeout(function () {
          if (remaining > step) schedule(remaining - step);
          else controller.abort(failure('The operation timed out', 'TimeoutError'));
        }, step);
      }
      schedule(delay);return controller.signal;
    });
  }
  if (g.crypto && typeof g.crypto.randomUUID !== 'function' && typeof g.crypto.getRandomValues === 'function') {
    define(g.crypto, 'randomUUID', function randomUUID() {
      var bytes = new Uint8Array(16);g.crypto.getRandomValues(bytes);
      bytes[6] = (bytes[6] & 15) | 64;bytes[8] = (bytes[8] & 63) | 128;
      var hex = Array.from(bytes, function (b) { return (b + 256).toString(16).slice(1); }).join('');
      return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
    });
  }
})(typeof globalThis === 'object' ? globalThis : self);

/* dsh 的 HTML 启动信号先于 module 执行；兼容补丁必须在文档起始注入。 */
if (typeof Promise.withResolvers !== 'function') {
  Object.defineProperty(Promise,'withResolvers',{configurable:true,writable:true,value:function(){
    var resolve,reject;
    var promise=new this(function(res,rej){
      if (resolve !== undefined || reject !== undefined) throw new TypeError('Promise executor already called');
      resolve=res;reject=rej;
    });
    if (typeof resolve !== 'function' || typeof reject !== 'function') throw new TypeError('Invalid Promise constructor');
    return {promise:promise,resolve:resolve,reject:reject};
  }});
}

/* 新版 PDF.js 使用集合插入接口；旧 WebView / Gecko 在文档开始时补齐，保留原生实现。 */
(function () {
  'use strict';
  function installMap(Type, weak) {
    if (typeof Type !== 'function') return;
    var proto = Type.prototype, has = proto.has, get = proto.get, set = proto.set;
    var probe = weak ? new Type() : null;
    function validate(key) {
      if (weak) { set.call(probe, key, undefined); proto.delete.call(probe, key); }
    }
    if (typeof proto.getOrInsert !== 'function') Object.defineProperty(proto, 'getOrInsert', {
      configurable: true, writable: true, value: function getOrInsert(key, value) {
        var present = has.call(this, key); validate(key);
        if (present) return get.call(this, key);
        set.call(this, key, value); return value;
      }
    });
    if (typeof proto.getOrInsertComputed !== 'function') Object.defineProperty(proto, 'getOrInsertComputed', {
      configurable: true, writable: true, value: function getOrInsertComputed(key, callback) {
        var present = has.call(this, key); validate(key);
        if (typeof callback !== 'function') throw new TypeError('Callback must be callable');
        if (present) return get.call(this, key);
        if (!weak && key === 0) key = 0;
        var value = callback(key);
        set.call(this, key, value); return value;
      }
    });
  }
  installMap(Map, false);
  installMap(WeakMap, true);
  if (typeof Response === 'function' && typeof Response.prototype.bytes !== 'function') {
    var arrayBuffer = Response.prototype.arrayBuffer;
    Object.defineProperty(Response.prototype, 'bytes', {configurable: true, writable: true, value: function bytes() {
      return arrayBuffer.call(this).then(function (buffer) { return new Uint8Array(buffer); });
    }});
  }
})();
