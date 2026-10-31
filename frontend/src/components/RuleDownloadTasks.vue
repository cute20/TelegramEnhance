<script setup lang="ts">
import {ref,watch,onBeforeUnmount} from 'vue'
import {api,ApiError} from '../api'
import {formatMediaBytes,originalMessageLink} from '../library'
import {validTask,taskLabels,type DownloadTask} from '../downloadTasks'
import {validRuleDownload,ruleDownloadLabels,validRuleFilePage,type RuleDownloadTask,type RuleFileSource,type RuleFileEntry,type RuleFileTotals} from '../ruleDownloadTasks'
import CompactRecord from './CompactRecord.vue'
import InfiniteLoad from './InfiniteLoad.vue'
const props=defineProps<{authorized:boolean;available:boolean;accountId:string|null;active?:boolean;refreshVersion?:number}>()
const emit=defineEmits<{accessError:[cause:ApiError];count:[value:number]}>()
const groups=ref<RuleDownloadTask[]>([]),error=ref(''),loading=ref(false),busy=ref(''),uncertain=ref<string[]>([])
const members=ref<Record<string,DownloadTask[]>>({}),offsets=ref<Record<string,number>>({}),more=ref<Record<string,boolean>>({}),memberErrors=ref<Record<string,string>>({}),memberBusy=ref('')
interface SourceFiles {items:RuleFileEntry[];totals:RuleFileTotals|null;cursor:string|null;hasMore:boolean;loaded:boolean;busy:boolean;error:string;request:number;queued:boolean;failedAppend:boolean|null;failedCursor:string|null;pages:(string|null)[]}
const fileSources:RuleFileSource[]=['COMMENT','MAIN']
const displaySources:RuleFileSource[]=['MAIN','COMMENT']
const files=ref<Record<string,SourceFiles>>({}),opened=ref<Record<string,boolean>>({}),sourceOpen=ref<Record<string,boolean>>({}),refreshingFiles=ref('')
const staleSources=ref<Record<string,boolean>>({})
const hasListed=ref(false),refreshingRange=ref(false)
const fileReads=new Map<string,Promise<unknown>>()
let memberRead:Promise<unknown>|undefined
let fileEpoch=0
let generation=0,epoch=0,disposed=false,pending=false,progressPending=false,index=0,queued=false
let timer:ReturnType<typeof setTimeout>|undefined
const ready=()=>!disposed&&props.active!==false&&props.authorized&&props.available&&!!props.accountId
const current=(request:number,account:string|null)=>ready()&&request===generation&&account===props.accountId
function stop(){clearTimeout(timer);timer=undefined;epoch++}
function schedule(){stop();if(ready()&&!pending&&!progressPending&&!busy.value&&groups.value.some(g=>g.enabled||['RUNNING','QUEUED','PAUSING'].includes(g.state)))timer=setTimeout(()=>void readProgress(),2000)}
function access(cause:unknown){if(cause instanceof ApiError&&[401,403].includes(cause.status))emit('accessError',cause)}
function replace(g:RuleDownloadTask){groups.value=groups.value.map(old=>old.id===g.id&&JSON.stringify(old)!==JSON.stringify(g)?g:old)}
const fileKey=(id:string,source:RuleFileSource)=>id+':'+source
const sourceName=(source:RuleFileSource)=>source==='COMMENT'?'评论媒体':'主帖附件'
function fileState(id:string,source:RuleFileSource){const key=fileKey(id,source);return files.value[key]??=( {items:[],totals:null,cursor:null,hasMore:false,loaded:false,busy:false,error:'',request:0,queued:false,failedAppend:null,failedCursor:null,pages:[]} )}
function sourceIsOpen(id:string,source:RuleFileSource){return sourceOpen.value[fileKey(id,source)]??source==='COMMENT'}
function setSourceOpen(id:string,source:RuleFileSource,event:Event){sourceOpen.value[fileKey(id,source)]=(event.target as HTMLDetailsElement).open}
function hasUnfinished(group:RuleDownloadTask){return group.failedTasks>0||group.completed+group.skipped<group.total}
function unfinishedMembers(group:RuleDownloadTask){return(members.value[group.id]??[]).filter(task=>task.state!=='COMPLETED')}
async function loadFiles(group:RuleDownloadTask,source:RuleFileSource,append=false,retry=false){
  if(!ready()||refreshingRange.value||!opened.value[group.id]||!groups.value.some(g=>g.id===group.id))return false
  const state=fileState(group.id,source)
  if(state.busy)return false
  if(retry){if(state.failedAppend===null)return false;append=state.failedAppend}
  if(append&&(!state.loaded||!state.hasMore&&!retry||!sourceIsOpen(group.id,source)||!!state.error&&!retry))return false
  const cursor=append?(retry?state.failedCursor:state.cursor):null
  if(append&&cursor===null){state.error='文件分页位置未确认，请刷新明细。';return false}
  const request=generation,account=props.accountId,context=fileEpoch,ticket=++state.request
  let reading:Promise<unknown>|undefined
  state.busy=true;state.error=''
  try{
    reading=api<unknown>(`/transfers/download/rule-tasks/${group.id}/files?source=${source}&limit=50${cursor===null?'':'&cursor='+encodeURIComponent(cursor)}`)
    fileReads.set(fileKey(group.id,source),reading)
    const result=await reading
    if(!current(request,account)||context!==fileEpoch||files.value[fileKey(group.id,source)]!==state||ticket!==state.request)return false
    if(!validRuleFilePage(result,group.id,source)||append&&result.hasMore&&result.nextCursor===cursor)throw new Error('文件来源、统计或分页位置无法确认，请手动重试。')
    const updates=new Map(result.items.map(row=>[row.item.id,row]))
    state.items=[...state.items.map(row=>updates.get(row.item.id)??row),...result.items.filter(row=>!state.items.some(old=>old.item.id===row.item.id))]
    state.totals=result.totals;state.cursor=result.nextCursor;state.hasMore=result.hasMore;state.loaded=true;state.failedAppend=null;state.failedCursor=null
    if(!state.pages.includes(cursor))state.pages.push(cursor)
    return true
  }catch(cause){if(current(request,account)&&context===fileEpoch&&ticket===state.request){state.failedAppend=append;state.failedCursor=cursor;state.error=cause instanceof Error?cause.message:'读取文件明细失败。';access(cause)}return false}
  finally{
    if(fileReads.get(fileKey(group.id,source))===reading)fileReads.delete(fileKey(group.id,source))
    if(files.value[fileKey(group.id,source)]===state&&ticket===state.request){state.busy=false
      if(state.queued&&ready()&&opened.value[group.id]){state.queued=false;if(!state.loaded&&!state.error)void loadFiles(group,source)}
    }
  }
}
function retryFiles(group:RuleDownloadTask,source:RuleFileSource){return loadFiles(group,source,false,true)}
async function ensureFiles(group:RuleDownloadTask){
  if(refreshingRange.value)return
  const context=fileEpoch
  for(const source of fileSources){
    if(!ready()||!opened.value[group.id]||context!==fileEpoch)return
    const state=fileState(group.id,source)
    if(state.busy){if(!state.loaded)state.queued=true;continue}
    if(!state.loaded&&!state.error)await loadFiles(group,source)
  }
}
async function refreshFiles(group:RuleDownloadTask){
  if(!ready()||pending||progressPending||refreshingRange.value||refreshingFiles.value||fileSources.some(source=>fileState(group.id,source).busy))return
  const context=fileEpoch;refreshingFiles.value=group.id
  try{await load(group.id)}
  finally{if(context===fileEpoch&&refreshingFiles.value===group.id)refreshingFiles.value=''}
}
function openFiles(group:RuleDownloadTask,event:Event){
  const details=event.target as HTMLDetailsElement
  if(!details.classList.contains('record-disclosure'))return
  opened.value[group.id]=details.open&&ready()
  if(opened.value[group.id]){if(staleSources.value[group.id])void load(group.id);else void ensureFiles(group)}
}
async function load(sourceGroup?:string){
  if(!ready()||pending||progressPending||busy.value){queued=ready();return}
  queued=false;stop();pending=true;loading.value=!groups.value.length;refreshingRange.value=true
  const request=generation,account=props.accountId,context=fileEpoch
  const alive=()=>current(request,account)&&context===fileEpoch
  let succeeded=false
  // Freeze the visible history scope before any GET; returned cursors never expand this refresh.
  const sources=groups.value.filter(group=>opened.value[group.id]&&(!sourceGroup||group.id===sourceGroup)).flatMap(group=>fileSources.flatMap(source=>{
    const state=fileState(group.id,source)
    return state.loaded||sourceGroup?[{groupId:group.id,source,state,items:[...state.items],pages:[...state.pages]}]:[]
  }))
  // One user expansion consumes the stale marker; failed GETs require an explicit retry, never a reopen loop.
  for(const snapshot of sources)delete staleSources.value[snapshot.groupId]
  const knownMembers=Object.entries(members.value).filter(([id])=>!sourceGroup||id===sourceGroup).map(([id,tasks])=>({id,tasks:[...tasks]}))
  try{
    await Promise.allSettled([...fileReads.values(),...(memberRead?[memberRead]:[])])
    if(!alive())return
    const result=await api<{items:RuleDownloadTask[]}>('/transfers/download/rule-tasks')
    if(!alive())return
    if(!Array.isArray(result.items)||result.items.length>20||result.items.some(g=>!validRuleDownload(g))||new Set(result.items.map(g=>g.id)).size!==result.items.length)throw new Error('规则下载汇总无法确认，请刷新。')
    const taskReads=new Map<string,DownloadTask>()
    async function readTask(id:string,ruleId:string){
      if(!alive())return null
      let task=taskReads.get(id)
      if(!task){
        task=await api<DownloadTask>('/transfers/download/tasks/'+encodeURIComponent(id))
        if(!alive())return null
        if(!validTask(task)||task.id!==id||task.automation?.ruleId!==ruleId)throw new Error('已有规则文件记录无法确认，请手动刷新。')
        taskReads.set(id,task)
      }
      if(task.automation?.ruleId!==ruleId)throw new Error('规则任务归属无法确认，请手动刷新。')
      return task
    }
    const stagedSources:{state:SourceFiles;items:RuleFileEntry[];totals:RuleFileTotals;cursor:string|null;hasMore:boolean;readCursors:(string|null)[]}[]=[]
    for(const snapshot of sources){
      if(!alive())return
      if(!result.items.some(group=>group.id===snapshot.groupId))continue
      const updates=new Map<string,RuleFileEntry>();let totals:RuleFileTotals|null=null,headCursor:string|null=null,headMore=false
      const readCursors=snapshot.pages.length?snapshot.pages:[null]
      for(const cursor of readCursors){
        if(!alive())return
        let page:unknown
        try{
          page=await api<unknown>(`/transfers/download/rule-tasks/${snapshot.groupId}/files?source=${snapshot.source}&limit=50${cursor===null?'':'&cursor='+encodeURIComponent(cursor)}`)
          if(!alive())return
          if(!validRuleFilePage(page,snapshot.groupId,snapshot.source))throw new Error('已展开的规则文件明细无法确认，请手动刷新。')
        }catch(cause){if(alive()){snapshot.state.failedAppend=cursor!==null;snapshot.state.failedCursor=cursor;snapshot.state.error=cause instanceof Error?cause.message:'读取文件明细失败。'}throw cause}
        if(!validRuleFilePage(page,snapshot.groupId,snapshot.source))return
        if(cursor===null){headCursor=page.nextCursor;headMore=page.hasMore}
        totals=page.totals;for(const row of page.items)updates.set(row.item.id,row)
      }
      for(const row of snapshot.items)if(!updates.has(row.item.id)){
        // These rows already came from the strict files projection; a task view cannot replace completion evidence.
        if(row.item.state==='COMPLETED'){updates.set(row.item.id,row);continue}
        const task=await readTask(row.taskId,snapshot.groupId)
        if(!task)return
        const item=task.items.find(item=>item.id===row.item.id)
        if(!item||task.automation?.ruleVersion!==row.ruleVersion)throw new Error('已有文件与规则任务不一致，请手动刷新。')
        if(item.state==='COMPLETED')throw new Error('已有文件的保存回执需重新从来源明细核对；已保留原记录，请刷新明细。')
        updates.set(item.id,{...row,item,taskState:task.state,pauseRequested:task.pauseRequested})
      }
      if(totals){
        const items=[...updates.values()],reopen=headMore&&totals.total>items.length&&(!snapshot.state.hasMore||items.some(row=>!snapshot.items.some(old=>old.item.id===row.item.id)))
        stagedSources.push({state:snapshot.state,totals,items,cursor:!snapshot.state.loaded||reopen?headCursor:snapshot.state.cursor,hasMore:!snapshot.state.loaded||reopen?headMore:snapshot.state.hasMore,readCursors})
      }
    }
    const stagedMembers:Record<string,DownloadTask[]>={}
    for(const snapshot of knownMembers){
      if(!result.items.some(group=>group.id===snapshot.id))continue
      const tasks:DownloadTask[]=[]
      for(const old of snapshot.tasks){const task=await readTask(old.id,snapshot.id);if(!task)return;tasks.push(task)}
      stagedMembers[snapshot.id]=tasks
    }
    if(!alive())return
    // Refresh known records in place; local progress never recreates/reorders history or its disclosures.
    const ids=new Set(result.items.map(g=>g.id))
    groups.value=[...groups.value.filter(g=>ids.has(g.id)).map(g=>result.items.find(n=>n.id===g.id)!),...result.items.filter(g=>!groups.value.some(old=>old.id===g.id))]
    for(const staged of stagedSources){
      staged.state.items=staged.items;staged.state.totals=staged.totals;staged.state.cursor=staged.cursor;staged.state.hasMore=staged.hasMore;staged.state.loaded=true
      for(const cursor of staged.readCursors)if(!staged.state.pages.includes(cursor))staged.state.pages.push(cursor)
      if(sourceGroup||staged.state.failedCursor!==null&&staged.readCursors.includes(staged.state.failedCursor)||staged.state.failedAppend===false&&staged.readCursors.includes(null)){staged.state.error='';staged.state.failedAppend=null;staged.state.failedCursor=null}
    }
    for(const [id,tasks]of Object.entries(stagedMembers))members.value[id]=tasks
    hasListed.value=true;uncertain.value=[];error.value='';emit('count',groups.value.length)
    succeeded=true
  }catch(cause){if(alive()){error.value=cause instanceof Error?cause.message:'读取规则下载失败。';access(cause)}}
  finally{pending=false;refreshingRange.value=false;if(current(request,account))loading.value=false;if(queued&&ready())void load();else{if(alive()&&succeeded)for(const group of groups.value)if(opened.value[group.id])void ensureFiles(group);schedule()}}
}
async function readProgress(){
  if(!ready()||pending||busy.value||progressPending)return
  const candidates=groups.value.filter(g=>g.enabled||['RUNNING','QUEUED','PAUSING'].includes(g.state)),selected=candidates[index++%candidates.length]
  if(!selected)return
  const request=generation,account=props.accountId,version=epoch;progressPending=true
  try{
    const result=await api<RuleDownloadTask>('/transfers/download/rule-tasks/'+selected.id)
    if(!current(request,account)||version!==epoch)return
    if(!validRuleDownload(result)||result.id!==selected.id)return
    replace(result)
    const child=members.value[selected.id]?.find(t=>['RUNNING','QUEUED'].includes(t.state))
    if(child&&!memberBusy.value){
      const updated=await api<DownloadTask>('/transfers/download/tasks/'+child.id)
      if(current(request,account)&&version===epoch&&validTask(updated)&&updated.id===child.id&&updated.automation?.ruleId===selected.id)members.value[selected.id]=members.value[selected.id]!.map(t=>t.id===child.id?updated:t)
    }
  }catch(cause){if(current(request,account)&&version===epoch)access(cause)}
  finally{progressPending=false;if(queued&&ready()&&!pending)void load();else schedule()}
}
async function loadMembers(group:RuleDownloadTask,append=false){
  if(!ready()||pending||!hasUnfinished(group)||memberBusy.value||busy.value||append&&more.value[group.id]===false)return
  const request=generation,account=props.accountId,context=fileEpoch,start=append?(offsets.value[group.id]??0):0
  stop();memberBusy.value=group.id;memberErrors.value[group.id]=''
  try{
    const reading=api<{items:DownloadTask[]}>(`/transfers/download/rule-tasks/${group.id}/members?limit=10&offset=${start}`);memberRead=reading
    const result=await reading
    if(!current(request,account)||context!==fileEpoch)return
    if(!Array.isArray(result.items)||result.items.length>10||result.items.some(t=>!validTask(t)||t.automation?.ruleId!==group.id)||new Set(result.items.map(t=>t.id)).size!==result.items.length)throw new Error('规则下载明细无法确认，请刷新。')
    const previous=append?(members.value[group.id]??[]):[]
    members.value[group.id]=[...previous.filter(t=>!result.items.some(n=>n.id===t.id)),...result.items]
    offsets.value[group.id]=start+result.items.length;more.value[group.id]=result.items.length===10
  }catch(cause){if(current(request,account)&&context===fileEpoch){memberErrors.value[group.id]=cause instanceof Error?cause.message:'读取明细失败。';access(cause)}}
  finally{memberRead=undefined;if(request===generation&&account===props.accountId)memberBusy.value='';schedule()}
}
function openMembers(group:RuleDownloadTask,event:Event){if((event.target as HTMLDetailsElement).open&&!members.value[group.id])void loadMembers(group)}
async function toggle(group:RuleDownloadTask){
  if(!ready()||busy.value||pending||uncertain.value.includes(group.id))return
  const request=generation,account=props.accountId;stop();busy.value=group.id;error.value=''
  try{
    const result=await api<{rule:{id:string;enabled:boolean;revision:number}}>(`/rules/download/${group.id}/enabled`,{method:'POST',body:{revision:group.revision,enabled:!group.enabled}})
    if(!current(request,account))return
    if(result?.rule?.id!==group.id||result.rule.enabled!==!group.enabled||result.rule.revision<=group.revision)throw new Error('规则操作回执无法确认。')
    const updated=await api<RuleDownloadTask>('/transfers/download/rule-tasks/'+group.id)
    if(!current(request,account))return
    if(!validRuleDownload(updated)||updated.id!==group.id)throw new Error('汇总状态无法确认。')
    replace(updated)
  }catch(cause){if(current(request,account)){uncertain.value=[...uncertain.value,group.id];error.value=(cause instanceof Error?cause.message:'操作未完成。')+' 请刷新任务核对，不会自动重试。';access(cause)}}
  finally{if(request===generation&&account===props.accountId)busy.value='';if(queued&&ready())void load();else schedule()}
}
async function action(group:RuleDownloadTask,task:DownloadTask,verb:'pause'|'resume'){
  if(!ready()||busy.value||pending||memberBusy.value||uncertain.value.includes(group.id))return
  const request=generation,account=props.accountId;stop();busy.value=group.id
  try{
    const result=await api<DownloadTask>(`/transfers/download/tasks/${task.id}/${verb}`,{method:'POST'})
    if(!current(request,account))return
    if(!validTask(result)||result.id!==task.id||result.automation?.ruleId!==group.id)throw new Error('下载操作回执无法确认。')
    members.value[group.id]=(members.value[group.id]??[]).map(t=>t.id===task.id?result:t)
    const updated=await api<RuleDownloadTask>('/transfers/download/rule-tasks/'+group.id)
    if(current(request,account)&&validRuleDownload(updated)&&updated.id===group.id)replace(updated)
  }catch(cause){if(current(request,account)){uncertain.value=[...uncertain.value,group.id];error.value=(cause instanceof Error?cause.message:'操作未完成。')+' 请刷新任务核对，不会自动重试。';access(cause)}}
  finally{if(request===generation&&account===props.accountId)busy.value='';if(queued&&ready())void load();else schedule()}
}
watch([()=>props.authorized,()=>props.accountId],()=>{
  generation++;fileEpoch++;stop();hasListed.value=false;groups.value=[];members.value={};offsets.value={};more.value={};memberErrors.value={};memberBusy.value='';busy.value='';loading.value=false;error.value='';uncertain.value=[];files.value={};opened.value={};sourceOpen.value={};staleSources.value={};refreshingFiles.value='';emit('count',0)
  queued=ready();if(!pending&&ready())void load()
},{immediate:true})
watch(()=>props.available,()=>{
  generation++;fileEpoch++;stop();queued=false;loading.value=false;memberBusy.value='';refreshingFiles.value=''
  for(const state of Object.values(files.value))state.queued=false
  if(busy.value){if(!uncertain.value.includes(busy.value))uncertain.value.push(busy.value);error.value='连接中断，操作结果未确认，请手动刷新任务核对；不会自动重试。';busy.value=''}
  if(ready()){
    if(!hasListed.value){queued=true;if(!pending)void load()}
    else if(!pending&&!progressPending)void readProgress()
  }
})
watch(()=>props.active,value=>{
  if(value!==false)return
  fileEpoch++;refreshingFiles.value='';stop();queued=false
  for(const group of groups.value)if(fileSources.some(source=>fileState(group.id,source).loaded))staleSources.value[group.id]=true
  opened.value={}
},{flush:'sync'})
watch([()=>props.active,()=>props.refreshVersion],()=>{fileEpoch++;refreshingFiles.value='';stop();queued=ready();if(!pending&&!busy.value&&ready())void load()})
onBeforeUnmount(()=>{disposed=true;generation++;fileEpoch++;stop()})
</script>
<template>
  <div class="rule-download-tasks">
    <p v-if="error" class="notice warning" role="alert">{{error}}</p>
    <p v-if="loading&&!groups.length" class="rule-loading" role="status">正在读取规则下载…</p>
    <CompactRecord v-for="group in groups" :key="group.id" class="rule-download-task" :title="group.name" :description="`${group.channelTitle} · 已保存 ${group.completed}/${group.total} · 主帖附件 ${group.mainTotal} · 评论媒体 ${group.commentTotal} · ${formatMediaBytes(group.totalBytes)}`" :status="ruleDownloadLabels[group.state]" :tone="group.state==='FAILED'?'warning':['RUNNING','QUEUED','PAUSING'].includes(group.state)?'active':'neutral'" :timestamp="group.updatedAt" :default-expanded="false" :active="active" @toggle.capture="openFiles(group,$event)">
      <dl class="rule-task-facts"><dt>保存目录</dt><dd><code>{{group.targetDirectory}}</code></dd><dt>执行时段</dt><dd>{{group.window.mode==='ALL_DAY'?'全天':`${group.window.start}—${group.window.end}`}} · 北京时间</dd><dt>累计下载</dt><dd>{{group.completed}} / {{group.total}} 个媒体<span v-if="group.duplicates"> · 重复媒体 {{group.duplicates}}</span><span v-if="group.failedTasks"> · {{group.failedTasks}} 批需要处理</span></dd></dl>
      <div class="rule-detail-toolbar"><button class="btn" :disabled="refreshingRange||refreshingFiles===group.id||fileSources.some(source=>fileState(group.id,source).busy)||!ready()" @click="refreshFiles(group)">刷新明细</button></div>
      <details v-for="source in displaySources" :key="source" class="rule-source-files" :open="sourceIsOpen(group.id,source)" @toggle="setSourceOpen(group.id,source,$event)">
        <summary>{{sourceName(source)}} <span v-if="fileState(group.id,source).totals">{{fileState(group.id,source).totals!.completed}}/{{fileState(group.id,source).totals!.total}}</span><span v-else>· {{source==='COMMENT'?group.commentTotal:group.mainTotal}} 个</span></summary>
        <p class="source-file-count" v-if="fileState(group.id,source).loaded">已加载 {{fileState(group.id,source).items.length}} 个文件<span v-if="fileState(group.id,source).totals?.duplicates"> · 累计重复媒体 {{fileState(group.id,source).totals!.duplicates}}</span><span v-if="fileState(group.id,source).totals?.skipped"> · 累计历史跳过 {{fileState(group.id,source).totals!.skipped}}</span></p>
        <p v-if="fileState(group.id,source).error" class="notice warning" role="status">{{fileState(group.id,source).error}} <button class="btn" :disabled="fileState(group.id,source).busy||!ready()" @click="retryFiles(group,source)">重试读取</button></p>
        <p v-if="fileState(group.id,source).busy&&!fileState(group.id,source).loaded" class="rule-loading">正在读取文件明细…</p>
        <div v-for="row in fileState(group.id,source).items" :key="row.item.id" class="rule-download-file">
          <strong>{{row.item.fileName}}</strong><small>{{taskLabels[row.item.state]}} · {{formatMediaBytes(row.item.size)}}<span v-if="row.item.state==='DOWNLOADING'"> · 已取得 {{formatMediaBytes(row.item.downloadedBytes)}}</span><span v-if="row.item.state==='WRITING'"> · 已写入 {{formatMediaBytes(row.item.writtenBytes)}}</span><span v-if="row.pauseRequested&&row.taskState==='RUNNING'"> · 正在暂停</span><span v-else-if="row.taskState==='PAUSED'"> · 已暂停</span></small>
          <small v-if="row.item.duplicate&&row.item.state==='COMPLETED'">重复媒体已单独保存</small><small v-if="row.taskState==='FAILED'" class="file-warning">所属任务需要处理，请展开下方“任务处理”。</small><p v-if="row.item.error" class="notice warning">{{row.item.error}}</p>
          <details v-if="row.item.state==='COMPLETED'||row.item.history||row.item.manifestPath" class="file-evidence"><summary>保存位置与来源</summary><code v-if="row.item.state==='COMPLETED'">{{row.item.outputPath}}</code><small v-if="row.item.duplicate">重复依据：{{row.item.duplicate.reason==='CONTENT_SHA'?'大小及 SHA-256 一致':'来源或文件标识及 SHA-256 一致'}}</small><small v-if="row.item.history">历史保存位置：{{row.item.history.path}}</small><code v-if="row.item.manifestPath">{{row.item.manifestPath}}</code><a v-if="originalMessageLink(row.item.sourceLink)" :href="originalMessageLink(row.item.sourceLink)" target="_blank" rel="noreferrer">媒体原消息 ↗</a></details>
          <a v-else-if="originalMessageLink(row.item.sourceLink)" :href="originalMessageLink(row.item.sourceLink)" target="_blank" rel="noreferrer">媒体原消息 ↗</a>
        </div>
        <p v-if="fileState(group.id,source).loaded&&!fileState(group.id,source).items.length" class="rule-loading">暂无此来源的下载记录。</p>
        <InfiniteLoad v-if="sourceIsOpen(group.id,source)&&fileState(group.id,source).loaded" :has-more="fileState(group.id,source).hasMore" :busy="fileState(group.id,source).busy" :blocked="refreshingRange||!ready()||!opened[group.id]||!!fileState(group.id,source).error" :version="fileState(group.id,source).cursor??'end'" :context="`${accountId}:${group.id}:${source}:${active}:${fileEpoch}`" @load="loadFiles(group,source,true)" />
      </details>
      <details v-if="hasUnfinished(group)" class="rule-files" @toggle="openMembers(group,$event)">
        <summary>任务处理<span v-if="group.failedTasks"> · {{group.failedTasks}} 批需要处理</span></summary>
        <div class="rule-detail-toolbar"><button class="btn" :disabled="!!memberBusy||!!busy||!ready()" @click="loadMembers(group)">刷新任务状态</button></div>
        <p v-if="memberErrors[group.id]" class="notice warning">{{memberErrors[group.id]}}</p>
        <details v-for="task in unfinishedMembers(group)" :key="task.id" class="download-segment">
          <summary>{{task.title}}<small>{{taskLabels[task.state]}} · {{task.completed}} / {{task.total}} 个<span v-if="task.automation?.ruleVersion!==undefined"> · 配置 v{{task.automation.ruleVersion}}</span></small></summary>
          <p v-if="task.error" class="notice warning">{{task.error}}</p>
          <p class="segment-directory"><code>{{task.targetDirectory}}</code></p>
          <div class="segment-controls" v-if="task.state!=='COMPLETED'"><button v-if="['RUNNING','QUEUED'].includes(task.state)" class="btn" :disabled="!!busy||!!memberBusy||task.pauseRequested||uncertain.includes(group.id)||!ready()" @click="action(group,task,'pause')">暂停本批</button><button v-else class="btn" :disabled="!!busy||!!memberBusy||uncertain.includes(group.id)||!ready()" @click="action(group,task,'resume')">继续本批未完成项</button></div>
        </details>
        <p v-if="members[group.id]&&!unfinishedMembers(group).length" class="rule-loading">当前已加载记录没有未完成任务。</p>
        <InfiniteLoad v-if="members[group.id]" :has-more="more[group.id]??false" :busy="memberBusy===group.id" :blocked="refreshingRange||!ready()||!!memberBusy||!!busy||!!memberErrors[group.id]" :version="offsets[group.id]??0" :context="`${accountId}:${group.id}:${active}`" @load="loadMembers(group,true)" />
      </details>
      <template #actions><button class="btn" :disabled="!!busy||loading||uncertain.includes(group.id)||!ready()" @click="toggle(group)">{{group.enabled?'暂停规则':'启用规则'}}</button></template>
    </CompactRecord>
  </div>
