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
