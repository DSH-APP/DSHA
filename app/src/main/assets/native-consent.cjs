'use strict';
// APK-owned launcher query for the authenticated device bridge.
//
// The native bridge binds one of 3090-3099 (order depends on the package); any
// of them may belong to another DSHA installation on the same device. The
// selected port is published in this installation's rootfs at
// /root/.dsh/.bridge_port; guests must use it and never guess a fixed port.
// An unpublished port fails closed before the token is sent.
const fs = require('node:fs');
const http = require('node:http');
const crypto = require('node:crypto');
const TOKEN = '/root/.dsh/.bridge_token';
const bridgePort = require('./bridge-port.cjs');
const LIMIT = 8192;

function invalid(code) {
  const error = Error(code);
  error.code = code;
  return error;
}

function credential() {
  for (const parent of ['/root', '/root/.dsh']) {
    const info = fs.lstatSync(parent);
    if (!info.isDirectory() || info.isSymbolicLink()) throw invalid('NATIVE_CONSENT_TOKEN_PARENT');
  }
  const before = fs.lstatSync(TOKEN);
  if (!before.isFile() || before.isSymbolicLink() || before.size < 1 || before.size > 129
      || (before.mode & 0o077) !== 0) throw invalid('NATIVE_CONSENT_TOKEN_FILE');
  const fd = fs.openSync(TOKEN, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW);
  try {
    const opened = fs.fstatSync(fd);
    if (!opened.isFile() || opened.dev !== before.dev || opened.ino !== before.ino
        || opened.size !== before.size || (opened.mode & 0o077) !== 0)
      throw invalid('NATIVE_CONSENT_TOKEN_CHANGED');
    const bytes = Buffer.alloc(130), length = fs.readSync(fd, bytes, 0, bytes.length, 0);
    const token = bytes.subarray(0, length).toString('ascii').trim();
    if (length !== opened.size || !/^[A-Za-z0-9_-]{1,128}$/.test(token))
      throw invalid('NATIVE_CONSENT_TOKEN_INVALID');
    const after = fs.lstatSync(TOKEN);
    if (!after.isFile() || after.dev !== opened.dev || after.ino !== opened.ino
        || after.size !== opened.size || (after.mode & 0o077) !== 0)
      throw invalid('NATIVE_CONSENT_TOKEN_CHANGED');
    return { token, generation: crypto.createHash('sha256').update(token).digest('hex') };
  } finally {
    fs.closeSync(fd);
  }
}

function readConnection() {
  return Object.freeze({
    applicationId:
        (typeof process !== 'undefined' && process.env && process.env.DSHA_BRIDGE_APPLICATION_ID)
        || 'io.github.sunsetrne.dsha',
    generation: credential().generation,
    port: bridgePort(),
  });
}

function bridgeFetch(route, init = {}) {
  if (!['/device/consent', '/app/native-policy'].includes(route)
      || Object.keys(init).some(
          key => !['signal', 'expectedConnectionGeneration', 'expectedConnectionPort'].includes(key)))
    return Promise.reject(invalid('NATIVE_CONSENT_QUERY_ONLY'));
  if (init.signal?.aborted) return Promise.reject(init.signal.reason || invalid('NATIVE_CONSENT_CANCELLED'));
  let current, port;
  try {
    current = credential();
    port = bridgePort();
    if (current.generation !== init.expectedConnectionGeneration)
      throw invalid('NATIVE_CONSENT_TOKEN_CHANGED');
    if (init.expectedConnectionPort !== undefined && init.expectedConnectionPort !== port)
      throw invalid('NATIVE_CONSENT_PORT_CHANGED');
  } catch (error) {
    return Promise.reject(error);
  }
  return new Promise((resolve, reject) => {
    const request = http.request({
      protocol: 'http:',
      hostname: '127.0.0.1',
      port,
      path: '/app/native-policy',
      method: 'GET',
      headers: { 'X-Token': current.token },
      signal: init.signal,
      agent: false,
    }, response => {
      const chunks = [];
      let size = 0;
      response.on('data', part => {
        size += part.length;
        if (size > LIMIT) {
          response.destroy(invalid('NATIVE_CONSENT_REPLY_LIMIT'));
          return;
        }
        chunks.push(part);
      });
      response.on('error', reject);
      response.on('end', () => {
        try {
          if (credential().generation !== current.generation)
            throw invalid('NATIVE_CONSENT_TOKEN_CHANGED');
          if (bridgePort() !== port) throw invalid('NATIVE_CONSENT_PORT_CHANGED');
          const body = Buffer.concat(chunks, size).toString('utf8'), status = response.statusCode;
          resolve({
            ok: status >= 200 && status < 300,
            status,
            json: async () => JSON.parse(body),
          });
        } catch (error) {
          reject(error);
        }
      });
    });
    request.on('error', reject);
    request.end();
  });
}

module.exports = Object.freeze({ readConnection, bridgeFetch });
