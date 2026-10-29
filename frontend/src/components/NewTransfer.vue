<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import type { Media } from '../library'
import { mediaTypes, selectedPreviewMedia, validDownloadPreview, type DownloadPreview } from '../downloadPreview'
import AppIcon from './AppIcon.vue'
import MediaCollection from './MediaCollection.vue'
import MediaViewSwitch from './MediaViewSwitch.vue'
import InfiniteLoad from './InfiniteLoad.vue'
import { validTask, type CreateDownload, type DownloadTask } from '../downloadTasks'
import UploadPreparation from './UploadPreparation.vue'
import DownloadChannelPreview from './DownloadChannelPreview.vue'
import type { TaskCategory } from '../taskCategories'

const props = defineProps<{ authorized: boolean; available: boolean; accountId: string | null; previewOnly?: boolean; browseSource?: { sourceMode?: 'CHANNEL' | 'POST'; source: string; tags: string; keywords: string } }>()
const emit = defineEmits<{ close: []; settings: []; tasks: [category: TaskCategory]; accessError: [cause: ApiError] }>()
const dialog = ref<HTMLDialogElement | null>(null)
const direction = ref<'download' | 'upload'>('download')
const downloadSourceKind = ref<'post'|'channel'>(props.previewOnly && props.browseSource?.sourceMode !== 'POST' ? 'channel' : 'post')
const channelDownloadLocked=ref(false)
const link = ref(props.previewOnly && props.browseSource?.sourceMode === 'POST' ? props.browseSource.source : '')
const preview = ref<DownloadPreview | null>(null)
const collectedMedia = ref<Media[]>([])
const originPages = ref<Record<string, number>>({})
const mediaView = ref<'list' | 'cards'>('list')
const autoPaused = ref(false)
const autoEpoch = ref(0)
const selectedTypes = ref<Media['type'][]>(props.previewOnly ? mediaTypes.map(item => item.type) : ['VIDEO'])
const allTypesSelected = computed(() => mediaTypes.every(item => selectedTypes.value.includes(item.type)))
const includeMain = ref(!!props.previewOnly)
const busy = ref(false)
const error = ref('')
const needsPreview = ref(false)
const targetDirectory = ref('')
const excludedKeys = ref<string[]>([])
const manualSelection = ref<string[] | null>(null)
const creating = ref(false), createUncertain = ref(false)
const createError = ref('')
const createdTask = ref<DownloadTask | null>(null)
let frozenRequest: CreateDownload | null = null
const downloadLocked = computed(() => creating.value || createUncertain.value || !!createdTask.value || channelDownloadLocked.value)
const canAccess = computed(() => props.authorized && props.available && !!props.accountId)
const items = computed(() => selectedPreviewMedia(preview.value?.comments ? {
  ...preview.value, comments: { ...preview.value.comments, media: collectedMedia.value },
} : preview.value, selectedTypes.value, includeMain.value))
const commentMatches = computed(() => items.value.filter(item => item.source === 'COMMENT').length)
const mainMatches = computed(() => items.value.filter(item => item.source === 'MAIN').length)
const selectedDownloadKeys = computed(() => items.value.filter(item => manualSelection.value === null ? !excludedKeys.value.includes(item.key) : manualSelection.value.includes(item.key)).map(item => item.key))
const emptyText = computed(() => !selectedTypes.value.length
  ? includeMain.value ? '未选择评论媒体类型，当前仅显示主帖自身媒体。' : '未选择媒体类型，请选择要浏览的评论媒体。'
  : preview.value?.exhausted
  ? '已读范围没有符合条件的媒体，评论历史已读到边界。'
  : preview.value?.canContinue ? '已读范围没有符合条件的媒体。向下滚动继续读取，零媒体不表示评论读完。'
    : '当前预览没有符合条件的媒体，评论范围尚未核全，请查看上方提示。')
