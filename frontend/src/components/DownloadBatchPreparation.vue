<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {api,ApiError} from '../api'
import {mediaTypes} from '../downloadPreview'
import {validBatch,validBatchReceipt,type BatchPreview,type BatchReceipt,type BatchCreate} from '../downloadBatch'
const props=defineProps<{scanId:string;scanRevision:number;postKeys:string[];accountId:string|null;active:boolean;authorized:boolean;available:boolean}>()
const emit=defineEmits<{tasks:[];accessError:[cause:ApiError];locked:[value:boolean]}>()
const types=ref(['VIDEO']),includeMain=ref(false),directory=ref(''),preview=ref<BatchPreview|null>(null),created=ref<BatchReceipt|null>(null)
const busy=ref(''),running=ref(false),needsCheck=ref(false),uncertain=ref(false),error=ref(''),notice=ref('')
const locked=computed(()=>!!busy.value||running.value||uncertain.value||!!created.value)
let generation=0,disposed=false,command:BatchCreate|null=null
const ready=()=>props.active&&props.authorized&&props.available&&!!props.accountId
const alive=(g:number,account:string|null)=>!disposed&&g===generation&&props.accountId===account&&ready()
const bytes=(value:number)=>value<1024?value+' B':value<1024*1024?(value/1024).toFixed(1)+' KB':(value/1024/1024).toFixed(1)+' MB'
function fail(cause:unknown){error.value=cause instanceof Error?cause.message:'批量准备未完成。';if(cause instanceof ApiError&&[401,403].includes(cause.status))emit('accessError',cause)}
function toggleType(type:string){if(locked.value)return;const next=types.value.includes(type)?types.value.filter(value=>value!==type):[...types.value,type];types.value=next.length?next:mediaTypes.map(value=>value.type)}
function reset(){generation++;busy.value='';running.value=false;needsCheck.value=false;preview.value=null;created.value=null;uncertain.value=false;command=null;error.value='';notice.value=''}
async function begin(){if(!ready()||locked.value||!props.postKeys.length||!directory.value.trim())return;reset();const g=generation,account=props.accountId;busy.value='start'
  try{const value=await api<BatchPreview>('/transfers/download/batches/preview',{method:'POST',body:{scanId:props.scanId,revision:props.scanRevision,postKeys:[...props.postKeys],types:[...types.value],includeMain:includeMain.value,targetDirectory:directory.value.trim()}});if(!alive(g,account))return;if(!validBatch(value,account))throw new Error('批量清单身份无法确认。');preview.value=value;busy.value='';await proceed()}
  catch(cause){if(alive(g,account))fail(cause)}finally{if(alive(g,account))busy.value=''}
}
async function proceed(){if(!ready()||locked.value||needsCheck.value||!preview.value?.canContinue)return;running.value=true;error.value='';notice.value='';const g=generation,account=props.accountId,start=Date.now();let steps=0
  try{while(running.value&&alive(g,account)&&preview.value?.canContinue){if(steps++>=200||Date.now()-start>=20*60*1000){notice.value='本次准备已暂停，可继续已统计的位置；尚未表示全部读完。';break}const before:BatchPreview=preview.value;busy.value='step'
      try{const value:BatchPreview=await api<BatchPreview>('/transfers/download/batches/preview/'+before.id+'/next',{method:'POST',timeout:110000,body:{revision:before.revision}});if(!alive(g,account))return;if(!validBatch(value,account,before,true))throw new Error('准备位置或媒体统计无法确认。');preview.value=value;if(value.error){error.value=value.error;break}}
      catch(cause){if(alive(g,account)){fail(cause);needsCheck.value=true}break}finally{if(alive(g,account))busy.value=''}
    }}finally{if(alive(g,account))running.value=false}
}
function stop(){running.value=false;notice.value='已停止后续统计，当前只读步骤完成后保留结果。'}
async function check(){if(!ready()||busy.value||!preview.value)return;running.value=false;const g=generation,account=props.accountId,before=preview.value;busy.value='check'
  try{const value=await api<BatchPreview>('/transfers/download/batches/preview/'+before.id);if(!alive(g,account))return;if(!validBatch(value,account,before))throw new Error('批量准备状态无法确认。');preview.value=value;needsCheck.value=value.busy;error.value=value.error??'';notice.value=value.busy?'当前步骤仍在读取，请稍后再核对。':'位置已核对，可显式继续准备。'}catch(cause){if(alive(g,account))fail(cause)}finally{if(alive(g,account))busy.value=''}
}
async function create(retry=false){if(!ready()||busy.value||running.value||created.value||!preview.value?.exhausted||!preview.value.total||needsCheck.value||uncertain.value&&!retry)return
  command??={operationId:crypto.randomUUID(),previewId:preview.value.id,revision:preview.value.revision};const g=generation,account=props.accountId,before=preview.value;busy.value='create';error.value=''
  try{const value=await api<BatchReceipt>('/transfers/download/batches',{method:'POST',body:command});if(!alive(g,account))return;if(!validBatchReceipt(value,before,command))throw new Error('批量任务创建回执无法确认。');created.value=value;uncertain.value=false}
  catch(cause){if(alive(g,account)){fail(cause);uncertain.value=true}}finally{if(alive(g,account))busy.value=''}
}
async function lookup(){if(!ready()||busy.value||!command||!preview.value)return;const g=generation,account=props.accountId,before=preview.value;busy.value='lookup'
  try{const value=await api<{batch:BatchReceipt|null}>('/transfers/download/batches/by-operation/'+command.operationId);if(!alive(g,account))return;if(!value||!('batch'in value)||value.batch!==null&&!validBatchReceipt(value.batch,before,command))throw new Error('批量任务核对回执无效。');if(value.batch){created.value=value.batch;uncertain.value=false;error.value=''}else notice.value='尚未找到此操作的任务，可显式重试相同创建请求。'}catch(cause){if(alive(g,account))fail(cause)}finally{if(alive(g,account))busy.value=''}
}
watch([types,includeMain,directory],reset,{flush:'sync'})
watch([()=>props.scanId,()=>JSON.stringify(props.postKeys),()=>props.accountId,()=>props.authorized,()=>props.available],reset)
watch(()=>props.active,active=>{if(!active){generation++;running.value=false;if(busy.value==='step')needsCheck.value=true;if(busy.value==='create')uncertain.value=true;busy.value=''}else if(ready()&&needsCheck.value&&preview.value)void check()})
watch(locked,value=>emit('locked',value),{flush:'sync',immediate:true})
onBeforeUnmount(()=>{disposed=true;generation++;running.value=false;emit('locked',false)})
</script>
<template>
  <section class="batch-download" aria-label="批量下载准备"><h3>批量下载 · 已选 {{postKeys.length}} 条主帖</h3><p>先统计所选主帖的评论媒体和精确大小，再核对并创建下载任务。每批最多20帖、1000个媒体。</p>
    <fieldset :disabled="locked"><legend>评论媒体类型 · 可多选</legend><div class="batch-types"><button type="button" :aria-pressed="types.length===5" :class="{selected:types.length===5}" @click="types=mediaTypes.map(value=>value.type)">全部</button><button v-for="type in mediaTypes" :key="type.type" type="button" :aria-pressed="types.includes(type.type)" :class="{selected:types.includes(type.type)}" @click="toggleType(type.type)">{{type.label}}</button></div><label class="batch-main"><input v-model="includeMain" type="checkbox" /> 包含主帖自身全部媒体</label><label class="batch-directory">保存根目录<input v-model="directory" class="input" placeholder="例如 D:\TelegramDownloads" autocomplete="off" /></label></fieldset>
    <div class="batch-actions"><button class="btn" :disabled="locked||!ready()||!directory.trim()" @click="begin">{{busy==='start'?'正在准备…':'准备批量下载清单'}}</button><button v-if="running" class="btn" @click="stop">停止后续统计</button><button v-if="preview?.canContinue&&!running&&!needsCheck" class="btn" :disabled="locked||!ready()" @click="proceed">继续准备</button><button v-if="preview&&!created&&!uncertain" class="btn" :disabled="!!busy||running||!ready()" @click="check">核对准备位置</button></div>
    <p v-if="error" class="notice danger" role="alert">{{error}}</p><p v-if="notice" class="notice" role="status">{{notice}}</p>
    <template v-if="preview"><div class="batch-summary" role="status"><strong>{{preview.total}} 个媒体 · {{bytes(preview.totalBytes)}}</strong><span>{{preview.exhausted?'所选主帖清单已准备完成':running?'正在逐帖统计评论…':'尚未读完，未创建下载任务'}}</span></div><ul class="batch-posts"><li v-for="post in preview.posts" :key="post.key"><span>{{post.title}}</span><small>{{post.state==='READY'?'已准备':post.state==='PENDING'?'等待统计':'读取评论中'}} · 主帖 {{post.mainMedia}} / 评论 {{post.commentMedia}} · {{bytes(post.bytes)}}</small></li></ul>
      <p v-for="warning in preview.warnings" :key="warning">{{warning}}</p>
      <p>创建后按主帖分任务，每任务最多100个媒体，共用原下载队列；重复媒体仍保存到本次目录下的“重复媒体”文件夹。后台重启后任务暂停，可在任务中心继续。</p>
      <button v-if="!created&&!uncertain" class="btn primary" :disabled="locked||needsCheck||!ready()||!preview.exhausted||!preview.total" @click="create()">{{busy==='create'?'正在保存任务…':`创建并开始批量下载（${preview.total}）`}}</button>
      <div v-if="uncertain" class="batch-actions"><span>创建结果未确认，不会自动重试。</span><button class="btn" :disabled="!!busy||!ready()" @click="lookup">核对批量任务</button><button class="btn" :disabled="!!busy||!ready()" @click="create(true)">重试相同创建请求</button></div>
      <div v-if="created" class="notice success" role="status"><span>已创建 {{created.taskIds.length}} 个下载任务，共 {{created.snapshot.total}} 个媒体。</span><button class="btn" @click="emit('tasks')">查看任务</button></div>
    </template>
  </section>
