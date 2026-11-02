<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { validBotPreview, validBotWaitSettings, botObservationDelay, botOperationId, botMediaNames, type BotPreview, type BotButton, type BotMedia } from '../botDownload'
import { taskLabels, validTask, type DownloadTask } from '../downloadTasks'
import { formatMediaBytes } from '../library'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ authorized: boolean; available: boolean; accountId: string | null }>()
const emit = defineEmits<{ locked: [value: boolean]; tasks: [category: 'download']; accessError: [cause: ApiError] }>()
const source = ref(''), targetDirectory = ref(''), preview = ref<BotPreview | null>(null), selected = ref<string[]>([])
const busy = ref(''), error = ref(''), notice = ref(''), display = ref<'list' | 'cards'>('list')
const manualButton = ref<BotButton | null>(null), pressUnknown = ref(false)
const createdTask = ref<DownloadTask | null>(null)
const waitSeconds = ref(5), timeoutSeconds = ref(120), observing = ref(false), previewReadPending = ref(false)
const waitUnknown = ref(false), localTimedOut = ref(false), observedNow = ref(Date.now()), waitReceivedAt = ref(Date.now())
let observationTimer: ReturnType<typeof setTimeout> | undefined, observationDeadlineTimer: ReturnType<typeof setTimeout> | undefined
let observationEpoch = 0, observationDeadline = 0, windowStartedAt = 0
let waitRevision: number | null = null
type Create = { operationId: string; sourceId: string; revision: number; mediaKeys: string[]; targetDirectory: string }
const createRequest = ref<Create | null>(null)
const preparingCreate = ref(false), creationRecheckRequired = ref(false)
const creationFeedback = ref('')
type PreviewFlight = { done: Promise<boolean>; finish: (accepted: boolean) => void }
let previewFlight: PreviewFlight | null = null
let generation = 0, disposed = false, pressRevision: number | null = null
const base = '/transfers/download/bots'
const ready = computed(() => !disposed && props.authorized && props.available && !!props.accountId)
const inputLocked = computed(() => !!busy.value || !!createRequest.value || preparingCreate.value)
const controls = computed(() => preview.value?.messages.find(message => message.id === preview.value?.controlMessageId)?.buttons ?? [])
const recommendation = computed(() => controls.value.find(button => button.id === preview.value?.recommendedButtonId) ?? null)
const waitingExpired = computed(() => localTimedOut.value || !!preview.value?.wait?.timedOut)
const waitSettingsValid = computed(() => validBotWaitSettings(waitSeconds.value, timeoutSeconds.value))
const canPress = computed(() => ready.value && !inputLocked.value && !previewReadPending.value && !observing.value && !pressUnknown.value && !waitUnknown.value && !waitingExpired.value && waitSettingsValid.value && preview.value?.state === 'READY' && !createdTask.value)
const canContinueWait = computed(() => ready.value && !inputLocked.value && !previewReadPending.value && !observing.value && !waitUnknown.value && waitSettingsValid.value && !!preview.value && (preview.value.state !== 'READY' || pressUnknown.value || waitingExpired.value) && !createdTask.value)
const remainingSeconds = computed(() => {
  const wait = preview.value?.wait
  return Math.max(0, Math.ceil(wait ? (wait.remainingMs - Math.max(0, observedNow.value - waitReceivedAt.value)) / 1000 : (observationDeadline - observedNow.value) / 1000))
})
const selectable = computed(() => preview.value?.media.filter(media => media.size > 0) ?? [])
const allSelected = computed(() => !!selectable.value.length && selectable.value.length <= 100 && selectable.value.every(media => selected.value.includes(media.key)))
const businessBusy = computed(() => !!busy.value && busy.value !== 'refresh')
const validSelection = computed(() => selected.value.length > 0 && selected.value.length <= 100 && selected.value.every(key => selectable.value.some(media => media.key === key)))
const canCreate = computed(() => ready.value && !!preview.value && validSelection.value && !!targetDirectory.value.trim() && !preparingCreate.value && !creationRecheckRequired.value && !businessBusy.value && !createRequest.value && !createdTask.value)
const creationHint = computed(() => !props.authorized ? '请先连接 Telegram。' : !ready.value ? '连接暂不可用，请恢复连接后再下载。'
  : preparingCreate.value ? '正在核对已选媒体…' : createRequest.value ? '请先核对上一次创建结果。' : createdTask.value ? ''
  : businessBusy.value ? '请等待当前操作完成后再下载。' : !preview.value ? '请先读取本次资源。'
  : creationRecheckRequired.value ? '请先刷新消息核对已选媒体，再点击下载。' : !selected.value.length ? '请选择要下载的媒体。'
  : !validSelection.value ? '所选媒体已变化，请重新核对选择。' : !targetDirectory.value.trim() ? '请填写保存目录。' : '')
