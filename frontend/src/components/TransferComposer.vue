<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import type {ApiError} from '../api'
import type {TaskCategory} from '../taskCategories'
import RuleFormDialog from './RuleFormDialog.vue'
import NewTransfer from './NewTransfer.vue'
import DownloadRules from './DownloadRules.vue'
import RuleWorkspace from './RuleWorkspace.vue'
import DownloadCreationFields from './DownloadCreationFields.vue'
import UploadCreationFields from './UploadCreationFields.vue'
import {emptyDownloadSelection,emptyUploadSelection} from '../transferCreation'
const props=defineProps<{authorized:boolean;available:boolean;accountId:string|null;initialDirection?:'download'|'upload';initialMode?:'ONCE'|'RULE'}>()
const emit=defineEmits<{close:[];settings:[];tasks:[category:TaskCategory];saved:[direction:'download'|'upload'];accessError:[cause:ApiError]}>()
const direction=ref<'download'|'upload'>(props.initialDirection??'download'),mode=ref<'ONCE'|'RULE'>(props.initialMode??'ONCE'),locked=ref(false)
const downloadSelection=ref(emptyDownloadSelection()),uploadSelection=ref(emptyUploadSelection()),fieldsLocked=ref(false)
const contextKey=computed(()=>`${props.authorized}:${props.accountId??''}`)
watch([()=>props.authorized,()=>props.accountId],()=>{locked.value=false;fieldsLocked.value=false;downloadSelection.value=emptyDownloadSelection();uploadSelection.value=emptyUploadSelection()})
function selectDirection(value:'download'|'upload'){if(!locked.value&&value!==direction.value&&['download','upload'].includes(value)){fieldsLocked.value=false;direction.value=value}}
function selectMode(value:'ONCE'|'RULE'){if(!locked.value&&value!==mode.value&&['ONCE','RULE'].includes(value)){fieldsLocked.value=false;mode.value=value}}
function close(){if(!locked.value)emit('close')}
function settings(){if(!locked.value)emit('settings')}
function saved(value:'download'|'upload'){if(mode.value==='RULE'&&value===direction.value){locked.value=false;emit('saved',value)}}
</script>
<template>
  <RuleFormDialog :open="true" title="新建传输" :stable-size="true" :busy="locked" @close="close">
    <div class="creation-options">
      <div class="creation-switch" role="group" aria-label="传输方向"><button type="button" :class="{selected:direction==='download'}" :aria-pressed="direction==='download'" :disabled="locked" @click="selectDirection('download')">下载</button><button type="button" :class="{selected:direction==='upload'}" :aria-pressed="direction==='upload'" :disabled="locked" @click="selectDirection('upload')">上传</button></div>
      <div class="creation-switch" role="group" aria-label="执行方式"><button type="button" :class="{selected:mode==='ONCE'}" :aria-pressed="mode==='ONCE'" :disabled="locked" @click="selectMode('ONCE')">一次性任务</button><button type="button" :class="{selected:mode==='RULE'}" :aria-pressed="mode==='RULE'" :disabled="locked" @click="selectMode('RULE')">自动规则</button></div>
    </div>
    <DownloadCreationFields v-if="direction==='download'" v-model="downloadSelection" :authorized="authorized" :available="available" :account-id="accountId" :disabled="locked||fieldsLocked" @access-error="emit('accessError',$event)" />
    <UploadCreationFields v-else v-model="uploadSelection" :disabled="locked||fieldsLocked||!authorized||!available" />
    <div v-if="mode==='RULE'&&!authorized" class="notice warning" role="status">请先连接 Telegram，再创建自动规则。<button type="button" class="btn" :disabled="locked" @click="settings">前往设置</button></div>
    <NewTransfer v-if="mode==='ONCE'" :key="contextKey+':'+direction" :embedded="true" :selection="downloadSelection" :upload-selection="uploadSelection" :initial-direction="direction" :authorized="authorized" :available="available" :account-id="accountId" @fields-locked="fieldsLocked=$event" @locked="locked=$event" @close="close" @settings="settings" @tasks="emit('tasks',$event)" @access-error="emit('accessError',$event)" />
    <DownloadRules v-else-if="direction==='download'" :key="contextKey" :selection="downloadSelection" :create-only="true" :embedded="true" :authorized="authorized" :available="available" :account-id="accountId" @locked="locked=$event" @saved="saved" @close="close" @access-error="emit('accessError',$event)" />
    <RuleWorkspace v-else :key="contextKey" :selection="uploadSelection" :create-only="true" :embedded="true" :authorized="authorized" :available="available" :account-id="accountId" @locked="locked=$event" @saved="saved" @close="close" @access-error="emit('accessError',$event)" />
  </RuleFormDialog>
</template>
<style scoped>
.creation-options{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:12px;margin:14px 0 18px;padding-bottom:16px;border-bottom:1px solid #e6edf3}.creation-switch{display:flex;flex-wrap:wrap;gap:7px}.creation-switch button{border:1px solid #d8e3ec;border-radius:6px;background:#fff;color:#7893a8;padding:8px 13px;font-size:12px;line-height:1.5}.creation-switch button.selected{background:#263f54;border-color:#263f54;color:#fff}.creation-switch button:disabled{opacity:.6;cursor:wait}.creation-switch button:focus-visible{outline:2px solid #2986cc;outline-offset:2px}@media(max-width:480px){.creation-options{align-items:stretch;flex-direction:column;gap:10px}.creation-switch button{flex:1;font-size:11px;padding:8px 10px}}
</style>
