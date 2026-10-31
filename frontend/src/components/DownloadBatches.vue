<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { formatMediaBytes, originalMessageLink } from '../library'
import { taskLabels, type DownloadTask } from '../downloadTasks'
import { batchStateLabels, validBatchStatus, validBatchTaskDetail, type DownloadBatchStatus } from '../downloadBatchStatus'
import AppIcon from './AppIcon.vue'
import CompactRecord from './CompactRecord.vue'
import InfiniteLoad from './InfiniteLoad.vue'
import { mergeTaskHistory } from '../taskHistory'

const props = withDefaults(defineProps<{ authorized: boolean; available: boolean; accountId: string | null; active?: boolean; refreshVersion?: number }>(), { active: true })
const emit = defineEmits<{ accessError: [cause: ApiError] }>()
const batches = ref<DownloadBatchStatus[]>([])
const offset = ref(0), loading = ref(false), pending = ref(false), busy = ref(''), error = ref(''), workerWarning = ref('')
const hasMore = ref(true), loadingMore = ref(false), historyError = ref('')
const uncertainIds = ref<string[]>([])
const detailBatchId = ref(''), detailTaskId = ref(''), taskDetail = ref<DownloadTask | null>(null), detailError = ref('')
const canAccess = computed(() => props.authorized && props.available && !!props.accountId)
const isActive = () => !disposed && props.active !== false
let generation = 0, detailGeneration = 0, disposed = false, refreshQueued = false
let progressEpoch = 0, progressPending = false, progressIndex = 0
let listEpoch = 0
let timer: ReturnType<typeof setTimeout> | undefined
const limit = 10

