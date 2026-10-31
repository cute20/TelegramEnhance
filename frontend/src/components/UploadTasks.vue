<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { uploadTaskLabels, validUploadTask, documentAcceptancePath, canStartManaged, type UploadTask } from '../uploadTasks'
import { uploadTypes, type UploadType } from '../uploadPreview'
import { formatMediaBytes } from '../library'
import { validRetryCheck, retryLabels, type UploadRetryCheck } from '../uploadRetry'
import CompactRecord from './CompactRecord.vue'
import InfiniteLoad from './InfiniteLoad.vue'
import { mergeTaskHistory } from '../taskHistory'
const props=withDefaults(defineProps<{authorized:boolean;available:boolean;accountId:string|null;active?:boolean;refreshVersion?:number}>(),{active:true})
const emit=defineEmits<{accessError:[cause:ApiError]}>()
const tasks=ref<UploadTask[]>([]),error=ref(''),workerWarning=ref(''),busy=ref(''),offset=ref(0)
const hasMore=ref(true),loading=ref(false),loadingMore=ref(false),historyError=ref('')
const groupDisclosures=ref<Record<string,boolean>>({})
const groupKey=(taskId:string,groupId:string)=>JSON.stringify([taskId,groupId])
function groupOpen(task:UploadTask,groupId:string){return groupDisclosures.value[groupKey(task.id,groupId)]??task.state!=='COMPLETED'}
function toggleGroup(taskId:string,groupId:string,event:Event){const details=event.currentTarget as HTMLDetailsElement|null;if(details)groupDisclosures.value[groupKey(taskId,groupId)]=details.open}
watch(tasks,items=>{for(const task of items)for(const group of task.groups){const key=groupKey(task.id,group.id);if(!(key in groupDisclosures.value))groupDisclosures.value[key]=task.state!=='COMPLETED'}},{flush:'sync'})
const retryCheck=ref<UploadRetryCheck|null>(null),retryUncertain=ref(false)
const takeover=ref<UploadTask|null>(null),takeoverDialog=ref<HTMLDialogElement|null>(null)
function closeTakeover(){takeoverDialog.value?.close();takeover.value=null}
async function prepareTakeover(task:UploadTask){
  if(!canOperate()||busy.value||pending||retryUncertain.value||!task.automation||task.automation.manualTakeover||['RUNNING','COMPLETED','CANCELLED'].includes(task.state))return
  const context=generation,account=props.accountId;takeover.value=JSON.parse(JSON.stringify(task));await nextTick()
  if(current(context,account)&&canOperate()&&takeover.value?.id===task.id)takeoverDialog.value?.showModal()
}
async function confirmTakeover(){const task=takeover.value;if(!task||!canOperate()||busy.value||pending||retryUncertain.value)return;closeTakeover();clearRetry();await action(task,'takeover')}
let retryOperation:{taskId:string;operationId:string;checkId:string}|null=null
function clearRetry(){retryCheck.value=null;retryUncertain.value=false;retryOperation=null}
let generation=0,disposed=false,pending=false,refreshQueued=false,timer:ReturnType<typeof setTimeout>|undefined
let progressEpoch=0,progressPending=false,progressIndex=0,listEpoch=0
function isActive(){return props.active!==false}
function canOperate(){return !disposed&&isActive()&&props.authorized&&props.available&&!!props.accountId}
function current(context:number,account:string|null){return !disposed&&context===generation&&account===props.accountId}
function stopTimer(){clearTimeout(timer);timer=undefined;progressEpoch++}
function schedule(){stopTimer();if(canOperate()&&!pending&&!busy.value&&!retryUncertain.value&&!progressPending&&tasks.value.some(t=>['RUNNING','QUEUED'].includes(t.state)))timer=setTimeout(()=>void readProgress(),2000)}
async function readProgress(){
  if(!canOperate()||pending||busy.value||retryUncertain.value||progressPending)return
  const active=tasks.value.filter(task=>['RUNNING','QUEUED'].includes(task.state))
  const selected=active.find(task=>task.state==='RUNNING')??active[progressIndex++%active.length]
  if(!selected)return
  const context=generation,epoch=progressEpoch,account=props.accountId;progressPending=true
  try{const result=await api<UploadTask>('/transfers/upload/tasks/'+encodeURIComponent(selected.id));if(!current(context,account)||!canOperate()||epoch!==progressEpoch||retryUncertain.value)return;if(!validUploadTask(result)||result.id!==selected.id)return;tasks.value=tasks.value.map(task=>task.id===result.id&&JSON.stringify(task)!==JSON.stringify(result)?result:task)}
  catch(cause){if(current(context,account)&&epoch===progressEpoch&&cause instanceof ApiError&&[401,403].includes(cause.status))emit('accessError',cause)}
  finally{progressPending=false;finishCycle()}
}
function finishCycle(){
  if(disposed)return
  if(refreshQueued&&canOperate()&&!pending&&!busy.value&&!retryUncertain.value&&!progressPending){void load();return}
  schedule()
}
async function load(append=false){
  if(!canOperate())return
  if(append&&(!hasMore.value||pending||busy.value||retryUncertain.value))return
  if(pending||busy.value||retryUncertain.value||!append&&progressPending){refreshQueued=true;return}
  refreshQueued=false;stopTimer();const request=generation,account=props.accountId;pending=true;loading.value=true;loadingMore.value=append
  const epoch=listEpoch,start=append?offset.value:0,known=[...tasks.value],initial=offset.value===0&&known.length===0
  const valid=()=>current(request,account)&&canOperate()&&epoch===listEpoch&&!retryUncertain.value
  try{
    const updates=new Map<string,UploadTask>(),warnings=new Set<string>()
    let firstLength=0,lastLength=0
    const end=append?start+20:Math.max(20,offset.value,known.length)
    for(let cursor=start;cursor<end;cursor+=20){
      if(!valid())return
      const result=await api<{items:UploadTask[];workerWarning:string|null}>('/transfers/upload/tasks?limit=20&offset='+cursor)
      if(!valid())return
      if(!Array.isArray(result.items)||result.items.length>20||new Set(result.items.map(t=>t?.id)).size!==result.items.length||result.items.some(t=>!validUploadTask(t)))throw new Error('上传任务回执无法确认，请刷新。')
      if(cursor===start)firstLength=result.items.length
      lastLength=result.items.length
      for(const item of result.items)updates.set(item.id,item)
      if(result.workerWarning)warnings.add(result.workerWarning)
      if(result.items.length<20)break
    }
    if(!append){
      for(const item of known){
        if(updates.has(item.id))continue
        if(!valid())return
        const updated=await api<UploadTask>('/transfers/upload/tasks/'+encodeURIComponent(item.id))
        if(!valid())return
        if(!validUploadTask(updated)||updated.id!==item.id)throw new Error('已加载上传任务的状态无法确认，请刷新。')
        updates.set(item.id,updated)
      }
    }
    if(!valid())return
    const incoming=[...updates.values()]
    const newHead=!append&&incoming.some(item=>!known.some(old=>old.id===item.id))
    tasks.value=mergeTaskHistory(append?tasks.value:known,incoming)
    workerWarning.value=[...warnings].join('；');error.value=''
    if(append)historyError.value=''
    if(append||initial){offset.value=start+firstLength;hasMore.value=firstLength===20}
    else if(newHead)hasMore.value=lastLength===20
  }catch(cause){if(valid()){error.value=cause instanceof Error?cause.message:'读取上传任务未完成';if(append)historyError.value=error.value;if(cause instanceof ApiError&&[401,403].includes(cause.status))emit('accessError',cause)}}
  finally{if(current(request,account)){loading.value=false;loadingMore.value=false}pending=false;finishCycle()}
}
async function action(task:UploadTask,verb:'start'|'pause'|'reconcile'|'cancel'|'accept-document'|'takeover',groupId=''){
  if(!canOperate()||busy.value||pending||retryUncertain.value)return
  const recover=verb==='reconcile'&&task.state==='NEEDS_REVIEW'
  const path=verb==='accept-document'?documentAcceptancePath(task,groupId):`/transfers/upload/tasks/${encodeURIComponent(task.id)}/${recover?'recover-pending':verb}`
  if(!path)return
  stopTimer();const request=++generation,account=props.accountId;busy.value=task.id;error.value=''
  try{const result=await api<UploadTask>(path,{method:'POST',...(recover?{timeout:150000}:{})});if(!current(request,account))return;if(!validUploadTask(result)||result.id!==task.id)throw new Error('上传操作回执无法确认');tasks.value=tasks.value.map(t=>t.id===result.id?result:t)}
  catch(cause){if(current(request,account))error.value=(cause instanceof Error?cause.message:'操作未完成')+' 请先刷新核对，不会自动重试。'}
  finally{if(current(request,account))busy.value='';finishCycle()}
}
function loadMore(){return load(true)}
async function checkFailures(task:UploadTask){
  if(!canOperate()||busy.value||pending||retryUncertain.value||task.state!=='NEEDS_REVIEW')return;stopTimer();const request=++generation,account=props.accountId;busy.value=task.id;error.value='';clearRetry()
  try{const result=await api<UploadRetryCheck>(`/transfers/upload/tasks/${encodeURIComponent(task.id)}/retry-check`,{method:'POST',timeout:150000});if(!current(request,account))return;if(!validRetryCheck(result,task))throw new Error('失败成员核对回执无法确认');retryCheck.value=result}
  catch(cause){if(current(request,account))error.value=(cause instanceof Error?cause.message:'核对未完成')+' 没有发起重试，请手动重新核对。'}finally{if(current(request,account))busy.value='';finishCycle()}
}
async function retryFailed(task:UploadTask,repeat=false){
  if(!canOperate()||busy.value||pending)return
  if(!repeat){if(task.state!=='NEEDS_REVIEW'||retryUncertain.value||!retryCheck.value||retryCheck.value.taskId!==task.id||!retryCheck.value.retryable||retryCheck.value.expiresAt<=Date.now())return;retryOperation={taskId:task.id,operationId:crypto.randomUUID(),checkId:retryCheck.value.id}}
  if(!retryOperation||retryOperation.taskId!==task.id)return;stopTimer();const request=++generation,account=props.accountId;busy.value=task.id;error.value=''
  try{const result=await api<UploadTask>(`/transfers/upload/tasks/${encodeURIComponent(task.id)}/retry-failed`,{method:'POST',body:{operationId:retryOperation.operationId,checkId:retryOperation.checkId}});if(!current(request,account))return;if(!validUploadTask(result)||result.id!==task.id)throw new Error('失败成员重试回执无法确认');tasks.value=tasks.value.map(t=>t.id===result.id?result:t);clearRetry()}
  catch(cause){if(current(request,account)){retryUncertain.value=!(cause instanceof ApiError)||cause.status>=500;error.value=(cause instanceof Error?cause.message:'重试提交未确认')+' 不会自动再次提交；请先核对任务。'}}finally{if(current(request,account))busy.value='';finishCycle()}
}
async function lookupRetry(task:UploadTask){
  if(!canOperate()||!retryOperation||busy.value||pending)return;stopTimer();const request=++generation,account=props.accountId;busy.value=task.id
  try{const result=await api<{task:UploadTask|null}>(`/transfers/upload/tasks/${encodeURIComponent(task.id)}/retry-operations/${retryOperation.operationId}`);if(!current(request,account))return;if(result.task&&validUploadTask(result.task)&&result.task.id===task.id){tasks.value=tasks.value.map(t=>t.id===task.id?result.task!:t);clearRetry();error.value=''}else error.value='尚未查到本次操作，请稍后再核对；如重试请求，沿用原操作号。'}catch(cause){if(current(request,account))error.value=cause instanceof Error?cause.message:'状态未核实'}finally{if(current(request,account))busy.value='';finishCycle()}
}
watch([()=>props.authorized,()=>props.available,()=>props.accountId],()=>{generation++;listEpoch++;closeTakeover();clearRetry();stopTimer();groupDisclosures.value={};tasks.value=[];error.value='';historyError.value='';workerWarning.value='';busy.value='';offset.value=0;hasMore.value=true;loading.value=false;loadingMore.value=false;refreshQueued=true;finishCycle()},{immediate:true})
watch([()=>props.active,()=>props.refreshVersion],()=>{listEpoch++;stopTimer();if(!isActive()){loading.value=false;loadingMore.value=false;closeTakeover();return}refreshQueued=true;finishCycle()})
onBeforeUnmount(()=>{disposed=true;generation++;closeTakeover();stopTimer()})
</script>
<template>
  <section class="upload-tasks" aria-label="上传任务"><header class="upload-toolbar"><div><h2>上传任务</h2></div><button class="btn" :disabled="!isActive()||loading||!!busy||retryUncertain" @click="load()">刷新上传任务</button></header>
    <div v-if="error" class="notice danger" role="alert">{{error}}</div><div v-if="workerWarning" class="notice warning">{{workerWarning}}</div>
    <p v-if="!tasks.length" class="upload-empty">暂无上传任务。先在“新建传输→上传准备”预览并保存任务。</p>
    <CompactRecord v-for="task in tasks" :key="task.id" class="upload-task" :title="task.postTitle" :description="`${task.channelTitle} · ${task.total} 个文件 · 回执 ${task.confirmed} · 已归档 ${task.archived}${task.automation?' · '+task.automation.name:''}`" :status="task.stopRequested&&task.state==='RUNNING'?'当前组继续核对，之后暂停':uploadTaskLabels[task.state]!" :tone="task.state==='COMPLETED'?'success':['NEEDS_REVIEW','SENT_PENDING_ARCHIVE'].includes(task.state)?'warning':['RUNNING','QUEUED'].includes(task.state)?'active':'neutral'" :timestamp="task.updatedAt" :active="active" :default-expanded="false" :alert="task.error"><div class="upload-task-body"><small>{{task.channelTitle}} · {{task.discussionTitle}}</small><h3>{{task.postTitle}}</h3>
      <div v-if="task.automation" class="upload-rule-context"><strong>自动规则：{{task.automation.name}} · v{{task.automation.version}}</strong><p>保存的时段：{{task.automation.windowMode==='ALL_DAY'?'全天':`${task.automation.start}—${task.automation.end}`}} · 北京时间</p><p>{{task.automation.reason}}</p><button v-if="!task.automation.manualTakeover&&!['RUNNING','COMPLETED','CANCELLED'].includes(task.state)" class="btn" :disabled="!!busy||retryUncertain" @click="prepareTakeover(task)">转为手动处理</button></div><p class="upload-count">{{task.total}} 个文件 · 正式回执 {{task.confirmed}} · 已归档 {{task.archived}}</p><p>待上传：<code>{{task.sourceDirectory}}</code><br/>归档至：<code>{{task.archiveDirectory}}</code></p><a :href="task.postLink" target="_blank" rel="noreferrer">核对 Telegram 目标主帖 ↗</a>
      <div v-if="task.error" class="notice warning" role="status">{{task.error}}</div>
      <section v-for="group in task.groups" :key="group.id" class="upload-task-group"><strong>第 {{group.number}} 组 · {{uploadTypes[group.mediaType as UploadType]??group.mediaType}} · {{group.items.length}} 个</strong><span>{{uploadTaskLabels[group.state]}}</span><p v-if="group.hasRetries">本组有失败成员的独立重试记录；原计划分组保留，实际送达可能分为单条或新相册。</p><p v-if="group.error&&group.error!==task.error" class="notice warning">{{group.error}}</p><details :open="groupOpen(task,group.id)" @toggle="toggleGroup(task.id,group.id,$event)"><summary>文件清单与回执（{{group.items.length}}）</summary><ul><li v-for="item in group.items" :key="item.id"><div><strong>{{item.fileName}}</strong><small>{{formatMediaBytes(item.size)}} · {{uploadTaskLabels[item.state]}}</small><small v-if="item.formalMessageId">正式消息：{{item.formalMessageId}}</small><code v-if="item.state==='ARCHIVED'">{{item.archivePath}}</code><small v-if="item.error" class="upload-file-error">{{item.error}}</small></div></li></ul></details><button v-if="documentAcceptancePath(task,group.id)" class="btn" :disabled="!!busy" @click="action(task,'accept-document',group.id)">接受按文件送达并归档（不重发）</button></section>
      <p v-if="task.state==='NEEDS_REVIEW'" class="upload-retry-note">这组已有发送记录，不能整组重传。请先核对：成功成员保留，仅明确失败且Telegram允许的成员可重试。</p>
