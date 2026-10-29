import type { RuleWindow } from './rules'

export const downloadRuleTypes = [
  { type: 'VIDEO', label: '视频' }, { type: 'PHOTO', label: '图片' }, { type: 'AUDIO', label: '音频' },
  { type: 'DOCUMENT', label: '文档' }, { type: 'OTHER', label: '其他' },
] as const
export type DownloadRuleType = typeof downloadRuleTypes[number]['type']
export type DownloadRuleSourceMode = 'CHANNEL' | 'POST'
export interface DownloadRuleInput {
  name: string; source: string; tags: string; keywords: string; types: DownloadRuleType[]; includeMain: boolean
  targetDirectory: string; window: RuleWindow
  sourceMode?: DownloadRuleSourceMode; postKey?: string | null; mainMessageId?: string | null
}
export interface DownloadRuleJob {
  taskId: string; ruleVersion: number; state: 'QUEUED' | 'RUNNING' | 'PAUSED' | 'FAILED' | 'COMPLETED'
  total: number; completed: number; error: string | null; manual: boolean; held: boolean
}
export interface DownloadRuleView {
  rule: {
    id: string; accountId: string; version: number; revision: number; enabled: boolean; nextScanAt: number
    createdAt: number; updatedAt: number; input: DownloadRuleInput; channelId: string; channelTitle: string
  }
  summary: {
    historyDone: boolean; scanned: number; matched: number; unresolved: number; commentPages: number; media: number
    completed: number; nextScanAt: number; scanError: string | null; job: DownloadRuleJob | null; errors?: number
  }
  state: 'PAUSED' | 'WAITING_WINDOW' | 'SCANNING' | 'DOWNLOADING' | 'WAITING' | 'ATTENTION'
  busy: boolean; nextAllowedAt: number
}
export interface DownloadRuleVersion { version: number; createdAt: number; input: DownloadRuleInput; channelId?: string; channelTitle?: string }
const count = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0
const text = (value: unknown, limit: number, required = false): value is string => typeof value === 'string' && value.length <= limit && (!required || !!value.trim())
const nullableText = (value: unknown) => value === null || typeof value === 'string'
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(value)
function channelId(value: unknown): value is string {
  if (typeof value !== 'string' || !/^-?[1-9]\d{0,18}$/.test(value)) return false
  const number = BigInt(value)
  return number >= -9223372036854775808n && number <= 9223372036854775807n
}
export const downloadRuleSourceMode = (input: DownloadRuleInput): DownloadRuleSourceMode => input.sourceMode ?? 'CHANNEL'
export function validDownloadPostSource(value: string) {
  try {
    const url = new URL(value)
    return url.protocol === 'https:' && ['t.me', 'telegram.me'].includes(url.hostname) && !url.port && !url.username && !url.password && !url.hash
      && /^\/(?:c\/[1-9]\d{0,18}|[a-zA-Z][a-zA-Z\d_]{0,63})\/[1-9]\d{0,18}$/.test(url.pathname)
      && (!url.search || /^\?single(?:=1)?$/.test(url.search))
  } catch { return false }
}
function validSavedSource(input: DownloadRuleInput, account: string, channel: string) {
  if (downloadRuleSourceMode(input) === 'CHANNEL') return input.source === channel
  if (!validDownloadPostSource(input.source) || !input.source.startsWith('https://t.me/') || new URL(input.source).search || typeof input.postKey !== 'string'
    || !input.postKey.startsWith(`${account}:${channel}:`) || input.postKey.length > 512
    || typeof input.mainMessageId !== 'string' || !/^[1-9]\d{0,18}$/.test(input.mainMessageId)
    || BigInt(input.mainMessageId) > 9223372036854775807n) return false
  const parts = new URL(input.source).pathname.split('/')
  return parts[1] !== 'c' || channel === `-100${parts[2]}`
}
export function validDownloadRuleInput(value: unknown): value is DownloadRuleInput {
  if (!value || typeof value !== 'object') return false
  const input = value as DownloadRuleInput, window = input.window
  return text(input.name, 80, true) && text(input.source, 2048, true) && text(input.tags, 2048) && text(input.keywords, 2048)
    && (input.sourceMode === undefined || input.sourceMode === 'CHANNEL' || input.sourceMode === 'POST')
    && (downloadRuleSourceMode(input) !== 'POST' || validDownloadPostSource(input.source) && !input.tags && !input.keywords)
    && Array.isArray(input.types) && (input.types.length > 0 || input.includeMain === true) && input.types.length <= 5
    && input.types.every(type => downloadRuleTypes.some(item => item.type === type)) && new Set(input.types).size === input.types.length
    && typeof input.includeMain === 'boolean' && text(input.targetDirectory, 4096, true)
    && !!window && window.timezone === 'Asia/Shanghai' && (window.mode === 'ALL_DAY' ? window.start === null && window.end === null
      : window.mode === 'WINDOW' && typeof window.start === 'string' && typeof window.end === 'string'
        && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(window.start) && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(window.end) && window.start !== window.end)
}
export function validDownloadRule(value: unknown, account: string | null, before?: DownloadRuleView): value is DownloadRuleView {
  if (!value || typeof value !== 'object') return false
  const view = value as DownloadRuleView, rule = view.rule, summary = view.summary, job = summary?.job
  if (!rule || !summary || !uuid(rule.id) || rule.accountId !== account || !count(rule.version) || !rule.version
    || !count(rule.revision) || !rule.revision || typeof rule.enabled !== 'boolean' || !count(rule.nextScanAt)
    || !count(rule.createdAt) || !count(rule.updatedAt) || rule.updatedAt < rule.createdAt || !validDownloadRuleInput(rule.input)
    || !channelId(rule.channelId) || !validSavedSource(rule.input, rule.accountId, rule.channelId) || !text(rule.channelTitle, 4096)
    || typeof summary.historyDone !== 'boolean' || ![summary.scanned, summary.matched, summary.unresolved, summary.commentPages, summary.media, summary.completed, summary.nextScanAt].every(count)
    || summary.errors !== undefined && (!count(summary.errors) || summary.errors > summary.unresolved)
    || !nullableText(summary.scanError) || !['PAUSED', 'WAITING_WINDOW', 'SCANNING', 'DOWNLOADING', 'WAITING', 'ATTENTION'].includes(view.state)
    || typeof view.busy !== 'boolean' || !count(view.nextAllowedAt)) return false
  if (job !== null && (!job || !uuid(job.taskId) || !count(job.ruleVersion) || !job.ruleVersion || job.ruleVersion > rule.version
    || !['QUEUED', 'RUNNING', 'PAUSED', 'FAILED', 'COMPLETED'].includes(job.state) || !count(job.total) || !job.total
    || !count(job.completed) || job.completed > job.total || !nullableText(job.error) || typeof job.manual !== 'boolean' || typeof job.held !== 'boolean'
    || job.state === 'COMPLETED' && job.completed !== job.total)) return false
  return !before || rule.id === before.rule.id && rule.createdAt === before.rule.createdAt && rule.version >= before.rule.version
    && rule.revision >= before.rule.revision && (rule.version !== before.rule.version
      || rule.channelId === before.rule.channelId && sameDownloadRuleInput(rule.input, before.rule.input))
}
export function sameDownloadRuleInput(left: DownloadRuleInput, right: DownloadRuleInput, includeSource = true) {
  return downloadRuleSourceMode(left) === downloadRuleSourceMode(right)
    && (!includeSource || left.source === right.source && left.postKey === right.postKey && left.mainMessageId === right.mainMessageId)
    && left.name === right.name && left.tags === right.tags && left.keywords === right.keywords
    && left.types.length === right.types.length && left.types.every(type => right.types.includes(type)) && left.includeMain === right.includeMain
    && left.targetDirectory === right.targetDirectory && left.window.mode === right.window.mode && left.window.start === right.window.start
    && left.window.end === right.window.end && left.window.timezone === right.window.timezone
}
export function validDownloadRuleVersions(value: unknown): value is DownloadRuleVersion[] {
  return Array.isArray(value) && value.length <= 20 && value.every(item => !!item && count(item.version) && item.version > 0
    && count(item.createdAt) && validDownloadRuleInput(item.input) && (item.channelId === undefined || channelId(item.channelId))
    && (item.channelTitle === undefined || typeof item.channelTitle === 'string')) && new Set(value.map(item => item.version)).size === value.length
}
// Search text, source and directory are canonicalized by the server before saving.
export function sameDownloadRuleSelection(left: DownloadRuleInput, right: DownloadRuleInput) {
  return downloadRuleSourceMode(left) === downloadRuleSourceMode(right)
    && left.name === right.name && left.types.length === right.types.length && left.types.every(type => right.types.includes(type))
    && left.includeMain === right.includeMain && left.window.mode === right.window.mode && left.window.start === right.window.start
    && left.window.end === right.window.end && left.window.timezone === right.window.timezone
}
export const downloadRuleStates: Record<DownloadRuleView['state'], string> = {
  PAUSED: '已暂停', WAITING_WINDOW: '等待北京时间窗口', SCANNING: '正在扫描', DOWNLOADING: '正在下载', WAITING: '已启用，等待检查', ATTENTION: '需要处理',
}
export function canCheckDownloadRule(view: DownloadRuleView) {
  return view.rule.enabled && !view.busy && view.state !== 'WAITING_WINDOW'
}
export function canTakeoverDownloadRule(view: DownloadRuleView) {
  const job = view.summary.job
  return !!job && !job.manual && ['PAUSED', 'FAILED'].includes(job.state) && !view.busy
}