function current(context: number, account: string | null) { return !disposed && context === generation && canAccess.value && props.accountId === account }
function stopTimer() { clearTimeout(timer); timer = undefined; progressEpoch++ }
const activeBatch = (batch: DownloadBatchStatus) => ['RUNNING', 'QUEUED', 'PAUSING'].includes(batch.state) && !uncertainIds.value.includes(batch.id)
function schedule() {
  stopTimer()
  if (isActive() && canAccess.value && !pending.value && !progressPending && batches.value.some(activeBatch)) timer = setTimeout(() => { void readProgress() }, 2000)
}
async function readProgress() {
  if (!isActive() || !canAccess.value || pending.value || progressPending) return
  const active = batches.value.filter(activeBatch)
  const selected = active.find(batch => ['RUNNING', 'PAUSING'].includes(batch.state)) ?? active[progressIndex++ % active.length]
  if (!selected) return
  const context = generation, epoch = progressEpoch, account = props.accountId
  progressPending = true
  try {
    const result = await api<DownloadBatchStatus>('/transfers/download/batches/' + encodeURIComponent(selected.id))
    if (!current(context, account) || !isActive() || epoch !== progressEpoch) return
    if (!validBatchStatus(result, selected) || result.id !== selected.id) return
    batches.value = batches.value.map(batch => batch.id === result.id && JSON.stringify(batch) !== JSON.stringify(result) ? result : batch)
  } catch (cause) { if (current(context, account) && epoch === progressEpoch) reportAccessError(cause) }
  finally { progressPending = false; if (refreshQueued && isActive() && canAccess.value && !pending.value) void load(); else schedule() }
}
function finish() {
  pending.value = false
  if (disposed) return
  if (refreshQueued && isActive() && canAccess.value) { refreshQueued = false; void load() } else schedule()
}
function reportAccessError(cause: unknown) { if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause) }
function clearDetail() { detailGeneration++; detailBatchId.value = ''; detailTaskId.value = ''; taskDetail.value = null; detailError.value = '' }
function applyBatch(result: DownloadBatchStatus) {
  const previous = batches.value.find(batch => batch.id === result.id)
  if (detailBatchId.value === result.id) {
    const before = previous?.tasks.find(task => task.id === detailTaskId.value)
    const after = result.tasks.find(task => task.id === detailTaskId.value)
    if (JSON.stringify(before) !== JSON.stringify(after)) { taskDetail.value = null; detailError.value = '子任务状态已更新，请重新读取文件明细。' }
  }
  batches.value = batches.value.map(batch => batch.id === result.id ? result : batch)
}
async function load(append = false) {
  if (!isActive() || !canAccess.value || append && (!hasMore.value || uncertainIds.value.length)) return
  if (pending.value || progressPending) { if (!append) refreshQueued = true; return }
  refreshQueued = false
  const start = append ? offset.value : 0
  const context = generation, account = props.accountId, epoch = listEpoch, previous = [...batches.value], previousOffset = offset.value
  const detailBatch = detailBatchId.value, detailTask = detailTaskId.value, detailRequest = detailGeneration
  const alive = () => current(context, account) && isActive() && epoch === listEpoch
  stopTimer(); pending.value = true; loading.value = !batches.value.length; loadingMore.value = append
  try {
    const updates = new Map<string, DownloadBatchStatus>(); let lastSize = 0, warning = ''
    const end = append ? start + limit : Math.max(limit, previousOffset, previous.length)
    for (let position = start; position < end; position += limit) {
      if (!alive()) return
      const result = await api<{ items: DownloadBatchStatus[]; workerWarning: string | null }>('/transfers/download/batches?limit=10&offset=' + position)
      if (!alive()) return
      if (!result || !Array.isArray(result.items) || result.items.length > limit || new Set(result.items.map(batch => batch?.id)).size !== result.items.length
        || result.items.some(batch => !validBatchStatus(batch, previous.find(old => old.id === batch?.id)))
        || result.workerWarning !== null && typeof result.workerWarning !== 'string') throw new Error('批量下载记录无法确认，请刷新核对。')
      for (const batch of result.items) updates.set(batch.id, batch)
      const receivedMembers = [...updates.values()].flatMap(batch => batch.tasks.map(task => task.id))
      if (new Set(receivedMembers).size !== receivedMembers.length) throw new Error('批次子任务归属无法确认，请刷新核对。')
      lastSize = result.items.length; warning = result.workerWarning ?? ''
      if (lastSize < limit) break
    }
    if (!append) for (const old of previous) if (!updates.has(old.id)) {
      if (!alive()) return
      const batch = await api<DownloadBatchStatus>('/transfers/download/batches/' + encodeURIComponent(old.id))
      if (!alive()) return
      if (!validBatchStatus(batch, old) || batch.id !== old.id) throw new Error('已有批次无法确认，请手动刷新核对。')
      updates.set(batch.id, batch)
    }
    const incoming = [...updates.values()], newHead = incoming.some(item => !previous.some(old => old.id === item.id))
    const merged = mergeTaskHistory(append ? batches.value : previous, incoming)
    const memberIds = merged.flatMap(batch => batch.tasks.map(task => task.id))
    if (new Set(memberIds).size !== memberIds.length) throw new Error('批次子任务归属无法确认，请刷新核对。')
    let detail: DownloadTask | undefined
    const selected = !append && detailBatch && detailTask ? merged.find(batch => batch.id === detailBatch) : undefined
    if (selected) {
      if (!alive()) return
      const result = await api<DownloadTask>('/transfers/download/tasks/' + encodeURIComponent(detailTask))
      if (!alive()) return
      if (!validBatchTaskDetail(result, selected, detailTask)) throw new Error('已打开的文件明细无法确认，请手动刷新核对。')
      detail = result
    }
    if (append) for (const resultBatch of incoming) applyBatch(resultBatch)
    batches.value = merged; workerWarning.value = warning
    if (detail && detailRequest === detailGeneration && detailBatchId.value === detailBatch && detailTaskId.value === detailTask) { taskDetail.value = detail; detailError.value = '' }
    if (append) historyError.value = ''
    if (append || !previous.length) { offset.value = start + lastSize; hasMore.value = lastSize === limit }
    else if (newHead) hasMore.value = lastSize === limit
    if (detailBatchId.value && !batches.value.some(batch => batch.id === detailBatchId.value)) clearDetail()
    if (!uncertainIds.value.length) error.value = ''
  } catch (cause) {
    if (!alive()) return
    error.value = cause instanceof Error ? cause.message : '批量下载记录读取未完成。'
    if (append) historyError.value = error.value
    reportAccessError(cause)
  } finally { if (current(context, account)) { loading.value = false; loadingMore.value = false }; finish() }
}
async function readStatus(batch: DownloadBatchStatus, context: number, account: string | null) {
  const result = await api<DownloadBatchStatus>('/transfers/download/batches/' + encodeURIComponent(batch.id))
  if (!current(context, account)) return false
  if (!validBatchStatus(result, batch) || result.id !== batch.id) throw new Error('批量下载状态无法确认，请刷新核对。')
  applyBatch(result); uncertainIds.value = uncertainIds.value.filter(id => id !== batch.id)
  return true
}
async function checkBatch(batch: DownloadBatchStatus) {
  const saved = batches.value.find(item => item.id === batch.id)
  if (!saved || !isActive() || !canAccess.value || pending.value) return
  const context = generation, account = props.accountId
  stopTimer(); pending.value = true; busy.value = batch.id
  try { if (await readStatus(saved, context, account)) error.value = uncertainIds.value.length ? '其他批次仍需核对，操作不会自动重试。' : '' }
  catch (cause) { if (current(context, account)) { error.value = '状态核对未完成，请刷新核对；不会自动重试操作。'; reportAccessError(cause) } }
  finally { if (current(context, account)) busy.value = ''; finish() }
}
async function action(batch: DownloadBatchStatus, verb: 'pause' | 'resume') {
  const saved = batches.value.find(item => item.id === batch.id)
  if (!['pause', 'resume'].includes(verb) || !saved || !isActive() || !canAccess.value || pending.value || uncertainIds.value.includes(saved.id) || (verb === 'pause' ? !saved.canPause : !saved.canResume)) return
  const context = generation, account = props.accountId
  stopTimer(); pending.value = true; busy.value = saved.id; error.value = ''
  try {
    const result = await api<DownloadBatchStatus>(`/transfers/download/batches/${encodeURIComponent(saved.id)}/${verb}`, { method: 'POST' })
    if (!current(context, account)) return
    if (!validBatchStatus(result, saved) || result.id !== saved.id) throw new Error('批次操作回执无法确认。')
    applyBatch(result)
  } catch (cause) {
    if (!current(context, account)) return
    if (!uncertainIds.value.includes(saved.id)) uncertainIds.value.push(saved.id)
    error.value = '操作结果尚未确认，正在核对当前状态；不会自动重试。'
    reportAccessError(cause)
    if (isActive() && !(cause instanceof ApiError && [401, 403].includes(cause.status))) {
      try { await readStatus(saved, context, account) } catch { /* Leave the batch locked until an explicit GET succeeds. */ }
    }
    if (current(context, account)) error.value = uncertainIds.value.includes(saved.id) ? '操作结果尚未确认，请先核对批次状态；不会自动重试。' : '操作回执未确认，已读取当前状态，请核对后再操作；不会自动重试。'
  } finally { if (current(context, account)) busy.value = ''; finish() }
}
async function showTask(batch: DownloadBatchStatus, taskId: string, refresh = false) {
  const saved = batches.value.find(item => item.id === batch.id)
  if (!saved || !saved.tasks.some(task => task.id === taskId) || !isActive() || !canAccess.value || pending.value) return
  if (!refresh && detailBatchId.value === saved.id && detailTaskId.value === taskId) { clearDetail(); return }
  clearDetail(); detailBatchId.value = saved.id; detailTaskId.value = taskId
  const request = detailGeneration, context = generation, account = props.accountId
  stopTimer(); pending.value = true
  try {
    const result = await api<DownloadTask>('/transfers/download/tasks/' + encodeURIComponent(taskId))
    if (!current(context, account) || request !== detailGeneration) return
    if (!validBatchTaskDetail(result, saved, taskId)) throw new Error('子任务文件明细与当前批次不一致，请刷新核对。')
    taskDetail.value = result
  } catch (cause) {
    if (current(context, account) && request === detailGeneration) { detailError.value = cause instanceof Error ? cause.message : '读取文件明细未完成。'; reportAccessError(cause) }
  } finally { finish() }
}
function loadMore() { return load(true) }
function description(batch: DownloadBatchStatus) {
  return `${batch.snapshot.postCount} 条主帖 · 已保存 ${batch.completed}/${batch.snapshot.total}${batch.duplicates ? ' · 其中重复 ' + batch.duplicates : ''}${batch.skipped ? ' · 历史跳过 ' + batch.skipped : ''} · 子任务完成 ${batch.completedTasks}/${batch.totalTasks}${batch.failedTasks ? ' · 失败 ' + batch.failedTasks : ''}`
}
function batchAlert(batch: DownloadBatchStatus) {
  return uncertainIds.value.includes(batch.id) ? '操作结果尚未确认，先核对状态。' : batch.failedTasks ? `${batch.failedTasks} 个子任务需要处理，其他子任务状态分别保留。` : batch.state === 'PAUSING' ? '等待工作任务停稳，已确认保存数和字节仍可能更新。' : null
}