const downloadStatus = computed(() => busy.value === 'create' ? '正在创建下载任务…' : preparingCreate.value ? '正在核对已选媒体…'
  : busy.value === 'lookup' ? '正在核对创建结果…' : createdTask.value ? `下载任务已创建，共 ${createdTask.value.total} 个文件 · ${taskLabels[createdTask.value.state]}。`
  : createRequest.value ? `${creationFeedback.value || '创建结果尚未确认。'} 请点击“核对创建结果”确认原任务，不会重复提交。` : creationFeedback.value)
const pageLabel = computed(() => !preview.value ? '' : preview.value.currentPage !== null ? `第 ${preview.value.currentPage}${preview.value.totalPages !== null ? ' / ' + preview.value.totalPages : ''} 页／组`
  : preview.value.paginationStatus === 'END' ? '机器人已显示结束' : '分页位置尚未识别')
watch([busy, createRequest, preparingCreate], () => emit('locked', ['press','wait','create'].includes(busy.value) || !!createRequest.value || preparingCreate.value), { flush: 'sync' })
watch([() => props.authorized, () => props.accountId], () => {
  stopObservation(); generation++; busy.value = ''; preview.value = null; selected.value = []; manualButton.value = null
  createRequest.value = null; createdTask.value = null; pressUnknown.value = false; pressRevision = null; error.value = ''; notice.value = ''
  preparingCreate.value = false; creationRecheckRequired.value = false; creationFeedback.value = ''
  waitUnknown.value = false; waitRevision = null; localTimedOut.value = false
})
watch(source, () => { if (!busy.value && !createRequest.value && !preparingCreate.value) { stopObservation(); generation++; preview.value = null; selected.value = []; createdTask.value = null; manualButton.value = null; pressUnknown.value = false; pressRevision = null; waitUnknown.value = false; waitRevision = null; localTimedOut.value = false; creationRecheckRequired.value = false; creationFeedback.value = '' } })
watch(() => props.available, value => { if (!value) stopObservation() }, { flush: 'sync' })
const current = (ticket: number, account: string | null) => !disposed && generation === ticket && props.accountId === account && props.authorized
function failed(cause: unknown, fallback: string) {
  error.value = cause instanceof Error ? cause.message : fallback
  if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
}
function creationFailed(message: string) { error.value = message; creationFeedback.value = message }
function beginPreviewRead(): PreviewFlight {
  let finish!: (accepted: boolean) => void
  const done = new Promise<boolean>(resolve => { finish = resolve })
  const flight = { done, finish }; previewFlight = flight; previewReadPending.value = true
  return flight
}
function finishPreviewRead(flight: PreviewFlight, accepted: boolean) {
  if (previewFlight === flight) { previewFlight = null; previewReadPending.value = false }
  flight.finish(accepted)
}
function discardInvalidPreview(cause: unknown): boolean {
  if (!(cause instanceof ApiError) || cause.status !== 400) return false
  stopObservation(); preview.value = null; selected.value = []; manualButton.value = null; createdTask.value = null
  pressUnknown.value = false; pressRevision = null; waitUnknown.value = false; waitRevision = null; localTimedOut.value = false; notice.value = ''
  return true
}
function apply(value: unknown, expectedId: string | null) {
  if (!validBotPreview(value) || expectedId !== null && value.id !== expectedId
    || expectedId !== null && preview.value?.id === expectedId && value.revision < preview.value.revision) throw new Error('机器人预览回执无法确认，请刷新消息核对。')
  preview.value = value; manualButton.value = null
  creationRecheckRequired.value = false; creationFeedback.value = ''
  observedNow.value = Date.now(); waitReceivedAt.value = observedNow.value
  const keys = new Set(value.media.map(media => media.key)); selected.value = selected.value.filter(key => keys.has(key))
  if (pressRevision !== null && value.revision > pressRevision && value.state === 'READY') { pressUnknown.value = false; pressRevision = null }
  if (waitRevision !== null && value.revision > waitRevision && (value.wait != null || value.state === 'READY')) { waitUnknown.value = false; waitRevision = null }
  if (value.state === 'READY' && !pressUnknown.value && !waitUnknown.value && !value.wait?.timedOut) localTimedOut.value = false
}
function pauseObservationQueue() { clearTimeout(observationTimer); clearTimeout(observationDeadlineTimer); observationTimer = undefined; observationDeadlineTimer = undefined; observing.value = false }
function stopObservation() { pauseObservationQueue(); observationEpoch++ }
function expireObservation() { stopObservation(); localTimedOut.value = true; observedNow.value = Date.now(); notice.value = '等待已超时，请核对当前媒体；需要继续时点击“继续等待”，不会再次翻页。' }
function stopWaiting() { stopObservation(); notice.value = '已停止自动观察，机器人原请求保持；可手动刷新或继续等待。' }
function armObservationDeadline(epoch: number) {
  clearTimeout(observationDeadlineTimer)
  const remaining = observationDeadline - Date.now()
  if (remaining <= 0) { if (epoch === observationEpoch) expireObservation(); return }
  observationDeadlineTimer = setTimeout(() => { observationDeadlineTimer = undefined; if (epoch === observationEpoch) expireObservation() }, remaining)
}
function scheduleObservation(epoch: number, first = false) {
  if (epoch !== observationEpoch || !observing.value || !ready.value) return
  const now = Date.now(), wait = preview.value?.wait
  observedNow.value = now
  if (wait?.timedOut || now >= observationDeadline) { expireObservation(); return }
  if (wait) observationDeadline = Math.min(observationDeadline, waitReceivedAt.value + wait.remainingMs)
  const remaining = Math.max(0, observationDeadline - now)
  if (!remaining) { expireObservation(); return }
  armObservationDeadline(epoch)
  const elapsed = wait ? wait.elapsedMs + Math.max(0, now - waitReceivedAt.value) : Math.max(0, now - windowStartedAt)
  observationTimer = setTimeout(() => { observationTimer = undefined; void observe(epoch) }, botObservationDelay(wait?.waitSeconds ?? waitSeconds.value, elapsed, remaining, first))
}
function startObservation() {
  stopObservation()
  if (!ready.value || !preview.value || createRequest.value || createdTask.value || preview.value.wait?.timedOut || (preview.value.state === 'READY' && !pressUnknown.value)) return
  const now = Date.now(), wait = preview.value.wait
  observationDeadline = wait ? Math.min(now + wait.remainingMs, windowStartedAt + wait.timeoutSeconds * 1000) : windowStartedAt + timeoutSeconds.value * 1000
  localTimedOut.value = false; observing.value = true
  scheduleObservation(observationEpoch, true)
}
async function observe(epoch: number) {
  const saved = preview.value
  if (epoch !== observationEpoch || !observing.value || !ready.value || !saved) return
  if (Date.now() >= observationDeadline) { expireObservation(); return }
  if (previewReadPending.value || busy.value || createRequest.value) { stopObservation(); return }
  const ticket = generation, account = props.accountId
  const flight = beginPreviewRead(); let accepted = false
  try {
    const value = await api<unknown>(base + '/preview/' + saved.id, { timeout: 60000 })
    if (!current(ticket, account) || !ready.value || epoch !== observationEpoch) return
    if (Date.now() >= observationDeadline) { expireObservation(); return }
    apply(value, saved.id)
    accepted = true
    if (preview.value?.state === 'READY' && !pressUnknown.value && !waitUnknown.value) stopObservation()
    else if (preview.value?.wait?.timedOut || Date.now() >= observationDeadline) expireObservation()
  } catch (cause) {
    if (current(ticket, account) && epoch === observationEpoch) {
      const invalid = discardInvalidPreview(cause)
      if (!invalid) stopObservation()
      failed(cause, '观察回复未完成。')
      if (!invalid) error.value += ' 自动观察已停止，请手动刷新核对。'
    }
  } finally {
    finishPreviewRead(flight, accepted)
    if (epoch === observationEpoch && observing.value) scheduleObservation(epoch)
  }
}
async function open() {
  if (!ready.value || inputLocked.value || previewReadPending.value || !source.value.trim()) return
  stopObservation()
  const ticket = ++generation, account = props.accountId, input = source.value.trim()
  preview.value = null; selected.value = []; manualButton.value = null; createdTask.value = null
  creationRecheckRequired.value = false; creationFeedback.value = ''
  pressUnknown.value = false; pressRevision = null; waitUnknown.value = false; waitRevision = null; localTimedOut.value = false
  busy.value = 'open'; error.value = ''; notice.value = ''
  try {
    const value = await api<unknown>(base + '/preview', { method: 'POST', body: { source: input }, timeout: 60000 })
    if (!current(ticket, account)) return
    apply(value, null); selected.value = []; createdTask.value = null; pressUnknown.value = false; pressRevision = null; waitUnknown.value = false; waitRevision = null; localTimedOut.value = false
  } catch (cause) { if (current(ticket, account)) failed(cause, '读取机器人会话未完成。') }
  finally { if (current(ticket, account)) busy.value = '' }
}
async function read(kind: 'refresh' = 'refresh') {
  if (kind !== 'refresh') return
  const saved = preview.value
  if (!saved || !ready.value || inputLocked.value || previewReadPending.value || createdTask.value) return
  const resume = observing.value
  stopObservation()
  const ticket = generation, account = props.accountId, epoch = observationEpoch; busy.value = kind; const flight = beginPreviewRead(); error.value = ''; notice.value = ''
  if (resume) { observing.value = true; armObservationDeadline(epoch) }
  let accepted = false
  try {
    const value = await api<unknown>(base + '/preview/' + saved.id, { timeout: 60000 })
    if (current(ticket, account) && epoch === observationEpoch) {
      if (resume && Date.now() >= observationDeadline) { expireObservation(); return }
      apply(value, saved.id); accepted = true
      if (resume && preview.value?.state === 'READY' && !pressUnknown.value && !waitUnknown.value) stopObservation()
      else if (resume && preview.value?.wait?.timedOut) expireObservation()
    }
  } catch (cause) {
    if (current(ticket, account) && epoch === observationEpoch) {
      if (!discardInvalidPreview(cause) && resume) stopObservation()
      failed(cause, '读取消息未完成，请手动刷新。')
    }
  }
  finally {
    if (current(ticket, account)) {
      busy.value = ''
      if (!preparingCreate.value && resume && accepted && epoch === observationEpoch && ready.value && preview.value && (preview.value.state !== 'READY' || pressUnknown.value || waitUnknown.value)) {
        observing.value = true; scheduleObservation(epoch)
      }
    }
    finishPreviewRead(flight, accepted)
  }
}
function chooseButton(button: BotButton) {
  if (!canPress.value || !button.supported) return
  manualButton.value = button
}
async function press(button: BotButton) {
  const saved = preview.value
  if (!saved || !canPress.value || !button.supported || !controls.value.some(value => value.id === button.id)) return
  let operationId: string
  try { operationId = botOperationId() }
  catch (cause) { failed(cause, '无法生成安全操作号，尚未点击分页按钮。'); return }
  const ticket = generation, account = props.accountId
  stopObservation(); windowStartedAt = Date.now(); localTimedOut.value = false; waitUnknown.value = false
  busy.value = 'press'; error.value = ''; notice.value = ''; manualButton.value = null; pressRevision = saved.revision
  try {
    const value = await api<unknown>(base + '/preview/' + saved.id + '/press', { method: 'POST', body: { operationId, revision: saved.revision, buttonId: button.id, waitSeconds: waitSeconds.value, timeoutSeconds: timeoutSeconds.value }, timeout: 60000 })
    if (!current(ticket, account)) return
    apply(value, saved.id); pressUnknown.value = preview.value?.state === 'NEEDS_REVIEW'
    notice.value = preview.value?.state === 'READY' ? '已读取翻页后的消息，请核对媒体。' : '翻页请求已提交，正在等待回复稳定；不会自动重复点击。'
  } catch (cause) {
    if (current(ticket, account)) { pressUnknown.value = true; failed(cause, '翻页结果尚未确认。'); error.value += ' 请刷新消息核对，不会自动重复翻页。' }
  } finally { if (current(ticket, account)) { busy.value = ''; startObservation() } }
}
async function continueWait() {
  const saved = preview.value
  if (!saved || !canContinueWait.value) return
  stopObservation(); windowStartedAt = Date.now(); waitRevision = saved.revision
  const ticket = generation, account = props.accountId; busy.value = 'wait'; error.value = ''; notice.value = ''; waitUnknown.value = true
  let accepted = false
  try {
    const value = await api<unknown>(base + '/preview/' + saved.id + '/wait', { method: 'POST', body: { revision: saved.revision, waitSeconds: waitSeconds.value, timeoutSeconds: timeoutSeconds.value }, timeout: 60000 })
    if (!current(ticket, account)) return
    apply(value, saved.id); waitUnknown.value = false; waitRevision = null; localTimedOut.value = false; accepted = true
    notice.value = '已继续等待回复，没有再次点击分页按钮。'
  } catch (cause) {
    if (current(ticket, account)) {
      if (cause instanceof ApiError && cause.status >= 400 && cause.status < 500) { waitUnknown.value = false; waitRevision = null }
      failed(cause, '继续等待的结果尚未确认。'); error.value += ' 请刷新消息核对，不会自动重复提交。'
    }
  } finally { if (current(ticket, account)) { busy.value = ''; if (accepted) startObservation() } }
}
function select(key: string) {
  if (inputLocked.value || createdTask.value || !selectable.value.some(media => media.key === key)) return
  if (selected.value.includes(key)) selected.value = selected.value.filter(value => value !== key)
  else if (selected.value.length < 100) selected.value = [...selected.value, key]
  else error.value = '每个下载任务最多选择 100 个媒体。'
}
function selectAll() {
  if (inputLocked.value || createdTask.value) return
  selected.value = allSelected.value ? [] : selectable.value.slice(0, 100).map(media => media.key)
  if (selectable.value.length > 100) notice.value = '已选前 100 个媒体，其余可在下一次任务中选择。'
}
const directoryKey = (value: string) => value.trim().replaceAll('/', '\\').replace(/\\+$/, '').toLocaleLowerCase('en-US')
function taskMatches(value: unknown, request: Create): value is DownloadTask {
  return validTask(value as DownloadTask) && (value as DownloadTask).total === request.mediaKeys.length
    && directoryKey((value as DownloadTask).targetDirectory) === directoryKey(request.targetDirectory)
}
async function create() {
  const saved = preview.value
  if (!saved || !canCreate.value) { if (!disposed && !createRequest.value && !createdTask.value) creationFeedback.value = creationHint.value || '当前媒体尚未就绪，请核对选择后再下载。'; return }
  const ticket = generation, account = props.accountId
  preparingCreate.value = true; pauseObservationQueue(); manualButton.value = null; error.value = ''; notice.value = ''; creationFeedback.value = ''
  try {
    const keys = [...selected.value], directory = targetDirectory.value.trim()
    const version = (media: BotMedia) => JSON.stringify([media.key, media.type, media.size, media.fileName, media.message.chatId, media.message.messageId])
    const versions = new Map(keys.map(key => [key, version(saved.media.find(media => media.key === key)!)]))
    const flight = previewFlight
    if (flight && !await flight.done) {
      if (current(ticket, account)) { creationRecheckRequired.value = true; creationFailed(error.value || '消息核对未完成，请刷新消息核对后再下载。') }
      return
    }
    if (!current(ticket, account)) return
    if (!ready.value) { creationFailed('连接暂不可用，请恢复连接后核对已选媒体。'); return }
    const latest = preview.value
    if (!latest || latest.id !== saved.id) { creationFailed('本次资源已变化，请重新读取资源后选择媒体。'); return }
    if (keys.some(key => { const media = latest.media.find(media => media.key === key); return !media || version(media) !== versions.get(key) })) {
      creationFailed('所选媒体已变化或不再可见，请核对当前媒体后再次点击下载。'); return
    }
    const request: Create = { operationId: botOperationId(), sourceId: latest.id, revision: latest.revision, mediaKeys: keys, targetDirectory: directory }
    createRequest.value = request; busy.value = 'create'
    try {
      const result = await api<unknown>(base + '/tasks', { method: 'POST', body: request, timeout: 150000 })
      if (!current(ticket, account)) return
      if (!taskMatches(result, request)) throw new Error('下载任务回执与本次选择不符，请核对结果。')
      createdTask.value = result; createRequest.value = null; notice.value = '下载任务已创建，可到任务中心查看进度。'
    } catch (cause) {
      if (current(ticket, account)) {
        failed(cause, '创建任务结果尚未确认。')
        if (cause instanceof ApiError && cause.status >= 400 && cause.status < 500) createRequest.value = null
        else error.value += ' 参数已冻结，请核对创建结果，不会自动重新提交。'
        creationFeedback.value = error.value
      }
    } finally { if (current(ticket, account)) busy.value = '' }
  } catch (cause) {
    if (current(ticket, account)) {
      failed(cause, '准备下载任务未完成，请核对后重试。')
      if (createRequest.value) error.value += ' 参数已冻结，请核对创建结果，不会自动重新提交。'
      creationFeedback.value = error.value
    }
  } finally { if (current(ticket, account)) preparingCreate.value = false }
}
async function lookup() {
  const request = createRequest.value
  if (!request || !ready.value || busy.value) return
  const ticket = generation, account = props.accountId; busy.value = 'lookup'; error.value = ''
  try {
    const result = await api<{ task: unknown }>(base + '/tasks/by-operation/' + request.operationId)
    if (!current(ticket, account)) return
    if (result?.task === null) { creationFailed('尚未查到这次创建，请稍后再次核对；没有重新提交。'); return }
    if (!taskMatches(result?.task, request)) throw new Error('创建结果尚未核对一致，请保留当前参数并再次核对。')
    createdTask.value = result.task; createRequest.value = null; notice.value = '已找到原下载任务，没有重复创建。'
  } catch (cause) { if (current(ticket, account)) { failed(cause, '核对任务未完成。'); creationFeedback.value = error.value } }
  finally { if (current(ticket, account)) busy.value = '' }
}
function continueBrowsing() {
  if (!ready.value || inputLocked.value || !createdTask.value) return
  createdTask.value = null; selected.value = []; notice.value = '可继续翻页或选择另一批媒体。'; creationFeedback.value = ''
}
onBeforeUnmount(() => { stopObservation(); disposed = true; generation++ })
</script>

