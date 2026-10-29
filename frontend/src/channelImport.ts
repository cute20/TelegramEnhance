import type { Post } from './library'

export interface ImportChannel { id: string; title: string }
export interface ImportChannels {
  items: ImportChannel[]
  scope: 'MANAGED_CHANNELS'
  partial: boolean
  warnings: string[]
}
export interface ImportPreviewItem { messageId: string; albumId: string | null; title: string }
export interface ImportPreview {
  channel: ImportChannel
  items: ImportPreviewItem[]
  scope: 'CHANNEL_PAGE'
  limit: 10
  nextCursor: string | null
  exhausted: boolean
  hasMore: boolean
  warnings: string[]
}
export interface ImportedPost { post: Post; created: boolean }
export interface ImportRun {
  channelId: string
  runId: string
  revision: number
  status: 'READY' | 'COMPLETE' | 'BLOCKED'
  processed: number
  created: number
  updated: number
  incomplete: number
  exhausted: boolean
  warning: string | null
}
export interface ImportProgress {
  completed: number
  total: number
  created: number
  updated: number
  status: 'running' | 'complete' | 'stopped' | 'failed'
}