</template>
<style scoped>
.batch-download{padding:16px;margin:14px 0;border:1px solid #dbe8f2;border-radius:7px;background:#f8fbfe;font-size:11px}.batch-download h3{margin:0;color:#355b76;font-size:13px}.batch-download p{font-size:10px;color:#829bad;line-height:1.9}.batch-download fieldset{border:0;padding:0;margin:14px 0}.batch-download legend{color:#6b89a1;font-size:11px;margin-bottom:8px}.batch-types,.batch-actions{display:flex;flex-wrap:wrap;gap:7px;align-items:center}.batch-types button{border:1px solid #dbe7f0;border-radius:4px;padding:6px 10px;background:white;color:#7d99ae;font-size:10px}.batch-types button.selected{background:#203f56;border-color:#203f56;color:white}.batch-main{display:flex;align-items:center;gap:6px;margin:12px 0;color:#6d8ba2;font-size:10px}.batch-directory{display:block;color:#6d8ba2}.batch-directory .input{display:block;width:100%;margin-top:7px}.batch-actions{margin:12px 0}.batch-download .btn{font-size:10px}.batch-summary{display:flex;justify-content:space-between;flex-wrap:wrap;gap:8px;color:#4a7a9c}.batch-posts{list-style:none;padding:0;max-height:220px;overflow:auto}.batch-posts li{display:flex;flex-wrap:wrap;justify-content:space-between;gap:7px;padding:9px 0;border-bottom:1px solid #e3edf5;color:#4a718c;overflow-wrap:anywhere}.batch-posts small{color:#89a0b2;font-size:10px}.batch-download .notice{font-size:11px}
</style>
