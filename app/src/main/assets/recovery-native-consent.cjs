'use strict';
// Independent signed recovery launcher. This module never reads a formal bridge
// credential or grants a global bypass. Only the already-owned repair broker is queried.
const http = require('node:http');
const crypto = require('node:crypto');
const fs = require('node:fs');
function invalid(code) { const error = Error(code); error.code = code; return error; }
function current() {
  const instance = process.env.DSHA_RECOVERY_INSTANCE_ID;
  const generation = Number(process.env.DSHA_RECOVERY_GENERATION);
  const token = process.env.DSHA_RECOVERY_BROKER_TOKEN;
  const port = Number(process.env.DSHA_RECOVERY_BROKER_PORT);
  if (!/^[a-f0-9]{32}$/.test(instance || '') || !Number.isSafeInteger(generation) || generation < 1
      || !/^[a-f0-9]{64}$/.test(token || '') || !Number.isInteger(port) || port < 1 || port > 65535)
    throw invalid('RECOVERY_CONSENT_IDENTITY');
  try { fs.lstatSync('/root/.recovery-stop'); throw invalid('RECOVERY_CONSENT_STOPPED'); }
  catch (error) { if (error.code !== 'ENOENT') throw error; }
  return { instance, generation, token, port,
    connection: crypto.createHash('sha256').update(instance + ':' + generation + ':' + token).digest('hex') };
}
function readConnection() {
  return Object.freeze({ applicationId: 'dsh.android.client', generation: current().connection });
}
function bridgeFetch(route, init = {}) {
  if (route !== '/device/consent' || Object.keys(init).some(key => !['signal', 'expectedConnectionGeneration'].includes(key)))
    return Promise.reject(invalid('RECOVERY_CONSENT_QUERY_ONLY'));
  if (init.signal?.aborted) return Promise.reject(init.signal.reason || invalid('RECOVERY_CONSENT_CANCELLED'));
  let owner;
  try {
    owner = current();
    if (owner.connection !== init.expectedConnectionGeneration) throw invalid('RECOVERY_CONSENT_CHANGED');
  } catch (error) { return Promise.reject(error); }
  return new Promise((resolve, reject) => {
    const body = '{}';
    const request = http.request({ hostname: '127.0.0.1', port: owner.port, path: '/v1/consent', method: 'POST',
      headers: { Authorization: 'Bearer ' + owner.token, 'Content-Type': 'application/json', 'Content-Length': 2 },
      signal: init.signal, agent: false }, response => {
      const chunks = []; let length = 0;
      response.on('data', part => {
        length += part.length;
        if (length > 8192) { response.destroy(invalid('RECOVERY_CONSENT_REPLY_LIMIT')); return; }
        chunks.push(part);
      });
      response.on('error', reject);
      response.on('end', () => {
        try {
          if (current().connection !== owner.connection) throw invalid('RECOVERY_CONSENT_CHANGED');
          const value = JSON.parse(Buffer.concat(chunks, length).toString('utf8'));
          if (value.globalAllow !== false || value.recoveryInstanceId !== owner.instance
              || value.webGeneration !== owner.generation) throw invalid('RECOVERY_CONSENT_REPLY');
          resolve({ ok: response.statusCode >= 200 && response.statusCode < 300, status: response.statusCode,
            json: async () => ({ result: JSON.stringify(value) }) });
        } catch (error) { reject(error); }
      });
    });
    request.on('error', reject);
    request.end(body);
  });
}
module.exports = Object.freeze({ readConnection, bridgeFetch });
