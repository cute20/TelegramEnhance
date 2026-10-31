<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { formatMediaBytes, originalMessageLink } from '../library'
import { taskLabels, validTask, type DownloadTask, type DownloadAutomation } from '../downloadTasks'
import AppIcon from './AppIcon.vue'
import CompactRecord from './CompactRecord.vue'
import InfiniteLoad from './InfiniteLoad.vue'
import { mergeTaskHistory } from '../taskHistory'
import RuleDownloadTasks from './RuleDownloadTasks.vue'
const props = withDefaults(defineProps<{ authorized: boolean; available: boolean; accountId: string | null; active?: boolean; refreshVersion?: number }>(), { active: true })
const emit = defineEmits<{ accessError: [cause: ApiError] }>()
const tasks = ref<DownloadTask[]>([]), error = ref(''), workerWarning = ref(''), busy = ref(''), loading = ref(false)
const offset = ref(0)
const hasMore = ref(true), loadingMore = ref(false), historyError = ref('')
const ruleCount = ref(0), localRefresh = ref(0)
function refreshAll() { localRefresh.value++; void load() }
let generation = 0, disposed = false, pending = false, refreshQueued = false
let progressEpoch = 0, progressPending = false, progressIndex = 0
let listEpoch = 0
let timer: ReturnType<typeof setTimeout> | undefined
function stop() { clearTimeout(timer); timer = undefined; progressEpoch++ }
const ready = () => !disposed && props.active !== false && props.authorized && props.available && !!props.accountId
const current = (request: number, account: string | null) => !disposed && request === generation && account === props.accountId && props.authorized && props.available
function schedule() { stop(); if (ready() && !pending && !busy.value && !progressPending && tasks.value.some(task => ['RUNNING','QUEUED'].includes(task.state))) timer = setTimeout(() => { void readProgress() }, 2000) }
async function readProgress() {
  if (!ready() || pending || busy.value || progressPending) return
  const active = tasks.value.filter(task => ['RUNNING','QUEUED'].includes(task.state))
  const selected = active.find(task => task.state === 'RUNNING') ?? active[progressIndex++ % active.length]
  if (!selected) return
  const request = generation, epoch = progressEpoch, account = props.accountId
  progressPending = true
  try {
    const result = await api<DownloadTask>('/transfers/download/tasks/' + encodeURIComponent(selected.id))
    if (!current(request, account) || !ready() || epoch !== progressEpoch) return
    if (!validTask(result) || result.id !== selected.id) return
    tasks.value = tasks.value.map(task => task.id === result.id && JSON.stringify(task) !== JSON.stringify(result) ? result : task)
  } catch (cause) { if (current(request, account) && epoch === progressEpoch && cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause) }
  finally { progressPending = false; if (refreshQueued && ready() && !pending) void load(); else schedule() }
}
function finish() { pending = false; if (disposed) return; if (refreshQueued && ready()) { refreshQueued = false; void load() } else schedule() }
async function load(append = false) {
  if (!ready() || append && !hasMore.value) return
  if (pending || progressPending) { if (!append) refreshQueued = true; return }
  refreshQueued = false; stop()
  const start = append ? offset.value : 0
  const request = generation, account = props.accountId, epoch = listEpoch, previous = [...tasks.value], previousOffset = offset.value
  const alive = () => current(request, account) && ready() && epoch === listEpoch
  pending = true; loading.value = true; loadingMore.value = append
  try {
    const updates = new Map<string, DownloadTask>(); let lastSize = 0, warning = ''
    const end = append ? start + 20 : Math.max(20, previousOffset, previous.length)
    for (let position = start; position < end; position += 20) {
      if (!alive()) return
      const result = await api<{ items: DownloadTask[]; workerWarning: string | null }>('/transfers/download/tasks?standalone=true&limit=20&offset=' + position)
      if (!alive()) return
      if (!Array.isArray(result.items) || result.items.length > 20 || new Set(result.items.map(item => item?.id)).size !== result.items.length || result.items.some(item => !validTask(item))) throw new Error('下载任务回执无法确认，请刷新。')
      for (const item of result.items) updates.set(item.id, item)
      lastSize = result.items.length; warning = result.workerWarning ?? ''
      if (lastSize < 20) break
    }
    if (!append) for (const old of previous) if (!updates.has(old.id)) {
      if (!alive()) return
      const item = await api<DownloadTask>('/transfers/download/tasks/' + encodeURIComponent(old.id))
      if (!alive()) return
      if (!validTask(item) || item.id !== old.id) throw new Error('已有下载记录无法确认，请手动刷新。')
      updates.set(item.id, item)
    }
    const incoming = [...updates.values()], newHead = incoming.some(item => !previous.some(old => old.id === item.id))
    tasks.value = mergeTaskHistory(append ? tasks.value : previous, incoming)
    workerWarning.value = warning; error.value = ''
    if (append) historyError.value = ''
    if (append || !previous.length) { offset.value = start + lastSize; hasMore.value = lastSize === 20 }
    else if (newHead) hasMore.value = lastSize === 20
  } catch (cause) {
    if (!alive()) return
    error.value = cause instanceof Error ? cause.message : '读取任务失败。'
    if (append) historyError.value = error.value
    if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
  } finally { if (current(request, account)) { loading.value = false; loadingMore.value = false }; finish() }
}
async function action(task: DownloadTask, verb: 'pause' | 'resume') {
  if (busy.value || pending || !ready()) return
  const request = generation, account = props.accountId; stop(); pending = true; busy.value = task.id; error.value = ''
  try {
    const result = await api<DownloadTask>(`/transfers/download/tasks/${encodeURIComponent(task.id)}/${verb}`, { method: 'POST' })
    if (!current(request, account)) return
    if (!validTask(result) || result.id !== task.id) throw new Error('任务操作回执无法确认，请刷新。')
    tasks.value = tasks.value.map(item => item.id === result.id ? result : item)
  } catch (cause) { if (current(request, account)) error.value = (cause instanceof Error ? cause.message : '操作未完成。') + ' 请先刷新核对，不会自动重试。' }
  finally { if (current(request, account)) busy.value = ''; finish() }
}
function loadMore() { return load(true) }
function automationState(value: DownloadAutomation) {
  return value.manual ? '已转手动' : value.held ? '手动暂停' : !value.enabled ? '规则已暂停'
    : value.ruleVersion !== value.currentVersion ? '使用旧规则配置' : '规则已启用'
}
watch([() => props.authorized, () => props.available, () => props.accountId], () => {
  generation++; stop(); tasks.value = []; error.value = ''; historyError.value = ''; busy.value = ''; loading.value = false; loadingMore.value = false; workerWarning.value = ''; offset.value = 0; hasMore.value = true
  refreshQueued = ready(); if (!pending) void load()
}, { immediate: true })
watch([() => props.active, () => props.refreshVersion], () => { listEpoch++; stop(); refreshQueued = ready(); if (!pending && ready()) void load() })
onBeforeUnmount(() => { disposed = true; generation++; stop() })
</script>
<template>
  <section class="download-tasks" aria-label="单帖下载">
    <header class="tasks-toolbar"><div><h2>单帖下载</h2></div><button class="btn" :disabled="loading" @click="refreshAll()"><AppIcon name="refresh" />刷新任务</button></header>
    <RuleDownloadTasks :authorized="authorized" :available="available" :account-id="accountId" :active="active" :refresh-version="(refreshVersion??0)+localRefresh" @count="ruleCount=$event" @access-error="emit('accessError',$event)" />
    <div v-if="!authorized" class="notice warning">请先连接 Telegram，再查看当前账号的下载任务。</div>
    <div v-if="workerWarning" class="notice warning" role="alert">{{ workerWarning }}</div><div v-if="error" class="notice danger" role="alert">{{ error }}</div>
    <p v-if="loading && !tasks.length && !ruleCount" class="tasks-empty" role="status">正在读取任务…</p><p v-else-if="!tasks.length && !ruleCount" class="tasks-empty">暂无下载任务。点击右上“新建传输”，预览、勾选媒体并填写保存目录。</p>
    <CompactRecord v-for="task in tasks" :key="task.id" class="download-task" :title="task.title" :description="`${task.channelTitle} · 已保存 ${task.completed}/${task.total}${task.duplicates?' · 重复媒体 '+task.duplicates:''}${task.skipped?' · 历史跳过 '+task.skipped:''} · ${formatMediaBytes(task.totalBytes)}`" :status="task.pauseRequested&&task.state==='RUNNING'?'正在暂停':task.state==='COMPLETED'&&task.skipped?'已处理完成':taskLabels[task.state]!" :tone="task.state==='COMPLETED'?'success':task.state==='FAILED'?'warning':['RUNNING','QUEUED'].includes(task.state)?'active':'neutral'" :timestamp="task.updatedAt" :default-expanded="false" :active="active" :alert="task.error">
      <div class="download-task-body">
        <p v-if="task.error" class="notice warning" role="status">{{ task.error }}</p>
        <dl class="task-facts">
          <dt>保存位置</dt><dd><code>{{ task.targetDirectory }}</code></dd>
          <template v-if="task.duplicatePolicy==='KEEP_DUPLICATES_V2' && task.duplicates">
            <dt>重复媒体</dt><dd><code>{{ task.duplicateDirectory }}</code></dd>
          </template>
          <template v-if="task.automation">
            <dt>自动规则</dt><dd>{{ task.automation.ruleName }}<span class="automation-state">{{ automationState(task.automation) }}</span></dd>
            <dt>执行时段</dt><dd>{{ task.automation.window.mode==='ALL_DAY' ? '全天' : `${task.automation.window.start}—${task.automation.window.end}` }}<span class="task-muted">北京时间</span></dd>
          </template>
        </dl>
        <div v-if="['QUEUED','RUNNING','PAUSED','FAILED'].includes(task.state)" class="task-controls">
          <button v-if="['QUEUED','RUNNING'].includes(task.state)" class="btn" :disabled="!!busy || task.pauseRequested" @click="action(task,'pause')">暂停下载</button>
          <button v-if="['PAUSED','FAILED'].includes(task.state)" class="btn primary" :disabled="!!busy" @click="action(task,'resume')">继续未完成项</button>
          <small v-if="task.automation && !task.automation.manual">继续下载受规则状态和时段控制。</small>
        </div>
        <details class="task-files">
          <summary>文件明细（{{ task.total }}）</summary>
          <div v-for="item in task.items" :key="item.id" class="download-item">
            <div class="download-item-content">
              <strong>{{ item.fileName }}</strong>
              <small>{{ taskLabels[item.state] }} · {{ formatMediaBytes(item.size) }}</small>
              <small v-if="item.state==='DOWNLOADING'">客户端已取得 {{ formatMediaBytes(item.downloadedBytes) }}</small>
              <small v-if="item.state==='WRITING'">目标已写入 {{ formatMediaBytes(item.writtenBytes) }}</small>
              <p v-if="item.error" class="notice warning" role="status">{{ item.error }}</p>
              <code v-if="item.state==='COMPLETED'">{{ item.outputPath }}</code>
              <small v-if="originalMessageLink(item.sourceLink)"><a :href="originalMessageLink(item.sourceLink)" target="_blank" rel="noreferrer">媒体原消息 ↗</a></small>
              <details v-if="item.duplicate || item.manifestPath || item.history" class="item-evidence">
                <summary>来源与去重记录</summary>
                <small v-if="item.duplicate">重复依据：{{ item.duplicate.reason==='SOURCE_MESSAGE'?'同一来源媒体及SHA一致':item.duplicate.reason==='FILE_ID'?'文件标识及SHA一致':'大小及SHA-256一致' }}</small>
                <small v-if="item.state==='COMPLETED' && item.manifestPath">来源清单：<code>{{ item.manifestPath }}</code></small>
                <small v-if="item.history">历史保存位置（文件可能已移动）：<code>{{ item.history.path }}</code></small>
              </details>
            </div>
            <span v-if="item.state==='COMPLETED'" class="item-result">{{ item.duplicate?'重复媒体已保存':'已校验保存' }}</span><span v-else-if="item.history" class="item-result">按历史跳过</span>
          </div>
        </details>
        <details v-if="task.state==='COMPLETED'" class="task-proof">
          <summary>校验说明</summary>
          <p>{{ task.skipped ? '历史命中项未重新下载，也不表示文件仍在原目录；完整大小和SHA-256证据已保留。' : '已保存校验记录；本机SHA-256用于恢复和去重，不代表取得源端哈希。' }}</p>
        </details>
      </div>
    </CompactRecord>
    <InfiniteLoad :has-more="hasMore" :busy="loadingMore" :blocked="!ready() || loading || !!busy || !!error || !!historyError" :version="offset" :context="`${accountId ?? ''}:${active}`" loading-label="正在加载历史任务…" @load="loadMore" />
    <p v-if="historyError && hasMore && tasks.length" class="history-retry">{{ historyError }} <button class="btn" :disabled="pending || !ready()" @click="loadMore">重试加载历史记录</button></p>
    <p v-else-if="tasks.length && !hasMore" class="history-end">已显示全部历史任务</p>
  </section>
