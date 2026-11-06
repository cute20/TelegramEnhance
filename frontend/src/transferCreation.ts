import type { Media } from './library'

export interface DownloadCreationSelection {
  entry: 'CHANNEL' | 'POST' | 'MANUAL'
  source: string
  tags: string
  keywords: string
  startDate?: string
  endDate?: string
  types: Media['type'][]
  includeMain: boolean
  targetDirectory: string
  suggestedName: string
}
export interface UploadCreationSelection {
  link: string
  sourceDirectory: string
  recursive: boolean
  captionMode: 'FILENAME' | 'EMPTY'
  albumSize: number
}
export type CreationStage = 'parameters' | 'preview' | 'confirm' | 'complete'
export const emptyDownloadSelection = (defaultDownloadDirectory = ''): DownloadCreationSelection => ({
  entry: 'CHANNEL', source: '', tags: '', keywords: '', startDate: '', endDate: '', types: ['VIDEO'],
  includeMain: false, targetDirectory: defaultDownloadDirectory.trim(), suggestedName: '',
})
export const emptyUploadSelection = (): UploadCreationSelection => ({
  link: '', sourceDirectory: '', recursive: true, captionMode: 'FILENAME', albumSize: 10,
})

export interface DownloadDateFields { startDate?: string | null; endDate?: string | null }
export const normalizeDownloadDate = (value: string | null | undefined) => value?.trim() || null
export function validDownloadDate(value: unknown): value is string {
  if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const year = Number(value.slice(0,4)), month = Number(value.slice(5,7)), day = Number(value.slice(8,10))
  const days = [31,year%4===0&&(year%100!==0||year%400===0)?29:28,31,30,31,30,31,31,30,31,30,31]
  return month >= 1 && month <= 12 && day >= 1 && day <= days[month-1]!
}
export function downloadDatesError(startDate?: string | null, endDate?: string | null): string {
  const start = normalizeDownloadDate(startDate), end = normalizeDownloadDate(endDate)
  if (start && !validDownloadDate(start) || end && !validDownloadDate(end)) return '请选择有效的发布时间日期（YYYY-MM-DD）。'
  return start && end && start > end ? '开始日期不能晚于结束日期。' : ''
}
export function validDownloadDateFields(value: DownloadDateFields): boolean {
  return [value.startDate,value.endDate].every(date => date == null || validDownloadDate(date))
    && !(value.startDate && value.endDate && value.startDate > value.endDate)
}
export function sameDownloadDates(left: DownloadDateFields, right: DownloadDateFields): boolean {
  return (left.startDate ?? null) === (right.startDate ?? null) && (left.endDate ?? null) === (right.endDate ?? null)
}
