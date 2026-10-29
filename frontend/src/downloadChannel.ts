import type { Post } from './library'
export interface SourceChannel {id:string;title:string}
export function filterSourceChannels(items:SourceChannel[],query:string):SourceChannel[]{
  const normalize=(text:string)=>text.normalize('NFKC').toLowerCase()
  const terms=normalize(query).trim().split(/\s+/u).filter(Boolean)
  return terms.length?items.filter(channel=>{const text=normalize(channel.title+' '+channel.id);return terms.every(term=>text.includes(term))}):items
}
export interface DownloadChannels {items:SourceChannel[];scope:'READABLE_CHANNELS';partial:boolean;warnings:string[]}
export interface ChannelRow {post:Post;match:'MATCH'|'UNVERIFIED';postLink:string|null;comments:'NOT_READ'|'UNAVAILABLE'}
export interface ChannelScan {id:string;accountId:string;channel:SourceChannel;tags:string[];keywords:string[];revision:number;scanned:number;matched:number;unverified:number;rows:ChannelRow[];exhausted:boolean;canContinue:boolean;busy:boolean;limitReached:boolean;error:string|null;warnings:string[];createdAt:number;expiresAt:number}
function id(x:unknown,positive=false):x is string{if(typeof x!=='string'||!/^[-]?[1-9][0-9]*$/.test(x)||x.length>20)return false;const value=BigInt(x);return value>=(positive?1n:-9223372036854775808n)&&value<=9223372036854775807n}
export function validSourceChannels(x:unknown):x is DownloadChannels{const c=x as DownloadChannels;return !!c&&c.scope==='READABLE_CHANNELS'&&typeof c.partial==='boolean'&&Array.isArray(c.warnings)&&c.warnings.every(w=>typeof w==='string')&&Array.isArray(c.items)&&c.items.length<=500&&c.items.every(v=>!!v&&id(v.id)&&typeof v.title==='string')&&new Set(c.items.map(v=>v.id)).size===c.items.length}
export function validPostLink(link:unknown):link is string{if(typeof link!=='string'||link.length>2048)return false;try{const u=new URL(link);return u.protocol==='https:'&&['t.me','telegram.me'].includes(u.hostname)&&!u.username&&!u.password&&!u.port&&!u.hash&&/^\/(?:c\/[0-9]+|[a-zA-Z0-9_]+)\/[1-9][0-9]*\/?$/.test(u.pathname)}catch{return false}}
export function validChannelScan(x:unknown,account:string|null,before?:ChannelScan,step=false):x is ChannelScan{
  const v=x as ChannelScan
  if(!v||typeof v.id!=='string'||!/^[0-9a-f-]{36}$/.test(v.id)||v.accountId!==account||!v.channel||!id(v.channel.id)||typeof v.channel.title!=='string'||!Number.isSafeInteger(v.revision)||v.revision<1||![v.scanned,v.matched,v.unverified].every(n=>Number.isSafeInteger(n)&&n>=0)||v.matched+v.unverified>v.scanned||!Array.isArray(v.rows)||v.rows.length>200||v.rows.length!==v.matched+v.unverified||![v.tags,v.keywords,v.warnings].every(a=>Array.isArray(a)&&a.every(s=>typeof s==='string'))||!['exhausted','canContinue','busy','limitReached'].every(k=>typeof v[k as keyof ChannelScan]==='boolean')||v.error!==null&&typeof v.error!=='string'||!Number.isSafeInteger(v.expiresAt)||!Number.isSafeInteger(v.createdAt))return false
  if(v.exhausted&&(v.canContinue||v.limitReached)||v.limitReached&&v.canContinue)return false
  const keys=new Set<string>();let matched=0,unverified=0
  for(const row of v.rows){const p=row?.post;if(!p||p.accountId!==account||p.channelId!==v.channel.id||!id(p.mainMessageId,true)||typeof p.key!=='string'||!p.key||keys.has(p.key)||typeof p.title!=='string'||typeof p.text!=='string'||!Array.isArray(p.mainMedia)||!Array.isArray(p.warnings)||!['MATCH','UNVERIFIED'].includes(row.match)||!['NOT_READ','UNAVAILABLE'].includes(row.comments)||row.postLink!==null&&!validPostLink(row.postLink))return false;keys.add(p.key);if(row.match==='MATCH')matched++;else unverified++}
  if(matched!==v.matched||unverified!==v.unverified)return false
  if(before&&(v.id!==before.id||v.channel.id!==before.channel.id||JSON.stringify(v.tags)!==JSON.stringify(before.tags)||JSON.stringify(v.keywords)!==JSON.stringify(before.keywords)||v.revision<(step?before.revision+1:before.revision)||step&&v.revision!==before.revision+1||v.scanned<before.scanned||v.matched<before.matched||v.unverified<before.unverified||before.rows.some(r=>!keys.has(r.post.key))))return false
  return true
}
