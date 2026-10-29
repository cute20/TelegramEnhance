<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import type { ImportChannels, ImportPreview, ImportProgress, ImportedPost, ImportRun } from '../channelImport'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ authorized: boolean; available: boolean; accountId: string | null }>()
const emit = defineEmits<{ settings: []; accessError: [cause: ApiError] }>()
const channels = ref<ImportChannels | null>(null)
const selectedChannelId = ref('')
const preview = ref<ImportPreview | null>(null)
const previewReady = ref(false)
const pageCursors = ref<(string | null)[]>([null])
const pageIndex = ref(0)
const selectedMessages = ref<string[]>([])
const importedMessages = ref<string[]>([])
const activeMessage = ref('')
const progress = ref<ImportProgress | null>(null)
const importWarnings = ref<string[]>([])
const fullRun = ref<ImportRun | null>(null)
const runKnown = ref(false)
const fullNeedsCheck = ref(false)
const stopRequested = ref(false)
const busy = ref<'channels' | 'preview' | 'import' | 'state' | 'full' | ''>('')
const error = ref('')
const notice = ref('')
const canAccess = computed(() => props.authorized && props.available && !!props.accountId)
const controlsEnabled = computed(() => canAccess.value && !busy.value)
const selectedChannel = computed(() => channels.value?.items.find(channel => channel.id === selectedChannelId.value))
const selectedItems = computed(() => preview.value?.items.filter(item => selectedMessages.value.includes(item.messageId)) ?? [])
const warnings = computed(() => [...new Set([...(channels.value?.warnings ?? []), ...(preview.value?.warnings ?? []), ...importWarnings.value,
  ...(fullRun.value?.warning ? [fullRun.value.warning] : []),
  ...(fullRun.value?.incomplete ? ['主帖待核实 ' + fullRun.value.incomplete + ' 条，具体媒体范围请查看各主帖的读取状态。'] : []),
])])
const fullButtonLabel = computed(() => fullRun.value ? '继续导入全部主帖' : '导入频道全部主帖')
let generation = 0
let disposed = false