let generation = 0
let disposed = false
let autoSteps = 0
let autoStartedAt = Date.now()

function toggleType(type: Media['type']) {
  if (busy.value || downloadLocked.value) return
  selectedTypes.value = selectedTypes.value.includes(type)
    ? selectedTypes.value.filter(item => item !== type) : [...selectedTypes.value, type]
}
function toggleAllTypes() {
  if (busy.value || downloadLocked.value) return
  selectedTypes.value = allTypesSelected.value ? [] : mediaTypes.map(item => item.type)
}
function continueBrowsing() { autoSteps = 0; autoStartedAt = Date.now(); autoPaused.value = false; autoEpoch.value++ }
function loadMore() {
  if (!selectedTypes.value.length) return
  if (autoSteps >= 200 || Date.now() - autoStartedAt >= 20 * 60 * 1000) { autoPaused.value = true; return }
  if (!busy.value && !needsPreview.value && !downloadLocked.value && preview.value?.canContinue) { autoSteps++; void readPreview(true) }
}
function toggleDownload(key: string) {
  if (props.previewOnly || downloadLocked.value) return
  const list = manualSelection.value ?? excludedKeys.value
  const result = list.includes(key) ? list.filter(item => item !== key) : [...list, key]
  if (manualSelection.value === null) excludedKeys.value = result; else manualSelection.value = result
}
function resetDownload() { creating.value = false; createUncertain.value = false; createError.value = ''; createdTask.value = null; frozenRequest = null; excludedKeys.value = []; manualSelection.value = null }
async function createDownload(retry = false) {
  if (props.previewOnly || creating.value || !canAccess.value || !preview.value || createdTask.value) return
  if (!retry) {
    if (downloadLocked.value || !targetDirectory.value.trim() || !selectedDownloadKeys.value.length || selectedDownloadKeys.value.length > 100) return
    frozenRequest = { operationId: crypto.randomUUID(), sourceId: preview.value.id, mediaKeys: [...selectedDownloadKeys.value], includeMain: includeMain.value, targetDirectory: targetDirectory.value.trim() }
  }
  if (!frozenRequest) return
  const request = generation, account = props.accountId
  creating.value = true; createError.value = ''
  try {
    const result = await api<DownloadTask>('/transfers/download/tasks', { method: 'POST', timeout: 120000, body: frozenRequest })
    if (disposed || request !== generation || account !== props.accountId) return
    if (!validTask(result)) throw new Error('任务创建回执无法确认。')
    createdTask.value = result; createUncertain.value = false
  } catch (cause) {
    if (disposed || request !== generation) return
    createUncertain.value = true; createError.value = (cause instanceof Error ? cause.message : '创建未完成。') + ' 请先核对任务；重试将使用同一操作号。'
    if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
  } finally { if (!disposed && request === generation) creating.value = false }
}
async function lookupDownload() {
  if (props.previewOnly || !frozenRequest || creating.value || !canAccess.value) return
  const request = generation; creating.value = true
  try {
    const result = await api<{ task: DownloadTask | null }>('/transfers/download/tasks/by-operation/' + frozenRequest.operationId)
    if (disposed || request !== generation) return
    if (result.task && validTask(result.task)) { createdTask.value = result.task; createUncertain.value = false; createError.value = '' }
    else createError.value = '尚未查到任务；后台可能仍在准备。可稍后核对，或重试相同创建请求。'
  } catch { if (!disposed && request === generation) createError.value = '任务状态核对未完成，请到任务中心刷新查看。' }
  finally { if (!disposed && request === generation) creating.value = false }
}
async function readPreview(next = false) {
  if (!canAccess.value || busy.value || downloadLocked.value || !props.accountId) return
  const previous = next ? preview.value : null
  if (next && (!previous?.canContinue || needsPreview.value)) return
  if (!next && !link.value.trim()) { error.value = '请填写频道主帖链接。'; return }
  const account = props.accountId, currentLink = link.value, request = ++generation
  const isCurrent = () => !disposed && request === generation && canAccess.value && props.accountId === account && link.value === currentLink
  busy.value = true; error.value = ''; needsPreview.value = false
  if (!next) { preview.value = null; collectedMedia.value = []; originPages.value = {}; continueBrowsing() }
  try {
    const result = await api<DownloadPreview>(next ? '/transfers/download/preview/next' : '/transfers/download/preview', {
      method: 'POST', timeout: 120000,
      body: next && previous ? { id: previous.id, cursor: previous.comments?.nextCursor, pageNumber: previous.pageNumber } : { link: currentLink.trim() },
    })
    if (!isCurrent()) return
    if (!validDownloadPreview(result, account, previous ?? undefined)) throw new Error('来源预览回执无法确认。')
    const merged = new Map(collectedMedia.value.map(item => [item.key, item]))
    const origins = { ...originPages.value }
    for (const media of result.comments?.media ?? []) { merged.set(media.key, media); origins[media.key] = result.pageNumber }
    collectedMedia.value = [...merged.values()]; originPages.value = origins
    preview.value = result
  } catch (cause) {
    if (!isCurrent()) return
    needsPreview.value = true
    error.value = (cause instanceof Error ? cause.message : '来源读取未完成。') + ' 请重新预览来源，不会自动重试。'
    if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
  } finally { if (isCurrent()) busy.value = false }
}
function resetPreview() { generation++; busy.value = false; preview.value = null; collectedMedia.value = []; originPages.value = {}; error.value = ''; needsPreview.value = false; resetDownload(); continueBrowsing() }
function backToChannel() {
  if (!props.previewOnly || props.browseSource?.sourceMode === 'POST') return
  resetPreview()
  downloadSourceKind.value = 'channel'
}
async function openChannelPost(value:string){if(downloadLocked.value||busy.value)return;downloadSourceKind.value='post';link.value=value;await nextTick();await readPreview()}
watch(direction, resetPreview)
watch(downloadSourceKind, resetPreview)
watch(link, resetPreview)
watch([() => props.authorized, () => props.available, () => props.accountId], (_value, previous) => {
  resetPreview()
  if (previous && props.accountId !== previous[2]) link.value = ''
})
onMounted(() => { dialog.value?.showModal(); if (props.previewOnly && downloadSourceKind.value === 'post') void readPreview() })
onBeforeUnmount(() => { disposed = true; generation++; dialog.value?.close() })
</script>

