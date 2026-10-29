import type { UploadTask } from './uploadTasks'
export interface UploadRetryCheck {id:string;taskId:string;groupId:string;expiresAt:number;confirmed:number;retryable:number;pending:number;unresolved:number;items:{itemId:string;fileName:string;state:string;reason:string}[]}
export const retryLabels:Record<string,string>={CONFIRMED:'已成功，保留',RETRYABLE:'明确失败，可重试',PENDING:'仍在发送',UNKNOWN:'结果不明',BLOCKED:'暂不能重试',WAITING:'等待服务端允许'}
export function validRetryCheck(value:unknown,task:UploadTask):value is UploadRetryCheck{
  if(!value||typeof value!=='object')return false;const c=value as UploadRetryCheck,g=task.groups.find(g=>g.id===c.groupId)
  if(c.taskId!==task.id||typeof c.id!=='string'||!/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(c.id)||!g||!Number.isSafeInteger(c.expiresAt)||c.expiresAt<=0||!Array.isArray(c.items)||c.items.length!==g.items.length)return false
  const seen=new Set<string>();for(const row of c.items){if(!row||seen.has(row.itemId)||!g.items.some(i=>i.id===row.itemId&&i.fileName===row.fileName)||!Object.hasOwn(retryLabels,row.state)||typeof row.reason!=='string')return false;seen.add(row.itemId)}
  return [c.confirmed,c.retryable,c.pending,c.unresolved].every(n=>Number.isSafeInteger(n)&&n>=0)&&c.confirmed===c.items.filter(i=>i.state==='CONFIRMED').length&&c.retryable===c.items.filter(i=>i.state==='RETRYABLE').length&&c.pending===c.items.filter(i=>i.state==='PENDING').length&&c.confirmed+c.retryable+c.pending+c.unresolved===c.items.length
}
