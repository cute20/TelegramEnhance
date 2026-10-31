export interface DownloadItem {
  id: string; fileName: string; type: string; state: string; size: number
  downloadedBytes: number; writtenBytes: number; outputPath: string | null; error: string | null
  history?: { id:string;taskId:string;path:string;sha256:string;completedAt:number } | null
  sourceLink?: string | null
  duplicate?: { itemId:string;reason:'SOURCE_MESSAGE'|'FILE_ID'|'CONTENT_SHA';sourceLink:string|null;path:string } | null
  manifestPath?: string | null
}
export interface DownloadTask {
  id: string; title: string; channelTitle: string; state: 'QUEUED' | 'RUNNING' | 'PAUSED' | 'FAILED' | 'COMPLETED'
  pauseRequested: boolean; targetDirectory: string; scope: 'OBSERVED_SELECTION'; total: number
  completed: number; skipped: number; totalBytes: number; error: string | null; createdAt: number; updatedAt: number; items: DownloadItem[]
  duplicatePolicy: 'HISTORY_SKIP_V1' | 'KEEP_DUPLICATES_V2'; duplicates:number; duplicateDirectory:string
  automation?: DownloadAutomation | null
}
export interface DownloadAutomation {
  ruleId: string; ruleVersion: number; ruleName: string; manual: boolean; held: boolean; enabled: boolean; currentVersion: number
  window: { mode: 'ALL_DAY' | 'WINDOW'; start: string | null; end: string | null; timezone: 'Asia/Shanghai' }
}
export function validDownloadAutomation(value: unknown): value is DownloadAutomation {
  if (!value || typeof value !== 'object') return false
  const automation = value as DownloadAutomation, window = automation.window
  return typeof automation.ruleId === 'string' && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/i.test(automation.ruleId)
    && Number.isSafeInteger(automation.ruleVersion) && automation.ruleVersion > 0
    && Number.isSafeInteger(automation.currentVersion) && automation.currentVersion >= automation.ruleVersion
    && typeof automation.ruleName === 'string' && !!automation.ruleName.trim() && automation.ruleName.length <= 80
    && ['manual', 'held', 'enabled'].every(key => typeof automation[key as keyof DownloadAutomation] === 'boolean')
    && !!window && window.timezone === 'Asia/Shanghai' && (window.mode === 'ALL_DAY' ? window.start === null && window.end === null
      : window.mode === 'WINDOW' && typeof window.start === 'string' && typeof window.end === 'string'
        && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(window.start) && /^(?:[01]\d|2[0-3]):[0-5]\d$/.test(window.end) && window.start !== window.end)
}
export function downloadAutomationLabel(automation: DownloadAutomation) {
  const state = automation.manual ? '已转手动' : automation.held ? '手动暂停' : !automation.enabled ? '规则已暂停'
    : automation.ruleVersion !== automation.currentVersion ? '旧版本' : '遵守规则时段'
  const window = automation.window.mode === 'ALL_DAY' ? '全天' : `${automation.window.start}—${automation.window.end}`
  return `持续规则：${automation.ruleName} · v${automation.ruleVersion} · ${state} · ${window} · 北京时间`
}
export interface CreateDownload { operationId: string; sourceId: string; mediaKeys: string[]; includeMain: boolean; targetDirectory: string }
export function validDownloadItem(value: unknown): value is DownloadItem {
  if (!value || typeof value !== 'object') return false
  const item = value as DownloadItem
  return typeof item.id === 'string'
    && Number.isSafeInteger(item.size) && item.size > 0 && Number.isSafeInteger(item.writtenBytes) && item.writtenBytes >= 0 && item.writtenBytes <= item.size
    && Number.isSafeInteger(item.downloadedBytes) && item.downloadedBytes >= 0 && item.downloadedBytes <= item.size
    && ['QUEUED', 'DOWNLOADING', 'WRITING', 'FINALIZING', 'COMPLETED', 'SKIPPED_HISTORY'].includes(item.state)
    && (item.state === 'SKIPPED_HISTORY'
      ? item.outputPath === null && item.downloadedBytes === 0 && item.writtenBytes === 0 && !!item.history
        && typeof item.history.id === 'string' && !!item.history.id && typeof item.history.taskId === 'string' && !!item.history.taskId
        && typeof item.history.path === 'string' && !!item.history.path && typeof item.history.sha256 === 'string' && /^[0-9a-f]{64}$/.test(item.history.sha256)
        && Number.isSafeInteger(item.history.completedAt) && item.history.completedAt >= 0
      : typeof item.outputPath === 'string' && !!item.outputPath && item.history == null)
    && (item.state !== 'COMPLETED' || item.writtenBytes === item.size && item.downloadedBytes === item.size)
    && (item.duplicate == null ? item.manifestPath == null
      : typeof item.duplicate.itemId === 'string' && !!item.duplicate.itemId
        && ['SOURCE_MESSAGE', 'FILE_ID', 'CONTENT_SHA'].includes(item.duplicate.reason) && typeof item.duplicate.path === 'string'
        && (item.manifestPath == null || typeof item.manifestPath === 'string' && !!item.manifestPath) && item.history == null)
}
export function validTask(task: DownloadTask | null | undefined): task is DownloadTask {
  return !!task && typeof task.id === 'string' && !!task.id && task.scope === 'OBSERVED_SELECTION'
    && ['QUEUED', 'RUNNING', 'PAUSED', 'FAILED', 'COMPLETED'].includes(task.state)
    && Number.isSafeInteger(task.total) && task.total > 0 && task.total <= 100
    && Number.isSafeInteger(task.completed) && task.completed >= 0 && task.completed <= task.total
    && Number.isSafeInteger(task.skipped) && task.skipped >= 0 && task.completed + task.skipped <= task.total
    && ['HISTORY_SKIP_V1','KEEP_DUPLICATES_V2'].includes(task.duplicatePolicy)
    && Number.isSafeInteger(task.duplicates) && task.duplicates >= 0 && task.duplicates <= task.completed && typeof task.duplicateDirectory === 'string'
    && (task.duplicatePolicy !== 'KEEP_DUPLICATES_V2' || task.skipped === 0)
    && Array.isArray(task.items) && task.items.length === task.total
    && task.items.every(validDownloadItem)
    && task.items.every(item => item.duplicate == null || task.duplicatePolicy === 'KEEP_DUPLICATES_V2')
    && Number.isSafeInteger(task.totalBytes) && task.totalBytes === task.items.reduce((sum, item) => sum + item.size, 0)
    && task.completed === task.items.filter(item => item.state === 'COMPLETED' && item.writtenBytes === item.size).length
    && task.skipped === task.items.filter(item => item.state === 'SKIPPED_HISTORY').length
    && task.duplicates === task.items.filter(item => item.state === 'COMPLETED' && item.duplicate != null).length
    && (task.state !== 'COMPLETED' || task.completed + task.skipped === task.total)
    && (task.automation == null || validDownloadAutomation(task.automation))
}
export const taskLabels: Record<string, string> = { QUEUED: '等待下载', RUNNING: '正在处理', PAUSED: '已暂停', FAILED: '需要处理', COMPLETED: '已完成', DOWNLOADING: '下载原文件', WRITING: '写入目标文件', FINALIZING: '校验并发布文件', SKIPPED_HISTORY:'历史已下载，已跳过' }