watch([() => props.authorized, () => props.available, () => props.accountId], () => {
  generation++; stopTimer(); batches.value = []; offset.value = 0; hasMore.value = true; loadingMore.value = false; historyError.value = ''; error.value = ''; workerWarning.value = ''; busy.value = ''; loading.value = false; uncertainIds.value = []; clearDetail()
  if (pending.value) refreshQueued = true
  else if (canAccess.value) void load()
}, { immediate: true })
watch([() => props.active, () => props.refreshVersion], () => {
  listEpoch++; stopTimer(); refreshQueued = isActive() && canAccess.value
  if (refreshQueued && !pending.value) void load()
})
onBeforeUnmount(() => { disposed = true; generation++; stopTimer(); clearDetail() })
</script>

<template>
  <section class="download-batches" aria-label="频道批量下载">
    <header class="batch-toolbar"><div><h2>频道批量下载</h2></div><button class="btn" type="button" :disabled="!canAccess || pending" @click="load()"><AppIcon name="refresh" />刷新批次</button></header>
    <div v-if="!authorized || !accountId" class="notice warning">完成 Telegram 授权后查看当前账号的批量下载。</div><div v-else-if="!available" class="notice warning">本机服务当前不可用，批次读取已暂停。</div>
    <div v-if="workerWarning" class="notice warning" role="status">{{ workerWarning }}</div><div v-if="error" class="notice danger" role="alert">{{ error }}</div>
    <p v-if="loading && !batches.length" class="batch-empty" role="status">正在读取批量下载记录…</p><p v-else-if="canAccess && !batches.length && !error" class="batch-empty">暂无批量下载记录。可在频道扫描结果中选择主帖，准备并确认下载清单。</p>
    <CompactRecord v-for="batch in batches" :key="batch.id" :title="batch.snapshot.channelTitle || '频道批量下载'" :description="description(batch)" :status="batchStateLabels[batch.state]" :tone="batch.state === 'COMPLETED' ? 'success' : batch.failedTasks || batch.state === 'FAILED' ? 'warning' : ['RUNNING', 'QUEUED', 'PAUSING'].includes(batch.state) ? 'active' : 'neutral'" :timestamp="batch.updatedAt" :default-expanded="false" :active="active" :alert="batchAlert(batch)">
      <template #actions><span class="batch-byte-summary">客户端已取得 {{ formatMediaBytes(batch.downloadedBytes) }} · 目标已写入 {{ formatMediaBytes(batch.writtenBytes) }} / {{ formatMediaBytes(batch.snapshot.totalBytes) }}</span><button v-if="batch.canPause" class="btn" type="button" :disabled="pending || uncertainIds.includes(batch.id)" @click="action(batch, 'pause')">暂停整批</button><span v-else-if="batch.state === 'PAUSING'" class="batch-pausing">正在暂停整批…</span><button v-if="batch.canResume" class="btn" type="button" :disabled="pending || uncertainIds.includes(batch.id)" @click="action(batch, 'resume')">继续暂停 / 失败项</button><button v-if="uncertainIds.includes(batch.id)" class="btn" type="button" :disabled="pending" @click="checkBatch(batch)">核对批次状态</button></template>
      <p class="batch-directory">保存根目录：<code>{{ batch.snapshot.targetDirectory }}</code></p><p class="batch-note">仅包含创建时冻结的 {{ batch.snapshot.postCount }} 条主帖、{{ batch.snapshot.total }} 个媒体。重复媒体已保存数包含在“已保存”中；历史跳过不表示本次保存了新文件。</p>
      <ul class="batch-members"><li v-for="task in batch.tasks" :key="task.id"><div class="batch-member-summary"><div><strong>{{ task.title || '未命名主帖' }}</strong><p>{{ task.pauseRequested && task.state === 'RUNNING' ? '正在暂停' : taskLabels[task.state] }} · 已保存 {{ task.completed }}/{{ task.total }}<span v-if="task.duplicates"> · 其中重复 {{ task.duplicates }}</span><span v-if="task.skipped"> · 历史跳过 {{ task.skipped }}</span></p><small>客户端已取得 {{ formatMediaBytes(task.downloadedBytes) }} · 目标已写入 {{ formatMediaBytes(task.writtenBytes) }} / {{ formatMediaBytes(task.totalBytes) }}</small><p v-if="task.error" class="batch-member-error">{{ task.error }}</p></div><button class="btn" type="button" :disabled="pending" @click="showTask(batch, task.id)">{{ detailBatchId === batch.id && detailTaskId === task.id ? '收起文件明细' : '查看文件明细' }}</button></div>
        <div v-if="detailBatchId === batch.id && detailTaskId === task.id" class="batch-file-detail"><p v-if="pending && !taskDetail" role="status">正在读取文件明细…</p><p v-if="detailError" class="batch-member-error" role="status">{{ detailError }}</p><button class="btn" type="button" :disabled="pending" @click="showTask(batch, task.id, true)">刷新文件明细</button><template v-if="taskDetail"><p>文件明细状态：{{ taskLabels[taskDetail.state] }} · 已保存 {{ taskDetail.completed }}/{{ taskDetail.total }}</p><div v-for="item in taskDetail.items" :key="item.id" class="batch-file"><strong>{{ item.fileName }}</strong><small>{{ taskLabels[item.state] }} · {{ formatMediaBytes(item.size) }} · 客户端 {{ formatMediaBytes(item.downloadedBytes) }} · 写入 {{ formatMediaBytes(item.writtenBytes) }}</small><code v-if="item.state === 'COMPLETED'">{{ item.outputPath }}</code><small v-if="item.state === 'COMPLETED' && item.duplicate">重复媒体已单独保存</small><small v-if="item.manifestPath">来源清单：<code>{{ item.manifestPath }}</code></small><small v-if="item.history">按历史跳过；原保存位置可能已移动：<code>{{ item.history.path }}</code></small><small v-if="originalMessageLink(item.sourceLink)"><a :href="originalMessageLink(item.sourceLink)" target="_blank" rel="noopener noreferrer">媒体原消息 ↗</a></small><small v-if="item.error" class="batch-member-error">{{ item.error }}</small></div></template></div>
      </li></ul>
    </CompactRecord>
    <InfiniteLoad :has-more="hasMore" :busy="loadingMore" :blocked="!isActive() || !canAccess || pending || !!error || !!historyError || !!uncertainIds.length" :version="offset" :context="`${accountId ?? ''}:${active}`" loading-label="正在加载历史批次…" @load="loadMore" />
    <p v-if="historyError && hasMore && batches.length" class="history-retry">{{ historyError }} <button class="btn" :disabled="pending || !isActive() || !canAccess || !!uncertainIds.length" @click="loadMore">重试加载历史记录</button></p><p v-else-if="batches.length && !hasMore" class="history-end">已显示全部历史批次</p>
  </section>
