import type { BatchSnapshot } from './downloadBatch'
import { validTask, type DownloadTask } from './downloadTasks.ts'

export interface BatchTaskSummary {
  id: string
  title: string
  state: DownloadTask['state']
  pauseRequested: boolean
  total: number
  completed: number
  skipped: number
  duplicates: number
  totalBytes: number
  downloadedBytes: number
  writtenBytes: number
  updatedAt: number
  error: string | null
}
export interface DownloadBatchStatus {
  id: string
  snapshot: BatchSnapshot
  state: 'PAUSING' | 'RUNNING' | 'QUEUED' | 'FAILED' | 'PAUSED' | 'COMPLETED'
  totalTasks: number
  completedTasks: number
  runningTasks: number
  queuedTasks: number
  pausedTasks: number
  failedTasks: number
  completed: number
  skipped: number
  duplicates: number
  downloadedBytes: number
  writtenBytes: number
  createdAt: number
  updatedAt: number
  canPause: boolean
  canResume: boolean
  tasks: BatchTaskSummary[]
}
const count = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0
const text = (value: unknown): value is string => typeof value === 'string' && !!value.trim()
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string')
const nullableText = (value: unknown) => value === null || typeof value === 'string'
const states = ['QUEUED', 'RUNNING', 'FAILED', 'PAUSED', 'COMPLETED']
const snapshotFields = ['previewId', 'revision', 'channelTitle', 'tags', 'keywords', 'types', 'includeMain', 'targetDirectory', 'postCount', 'total', 'totalBytes'] as const

