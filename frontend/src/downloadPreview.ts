import type { CommentPage, Media, Post } from './library'

export interface DownloadPreview {
  id: string
  post: Post
  comments: CommentPage | null
  pageNumber: number
  observedMessages: number
  exhausted: boolean
  canContinue: boolean
  warnings: string[]
}
export const mediaTypes: { type: Media['type']; label: string }[] = [
  { type: 'VIDEO', label: '视频' }, { type: 'PHOTO', label: '图片' }, { type: 'AUDIO', label: '音频' },
  { type: 'DOCUMENT', label: '文档' }, { type: 'OTHER', label: '其他' },
]
function nativeId(value: unknown, positive = false): value is string {
  if (typeof value !== 'string' || !/^-?[1-9]\d*$/.test(value) || value.length > 20) return false
  const parsed = BigInt(value)
  return parsed >= (positive ? 1n : -9223372036854775808n) && parsed <= 9223372036854775807n
}
function validMedia(value: Media) {
  return value && typeof value.key === 'string' && !!value.key && value.message
    && nativeId(value.message.chatId) && nativeId(value.message.messageId, true)
    && mediaTypes.some(item => item.type === value.type)
    && typeof value.fileName === 'string' && typeof value.caption === 'string'
    && typeof value.size === 'number' && Number.isFinite(value.size)
}
export function validDownloadPreview(value: DownloadPreview, accountId: string, previous?: DownloadPreview): boolean {
  const post = value?.post, page = value?.comments
  if (!value || typeof value.id !== 'string' || !/^[0-9a-f-]{36}$/.test(value.id)
    || !post || post.accountId !== accountId || typeof post.key !== 'string' || !post.key
    || !nativeId(post.channelId) || !nativeId(post.mainMessageId, true) || !Array.isArray(post.mainMedia)
    || post.mainMedia.some(item => !validMedia(item) || item.source !== 'MAIN' || item.message.chatId !== post.channelId)
    || !Number.isSafeInteger(value.pageNumber) || value.pageNumber < 0
    || !Number.isSafeInteger(value.observedMessages) || value.observedMessages < 0
    || typeof value.exhausted !== 'boolean' || typeof value.canContinue !== 'boolean'
    || !Array.isArray(value.warnings) || value.warnings.some(item => typeof item !== 'string')) return false
  if (page === null) return !previous && !post.canReadComments && value.pageNumber === 0
    && value.observedMessages === 0 && !value.exhausted && !value.canContinue
  if (!post.canReadComments || !page || page.postKey !== post.key || !Array.isArray(page.messages) || page.messages.length > 50
    || page.messageCount !== page.messages.length || !Array.isArray(page.media)
    || page.messages.some(message => message.chatId !== post.discussionChatId || !nativeId(message.messageId, true))
    || page.media.some(item => !validMedia(item) || item.source !== 'COMMENT' || item.message.chatId !== post.discussionChatId)
    || typeof page.exhausted !== 'boolean' || page.exhausted !== value.exhausted || value.pageNumber < 1
    || !Array.isArray(page.warnings)) return false
  if (previous && (value.id !== previous.id || post.key !== previous.post.key
    || value.pageNumber !== previous.pageNumber + 1 || !previous.canContinue)) return false
  if (!previous && value.pageNumber !== 1) return false
  if (value.observedMessages !== (previous?.observedMessages ?? 0) + page.messageCount) return false
  const advanced = nativeId(page.nextCursor, true) && (!previous?.comments?.nextCursor
    || BigInt(page.nextCursor) < BigInt(previous.comments.nextCursor))
  return value.canContinue === (!value.exhausted && advanced)
}
export function selectedPreviewMedia(value: DownloadPreview | null, types: Media['type'][], includeMain: boolean): Media[] {
  if (!value) return []
  const comments = value.comments?.media.filter(item => types.includes(item.type)) ?? []
  return [...(includeMain ? value.post.mainMedia : []), ...comments]
}