</template>

<style scoped>
.download-batches{margin:0 0 30px}.batch-toolbar{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:16px}.batch-toolbar h2{font-size:15px;color:#506f87;font-weight:550;margin:0 0 6px}.batch-toolbar p,.batch-empty{font-size:10px;line-height:1.9;color:#93a9b9}.batch-toolbar>.btn{font-size:10px;flex-shrink:0}.batch-empty{padding:23px;background:#fff;border:1px solid #e1eaf1;border-radius:7px;text-align:center}.batch-byte-summary{font-size:9px;line-height:1.8;color:#8da5b7;align-self:center;margin-right:auto}.batch-pausing{font-size:10px;color:#a68b62;align-self:center}.batch-directory,.batch-note{font-size:10px;line-height:1.9;color:#849db0;overflow-wrap:anywhere;margin:0 0 10px}.batch-directory code,.batch-file code{font-size:9px;overflow-wrap:anywhere}.batch-note{font-size:9px;color:#9cafbd}.batch-members{list-style:none;margin:15px 0 0;padding:0}.batch-members>li{border-top:1px solid #e8eff5;padding:12px 0}.batch-member-summary{display:flex;justify-content:space-between;gap:15px;align-items:flex-start}.batch-member-summary>div{min-width:0;flex:1}.batch-member-summary strong{font-size:11px;font-weight:550;color:#68889f;line-height:1.8;overflow-wrap:anywhere}.batch-member-summary p,.batch-member-summary small{font-size:9px;color:#95aabb;line-height:1.9;margin-top:4px}.batch-member-summary small{display:block}.batch-member-summary>.btn,.batch-file-detail>.btn{font-size:9px;min-height:28px;flex-shrink:0}.batch-member-summary .batch-member-error,.batch-member-error{font-size:10px;color:#af8c5e;line-height:1.9;overflow-wrap:anywhere}.batch-file-detail{padding:12px;background:#f8fafc;border:1px solid #e8eef4;border-radius:6px;margin-top:10px;font-size:10px;color:#8ca3b5;line-height:1.9}.batch-file-detail>p{margin:5px 0 9px}.batch-file{border-top:1px solid #e6edf3;padding:11px 0}.batch-file strong,.batch-file small,.batch-file>code{display:block;overflow-wrap:anywhere}.batch-file strong{font-size:10px;font-weight:500;color:#7b95a8}.batch-file small{font-size:9px}.batch-file a{color:#5e91b5;text-decoration:none}.batch-pages{display:flex;align-items:center;justify-content:flex-end;flex-wrap:wrap;gap:10px;margin-top:15px}.batch-pages>.btn{font-size:10px}.batch-pages>span{font-size:9px;color:#9db0be}@media(max-width:650px){.batch-toolbar{align-items:flex-start}.batch-toolbar p{font-size:9px}.batch-toolbar>.btn{font-size:9px}.batch-byte-summary{flex-basis:100%}.batch-member-summary{flex-direction:column;gap:8px}.batch-member-summary>.btn{align-self:flex-end}.batch-file-detail{padding:10px}.batch-pages{gap:7px}.batch-pages>.btn{font-size:9px}}
@media(max-width:480px){.batch-toolbar{flex-direction:column;align-items:stretch;gap:10px}.batch-toolbar>div{width:100%;min-width:0}.batch-toolbar>.btn{align-self:flex-end}}
</style>
