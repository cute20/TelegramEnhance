<script setup lang="ts">
import { computed, getCurrentInstance, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { formatMediaBytes } from '../library'
import { validUploadPreview, uploadTypes } from '../uploadPreview'
import { validRule, ruleTime, ruleStates, ruleArchiveDirectory, type RuleInput, type RuleView, type RulePreview } from '../rules'
import {uploadTaskLabels} from '../uploadTasks'
import CompactRecord from './CompactRecord.vue'
import DownloadRules from './DownloadRules.vue'
import RuleFormDialog from './RuleFormDialog.vue'
import type {TaskCategory} from '../taskCategories'
import type {UploadCreationSelection} from '../transferCreation'
import { preparationElapsed, rulePreparationId, validRulePreparation, type PreparationIdentity, type PreparationKind, type RulePreparation } from '../rulePreparation'
const props=defineProps<{authorized:boolean;available:boolean;accountId:string|null;active?:boolean;createOnly?:boolean;embedded?:boolean;category?:'download'|'upload';refreshVersion?:number;selection?:UploadCreationSelection;actionTarget?:string}>()
const emit=defineEmits<{accessError:[cause:ApiError];tasks:[category:TaskCategory];create:[direction:'download'|'upload'];saved:[direction:'download'|'upload'];close:[];locked:[value:boolean]}>()
const ruleCategories=[{id:'download',label:'下载规则'},{id:'upload',label:'上传规则'}] as const
const selectedCategory=ref<'download'|'upload'>(props.createOnly?'upload':props.category??'download')
function selectCategory(category:'download'|'upload'){if(busy.value||formOpen.value||activation.value)return;selectedCategory.value=category}
const items=ref<RuleView[]>([]),busy=ref(''),error=ref(''),notice=ref(''),editing=ref<RuleView|null>(null),formOpen=ref(false),uncertain=ref(false)
const preview=ref<RulePreview|null>(null)
const activation=ref<{rule:RuleView;preview:RulePreview}|null>(null),activationDialog=ref<HTMLDialogElement|null>(null)
type PreparationRequest=PreparationIdentity&{intent:'observe'|'preview'|'activation';rule:RuleView;generation:number;startedAt:number}
const preparationRequest=ref<PreparationRequest|null>(null),preparation=ref<RulePreparation|null>(null),preparationWaiting=ref(false),preparationPending=ref(false),preparationNow=ref(Date.now())
const pausingRule=ref('')
const pauseErrors=ref<Record<string,string>>({})
const preparationTime=computed(()=>preparationElapsed(preparation.value?.startedAt??preparationRequest.value?.startedAt??Date.now(),preparationNow.value))
let preparationTimer:ReturnType<typeof setTimeout>|undefined,preparationEpoch=0,preparationFlight=0,pauseSequence=0
const empty=():RuleInput=>({name:'',link:'',sourceDirectory:'',archiveDirectory:'',recursive:true,captionMode:'FILENAME',albumSize:10,window:{mode:'ALL_DAY',start:null,end:null,timezone:'Asia/Shanghai'},executionMode:'AUTO_UPLOAD'})
const form=reactive(empty())
const formId='upload-rule-form-'+(getCurrentInstance()?.uid??'standalone')
const nameAutomatic=ref(true),nameBusy=ref(false),nameError=ref('')
let nameGeneration=0,lastNameAttempt='',lastAutoName=''
let generation=0,listReadSequence=0,disposed=false,pendingOperation:string|null=null
const ready=()=>props.authorized&&props.available&&!!props.accountId
const pageActive=()=>props.active!==false
const enabledCount=computed(()=>items.value.filter(value=>value.rule.enabled).length)
const attentionCount=computed(()=>items.value.filter(value=>!!ruleAlert(value)).length)
function ruleDescription(value:RuleView){return `${value.mode==='AUTO_UPLOAD'?'自动上传':'仅观察'} · ${value.rule.input.window.mode==='ALL_DAY'?'全天':`${value.rule.input.window.start}—${value.rule.input.window.end}`} · 北京时间`}
function jobNeedsAttention(value:RuleView){return !!value.job&&['NEEDS_REVIEW','SENT_PENDING_ARCHIVE','FAILED'].includes(value.job.state)}
function ruleAlert(value:RuleView){return pauseErrors.value[value.rule.id]??(value.rule.observation?.outcome==='ERROR'?value.rule.observation.error:value.job?.error??(jobNeedsAttention(value)?'最近任务需要核对，请到任务中心处理。':null))}
function alive(g:number,account:string|null){return !disposed&&generation===g&&props.accountId===account&&ready()}
function fail(cause:unknown){error.value=cause instanceof Error?cause.message:'操作未完成';if(cause instanceof ApiError&&[401,403].includes(cause.status))emit('accessError',cause)}
function put(value:unknown){if(!validRule(value,props.accountId))throw new Error('规则回执无法确认，请刷新列表核对');items.value=[value,...items.value.filter(x=>x.rule.id!==value.rule.id)]}
async function refresh(){
  if(props.createOnly||!pageActive()||!ready()||busy.value||pausingRule.value||disposed)return;const g=generation,account=props.accountId,listing=++listReadSequence
  try{const result=await api<{items:RuleView[];mode:string}>('/rules');if(!alive(g,account)||listing!==listReadSequence||busy.value)return
    if(!['RULES','OBSERVE_ONLY'].includes(result.mode)||!Array.isArray(result.items)||result.items.length>20||!result.items.every(x=>validRule(x,account)))throw new Error('规则列表无法确认')
    items.value=result.items;pauseErrors.value={}
    const request=preparationRequest.value
    if(request&&!items.value.some(value=>value.rule.id===request.ruleId&&value.rule.version===request.version&&value.rule.revision===request.revision)){clearPreparation();error.value='规则已变化，旧准备结果已丢弃；请重新核对当前规则。'}
    if(preview.value&&!items.value.some(v=>v.rule.id===preview.value!.ruleId&&v.rule.revision===preview.value!.revision))preview.value=null
  }catch(cause){if(alive(g,account)&&listing===listReadSequence)fail(cause)}
}
watch(()=>form.sourceDirectory,source=>{form.archiveDirectory=ruleArchiveDirectory(source)},{flush:'sync'})
watch(()=>form.link,()=>{nameGeneration++;nameBusy.value=false;nameError.value='';lastNameAttempt='';if(nameAutomatic.value&&form.name===lastAutoName)form.name=''},{flush:'sync'})
function applySelection(){
  const selection=props.selection
  if(!props.createOnly||!selection||busy.value||uncertain.value)return
  form.link=selection.link;form.sourceDirectory=selection.sourceDirectory;form.archiveDirectory=ruleArchiveDirectory(selection.sourceDirectory)
  form.recursive=selection.recursive;form.captionMode=selection.captionMode;form.albumSize=selection.albumSize
}
function edit(value:RuleView|null){if(busy.value||pausingRule.value||uncertain.value||preparationRequest.value)return;generation++;nameGeneration++;nameBusy.value=false;nameError.value='';nameAutomatic.value=false;editing.value=value;Object.assign(form,empty(),JSON.parse(JSON.stringify(value?value.rule.input:empty())));form.executionMode=value?.mode??'AUTO_UPLOAD';form.albumSize=value?.rule.input.albumSize??10;form.archiveDirectory=ruleArchiveDirectory(form.sourceDirectory);nameAutomatic.value=!value;lastNameAttempt='';lastAutoName='';formOpen.value=true;preview.value=null;error.value='';notice.value='';if(!value)applySelection()}
function closeForm(){
  if(busy.value||props.createOnly&&uncertain.value)return
  generation++;nameGeneration++;nameBusy.value=false;nameError.value='';formOpen.value=false
  if(!uncertain.value){editing.value=null;pendingOperation=null;error.value=''}
  if(props.createOnly)emit('close')
}
function reopenForm(){if(uncertain.value&&!busy.value)formOpen.value=true}
async function resolveName(force=false){
  if(!pageActive()||!ready()||!formOpen.value||nameBusy.value||busy.value||uncertain.value||!form.link.trim())return false
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
  if(!Number.isInteger(input.albumSize)||input.albumSize<1||input.albumSize>10){error.value='请选择每组媒体数量（1 至 10 个）。';return}
  input.archiveDirectory=ruleArchiveDirectory(input.sourceDirectory)
  if(input.window.mode==='ALL_DAY'){input.window.start=null;input.window.end=null}
  const operation=old?null:crypto.randomUUID();pendingOperation=operation;busy.value='save';error.value='';notice.value='';preview.value=null
  let completed=false
  try{const result=await api<RuleView>(old?'/rules/'+old.rule.id:'/rules',{method:old?'PUT':'POST',body:old?{revision:old.rule.revision,input}:{operationId:operation,input}})
    if(!alive(g,account))return;if(!validRule(result,account)||result.rule.enabled||result.mode!==(input.executionMode??'OBSERVE_ONLY'))throw new Error('保存后的规则模式或暂停状态与本次设置不符，请先核对。');put(result);formOpen.value=false;editing.value=null;pendingOperation=null;notice.value=result.mode==='AUTO_UPLOAD'?'自动上传规则已保存并暂停；请预览核对后明确启用。':old?'规则修改已保存并暂停。':'规则已保存，默认暂停观察。';completed=true
  }catch(cause){if(alive(g,account)){fail(cause);if(!(cause instanceof ApiError)||cause.status>=500){uncertain.value=true;error.value+=' 请先核对保存结果，不会自动重复提交。'}}}finally{if(alive(g,account)){busy.value='';if(completed&&props.createOnly)emit('saved','upload')}}
}
async function lookup(){
  if(!ready()||busy.value||!uncertain.value||!pendingOperation&&!editing.value)return;const g=generation,account=props.accountId;busy.value='lookup'
  let completed=false
  try{const result=await api<RuleView|null>(pendingOperation?'/rules/by-operation/'+pendingOperation:'/rules/'+editing.value!.rule.id)
    if(!alive(g,account))return;if(!result){error.value='尚未查到这次保存，请稍后再次核对。';return}if(result.mode!==(form.executionMode??'OBSERVE_ONLY')){error.value='本次保存的规则模式尚未确认，请再次核对。';return}if(editing.value&&result.rule?.revision<=editing.value.rule.revision){error.value='尚未确认新版本已保存，请稍后再次核对。';return}put(result);uncertain.value=false;pendingOperation=null;formOpen.value=false;editing.value=null;notice.value='已读取当前保存结果，请核对保存内容。';error.value='';completed=true
  }catch(cause){if(alive(g,account))fail(cause)}finally{if(alive(g,account)){busy.value='';if(completed&&props.createOnly)emit('saved','upload')}}
}
function canPauseOtherRule(value:RuleView){
  const request=preparationRequest.value
  return !!request&&request.ruleId!==value.rule.id&&value.rule.enabled&&ready()&&!uncertain.value&&!pausingRule.value
    &&(!busy.value||busy.value==='preparation')&&items.value.some(item=>item.rule.id===value.rule.id&&item.rule.revision===value.rule.revision&&item.rule.enabled)
}
async function pauseOtherRule(value:RuleView){
  if(!canPauseOtherRule(value))return
  const g=generation,account=props.accountId,ticket=++pauseSequence;listReadSequence++;pausingRule.value=value.rule.id;delete pauseErrors.value[value.rule.id]
  const current=()=>ticket===pauseSequence&&alive(g,account)
  try{
    const result=await api<RuleView>('/rules/'+value.rule.id+'/enabled',{method:'POST',timeout:80000,body:{revision:value.rule.revision,enabled:false}})
    if(!current())return
    if(!validRule(result,account)||result.rule.id!==value.rule.id||result.rule.enabled||result.rule.version!==value.rule.version||result.rule.revision<=value.rule.revision)throw new Error('暂停规则的回执无法确认，请刷新规则核对。')
    put(result)
  }catch(cause){if(current()){pauseErrors.value[value.rule.id]=(cause instanceof Error?cause.message:'暂停结果未确认')+' 请刷新规则核对暂停结果，不会自动重试。';if(cause instanceof ApiError&&[401,403].includes(cause.status))emit('accessError',cause)}}
  finally{if(ticket===pauseSequence)pausingRule.value=''}
}
async function act(value:RuleView,kind:'enabled'|'observe'|'preview'){
  if(kind==='enabled'&&canPauseOtherRule(value)){await pauseOtherRule(value);return}
  if(!ready()||busy.value||pausingRule.value||uncertain.value||preparationRequest.value||value.scanning&&kind!=='enabled')return
  if(kind==='enabled'&&value.mode==='AUTO_UPLOAD'&&!value.rule.enabled){await prepareActivation(value);return}
  if(kind==='observe'||kind==='preview'){await startPreparation(value,kind,kind);return}
  const g=++generation,account=props.accountId;busy.value=kind;error.value='';notice.value='';preview.value=null
  try{const result=await api<unknown>('/rules/'+value.rule.id+'/enabled',{method:'POST',timeout:80000,body:{revision:value.rule.revision,enabled:!value.rule.enabled}})
    if(!alive(g,account))return
    put(result);notice.value='目录观察状态已更新。'
  }catch(cause){if(alive(g,account)){fail(cause);error.value+=' 不会自动重试，请刷新规则核对状态。'}}finally{if(alive(g,account))busy.value=''}
}
function closeActivation(){activationDialog.value?.close();activation.value=null}
async function prepareActivation(value:RuleView){
  return startPreparation(value,'preview','activation')
}
function stopPreparation(){clearTimeout(preparationTimer);preparationTimer=undefined;preparationEpoch++;preparationWaiting.value=false;if(busy.value==='preparation')busy.value=''}
function clearPreparation(){stopPreparation();preparationFlight++;preparationPending.value=false;preparationRequest.value=null;preparation.value=null}
function pausePreparation(){stopPreparation();notice.value='已停止前端等待，后台不会因此取消；可核对原操作结果。'}
function preparationCurrent(request:PreparationRequest,epoch:number){return preparationRequest.value?.operationId===request.operationId&&epoch===preparationEpoch&&alive(request.generation,request.accountId)}
function schedulePreparation(request:PreparationRequest,epoch:number){
  if(!preparationCurrent(request,epoch)||!preparationWaiting.value||!pageActive())return
  preparationNow.value=Date.now()
  if(preparationNow.value-request.startedAt>=13*60_000){stopPreparation();error.value='准备结果尚未确认，请核对原操作；没有重新提交。';return}
  preparationTimer=setTimeout(()=>{preparationTimer=undefined;void readPreparation()},2000)
}
async function applyPreparation(value:unknown,request:PreparationRequest,epoch:number){
  if(!preparationCurrent(request,epoch))return
  if(!validRulePreparation(value,request))throw new Error('后台准备回执无法确认，请核对原操作结果。')
  preparation.value=value;preparationNow.value=Date.now()
  if(value.state==='RUNNING')return
  if(value.state==='FAILED'){const message=value.error!;clearPreparation();error.value=message;return}
  const result=value.result
  if(request.kind==='observe'){
    if(!validRule(result,request.accountId)||result.rule.id!==request.ruleId||result.rule.version!==request.version||result.rule.revision!==request.revision)throw new Error('目录检测回执与本次规则不符，请核对原操作。')
    put(result);clearPreparation();notice.value='本次目录检测已完成，请查看结果。'
  }else{
    const p=result as RulePreview
    if(!p||p.ruleId!==request.ruleId||p.version!==request.version||p.revision!==request.revision||!validUploadPreview(p.preview,request.accountId)||p.preview.albumSize!==(request.rule.rule.input.albumSize??10))throw new Error('分组预览无法确认，请核对原操作。')
    if(request.intent==='activation'){activation.value={rule:request.rule,preview:p};clearPreparation();await nextTick();if(alive(request.generation,request.accountId)&&pageActive())activationDialog.value?.showModal()}
    else{preview.value=p;clearPreparation()}
  }
}
async function startPreparation(value:RuleView,kind:PreparationKind,intent:PreparationRequest['intent']){
  if(!ready()||busy.value||pausingRule.value||uncertain.value||preparationRequest.value)return
  let operationId:string
  try{operationId=rulePreparationId()}catch(cause){fail(cause);return}
  const request:PreparationRequest={operationId,ruleId:value.rule.id,accountId:props.accountId!,version:value.rule.version,revision:value.rule.revision,kind,intent,rule:JSON.parse(JSON.stringify(value)),generation:++generation,startedAt:Date.now()}
  preparationRequest.value=request;preparation.value=null;preparationNow.value=request.startedAt;preparationWaiting.value=true;busy.value='preparation';error.value='';notice.value='';preview.value=null
  const epoch=++preparationEpoch,flight=++preparationFlight;preparationPending.value=true
  try{const result=await api<unknown>('/rules/'+request.ruleId+'/preparations',{method:'POST',body:{operationId,revision:request.revision,kind}});await applyPreparation(result,request,epoch)}
  catch(cause){if(preparationCurrent(request,epoch)){fail(cause);if(cause instanceof ApiError&&cause.status>=400&&cause.status<500)clearPreparation();else{stopPreparation();error.value+=' 请核对原操作结果，不会自动重复提交。'}}}
  finally{if(flight===preparationFlight)preparationPending.value=false;schedulePreparation(request,epoch)}
}
async function readPreparation(){
  const request=preparationRequest.value
  if(!request||!ready()||!pageActive()||preparationPending.value)return
  clearTimeout(preparationTimer);preparationTimer=undefined
  const epoch=preparationEpoch,flight=++preparationFlight;preparationPending.value=true;error.value=''
  try{const result=await api<unknown>('/rules/'+request.ruleId+'/preparations/by-operation/'+request.operationId);if(!preparationCurrent(request,epoch))return
    if(result===null){stopPreparation();error.value='尚未查到这次后台准备，请稍后核对原操作；重启或过期后可离开并重新打开规则校验，没有重新提交。';return}await applyPreparation(result,request,epoch)}
  catch(cause){if(preparationCurrent(request,epoch)){fail(cause);if(cause instanceof ApiError&&cause.status===400)clearPreparation();else stopPreparation();error.value+=' 请核对原操作结果，不会自动重新准备。'}}
  finally{if(flight===preparationFlight)preparationPending.value=false;schedulePreparation(request,epoch)}
}
function resumePreparation(){if(!preparationRequest.value||!ready()||!pageActive()||preparationPending.value)return;preparationWaiting.value=true;busy.value='preparation';notice.value='';void readPreparation()}
async function confirmActivation(){
  const selected=activation.value;if(!selected||!ready()||busy.value||pausingRule.value)return;if(selected.preview.preview.expiresAt<=Date.now()){closeActivation();error.value='启用预览已过期，请重新预览后确认。';return}const g=++generation,account=props.accountId;busy.value='enable-auto';error.value=''
  try{const result=await api<RuleView>('/rules/'+selected.rule.rule.id+'/enabled',{method:'POST',body:{revision:selected.rule.rule.revision,enabled:true,previewId:selected.preview.preview.id}});if(!alive(g,account))return;put(result);closeActivation();notice.value='自动上传已明确启用，按保存的时段处理当前及今后稳定媒体。'}
  catch(cause){if(alive(g,account)){fail(cause);closeActivation();error.value+=' 请刷新规则核对是否已启用，不会自动重复提交。'}}finally{if(alive(g,account))busy.value=''}
}
watch([()=>props.authorized,()=>props.available,()=>props.accountId],(context,previous)=>{
  pauseSequence++;listReadSequence++;pausingRule.value=''
  const identityChanged=!previous||context[0]!==previous[0]||context[2]!==previous[2]
  const saving=busy.value==='save'||busy.value==='lookup'
  if(!identityChanged&&preparationRequest.value){stopPreparation();error.value='连接状态已变化，请核对原后台准备结果；不会重新提交。';if(ready())void refresh();return}
  if(!identityChanged&&saving)uncertain.value=true
  clearPreparation();generation++;closeActivation();nameGeneration++;nameBusy.value=false;lastNameAttempt='';preview.value=null;busy.value=''
  if(identityChanged){nameError.value='';nameAutomatic.value=true;lastAutoName='';items.value=[];pauseErrors.value={};error.value='';notice.value='';uncertain.value=false;pendingOperation=null;formOpen.value=false;editing.value=null;Object.assign(form,empty())}
  else if(saving){error.value='连接中断，已保留本次提交，请恢复连接后核对保存结果；不会自动重复提交。'}
  if(ready())void refresh()
},{immediate:true})
watch([busy,uncertain],()=>{if(props.createOnly)emit('locked',!!busy.value||uncertain.value)},{immediate:true,flush:'sync'})
watch(()=>props.category,category=>{if(!props.createOnly&&category&&!busy.value&&!formOpen.value&&!activation.value)selectedCategory.value=category})
watch(()=>props.refreshVersion,()=>{if(!props.createOnly){if(props.category&&!busy.value&&!formOpen.value&&!activation.value)selectedCategory.value=props.category;void refresh()}})
watch(()=>props.active,async active=>{
  if(active===false){clearTimeout(preparationTimer);preparationTimer=undefined;activationDialog.value?.close();return}
  if(!ready())return
  if(props.createOnly&&formOpen.value&&!form.name.trim()&&form.link.trim())void resolveName()
  // Visibility is not an account change: keep the original operation, revision and in-flight receipt.
  if(preparationRequest.value&&preparationWaiting.value){if(!preparationPending.value)void readPreparation()}
  else if(!uncertain.value&&!formOpen.value)void refresh()
  if(activation.value){await nextTick();if(pageActive()&&activation.value)activationDialog.value?.showModal()}
})
watch([()=>props.selection?.link,()=>props.selection?.sourceDirectory,()=>props.selection?.recursive,()=>props.selection?.captionMode,()=>props.selection?.albumSize],applySelection,{immediate:true,flush:'sync'})
onMounted(()=>{if(props.createOnly){edit(null);if(props.selection&&form.link.trim())void resolveName()}})
onBeforeUnmount(()=>{disposed=true;generation++;clearPreparation();closeActivation();nameGeneration++})
</script>
<template>
  <div class="rule-workspace">
    <div v-if="!createOnly" class="rule-category-switch" role="group" aria-label="规则类型"><button v-for="category in ruleCategories" :id="'rule-category-'+category.id" :key="category.id" type="button" :class="{selected:selectedCategory===category.id}" :aria-pressed="selectedCategory===category.id" :aria-controls="'rule-panel-'+category.id" :disabled="!!busy||formOpen||!!activation" @click="selectCategory(category.id)">{{category.label}}</button></div>
    <section v-if="!createOnly" id="rule-panel-download" v-show="selectedCategory==='download'" class="download-rule-panel" aria-labelledby="rule-category-download">
    <DownloadRules :authorized="authorized" :available="available" :account-id="accountId" :active="active!==false&&selectedCategory==='download'" :refresh-version="refreshVersion" @create="emit('create',$event)" @access-error="emit('accessError',$event)" @tasks="emit('tasks','download')" />
    </section>
    <section :id="createOnly?undefined:'rule-panel-upload'" v-show="createOnly||selectedCategory==='upload'" class="upload-rules" :aria-labelledby="createOnly?undefined:'rule-category-upload'">
    <header v-if="!createOnly" class="rule-toolbar rule-section-header"><span class="rule-count">{{items.length}} 条上传规则</span><button class="btn" :disabled="!!busy||!!pausingRule||!authorized||!available" @click="refresh()">刷新</button><button class="btn primary" :disabled="!!busy||!!pausingRule||uncertain||!!preparationRequest||!authorized||!available" @click="emit('create','upload')">新建上传规则</button></header>
    <div v-if="!createOnly" class="rule-stats" aria-label="上传规则概况"><div><span>全部规则</span><strong>{{items.length}}</strong></div><div><span>已启用</span><strong>{{enabledCount}}</strong></div><div :class="{'needs-attention':attentionCount>0}"><span>需要关注</span><strong>{{attentionCount}}</strong></div></div>
    <div v-if="!createOnly&&(!authorized||!available)" class="notice warning">请先连接 Telegram 并恢复本机服务。</div>

    <p v-if="!createOnly&&busy&&!formOpen&&!preparationRequest" class="notice" role="status">正在处理，请等待当前操作完成…</p><p v-if="!createOnly&&error&&!formOpen" class="notice danger" role="alert">{{error}}</p><p v-if="!createOnly&&notice" class="notice success" role="status">{{notice}}</p>
    <section v-if="preparationRequest" class="rule-preparation" aria-label="后台目录准备"><div><strong>{{preparationRequest.rule.rule.input.name}}</strong><p role="status">{{preparationWaiting?'正在校验目录与文件':'准备结果待核对'}} · 已用 {{preparationTime}}</p><small>大目录会在后台完整校验，完成前不会启用规则或发送文件。</small></div><button v-if="preparationWaiting" type="button" class="btn" @click="pausePreparation">停止等待</button><button v-else type="button" class="btn" :disabled="!authorized||!available||preparationPending" @click="resumePreparation">{{preparationPending?'等待本次查询结束…':'继续等待／核对结果'}}</button></section>
    <div v-if="!createOnly&&uncertain&&!formOpen" class="notice warning rule-uncertain"><p>保存结果尚未确认，请先核对，避免重复创建或修改规则。</p><div class="rule-actions"><button class="btn" type="button" :disabled="!!busy" @click="lookup">核对本次保存结果</button><button class="btn" type="button" :disabled="!!busy" @click="reopenForm">返回表单</button></div></div>
    <RuleFormDialog :open="formOpen&&active!==false" :embedded="embedded" :title="editing?'编辑上传规则':'新建上传规则'" :busy="!!busy||!!createOnly&&uncertain" @close="closeForm">
    <form v-if="formOpen" :id="formId" class="rule-form" @submit.prevent="save">
      <p v-if="!available" class="notice warning" role="status">连接暂时中断，已保留填写内容；恢复连接后可继续。</p>
      <p v-if="editing" class="rule-muted">修改后需重新启用，已创建的任务保持原参数。</p>
      <p v-if="busy" class="notice" role="status">正在处理，请等待当前操作完成…</p><p v-if="error" class="notice danger" role="alert">{{error}}</p>
      <fieldset :disabled="!!busy||uncertain||!authorized||!available">
        <p v-if="form.executionMode==='OBSERVE_ONLY'" class="rule-muted">这条旧规则仅检测目录，不上传文件。需要自动上传时，请新建上传规则。</p>
        <p v-if="form.executionMode==='AUTO_UPLOAD'&&(!createOnly||!selection)" class="notice warning">自动上传会持续处理此目录当前及今后新增的稳定媒体。保存后先暂停，必须预览并明确启用；修改配置后也需重新启用。</p>
        <label v-if="!createOnly||!selection">目标频道主帖链接<input v-model="form.link" class="input" type="url" required placeholder="https://t.me/…" @blur="resolveName()" /></label>
        <label>规则名称<input v-model="form.name" class="input" maxlength="80" placeholder="填写链接后自动获取主帖名称，可修改" @input="nameAutomatic=!form.name.trim()" /></label>
        <div class="rule-name-action"><small>{{nameBusy?'正在读取主帖名称…':'自动使用主帖名称，也可以手动修改。'}}</small><button class="btn" type="button" :disabled="nameBusy||!form.link.trim()" @click="resolveName(true)">{{nameBusy?'读取中…':'使用主帖名称'}}</button></div>
        <small v-if="nameError" role="alert">{{nameError}}</small>
        <div v-if="!createOnly||!selection" class="rule-fields"><label>待上传目录<input v-model="form.sourceDirectory" class="input" required placeholder="D:\待上传" /></label><label>已上传归档目录（自动生成）<input :value="form.archiveDirectory" class="input" readonly placeholder="随待上传目录自动生成" /></label></div>
        <small v-if="!createOnly||!selection">归档放在待上传目录旁，文件夹名加“-已上传”。实际归档时才创建目录；保存和检测不会创建。</small>
        <small v-if="editing&&editing.rule.input.archiveDirectory!==form.archiveDirectory">保存新版本后使用上方归档目录，旧版本的目录记录保留。</small>
        <div class="rule-options"><label v-if="!createOnly||!selection"><input v-model="form.recursive" type="checkbox" />包含子目录</label><label v-if="!createOnly||!selection">媒体说明<select v-model="form.captionMode" class="input"><option value="FILENAME">使用文件名</option><option value="EMPTY">留空</option></select></label><label v-if="!createOnly||!selection">每组媒体数量<select v-model="form.albumSize" class="input"><option v-for="size in 10" :key="size" :value="size">{{size === 1 ? '1 个（逐条发送）' : `${size} 个`}}</option></select></label><label>执行时段<select v-model="form.window.mode" class="input"><option value="ALL_DAY">全天</option><option value="WINDOW">指定时段</option></select></label></div>
        <small>{{form.albumSize === 1 ? '逐条发送（每组 1 个）' : `每组最多 ${form.albumSize} 个媒体`}}；不同类型分别分组，动图逐条发送。</small>
        <div v-if="form.window.mode==='WINDOW'" class="rule-options"><label>开始<input v-model="form.window.start" class="input" type="time" required /></label><label>结束<input v-model="form.window.end" class="input" type="time" required /></label><small>北京时间 · 支持跨午夜，例如22:00至08:00</small></div>
        <Teleport :to="actionTarget||'body'" :disabled="!actionTarget" defer><div class="rule-actions creation-primary-actions"><button class="btn primary" type="submit" :form="formId" :disabled="!!busy||uncertain||!authorized||!available||nameBusy||!form.archiveDirectory">{{busy==='save'?'保存中…':'保存规则'}}</button><button v-if="!actionTarget" class="btn" type="button" @click="closeForm">取消</button></div></Teleport>
      </fieldset>
      <Teleport :to="actionTarget||'body'" :disabled="!actionTarget" defer><div v-if="uncertain" class="rule-actions creation-primary-actions"><button class="btn" type="button" :disabled="!!busy||!authorized||!available" @click="lookup">核对本次保存结果</button><button v-if="!createOnly" class="btn" type="button" :disabled="!!busy" @click="closeForm">稍后核对</button></div></Teleport>
    </form>
    </RuleFormDialog>
    <p v-if="!createOnly&&!items.length&&!formOpen" class="rule-empty">还没有规则。添加待上传目录和目标主帖后，可以先核对目录内容及分组。</p>
    <CompactRecord v-for="value in createOnly?[]:items" :key="value.rule.id" class="rule-card" :title="value.rule.input.name" :description="ruleDescription(value)" :status="value.scanning?'正在检测…':value.mode==='AUTO_UPLOAD'?(value.state==='OBSERVING'?'自动上传已启用':value.state==='PAUSED'?'自动上传已暂停':ruleStates[value.state]):ruleStates[value.state]" :tone="ruleAlert(value)?'warning':value.rule.enabled?'active':'neutral'" :timestamp="value.rule.observation?.checkedAt" time-label="检测" :alert="ruleAlert(value)">
      <dl class="rule-facts">
        <dt>目标主帖</dt><dd><a :href="value.rule.input.link" target="_blank" rel="noreferrer">{{value.rule.input.link}} ↗</a></dd>
        <dt>待上传目录</dt><dd><code>{{value.rule.input.sourceDirectory}}</code></dd>
        <dt>归档目录</dt><dd><code>{{value.rule.input.archiveDirectory}}</code></dd>
        <dt>文件范围</dt><dd>{{value.rule.input.recursive?'包含子目录':'仅当前目录'}} · {{value.rule.input.captionMode==='FILENAME'?'文件名作为说明':'不添加说明'}} · {{value.rule.input.albumSize === 1 ? '逐条发送' : `每组最多 ${value.rule.input.albumSize??10} 个`}}</dd>
      </dl>
      <p v-if="value.state==='WAITING_WINDOW'" class="rule-muted">下次运行：{{ruleTime(value.nextAllowedAt)}}</p>
      <p v-if="value.rule.observation?.outcome==='ERROR'" class="notice warning">{{value.rule.observation.error}}</p>
      <div class="rule-detection" v-if="value.rule.observation?.scan"><span>最近检测 {{ruleTime(value.rule.observation.scanAt)}}</span><strong>{{value.rule.observation.scan.files.length}} 个候选媒体</strong></div><p v-else class="rule-muted">尚未检测目录。</p>
      <details v-if="value.rule.observation?.scan" class="rule-observation"><summary>检测明细与文件</summary><p>新增 {{value.rule.observation.added}} · 变化 {{value.rule.observation.changed}} · 本次未观察到 {{value.rule.observation.unobserved}} · 暂缓 {{value.rule.observation.scan.deferredFiles}} · 重复 {{value.rule.observation.scan.duplicateFiles}} · 跳过链接 {{value.rule.observation.scan.linkedEntries}}</p><ul><li v-for="file in value.rule.observation.scan.files.slice(0,50)" :key="file.relativePath">{{file.relativePath}} · {{formatMediaBytes(file.size)}}</li></ul><p v-if="value.rule.observation.scan.files.length>50">仅显示前50个文件</p><p v-if="value.rule.observation.upload">最近调度 {{ruleTime(value.rule.observation.upload.checkedAt)}}：{{value.rule.observation.upload.message}}</p></details>
      <div v-if="value.job" class="rule-latest"><span>最近任务</span><strong>{{uploadTaskLabels[value.job.state]??value.job.state}}</strong><span>{{value.job.total}} 个媒体 · 已归档 {{value.job.archived}}</span><button class="btn" @click="emit('tasks','upload')">查看任务</button><p v-if="value.job.manualTakeover">已转为手动处理</p><p v-else-if="value.job.ruleVersion!==value.rule.version">旧版本任务，请到任务中心处理。</p><p v-if="value.job.error" class="notice warning">{{value.job.error}}</p></div>
      <template #actions>
        <div class="rule-at-a-glance"><div><span>执行时段 · 北京时间</span><strong>{{value.rule.input.window.mode==='ALL_DAY'?'全天':value.rule.input.window.start+'—'+value.rule.input.window.end}}</strong><small v-if="value.state==='WAITING_WINDOW'">下次 {{ruleTime(value.nextAllowedAt)}}</small><small v-else>{{value.rule.enabled?'按规则持续观察':'已暂停，等待手动启用'}}</small></div><div><span>最近检测</span><strong>{{value.scanning?'正在校验目录':value.rule.observation?.scan?value.rule.observation.scan.files.length+' 个候选媒体':'尚未检测'}}</strong><small>{{value.rule.observation?.checkedAt?ruleTime(value.rule.observation.checkedAt):'检测不会发送文件'}}</small></div><div><span>最近任务</span><strong>{{value.job?(uploadTaskLabels[value.job.state]??value.job.state):'尚无任务'}}</strong><small>{{value.job?'正式回执 '+value.job.confirmed+' · 已归档 '+value.job.archived:'保存后先预览，再明确启用'}}</small></div></div>
        <button class="btn rule-toggle" :class="{'primary':!value.rule.enabled}" :disabled="!!pausingRule||uncertain||!authorized||!available||((!!busy||!!preparationRequest)&&!canPauseOtherRule(value))" @click="act(value,'enabled')">{{value.mode==='AUTO_UPLOAD'?(value.rule.enabled?'暂停自动上传':'启用自动上传'):(value.rule.enabled?'暂停观察':'启用观察')}}</button><button class="btn" :disabled="!!busy||!!pausingRule||uncertain||!!preparationRequest" @click="edit(value)">修改规则</button><button class="btn" :disabled="!!busy||!!pausingRule||uncertain||!!preparationRequest||value.scanning||!authorized||!available" @click="act(value,'preview')">预览分组</button><button class="btn" :disabled="!!busy||!!pausingRule||uncertain||!!preparationRequest||value.scanning||!authorized||!available" @click="act(value,'observe')">立即检测</button><button v-if="jobNeedsAttention(value)" class="btn" @click="emit('tasks','upload')">到任务中心处理</button>
      </template>
    </CompactRecord>
    <section v-if="!createOnly&&preview" class="settings-section rule-preview"><h2>分组预览 · 规则版本 {{preview.version}}</h2><p>{{preview.preview.channelTitle}} / {{preview.preview.postTitle}}</p><p>{{preview.preview.fileCount}} 个媒体 · {{preview.preview.groups.length}} 组 · {{formatMediaBytes(preview.preview.totalBytes)}}</p><p class="rule-muted">{{preview.preview.albumSize === 1 ? '逐条发送（每组 1 个）' : `每组最多 ${preview.preview.albumSize} 个媒体`}}；本次目标权限已核对，仍未发送。目录变化后请重新预览。</p><p v-for="warning in preview.preview.warnings.filter(w=>!w.startsWith('预览不发送或移动文件；'))" :key="warning" class="rule-muted">{{warning}}</p><div v-for="group in preview.preview.groups" :key="group.number" class="rule-group"><strong>第{{group.number}}组 · {{uploadTypes[group.mediaType]}} · {{group.files.length}}个</strong><ul><li v-for="file in group.files" :key="file.relativePath">{{file.relativePath}} · {{formatMediaBytes(file.size)}}</li></ul></div></section>
    </section>
  </div>
  <dialog v-if="!createOnly" ref="activationDialog" class="rule-activation" @cancel="closeActivation">
    <template v-if="activation"><h2>确认启用自动上传</h2><p>此规则将持续处理下方目录当前及今后新增的稳定媒体，发送到指定主帖评论区。正式回执齐全后归档。</p><strong>{{activation.preview.preview.channelTitle}} / {{activation.preview.preview.postTitle}}</strong><p><a :href="activation.rule.rule.input.link" target="_blank" rel="noreferrer">{{activation.rule.rule.input.link}} ↗</a></p><p class="rule-path">来源：{{activation.rule.rule.input.sourceDirectory}}<br/>归档：{{activation.rule.rule.input.archiveDirectory}}</p><p>{{activation.rule.rule.input.recursive?'包含子目录':'仅当前目录'}} · 媒体说明：{{activation.rule.rule.input.captionMode==='FILENAME'?'使用文件名':'留空'}} · {{activation.preview.preview.albumSize === 1 ? '逐条发送' : `每组最多 ${activation.preview.preview.albumSize} 个`}}</p><p>时段：{{activation.rule.rule.input.window.mode==='ALL_DAY'?'全天':`${activation.rule.rule.input.window.start}—${activation.rule.rule.input.window.end}`}} · 北京时间</p><p>当前预览 {{activation.preview.preview.fileCount}} 个媒体、{{activation.preview.preview.groups.length}} 组；实际按同类型每次一组处理。已有同目标上传任务的内容不重复创建或移动。</p><details><summary>查看当前分组与文件</summary><p v-for="group in activation.preview.preview.groups" :key="group.number">第{{group.number}}组 · {{uploadTypes[group.mediaType]}} · {{group.files.map(f=>f.relativePath).join('、')}}</p></details><p>窗口结束、暂停或改版后不启动新单元，已开始的单元继续核对。结果不明或归档失败时停止新任务，需在任务中心处理。</p><div class="rule-actions"><button class="btn" :disabled="!!busy" @click="closeActivation">暂不启用</button><button class="btn primary" :disabled="!!busy" @click="confirmActivation">确认启用自动上传</button></div></template>
  </dialog>
</template>
<style scoped>
.rule-preparation{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:12px 14px;margin:12px 0;border:1px solid #dce8f2;border-radius:7px;background:#f2f7fb;min-width:0}.rule-preparation>div{min-width:0}.rule-preparation strong{font-size:12px;color:#4c6c84;overflow-wrap:anywhere}.rule-preparation p{font-size:11px;line-height:1.8;margin:3px 0;color:#65869e}.rule-preparation small{font-size:10px;line-height:1.6}.rule-preparation .btn{font-size:10px;flex-shrink:0}@media(max-width:480px){.rule-preparation{align-items:stretch;flex-direction:column}.rule-preparation .btn{align-self:flex-end}}
.rule-category-switch{display:flex;flex-wrap:wrap;gap:8px;border-bottom:1px solid #dfe8ef;padding-bottom:15px;margin-bottom:23px}.rule-category-switch button{border:1px solid #d8e3ec;border-radius:6px;background:#fff;color:#7893a8;padding:8px 14px;font-size:12px;line-height:1.5}.rule-category-switch button.selected{background:#263f54;border-color:#263f54;color:#fff}.rule-category-switch button:hover:not(.selected):not(:disabled){background:#f0f6fa}.download-rule-panel{min-width:0}.download-rule-panel :deep(.download-rules){margin:0;padding:0;border:0}@media(max-width:480px){.rule-category-switch{gap:6px;margin-bottom:18px}.rule-category-switch button{font-size:10px;padding:7px 10px}}
.upload-rules{min-width:0}.rule-section-header{margin:0 0 12px;align-items:flex-start}.rule-section-header>div{flex:1;min-width:200px}.rule-section-header h2{font-size:17px;margin-bottom:6px}.rule-section-header p,.rule-count{margin:0;color:#7894a8;font-size:12px;line-height:1.8}.rule-count{margin:12px 0}.rule-form{min-width:0;margin:0!important;padding:0;border:0;box-shadow:none}.rule-form .notice,.upload-rules>.notice{font-size:12px;line-height:1.8}.rule-form small{display:block;color:#869eaf;font-size:11px;line-height:1.9;overflow-wrap:anywhere}.rule-uncertain p{margin:0 0 10px}.rule-form .rule-actions{margin-top:18px}.rule-form .rule-name-action small{flex:1;min-width:160px}.rule-form .rule-fields>*{min-width:0}.rule-form .rule-options{align-items:flex-start}.rule-form .rule-options label{max-width:100%}.rule-form .rule-options .input{min-width:0}.rule-form .rule-muted{font-size:12px;color:#869eaf;line-height:1.8}@media(max-width:600px){.rule-section-header>div{flex-basis:100%}.rule-section-header>.btn{flex:1}.rule-intro{flex-wrap:wrap}.rule-intro details{max-width:100%}}
.rule-intro{display:flex;align-items:flex-start;gap:16px;justify-content:space-between;font-size:11px;line-height:1.9;color:#7892a7}.rule-intro details{flex:0 0 auto;max-width:500px;margin:0}.rule-intro summary{white-space:nowrap}.rule-intro p{font-size:11px;white-space:normal}
.rule-job{border:1px solid #dfebf3;border-radius:6px;padding:12px;margin:15px 0;font-size:11px;color:#7190a6;line-height:1.8}.rule-activation{border:1px solid #dce7ef;border-radius:10px;max-width:660px;width:calc(100% - 36px);max-height:85vh;overflow:auto;padding:24px;color:#55788f;font-size:12px;line-height:1.9}.rule-activation::backdrop{background:#12283d88}.rule-activation h2{font-size:17px}.rule-activation strong{overflow-wrap:anywhere}
.rule-workspace{max-width:1100px}.rule-workspace>.notice{font-size:12px;line-height:1.8}.rule-workspace>.notice p{margin:5px 0}.rule-workspace small,.rule-muted{color:#869eaf;font-size:11px;line-height:1.9}.rule-toolbar,.rule-actions,.rule-options,.rule-name-action{display:flex;align-items:center;gap:10px;flex-wrap:wrap}.rule-toolbar{margin:14px 0;font-size:12px;color:#7894a8}.rule-toolbar>span{margin-right:auto}.rule-form,.rule-preview{margin:18px 0}.rule-card{margin:8px 0}.rule-workspace h2{font-size:15px;margin:0 0 10px}.rule-form fieldset{border:0;padding:0;margin:0}.rule-form label{display:flex;flex-direction:column;gap:8px;font-size:12px;margin:16px 0;color:#638096}.rule-form .input{width:100%;min-height:38px}.rule-form input[readonly]{background:#f5f8fb;color:#7c95a7}.rule-name-action{justify-content:space-between}.rule-fields{display:grid;grid-template-columns:1fr 1fr;gap:16px}.rule-options label{flex-direction:row;align-items:center}.rule-options select{width:auto}.rule-card header{display:flex;justify-content:space-between;gap:16px;align-items:flex-start}.rule-path{font-size:12px;line-height:2;overflow-wrap:anywhere;color:#7890a2}.rule-result{display:flex;flex-wrap:wrap;gap:12px;font-size:11px;color:#698aa0;padding:12px;background:#f4f8fb;border-radius:5px;margin-bottom:16px}.rule-result strong{color:#365b74}.rule-workspace details{font-size:11px;line-height:1.9;color:#829aab;margin:15px 0;overflow-wrap:anywhere}.rule-workspace summary{cursor:pointer}.rule-empty{text-align:center;padding:45px 10px;color:#8ca3b3;font-size:12px}.rule-preview{font-size:12px;color:#6b889d}.rule-group{border-top:1px solid #e5edf3;padding-top:15px;margin-top:15px}.rule-group li{line-height:2;overflow-wrap:anywhere}@media(max-width:700px){.rule-form input[readonly]{background:#f5f8fb;color:#7c95a7}.rule-name-action{justify-content:space-between}.rule-fields{grid-template-columns:1fr;gap:0}.rule-card header{flex-direction:column}.rule-workspace .btn{font-size:11px}}
.rule-stats{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;margin:0 0 22px}.rule-stats>div{display:flex;align-items:center;justify-content:space-between;gap:12px;border:1px solid var(--line,#e1e9f1);border-radius:11px;padding:16px 18px;background:#fff}.rule-stats span{font-size:12px;color:var(--muted,#6c8297)}.rule-stats strong{font-size:22px;line-height:1.3;font-weight:650;color:var(--ink,#243f55)}.rule-stats .needs-attention strong{color:#a47732}
.rule-at-a-glance{flex-basis:100%;display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:14px;padding:14px 0 16px;margin-bottom:4px;border-bottom:1px solid var(--line,#e5edf4)}.rule-at-a-glance>div{min-width:0;display:flex;flex-direction:column;gap:5px}.rule-at-a-glance span{font-size:12px;color:#71869a;line-height:1.7}.rule-at-a-glance strong{font-size:13px;font-weight:600;line-height:1.7;color:#345773;overflow-wrap:anywhere}.rule-at-a-glance small{font-size:12px;line-height:1.7;color:#6c859a;overflow-wrap:anywhere}
:deep(.compact-record){border-radius:11px;margin-bottom:15px}:deep(.record-summary){padding:16px 18px;gap:8px 16px}:deep(.record-title){font-size:14px;line-height:1.7}:deep(.record-meta),:deep(.record-state),:deep(.record-hint),:deep(.record-alert){font-size:12px;line-height:1.7}:deep(.record-actions){padding:0 18px 16px;gap:8px}:deep(.record-actions .btn){font-size:12px;min-height:34px;padding:6px 11px}
@media(max-width:680px){.rule-stats{gap:9px}.rule-stats>div{padding:13px 12px;align-items:flex-start;flex-direction:column;gap:6px}.rule-stats strong{font-size:20px}.rule-at-a-glance{grid-template-columns:minmax(0,1fr);gap:12px;padding-top:12px}.rule-at-a-glance>div{display:grid;grid-template-columns:120px minmax(0,1fr);gap:2px 9px}.rule-at-a-glance small{grid-column:2}.rule-at-a-glance span{grid-row:span 2}:deep(.record-summary){padding:14px}:deep(.record-actions){padding:0 14px 14px}}
.rule-workspace{max-width:none}.rule-category-switch{gap:6px;padding:4px;background:#eaf0f6;border:0;border-radius:9px;width:fit-content;margin-bottom:22px}.rule-category-switch button{font-size:13px;padding:9px 18px;border:0;background:transparent;color:#5f7a91}.rule-category-switch button.selected{background:#fff;color:#2678ba;box-shadow:0 1px 5px #1a3d5b12}.rule-toolbar .btn,.rule-actions .btn{font-size:12px;min-height:34px}.rule-count{font-size:13px;color:#657f94}.rule-form label{font-size:13px;color:#4c6c84}.rule-form small,.rule-form .rule-muted,.rule-form .notice,.rule-muted{font-size:12px;color:#6c8499;line-height:1.8}.rule-form .input{font-size:14px;min-height:40px}.rule-workspace details,.rule-path,.rule-preview,.rule-workspace small{font-size:12px;line-height:1.8}.rule-preparation strong{font-size:14px;color:#345b7a}.rule-preparation p,.rule-preparation small,.rule-preparation .btn{font-size:12px}.rule-empty{border:1px dashed #cedde9;border-radius:11px;background:#fff;font-size:13px;padding:46px 20px;line-height:1.8;color:#698298}
</style>
