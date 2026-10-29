<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { formatMediaBytes } from '../library'
import { uploadTypes, validUploadPreview, type UploadPreview } from '../uploadPreview'
import AppIcon from './AppIcon.vue'
import { validUploadTask, type UploadTask } from '../uploadTasks'
const props = defineProps<{ authorized: boolean; available: boolean; accountId: string | null }>()
const emit = defineEmits<{ settings: []; tasks: []; accessError: [cause: ApiError] }>()
const link = ref(''), sourceDirectory = ref(''), archiveDirectory = ref('')
const recursive = ref(true), captionMode = ref<'FILENAME' | 'EMPTY'>('FILENAME')
const preview = ref<UploadPreview | null>(null), busy = ref(false), error = ref(''), verified = ref(false)
const creating=ref(false),uncertain=ref(false),created=ref<UploadTask|null>(null)
let frozenCreate:{operationId:string;previewId:string}|null=null
let generation = 0, disposed = false
function reset() { generation++; preview.value = null; error.value = ''; busy.value = false; verified.value = false;creating.value=false;uncertain.value=false;created.value=null;frozenCreate=null }
watch([link,sourceDirectory,archiveDirectory,recursive,captionMode], reset)
watch([() => props.authorized, () => props.available, () => props.accountId], (_value, before) => { reset(); if (before && props.accountId !== before[2]) { link.value = ''; sourceDirectory.value = ''; archiveDirectory.value = '' } })
async function prepare(recheck = false) {
  if (busy.value || creating.value || uncertain.value || created.value || !props.authorized || !props.available || !props.accountId || !link.value.trim() || !sourceDirectory.value.trim() || !archiveDirectory.value.trim() || recheck && !preview.value) return
  const request = ++generation, account = props.accountId, old = preview.value
  const input = { link: link.value.trim(), sourceDirectory: sourceDirectory.value.trim(), archiveDirectory: archiveDirectory.value.trim(), recursive: recursive.value, captionMode: captionMode.value }
  busy.value = true; error.value = ''; verified.value = false; if (!recheck) preview.value = null
  try {
    const result = await api<UploadPreview>(recheck ? `/transfers/upload/preview/${encodeURIComponent(old!.id)}/verify` : '/transfers/upload/preview', { method: 'POST', timeout: 150000, body: recheck ? {} : input })
    if (disposed || request !== generation || account !== props.accountId || !props.authorized || !props.available) return
    if (!validUploadPreview(result, account) || result.postLink !== input.link || recheck && result.id !== old?.id) throw new Error('上传预览回执无法确认，请重新生成。')
    if (recheck && JSON.stringify(result.groups) !== JSON.stringify(old?.groups)) throw new Error('原分组发生变化，请重新生成预览。')
    preview.value = result; verified.value = recheck
  } catch (cause) {
    if (disposed || request !== generation) return
    error.value = (cause instanceof Error ? cause.message : '上传预览未完成。') + ' 没有发送或移动文件，不会自动重试。'
    if (cause instanceof ApiError && [401,403].includes(cause.status)) emit('accessError', cause)
  } finally { if (!disposed && request === generation) busy.value = false }
}
async function createTask(retry=false){
  if(creating.value||busy.value||created.value||!preview.value||!props.authorized||!props.available||!props.accountId)return
  if(!retry){if(uncertain.value||!preview.value.fileCount)return;frozenCreate={operationId:crypto.randomUUID(),previewId:preview.value.id}}
  if(!frozenCreate)return;const request=generation,account=props.accountId;creating.value=true;error.value=''
  try{const result=await api<UploadTask>('/transfers/upload/tasks',{method:'POST',timeout:150000,body:frozenCreate});if(disposed||request!==generation||account!==props.accountId)return;if(!validUploadTask(result))throw new Error('上传任务回执无法确认');created.value=result;uncertain.value=false}
  catch(cause){if(!disposed&&request===generation){uncertain.value=true;error.value=(cause instanceof Error?cause.message:'创建未完成')+' 请先核对任务；重试沿用同一操作号。'}}finally{if(!disposed&&request===generation)creating.value=false}
}
async function lookupTask(){if(!frozenCreate||creating.value)return;const request=generation;creating.value=true;try{const result=await api<{task:UploadTask|null}>('/transfers/upload/tasks/by-operation/'+frozenCreate.operationId);if(disposed||request!==generation)return;if(result.task&&validUploadTask(result.task)){created.value=result.task;uncertain.value=false;error.value=''}else error.value='尚未查到任务；请稍后核对，或重试同一创建操作。'}catch{if(!disposed&&request===generation)error.value='任务状态未核实，请到任务中心检查。'}finally{if(!disposed&&request===generation)creating.value=false}}
onBeforeUnmount(() => { disposed = true; generation++ })
</script>
<template>
  <section class="upload-preparation" aria-label="上传准备">
    <p class="upload-intro">将整理好的媒体上传到自己频道主帖的评论区。图片、视频、音频和文档分别分组，不混合发送；预览后保存任务，再到任务中心确认开始。</p>
    <div v-if="!authorized || !available" class="notice warning"><span>{{ authorized ? '请恢复本机连接后重新预览。' : '请先连接 Telegram。' }}</span><button v-if="!authorized" class="btn" @click="emit('settings')">前往设置</button></div>
    <form @submit.prevent="prepare()">
      <fieldset :disabled="busy || creating || uncertain || !!created || !authorized || !available"><label for="upload-link">目标频道主帖链接</label><input id="upload-link" v-model="link" class="input" type="url" placeholder="https://t.me/…" autocomplete="off" required />
        <label for="upload-source">待上传目录</label><input id="upload-source" v-model="sourceDirectory" class="input" placeholder="例如 D:\待上传" autocomplete="off" required />
        <label for="upload-archive">已上传归档目录</label><input id="upload-archive" v-model="archiveDirectory" class="input" placeholder="例如 D:\已上传" autocomplete="off" required /><small>两个目录不能相同或互相包含。预览不会创建归档目录。</small>
        <div class="upload-options"><label><input v-model="recursive" type="checkbox" />包含子目录</label><label for="upload-caption">媒体说明<select id="upload-caption" v-model="captionMode" class="input"><option value="FILENAME">使用原文件名</option><option value="EMPTY">留空</option></select></label></div>
        <button class="btn primary" :disabled="!link.trim() || !sourceDirectory.trim() || !archiveDirectory.trim()"><AppIcon name="search" />{{ busy ? '正在核对目录与目标…' : preview ? '重新生成上传预览' : '生成上传预览' }}</button>
      </fieldset>
    </form>
    <div v-if="error" class="notice danger" role="alert">{{ error }}</div>
    <template v-if="preview">
      <div class="upload-target"><div><small>{{ preview.channelTitle }}</small><strong>{{ preview.postTitle }}</strong><span>评论区：{{ preview.discussionTitle }}</span></div><span class="status-pill green">本批媒体权限已核对</span></div>
      <div class="upload-notes" role="status"><p v-for="warning in preview.warnings" :key="warning">{{ warning }}</p></div>
      <div class="upload-summary"><strong>{{ preview.fileCount }} 个媒体 · {{ preview.groups.length }} 组 · {{ formatMediaBytes(preview.totalBytes) }}</strong><span>忽略临时文件 {{ preview.otherFiles }} · 暂缓 {{ preview.deferredFiles }} · 同内容重复 {{ preview.duplicateFiles }}</span><button class="btn" :disabled="busy || creating || uncertain || !!created" @click="prepare(true)"><AppIcon name="refresh" />重新核对这份预览</button></div>
      <p v-if="verified" class="notice success" role="status">目标权限和原目录快照已重新核对；分组未改变，仍未发送文件。</p>
      <section v-for="group in preview.groups" :key="group.number" class="upload-group"><header><strong>第 {{ group.number }} 组 · {{ uploadTypes[group.mediaType] }}</strong><span>{{ group.mode === 'SINGLE' ? '单条发送' : `相册 · ${group.files.length} 个` }}</span></header><ol><li v-for="file in group.files" :key="file.relativePath"><div><strong>{{ file.fileName }}</strong><small>{{ file.relativePath }}</small><small>说明：{{ file.caption || '留空' }}</small></div><span>{{ formatMediaBytes(file.size) }}</span></li></ol></section>
      <details class="upload-text"><summary>目标主帖全文与目录</summary><p>{{ preview.postText }}</p><p>待上传：{{ preview.sourceDirectory }}<br />归档：{{ preview.archiveDirectory }}</p><a :href="preview.postLink" target="_blank" rel="noreferrer">查看 Telegram 主帖 ↗</a></details>
      <p class="upload-next">同类型内按自然顺序排列，每组最多10个，尾组1个及动图单条处理。保存后分组固定，任务中心确认开始才会发送；收到正式回执后归档。</p>
      <button v-if="!created&&!uncertain" class="btn primary" :disabled="busy||creating||!preview.fileCount" @click="createTask()">{{creating?'正在保存任务…':'保存上传任务（等待开始）'}}</button>
      <div v-if="uncertain" class="upload-options"><button class="btn" :disabled="creating" @click="lookupTask">核对是否已创建</button><button class="btn" :disabled="creating" @click="createTask(true)">重试同一创建操作</button></div>
      <div v-if="created" class="notice success" role="status"><span>上传任务已保存，尚未开始发送。</span><button class="btn" @click="emit('tasks')">查看上传任务</button></div>
    </template>
  </section>