<section v-if="retryCheck?.taskId===task.id" class="upload-retry-panel" aria-label="失败成员核对">
<strong>第 {{task.groups.find(g=>g.id===retryCheck!.groupId)?.number}} 组核对结果</strong>
<p>已成功 {{retryCheck.confirmed}} · 可重试 {{retryCheck.retryable}} · 仍在发送 {{retryCheck.pending}} · 需等待或人工核对 {{retryCheck.unresolved}}</p>
<ul><li v-for="row in retryCheck.items" :key="row.itemId"><strong>{{row.fileName}}</strong><span>{{retryLabels[row.state]}}</span><small>{{row.reason}}</small></li></ul>
<p>仅逐条重试明确失败的原消息，可能显示为单条或新的相册；成功和结果不明的成员不会重发。整组正式回执齐全后才归档，后续未发送组仍暂停。</p>
<button v-if="retryCheck.retryable&&!retryUncertain" class="btn primary" :disabled="!!busy" @click="retryFailed(task)">仅重试这 {{retryCheck.retryable}} 个失败成员</button>
<p v-if="!retryCheck.retryable">本轮没有可直接重试的成员。仍在发送的等待原请求结果；其余请核对Telegram消息。全部成功后可使用“核对已有回执”完成归档。</p>
</section>
<div v-if="retryUncertain&&retryOperation?.taskId===task.id" class="upload-task-actions"><button class="btn" :disabled="!!busy" @click="lookupRetry(task)">核对本次重试操作</button><button class="btn" :disabled="!!busy" @click="retryFailed(task,true)">重试同一请求</button></div>
<div class="upload-task-actions"><button v-if="task.state==='NEEDS_REVIEW'" class="btn primary" :disabled="!!busy||retryUncertain" @click="checkFailures(task)">核对并继续</button><button v-if="['READY','PAUSED'].includes(task.state)&&task.groups.every(g=>['READY','CONFIRMED'].includes(g.state))" class="btn primary" :disabled="!!busy||!canStartManaged(task)" @click="action(task,'start')">{{!canStartManaged(task)?(task.automation?.status==='WAITING_WINDOW'?'等待自动上传时段':'规则已暂停或变更'):(task.state==='READY'?'确认并开始上传':'继续未发送组')}}</button><button v-if="['RUNNING','QUEUED'].includes(task.state)" class="btn" :disabled="!!busy||task.stopRequested" @click="action(task,'pause')">当前组后暂停</button><button v-if="['NEEDS_REVIEW','SENT_PENDING_ARCHIVE','PAUSED'].includes(task.state)" class="btn" :disabled="!!busy" @click="action(task,'reconcile')">{{task.state==='SENT_PENDING_ARCHIVE'?'仅重试归档':'核对已有回执，不重发'}}</button><button v-if="['READY','PAUSED'].includes(task.state)&&task.groups.every(g=>g.state==='READY')" class="btn" :disabled="!!busy" @click="action(task,'cancel')">取消未发送任务</button></div>
    </div></CompactRecord>
    <InfiniteLoad :has-more="hasMore" :busy="loadingMore" :blocked="!canOperate() || loading || !!busy || !!error || !!historyError || retryUncertain" :version="offset" :context="`${accountId ?? ''}:${active}`" loading-label="正在加载历史任务…" @load="loadMore" />
    <p v-if="historyError && hasMore && tasks.length" class="history-retry">{{historyError}} <button class="btn" :disabled="!canOperate() || loading || !!busy || retryUncertain" @click="loadMore">重试加载历史记录</button></p><p v-else-if="tasks.length && !hasMore" class="history-end">已显示全部历史任务</p>
  </section>
