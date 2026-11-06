import { validDownloadRetry, type DownloadRetry } from './downloadTasks.ts'

export type CenterKind='all'|'upload'|'download'|'scan'
export type CenterStatus='all'|'active'|'attention'|'waiting'|'completed'
export interface CenterRow {id:string;kind:Exclude<CenterKind,'all'>;category:string;title:string;subtitle:string;state:string;statusGroup:Exclude<CenterStatus,'all'>;createdAt:number;updatedAt:number;totalFiles:number;resolvedFiles:number;totalBytes:number;transferredBytes:number;writtenBytes:number;speedBytesPerSecond:number|null;etaSeconds:number|null;phase:string;detailId:string;actions:string[];error:string|null;targetDirectory:string|null;skippedFiles?:number;retry?:DownloadRetry|null}
export interface CenterPage {items:CenterRow[];total:number;counts:Record<Exclude<CenterStatus,'all'>,number>;warnings:string[]}
export interface ScanDetail {id:string;sourceId:string;category:string;title:string;subtitle:string;state:string;createdAt:number;updatedAt:number;total:number;processed:number;bytes:number;phase:string;detailId:string;directory:string|null;error:string|null;revision:number;actions:string[];current:boolean}
export interface PauseOutcome {category:string;id:string;action:string;message:string|null}
export interface PauseReceipt {operationId:string;state:'RUNNING'|'COMPLETED'|'NEEDS_REVIEW';accepted:PauseOutcome[];failed:PauseOutcome[];pending:PauseOutcome[];warnings:string[];createdAt:number;updatedAt:number}
const integer=(value:unknown):value is number=>Number.isSafeInteger(value)&&Number(value)>=0
const text=(value:unknown):value is string=>typeof value==='string'
const nullableText=(value:unknown)=>value===null||text(value)
export function validCenterPage(value:unknown):value is CenterPage {
  if(!value||typeof value!=='object')return false;const page=value as CenterPage
  return integer(page.total)&&Array.isArray(page.items)&&page.items.length<=50&&page.items.every(validCenterRow)&&new Set(page.items.map(row=>row.category+':'+row.id)).size===page.items.length&&page.total>=page.items.length
    &&!!page.counts&&['active','attention','waiting','completed'].every(key=>integer(page.counts[key as keyof typeof page.counts]))&&Array.isArray(page.warnings)&&page.warnings.every(text)
}
export function validCenterRow(value:unknown):value is CenterRow {
  if(!value||typeof value!=='object')return false;const row=value as CenterRow
  return [row.id,row.category,row.title,row.subtitle,row.state,row.phase,row.detailId].every(text)&&['upload','download','scan'].includes(row.kind)&&['active','attention','waiting','completed'].includes(row.statusGroup)
    &&[row.createdAt,row.updatedAt,row.totalFiles,row.resolvedFiles,row.totalBytes,row.transferredBytes,row.writtenBytes].every(integer)&&(row.totalFiles===0||row.resolvedFiles<=row.totalFiles)&&row.transferredBytes<=row.totalBytes&&row.writtenBytes<=row.totalBytes&&(row.state!=='COMPLETED'||row.kind==='scan'||row.resolvedFiles===row.totalFiles)
    &&[row.speedBytesPerSecond,row.etaSeconds].every(value=>value===null||integer(value))&&(row.skippedFiles===undefined||integer(row.skippedFiles)&&row.skippedFiles<=row.resolvedFiles)&&nullableText(row.error)&&nullableText(row.targetDirectory)&&Array.isArray(row.actions)&&row.actions.every(action=>['start','pause','resume','cancel','delete','reconcile','recover-pending','retry-check','pause-rule'].includes(action))&&(!row.actions.includes('delete')||canDeleteCenterTask(row))&&validScanActions(row)&&validCenterRetry(row)
}
function validCenterRetry(row:CenterRow):boolean {
  return row.retry==null||row.kind==='download'&&row.category==='download'&&row.state==='QUEUED'&&row.statusGroup==='waiting'&&row.phase==='RETRY_WAIT'
    &&validDownloadRetry(row.retry,{state:'QUEUED',pauseRequested:false})
}
export function centerRetrySummary(row:CenterRow,now=Date.now()):string {
  if(!row.retry||!validCenterRetry(row))return ''
  const attempt=`第 ${row.retry.attempt} 次自动重试`
  if(now>=row.retry.nextAttemptAt)return attempt+' · 已到重试时间，等待后台调度'
  const time=new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(row.retry.nextAttemptAt)
  return attempt+' · 预计北京时间 '+time
}
export function canDeleteCenterTask(row:CenterRow){return row.kind==='download'&&row.category==='download'&&row.state==='PAUSED'&&row.actions.includes('delete')}
export function validCenterDelete(value:unknown,row:CenterRow):boolean {
  if(!value||typeof value!=='object')return false
  const result=value as {item?:unknown;check?:{id?:unknown;category?:unknown;deleted?:unknown};warning?:unknown}
  return result.item===null&&!!result.check&&result.check.id===row.id&&result.check.category===row.category&&result.check.deleted===true
    &&(result.warning===null||typeof result.warning==='string')
}
const controlledScans=new Set(['channel-scan','preparation','import'])
export function registeredScan(row:CenterRow){return row.kind==='scan'&&(controlledScans.has(row.category)||row.category==='rule-preparation')}
export function validScanDetail(value:unknown,row:CenterRow):value is ScanDetail {
  if(!registeredScan(row)||!value||typeof value!=='object')return false
  const detail=value as ScanDetail
  return detail.id===row.id&&detail.category===row.category&&detail.detailId===row.detailId
    &&[detail.sourceId,detail.title,detail.subtitle,detail.state,detail.phase,detail.detailId].every(text)&&!!detail.sourceId
    &&[detail.createdAt,detail.updatedAt,detail.total,detail.processed,detail.bytes,detail.revision].every(integer)
    &&(detail.total===0||detail.processed<=detail.total)&&nullableText(detail.directory)&&nullableText(detail.error)&&typeof detail.current==='boolean'
    &&Array.isArray(detail.actions)&&detail.actions.every(action=>['pause','resume'].includes(action))&&(!detail.current||detail.category==='rule-preparation'?detail.actions.length===0:true)
}
function validScanActions(row:CenterRow){
  if(row.kind!=='scan')return true
  if(!controlledScans.has(row.category)||['EXPIRED','NEEDS_REVIEW','COMPLETED'].includes(row.state))return row.actions.length===0
  return row.actions.every(action=>action==='pause'||action==='resume')
}
export function validPauseReceipt(value:unknown,operation:string):value is PauseReceipt {
  if(!value||typeof value!=='object')return false;const receipt=value as PauseReceipt
  return receipt.operationId===operation&&['RUNNING','COMPLETED','NEEDS_REVIEW'].includes(receipt.state)&&integer(receipt.createdAt)&&integer(receipt.updatedAt)
    &&[receipt.accepted,receipt.failed,receipt.pending].every(items=>Array.isArray(items)&&items.every(item=>item&&[item.category,item.id,item.action].every(text)&&nullableText(item.message)))&&Array.isArray(receipt.warnings)&&receipt.warnings.every(text)
}
export const centerStateLabels:Record<string,string>={READY:'等待确认',QUEUED:'队列等待',RUNNING:'进行中',PAUSING:'正在暂停',PAUSED:'已暂停',FAILED:'需要处理',ERROR:'需要处理',EXPIRED:'准备已过期',NEEDS_REVIEW:'结果待核对',SENT_PENDING_ARCHIVE:'已发送待移动',COMPLETED:'已完成',CANCELLED:'已取消',WATCHING:'等待新内容',STOPPED:'规则已暂停',WAITING_STEP:'等待手动继续',WAITING_SCAN:'等待下次扫描'}
export const centerPhaseLabels:Record<string,string>={RETRY_WAIT:'等待自动重试',PREPARING:'准备文件',UPLOADING:'上传字节',WAITING_RECEIPT:'核对正式回执',ARCHIVING:'移动到归档目录',DOWNLOADING:'下载到缓存',WRITING:'写入目标目录',COMMITTING:'核对保存回执',COMPLETED:'已完成',QUEUED:'等待开始',SCANNING:'扫描来源',IMPORTING:'导入频道',OBSERVING:'观察目录'}
export const centerActionLabels:Record<string,string>={start:'开始 / 继续',pause:'暂停',resume:'继续 / 重试',cancel:'取消',delete:'删除',reconcile:'重试移动','recover-pending':'核对结果','retry-check':'核对失败成员','pause-rule':'暂停规则生产'}
export function centerActionLabel(row:CenterRow,verb:string):string {
  if(row.kind==='scan'&&controlledScans.has(row.category)){if(verb==='pause')return '暂停';if(verb==='resume')return '恢复检查点'}
  return verb==='reconcile'&&row.phase!=='ARCHIVING'?'核对已有回执':centerActionLabels[verb]??verb
}
export function centerScanScope(row:CenterRow):string {return ({'channel-scan':'频道扫描',preparation:'批量下载清单准备',import:'频道主帖导入','rule-preparation':'规则目录准备'} as Record<string,string>)[row.category]??'来源扫描'}
export function centerScanHint(row:CenterRow):string {
  if(row.category==='rule-preparation')return '单次有界目录准备不支持中断。这里保留真实结果与历史，全部暂停会记录此能力限制，不会重新发起准备。'
  if(['EXPIRED','NEEDS_REVIEW'].includes(row.state)&&!row.actions.length)return '这是只读历史记录，当前会话不能恢复此检查点。请返回原面板重新核对；这里不会重复读取或提交。'
  if(!controlledScans.has(row.category))return '本记录仅显示实际观察到的范围，请返回原面板核对读取位置。'
  if(row.state==='COMPLETED')return '本次记录已结束。数量仅表示本记录实际处理的范围，不推断整个频道或目录的总量。'
  return (row.state==='PAUSING'?'暂停已受理，当前有界读取结束后停止后续步骤。':row.state==='PAUSED'?'检查点已暂停。':row.state==='WAITING_STEP'?'检查点等待手动继续。':'可暂停后续步骤，正在执行的有界读取仍会完成并保留结果。')+' 恢复只解除暂停门禁，不会自动执行下一步；请回原面板使用原检查点和修订号继续。'
}
export function centerProgress(row:CenterRow):number|null {if(row.kind==='scan')return row.totalFiles>0?Math.min(100,Math.floor(row.resolvedFiles/row.totalFiles*100)):null;if(row.state==='COMPLETED')return 100;if(row.totalBytes>0)return Math.min(100,Math.floor(row.transferredBytes/row.totalBytes*100));return row.totalFiles>0?Math.min(100,Math.floor(row.resolvedFiles/row.totalFiles*100)):null}
export function centerEta(seconds:number|null):string {if(seconds===null)return '';return seconds<60?`约 ${seconds} 秒`:seconds<3600?`约 ${Math.ceil(seconds/60)} 分钟`:`约 ${(seconds/3600).toFixed(1)} 小时`}
