<script setup lang="ts">
import { computed, getCurrentInstance, onMounted, ref, watch } from 'vue'
import type { ApiError } from '../api'
import type { TaskCategory } from '../taskCategories'
import RuleFormDialog from './RuleFormDialog.vue'
import NewTransfer from './NewTransfer.vue'
import DownloadRules from './DownloadRules.vue'
import RuleWorkspace from './RuleWorkspace.vue'
import DownloadCreationFields from './DownloadCreationFields.vue'
import UploadCreationFields from './UploadCreationFields.vue'
import BotDownload from './BotDownload.vue'
import AppIcon from './AppIcon.vue'
import { emptyDownloadSelection, emptyUploadSelection, downloadDatesError, type CreationStage } from '../transferCreation'

const props=defineProps<{authorized:boolean;available:boolean;accountId:string|null;initialDirection?:'download'|'upload';initialMode?:'ONCE'|'RULE';defaultDownloadDirectory?:string}>()
const emit=defineEmits<{close:[];settings:[];tasks:[category:TaskCategory];saved:[direction:'download'|'upload'];accessError:[cause:ApiError]}>()
const direction=ref<'download'|'upload'>(props.initialDirection??'download'),mode=ref<'ONCE'|'RULE'>(props.initialMode??'ONCE'),locked=ref(false)
const downloadSelection=ref(emptyDownloadSelection(props.defaultDownloadDirectory)),uploadSelection=ref(emptyUploadSelection()),fieldsLocked=ref(false)
const downloadSource=ref<'POST'|'BOT'>('POST'),reviewOpen=ref(false),reviewError=ref('')
const creationStage=ref<CreationStage>('preview'),footerReady=ref(false)
const actionMountId='transfer-actions-'+(getCurrentInstance()?.uid??'standalone')
const actionTarget=computed(()=>footerReady.value&&reviewOpen.value?'#'+actionMountId:undefined)
const contextKey=computed(()=>`${props.authorized}:${props.accountId??''}`)
const steps=computed(()=>mode.value==='RULE'?['填写参数','核对规则','保存为暂停']:downloadSource.value==='BOT'&&direction.value==='download'?['选择来源','浏览与选择','确认下载']:['填写参数','核对预览','确认创建'])
const currentStep=computed(()=>!reviewOpen.value||creationStage.value==='parameters'?0:['confirm','complete'].includes(creationStage.value)?2:1)
const ready=computed(()=>props.authorized&&props.available&&!!props.accountId)
const dateFiltering=computed(()=>direction.value==='download'&&downloadSource.value==='POST'&&mode.value==='ONCE'&&downloadSelection.value.entry!=='POST')
const datesError=computed(()=>dateFiltering.value?downloadDatesError(downloadSelection.value.startDate,downloadSelection.value.endDate):'')
const canReview=computed(()=>{
  if(!ready.value||locked.value)return false
  if(direction.value==='upload')return !!uploadSelection.value.link.trim()&&!!uploadSelection.value.sourceDirectory.trim()&&Number.isInteger(uploadSelection.value.albumSize)&&uploadSelection.value.albumSize>=1&&uploadSelection.value.albumSize<=10
  if(downloadSource.value==='BOT')return true
  if(datesError.value)return false
  return !!downloadSelection.value.source.trim()&&!!downloadSelection.value.targetDirectory.trim()&&(!!downloadSelection.value.types.length||downloadSelection.value.includeMain)
})
const summary=computed(()=>direction.value==='upload'
  ? {title:'上传到 Telegram',source:uploadSelection.value.sourceDirectory,target:uploadSelection.value.link}
  : downloadSource.value==='BOT'?{title:'从机器人下载',source:'浏览当前页或组，选择需要的媒体',target:'只创建本次选中的下载内容'}
  : {title:'下载到本机',source:downloadSelection.value.source,target:downloadSelection.value.targetDirectory})
