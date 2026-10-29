export type Coverage = 'PARTIAL' | 'COMPLETE' | 'UNAVAILABLE'
export interface MessageRef { chatId: string; messageId: string; link: string | null }
export interface Media {
  key: string
  message: MessageRef
  source: 'MAIN' | 'COMMENT'
  type: 'VIDEO' | 'PHOTO' | 'AUDIO' | 'DOCUMENT' | 'OTHER'
  fileName: string
  size: number
  caption: string
}
export interface Post {
  key: string
  accountId: string
  channelId: string
  channelTitle: string
  mainMessageId: string
  title: string
  text: string
  albumId: string | null
  albumEvidence?: {
    complete: boolean
    method: string
    newerBoundaryId: string | null
    olderBoundaryId: string | null
  } | null
  mainMessages: MessageRef[]
  mainMedia: Media[]
  discussionChatId: string | null
  discussionRootMessageId: string | null
  discussionRoots: MessageRef[]
  canReadComments: boolean
  mainCoverage?: Coverage
  coverage: Coverage
  warnings: string[]
}
export interface CommentPage {
  postKey: string
  media: Media[]
  messages: MessageRef[]
  messageCount: number
  nextCursor: string | null
  exhausted: boolean
  coverage: Coverage
  warnings: string[]
  commentCoverage?: Coverage
  commentWarnings?: string[]
  observations?: {
    message: MessageRef
    mediaComplete: boolean
    warnings: string[]
  }[]
  scanWarnings?: string[]
}
export interface PostDetail {
  post: Post
  mainCoverage?: Coverage
  commentCoverage?: Coverage
  indexedCommentMessages?: number
  indexedCommentMedia?: number
  commentWarnings?: string[]
  commentMedia: Media[]
  nextCursor: string | null
  exhausted: boolean
  coverage: Coverage
  warnings: string[]
}
export interface LibraryChannel { id: string; title: string; indexedPosts: number }
export interface CatalogItem {
  post: Post
  tags: string[]
  indexedCommentMessages: number
  indexedCommentMedia: number
  updatedAt: number
}
export interface CatalogPage {
  items: CatalogItem[]
  limit: number
  offset: number
  total: number
  indexedPosts: number
  indexedChannels: number
  hasMore: boolean
  scope: 'INDEXED_ONLY'
}
export interface MediaCatalogPage {
  items: Media[]
  limit: number
  offset: number
  total: number
  hasMore: boolean
  mainMediaCount: number
  commentMediaCount: number
  scope: 'INDEXED_ONLY'
}
export interface PostPreview {
  status: 'AVAILABLE' | 'UNAVAILABLE'
  quality: 'THUMBNAIL' | 'MINI' | null
  dataUrl: string | null
  width: number
  height: number
}
export function originalMessageLink(link: string | null | undefined) {
  if (!link) return undefined
  try {
    const parsed = new URL(link)
    if (parsed.protocol === 'tg:' || (parsed.protocol === 'https:' && ['t.me', 'telegram.me'].includes(parsed.hostname))) return link
  } catch { /* Unusable links are shown as unavailable, never reconstructed from IDs. */ }
  return undefined
}
export function coverageLabel(coverage: string) {
  const labels: Record<string, string> = { PARTIAL: '已读取部分', COMPLETE: '本轮范围完整', UNAVAILABLE: '范围不可访问' }
  return labels[coverage] ?? `未知范围：${coverage}`
}
export function formatMediaBytes(bytes: number) {
  if (!Number.isFinite(bytes) || bytes < 0) return '大小未知'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB', 'PB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++ }
  return `${value.toFixed(value < 10 ? 2 : 1)} ${units[unit]}`
}
