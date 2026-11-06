import type { Media } from './library'
import { validTask, type DownloadTask } from './downloadTasks.ts'

export interface BotPreparation {
  operationId: string; state: 'PREPARING' | 'READY' | 'FAILED'; task: DownloadTask | null; error: string | null
}
export function validBotPreparation(value: unknown): value is BotPreparation {
  if (!value || typeof value !== 'object') return false
  const receipt = value as BotPreparation
  return botUuid(receipt.operationId) && (receipt.state === 'PREPARING' ? receipt.task === null && receipt.error === null
    : receipt.state === 'READY' ? validTask(receipt.task) && receipt.error === null
      : receipt.state === 'FAILED' && receipt.task === null && typeof receipt.error === 'string' && !!receipt.error.trim() && receipt.error.length <= 8192)
}

export type BotMedia = Omit<Media, 'source'> & { source: 'BOT' }
export interface BotButton {
  id: string; text: string; kind: 'NEXT' | 'WINDOW' | 'PAGE' | 'PREVIOUS' | 'OTHER' | 'UNSUPPORTED'
  messageId: string; row: number; column: number; supported: boolean
}
export interface BotMessage { id: string; text: string; media: BotMedia[]; buttons: BotButton[] }
export interface BotWait {
  waitSeconds: number; timeoutSeconds: number; elapsedMs: number; quietMs: number; remainingMs: number
  progressObserved: boolean; timedOut: boolean
}
export interface BotPreview {
  id: string; revision: number; bot: { chatId: string; botUserId: string; title: string }
  messages: BotMessage[]; media: BotMedia[]; controlMessageId: string | null; recommendedButtonId: string | null
  paginationStatus: 'MORE' | 'END' | 'UNKNOWN'; currentPage: number | null; totalPages: number | null
  state: 'READY' | 'WAITING' | 'NEEDS_REVIEW'; warning: string | null; nextHistoryCursor: null; historyEnd: true
  wait?: BotWait | null
}
const text = (value: unknown, max: number, empty = false): value is string => typeof value === 'string' && value.length <= max && (empty || !!value.trim())
export const botUuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(value)
type BotCrypto = { randomUUID?: () => string; getRandomValues?: (bytes: Uint8Array<ArrayBuffer>) => Uint8Array<ArrayBuffer> }
/** Older embedded browsers can lack randomUUID while still providing Web Crypto randomness. */
export function botOperationId(provider: BotCrypto | undefined = globalThis.crypto): string {
  try {
    const value = provider?.randomUUID?.()
    if (typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) return value
  } catch { /* Continue only with the browser's cryptographically secure random source. */ }
  try {
    if (typeof provider?.getRandomValues !== 'function') throw new Error('Secure randomness unavailable')
    const bytes = new Uint8Array(16); provider.getRandomValues(bytes)
    bytes[6] = (bytes[6]! & 0x0f) | 0x40; bytes[8] = (bytes[8]! & 0x3f) | 0x80
    const hex = Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('')
    return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`
  } catch { throw new Error('当前浏览器无法生成安全操作号，请使用支持安全随机数的浏览器后重试。') }
}
const id = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d{0,18}$/.test(value) && BigInt(value) <= 9223372036854775807n
const count = (value: unknown, max: number) => Number.isSafeInteger(value) && Number(value) >= 0 && Number(value) <= max
export function validBotWaitSettings(waitSeconds: unknown, timeoutSeconds: unknown): boolean {
  return count(waitSeconds, 60) && Number(waitSeconds) >= 2 && count(timeoutSeconds, 300) && Number(timeoutSeconds) >= 15
    && Number(timeoutSeconds) >= 2 * Number(waitSeconds)
}
export function validBotWait(value: unknown): value is BotWait {
  if (!value || typeof value !== 'object') return false
  const wait = value as BotWait
  return validBotWaitSettings(wait.waitSeconds, wait.timeoutSeconds) && count(wait.elapsedMs, Number.MAX_SAFE_INTEGER)
    && count(wait.quietMs, wait.elapsedMs) && count(wait.remainingMs, wait.timeoutSeconds * 1000)
    && typeof wait.progressObserved === 'boolean' && typeof wait.timedOut === 'boolean' && (!wait.timedOut || wait.remainingMs === 0)
}
/** The first read observes the minimum buffer; later reads remain serial and bounded by the original deadline. */
export function botObservationDelay(waitSeconds: number, elapsedMs: number, remainingMs: number, first: boolean): number {
  return Math.max(0, Math.min(remainingMs, first ? Math.max(0, waitSeconds * 1000 - elapsedMs) : 2000))
}
function mediaValid(value: unknown, chatId: string): value is BotMedia {
  if (!value || typeof value !== 'object') return false
  const media = value as BotMedia, ref = media.message
  return text(media.key, 512) && media.source === 'BOT' && ['VIDEO', 'PHOTO', 'AUDIO', 'DOCUMENT', 'OTHER'].includes(media.type)
    && text(media.fileName, 1024) && count(media.size, Number.MAX_SAFE_INTEGER) && text(media.caption, 65536, true)
    && !!ref && ref.chatId === chatId && id(ref.messageId) && (ref.link === null || text(ref.link, 2048) && /^https:\/\/(?:t\.me|telegram\.me)\//.test(ref.link))
}
export function validBotPreview(value: unknown): value is BotPreview {
  if (!value || typeof value !== 'object') return false
  const view = value as BotPreview, bot = view.bot
  if (!botUuid(view.id) || !count(view.revision, Number.MAX_SAFE_INTEGER) || view.revision < 1 || !bot || !id(bot.chatId) || !id(bot.botUserId) || !text(bot.title, 512)
    || !['READY', 'WAITING', 'NEEDS_REVIEW'].includes(view.state) || !['MORE', 'END', 'UNKNOWN'].includes(view.paginationStatus)
    || view.warning !== null && !text(view.warning, 8192, true) || view.historyEnd !== true || view.nextHistoryCursor !== null
    || view.wait != null && !validBotWait(view.wait)
    || !Array.isArray(view.messages) || view.messages.length > 50 || !Array.isArray(view.media)
    || !view.media.every(item => mediaValid(item, bot.chatId)) || new Set(view.media.map(item => item.key)).size !== view.media.length) return false
  if ([view.currentPage, view.totalPages].some(value => value !== null && (!count(value, Number.MAX_SAFE_INTEGER) || value < 1))
    || view.currentPage !== null && view.totalPages !== null && view.currentPage > view.totalPages) return false
  const media = new Map(view.media.map(item => [item.key, item])), buttons = new Map<string, BotButton>(), messageIds = new Set<string>()
  let totalButtons = 0
  for (const message of view.messages) {
    if (!message || !id(message.id) || messageIds.has(message.id) || !text(message.text, 65536, true)
      || !Array.isArray(message.media) || message.media.length > 50 || !Array.isArray(message.buttons) || message.buttons.length > 100) return false
    messageIds.add(message.id)
    for (const item of message.media) {
      if (!mediaValid(item, bot.chatId) || item.message.messageId !== message.id) return false
      const known = media.get(item.key)
      if (!known || known.type !== item.type || known.fileName !== item.fileName || known.size !== item.size || known.caption !== item.caption || known.message.messageId !== item.message.messageId || known.message.link !== item.message.link) return false
    }
    const positions = new Set<string>()
    for (const button of message.buttons) {
      const position = `${button?.row}:${button?.column}`
      if (!button || !text(button.id, 256) || buttons.has(button.id) || !text(button.text, 512) || button.messageId !== message.id
        || !count(button.row, 99) || !count(button.column, 99) || positions.has(position) || typeof button.supported !== 'boolean'
        || !['NEXT', 'WINDOW', 'PAGE', 'PREVIOUS', 'OTHER', 'UNSUPPORTED'].includes(button.kind)
        || button.kind === 'UNSUPPORTED' && button.supported || 'data' in button || 'url' in button) return false
      positions.add(position); buttons.set(button.id, button); totalButtons++
    }
  }
  return totalButtons <= 100 && (view.controlMessageId === null || messageIds.has(view.controlMessageId))
    && (view.recommendedButtonId === null || !!buttons.get(view.recommendedButtonId)?.supported
      && buttons.get(view.recommendedButtonId)?.messageId === view.controlMessageId
      && ['NEXT', 'WINDOW', 'PAGE'].includes(buttons.get(view.recommendedButtonId)!.kind))
}
export const botMediaNames: Record<string, string> = { VIDEO: '视频', PHOTO: '图片', AUDIO: '音频', DOCUMENT: '文档', OTHER: '其他' }