function resetReview(){reviewOpen.value=false;reviewError.value='';creationStage.value='preview'}
watch([()=>props.authorized,()=>props.accountId],()=>{locked.value=false;fieldsLocked.value=false;downloadSelection.value=emptyDownloadSelection(props.defaultDownloadDirectory);uploadSelection.value=emptyUploadSelection();resetReview()})
watch(()=>props.defaultDownloadDirectory,value=>{if(value?.trim()&&!downloadSelection.value.targetDirectory.trim()&&!locked.value&&!fieldsLocked.value)downloadSelection.value={...downloadSelection.value,targetDirectory:value.trim()}})
onMounted(()=>{footerReady.value=true})
function selectDirection(value:'download'|'upload'){
  if(locked.value||value===direction.value||!['download','upload'].includes(value))return
  fieldsLocked.value=false;resetReview();direction.value=value;if(value==='download'&&downloadSource.value==='BOT')mode.value='ONCE'
}
function selectMode(value:'ONCE'|'RULE'){
  if(locked.value||value===mode.value||!['ONCE','RULE'].includes(value)||value==='RULE'&&direction.value==='download'&&downloadSource.value==='BOT')return
  fieldsLocked.value=false;resetReview();mode.value=value
}
function selectDownloadSource(value:'POST'|'BOT'){
  if(locked.value||!['POST','BOT'].includes(value)||value===downloadSource.value)return
  fieldsLocked.value=false;resetReview();downloadSource.value=value;if(value==='BOT')mode.value='ONCE'
}
function startReview(){if(!canReview.value){reviewError.value=datesError.value||'请先填写来源和目录，并选择要处理的媒体。';return}reviewError.value='';reviewOpen.value=true}
function backToParameters(){if(!locked.value&&!fieldsLocked.value)reviewOpen.value=false}
function onLocked(value:boolean){locked.value=value;if(value&&mode.value==='RULE')creationStage.value='confirm'}
function close(){if(!locked.value)emit('close')}
function settings(){if(!locked.value)emit('settings')}
function saved(value:'download'|'upload'){if(mode.value==='RULE'&&value===direction.value){locked.value=false;creationStage.value='complete';emit('saved',value)}}
</script>
<template>
  <RuleFormDialog :open="true" title="新建传输" :stable-size="true" :scroll-key="reviewOpen?'review':'parameters'" :busy="locked" @close="close">
    <ol class="creation-steps" aria-label="创建进度"><li v-for="(step,index) in steps" :key="step" :class="{current:currentStep===index,complete:currentStep>index}" :aria-current="currentStep===index?'step':undefined"><span>{{currentStep>index?'✓':index+1}}</span>{{step}}</li></ol>
    <section v-show="!reviewOpen" class="creation-parameters" aria-label="传输参数">
      <h3>这次想做什么？</h3>
      <div class="creation-purpose" role="group" aria-label="传输方向">
        <button type="button" :class="{selected:direction==='download'}" :aria-pressed="direction==='download'" :disabled="locked||fieldsLocked" @click="selectDirection('download')"><span class="purpose-icon"><AppIcon name="arrow" /></span><span><strong>下载到本机</strong><small>从频道、主帖或机器人保存媒体</small></span><AppIcon v-if="direction==='download'" name="check" /></button>
        <button type="button" :class="{selected:direction==='upload'}" :aria-pressed="direction==='upload'" :disabled="locked||fieldsLocked" @click="selectDirection('upload')"><span class="purpose-icon upload"><AppIcon name="telegram" /></span><span><strong>上传到 Telegram</strong><small>从本机目录发送到主帖评论区</small></span><AppIcon v-if="direction==='upload'" name="check" /></button>
      </div>
      <div class="creation-setting-row"><div><h3>执行方式</h3><p>{{mode==='RULE'?'保存为暂停规则，核对后再明确启用。':'本次准备、核对后只创建一次任务。'}}</p></div><div class="creation-switch" role="group" aria-label="执行方式"><button type="button" :class="{selected:mode==='ONCE'}" :aria-pressed="mode==='ONCE'" :disabled="locked||fieldsLocked" @click="selectMode('ONCE')">一次性任务</button><button type="button" :class="{selected:mode==='RULE'}" :aria-pressed="mode==='RULE'" :disabled="locked||fieldsLocked||direction==='download'&&downloadSource==='BOT'" @click="selectMode('RULE')">自动规则</button></div></div>
      <div v-if="direction==='download'" class="creation-source-setting"><h3>下载来源</h3><div class="creation-switch" role="group" aria-label="下载来源"><button type="button" :class="{selected:downloadSource==='POST'}" :aria-pressed="downloadSource==='POST'" :disabled="locked||fieldsLocked" @click="selectDownloadSource('POST')">频道／主帖</button><button type="button" :class="{selected:downloadSource==='BOT'}" :aria-pressed="downloadSource==='BOT'" :disabled="locked||fieldsLocked" @click="selectDownloadSource('BOT')">机器人下载</button></div></div>
      <DownloadCreationFields v-if="direction==='download'&&downloadSource==='POST'" v-model="downloadSelection" :date-filtering="mode==='ONCE'" :authorized="authorized" :available="available" :account-id="accountId" :disabled="locked||fieldsLocked" @access-error="emit('accessError',$event)" />
      <UploadCreationFields v-else-if="direction==='upload'" v-model="uploadSelection" :disabled="locked||fieldsLocked||!authorized||!available" />
      <p v-else class="creation-help">下一步从机器人当前页或组开始自动采集，保留可用文件引用并继续翻页。采集完成或停止后，勾选媒体创建后台下载任务。</p>
      <div v-if="!authorized" class="notice warning" role="status"><span>请先连接 Telegram，再预览并创建传输。</span><button type="button" class="btn" :disabled="locked" @click="settings">前往设置</button></div><p v-else-if="!available" class="notice warning" role="status">本机连接暂时中断，已保留填写内容。</p>
      <p v-if="reviewError" class="notice warning" role="alert">{{reviewError}}</p>
    </section>
    <section v-show="reviewOpen" class="creation-review" aria-label="预览与确认">
      <div class="creation-context"><span class="creation-context-icon"><AppIcon :name="direction==='upload'?'telegram':'tasks'" /></span><div><strong>{{summary.title}}<small>{{mode==='RULE'?'自动规则':'一次性任务'}}</small></strong><p :title="summary.source">{{summary.source}}</p><p :title="summary.target">{{summary.target}}</p></div></div>
      <p class="creation-review-hint">{{mode==='RULE'?'补充规则名称与执行时段，保存后保持暂停。启用在规则中心单独确认。':direction==='upload'?'先核对目录和分组。保存上传任务后，还需要在任务中心明确开始发送。':'先读取并选择需要的媒体。只有明确创建后，才会开始下载。'}}</p>
      <BotDownload v-if="direction==='download'&&downloadSource==='BOT'" :key="contextKey+':bot'" :action-target="actionTarget" :active="reviewOpen" :default-download-directory="defaultDownloadDirectory" :authorized="authorized" :available="available" :account-id="accountId" @stage="creationStage=$event" @locked="onLocked" @tasks="emit('tasks',$event)" @access-error="emit('accessError',$event)" />
      <NewTransfer v-else-if="mode==='ONCE'" :key="contextKey+':'+direction" :embedded="true" :selection="downloadSelection" :upload-selection="uploadSelection" :action-target="actionTarget" :active="reviewOpen" :initial-direction="direction" :authorized="authorized" :available="available" :account-id="accountId" @stage="creationStage=$event" @fields-locked="fieldsLocked=$event" @locked="onLocked" @close="close" @settings="settings" @tasks="emit('tasks',$event)" @access-error="emit('accessError',$event)" />
      <DownloadRules v-else-if="direction==='download'" :key="contextKey" :selection="downloadSelection" :action-target="actionTarget" :active="reviewOpen" :create-only="true" :embedded="true" :authorized="authorized" :available="available" :account-id="accountId" @locked="onLocked" @saved="saved" @close="close" @access-error="emit('accessError',$event)" />
      <RuleWorkspace v-else :key="contextKey" :selection="uploadSelection" :action-target="actionTarget" :active="reviewOpen" :create-only="true" :embedded="true" :authorized="authorized" :available="available" :account-id="accountId" @locked="onLocked" @saved="saved" @close="close" @access-error="emit('accessError',$event)" />
    </section>
    <template #footer>
      <div :id="actionMountId" v-show="reviewOpen" class="creation-action-mount" />
      <div class="creation-footer"><span>{{locked?'正在提交或核对结果，请保留当前操作。':reviewOpen?creationStage==='complete'?'任务已保存，可前往任务中心查看。':'预览不开始传输；请核对内容后确认。':'填写参数后，再进入预览与确认。'}}</span><div><button v-if="reviewOpen" class="btn" type="button" :disabled="locked||fieldsLocked" @click="backToParameters">修改参数</button><button class="btn" type="button" :disabled="locked" @click="close">{{reviewOpen?'关闭':'取消'}}</button><button v-if="!reviewOpen" class="btn primary" type="button" :disabled="!canReview" @click="startReview">下一步：{{mode==='RULE'?'核对规则':downloadSource==='BOT'&&direction==='download'?'浏览机器人':'预览内容'}}<AppIcon name="arrow" /></button></div></div>
    </template>
  </RuleFormDialog>