function validSnapshot(value: BatchSnapshot | undefined): value is BatchSnapshot {
  return !!value && uuid(value.previewId) && count(value.revision) && value.revision >= 1
    && typeof value.channelTitle === 'string' && strings(value.tags) && strings(value.keywords) && strings(value.types)
    && value.types.length >= 1 && value.types.length <= 5 && new Set(value.types).size === value.types.length
    && value.types.every(type => ['PHOTO', 'VIDEO', 'AUDIO', 'DOCUMENT', 'OTHER'].includes(type))
    && typeof value.includeMain === 'boolean' && text(value.targetDirectory)
    && count(value.postCount) && value.postCount >= 1 && value.postCount <= 20
    && count(value.total) && value.total >= 1 && value.total <= 1000 && count(value.totalBytes) && value.totalBytes > 0
}
function validSummary(value: BatchTaskSummary): boolean {
  return !!value && uuid(value.id) && typeof value.title === 'string' && states.includes(value.state) && typeof value.pauseRequested === 'boolean'
    && [value.total, value.completed, value.skipped, value.duplicates, value.totalBytes, value.downloadedBytes, value.writtenBytes, value.updatedAt].every(count)
    && value.total >= 1 && value.total <= 100 && value.totalBytes > 0 && value.completed + value.skipped <= value.total && value.duplicates <= value.completed
    && value.downloadedBytes <= value.totalBytes && value.writtenBytes <= value.totalBytes && nullableText(value.error)
    && (value.state !== 'COMPLETED' || value.completed + value.skipped === value.total)
    && (value.completed !== value.total || value.downloadedBytes === value.totalBytes && value.writtenBytes === value.totalBytes)
}
export function aggregateBatchState(tasks: BatchTaskSummary[]): DownloadBatchStatus['state'] {
  if (tasks.some(task => task.state === 'RUNNING' && task.pauseRequested)) return 'PAUSING'
  for (const state of ['RUNNING', 'QUEUED', 'FAILED', 'PAUSED'] as const) if (tasks.some(task => task.state === state)) return state
  return 'COMPLETED'
}
export function validBatchStatus(value: unknown, before?: DownloadBatchStatus): value is DownloadBatchStatus {
  const batch = value as DownloadBatchStatus
  if (!batch || !uuid(batch.id) || !validSnapshot(batch.snapshot) || !Array.isArray(batch.tasks) || batch.tasks.length < 1 || batch.tasks.length > 29
    || batch.tasks.some(task => !validSummary(task)) || new Set(batch.tasks.map(task => task.id)).size !== batch.tasks.length
    || ![batch.totalTasks, batch.completedTasks, batch.runningTasks, batch.queuedTasks, batch.pausedTasks, batch.failedTasks,
      batch.completed, batch.skipped, batch.duplicates, batch.downloadedBytes, batch.writtenBytes, batch.createdAt, batch.updatedAt].every(count)
    || batch.updatedAt < batch.createdAt || batch.totalTasks !== batch.tasks.length || batch.state !== aggregateBatchState(batch.tasks)) return false
  const sum = (field: 'total' | 'completed' | 'skipped' | 'duplicates' | 'totalBytes' | 'downloadedBytes' | 'writtenBytes') => batch.tasks.reduce((total, task) => total + task[field], 0)
  const totals = ['completed', 'skipped', 'duplicates', 'downloadedBytes', 'writtenBytes'] as const
  const stateCounts = [['COMPLETED', 'completedTasks'], ['RUNNING', 'runningTasks'], ['QUEUED', 'queuedTasks'], ['PAUSED', 'pausedTasks'], ['FAILED', 'failedTasks']] as const
  if (sum('total') !== batch.snapshot.total || sum('totalBytes') !== batch.snapshot.totalBytes
    || totals.some(field => sum(field) !== batch[field])
    || stateCounts.some(([state, field]) => batch[field] !== batch.tasks.filter(task => task.state === state).length)) return false
  const pausing = batch.state === 'PAUSING'
  if (batch.canPause !== (!pausing && batch.runningTasks + batch.queuedTasks > 0)
    || batch.canResume !== (!pausing && batch.pausedTasks + batch.failedTasks > 0)) return false
  if (before) {
    if (batch.id !== before.id || batch.createdAt !== before.createdAt || snapshotFields.some(field => JSON.stringify(batch.snapshot[field]) !== JSON.stringify(before.snapshot[field])) || batch.tasks.length !== before.tasks.length) return false
    for (const task of batch.tasks) {
      const prior = before.tasks.find(item => item.id === task.id)
      if (!prior || task.title !== prior.title || task.total !== prior.total || task.totalBytes !== prior.totalBytes
        || task.completed < prior.completed || task.skipped < prior.skipped || task.duplicates < prior.duplicates) return false
      if (prior.state === 'COMPLETED' && (task.state !== 'COMPLETED' || task.completed !== prior.completed || task.skipped !== prior.skipped || task.duplicates !== prior.duplicates
        || task.downloadedBytes !== prior.downloadedBytes || task.writtenBytes !== prior.writtenBytes)) return false
    }
  }
  return true
}
export function validBatchTaskDetail(value: unknown, batch: DownloadBatchStatus, taskId: string): value is DownloadTask {
  const task = value as DownloadTask
  const member = batch.tasks.find(item => item.id === taskId)
  const root = normalizePath(batch.snapshot.targetDirectory), target = normalizePath(task?.targetDirectory)
  return !!member && validTask(task) && task.id === taskId && task.title === member.title && typeof task.channelTitle === 'string'
    && root !== null && target !== null && target.startsWith(root + '/')
    && normalizePath(task.duplicateDirectory) === root + '/重复媒体' && task.total === member.total && task.totalBytes === member.totalBytes
    && (member.state !== 'COMPLETED' || task.state === 'COMPLETED' && task.completed === member.completed && task.skipped === member.skipped && task.duplicates === member.duplicates)
}
function normalizePath(value: unknown): string | null {
  if (typeof value !== 'string' || !/^[a-z]:[\\/]/i.test(value)) return null
  const path = value.replace(/\\/g, '/').replace(/\/+$/, '').toLowerCase()
  return path.split('/').some(segment => segment === '.' || segment === '..') ? null : path
}
export const batchStateLabels: Record<DownloadBatchStatus['state'], string> = {
  PAUSING: '正在暂停', RUNNING: '正在下载', QUEUED: '等待下载', FAILED: '需要处理', PAUSED: '已暂停', COMPLETED: '已处理完成',
}
