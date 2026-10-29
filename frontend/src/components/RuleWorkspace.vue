<script setup lang="ts">
import { nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { formatMediaBytes } from '../library'
import { validUploadPreview, uploadTypes } from '../uploadPreview'
import { validRule, ruleTime, ruleStates, ruleArchiveDirectory, type RuleInput, type RuleView, type RulePreview } from '../rules'
import {uploadTaskLabels} from '../uploadTasks'
import CompactRecord from './CompactRecord.vue'
import DownloadRules from './DownloadRules.vue'
import RuleFormDialog from './RuleFormDialog.vue'
import type {TaskCategory} from '../taskCategories'
const props=defineProps<{authorized:boolean;available:boolean;accountId:string|null}>()
const emit=defineEmits<{accessError:[cause:ApiError];tasks:[category:TaskCategory]}>()
const ruleCategories=[{id:'download',label:'下载规则'},{id:'upload',label:'上传规则'}] as const
const selectedCategory=ref<'download'|'upload'>('download')
function selectCategory(category:'download'|'upload'){if(busy.value||formOpen.value||activation.value)return;selectedCategory.value=category}
const items=ref<RuleView[]>([]),busy=ref(''),error=ref(''),notice=ref(''),editing=ref<RuleView|null>(null),formOpen=ref(false),uncertain=ref(false)
const preview=ref<RulePreview|null>(null)
const activation=ref<{rule:RuleView;preview:RulePreview}|null>(null),activationDialog=ref<HTMLDialogElement|null>(null)
const empty=():RuleInput=>({name:'',link:'',sourceDirectory:'',archiveDirectory:'',recursive:true,captionMode:'FILENAME',window:{mode:'ALL_DAY',start:null,end:null,timezone:'Asia/Shanghai'},executionMode:'AUTO_UPLOAD'})
const form=reactive(empty())
const nameAutomatic=ref(true),nameBusy=ref(false),nameError=ref('')
let nameGeneration=0,lastNameAttempt='',lastAutoName=''
let generation=0,disposed=false,pendingOperation:string|null=null
const ready=()=>props.authorized&&props.available&&!!props.accountId
function ruleDescription(value:RuleView){return `${value.mode==='AUTO_UPLOAD'?'自动上传':'仅观察'} · ${value.rule.input.window.mode==='ALL_DAY'?'全天':`${value.rule.input.window.start}—${value.rule.input.window.end}`} · 北京时间`}
function jobNeedsAttention(value:RuleView){return !!value.job&&['NEEDS_REVIEW','SENT_PENDING_ARCHIVE','FAILED'].includes(value.job.state)}
function ruleAlert(value:RuleView){return value.rule.observation?.outcome==='ERROR'?value.rule.observation.error:value.job?.error??(jobNeedsAttention(value)?'最近任务需要核对，请到任务中心处理。':null)}
function alive(g:number,account:string|null){return !disposed&&generation===g&&props.accountId===account&&ready()}
function fail(cause:unknown){error.value=cause instanceof Error?cause.message:'操作未完成';if(cause instanceof ApiError&&[401,403].includes(cause.status))emit('accessError',cause)}
function put(value:unknown){if(!validRule(value,props.accountId))throw new Error('规则回执无法确认，请刷新列表核对');items.value=[value,...items.value.filter(x=>x.rule.id!==value.rule.id)]}
async function refresh(){
  if(!ready()||busy.value||disposed)return;const g=generation,account=props.accountId
  try{const result=await api<{items:RuleView[];mode:string}>('/rules');if(!alive(g,account)||busy.value)return
    if(!['RULES','OBSERVE_ONLY'].includes(result.mode)||!Array.isArray(result.items)||result.items.length>20||!result.items.every(x=>validRule(x,account)))throw new Error('规则列表无法确认')
    items.value=result.items
    if(preview.value&&!items.value.some(v=>v.rule.id===preview.value!.ruleId&&v.rule.revision===preview.value!.revision))preview.value=null
  }catch(cause){if(alive(g,account))fail(cause)}
}
watch(()=>form.sourceDirectory,source=>{form.archiveDirectory=ruleArchiveDirectory(source)},{flush:'sync'})
watch(()=>form.link,()=>{nameGeneration++;nameBusy.value=false;nameError.value='';lastNameAttempt='';if(nameAutomatic.value&&form.name===lastAutoName)form.name=''},{flush:'sync'})
function edit(value:RuleView|null){if(busy.value||uncertain.value)return;generation++;nameGeneration++;nameBusy.value=false;nameError.value='';nameAutomatic.value=false;editing.value=value;Object.assign(form,empty(),JSON.parse(JSON.stringify(value?value.rule.input:empty())));form.executionMode=value?.mode??'AUTO_UPLOAD';form.archiveDirectory=ruleArchiveDirectory(form.sourceDirectory);nameAutomatic.value=!value;lastNameAttempt='';lastAutoName='';formOpen.value=true;preview.value=null;error.value='';notice.value=''}
function closeForm(){
  if(busy.value)return
  generation++;nameGeneration++;nameBusy.value=false;nameError.value='';formOpen.value=false
  if(!uncertain.value){editing.value=null;pendingOperation=null;error.value=''}
}
function reopenForm(){if(uncertain.value&&!busy.value)formOpen.value=true}
async function resolveName(force=false){
  if(!ready()||!formOpen.value||nameBusy.value||busy.value||uncertain.value||!form.link.trim())return false
  if(!force&&(!nameAutomatic.value&&form.name.trim()||lastNameAttempt===form.link.trim()))return !!form.name.trim()
  if(force)nameAutomatic.value=true
  const ticket=++nameGeneration,g=generation,account=props.accountId,link=form.link.trim();lastNameAttempt=link;nameBusy.value=true;nameError.value=''
  try{const result=await api<{accountId:string;link:string;name:string}>('/rules/name',{method:'POST',timeout:150000,body:{link}})
    if(!alive(g,account)||ticket!==nameGeneration||!formOpen.value||form.link.trim()!==link)return false
    if(result.accountId!==account||result.link!==link||typeof result.name!=='string'||!result.name.trim()||result.name.length>80)throw new Error('主帖名称回执无法确认')
    if(nameAutomatic.value){form.name=result.name;lastAutoName=result.name}return !!form.name.trim()
  }catch(cause){if(alive(g,account)&&ticket===nameGeneration){nameError.value=(cause instanceof Error?cause.message:'主帖名称未读取到')+'，可手动填写或点击重新获取。';if(cause instanceof ApiError&&[401,403].includes(cause.status))emit('accessError',cause)}return false}
  finally{if(ticket===nameGeneration)nameBusy.value=false}
}
async function save(){
  if(!ready()||busy.value||uncertain.value||nameBusy.value)return
  const before=generation,beforeAccount=props.accountId,named=!!form.name.trim()||await resolveName()
  if(!alive(before,beforeAccount)||!formOpen.value)return
  if(!named){error.value='请填写规则名称，或先获取主帖名称。';return}
  const g=++generation,account=props.accountId,old=editing.value
  const input=JSON.parse(JSON.stringify(form)) as RuleInput
  input.archiveDirectory=ruleArchiveDirectory(input.sourceDirectory)
  if(input.window.mode==='ALL_DAY'){input.window.start=null;input.window.end=null}
  const operation=old?null:crypto.randomUUID();pendingOperation=operation;busy.value='save';error.value='';notice.value='';preview.value=null
  try{const result=await api<RuleView>(old?'/rules/'+old.rule.id:'/rules',{method:old?'PUT':'POST',body:old?{revision:old.rule.revision,input}:{operationId:operation,input}})
    if(!alive(g,account))return;if(!validRule(result,account)||result.rule.enabled||result.mode!==(input.executionMode??'OBSERVE_ONLY'))throw new Error('保存后的规则模式或暂停状态与本次设置不符，请先核对。');put(result);formOpen.value=false;editing.value=null;pendingOperation=null;notice.value=result.mode==='AUTO_UPLOAD'?'自动上传规则已保存并暂停；请预览核对后明确启用。':old?'规则修改已保存并暂停。':'规则已保存，默认暂停观察。'
  }catch(cause){if(alive(g,account)){fail(cause);if(!(cause instanceof ApiError)||cause.status>=500){uncertain.value=true;error.value+=' 请先核对保存结果，不会自动重复提交。'}}}finally{if(alive(g,account))busy.value=''}
}
async function lookup(){
  if(!ready()||busy.value||!uncertain.value||!pendingOperation&&!editing.value)return;const g=generation,account=props.accountId;busy.value='lookup'
  try{const result=await api<RuleView|null>(pendingOperation?'/rules/by-operation/'+pendingOperation:'/rules/'+editing.value!.rule.id)
    if(!alive(g,account))return;if(!result){error.value='尚未查到这次保存，请稍后再次核对。';return}if(result.mode!==(form.executionMode??'OBSERVE_ONLY')){error.value='本次保存的规则模式尚未确认，请再次核对。';return}if(editing.value&&result.rule?.revision<=editing.value.rule.revision){error.value='尚未确认新版本已保存，请稍后再次核对。';return}put(result);uncertain.value=false;pendingOperation=null;formOpen.value=false;editing.value=null;notice.value='已读取当前保存结果，请核对保存内容。';error.value=''
  }catch(cause){if(alive(g,account))fail(cause)}finally{if(alive(g,account))busy.value=''}
}
async function act(value:RuleView,kind:'enabled'|'observe'|'preview'){
  if(!ready()||busy.value||uncertain.value||value.scanning&&kind!=='enabled')return
  if(kind==='enabled'&&value.mode==='AUTO_UPLOAD'&&!value.rule.enabled){await prepareActivation(value);return}
  const g=++generation,account=props.accountId;busy.value=kind;error.value='';notice.value='';preview.value=null
  try{const result=await api<unknown>('/rules/'+value.rule.id+'/'+kind,{method:'POST',timeout:kind==='preview'?150000:80000,body:{revision:value.rule.revision,...(kind==='enabled'?{enabled:!value.rule.enabled}:{})}})
    if(!alive(g,account))return
    if(kind==='preview'){const p=result as RulePreview;if(p.ruleId!==value.rule.id||p.version!==value.rule.version||p.revision!==value.rule.revision||!validUploadPreview(p.preview,account!))throw new Error('分组预览无法确认');preview.value=p}
    else{put(result);notice.value=kind==='enabled'?'目录观察状态已更新。':'本次目录检测已完成，请查看结果。'}
  }catch(cause){if(alive(g,account)){fail(cause);error.value+=' 不会自动重试，请刷新规则核对状态。'}}finally{if(alive(g,account))busy.value=''}
}
function closeActivation(){activationDialog.value?.close();activation.value=null}
async function prepareActivation(value:RuleView){
  if(!ready()||busy.value||uncertain.value)return;const g=++generation,account=props.accountId;busy.value='activation';error.value='';notice.value=''
  try{const result=await api<RulePreview>('/rules/'+value.rule.id+'/preview',{method:'POST',timeout:150000,body:{revision:value.rule.revision}});if(!alive(g,account))return;if(result.ruleId!==value.rule.id||result.version!==value.rule.version||result.revision!==value.rule.revision||!validUploadPreview(result.preview,account!))throw new Error('启用预览无法确认');activation.value={rule:JSON.parse(JSON.stringify(value)),preview:result};await nextTick();if(alive(g,account))activationDialog.value?.showModal()}
  catch(cause){if(alive(g,account))fail(cause)}finally{if(alive(g,account))busy.value=''}
}
async function confirmActivation(){
  const selected=activation.value;if(!selected||!ready()||busy.value)return;if(selected.preview.preview.expiresAt<=Date.now()){closeActivation();error.value='启用预览已过期，请重新预览后确认。';return}const g=++generation,account=props.accountId;busy.value='enable-auto';error.value=''
  try{const result=await api<RuleView>('/rules/'+selected.rule.rule.id+'/enabled',{method:'POST',body:{revision:selected.rule.rule.revision,enabled:true,previewId:selected.preview.preview.id}});if(!alive(g,account))return;put(result);closeActivation();notice.value='自动上传已明确启用，按保存的时段处理当前及今后稳定媒体。'}
  catch(cause){if(alive(g,account)){fail(cause);closeActivation();error.value+=' 请刷新规则核对是否已启用，不会自动重复提交。'}}finally{if(alive(g,account))busy.value=''}
}
watch([()=>props.authorized,()=>props.available,()=>props.accountId],(context,previous)=>{
  const identityChanged=!previous||context[0]!==previous[0]||context[2]!==previous[2]
  const saving=busy.value==='save'||busy.value==='lookup'
  generation++;closeActivation();nameGeneration++;nameBusy.value=false;lastNameAttempt='';preview.value=null;busy.value=''
  if(identityChanged){nameError.value='';nameAutomatic.value=true;lastAutoName='';items.value=[];error.value='';notice.value='';uncertain.value=false;pendingOperation=null;formOpen.value=false;editing.value=null;Object.assign(form,empty())}
  else if(saving){uncertain.value=true;error.value='连接中断，已保留本次提交，请恢复连接后核对保存结果；不会自动重复提交。'}
  if(ready())void refresh()
},{immediate:true})
onBeforeUnmount(()=>{disposed=true;generation++;closeActivation();nameGeneration++})
</script>
<template>
  <div class="rule-workspace">
    <div class="rule-category-switch" role="group" aria-label="规则类型"><button v-for="category in ruleCategories" :id="'rule-category-'+category.id" :key="category.id" type="button" :class="{selected:selectedCategory===category.id}" :aria-pressed="selectedCategory===category.id" :aria-controls="'rule-panel-'+category.id" :disabled="!!busy||formOpen||!!activation" @click="selectCategory(category.id)">{{category.label}}</button></div>
    <section id="rule-panel-download" v-show="selectedCategory==='download'" class="download-rule-panel" aria-labelledby="rule-category-download">
    <DownloadRules :authorized="authorized" :available="available" :account-id="accountId" @access-error="emit('accessError',$event)" @tasks="emit('tasks','download')" />
    </section>
    <section id="rule-panel-upload" v-show="selectedCategory==='upload'" class="upload-rules" aria-labelledby="rule-category-upload">
    <header class="rule-toolbar rule-section-header"><span class="rule-count">{{items.length}} 条上传规则</span><button class="btn" :disabled="!!busy||!authorized||!available" @click="refresh()">刷新</button><button class="btn primary" :disabled="!!busy||uncertain||!authorized||!available" @click="edit(null)">新建上传规则</button></header>
    <div v-if="!authorized||!available" class="notice warning">请先连接 Telegram 并恢复本机服务。</div>

    <p v-if="busy&&!formOpen" class="notice" role="status">正在处理，请等待当前操作完成…</p><p v-if="error&&!formOpen" class="notice danger" role="alert">{{error}}</p><p v-if="notice" class="notice success" role="status">{{notice}}</p>
    <div v-if="uncertain&&!formOpen" class="notice warning rule-uncertain"><p>保存结果尚未确认，请先核对，避免重复创建或修改规则。</p><div class="rule-actions"><button class="btn" type="button" :disabled="!!busy" @click="lookup">核对本次保存结果</button><button class="btn" type="button" :disabled="!!busy" @click="reopenForm">返回表单</button></div></div>
    <RuleFormDialog :open="formOpen" :title="editing?'编辑上传规则':'新建上传规则'" :busy="!!busy" @close="closeForm">
    <form v-if="formOpen" class="rule-form" @submit.prevent="save">
      <p v-if="!available" class="notice warning" role="status">连接暂时中断，已保留填写内容；恢复连接后可继续。</p>
      <p v-if="editing" class="rule-muted">修改后需重新启用，已创建的任务保持原参数。</p>
      <p v-if="busy" class="notice" role="status">正在处理，请等待当前操作完成…</p><p v-if="error" class="notice danger" role="alert">{{error}}</p>
      <fieldset :disabled="!!busy||uncertain||!authorized||!available">
        <p v-if="form.executionMode==='OBSERVE_ONLY'" class="rule-muted">这条旧规则仅检测目录，不上传文件。需要自动上传时，请新建上传规则。</p>
        <p v-if="form.executionMode==='AUTO_UPLOAD'" class="notice warning">自动上传会持续处理此目录当前及今后新增的稳定媒体。保存后先暂停，必须预览并明确启用；修改配置后也需重新启用。</p>
        <label>目标频道主帖链接<input v-model="form.link" class="input" type="url" required placeholder="https://t.me/…" @blur="resolveName()" /></label>
        <label>规则名称<input v-model="form.name" class="input" maxlength="80" placeholder="填写链接后自动获取主帖名称，可修改" @input="nameAutomatic=!form.name.trim()" /></label>
        <div class="rule-name-action"><small>{{nameBusy?'正在读取主帖名称…':'自动使用主帖名称，也可以手动修改。'}}</small><button class="btn" type="button" :disabled="nameBusy||!form.link.trim()" @click="resolveName(true)">{{nameBusy?'读取中…':'使用主帖名称'}}</button></div>
        <small v-if="nameError" role="alert">{{nameError}}</small>
        <div class="rule-fields"><label>待上传目录<input v-model="form.sourceDirectory" class="input" required placeholder="D:\待上传" /></label><label>已上传归档目录（自动生成）<input :value="form.archiveDirectory" class="input" readonly placeholder="随待上传目录自动生成" /></label></div>
        <small>归档放在待上传目录旁，文件夹名加“-已上传”。实际归档时才创建目录；保存和检测不会创建。</small>
        <small v-if="editing&&editing.rule.input.archiveDirectory!==form.archiveDirectory">保存新版本后使用上方归档目录，旧版本的目录记录保留。</small>
        <div class="rule-options"><label><input v-model="form.recursive" type="checkbox" />包含子目录</label><label>媒体说明<select v-model="form.captionMode" class="input"><option value="FILENAME">使用文件名</option><option value="EMPTY">留空</option></select></label><label>执行时段<select v-model="form.window.mode" class="input"><option value="ALL_DAY">全天</option><option value="WINDOW">指定时段</option></select></label></div>
        <div v-if="form.window.mode==='WINDOW'" class="rule-options"><label>开始<input v-model="form.window.start" class="input" type="time" required /></label><label>结束<input v-model="form.window.end" class="input" type="time" required /></label><small>北京时间 · 支持跨午夜，例如22:00至08:00</small></div>
        <div class="rule-actions"><button class="btn primary" :disabled="nameBusy||!form.archiveDirectory">{{busy==='save'?'保存中…':'保存规则'}}</button><button class="btn" type="button" @click="closeForm">取消</button></div>
      </fieldset>
      <div v-if="uncertain" class="rule-actions"><button class="btn" type="button" :disabled="!!busy" @click="lookup">核对本次保存结果</button><button class="btn" type="button" :disabled="!!busy" @click="closeForm">稍后核对</button></div>
    </form>
    </RuleFormDialog>
    <p v-if="!items.length&&!formOpen" class="rule-empty">还没有规则。添加待上传目录和目标主帖后，可以先核对目录内容及分组。</p>
    <CompactRecord v-for="value in items" :key="value.rule.id" class="rule-card" :title="value.rule.input.name" :description="ruleDescription(value)" :status="value.scanning?'正在检测…':value.mode==='AUTO_UPLOAD'?(value.state==='OBSERVING'?'自动上传已启用':value.state==='PAUSED'?'自动上传已暂停':ruleStates[value.state]):ruleStates[value.state]" :tone="ruleAlert(value)?'warning':value.rule.enabled?'active':'neutral'" :timestamp="value.rule.observation?.checkedAt" time-label="检测" :alert="ruleAlert(value)">
      <dl class="rule-facts">
        <dt>目标主帖</dt><dd><a :href="value.rule.input.link" target="_blank" rel="noreferrer">{{value.rule.input.link}} ↗</a></dd>
        <dt>待上传目录</dt><dd><code>{{value.rule.input.sourceDirectory}}</code></dd>
        <dt>归档目录</dt><dd><code>{{value.rule.input.archiveDirectory}}</code></dd>
        <dt>文件范围</dt><dd>{{value.rule.input.recursive?'包含子目录':'仅当前目录'}} · {{value.rule.input.captionMode==='FILENAME'?'文件名作为说明':'不添加说明'}}</dd>
      </dl>
      <p v-if="value.state==='WAITING_WINDOW'" class="rule-muted">下次运行：{{ruleTime(value.nextAllowedAt)}}</p>
      <p v-if="value.rule.observation?.outcome==='ERROR'" class="notice warning">{{value.rule.observation.error}}</p>
      <div class="rule-detection" v-if="value.rule.observation?.scan"><span>最近检测 {{ruleTime(value.rule.observation.scanAt)}}</span><strong>{{value.rule.observation.scan.files.length}} 个候选媒体</strong></div><p v-else class="rule-muted">尚未检测目录。</p>
      <details v-if="value.rule.observation?.scan" class="rule-observation"><summary>检测明细与文件</summary><p>新增 {{value.rule.observation.added}} · 变化 {{value.rule.observation.changed}} · 本次未观察到 {{value.rule.observation.unobserved}} · 暂缓 {{value.rule.observation.scan.deferredFiles}} · 重复 {{value.rule.observation.scan.duplicateFiles}} · 跳过链接 {{value.rule.observation.scan.linkedEntries}}</p><ul><li v-for="file in value.rule.observation.scan.files.slice(0,50)" :key="file.relativePath">{{file.relativePath}} · {{formatMediaBytes(file.size)}}</li></ul><p v-if="value.rule.observation.scan.files.length>50">仅显示前50个文件</p><p v-if="value.rule.observation.upload">最近调度 {{ruleTime(value.rule.observation.upload.checkedAt)}}：{{value.rule.observation.upload.message}}</p></details>
      <div v-if="value.job" class="rule-latest"><span>最近任务</span><strong>{{uploadTaskLabels[value.job.state]??value.job.state}}</strong><span>{{value.job.total}} 个媒体 · 已归档 {{value.job.archived}}</span><button class="btn" @click="emit('tasks','upload')">查看任务</button><p v-if="value.job.manualTakeover">已转为手动处理</p><p v-else-if="value.job.ruleVersion!==value.rule.version">旧版本任务，请到任务中心处理。</p><p v-if="value.job.error" class="notice warning">{{value.job.error}}</p></div>
      <div class="rule-actions"><button class="btn" :disabled="!!busy||uncertain||value.scanning||!authorized||!available" @click="act(value,'preview')">预览分组</button></div>
      <template #actions><button class="btn" :disabled="!!busy||uncertain||!authorized||!available" @click="act(value,'enabled')">{{value.mode==='AUTO_UPLOAD'?(value.rule.enabled?'暂停自动上传':'启用自动上传'):(value.rule.enabled?'暂停观察':'启用观察')}}</button><button class="btn" :disabled="!!busy||uncertain||value.scanning||!authorized||!available" @click="act(value,'observe')">立即检测</button><button class="btn" :disabled="!!busy||uncertain" @click="edit(value)">修改规则</button><button v-if="jobNeedsAttention(value)" class="btn" @click="emit('tasks','upload')">到任务中心处理</button></template>
    </CompactRecord>
    <section v-if="preview" class="settings-section rule-preview"><h2>分组预览 · 规则版本 {{preview.version}}</h2><p>{{preview.preview.channelTitle}} / {{preview.preview.postTitle}}</p><p>{{preview.preview.fileCount}} 个媒体 · {{preview.preview.groups.length}} 组 · {{formatMediaBytes(preview.preview.totalBytes)}}</p><p class="rule-muted">本次目标权限已核对，仍未发送。目录变化后请重新预览。</p><p v-for="warning in preview.preview.warnings.filter(w=>!w.startsWith('预览不发送或移动文件；'))" :key="warning" class="rule-muted">{{warning}}</p><div v-for="group in preview.preview.groups" :key="group.number" class="rule-group"><strong>第{{group.number}}组 · {{uploadTypes[group.mediaType]}} · {{group.files.length}}个</strong><ul><li v-for="file in group.files" :key="file.relativePath">{{file.relativePath}} · {{formatMediaBytes(file.size)}}</li></ul></div></section>
    </section>
  </div>
  <dialog ref="activationDialog" class="rule-activation" @cancel="closeActivation">
    <template v-if="activation"><h2>确认启用自动上传</h2><p>此规则将持续处理下方目录当前及今后新增的稳定媒体，发送到指定主帖评论区。正式回执齐全后归档。</p><strong>{{activation.preview.preview.channelTitle}} / {{activation.preview.preview.postTitle}}</strong><p><a :href="activation.rule.rule.input.link" target="_blank" rel="noreferrer">{{activation.rule.rule.input.link}} ↗</a></p><p class="rule-path">来源：{{activation.rule.rule.input.sourceDirectory}}<br/>归档：{{activation.rule.rule.input.archiveDirectory}}</p><p>{{activation.rule.rule.input.recursive?'包含子目录':'仅当前目录'}} · 媒体说明：{{activation.rule.rule.input.captionMode==='FILENAME'?'使用文件名':'留空'}}</p><p>时段：{{activation.rule.rule.input.window.mode==='ALL_DAY'?'全天':`${activation.rule.rule.input.window.start}—${activation.rule.rule.input.window.end}`}} · 北京时间</p><p>当前预览 {{activation.preview.preview.fileCount}} 个媒体、{{activation.preview.preview.groups.length}} 组；实际按同类型每次一组处理。已有同目标上传任务的内容不重复创建或移动。</p><details><summary>查看当前分组与文件</summary><p v-for="group in activation.preview.preview.groups" :key="group.number">第{{group.number}}组 · {{uploadTypes[group.mediaType]}} · {{group.files.map(f=>f.relativePath).join('、')}}</p></details><p>窗口结束、暂停或改版后不启动新单元，已开始的单元继续核对。结果不明或归档失败时停止新任务，需在任务中心处理。</p><div class="rule-actions"><button class="btn" :disabled="!!busy" @click="closeActivation">暂不启用</button><button class="btn primary" :disabled="!!busy" @click="confirmActivation">确认启用自动上传</button></div></template>
  </dialog>
</template>
<style scoped>
.rule-category-switch{display:flex;flex-wrap:wrap;gap:8px;border-bottom:1px solid #dfe8ef;padding-bottom:15px;margin-bottom:23px}.rule-category-switch button{border:1px solid #d8e3ec;border-radius:6px;background:#fff;color:#7893a8;padding:8px 14px;font-size:12px;line-height:1.5}.rule-category-switch button.selected{background:#263f54;border-color:#263f54;color:#fff}.rule-category-switch button:hover:not(.selected):not(:disabled){background:#f0f6fa}.download-rule-panel{min-width:0}.download-rule-panel :deep(.download-rules){margin:0;padding:0;border:0}@media(max-width:480px){.rule-category-switch{gap:6px;margin-bottom:18px}.rule-category-switch button{font-size:10px;padding:7px 10px}}
.upload-rules{min-width:0}.rule-section-header{margin:0 0 12px;align-items:flex-start}.rule-section-header>div{flex:1;min-width:200px}.rule-section-header h2{font-size:17px;margin-bottom:6px}.rule-section-header p,.rule-count{margin:0;color:#7894a8;font-size:12px;line-height:1.8}.rule-count{margin:12px 0}.rule-form{min-width:0;margin:0!important;padding:0;border:0;box-shadow:none}.rule-form .notice,.upload-rules>.notice{font-size:12px;line-height:1.8}.rule-form small{display:block;color:#869eaf;font-size:11px;line-height:1.9;overflow-wrap:anywhere}.rule-uncertain p{margin:0 0 10px}.rule-form .rule-actions{margin-top:18px}.rule-form .rule-name-action small{flex:1;min-width:160px}.rule-form .rule-fields>*{min-width:0}.rule-form .rule-options{align-items:flex-start}.rule-form .rule-options label{max-width:100%}.rule-form .rule-options .input{min-width:0}.rule-form .rule-muted{font-size:12px;color:#869eaf;line-height:1.8}@media(max-width:600px){.rule-section-header>div{flex-basis:100%}.rule-section-header>.btn{flex:1}.rule-intro{flex-wrap:wrap}.rule-intro details{max-width:100%}}
.rule-intro{display:flex;align-items:flex-start;gap:16px;justify-content:space-between;font-size:11px;line-height:1.9;color:#7892a7}.rule-intro details{flex:0 0 auto;max-width:500px;margin:0}.rule-intro summary{white-space:nowrap}.rule-intro p{font-size:11px;white-space:normal}
.rule-job{border:1px solid #dfebf3;border-radius:6px;padding:12px;margin:15px 0;font-size:11px;color:#7190a6;line-height:1.8}.rule-activation{border:1px solid #dce7ef;border-radius:10px;max-width:660px;width:calc(100% - 36px);max-height:85vh;overflow:auto;padding:24px;color:#55788f;font-size:12px;line-height:1.9}.rule-activation::backdrop{background:#12283d88}.rule-activation h2{font-size:17px}.rule-activation strong{overflow-wrap:anywhere}
.rule-workspace{max-width:1100px}.rule-workspace>.notice{font-size:12px;line-height:1.8}.rule-workspace>.notice p{margin:5px 0}.rule-workspace small,.rule-muted{color:#869eaf;font-size:11px;line-height:1.9}.rule-toolbar,.rule-actions,.rule-options,.rule-name-action{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.rule-toolbar{margin:14px 0;font-size:12px;color:#7894a8}.rule-toolbar>span{margin-right:auto}.rule-form,.rule-preview{margin:18px 0}.rule-card{margin:8px 0}.rule-workspace h2{font-size:15px;margin:0 0 10px}.rule-form fieldset{border:0;padding:0;margin:0}.rule-form label{display:flex;flex-direction:column;gap:8px;font-size:12px;margin:16px 0;color:#638096}.rule-form .input{width:100%;min-height:38px}.rule-form input[readonly]{background:#f5f8fb;color:#7c95a7}.rule-name-action{justify-content:space-between}.rule-fields{display:grid;grid-template-columns:1fr 1fr;gap:16px}.rule-options label{flex-direction:row;align-items:center}.rule-options select{width:auto}.rule-card header{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.rule-path{font-size:12px;line-height:2;overflow-wrap:anywhere;color:#7890a2}.rule-result{display:flex;flex-wrap:wrap;gap:12px;font-size:11px;color:#698aa0;padding:12px;background:#f4f8fb;border-radius:5px;margin-bottom:16px}.rule-result strong{color:#365b74}.rule-workspace details{font-size:11px;line-height:1.9;color:#829aab;margin:15px 0;overflow-wrap:anywhere}.rule-workspace summary{cursor:pointer}.rule-empty{text-align:center;padding:45px 10px;color:#8ca3b3;font-size:12px}.rule-preview{font-size:12px;color:#6b889d}.rule-group{border-top:1px solid #e5edf3;padding-top:15px;margin-top:15px}.rule-group li{line-height:2;overflow-wrap:anywhere}@media(max-width:700px){.rule-form input[readonly]{background:#f5f8fb;color:#7c95a7}.rule-name-action{justify-content:space-between}.rule-fields{grid-template-columns:1fr;gap:0}.rule-card header{flex-direction:column}.rule-workspace .btn{font-size:11px}}
</style>
