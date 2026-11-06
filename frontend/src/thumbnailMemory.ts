import type { PostPreview } from './library'

const JPEG_PREFIX = 'data:image/jpeg;base64,'
const UNAVAILABLE: PostPreview = Object.freeze({ status: 'UNAVAILABLE', quality: null, dataUrl: null, width: 0, height: 0 })

export function previewLimits(quality: unknown) {
  if (quality === 'THUMBNAIL') return { bytes: 128 * 1024, edge: 480 }
  if (quality === 'MINI') return { bytes: 32 * 1024, edge: 256 }
  return null
}

function validPreviewDataUrl(value: unknown, maxBytes: number): value is string {
  if (typeof value !== 'string' || !value.startsWith(JPEG_PREFIX) || value.length > JPEG_PREFIX.length + 4 * Math.ceil(maxBytes / 3)) return false
  const encoded = value.slice(JPEG_PREFIX.length)
  if (encoded.length < 4 || encoded.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) return false
  const padding = encoded.endsWith('==') ? 2 : encoded.endsWith('=') ? 1 : 0
  if (encoded.length / 4 * 3 - padding > maxBytes) return false
  try {
    const decoded = atob(encoded)
    return decoded.length >= 4 && decoded.length <= maxBytes && decoded.startsWith('\u00ff\u00d8\u00ff') && decoded.endsWith('\u00ff\u00d9')
  } catch { return false }
}

export function validThumbnailPreview(value: unknown): value is PostPreview {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false
  const preview = value as PostPreview
  if (preview.status === 'UNAVAILABLE') return preview.quality === null && preview.dataUrl === null && preview.width === 0 && preview.height === 0
  const limits = previewLimits(preview.quality)
  return preview.status === 'AVAILABLE' && !!limits && validPreviewDataUrl(preview.dataUrl, limits.bytes)
    && Number.isInteger(preview.width) && preview.width > 0 && preview.width <= limits.edge
    && Number.isInteger(preview.height) && preview.height > 0 && preview.height <= limits.edge
}

export class ThumbnailCancelledError extends Error {
  constructor() { super('缩略图读取已取消。'); this.name = 'ThumbnailCancelledError' }
}
export interface ThumbnailAcquisition { promise: Promise<PostPreview>; cancel: () => void }
export interface ThumbnailMemoryOptions {
  now?: () => number
  maxBytes?: number
  maxEntries?: number
  maxKeyChars?: number
  concurrency?: number
  thumbnailTtlMs?: number
  transientTtlMs?: number
  cooldownMs?: number
}
interface CacheEntry { preview: PostPreview; bytes: number; expiresAt: number }
interface Consumer { resolve: (value: PostPreview) => void; reject: (cause: unknown) => void }
interface Job {
  key: string
  epoch: number
  fetcher: () => Promise<unknown>
  consumers: Set<Consumer>
  started: boolean
  closed: boolean
}

