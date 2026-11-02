export type PreparationKind = 'observe' | 'preview'
export interface PreparationIdentity { operationId: string; ruleId: string; accountId: string; version: number; revision: number; kind: PreparationKind }
export interface RulePreparation extends PreparationIdentity {
  state: 'RUNNING' | 'COMPLETED' | 'FAILED'; startedAt: number; finishedAt: number | null; error: string | null; result: unknown
}
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value)
const positive = (value: unknown) => Number.isSafeInteger(value) && Number(value) > 0
const stamp = (value: unknown) => Number.isSafeInteger(value) && Number(value) >= 0
export function validRulePreparation(value: unknown, expected: PreparationIdentity): value is RulePreparation {
  if (!value || typeof value !== 'object') return false
  const job = value as RulePreparation
  if (!uuid(job.operationId) || !uuid(job.ruleId) || !positive(job.version) || !positive(job.revision)
    || !['observe','preview'].includes(job.kind) || !stamp(job.startedAt)
    || (['operationId','ruleId','accountId','version','revision','kind'] as const).some(key => job[key] !== expected[key])) return false
  if (job.state === 'RUNNING') return job.finishedAt === null && job.error === null && job.result === null
  if (!stamp(job.finishedAt) || job.finishedAt! < job.startedAt) return false
  if (job.state === 'FAILED') return typeof job.error === 'string' && !!job.error.trim() && job.error.length <= 8192 && job.result === null
  return job.state === 'COMPLETED' && job.error === null && !!job.result && typeof job.result === 'object'
}
export function preparationElapsed(startedAt: number, now: number): string {
  const seconds = Math.max(0, Math.floor((now - startedAt) / 1000))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2,'0')}`
}
type SecureRandom = { randomUUID?: () => string; getRandomValues?: (bytes: Uint8Array<ArrayBuffer>) => Uint8Array<ArrayBuffer> }
export function rulePreparationId(provider: SecureRandom | undefined = globalThis.crypto): string {
  try { const value = provider?.randomUUID?.(); if (typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) return value } catch { /* Use Web Crypto below. */ }
  try {
    if (typeof provider?.getRandomValues !== 'function') throw new Error('No secure random source')
    const bytes = new Uint8Array(16); provider.getRandomValues(bytes); bytes[6] = (bytes[6]! & 15) | 64; bytes[8] = (bytes[8]! & 63) | 128
    const hex = Array.from(bytes, byte => byte.toString(16).padStart(2,'0')).join('')
    return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`
  } catch { throw new Error('当前浏览器无法生成安全操作号，请使用支持安全随机数的浏览器后重试。') }
}