<template>
  <dialog ref="dialog" class="transfer-dialog" aria-labelledby="transfer-heading" @cancel.prevent="emit('close')">
    <header class="transfer-heading"><div><span v-if="!previewOnly" class="eyebrow">TRANSFER</span><h2 id="transfer-heading">{{ previewOnly ? '浏览帖子' : '新建传输' }}</h2></div><button class="icon-btn" :aria-label="previewOnly ? '关闭帖子浏览' : '关闭新建传输'" @click="emit('close')"><AppIcon name="close" /></button></header>
    <div v-if="!previewOnly" class="transfer-direction"><button type="button" :class="{ selected: direction === 'download' }" :aria-pressed="direction === 'download'" :disabled="busy || downloadLocked" @click="direction = 'download'">下载</button><button type="button" :class="{ selected: direction === 'upload' }" :aria-pressed="direction === 'upload'" :disabled="busy || downloadLocked" @click="direction = 'upload'">上传准备</button></div>
    <UploadPreparation v-if="!previewOnly && direction === 'upload'" :authorized="authorized" :available="available" :account-id="accountId" @settings="emit('settings')" @tasks="emit('tasks', 'upload')" @access-error="emit('accessError', $event)" />
    <template v-else>
    <div v-if="!previewOnly" class="download-source-kind" role="group" aria-label="下载来源方式"><button type="button" :aria-pressed="downloadSourceKind==='post'" :class="{selected:downloadSourceKind==='post'}" :disabled="busy||downloadLocked" @click="downloadSourceKind='post'">主帖链接</button><button type="button" :aria-pressed="downloadSourceKind==='channel'" :class="{selected:downloadSourceKind==='channel'}" :disabled="busy||downloadLocked" @click="downloadSourceKind='channel'">频道扫描预览</button></div>
    <button v-if="previewOnly && browseSource?.sourceMode !== 'POST' && downloadSourceKind==='post'" class="btn browse-back" @click="backToChannel">返回帖子列表</button>
    <DownloadChannelPreview v-show="downloadSourceKind==='channel'" :active="downloadSourceKind==='channel'" :authorized="authorized" :available="available" :account-id="accountId" :read-only="previewOnly" :initial-source="previewOnly && browseSource?.sourceMode !== 'POST' ? browseSource : undefined" @open-post="openChannelPost" @locked="channelDownloadLocked=$event" @tasks="emit('tasks', 'batches')" @access-error="emit('accessError',$event)" />
    <template v-if="downloadSourceKind==='post'">
    <p v-if="!previewOnly" class="transfer-intro">先预览频道主帖及评论媒体。支持当前账号可访问的外部频道，不要求管理员权限，也不会加入“我的资源库”。</p>
    <p v-if="!previewOnly" class="transfer-limit"><AppIcon name="info" />预览后勾选已加载媒体、填写保存目录，再创建一次性下载任务；不会自动下载未加载内容。</p>
    <div v-if="!canAccess" class="notice warning" role="status"><span>{{ authorized ? '本机连接暂不可用，请恢复连接后重新预览。' : '请先连接 Telegram，再预览下载来源。' }}</span><button v-if="!authorized" class="btn" @click="emit('settings')">前往设置</button></div>
    <form class="transfer-source" @submit.prevent="readPreview()"><label for="transfer-link">频道主帖链接</label><div><input id="transfer-link" v-model="link" class="input" type="url" placeholder="https://t.me/…" autocomplete="off" spellcheck="false" :disabled="busy || !canAccess || downloadLocked" :readonly="previewOnly" required /><button class="btn primary" :disabled="busy || !canAccess || downloadLocked || !link.trim()"><AppIcon name="search" />{{ busy ? '正在读取…' : previewOnly ? '刷新帖子' : preview || needsPreview ? '重新预览来源' : '预览下载来源' }}</button></div><small>读取这一条主帖，评论随滚动继续加载；不会自动加入频道或保存到资源库。</small></form>
    <div v-if="error" class="notice danger" role="alert">{{ error }}</div>
    <template v-if="preview">
      <div class="transfer-context"><div><small>{{ preview.post.channelTitle }}</small><strong>{{ preview.post.title }}</strong></div><span class="status-pill" :class="preview.post.mainCoverage === 'COMPLETE' ? 'green' : 'neutral'">{{ preview.post.mainCoverage === 'COMPLETE' ? '主帖范围已核全' : '主帖范围待核实' }}</span></div>
      <div v-if="preview.warnings.length" class="transfer-warnings" role="status"><p v-for="warning in preview.warnings" :key="warning">{{ warning }}</p></div>
      <fieldset class="transfer-filters" :disabled="busy || downloadLocked"><legend>评论媒体类型 · 可多选</legend><div class="filter-chips"><button type="button" :class="{ selected: allTypesSelected }" :aria-pressed="allTypesSelected" @click="toggleAllTypes">全部</button><button v-for="item in mediaTypes" :key="item.type" type="button" :class="{ selected: selectedTypes.includes(item.type) }" :aria-pressed="selectedTypes.includes(item.type)" @click="toggleType(item.type)">{{ item.label }}</button></div><label class="transfer-include"><input v-model="includeMain" type="checkbox" />包含主帖自身全部媒体</label></fieldset>
      <div class="transfer-page-bar"><span>{{ preview.comments ? `本次已读取 ${preview.observedMessages} 条评论消息` : '当前未取得可读取的评论线程' }}</span><strong>{{ preview.exhausted ? '评论历史已到边界' : preview.canContinue ? '向下滚动继续读取' : '评论范围尚未核全' }}</strong></div>
      <div class="transfer-browse-bar"><p class="transfer-count">已展示评论媒体 {{ commentMatches }} 个<span v-if="includeMain"> · 主帖媒体 {{ mainMatches }} 个</span><small>筛选作用于已读范围，仅加载小封面，不下载原媒体。</small></p><MediaViewSwitch v-model="mediaView" /></div>
      <section v-if="!previewOnly" class="download-create" aria-label="创建下载任务"><div class="download-select-tools"><span>已勾选 {{ selectedDownloadKeys.length }} 个 · 每次最多100个</span><button class="btn" :disabled="downloadLocked" @click="manualSelection = null; excludedKeys = []">全选已加载</button><button class="btn" :disabled="downloadLocked" @click="manualSelection = []">清空选择</button></div><label for="download-directory">保存根目录</label><input id="download-directory" v-model="targetDirectory" class="input" placeholder="例如 D:\TelegramDownloads" autocomplete="off" :disabled="downloadLocked" /><p>任务仅包含本次勾选的媒体；重复媒体也会下载，校验后单独放入指定目录下的“重复媒体”文件夹，并保存来源清单。</p><button v-if="!createdTask && !createUncertain" class="btn primary" :disabled="busy || creating || !targetDirectory.trim() || !selectedDownloadKeys.length || selectedDownloadKeys.length > 100 || !canAccess" @click="createDownload()">{{ creating ? '正在准备下载任务…' : `创建并开始下载（${selectedDownloadKeys.length}）` }}</button><div v-if="createError" class="notice warning" role="alert">{{ createError }}</div><div v-if="createUncertain" class="download-select-tools"><button class="btn" :disabled="creating" @click="lookupDownload">核对下载任务</button><button class="btn" :disabled="creating" @click="createDownload(true)">重试相同创建请求</button></div><div v-if="createdTask" class="notice success" role="status"><span>下载任务已保存，包含 {{ createdTask.total }} 个文件。<template v-if="createdTask.skipped">历史已下载，跳过 {{ createdTask.skipped }} 个。</template></span><button class="btn" @click="emit('tasks', 'download')">查看任务</button></div></section>
      <MediaCollection :items="items" :layout="mediaView" :empty-text="emptyText" :post-key="preview.post.key" :account-id="accountId" :download-preview-id="preview.id" :origins="originPages" :version="0" :authorized="authorized" :available="canAccess && !needsPreview" :selectable="!previewOnly" :selected-keys="selectedDownloadKeys" :selection-disabled="downloadLocked" @toggle-media="toggleDownload" />
      <InfiniteLoad :has-more="preview.canContinue" :busy="busy" :blocked="needsPreview || autoPaused || downloadLocked || !canAccess || !selectedTypes.length" :version="preview.pageNumber + ':' + autoEpoch" :context="preview.id + ':' + mediaView + ':' + selectedTypes.join(',') + ':' + includeMain" @load="loadMore" />
      <div v-if="autoPaused" class="notice warning" role="status"><span>本次连续预览达到200批或20分钟，已暂停，尚未表示读完。</span><button class="btn" @click="continueBrowsing">继续滚动加载</button></div>
      <details class="transfer-text"><summary>主帖全文</summary><p>{{ preview.post.text || '主帖没有文字' }}</p></details>
    </template>
    <footer class="transfer-footer"><span>预览不会写入业务索引；关闭或重启后请重新预览。</span><button type="button" class="btn" @click="emit('close')">关闭预览</button></footer>
    </template>
    <footer v-else class="transfer-footer"><span>{{ previewOnly ? '仅浏览规则来源，不创建下载任务。' : '扫描和准备清单只读；确认“创建并开始批量下载”后才下载原媒体。' }}</span><button type="button" class="btn" @click="emit('close')">关闭预览</button></footer>
    </template>
  </dialog>
