/**
 * DSHA 内置插件「启动目标」服务端：在 Web 内选择下一次从桌面图标启动时进入主界面还是 Web。
 *
 * 真实值只保存在原生 App（3090 桥 /app/entry-target）；本插件不缓存、不另存配置，
 * 页面每次打开都向宿主回读。路由由 DSH 的 connection 守卫（Host/Origin + 已登录会话），
 * 浏览器拿不到桥 token。
 */
import bridgePort from './bridge-port.cjs'
import { readFile } from 'node:fs/promises'

export const name = 'dsh-launch-target'
export const inject = ['webServer', 'connection']

export const ROUTE = '/api/dsha-launch-target'
export const TARGETS = Object.freeze(['main', 'web'])
const BODY_LIMIT = 256

/** 读取宿主当前值；可选 set 精确为 main / web。桥只接受这两个值，其它输入不发送。 */
export async function bridgeEntryTarget(set) {
  if (set !== undefined && !TARGETS.includes(set)) throw Object.assign(Error('invalid target'), { code: 'invalid-target' })
  const token = (await readFile('/root/.dsh/.bridge_token', 'utf8')).trim()
  if (!token) throw Object.assign(Error('bridge unavailable'), { code: 'bridge-unavailable' })
  const query = set === undefined ? '' : '?set=' + encodeURIComponent(set)
  let port
  try { port = bridgePort() } catch { throw Object.assign(Error('bridge unavailable'), { code: 'bridge-unavailable' }) }
  const response = await fetch('http://127.0.0.1:' + port + '/app/entry-target' + query, {
    headers: { 'X-Token': token }, signal: AbortSignal.timeout(3000)
  })
  const result = (await response.json()).result
  if (!response.ok || typeof result !== 'string') throw Object.assign(Error('bridge rejected'), { code: 'bridge-rejected' })
  // 旧版 App 不认识该端点时返回 [NO_CMD]：如实报告“不支持”，不猜测。
  if (result === '[NO_CMD]') throw Object.assign(Error('unsupported app'), { code: 'unsupported' })
  if (result === 'INVALID_TARGET') throw Object.assign(Error('invalid target'), { code: 'invalid-target' })
  if (result.startsWith('[') || result.startsWith('ERROR')) throw Object.assign(Error('bridge rejected'), { code: 'bridge-rejected' })
  const value = JSON.parse(result)
  if (!TARGETS.includes(value?.target)) throw Object.assign(Error('bridge rejected'), { code: 'bridge-rejected' })
  return { target: value.target }
}

function respond(res, status, body) {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store'
  })
  res.end(payload)
}

/** 浏览器发出的 Origin 必须与请求 Host 相同；缺失 Origin 由 connection 守卫判定。 */
function sameOrigin(req) {
  const origin = req.headers.origin
  if (origin === undefined || origin === '') return true
  try { return new URL(origin).host === req.headers.host } catch { return false }
}

async function readBody(req) {
  let body = ''
  for await (const chunk of req) {
    body += chunk
    if (body.length > BODY_LIMIT) throw Object.assign(Error('body too large'), { code: 'payload-too-large' })
  }
  return body
}

const STATUS = { 'invalid-target': 400, 'payload-too-large': 413, unsupported: 501 }

export function createHandler(connection, bridge = bridgeEntryTarget) {
  return async (req, res) => {
    try {
      const rejection = connection.requestRejection(req)
      if (rejection !== undefined) return respond(res, rejection, { ok: false, error: 'access-denied' })
      if (req.method === 'GET') return respond(res, 200, { ok: true, ...(await bridge()) })
      if (req.method !== 'POST') return respond(res, 405, { ok: false, error: 'method-not-allowed' })
      if (!sameOrigin(req)) return respond(res, 403, { ok: false, error: 'cross-origin' })
      let target
      try { target = JSON.parse(await readBody(req))?.target } catch (error) {
        if (error?.code === 'payload-too-large') throw error
        return respond(res, 400, { ok: false, error: 'invalid-body' })
      }
      if (!TARGETS.includes(target)) return respond(res, 400, { ok: false, error: 'invalid-target' })
      return respond(res, 200, { ok: true, ...(await bridge(target)) })
    } catch (error) {
      const code = typeof error?.code === 'string' ? error.code : 'bridge-unavailable'
      try { respond(res, STATUS[code] ?? 502, { ok: false, error: code }) } catch {}
    }
  }
}

export function apply(ctx) {
  ctx.effect(() => ctx.webServer.register({
    kind: 'exact',
    path: ROUTE,
    handler: createHandler(ctx.connection)
  }), 'dsh-launch-target: entry target route')
}