</template>
<style scoped>
.creation-steps{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));list-style:none;padding:0 0 20px;margin:0 0 24px;border-bottom:1px solid var(--line,#e1e9f1);gap:15px}.creation-steps li{display:flex;align-items:center;gap:9px;font-size:12px;color:#7c91a3;line-height:1.6}.creation-steps li>span{display:grid;place-items:center;width:26px;height:26px;flex-shrink:0;border-radius:50%;background:#eef2f7;color:#7e94a6;font-size:12px}.creation-steps li.current{color:#256da8;font-weight:600}.creation-steps li.current>span{background:#277fce;color:#fff;box-shadow:0 0 0 4px #277fce10}.creation-steps li.complete{color:#4a8971}.creation-steps li.complete>span{background:#e8f4ed;color:#347d5d}
.creation-parameters h3,.creation-source-setting h3{font-size:13px;font-weight:600;line-height:1.6;color:var(--ink,#294b65);margin:0 0 12px}.creation-purpose{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px;margin-bottom:25px}.creation-purpose>button{display:flex;align-items:center;gap:12px;min-width:0;border:1px solid var(--line,#dce6ef);border-radius:11px;padding:17px 15px;background:#fff;text-align:left;color:#6e879c;cursor:pointer}.creation-purpose>button.selected{border-color:#9dc7ec;background:#f1f7ff;box-shadow:0 0 0 2px #277fce07}.creation-purpose>button>span:not(.purpose-icon){min-width:0;flex:1}.creation-purpose strong{display:block;font-size:14px;font-weight:600;color:#345b79;line-height:1.7}.creation-purpose small{display:block;font-size:12px;color:#6a859a;line-height:1.7;margin-top:3px}.creation-purpose>button>.icon{width:17px;height:17px;color:#277fce;flex-shrink:0}.purpose-icon{display:grid;place-items:center;flex-shrink:0;width:39px;height:39px;border-radius:10px;background:#e1effd;color:#277fce}.purpose-icon>.icon{width:21px;height:21px;transform:rotate(90deg)}.purpose-icon.upload{background:#e8f2ed;color:#4d8b71}.purpose-icon.upload>.icon{transform:none}
.creation-setting-row{display:flex;align-items:center;justify-content:space-between;gap:20px;padding:18px 0;border-top:1px solid var(--line,#e5ecf3);border-bottom:1px solid var(--line,#e5ecf3);margin-bottom:20px}.creation-setting-row h3{margin-bottom:5px}.creation-setting-row p,.creation-help{font-size:12px;color:#6d879b;line-height:1.8;margin:0}.creation-switch{display:flex;flex-wrap:wrap;gap:6px;flex-shrink:0}.creation-switch button{border:1px solid #d9e4ee;border-radius:7px;padding:8px 13px;background:#fff;color:#6b879d;font-size:12px;line-height:1.6;cursor:pointer}.creation-switch button.selected{background:#eaf3ff;border-color:#b0cfe9;color:#246ca9}.creation-switch button:disabled{opacity:.55;cursor:default}.creation-switch button:focus-visible,.creation-purpose button:focus-visible{outline:2px solid #2986cc;outline-offset:2px}.creation-source-setting{margin:0 0 18px}.creation-source-setting h3{margin-bottom:9px}.creation-help{border:1px solid #dfebf5;border-radius:8px;background:#f4f8fd;padding:14px;margin:18px 0}.creation-context{display:flex;align-items:flex-start;gap:12px;padding:15px 17px;border:1px solid #dce8f3;background:#f3f8fd;border-radius:10px;margin-bottom:14px}.creation-context-icon{display:grid;place-items:center;flex-shrink:0;background:#e3eef8;color:#5786aa;border-radius:9px;width:37px;height:37px}.creation-context-icon>.icon{width:19px;height:19px}.creation-context>div{min-width:0;flex:1}.creation-context strong{display:flex;align-items:center;gap:10px;flex-wrap:wrap;font-size:14px;color:#345a77;line-height:1.6}.creation-context strong small{font-size:11px;font-weight:400;background:#e2edf7;color:#61819a;padding:2px 6px;border-radius:4px}.creation-context p{font-size:12px;line-height:1.8;color:#69849a;margin:4px 0 0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.creation-review-hint{font-size:12px;line-height:1.8;color:#6b859a;margin:0 0 18px}.creation-action-mount:empty{display:none}.creation-action-mount:not(:empty){padding-bottom:14px;margin-bottom:13px;border-bottom:1px solid var(--line,#e5ecf3)}.creation-footer{display:flex;align-items:center;justify-content:space-between;gap:14px;flex-wrap:wrap}.creation-footer>span{font-size:12px;line-height:1.7;color:#70889b;flex:1;min-width:150px}.creation-footer>div{display:flex;align-items:center;justify-content:flex-end;gap:8px;flex-wrap:wrap;margin-left:auto}.creation-footer .btn{font-size:13px;min-height:38px}.creation-footer .btn>.icon{width:16px;height:16px}
@media(max-width:650px){.creation-purpose{grid-template-columns:minmax(0,1fr);gap:10px}.creation-purpose>button{padding:13px}.creation-setting-row{align-items:flex-start;flex-direction:column;gap:13px}.creation-steps{gap:7px;margin-bottom:20px;padding-bottom:17px}.creation-steps li{font-size:11px;gap:6px}.creation-steps li>span{width:22px;height:22px;font-size:11px}.creation-footer>span{flex-basis:100%;font-size:12px}.creation-footer>div{width:100%}.creation-context p{white-space:normal;overflow-wrap:anywhere}}
</style>