</template>
<style scoped>
.rule-source-files{margin-top:15px;border-top:1px solid #e5edf4;padding-top:12px;min-width:0}.rule-source-files>summary{cursor:pointer;color:#486b83;font-size:12px;font-weight:550}.rule-source-files>summary span{font-weight:400;color:#7e98ac;margin-left:5px}.source-file-count{font-size:10px;color:#91a6b6;line-height:1.9;margin:8px 0}.file-evidence{margin-top:6px;color:#879faf}.file-evidence summary{cursor:pointer}.rule-download-file .file-warning{color:#a98450}.rule-source-files .notice{display:block;overflow-wrap:anywhere}.rule-source-files .notice .btn{font-size:10px;margin-left:6px}
.rule-task-facts{display:grid;grid-template-columns:65px minmax(0,1fr);gap:10px 16px;font-size:11px;line-height:1.8;margin:0}.rule-task-facts dt{color:#8ba1b1}.rule-task-facts dd{margin:0;color:#526e83;min-width:0;overflow-wrap:anywhere}code{font:10px/1.9 Consolas,monospace;overflow-wrap:anywhere}.rule-files{margin-top:18px;border-top:1px solid #eaf0f5;padding-top:14px}.rule-files>summary,.download-segment>summary{cursor:pointer;font-size:11px;color:#57778e}.rule-detail-toolbar{display:flex;justify-content:flex-end;margin:8px 0}.rule-detail-toolbar .btn,.segment-controls .btn{font-size:10px}.download-segment{padding:12px 0;border-bottom:1px solid #eaf0f5}.download-segment>summary{overflow-wrap:anywhere}.download-segment>summary small{display:block;margin:4px 0 0 14px;color:#8ea5b6;font-size:10px}.segment-directory{margin:8px 0;color:#8ea5b6}.rule-download-file{padding:12px 0;border-bottom:1px solid #f0f4f7;min-width:0;overflow-wrap:anywhere}.rule-download-file strong{font-size:11px;font-weight:500}.rule-download-file small,.rule-download-file code{display:block;color:#8ea5b6;font-size:10px;line-height:1.9}.rule-download-file a,.rule-download-file details{font-size:10px}.rule-loading{font-size:11px;color:#8ea5b6}.segment-controls{margin:8px 0}@media(max-width:480px){.rule-task-facts{grid-template-columns:55px minmax(0,1fr);gap:8px}}
</style>