</template>
<style scoped>
.upload-intro,.upload-next{font-size:11px;line-height:1.9;color:#829caf;margin:15px 0}.upload-preparation fieldset{border:0;margin:0;padding:0}.upload-preparation fieldset>label{display:block;font-size:11px;margin:17px 0 8px;color:#64849c}.upload-preparation fieldset>input{width:100%;min-height:38px}.upload-preparation fieldset>small{font-size:10px;display:block;color:#98adbd;line-height:1.8;margin-top:6px}.upload-options{display:flex;gap:20px;align-items:center;flex-wrap:wrap;margin:17px 0;font-size:11px;color:#7a98ad}.upload-options label{display:flex;align-items:center;gap:8px}.upload-options select{font-size:11px}.upload-target{display:flex;justify-content:space-between;align-items:flex-start;gap:15px;border-top:1px solid #e5edf4;padding-top:20px;margin-top:25px}.upload-target small,.upload-target strong,.upload-target div>span{display:block}.upload-target small,.upload-target div>span{font-size:10px;line-height:1.9;color:#8da4b5}.upload-target strong{font-size:13px;color:#4d6f87;line-height:1.9;overflow-wrap:anywhere}.upload-target>.status-pill{white-space:nowrap;font-size:9px}.upload-notes{margin:16px 0;background:#f6f9fc;border:1px solid #e5edf4;border-radius:5px;padding:11px 13px}.upload-notes p,.upload-next{font-size:10px;line-height:1.9}.upload-notes p{margin:3px 0;color:#90a4b3}.upload-summary{display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:18px 0}.upload-summary strong{font-size:12px;font-weight:500;color:#547b96}.upload-summary>span{font-size:10px;color:#98aebb}.upload-summary>.btn{margin-left:auto;font-size:10px}.upload-group{border:1px solid #e0eaf2;border-radius:6px;margin:15px 0;overflow:hidden}.upload-group header{display:flex;justify-content:space-between;padding:12px;background:#f5f9fc;font-size:11px;color:#63869e}.upload-group header strong{font-weight:500}.upload-group ol{list-style:none;margin:0;padding:0;counter-reset:members}.upload-group li{display:flex;gap:10px;align-items:flex-start;padding:12px;border-top:1px solid #eaf0f5;counter-increment:members}.upload-group li::before{content:counter(members);font-size:10px;color:#9bb0be;min-width:14px}.upload-group li>div{min-width:0;flex:1}.upload-group li strong{font-size:11px;font-weight:500;color:#65849b;overflow-wrap:anywhere}.upload-group li small{display:block;font-size:9px;line-height:1.8;color:#9bafbd;overflow-wrap:anywhere}.upload-group li>span{font-size:10px;white-space:nowrap;color:#97acba}.upload-text{font-size:10px;color:#8aa5b7;margin:20px 0}.upload-text summary{cursor:pointer}.upload-text p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9}.upload-text a{color:#558bb2;text-decoration:none}@media(max-width:600px){.upload-target{flex-direction:column}}
</style>
