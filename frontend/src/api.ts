export type ProxyType = 'DIRECT' | 'SOCKS5' | 'HTTP'
export interface Settings {
  apiId: number
  apiHashStored: boolean
  proxy: { type: ProxyType; host: string; port: number; username: string; passwordStored: boolean }
}
export interface TelegramStatus {
  nativeLoaded: boolean
  nativeVersion: string
  authState: string
  authHint: string | null
  codeDelivery?: {
    type: string
    nextType: string | null
    resendAvailableAt: number | null
    canResend: boolean
  } | null
  connectionState: string
  userId: string | null
  displayName: string | null
  error: string | null
}
export interface Diagnostics {
  javaVersion: string
  os: string
  architecture: string
  dataDirectory: string
  database: { schemaVersion: number; journalMode: string; synchronous: string; integrity: string }
  dpapi: string
  stage: string
}
export interface Bootstrap { stage: string; settings: Settings; telegram: TelegramStatus; diagnostics: Diagnostics }

// Read the per-process launch token, then remove the fragment before rendering.
const incomingToken = new URLSearchParams(window.location.hash.slice(1)).get('token')
if (window.location.hash) window.history.replaceState(null, '', window.location.pathname + window.location.search)
let appToken = incomingToken ?? ''
try {
  if (incomingToken) sessionStorage.setItem('telegram-workbench-token', incomingToken)
  else appToken = sessionStorage.getItem('telegram-workbench-token') ?? ''
} catch { /* Initial tab still works when the browser blocks sessionStorage. */ }
export const hasAppToken = Boolean(appToken)
export function clearAppToken() {
  appToken = ''
  try { sessionStorage.removeItem('telegram-workbench-token') } catch { /* Storage unavailable. */ }
}
export class ApiError extends Error {
  readonly status: number
  constructor(message: string, status: number) { super(message); this.name = 'ApiError'; this.status = status }
}
export async function api<T>(path: string, options: { method?: string; body?: unknown; timeout?: number } = {}): Promise<T> {
  if (!appToken) throw new ApiError('请从项目启动脚本打开本机界面。', 401)
  const controller = new AbortController()
  const timer = window.setTimeout(() => controller.abort(), options.timeout ?? 35000)
  const method = options.method ?? 'GET'
  const mutation = !['GET', 'HEAD'].includes(method)
  try {
    const response = await fetch(`/api${path}`, {
      method,
      headers: { 'X-App-Token': appToken, ...(mutation ? { 'Content-Type': 'application/json' } : {}) },
      body: mutation ? JSON.stringify(options.body ?? {}) : undefined,
      cache: 'no-store', credentials: 'same-origin', signal: controller.signal,
    })
    if (!response.ok) {
      let message = `请求失败（HTTP ${response.status}）`
      try { const error = await response.json() as { message?: unknown }; if (typeof error.message === 'string') message = error.message } catch { /* Keep HTTP error. */ }
      throw new ApiError(message, response.status)
    }
    if (response.status === 204) return undefined as T
    const body = await response.text()
    return (body ? JSON.parse(body) : undefined) as T
  } catch (error) {
    if (error instanceof ApiError) throw error
    if (controller.signal.aborted) throw new Error('本机服务响应超时，请检查后台是否仍在运行后重试。')
    throw new Error('无法连接本机服务，请检查后台是否仍在运行。')
  } finally { window.clearTimeout(timer) }
}
