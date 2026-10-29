import type { UploadPreview } from './uploadPreview'
export interface RuleWindow {mode:'ALL_DAY'|'WINDOW';start:string|null;end:string|null;timezone:'Asia/Shanghai'}
export interface RuleInput {name:string;link:string;sourceDirectory:string;archiveDirectory:string;recursive:boolean;captionMode:'FILENAME'|'EMPTY';window:RuleWindow;executionMode?:'OBSERVE_ONLY'|'AUTO_UPLOAD'}
export interface RuleObservation {version:number;revision:number;checkedAt:number;scanAt:number;outcome:'OBSERVED'|'ERROR';scan:null|{files:{relativePath:string;mediaType:string;size:number;sha256:string}[];observed:unknown[];duplicateFiles:number;deferredFiles:number;linkedEntries:number};added:number;changed:number;unobserved:number;error:string|null;upload?:{state:string;message:string;taskId:string|null;claimed:number;checkedAt:number}|null}
export interface AutoRule {id:string;accountId:string;version:number;revision:number;enabled:boolean;nextScanAt:number;createdAt:number;updatedAt:number;input:RuleInput;observation:RuleObservation|null}
export interface RuleJob {taskId:string;ruleVersion:number;state:string;total:number;confirmed:number;archived:number;manualTakeover:boolean;error:string|null}
export interface RuleView {rule:AutoRule;mode:'OBSERVE_ONLY'|'AUTO_UPLOAD';state:'PAUSED'|'OBSERVING'|'WAITING_WINDOW';scanning:boolean;nextAllowedAt:number;job?:RuleJob|null}
export interface RulePreview {ruleId:string;version:number;revision:number;preview:UploadPreview}
export function validRule(x:unknown,account:string|null):x is RuleView {
  if(!x||typeof x!=='object')return false;const v=x as RuleView,r=v.rule
  return ['OBSERVE_ONLY','AUTO_UPLOAD'].includes(v.mode)&&v.mode===(r?.input?.executionMode??'OBSERVE_ONLY')&&['PAUSED','OBSERVING','WAITING_WINDOW'].includes(v.state)&&typeof v.scanning==='boolean'&&Number.isSafeInteger(v.nextAllowedAt)
    &&!!r&&r.accountId===account&&typeof r.id==='string'&&!!r.id&&Number.isSafeInteger(r.version)&&r.version>0&&Number.isSafeInteger(r.revision)&&r.revision>0&&typeof r.enabled==='boolean'
    &&!!r.input&&typeof r.input.name==='string'&&typeof r.input.link==='string'&&typeof r.input.sourceDirectory==='string'&&typeof r.input.archiveDirectory==='string'
    &&r.input.window?.timezone==='Asia/Shanghai'&&['ALL_DAY','WINDOW'].includes(r.input.window.mode)
    &&(r.observation===null||!!r.observation&&r.observation.version===r.version&&['OBSERVED','ERROR'].includes(r.observation.outcome)
      &&Number.isSafeInteger(r.observation.scanAt)&&Number.isSafeInteger(r.observation.checkedAt)
      &&[r.observation.added,r.observation.changed,r.observation.unobserved].every(n=>Number.isSafeInteger(n)&&n>=0)
      &&(r.observation.scan===null||Array.isArray(r.observation.scan.files)&&r.observation.scan.files.length<=500&&r.observation.scan.files.every(f=>typeof f.relativePath==='string'&&Number.isSafeInteger(f.size)&&f.size>0)))
    &&(v.job==null||typeof v.job.taskId==='string'&&Number.isSafeInteger(v.job.ruleVersion)&&v.job.ruleVersion>0&&[v.job.total,v.job.confirmed,v.job.archived].every(n=>Number.isSafeInteger(n)&&n>=0)&&v.job.total>0&&v.job.total<=10&&v.job.archived<=v.job.confirmed&&v.job.confirmed<=v.job.total&&typeof v.job.manualTakeover==='boolean')
}
export function ruleTime(stamp:number){return stamp?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit'}).format(stamp):'尚未检测'}
export const ruleStates={PAUSED:'已暂停观察',OBSERVING:'目录观察已启用',WAITING_WINDOW:'等待设定时段'}
export function ruleArchiveDirectory(source:string):string {
  const path=source.trim().replaceAll('/','\\');if(!/^[a-z]:\\/i.test(path))return ''
  const parts:string[]=[];for(const part of path.slice(3).split('\\')){if(!part||part==='.')continue;if(part==='..'){if(!parts.length)return '';parts.pop()}else parts.push(part)}
  if(!parts.length)return '';return path.slice(0,3)+parts.slice(0,-1).concat(parts.at(-1)+'-已上传').join('\\')
}