</template>
<style scoped>
.tasks-toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:22px}.tasks-toolbar h2{font-size:15px;font-weight:550;color:#4c6e88;margin:0 0 6px}.tasks-toolbar>.btn{flex-shrink:0;font-size:10px}.tasks-empty{text-align:center;padding:55px 20px;color:#96aab9;font-size:11px;line-height:1.9;background:white;border:1px solid #e1eaf1;border-radius:8px}.download-task{background:#fff;border:1px solid #e0e9f1;border-radius:8px;padding:0;margin-bottom:8px}
.task-facts{display:grid;grid-template-columns:70px minmax(0,1fr);gap:12px 16px;margin:0;font-size:11px;line-height:1.8}.task-facts dt{color:#8ba1b1}.task-facts dd{margin:0;min-width:0;color:#526e83;overflow-wrap:anywhere}.task-facts code{font:10px/1.9 Consolas,"Cascadia Code",monospace}.automation-state{display:inline-block;margin-left:10px;padding:0 6px;border-radius:3px;background:#f1f5f8;color:#8b9eac;font-size:10px}.task-muted{margin-left:10px;color:#96a9b7;font-size:10px}
.task-controls{display:flex;align-items:center;flex-wrap:wrap;gap:10px 14px;margin-top:16px}.task-controls .btn{font-size:10px}.task-controls small{font-size:10px;color:#8ba1b1}.task-files{border-top:1px solid #eaf0f5;padding-top:14px;margin-top:18px}.task-files>summary,.task-proof>summary{cursor:pointer;font-size:11px;color:#7e9ab0}.download-item{display:flex;justify-content:space-between;gap:12px;padding:14px 0;border-bottom:1px solid #edf2f6;min-width:0}.download-item-content{min-width:0}.download-item strong{font-size:11px;font-weight:500;overflow-wrap:anywhere}.download-item small{display:block;font-size:10px;line-height:1.9;color:#8ea5b6}.download-item code{font-size:10px;line-height:1.9;overflow-wrap:anywhere}.item-result{font-size:10px;color:#7c9e8e;white-space:nowrap}.item-evidence{margin-top:8px;font-size:10px;color:#8ea5b6}.item-evidence>summary{cursor:pointer}.task-proof{margin-top:14px}.task-proof>summary{font-size:10px;color:#96a9b7}.task-proof p{font-size:10px;color:#8fa5b5;line-height:1.9;margin:8px 0 0}
@media(max-width:480px){.tasks-toolbar{flex-direction:column;align-items:stretch;gap:10px}.tasks-toolbar>div{width:100%;min-width:0}.tasks-toolbar>.btn{align-self:flex-end}.task-facts{grid-template-columns:60px minmax(0,1fr);gap:10px}.download-item{flex-direction:column;gap:6px}.automation-state{margin-left:6px}}
</style>
