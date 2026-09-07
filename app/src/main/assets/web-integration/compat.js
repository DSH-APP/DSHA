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