<template>
  <section class="bot-download" aria-label="机器人下载">
    <p class="bot-help">在 Telegram 打开要下载的页／组，再点击“读取当前页／组”。可从任意页开始，也可手动翻页后重新读取；后续分页只追加本次媒体，不自动读取历史资源。</p>
    <div class="bot-source"><label>机器人用户名或会话 ID<input v-model="source" class="input" :disabled="inputLocked" placeholder="@example_bot 或 https://t.me/example_bot" maxlength="2048" autocomplete="off" @keydown.enter.prevent="open" /></label><button type="button" class="btn primary" :disabled="!ready || inputLocked || previewReadPending || !source.trim()" @click="open">{{ busy === 'open' ? '读取中…' : '读取当前页／组' }}</button></div>
    <div class="bot-wait-settings"><label>回复稳定等待（秒）<input v-model.number="waitSeconds" class="input" type="number" min="2" max="60" step="1" :disabled="!ready || inputLocked || observing || previewReadPending" /></label><label>最长等待（秒）<input v-model.number="timeoutSeconds" class="input" type="number" min="15" max="300" step="1" :disabled="!ready || inputLocked || observing || previewReadPending" /></label></div>
    <p v-if="!waitSettingsValid" class="notice warning" role="status">稳定等待为 2～60 秒；最长等待为 15～300 秒，且不少于稳定等待的两倍。</p>
    <p v-if="!authorized" class="notice warning">请先在设置中连接 Telegram。</p>
    <div v-if="error" class="notice warning" role="alert">{{ error }}</div><p v-if="notice" class="bot-notice" role="status">{{ notice }}</p>
    <template v-if="preview">
      <header class="bot-preview-heading"><div><strong>{{ preview.bot.title }}</strong><span>{{ pageLabel }}</span></div><button type="button" class="btn" :disabled="!ready || inputLocked || previewReadPending || !!createdTask" @click="read('refresh')"><AppIcon name="refresh" />{{ busy === 'refresh' ? '读取中…' : '刷新消息' }}</button></header>
      <p v-if="preview.warning" class="notice warning" role="status">{{ preview.warning }}</p>
      <div v-if="preview.state !== 'READY' || pressUnknown || waitUnknown || waitingExpired" class="bot-wait-status" role="status"><div><strong v-if="waitingExpired">已达到最长等待时间</strong><strong v-else-if="waitUnknown">继续等待的结果待核对，请刷新消息</strong><strong v-else-if="observing">等待回复稳定 · 最多剩余 {{ remainingSeconds }} 秒</strong><strong v-else>自动观察已停止</strong><small>{{ preview.wait?.progressObserved ? '已观察到回复变化，等待本批消息稳定。' : '尚未确认本批回复已稳定。' }} 不会再次点击分页按钮。</small></div><button v-if="observing" type="button" class="btn" @click="stopWaiting">停止等待</button><button v-else type="button" class="btn" :disabled="!canContinueWait" @click="continueWait">{{ busy === 'wait' ? '提交中…' : '继续等待' }}</button></div>
      <p v-if="preview.controlMessageId" class="bot-control-text">{{ preview.messages.find(message => message.id === preview!.controlMessageId)?.text }}</p>
      <div class="bot-buttons"><button v-if="recommendation" type="button" class="btn primary" :disabled="!canPress" @click="press(recommendation)">{{ busy === 'press' ? '翻页中…' : '下一页／组：' + recommendation.text }}</button><span v-if="preview.paginationStatus === 'END'" class="bot-end">机器人已显示最后一页／组</span><span v-else-if="preview.paginationStatus === 'UNKNOWN'" class="bot-help">未识别到可靠的下一页，可在下方手动选择分页按钮。</span></div>
      <details v-if="controls.length" class="bot-manual"><summary>手动选择其他分页按钮</summary><div class="bot-buttons"><button v-for="button in controls" :key="button.id" type="button" class="btn" :disabled="!canPress || !button.supported" @click="chooseButton(button)">{{ button.text }}</button></div><p class="bot-help">仅选择翻页按钮。链接、广告、付款及不支持的按钮不会执行。</p></details>
      <div v-if="manualButton" class="bot-confirm" role="group" aria-label="确认分页按钮"><p>将点击“{{ manualButton.text }}”。请确认这是你要使用的分页按钮。</p><button type="button" class="btn primary" :disabled="!canPress" @click="press(manualButton)">确认点击</button><button type="button" class="btn" @click="manualButton=null">取消</button></div>
      <details class="bot-messages"><summary>本次资源消息（{{ preview.messages.length }}）</summary><article v-for="message in preview.messages" :key="message.id"><p>{{ message.text || '媒体消息' }}</p><small>{{ message.media.length }} 个媒体</small></article></details>
      <div class="bot-media-toolbar"><div><strong>已读取 {{ preview.media.length }} 个媒体</strong><span>已选 {{ selected.length }} / 100</span></div><div class="bot-view-switch" role="group" aria-label="机器人媒体显示方式"><button type="button" :class="{selected:display==='list'}" :aria-pressed="display==='list'" @click="display='list'">列表</button><button type="button" :class="{selected:display==='cards'}" :aria-pressed="display==='cards'" @click="display='cards'">卡片</button></div></div>
      <button v-if="selectable.length" type="button" class="btn bot-select-all" :disabled="inputLocked || !!createdTask" @click="selectAll">{{ allSelected ? '取消全选' : selectable.length > 100 ? '选择前 100 个' : '全选媒体' }}</button>
      <div :class="['bot-media',display]"><label v-for="media in preview.media" :key="media.key" class="bot-media-item"><input type="checkbox" :checked="selected.includes(media.key)" :disabled="inputLocked || !!createdTask || media.size<=0 || selected.length>=100 && !selected.includes(media.key)" @change="select(media.key)" /><span class="bot-media-icon"><AppIcon :name="media.type==='PHOTO'?'photo':media.type==='VIDEO'?'video':media.type==='AUDIO'?'audio':'file'" /></span><span class="bot-media-info"><strong>{{ media.fileName }}</strong><small>{{ botMediaNames[media.type] }} · {{ formatMediaBytes(media.size) }}</small><span v-if="media.caption">{{ media.caption }}</span></span></label></div>
      <p v-if="!preview.media.length" class="bot-help">当前页／组还没有可下载媒体，请在 Telegram 打开目标页／组后重新读取。</p>
      <label class="bot-directory">保存目录<input v-model="targetDirectory" class="input" :disabled="inputLocked || !!createdTask" placeholder="例如 D:\TelegramDownloads" autocomplete="off" /></label>
    </template>
    <div v-if="preview || downloadStatus" class="bot-download-footer">
      <p v-if="creationHint && !downloadStatus" class="bot-help" role="status">{{ creationHint }}</p>
      <p v-if="downloadStatus" :class="['notice','bot-download-feedback',createdTask?'success':preparingCreate || busy==='lookup'?'':'warning']" role="status" aria-live="polite">{{ downloadStatus }}</p>
      <div v-if="preview || createRequest || createdTask" class="bot-download-actions"><button v-if="createRequest" type="button" class="btn primary" :disabled="!ready || !!busy" @click="lookup">核对创建结果</button><button v-else-if="!createdTask" type="button" class="btn primary" :disabled="!canCreate" @click="create">{{ preparingCreate && busy !== 'create' ? '正在核对已选媒体…' : busy === 'create' ? '创建中…' : '下载选中媒体' }}</button><template v-if="createdTask"><button type="button" class="btn primary" @click="emit('tasks','download')">查看下载任务</button><button type="button" class="btn" :disabled="!ready || inputLocked" @click="continueBrowsing">继续浏览机器人</button></template></div>
    </div>
  </section>