</template>

<style scoped>
.download-source-kind{display:flex;gap:6px;margin:18px 0}.download-source-kind button{padding:8px 13px;border:1px solid #dfe8ef;border-radius:5px;background:white;color:#8199ad;font-size:11px}.download-source-kind button.selected{background:#203f56;color:white;border-color:#203f56}
.transfer-direction>button{display:flex;gap:7px;align-items:center;padding:7px 12px;border:1px solid #dfe8f0;border-radius:5px;background:#fff;color:#829bae;font-size:12px;cursor:pointer}.transfer-direction>button:disabled{opacity:.5;cursor:default}
.download-create{padding:14px;background:#f6f9fc;border:1px solid #e0eaf2;border-radius:6px;margin:15px 0}.download-select-tools{display:flex;align-items:center;gap:8px;flex-wrap:wrap;font-size:10px;color:#879faf;margin-bottom:12px}.download-select-tools .btn{min-height:26px;font-size:9px;padding:4px 7px}.download-create>label{display:block;font-size:10px;margin:10px 0 7px}.download-create>p{font-size:9px;line-height:1.9;color:#96aab9;margin:8px 0 12px}.download-create>.btn{font-size:10px}.download-create .notice{margin-top:12px}
.transfer-browse-bar{display:flex;justify-content:space-between;gap:15px;align-items:center;margin:12px 0}.transfer-browse-bar .transfer-count{margin:0}
.source-media-cell{display:flex;align-items:flex-start;gap:10px}.source-media-image{position:relative;flex:0 0 80px;width:80px;height:60px;display:grid;place-items:center;border-radius:4px;background:#eff4f8}.source-media-image>.icon{width:24px;height:24px;color:#91aabd}.source-media-text{min-width:0;flex:1}.source-video-marker{position:absolute;right:4px;bottom:4px;pointer-events:none;width:0;height:0;border-top:4px solid transparent;border-bottom:4px solid transparent;border-left:6px solid #fff;filter:drop-shadow(0 1px 1px #233e55)}
.transfer-dialog{width:min(900px,calc(100vw - 32px));max-width:none;max-height:calc(100vh - 40px);overflow:auto;border:1px solid #dce5ed;border-radius:12px;padding:25px 28px;color:#5c798f;background:#fff;box-shadow:0 20px 90px #17334b33}.transfer-dialog::backdrop{background:#17334b55;backdrop-filter:blur(2px)}.transfer-heading{display:flex;justify-content:space-between;align-items:center}.transfer-heading h2{font-size:21px;margin:4px 0 0;color:#263f53}.transfer-direction{display:flex;align-items:center;gap:18px;margin:22px 0 13px;font-size:12px}.transfer-direction>span{display:flex;gap:7px;align-items:center;padding:7px 12px}.transfer-direction .selected{background:#233e55;color:#fff;border-radius:5px}.unavailable{color:#9aaebc}.transfer-intro{font-size:11px;line-height:1.9;margin-bottom:12px;color:#7f96a8}.transfer-limit{display:flex;gap:6px;align-items:flex-start;padding:10px;background:#f2f7fb;border-radius:5px;font-size:10px;line-height:1.8}.transfer-limit>.icon{width:15px;flex-shrink:0}.transfer-source{margin:20px 0}.transfer-source>label{display:block;font-size:11px;margin-bottom:8px}.transfer-source>div{display:flex;gap:9px}.transfer-source .input{flex:1;min-width:0}.transfer-source .btn{flex-shrink:0;font-size:11px}.transfer-source>small{display:block;font-size:9px;line-height:1.9;color:#91a5b4;margin-top:7px}.transfer-context{display:flex;justify-content:space-between;align-items:flex-start;gap:15px;padding:14px 0;border-top:1px solid #e7eef4}.transfer-context>div{min-width:0}.transfer-context small{display:block;font-size:10px;color:#91a6b6}.transfer-context strong{display:block;font-size:13px;line-height:1.8;overflow-wrap:anywhere;margin-top:4px}.transfer-context>.status-pill{white-space:nowrap;flex-shrink:0}.transfer-warnings{padding:10px 13px;background:#fffaf1;border-left:2px solid #d9b77f;font-size:10px;line-height:1.9;color:#a28a60;margin-bottom:14px}.transfer-filters{border:0;padding:0;margin:16px 0}.transfer-filters legend{font-size:10px;margin-bottom:9px}.transfer-filters .filter-chips{display:flex;gap:6px;flex-wrap:wrap}.filter-chips button{border:1px solid #dce6ee;background:#fff;border-radius:4px;padding:6px 10px;color:#829db1;font-size:10px}.filter-chips button.selected{background:#233e55;color:#fff;border-color:#233e55}.transfer-include{display:flex;align-items:center;gap:7px;font-size:10px;margin-top:13px}.transfer-include input{accent-color:#2986cc}.transfer-page-bar{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:10px 12px;background:#f5f9fc;border:1px solid #e3edf5;border-radius:5px;font-size:10px;line-height:1.8}.transfer-page-bar strong{font-weight:500;color:#7c9b92}.transfer-page-bar>.btn{margin-left:auto;font-size:10px;min-height:30px}.transfer-count{font-size:10px;margin:14px 0 10px}.transfer-count>small{display:block;color:#9aafbc;font-size:9px;line-height:1.9}.transfer-empty{padding:28px 15px;text-align:center;font-size:11px;line-height:1.9;background:#f8fafc;color:#8ea5b6}.transfer-table-scroll{overflow-x:auto;border:1px solid #e3ebf2;border-radius:5px}.transfer-table-scroll table{width:100%;border-collapse:collapse;table-layout:fixed;min-width:440px;font-size:10px}.transfer-table-scroll th{text-align:left;background:#f6f9fc;color:#8ba3b5;font-size:9px;font-weight:400;padding:10px}.transfer-table-scroll th:first-child{width:63%}.transfer-table-scroll th:last-child{width:17%}.transfer-table-scroll td{vertical-align:top;padding:11px 10px;border-top:1px solid #eaf0f5;overflow-wrap:anywhere}.transfer-table-scroll td strong{font-weight:500}.transfer-table-scroll td p{white-space:pre-wrap;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;margin:5px 0;color:#90a5b5}.transfer-table-scroll td small{display:block;color:#9aafbd;font-size:9px;line-height:1.9}.transfer-table-scroll a{color:#4383b1;text-decoration:none}.transfer-text{margin:16px 0;font-size:10px;color:#8ca4b6}.transfer-text summary{cursor:pointer}.transfer-text p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;padding:10px;background:#f7fafc}.transfer-footer{display:flex;align-items:center;gap:15px;justify-content:space-between;margin-top:23px;padding-top:16px;border-top:1px solid #e7eef4}.transfer-footer>span{font-size:9px;line-height:1.9;color:#99adbb}.transfer-footer>.btn{flex-shrink:0;font-size:11px}.transfer-dialog :focus-visible{outline:2px solid #2986cc;outline-offset:3px}@media(max-width:600px){.transfer-dialog{padding:18px 16px;max-height:calc(100vh - 20px);width:calc(100vw - 20px)}.transfer-source>div{flex-direction:column}.transfer-context{flex-direction:column;gap:8px}.transfer-page-bar{flex-direction:column;align-items:flex-start}.transfer-page-bar>.btn{margin-left:0}.transfer-footer{align-items:flex-start}.transfer-heading h2{font-size:18px}}
</style>