function clearBatch(resetPages = true) {
  preview.value = null; previewReady.value = false; progress.value = null
  selectedMessages.value = []; importedMessages.value = []; activeMessage.value = ''
  if (resetPages) { pageCursors.value = [null]; pageIndex.value = 0 }
  importWarnings.value = []; error.value = ''; notice.value = ''; stopRequested.value = false
}
function clearRun() { fullRun.value = null; runKnown.value = false; fullNeedsCheck.value = false }
function validRun(result: ImportRun | null, channelId: string): result is ImportRun {
  return !!result && result.channelId === channelId && typeof result.runId === 'string' && !!result.runId.trim()
    && ['READY', 'COMPLETE', 'BLOCKED'].includes(result.status)
    && [result.revision, result.processed, result.created, result.updated, result.incomplete].every(value => Number.isSafeInteger(value) && value >= 0)
    && result.incomplete <= result.processed
    && typeof result.exhausted === 'boolean' && (result.status !== 'COMPLETE' || result.exhausted)
    && (result.warning === null || typeof result.warning === 'string')
}
async function readRunState(isCurrent: () => boolean, channelId: string) {
  const query = new URLSearchParams({ channelId })
  const result = await api<ImportRun | null>('/library/import/all/state?' + query, { timeout: 35000 })
  if (!isCurrent()) return
  if (result !== null && !validRun(result, channelId)) throw new Error('导入进度无法确认，请刷新进度。')
  fullRun.value = result; runKnown.value = true; fullNeedsCheck.value = false
}
async function execute(name: Exclude<typeof busy.value, ''>, action: (isCurrent: () => boolean, accountId: string) => Promise<void>) {
  if (!controlsEnabled.value || !props.accountId) return
  const request = ++generation
  const accountId = props.accountId, channelId = selectedChannelId.value
  const isCurrent = () => !disposed && request === generation && canAccess.value && props.accountId === accountId && selectedChannelId.value === channelId
  busy.value = name; error.value = ''; notice.value = ''
  try { await action(isCurrent, accountId) }
  catch (cause) {
    if (!isCurrent()) return
    if (name === 'import') {
      if (progress.value) progress.value = { ...progress.value, status: 'failed' }
      const message = cause instanceof ApiError ? cause.message : '未取得有效回执（请求可能超时或中断），该条主帖可能仍在后台保存。'
      error.value = '当前条目未完成确认，导入已暂停。' + message + ' 请返回资源库刷新核对，或重新读取本页后再导入；已保存主帖会去重。'
    } else if (name === 'full') {
      fullNeedsCheck.value = true; runKnown.value = false
      error.value = '连续导入已暂停，当前步骤可能仍在后台保存。正在核对持久化进度，不会自动重试导入。'
      if (!(cause instanceof ApiError && [401, 403].includes(cause.status))) {
        try { await readRunState(isCurrent, channelId) } catch { /* Keep the last confirmed state until a user refresh succeeds. */ }
      }
      if (!isCurrent()) return
      error.value = runKnown.value ? '连续导入已暂停，已核对持久化进度。请确认后手动继续，不会自动重试。' : '连续导入已暂停，当前步骤可能仍在后台保存。请先刷新进度核对，再手动继续；不会自动重试。'
    } else error.value = cause instanceof Error ? cause.message : '读取未完成，请重试。'
    if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
  } finally { if (isCurrent()) busy.value = '' }
}
function loadChannels() {
  if (!controlsEnabled.value) return
  channels.value = null; selectedChannelId.value = ''; clearBatch(); clearRun()
  return execute('channels', async isCurrent => {
    const result = await api<ImportChannels>('/library/import/channels', { timeout: 60000 })
    if (!isCurrent()) return
    if (result?.scope !== 'MANAGED_CHANNELS' || !Array.isArray(result.items)
      || result.items.some(channel => !channel || typeof channel.id !== 'string' || !channel.id || typeof channel.title !== 'string')) throw new Error('频道列表格式无效，请刷新后重试。')
    channels.value = result
  })
}
function selectChannel(id: string) {
  if (!controlsEnabled.value || selectedChannelId.value === id || !channels.value?.items.some(channel => channel.id === id)) return
  generation++; selectedChannelId.value = id; clearBatch(); clearRun()
  return refreshRun()
}
function refreshRun() {
  if (!controlsEnabled.value || !selectedChannel.value) return
  runKnown.value = false
  return execute('state', isCurrent => readRunState(isCurrent, selectedChannelId.value))
}
function loadPage(cursor: string | null, index: number, cursors: (string | null)[]) {
  const channel = selectedChannel.value
  if (!controlsEnabled.value || !channel) return
  clearBatch(false); pageIndex.value = index; pageCursors.value = cursors
  return execute('preview', async isCurrent => {
    const result = await api<ImportPreview>('/library/import/history', { method: 'POST', body: { channelId: channel.id, cursor }, timeout: 60000 })
    if (!isCurrent()) return
    if (result?.scope !== 'CHANNEL_PAGE' || result.limit !== 10 || result.channel?.id !== channel.id || !Array.isArray(result.items)
      || result.items.length > 10 || result.items.some(item => !item || typeof item.messageId !== 'string' || !/^[1-9]\d*$/.test(item.messageId))
      || typeof result.exhausted !== 'boolean' || typeof result.hasMore !== 'boolean'
      || (result.nextCursor !== null && (typeof result.nextCursor !== 'string' || !result.nextCursor))
      || (!result.exhausted && result.hasMore && (result.nextCursor === null || result.nextCursor === cursor))) throw new Error('主帖页面无法确认，请重新读取本页。')
    preview.value = result; previewReady.value = true
    selectedMessages.value = result.items.map(item => item.messageId)
  })
}
function previewRecent() { return loadPage(null, 0, [null]) }
function reloadPage() { return loadPage(pageCursors.value[pageIndex.value] ?? null, pageIndex.value, [...pageCursors.value]) }
function nextPage() {
  if (!preview.value?.hasMore || preview.value.exhausted || !preview.value.nextCursor) return
  const cursors = pageCursors.value.slice(0, pageIndex.value + 1)
  cursors.push(preview.value.nextCursor)
  return loadPage(preview.value.nextCursor, pageIndex.value + 1, cursors)
}
function previousPage() {
  if (pageIndex.value > 0) return loadPage(pageCursors.value[pageIndex.value - 1] ?? null, pageIndex.value - 1, [...pageCursors.value])
}
function togglePost(id: string) {
  if (!controlsEnabled.value || !previewReady.value || !preview.value?.items.some(item => item.messageId === id)) return
  selectedMessages.value = selectedMessages.value.includes(id) ? selectedMessages.value.filter(value => value !== id) : [...selectedMessages.value, id]
}
function selectPage(all: boolean) { if (controlsEnabled.value && previewReady.value) selectedMessages.value = all ? preview.value?.items.map(item => item.messageId) ?? [] : [] }
function importBatch() {
  const batch = preview.value, items = [...selectedItems.value]
  if (!controlsEnabled.value || !previewReady.value || !batch || !items.length || batch.channel.id !== selectedChannelId.value) return
  previewReady.value = false; stopRequested.value = false; importWarnings.value = []; importedMessages.value = []
  progress.value = { completed: 0, total: items.length, created: 0, updated: 0, status: 'running' }
  return execute('import', async (isCurrent, accountId) => {
    for (const item of items) {
      if (!isCurrent()) return
      if (stopRequested.value) break
      activeMessage.value = item.messageId
      const result = await api<ImportedPost>('/library/import/posts', { method: 'POST', body: { channelId: batch.channel.id, messageId: item.messageId }, timeout: 90000 })
      if (!isCurrent()) return
      const saved = result?.post
      if (!saved || saved.accountId !== accountId || saved.channelId !== batch.channel.id || typeof result.created !== 'boolean'
        || typeof saved.key !== 'string' || !saved.key.trim()) throw new Error('导入回执无法确认。')
      const album = item.albumId != null && item.albumId !== '0'
      const matchesItem = album
        ? saved.albumId === item.albumId && Array.isArray(saved.mainMessages) && saved.mainMessages.some(message => message?.chatId === batch.channel.id && message.messageId === item.messageId)
        : saved.mainMessageId === item.messageId && (saved.albumId === '0' || saved.albumId === null)
      if (!matchesItem) throw new Error('导入回执无法确认。')
      const previous = progress.value!
      progress.value = { ...previous, completed: previous.completed + 1, created: previous.created + (result.created ? 1 : 0), updated: previous.updated + (result.created ? 0 : 1) }
      importedMessages.value.push(item.messageId)
      importWarnings.value = [...new Set([...importWarnings.value, ...(saved.warnings ?? [])])]
      if (stopRequested.value || progress.value.completed === items.length) break
      await new Promise(resolve => window.setTimeout(resolve, 0))
    }
    if (!isCurrent() || !progress.value) return
    const complete = progress.value.completed === items.length
    progress.value = { ...progress.value, status: complete ? 'complete' : 'stopped' }
    notice.value = complete ? '选中主帖已导入本机资源库，已有主帖会去重。评论仍按需读取。' : '已停止后续导入，当前条目的保存结果已接收。重新读取本页后可再次选择导入。'
  })
}
function startAll(restart = false) {
  const channelId = selectedChannelId.value, previous = fullRun.value
  if (!controlsEnabled.value || !selectedChannel.value || !runKnown.value || fullNeedsCheck.value) return
  if (restart ? !previous : previous && previous.status !== 'READY') return
  stopRequested.value = false
  return execute('full', async isCurrent => {
    const body = restart && previous ? { channelId, restart: true, runId: previous.runId, revision: previous.revision } : { channelId, restart: false }
    const started = await api<ImportRun>('/library/import/all/start', { method: 'POST', body, timeout: 90000 })
    if (!isCurrent()) return
    if (!validRun(started, channelId) || (!restart && previous && (started.runId !== previous.runId || started.revision < previous.revision))
      || (restart && previous && (started.runId === previous.runId || started.revision !== 0 || started.status !== 'READY' || started.exhausted
        || started.processed !== 0 || started.created !== 0 || started.updated !== 0 || started.incomplete !== 0))) throw new Error('导入进度回执无法确认。')
    fullRun.value = started; runKnown.value = true; fullNeedsCheck.value = false
    while (isCurrent() && !stopRequested.value && fullRun.value.status === 'READY') {
      const currentRun: ImportRun = fullRun.value
      const result: ImportRun = await api<ImportRun>('/library/import/all/step', { method: 'POST', body: { channelId, runId: currentRun.runId, revision: currentRun.revision }, timeout: 90000 })
      if (!isCurrent()) return
      if (!validRun(result, channelId) || result.runId !== currentRun.runId || result.revision !== currentRun.revision + 1
        || result.processed < currentRun.processed || result.processed > currentRun.processed + 1
        || result.created < currentRun.created || result.updated < currentRun.updated) throw new Error('导入进度回执无法确认。')
      fullRun.value = result
      if (stopRequested.value || result.status !== 'READY') break
      await new Promise(resolve => window.setTimeout(resolve, 0))
    }
    if (!isCurrent()) return
    notice.value = fullRun.value.status === 'COMPLETE' ? '频道主帖历史已读到边界，已有主帖及相册已去重。媒体范围以各主帖读取状态为准，评论仍按需读取。'
      : fullRun.value.status === 'BLOCKED' ? '导入已暂停，请查看提示。核对后可显式从头重新导入。' : '已停止后续导入，进度以当前持久化记录为准，下次可继续。'
  })
}
function stopImport() { if (busy.value === 'import' || busy.value === 'full') stopRequested.value = true }

