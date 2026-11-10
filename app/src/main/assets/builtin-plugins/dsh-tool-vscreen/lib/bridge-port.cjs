'use strict';
// 随插件部署的桥端口读取器：不缓存端口，不读取凭据，不发送请求。
const fs = require('node:fs');
const PORT_FILE = '/root/.dsh/.bridge_port';

function decimalPort(raw) {
  // 不 trim 或 Number(env)：空白、Unicode 数字、指数及其它进制都不是端口契约。
  if (typeof raw !== 'string' || raw.length < 1 || raw.length > 5 || /[^0-9]/.test(raw)) return undefined;
  const value = Number(raw);
  return value >= 1 && value <= 65535 ? value : undefined;
}
function regular(info) {
  return info.isFile() && !info.isSymbolicLink() && info.size >= 1 && info.size <= 5
    && (info.mode & 0o077) === 0;
}
function sameFile(left, right) {
  return regular(right) && ['dev', 'ino', 'mode', 'size', 'mtimeMs', 'ctimeMs']
    .every(key => left[key] === right[key]);
}
function bridgePort() {
  try {
    const before = fs.lstatSync(PORT_FILE);
    if (!regular(before)) throw Error('BRIDGE_PORT_FILE');
    // 即使检查后换成 FIFO，也不能卡住 Agent；打开后仍只接受同一普通文件。
    const fd = fs.openSync(PORT_FILE, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW | fs.constants.O_NONBLOCK);
    try {
      const opened = fs.fstatSync(fd);
      if (!sameFile(before, opened)) throw Error('BRIDGE_PORT_CHANGED');
      const bytes = Buffer.alloc(6);
      const count = fs.readSync(fd, bytes, 0, bytes.length, 0);
      if (count !== opened.size || !sameFile(opened, fs.fstatSync(fd))
          || !sameFile(opened, fs.lstatSync(PORT_FILE))) throw Error('BRIDGE_PORT_CHANGED');
      // latin1 保留高位字节；ascii 解码会把 0xb3 等折成合法数字。
      const port = decimalPort(bytes.subarray(0, count).toString('latin1'));
      if (port !== undefined) return port;
    } finally {
      fs.closeSync(fd);
    }
  } catch {
    // 端口仅负责发现，不赋予权限；后续请求仍必须通过当前 token 鉴权。
  }
  const explicit = decimalPort(typeof process !== 'undefined' ? process.env?.DSHA_BRIDGE_PORT : undefined);
  if (explicit !== undefined) return explicit;
  // 不回退任何固定端口：3090 及 3091–3099 可能属于同机另一个 DSHA 安装，
  // 猜一个端口再带上本安装 token 发过去，等于把凭据交给别的应用。
  throw Object.assign(Error('BRIDGE_PORT_UNAVAILABLE'), { code: 'BRIDGE_PORT_UNAVAILABLE' });
}
module.exports = bridgePort;
