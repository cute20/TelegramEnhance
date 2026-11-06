export const uploadTypes = { PHOTO: '图片', VIDEO: '视频', AUDIO: '音频', DOCUMENT: '文档', ANIMATION: '动图' } as const
export type UploadType = keyof typeof uploadTypes
export interface UploadMember { relativePath: string; fileName: string; mediaType: UploadType; size: number; caption: string }
export interface UploadGroup { number: number; mediaType: UploadType; mode: 'SINGLE' | 'ALBUM'; files: UploadMember[] }
export interface UploadPreview {
  id: string; accountId: string; state: 'PREVIEW_ONLY'; sourceDirectory: string; archiveDirectory: string
  recursive: boolean; captionMode: 'FILENAME' | 'EMPTY'; albumSize: number; channelTitle: string; postTitle: string; postText: string
  postLink: string; discussionTitle: string; permission: 'MEDIA_ALLOWED_AT_CHECK'; slowModeSeconds: number
  fileCount: number; totalBytes: number; groups: UploadGroup[]; otherFiles: number; deferredFiles: number
  linkedEntries: number; duplicateFiles: number; warnings: string[]; targetCheckedAt: number; createdAt: number; expiresAt: number
}
export function validUploadPreview(value: unknown, account: string): value is UploadPreview {
  if (!value || typeof value !== 'object') return false
  const p = value as UploadPreview
  if (typeof p.id !== 'string' || !/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(p.id) || p.accountId !== account || p.state !== 'PREVIEW_ONLY' || p.permission !== 'MEDIA_ALLOWED_AT_CHECK') return false
  if (![p.sourceDirectory,p.archiveDirectory,p.channelTitle,p.postTitle,p.postText,p.discussionTitle,p.postLink].every(x => typeof x === 'string') || !['FILENAME','EMPTY'].includes(p.captionMode) || typeof p.recursive !== 'boolean') return false
  if (!Number.isInteger(p.albumSize) || p.albumSize < 1 || p.albumSize > 10) return false
  if (![p.fileCount,p.totalBytes,p.otherFiles,p.deferredFiles,p.linkedEntries,p.duplicateFiles,p.slowModeSeconds,p.targetCheckedAt,p.createdAt,p.expiresAt].every(x => Number.isSafeInteger(x) && x >= 0) || p.fileCount > 500 || p.expiresAt <= p.createdAt) return false
  if (!Array.isArray(p.groups) || p.groups.length > p.fileCount || !Array.isArray(p.warnings) || !p.warnings.every(x => typeof x === 'string')) return false
  const paths = new Set<string>(); let count = 0, bytes = 0
  const order = Object.keys(uploadTypes), maximumBytes = 8 * 1024 * 1024 * 1024
  let previousType = -1, previousCount = 0, previousBytes = 0
  for (const [i, group] of p.groups.entries()) {
    if (!group || !Object.hasOwn(uploadTypes, group.mediaType)) return false
    const rank = order.indexOf(group.mediaType), maximum = group.mediaType === 'ANIMATION' ? 1 : p.albumSize
    if (rank < previousType || group.number !== i + 1 || !Array.isArray(group.files) || !group.files.length || group.files.length > maximum || group.mode !== (group.files.length === 1 ? 'SINGLE' : 'ALBUM')) return false
    const firstSize = group.files[0]?.size
    if (rank === previousType && previousCount !== maximum && (!Number.isSafeInteger(firstSize) || firstSize! <= 0 || firstSize! > maximumBytes || previousBytes + firstSize! <= maximumBytes)) return false
    let groupBytes = 0
    for (const file of group.files) {
      if (!file || typeof file.relativePath !== 'string' || !file.relativePath || typeof file.fileName !== 'string' || !file.fileName || typeof file.caption !== 'string' || !Number.isSafeInteger(file.size) || file.size <= 0 || file.size > maximumBytes || paths.has(file.relativePath)) return false
      if (file.mediaType !== group.mediaType || file.caption !== (p.captionMode === 'FILENAME' ? file.fileName : '')) return false
      groupBytes += file.size; if (groupBytes > maximumBytes) return false
      paths.add(file.relativePath); count++; bytes += file.size
    }
    previousType = rank; previousCount = group.files.length; previousBytes = groupBytes
  }
  return count === p.fileCount && bytes === p.totalBytes
}
