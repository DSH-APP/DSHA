// DSHA's bounded local CONNECT proxy for the GitHub CLI.
// The Go CLI bypasses proroot's libc path translation; Node resolves DNS and
// opens the TLS socket for it. This is deliberately not a general proxy.
import net from 'node:net';
import fs from 'node:fs';
import path from 'node:path';

const args = process.argv.slice(2);
const valueAfter = (name, fallback) => {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] !== undefined ? args[index + 1] : fallback;
};
const requestedPort = Number(valueAfter('--port', args.find(value => /^\d+$/.test(value)) || 7891));
const stateDir = valueAfter('--state-dir', process.env.DSHA_GH_PROXY_STATE_DIR || '/tmp/dsha-gh-proxy');
const host = '127.0.0.1';
const maxHeaderBytes = 16 * 1024;
const idleTimeoutMs = 120_000;
const allowedHost = hostname => {
  const normalized = String(hostname || '').toLowerCase().replace(/\.$/, '');
  // Keep github.com to the endpoints used by gh. GitHub's content service
  // uses several dynamic subdomains under githubusercontent.com.
  return new Set([
    'github.com',
    'api.github.com',
    'codeload.github.com',
    'uploads.github.com',
  ]).has(normalized)
    || normalized.endsWith('.githubusercontent.com');
};
const log = (...parts) => {
  try { console.log(new Date().toISOString(), ...parts); } catch { /* diagnostics never affect forwarding */ }
};
const respond = (socket, code, text) => {
  if (!socket.destroyed) socket.end(`HTTP/1.1 ${code} ${text}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n`);
};
const publishPort = port => {
  fs.mkdirSync(stateDir, { recursive: true, mode: 0o700 });
  const temporary = path.join(stateDir, `.port-${process.pid}-${Date.now()}`);
  fs.writeFileSync(temporary, `${port}\n`, { mode: 0o600 });
  fs.renameSync(temporary, path.join(stateDir, 'port'));
};

if (!Number.isInteger(requestedPort) || requestedPort < 0 || requestedPort > 65535) {
  throw new Error('GH_PROXY_PORT');
}

const server = net.createServer(client => {
  let header = '';
  let settled = false;
  let upstream;
  client.setTimeout(idleTimeoutMs, () => client.destroy());
  client.on('error', () => {});
  const onData = chunk => {
    if (settled) return;
    header += chunk.toString('latin1');
    if (header.length > maxHeaderBytes) {
      settled = true;
      client.off('data', onData);
      respond(client, 431, 'Request Header Fields Too Large');
      return;
    }
    const end = header.indexOf('\r\n\r\n');
    if (end < 0) return;
    settled = true;
    client.off('data', onData);
    client.pause();

    const firstLine = header.slice(0, header.indexOf('\r\n'));
    const match = /^CONNECT\s+([^\s]+)\s+HTTP\/1\.[01]$/i.exec(firstLine);
    if (!match) {
      respond(client, 405, 'Method Not Allowed');
      return;
    }
    const authority = match[1];
    const separator = authority.lastIndexOf(':');
    if (separator <= 0 || separator === authority.length - 1 || authority.includes('[') || authority.includes(']')) {
      respond(client, 400, 'Bad Request');
      return;
    }
    const targetHost = authority.slice(0, separator).toLowerCase().replace(/\.$/, '');
    const portText = authority.slice(separator + 1);
    if (!/^\d{1,5}$/.test(portText)) {
      respond(client, 400, 'Bad Request');
      return;
    }
    const targetPort = Number(portText);
    if (!allowedHost(targetHost)) {
      log('DENY', targetHost);
      respond(client, 403, 'Forbidden');
      return;
    }
    if (targetPort !== 443) {
      respond(client, 403, 'Forbidden');
      return;
    }

    const buffered = Buffer.from(header.slice(end + 4), 'latin1');
    upstream = net.connect({ host: targetHost, port: targetPort });
    upstream.setTimeout(idleTimeoutMs, () => upstream.destroy());
    let connected = false;
    upstream.on('error', () => {
      if (!connected) respond(client, 502, 'Bad Gateway');
      if (!upstream.destroyed) upstream.destroy();
    });
    upstream.on('connect', () => {
      connected = true;
      log('CONNECT', `${targetHost}:${targetPort}`);
      if (client.destroyed) {
        upstream.destroy();
        return;
      }
      client.write('HTTP/1.1 200 Connection Established\r\n\r\n');
      if (buffered.length) upstream.write(buffered);
      client.pipe(upstream);
      upstream.pipe(client);
      client.resume();
    });
    client.on('close', () => { if (upstream && !upstream.destroyed) upstream.destroy(); });
  };
  client.on('data', onData);
});

const listen = port => {
  server.listen({ port, host }, () => {
    const actual = server.address()?.port;
    if (!Number.isInteger(actual)) throw new Error('GH_PROXY_ADDRESS');
    publishPort(actual);
    log('listening', `${host}:${actual}`);
  });
};
server.on('error', error => {
  if (error.code === 'EADDRINUSE' && server.address() === null && requestedPort !== 0) {
    log('port occupied, selecting an ephemeral port', requestedPort);
    listen(0);
    return;
  }
  log('listen failed', error.message);
  process.exitCode = 1;
});
listen(requestedPort);