/** A document-local cache. Callers include account and complete media identity in each key. */
export function createThumbnailMemory(options: ThumbnailMemoryOptions = {}) {
  const now = options.now ?? Date.now
  const maxBytes = options.maxBytes ?? 32 * 1024 * 1024
  const maxEntries = options.maxEntries ?? 512
  const maxKeyChars = options.maxKeyChars ?? 64 * 1024
  const concurrency = options.concurrency ?? 2
  const thumbnailTtlMs = options.thumbnailTtlMs ?? 10 * 60 * 1000
  const transientTtlMs = options.transientTtlMs ?? 30 * 1000
  const cooldownMs = options.cooldownMs ?? 30 * 1000
  if (![maxBytes, maxEntries, maxKeyChars, concurrency, thumbnailTtlMs, transientTtlMs, cooldownMs].every(value => Number.isSafeInteger(value) && value > 0)) throw new TypeError('缩略图内存配置无效。')
  const entries = new Map<string, CacheEntry>()
  const jobs = new Map<string, Job>()
  const listeners = new Set<() => void>()
  let queue: Job[] = [], bytes = 0, active = 0, epoch = 0

  function evict(key: string) {
    const entry = entries.get(key)
    if (entry) { bytes -= entry.bytes; entries.delete(key) }
  }
  function readThumbnail(key: string): PostPreview | undefined {
    const entry = entries.get(key)
    if (!entry) return undefined
    if (entry.expiresAt <= now()) { evict(key); return undefined }
    entries.delete(key); entries.set(key, entry)
    return entry.preview
  }
  function remember(key: string, preview: PostPreview, ttl: number) {
    evict(key)
    const weight = (key.length + (preview.dataUrl?.length ?? 0)) * 2
    if (key.length > maxKeyChars || weight > maxBytes) return
    const time = now()
    for (const [savedKey, entry] of entries) if (entry.expiresAt <= time) evict(savedKey)
    entries.set(key, { preview, bytes: weight, expiresAt: time + ttl }); bytes += weight
    while (entries.size > maxEntries || bytes > maxBytes) evict(entries.keys().next().value!)
  }
  function close(job: Job, cause?: unknown, preview?: PostPreview) {
    if (job.closed) return
    job.closed = true
    if (jobs.get(job.key) === job) jobs.delete(job.key)
    for (const consumer of job.consumers) {
      if (preview) consumer.resolve(preview)
      else consumer.reject(cause)
    }
    job.consumers.clear()
  }
  function drain() {
    while (active < concurrency && queue.length) {
      const job = queue.shift()!
      if (job.closed || job.epoch !== epoch || !job.consumers.size) continue
      job.started = true; active++
      void Promise.resolve().then(() => {
        if (job.closed || job.epoch !== epoch) return
        if (!job.consumers.size) { close(job, new ThumbnailCancelledError()); return }
        return job.fetcher()
      }).then(value => {
        if (job.closed || job.epoch !== epoch) return
        if (!validThumbnailPreview(value)) throw new TypeError('缩略图回执无法确认。')
        const preview: PostPreview = Object.freeze({ status: value.status, quality: value.quality, dataUrl: value.dataUrl, width: value.width, height: value.height })
        // A request belongs to its consumers collectively, never to the first component.
        if (job.consumers.size) remember(job.key, preview, preview.quality === 'THUMBNAIL' ? thumbnailTtlMs : transientTtlMs)
        close(job, undefined, preview)
      }).catch(cause => close(job, cause)).finally(() => { active--; drain() })
    }
  }
  function acquireThumbnail(key: string, fetcher: () => Promise<unknown>): ThumbnailAcquisition {
    if (typeof key !== 'string' || !key || typeof fetcher !== 'function') throw new TypeError('缩略图读取参数无效。')
    const cached = readThumbnail(key)
    if (cached) return { promise: Promise.resolve(cached), cancel() {} }
    let job = jobs.get(key)
    if (!job) { job = { key, epoch, fetcher, consumers: new Set(), started: false, closed: false }; jobs.set(key, job); queue.push(job) }
    const currentJob = job
    let consumer!: Consumer
    const promise = new Promise<PostPreview>((resolve, reject) => { consumer = { resolve, reject }; currentJob.consumers.add(consumer) })
    // Teardown may cancel queued work before a component has attached its await handler.
    void promise.catch(() => {})
    drain()
    return { promise, cancel() {
      if (!currentJob.consumers.delete(consumer)) return
      consumer.reject(new ThumbnailCancelledError())
      if (!currentJob.started && !currentJob.consumers.size) {
        close(currentJob, new ThumbnailCancelledError())
        queue = queue.filter(queued => queued !== currentJob)
      }
    } }
  }
  function invalidateThumbnails() {
    epoch++; entries.clear(); bytes = 0; queue = []
    for (const job of [...jobs.values()]) close(job, new ThumbnailCancelledError())
    for (const listener of [...listeners]) { try { listener() } catch { /* One view cannot prevent other views from clearing their images. */ } }
  }
  function subscribeThumbnailInvalidation(listener: () => void) {
    listeners.add(listener)
    return () => { listeners.delete(listener) }
  }
  function forgetThumbnail(key: string, failed?: PostPreview) {
    if (!key) return
    const job = jobs.get(key)
    if (failed !== undefined) {
      // A stale DOM image can fail after its TTL while another view is fetching
      // a replacement. It cannot cancel that request or poison its new result.
      if (job) return
      const cached = entries.get(key)?.preview
      if (!cached || cached.status !== failed.status || cached.quality !== failed.quality
        || cached.dataUrl !== failed.dataUrl || cached.width !== failed.width || cached.height !== failed.height) return
    }
    if (job) { close(job, new ThumbnailCancelledError()); queue = queue.filter(queued => queued !== job) }
    remember(key, UNAVAILABLE, cooldownMs)
  }
  return { readThumbnail, acquireThumbnail, invalidateThumbnails, subscribeThumbnailInvalidation, forgetThumbnail }
}

const memory = createThumbnailMemory()
export const { readThumbnail, acquireThumbnail, invalidateThumbnails, subscribeThumbnailInvalidation, forgetThumbnail } = memory
