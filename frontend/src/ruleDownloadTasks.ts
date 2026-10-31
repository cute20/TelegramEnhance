import type { DownloadAutomation } from './downloadTasks'
import {validDownloadItem,type DownloadItem} from './downloadTasks.ts'
export type RuleFileSource='MAIN'|'COMMENT'
export interface RuleFileTotals {total:number;completed:number;skipped:number;duplicates:number;totalBytes:number;downloadedBytes:number;writtenBytes:number}
export interface RuleFileEntry {taskId:string;ruleVersion:number;taskState:'QUEUED'|'RUNNING'|'PAUSED'|'FAILED'|'COMPLETED';pauseRequested:boolean;source:RuleFileSource;item:DownloadItem}
export interface RuleFilePage {ruleId:string;source:RuleFileSource;totals:RuleFileTotals;items:RuleFileEntry[];nextCursor:string|null;hasMore:boolean}
export function validRuleFilePage(value:unknown,ruleId:string,source:RuleFileSource):value is RuleFilePage {
  if(!value||typeof value!=='object')return false
  const page=value as RuleFilePage,t=page.totals
  return page.ruleId===ruleId&&page.source===source&&!!t
    &&['total','completed','skipped','duplicates','totalBytes','downloadedBytes','writtenBytes'].every(key=>Number.isSafeInteger(t[key as keyof RuleFileTotals])&&t[key as keyof RuleFileTotals]>=0)
    &&t.completed+t.skipped<=t.total&&t.duplicates<=t.completed&&t.downloadedBytes<=t.totalBytes&&t.writtenBytes<=t.totalBytes
    &&Array.isArray(page.items)&&page.items.length<=50&&page.items.length<=t.total&&new Set(page.items.map(row=>row?.item?.id)).size===page.items.length
    &&page.items.every(row=>!!row&&typeof row.taskId==='string'&&!!row.taskId&&Number.isSafeInteger(row.ruleVersion)&&row.ruleVersion>0
      &&['QUEUED','RUNNING','PAUSED','FAILED','COMPLETED'].includes(row.taskState)&&typeof row.pauseRequested==='boolean'&&row.source===source
      &&validDownloadItem(row.item)&&!!row.item.id&&typeof row.item.fileName==='string'&&typeof row.item.type==='string'
      &&(row.item.error===null||typeof row.item.error==='string'))
    &&typeof page.hasMore==='boolean'&&(page.hasMore?typeof page.nextCursor==='string'&&!!page.nextCursor&&page.nextCursor.length<=4096:page.nextCursor===null)
    &&page.items.filter(row=>row.item.state==='COMPLETED').length<=t.completed
    &&page.items.filter(row=>row.item.state==='SKIPPED_HISTORY').length<=t.skipped
    &&page.items.filter(row=>row.item.state==='COMPLETED'&&row.item.duplicate!=null).length<=t.duplicates
    &&page.items.reduce((sum,row)=>sum+row.item.size,0)<=t.totalBytes
}
export interface RuleDownloadTask {
  id:string; name:string; channelTitle:string; targetDirectory:string; revision:number; enabled:boolean
  window:DownloadAutomation['window']; state:'RUNNING'|'PAUSING'|'QUEUED'|'FAILED'|'PAUSED'|'WATCHING'|'STOPPED'
  taskCount:number; total:number; completed:number; skipped:number; duplicates:number; mainTotal:number; commentTotal:number
  totalBytes:number; downloadedBytes:number; writtenBytes:number; updatedAt:number; failedTasks:number
}
export function validRuleDownload(value:unknown):value is RuleDownloadTask {
  if(!value||typeof value!=='object')return false
  const g=value as RuleDownloadTask
  return typeof g.id==='string'&&/^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(g.id)
    &&typeof g.name==='string'&&!!g.name.trim()&&typeof g.channelTitle==='string'&&typeof g.targetDirectory==='string'
    &&typeof g.enabled==='boolean'&&Number.isSafeInteger(g.revision)&&g.revision>0
    &&['RUNNING','PAUSING','QUEUED','FAILED','PAUSED','WATCHING','STOPPED'].includes(g.state)
    &&['taskCount','total','completed','skipped','duplicates','mainTotal','commentTotal','totalBytes','downloadedBytes','writtenBytes','updatedAt','failedTasks'].every(key=>Number.isSafeInteger(g[key as keyof RuleDownloadTask])&&(g[key as keyof RuleDownloadTask] as number)>=0)
    &&g.completed+g.skipped<=g.total&&g.duplicates<=g.completed&&g.mainTotal+g.commentTotal===g.total
    &&g.downloadedBytes<=g.totalBytes&&g.writtenBytes<=g.totalBytes&&g.failedTasks<=g.taskCount
    &&!!g.window&&g.window.timezone==='Asia/Shanghai'&&(g.window.mode==='ALL_DAY'?g.window.start===null&&g.window.end===null:g.window.mode==='WINDOW'&&typeof g.window.start==='string'&&typeof g.window.end==='string'&&/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(g.window.start)&&/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(g.window.end)&&g.window.start!==g.window.end)
}
export const ruleDownloadLabels:Record<RuleDownloadTask['state'],string>={RUNNING:'正在下载',PAUSING:'正在暂停',QUEUED:'等待下载',FAILED:'需要处理',PAUSED:'已暂停',WATCHING:'等待新媒体',STOPPED:'规则已暂停'}