</template>

<style scoped>
.bot-wait-settings{display:grid;grid-template-columns:repeat(2,minmax(0,170px));gap:16px}.bot-wait-settings label{margin:8px 0!important}.bot-wait-status{display:flex;align-items:center;justify-content:space-between;gap:12px;background:#f2f7fb;border:1px solid #deebf5;border-radius:6px;padding:12px;margin:14px 0}.bot-wait-status strong,.bot-wait-status small{display:block;font-size:11px;line-height:1.9;overflow-wrap:anywhere}.bot-wait-status strong{font-weight:500;color:#547b97}.bot-wait-status small{font-size:10px;color:#92a7b6}.bot-wait-status .btn{flex-shrink:0;font-size:10px}@media(max-width:420px){.bot-wait-settings{gap:10px}.bot-wait-status{align-items:stretch;flex-direction:column}.bot-wait-status .btn{align-self:flex-end}}
.bot-download{min-width:0;font-size:12px;color:#66859c}.bot-help,.bot-notice{font-size:11px;line-height:1.9;margin:10px 0;color:#8ba1b1}.bot-notice{color:#548671}.bot-source{display:flex;align-items:end;gap:12px}.bot-source>label{flex:1;min-width:0}.bot-download label:not(.bot-media-item){display:flex;flex-direction:column;gap:8px;font-size:11px;margin:15px 0}.bot-download .input{width:100%;min-width:0}.bot-source>.btn{flex-shrink:0;margin-bottom:15px}.bot-preview-heading,.bot-media-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:22px 0 14px}.bot-preview-heading>div,.bot-media-toolbar>div:first-child{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.bot-preview-heading span,.bot-media-toolbar span{font-size:11px;color:#8ba1b1}.bot-control-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;padding:14px;background:#f4f8fb;border-radius:6px}.bot-buttons,.bot-download-actions{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:12px 0}.bot-buttons .btn{font-size:11px}.bot-manual,.bot-messages{margin:16px 0;font-size:11px;color:#819aab}.bot-manual>summary,.bot-messages>summary{cursor:pointer}.bot-confirm{padding:14px;border:1px solid #dce8f2;border-radius:6px;background:#f6faff}.bot-confirm p{margin-top:0;overflow-wrap:anywhere}.bot-confirm .btn{margin-right:8px}.bot-end{font-size:11px;color:#588a70}.bot-messages article{border-bottom:1px solid #e5edf4;padding:10px 0}.bot-messages article p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;margin:0}.bot-messages small{display:block;color:#99adba;margin-top:5px}.bot-view-switch{display:flex;gap:4px}.bot-view-switch button{padding:6px 10px;background:white;border:1px solid #d8e4ee;border-radius:5px;color:#7b97ab;font-size:11px}.bot-view-switch button.selected{color:#2079ba;background:#edf6fd}.bot-select-all{font-size:11px;margin-bottom:12px}.bot-media{border:1px solid #e0e9f0;border-radius:7px;overflow:hidden;background:#fff}.bot-media-item{display:flex;align-items:center;gap:12px;padding:14px;border-bottom:1px solid #e7eef4;min-width:0;cursor:pointer}.bot-media-item:last-child{border-bottom:0}.bot-media-item>input{accent-color:#2782c7;flex-shrink:0}.bot-media-icon{display:flex;align-items:center;justify-content:center;background:#eff6fa;color:#88a7ba;width:48px;height:48px;border-radius:5px;flex-shrink:0}.bot-media-icon .icon{width:22px;height:22px}.bot-media-info{min-width:0;display:flex;flex-direction:column;gap:5px;overflow-wrap:anywhere}.bot-media-info strong{font-weight:500;font-size:12px;color:#486b84}.bot-media-info small,.bot-media-info>span{font-size:10px;line-height:1.8;color:#8da5b5;white-space:pre-wrap}.bot-media.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));border:0;gap:12px;background:transparent;overflow:visible}.bot-media.cards .bot-media-item{border:1px solid #dfe8f0;border-radius:7px;background:white;align-items:flex-start;flex-wrap:wrap}.bot-media.cards .bot-media-info{flex-basis:100%}.bot-download .notice{overflow-wrap:anywhere;line-height:1.9}@media(max-width:600px){.bot-source{align-items:stretch;flex-direction:column;gap:0}.bot-source>.btn{align-self:flex-end}.bot-preview-heading,.bot-media-toolbar{align-items:flex-start;flex-wrap:wrap}.bot-media.cards{grid-template-columns:repeat(auto-fill,minmax(150px,1fr))}.bot-media-item{gap:8px;padding:11px}.bot-media-icon{width:36px;height:36px}}
</style>