watch([() => props.authorized, () => props.available, () => props.accountId], () => {
  generation++; busy.value = ''; channels.value = null; selectedChannelId.value = ''; clearBatch(); clearRun()
  if (canAccess.value) void loadChannels()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++; stopRequested.value = true })
</script>

<template>
  <section class="channel-import" aria-labelledby="channel-import-heading">
    <header class="import-heading"><div><h2 id="channel-import-heading">导入频道</h2><p>浏览主帖后选择导入，或一键连续导入所选频道的全部主帖。</p></div><button class="btn" type="button" :disabled="!controlsEnabled" @click="loadChannels"><AppIcon name="refresh" />刷新频道</button></header>
    <p class="import-scope"><AppIcon name="info" />从本次加载的主聊天、归档及已索引范围中选择可管理频道，可能未列出所有频道。主帖与相册会去重；评论仍按需读取。</p>
    <div v-if="!authorized || !accountId" class="notice warning" role="status"><AppIcon name="lock" /><span>完成 Telegram 授权后，选择自己创建或管理的频道。</span><button class="btn" type="button" @click="emit('settings')">前往设置</button></div>
    <div v-else-if="!available" class="notice warning" role="status"><AppIcon name="info" /><span>本机服务暂不可用，导入已暂停。恢复后请重新核对页面及导入进度。</span></div>
    <div v-if="error" class="notice danger" role="alert"><AppIcon name="info" /><span>{{ error }}</span></div>
    <div v-if="notice" class="notice success" role="status"><AppIcon name="check" /><span>{{ notice }}</span></div>
    <div v-if="warnings.length || channels?.partial" class="import-warnings" role="status"><p v-if="channels?.partial">当前频道列表只有部分结果，可稍后刷新。</p><ul v-if="warnings.length"><li v-for="warning in warnings" :key="warning">{{ warning }}</li></ul></div>
    <div v-if="canAccess" class="import-layout">
      <aside class="import-channel-panel" aria-label="选择自己的频道"><h3>选择频道</h3><p v-if="busy === 'channels'" class="import-empty" role="status">正在读取可管理的频道…</p><p v-else-if="!channels" class="import-empty">频道列表尚未取得，请点击“刷新频道”重试。</p><p v-else-if="!channels.items.length" class="import-empty">{{ channels.partial ? '部分列表中暂无可选择频道，可稍后刷新。' : '没有找到可选择的自有或可管理频道。' }}</p><button v-for="channel in channels?.items ?? []" :key="channel.id" :class="['import-channel', { selected: selectedChannelId === channel.id }]" type="button" :disabled="!controlsEnabled" :aria-pressed="selectedChannelId === channel.id" @click="selectChannel(channel.id)"><span>{{ (channel.title || '频').slice(0, 1) }}</span><strong>{{ channel.title || '未命名频道' }}</strong></button></aside>
      <div class="import-batch-panel">
        <div class="import-preview-heading"><div><h3>{{ selectedChannel ? '当前频道：' + (selectedChannel.title || '未命名频道') : '先选择频道' }}</h3><p>每页最多 10 条主帖，相册按一条主帖计。浏览不会保存主帖。</p></div><button class="btn" type="button" :disabled="!controlsEnabled || !selectedChannel" @click="previewRecent"><AppIcon :name="busy === 'preview' ? 'clock' : 'search'" />{{ busy === 'preview' ? '正在读取…' : '浏览主帖' }}</button></div>
        <section v-if="selectedChannel" class="import-all" aria-label="导入频道全部主帖">
          <div class="import-all-actions"><button v-if="!fullRun || fullRun.status === 'READY'" class="btn primary" type="button" :disabled="!controlsEnabled || !runKnown || fullNeedsCheck" @click="startAll()"><AppIcon :name="busy === 'full' ? 'clock' : 'library'" />{{ busy === 'full' ? '正在连续导入…' : fullButtonLabel }}</button><button v-if="fullRun" class="btn" type="button" :disabled="!controlsEnabled || !runKnown || fullNeedsCheck" @click="startAll(true)">从头重新导入全部</button><button class="btn" type="button" :disabled="!controlsEnabled" @click="refreshRun"><AppIcon name="refresh" />刷新进度</button><button v-if="busy === 'full'" class="btn" type="button" :disabled="stopRequested" @click="stopImport">{{ stopRequested ? '当前步骤完成后停止…' : '停止导入' }}</button></div>
          <p v-if="fullRun" class="import-progress" role="status" aria-live="polite">已处理 {{ fullRun.processed }} 条 · 新增 {{ fullRun.created }} · 更新 {{ fullRun.updated }}<span>{{ fullRun.status === 'COMPLETE' ? ' · 主帖历史已到边界' : fullRun.status === 'BLOCKED' ? ' · 已暂停，请查看提示' : busy === 'full' ? ' · 正在逐步保存' : ' · 可从已保存位置继续' }}</span></p>
          <p v-if="fullNeedsCheck || !runKnown" class="import-note">{{ busy === 'state' ? '正在读取持久化导入进度…' : '请先刷新进度，核对已保存位置后再继续。' }}</p><p class="import-note">从本轮首次读取的可见历史向旧导入；期间新发主帖需完成后从头重新导入。可停止并稍后继续。历史到达边界不表示主帖媒体均已核全；评论仍按需读取，从头重读也会去重。</p>
        </section>
        <p v-if="!preview" class="import-empty">点击“浏览主帖”分页选择；也可以直接导入频道全部主帖。</p>
        <template v-else>
          <p class="import-batch-count">第 {{ pageIndex + 1 }} 页 · {{ preview.items.length }} 条主帖<span>{{ preview.exhausted ? '本轮已到历史边界' : '历史边界尚未确认' }}</span></p>
          <p v-if="!preview.items.length" class="import-empty">{{ preview.exhausted ? '此页没有可列出的主帖，本轮已到历史边界。' : '此页没有可列出的主帖，可能只有服务消息；尚未到历史边界，可继续下一页。' }}</p>
          <div v-if="preview.items.length" class="import-selection-actions"><span>本页已选 {{ selectedItems.length }} / {{ preview.items.length }}</span><button class="btn" type="button" :disabled="!controlsEnabled || !previewReady" @click="selectPage(true)">全选本页</button><button class="btn" type="button" :disabled="!controlsEnabled || !previewReady" @click="selectPage(false)">取消本页选择</button></div>
          <ol v-if="preview.items.length" class="import-preview-list"><li v-for="item in preview.items" :key="item.messageId"><button :class="['import-post-select', { selected: selectedMessages.includes(item.messageId) }]" type="button" :disabled="!controlsEnabled || !previewReady" :aria-pressed="selectedMessages.includes(item.messageId)" @click="togglePost(item.messageId)">{{ selectedMessages.includes(item.messageId) ? '已选' : '选择' }}</button><div><strong>{{ item.title || '无文字主帖' }}</strong><small>{{ item.albumId && item.albumId !== '0' ? '相册主帖' : '单条主帖' }}</small></div><small v-if="importedMessages.includes(item.messageId)" class="import-item-done">已导入</small><small v-else-if="busy === 'import' && activeMessage === item.messageId">正在导入…</small><small v-else-if="progress?.status === 'failed' && activeMessage === item.messageId">已暂停</small></li></ol>
          <div class="import-batch-actions"><button class="btn primary" type="button" :disabled="!controlsEnabled || !previewReady || !selectedItems.length" @click="importBatch"><AppIcon :name="busy === 'import' ? 'clock' : 'plus'" />{{ busy === 'import' ? '正在逐条导入…' : '导入选中主帖（' + selectedItems.length + '）' }}</button><button v-if="busy === 'import'" class="btn" type="button" :disabled="stopRequested" @click="stopImport">{{ stopRequested ? '当前条目保存后停止…' : '停止导入' }}</button></div>
          <p v-if="progress" class="import-progress" role="status" aria-live="polite">本次已确认 {{ progress.completed }} / {{ progress.total }} 条 · 新增 {{ progress.created }} · 更新 {{ progress.updated }}</p>
          <div class="import-page-actions"><button class="btn" type="button" :disabled="!controlsEnabled || pageIndex === 0" @click="previousPage">上一页</button><button class="btn" type="button" :disabled="!controlsEnabled" @click="reloadPage">重新读取本页</button><button class="btn" type="button" :disabled="!controlsEnabled || !preview.hasMore || preview.exhausted || !preview.nextCursor" @click="nextPage">下一页</button></div>
          <p v-if="progress && !previewReady" class="import-note">再次选择导入前请重新读取本页，已保存主帖会去重。</p>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.channel-import{color:#7891a6}.import-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;margin-bottom:13px}.import-heading h2{font-size:15px;font-weight:550;color:#506f87;margin:0}.import-heading p{font-size:10px;color:#94a7b7;line-height:1.9;margin-top:6px}.import-heading>.btn{font-size:10px;min-height:31px;flex-shrink:0}.import-scope{display:flex;align-items:flex-start;gap:7px;font-size:10px;line-height:1.9;color:#8fa5b5;margin:0 0 20px}.import-scope>.icon{width:14px;height:14px;flex-shrink:0;margin-top:2px}.import-layout{display:grid;grid-template-columns:220px minmax(0,1fr);gap:22px;align-items:start}.import-channel-panel,.import-batch-panel{background:#fff;border:1px solid #e1e9f0;border-radius:8px;padding:18px}.import-channel-panel h3,.import-preview-heading h3{font-size:11px;font-weight:550;color:#708da3;margin:0 0 13px}.import-channel{display:flex;align-items:center;gap:10px;width:100%;min-height:45px;background:#fff;border:1px solid #e6edf3;border-radius:6px;padding:9px;text-align:left;color:#879eaf;margin-top:8px}.import-channel>span{display:grid;place-items:center;flex-shrink:0;width:25px;height:25px;border-radius:6px;background:#eff4f8;color:#91a8ba;font-size:10px}.import-channel>strong{font-weight:500;font-size:11px;line-height:1.7;overflow-wrap:anywhere}.import-channel.selected{background:#233e55;border-color:#233e55;color:#fff}.import-channel.selected>span{background:#ffffff1c;color:#fff}.import-channel:not(.selected):hover:enabled{background:#f6fafd;border-color:#bfd1df}.import-empty{padding:22px 9px;text-align:center;color:#a0b0bd;font-size:10px;line-height:1.9}.import-preview-heading{display:flex;justify-content:space-between;align-items:center;gap:14px;border-bottom:1px solid #e9eff5;padding-bottom:16px;margin-bottom:16px}.import-preview-heading h3{font-size:12px;margin-bottom:5px;overflow-wrap:anywhere}.import-preview-heading p{font-size:9px;color:#9bafbd;line-height:1.9}.import-preview-heading>.btn{font-size:10px;min-height:33px;flex-shrink:0}.import-batch-count{display:flex;justify-content:space-between;gap:12px;font-size:10px;color:#8ba2b4;margin:0 0 10px}.import-batch-count>span{font-size:9px;color:#a7b6c1}.import-preview-list{list-style:none;padding:0;margin:0 0 18px}.import-preview-list>li{display:flex;align-items:center;gap:11px;padding:12px 0;border-bottom:1px solid #edf2f6;min-width:0}.import-item-number{color:#b0bfca;font-size:9px;flex:0 0 17px;text-align:center}.import-preview-list li>div{min-width:0;flex:1}.import-preview-list strong{display:block;color:#718fa5;font-size:11px;font-weight:500;line-height:1.8;overflow-wrap:anywhere}.import-preview-list small{display:block;font-size:8px;color:#a5b5c1;line-height:1.9}.import-preview-list li>small{font-size:9px;white-space:nowrap}.import-preview-list .import-item-done{color:#719580}.import-batch-actions{display:flex;flex-wrap:wrap;gap:9px}.import-batch-actions>.btn{font-size:10px;min-height:33px}.import-progress{color:#829eaf;font-size:10px;line-height:1.9;margin-top:14px}.import-progress>span{color:#a28a61}.import-note{font-size:9px;color:#a1b2bf;line-height:1.9;margin-top:9px}.import-warnings{font-size:10px;color:#a68b5d;line-height:1.9;border-left:2px solid #d9b77f;background:#fffaf1;padding:10px 13px;margin-bottom:18px;border-radius:0 5px 5px 0}.import-warnings ul{padding-left:17px;margin:0}.import-warnings p+ul{margin-top:6px}.notice>.btn{margin-left:auto;font-size:10px;flex-shrink:0}.notice>span{overflow-wrap:anywhere}
@media(max-width:900px){.import-layout{grid-template-columns:185px minmax(0,1fr);gap:15px}.import-channel-panel,.import-batch-panel{padding:15px}.import-preview-heading{align-items:flex-start;flex-direction:column;gap:10px}.import-preview-heading>.btn{align-self:flex-end}}
@media(max-width:680px){.import-layout{grid-template-columns:minmax(0,1fr)}.import-heading{align-items:flex-start}.import-heading h2{font-size:14px}.import-heading p,.import-scope{font-size:9px}.import-channel-panel{display:flex;flex-wrap:wrap;gap:7px}.import-channel-panel>h3,.import-channel-panel>.import-empty{flex-basis:100%}.import-channel{width:auto;max-width:100%;margin:0;min-height:39px}.import-channel>strong{font-size:10px}.import-preview-heading{flex-direction:row;align-items:center}.import-preview-heading h3{font-size:11px}.import-preview-heading>.btn{font-size:9px;min-height:31px}.import-batch-count{flex-wrap:wrap;gap:4px}.notice{flex-wrap:wrap}.notice>.btn{margin-left:23px}}

.import-all{border-bottom:1px solid #e6edf3;padding-bottom:16px;margin-bottom:16px}.import-all-actions,.import-selection-actions,.import-page-actions{display:flex;align-items:center;flex-wrap:wrap;gap:8px}.import-all-actions>.btn,.import-selection-actions>.btn,.import-page-actions>.btn{font-size:10px;min-height:31px}.import-selection-actions{font-size:9px;color:#94a9b8;margin-bottom:10px}.import-selection-actions>span{margin-right:auto}.import-post-select{font-size:9px;border:1px solid #dce7ef;border-radius:4px;background:#fff;color:#94a8b8;padding:5px 7px;flex-shrink:0}.import-post-select.selected{background:#233e55;color:#fff;border-color:#233e55}.import-page-actions{justify-content:space-between;margin-top:18px}
</style>
