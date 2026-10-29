import type { CommentPage } from './library'

export interface CommentReadProgress {
  pages: number
  messages: number
  media: number
}

export interface CommentReadResult extends CommentReadProgress {
  reason: 'exhausted' | 'stopped' | 'stale' | 'no-progress' | 'limit'
}

export interface CommentReadOptions {
  postKey: string
  cursor: string | null
  readPage: (cursor: string | null) => Promise<CommentPage>
  isCurrent: () => boolean
  shouldStop: () => boolean
  onPage: (page: CommentPage, progress: CommentReadProgress) => void | Promise<void>
  betweenPages?: () => Promise<void>
  maxPages?: number
  maxDurationMs?: number
  now?: () => number
}

const positiveDecimal = (value: unknown): value is string => typeof value === 'string' && /^[1-9]\d*$/.test(value)
const yieldToControls = () => new Promise<void>(resolve => setTimeout(resolve, 0))

/** Read one persisted page at a time. Limits pause the run; only the server's EOF ends it. */
export async function runCommentRead(options: CommentReadOptions): Promise<CommentReadResult> {
  const maxPages = options.maxPages ?? 200
  const maxDurationMs = options.maxDurationMs ?? 20 * 60 * 1000
  if (!Number.isSafeInteger(maxPages) || maxPages < 0 || !Number.isFinite(maxDurationMs) || maxDurationMs < 0) {
    throw new Error('连续读取的暂停上限无效。')
  }
  const now = options.now ?? (() => performance.now())
  const startedAt = now()
  const betweenPages = options.betweenPages ?? yieldToControls
  let cursor = options.cursor
  let pages = 0
  let messages = 0
  let media = 0
  const result = (reason: CommentReadResult['reason']): CommentReadResult => ({ reason, pages, messages, media })
  const limited = () => pages >= maxPages || now() - startedAt >= maxDurationMs

  while (true) {
    if (!options.isCurrent()) return result('stale')
    if (options.shouldStop()) return result('stopped')
    if (limited()) return result('limit')
    if (cursor !== null && !positiveDecimal(cursor)) return result('no-progress')

    // A stop request never discards a response from an already submitted page. A changed
    // account/post context does: it must not apply a late response to another view.
    const page = await options.readPage(cursor)
    if (!options.isCurrent()) return result('stale')
    if (page.postKey !== options.postKey) throw new Error('评论读取结果与当前主帖不一致，请刷新后重试。')
    pages++
    messages += page.messageCount
    media += page.media.length
    await options.onPage(page, { pages, messages, media })
    if (!options.isCurrent()) return result('stale')
    if (page.exhausted === true) return result('exhausted')
    if (options.shouldStop()) return result('stopped')

    // Do not infer EOF from short/empty pages, coverage or warnings. Even an unusable
    // continuation has already been delivered above so its checkpoint/warnings are visible.
    if (!positiveDecimal(page.nextCursor) || (cursor !== null && BigInt(page.nextCursor) >= BigInt(cursor))) {
      return result('no-progress')
    }
    cursor = page.nextCursor
    if (limited()) return result('limit')
    await betweenPages()
    // Recheck context, stop and elapsed time at the top before issuing another POST.
  }
}
