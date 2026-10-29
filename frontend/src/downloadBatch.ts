export interface BatchPost {key:string;title:string;state:'PENDING'|'READING_COMMENTS'|'READY';commentMessages:number;mainMedia:number;commentMedia:number;bytes:number}
export interface BatchPreview {id:string;accountId:string;revision:number;channelTitle:string;tags:string[];keywords:string[];types:string[];includeMain:boolean;targetDirectory:string;posts:BatchPost[];total:number;totalBytes:number;exhausted:boolean;canContinue:boolean;busy:boolean;error:string|null;warnings:string[];createdAt:number;expiresAt:number}
export interface BatchCreate {operationId:string;previewId:string;revision:number}
export interface BatchSnapshot {previewId:string;revision:number;channelTitle:string;tags:string[];keywords:string[];types:string[];includeMain:boolean;targetDirectory:string;postCount:number;total:number;totalBytes:number}
export interface BatchReceipt {id:string;snapshot:BatchSnapshot;taskIds:string[];createdAt:number}
const uuid=(value:unknown):value is string=>typeof value==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const count=(value:unknown)=>typeof value==='number'&&Number.isSafeInteger(value)&&value>=0
const strings=(value:unknown):value is string[]=>Array.isArray(value)&&value.every(item=>typeof item==='string')
const same=(a:unknown,b:unknown)=>JSON.stringify(a)===JSON.stringify(b)
export function validBatch(value:unknown,account:string|null,before?:BatchPreview,step=false):value is BatchPreview{
  const v=value as BatchPreview
  if(!v||!uuid(v.id)||v.accountId!==account||!count(v.revision)||v.revision<1||typeof v.channelTitle!=='string'||!strings(v.tags)||!strings(v.keywords)||!strings(v.types)||!v.types.length||v.types.length>5||new Set(v.types).size!==v.types.length||v.types.some(t=>!['PHOTO','VIDEO','AUDIO','DOCUMENT','OTHER'].includes(t))||typeof v.includeMain!=='boolean'||typeof v.targetDirectory!=='string'||!Array.isArray(v.posts)||!v.posts.length||v.posts.length>20||![v.total,v.totalBytes,v.createdAt,v.expiresAt].every(count)||v.total>1000||!['exhausted','canContinue','busy'].every(k=>typeof v[k as keyof BatchPreview]==='boolean')||v.error!==null&&typeof v.error!=='string'||!strings(v.warnings))return false
  const keys=new Set<string>();let total=0,bytes=0
  for(const p of v.posts){if(!p||typeof p.key!=='string'||!p.key||keys.has(p.key)||typeof p.title!=='string'||!['PENDING','READING_COMMENTS','READY'].includes(p.state)||![p.commentMessages,p.mainMedia,p.commentMedia,p.bytes].every(count))return false;keys.add(p.key);total+=p.mainMedia+p.commentMedia;bytes+=p.bytes}
  if(total!==v.total||bytes!==v.totalBytes||v.exhausted!==v.posts.every(p=>p.state==='READY')||v.exhausted&&v.canContinue)return false
  if(before&&(v.id!==before.id||v.revision<(step?before.revision+1:before.revision)||step&&v.revision!==before.revision+1||!['tags','keywords','types','includeMain','targetDirectory','channelTitle'].every(k=>same(v[k as keyof BatchPreview],before[k as keyof BatchPreview]))||!same(v.posts.map(p=>p.key),before.posts.map(p=>p.key))||v.total<before.total||v.totalBytes<before.totalBytes||v.posts.some((p,i)=>before.posts[i]!.state==='READY'&&p.state!=='READY'||p.commentMessages<before.posts[i]!.commentMessages||p.mainMedia<before.posts[i]!.mainMedia||p.commentMedia<before.posts[i]!.commentMedia)))return false
  return true
}
export function validBatchReceipt(value:unknown,preview:BatchPreview,command:BatchCreate):value is BatchReceipt{
  const v=value as BatchReceipt,s=v?.snapshot
  return !!v&&uuid(v.id)&&count(v.createdAt)&&Array.isArray(v.taskIds)&&v.taskIds.length>0&&v.taskIds.length<=29&&v.taskIds.length===preview.posts.reduce((n,p)=>n+Math.ceil((p.mainMedia+p.commentMedia)/100),0)&&v.taskIds.every(uuid)&&new Set(v.taskIds).size===v.taskIds.length&&!!s&&s.previewId===command.previewId&&s.revision===command.revision&&s.postCount===preview.posts.length&&['channelTitle','tags','keywords','types','includeMain','targetDirectory','total','totalBytes'].every(k=>same(s[k as keyof BatchSnapshot],preview[k as keyof BatchPreview]))
}