<dialog ref="takeoverDialog" class="upload-takeover-dialog" @cancel="closeTakeover"><template v-if="takeover"><h2>转为手动处理</h2><strong>{{takeover.channelTitle}} / {{takeover.postTitle}}</strong><p>接管不会立即发送。之后需要在任务中心手动确认开始，不再受自动规则的时段和启停控制。</p><p>原目标、目录、文件版本及发送回执保留；结果不明的成员仍需核对，不会整组重发。</p><p>来源：{{takeover.sourceDirectory}}<br/>归档：{{takeover.archiveDirectory}}</p><div class="upload-task-actions"><button class="btn" @click="closeTakeover">取消</button><button class="btn primary" @click="confirmTakeover">确认转为手动，不立即发送</button></div></template></dialog>
</template>
<style scoped>
.upload-task-body>small{color:#8299ab;font-size:10px}.upload-task-body>p,.upload-task-body>a{font-size:11px;color:#7893a8;line-height:1.9;overflow-wrap:anywhere}.upload-task-body>a{color:#5684aa;text-decoration:none}
.upload-rule-context{font-size:11px;line-height:1.8;color:#6a889e;background:#f3f8fc;border:1px solid #dfebf3;padding:12px;border-radius:6px;margin:15px 0}.upload-takeover-dialog{max-width:620px;width:calc(100% - 36px);max-height:85vh;overflow:auto;border:1px solid #dde8f1;border-radius:10px;padding:24px;color:#5b7a91;font-size:12px;line-height:1.9}.upload-takeover-dialog::backdrop{background:#12283d88}.upload-takeover-dialog p{overflow-wrap:anywhere}
.upload-retry-note,.upload-retry-panel{font-size:11px;color:#718da2;line-height:1.9}.upload-retry-panel{background:#f4f8fc;border:1px solid #dfebf4;border-radius:6px;padding:15px;margin:15px 0}.upload-retry-panel ul{list-style:none;padding:0}.upload-retry-panel li{padding:9px 0;border-bottom:1px solid #e0eaf2}.upload-retry-panel li strong,.upload-retry-panel li span,.upload-retry-panel li small{display:block;overflow-wrap:anywhere}.upload-retry-panel li small{font-size:10px;color:#90a4b3}
.upload-task-group details{margin-top:10px}.upload-task-group summary{font-size:10px;color:#91a7b7;cursor:pointer}
.upload-tasks{margin-bottom:32px}.upload-toolbar{display:flex;justify-content:space-between;gap:14px;margin-bottom:16px}.upload-toolbar h2{font-size:15px;font-weight:500;color:#53758d}.upload-toolbar p,.upload-empty{font-size:10px;line-height:1.9;color:#96acbc}.upload-empty{padding:20px;background:#fff;border:1px solid #e4ecf3;border-radius:7px}.upload-toolbar>.btn{flex-shrink:0;font-size:10px;align-self:center}.upload-task{padding:0;background:white;border:1px solid #dfe9f1;border-radius:8px;margin-bottom:8px}.upload-task>header{display:flex;justify-content:space-between;align-items:flex-start;gap:15px}.upload-task header small{font-size:10px;color:#90a7b8}.upload-task h3{font-size:13px;font-weight:550;color:#54738b;margin:6px 0;overflow-wrap:anywhere}.upload-task header>strong{font-size:10px;color:#7da28d;white-space:nowrap;font-weight:500}.upload-task>p,.upload-task>a{font-size:10px;color:#8aa4b6;line-height:1.9;overflow-wrap:anywhere}.upload-task code{font-size:9px;overflow-wrap:anywhere}.upload-task>a{text-decoration:none;color:#5684aa}.upload-task-group{margin:17px 0;border-top:1px solid #e6eef4;padding-top:12px;font-size:10px;color:#65839a}.upload-task-group>strong{font-weight:500}.upload-task-group>span{float:right;color:#8ba1b0}.upload-task-group ul{list-style:none;padding:0;margin:5px 0}.upload-task-group li{padding:10px 0;border-bottom:1px solid #edf2f6}.upload-task-group li strong{font-size:10px;font-weight:500;overflow-wrap:anywhere}.upload-task-group li small{display:block;font-size:9px;color:#9dafbc;line-height:1.9}.upload-task-group li .upload-file-error{color:#b08c60}.upload-task-actions{display:flex;gap:8px;flex-wrap:wrap;margin-top:18px}.upload-task-actions>.btn,.upload-task-pages>.btn{font-size:10px}.upload-task-pages{display:flex;gap:10px;justify-content:flex-end}
@media(max-width:480px){.upload-toolbar{flex-direction:column;align-items:stretch;gap:10px}.upload-toolbar>div{width:100%;min-width:0}.upload-toolbar>.btn{align-self:flex-end}}
</style>
