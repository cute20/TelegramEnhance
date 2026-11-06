<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { validBotPreview, validBotWaitSettings, validBotPreparation, botObservationDelay, botOperationId, botMediaNames, type BotPreview, type BotButton, type BotMedia, type BotPreparation } from '../botDownload'
import { taskLabels, validTask, type DownloadTask } from '../downloadTasks'
import { formatMediaBytes } from '../library'
import AppIcon from './AppIcon.vue'

const props = withDefaults(defineProps<{ authorized: boolean; available: boolean; accountId: string | null; active?: boolean; actionTarget?: string; defaultDownloadDirectory?: string }>(), { active: true })
const emit = defineEmits<{ locked: [value: boolean]; tasks: [category: 'download']; accessError: [cause: ApiError]; stage: [value: 'parameters' | 'preview' | 'confirm' | 'complete'] }>()
const source = ref(''), targetDirectory = ref(''), preview = ref<BotPreview | null>(null), selected = ref<string[]>([])
const busy = ref(''), error = ref(''), notice = ref(''), display = ref<'list' | 'cards'>('list')
const manualButton = ref<BotButton | null>(null), pressUnknown = ref(false)
const createdTask = ref<DownloadTask | null>(null)
const waitSeconds = ref(5), timeoutSeconds = ref(120), observing = ref(false), previewReadPending = ref(false)
const waitUnknown = ref(false), localTimedOut = ref(false), observedNow = ref(Date.now()), waitReceivedAt = ref(Date.now())
const autoPagination = ref(true), autoPaging = ref(false)
let autoPageTimer: ReturnType<typeof setTimeout> | undefined
let autoPaginationEpoch = 0
const autoVisited = new Set<string>()
let observationTimer: ReturnType<typeof setTimeout> | undefined, observationDeadlineTimer: ReturnType<typeof setTimeout> | undefined
let observationEpoch = 0, observationDeadline = 0, windowStartedAt = 0
let waitRevision: number | null = null
type Create = { operationId: string; sourceId: string; revision: number; mediaKeys: string[]; targetDirectory: string }
const createRequest = ref<Create | null>(null)
const preparation = ref<BotPreparation | null>(null), pollingCreation = ref(false)
let preparationTimer: ReturnType<typeof setTimeout> | undefined, preparationEpoch = 0
const preparingCreate = ref(false), creationRecheckRequired = ref(false)
const creationFeedback = ref('')
type PreviewFlight = { done: Promise<boolean>; finish: (accepted: boolean) => void }
let previewFlight: PreviewFlight | null = null
let generation = 0, disposed = false, pressRevision: number | null = null
const base = '/transfers/download/bots'
const ready = computed(() => !disposed && props.authorized && props.available && !!props.accountId)
const inputLocked = computed(() => !!busy.value || !!createRequest.value || preparingCreate.value)
const browsingLocked = computed(() => inputLocked.value || autoPaging.value)
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
const visibleMedia = computed(() => preview.value?.media ?? [])
const selectable = computed(() => visibleMedia.value.filter(media => media.size > 0))
const selectedKeys = computed(() => new Set(selected.value))
const selectableKeys = computed(() => new Set(selectable.value.map(media => media.key)))
const allSelected = computed(() => !!selectable.value.length && selectable.value.every(media => selectedKeys.value.has(media.key)))
const businessBusy = computed(() => !!busy.value && busy.value !== 'refresh')
const validSelection = computed(() => selected.value.length > 0 && selected.value.every(key => selectableKeys.value.has(key)))
const canCreate = computed(() => ready.value && !!preview.value && validSelection.value && !!targetDirectory.value.trim() && !autoPaging.value && !preparingCreate.value && !creationRecheckRequired.value && !businessBusy.value && !createRequest.value && !createdTask.value)
const creationHint = computed(() => !props.authorized ? '请先连接 Telegram。' : !ready.value ? '连接暂不可用，请恢复连接后再下载。'
  : preparingCreate.value ? '正在核对已选媒体…' : createRequest.value ? '请先核对上一次创建结果。' : createdTask.value ? ''
  : autoPaging.value ? '正在自动采集，可停止采集后下载已读取的媒体。' : businessBusy.value ? '请等待当前操作完成后再下载。' : !preview.value ? '请先读取本次资源。'
  : creationRecheckRequired.value ? '请先刷新消息核对已选媒体，再点击下载。' : !selected.value.length ? '请选择要下载的媒体。'
  : !validSelection.value ? '所选媒体已变化，请重新核对选择。' : !targetDirectory.value.trim() ? '请填写保存目录。'
  : '')
const downloadStatus = computed(() => busy.value === 'create' ? '正在创建下载任务…' : preparingCreate.value ? '正在核对已选媒体…'
  : busy.value === 'lookup' ? '正在核对创建结果…' : createdTask.value ? `下载任务已创建，共 ${createdTask.value.total} 个文件 · ${taskLabels[createdTask.value.state]}。`
  : createRequest.value && preparation.value?.state === 'PREPARING' ? `${creationFeedback.value ? creationFeedback.value + ' ' : ''}正在后台核对 ${createRequest.value.mediaKeys.length} 个媒体，完成后自动创建下载任务。${pollingCreation.value ? '' : ' 页面等待已停止，可核对原准备结果。'}`
  : createRequest.value ? `${creationFeedback.value || '创建结果尚未确认。'} 请点击“核对创建结果”确认原任务，不会重复提交。` : creationFeedback.value)
const pageLabel = computed(() => !preview.value ? '' : preview.value.currentPage !== null ? `第 ${preview.value.currentPage}${preview.value.totalPages !== null ? ' / ' + preview.value.totalPages : ''} 页／组`
  : preview.value.paginationStatus === 'END' ? '机器人已显示结束' : '分页位置尚未识别')
const reviewingAutoPage = computed(() => autoPaging.value && observing.value && pressUnknown.value)
const paginationNotice = computed(() => reviewingAutoPage.value
  ? '翻页回执未确认，正在自动核对回复；确认翻页成功并稳定后会继续，不会重复点击当前按钮。' : preview.value?.warning)
const creationStage = computed(() => createdTask.value ? 'complete' : preview.value && (validSelection.value || !!createRequest.value) ? 'confirm' : preview.value ? 'preview' : 'parameters')
watch(creationStage, value => emit('stage', value), { immediate: true, flush: 'sync' })
watch(() => props.defaultDownloadDirectory, value => {
  if (!inputLocked.value && !targetDirectory.value.trim() && value?.trim()) targetDirectory.value = value
}, { immediate: true })
watch([busy, createRequest, preparingCreate, autoPaging], () => emit('locked', autoPaging.value || ['press','wait','create'].includes(busy.value) || !!createRequest.value || preparingCreate.value), { flush: 'sync' })
watch([() => props.authorized, () => props.accountId], () => {
  pauseCreationPolling(); preparation.value = null; stopAutoPagination(); stopObservation(); generation++; busy.value = ''; preview.value = null; selected.value = []; manualButton.value = null
  createRequest.value = null; createdTask.value = null; pressUnknown.value = false; pressRevision = null; error.value = ''; notice.value = ''
  preparingCreate.value = false; creationRecheckRequired.value = false; creationFeedback.value = ''
  waitUnknown.value = false; waitRevision = null; localTimedOut.value = false
})
watch(source, () => { if (!busy.value && !createRequest.value && !preparingCreate.value) { stopAutoPagination(); stopObservation(); generation++; preview.value = null; selected.value = []; createdTask.value = null; manualButton.value = null; pressUnknown.value = false; pressRevision = null; waitUnknown.value = false; waitRevision = null; localTimedOut.value = false; creationRecheckRequired.value = false; creationFeedback.value = '' } })
watch(() => props.available, value => { if (!value) { pauseCreationPolling(); stopAutoPagination('连接暂不可用，已停止自动翻页。'); stopObservation() } }, { flush: 'sync' })
watch(() => props.active, value => { if (value === false) { pauseCreationPolling(); stopAutoPagination(); pauseObservationQueue() } }, { flush: 'sync' })
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
  stopAutoPagination(); stopObservation(); preview.value = null; selected.value = []; manualButton.value = null; createdTask.value = null
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
function stopAutoPagination(message?: string) {
  const running = autoPaging.value
  autoPaginationEpoch++
  clearTimeout(autoPageTimer); autoPageTimer = undefined; autoPaging.value = false
  if (running && message) notice.value = message
}
function startAutoPagination() {
  if (!canPress.value || props.active === false || autoPaging.value) return
  autoVisited.clear(); autoPaging.value = true; manualButton.value = null; error.value = ''
  notice.value = '正在自动采集后续页／组，采集完成后可选择媒体下载。'
  scheduleAutoPage()
}
function scheduleAutoPage() {
  clearTimeout(autoPageTimer); autoPageTimer = undefined
  if (!autoPaging.value) return
  if (!ready.value || props.active === false || createRequest.value || createdTask.value || preparingCreate.value) { stopAutoPagination(); return }
  if (waitingExpired.value || waitUnknown.value) { stopAutoPagination('分页结果需要核对，已停止自动采集。'); return }
  // Keep an acknowledged callback pending while serial GETs verify its stable reply.
  if (inputLocked.value || previewReadPending.value || observing.value) return
  if (pressUnknown.value) { stopAutoPagination('分页结果需要核对，已停止自动采集。'); return }
  if (preview.value?.state !== 'READY') return
  if (preview.value.paginationStatus === 'END') { stopAutoPagination('已采集到最后一页／组，可以选择媒体后台下载。'); return }
  if (preview.value.paginationStatus !== 'MORE' || !recommendation.value || !waitSettingsValid.value) { stopAutoPagination('未识别到可靠的下一页，已停止自动采集，请核对分页按钮。'); return }
  const epoch = autoPaginationEpoch
  autoPageTimer = setTimeout(() => {
    autoPageTimer = undefined
    const next = recommendation.value, saved = preview.value
    if (epoch !== autoPaginationEpoch || !autoPaging.value || !canPress.value || !next || !saved) return
    const position = JSON.stringify([saved.controlMessageId, saved.currentPage, next.row, next.column, next.text,
      saved.messages.map(message => [message.id, message.text, message.media.map(media => [media.key, media.type, media.size, media.fileName])])])
    if (autoVisited.has(position)) { stopAutoPagination('分页位置没有变化，已停止自动采集，请刷新消息核对。'); return }
    autoVisited.add(position)
    void press(next, true)
  }, 0)
}
function expireObservation() { stopAutoPagination(); stopObservation(); localTimedOut.value = true; observedNow.value = Date.now(); notice.value = '等待已超时，请核对当前媒体；需要继续时点击“继续等待”，不会再次翻页。' }
function stopWaiting() { stopAutoPagination(); stopObservation(); notice.value = '已停止自动观察，机器人原请求保持；可手动刷新或继续等待。' }
function armObservationDeadline(epoch: number) {
  clearTimeout(observationDeadlineTimer)
  const remaining = observationDeadline - Date.now()
  if (remaining <= 0) { if (epoch === observationEpoch) expireObservation(); return }
  observationDeadlineTimer = setTimeout(() => { observationDeadlineTimer = undefined; if (epoch === observationEpoch) expireObservation() }, remaining)
}
function scheduleObservation(epoch: number, first = false) {
  if (epoch !== observationEpoch || !observing.value || !ready.value || props.active === false) return
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
  if (props.active === false || !ready.value || !preview.value || createRequest.value || createdTask.value || preview.value.wait?.timedOut || (preview.value.state === 'READY' && !pressUnknown.value)) return
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
      stopAutoPagination()
      if (!invalid) stopObservation()
      failed(cause, '观察回复未完成。')
      if (!invalid) error.value += ' 自动观察已停止，请手动刷新核对。'
    }
  } finally {
    finishPreviewRead(flight, accepted)
    if (epoch === observationEpoch && observing.value) scheduleObservation(epoch)
    if (accepted) scheduleAutoPage()
  }
}
async function open() {
  if (!ready.value || browsingLocked.value || previewReadPending.value || !source.value.trim()) return
  stopAutoPagination(); stopObservation()
  const ticket = ++generation, account = props.accountId, input = source.value.trim(), autoEpoch = autoPaginationEpoch
  preview.value = null; selected.value = []; manualButton.value = null; createdTask.value = null
  creationRecheckRequired.value = false; creationFeedback.value = ''
  pressUnknown.value = false; pressRevision = null; waitUnknown.value = false; waitRevision = null; localTimedOut.value = false
  busy.value = 'open'; error.value = ''; notice.value = ''
  let accepted = false
  try {
    const value = await api<unknown>(base + '/preview', { method: 'POST', body: { source: input }, timeout: 60000 })
    if (!current(ticket, account)) return
    apply(value, null); selected.value = []; createdTask.value = null; pressUnknown.value = false; pressRevision = null; waitUnknown.value = false; waitRevision = null; localTimedOut.value = false
    accepted = true
  } catch (cause) { if (current(ticket, account)) failed(cause, '读取机器人会话未完成。') }
  finally { if (current(ticket, account)) { busy.value = ''; if (accepted && autoPagination.value && autoEpoch === autoPaginationEpoch) startAutoPagination() } }
}
async function read(kind: 'refresh' = 'refresh') {
  if (kind !== 'refresh') return
  const saved = preview.value
  if (!saved || !ready.value || browsingLocked.value || previewReadPending.value || createdTask.value) return
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
      if (props.active !== false && !preparingCreate.value && resume && accepted && epoch === observationEpoch && ready.value && preview.value && (preview.value.state !== 'READY' || pressUnknown.value || waitUnknown.value)) {
        observing.value = true; scheduleObservation(epoch)
      }
    }
    finishPreviewRead(flight, accepted)
  }
}
function chooseButton(button: BotButton) {
  if (autoPaging.value || !canPress.value || !button.supported) return
  manualButton.value = button
}
async function press(button: BotButton, automatic = false) {
  const saved = preview.value
  if (!saved || autoPaging.value && !automatic || !canPress.value || !button.supported || !controls.value.some(value => value.id === button.id)) return
  let operationId: string
  try { operationId = botOperationId() }
  catch (cause) { stopAutoPagination(); failed(cause, '无法生成安全操作号，尚未点击分页按钮。'); return }
  const ticket = generation, account = props.accountId
  stopObservation(); windowStartedAt = Date.now(); localTimedOut.value = false; waitUnknown.value = false
  busy.value = 'press'; error.value = ''; notice.value = ''; manualButton.value = null; pressRevision = saved.revision
  try {
    const value = await api<unknown>(base + '/preview/' + saved.id + '/press', { method: 'POST', body: { operationId, revision: saved.revision, buttonId: button.id, waitSeconds: waitSeconds.value, timeoutSeconds: timeoutSeconds.value }, timeout: 60000 })
    if (!current(ticket, account)) return
    apply(value, saved.id); pressUnknown.value = preview.value?.state === 'NEEDS_REVIEW'
    notice.value = preview.value?.state === 'READY' ? '已读取翻页后的消息，请核对媒体。' : '翻页请求已提交，正在等待回复稳定；不会自动重复点击。'
  } catch (cause) {
    if (current(ticket, account)) { stopAutoPagination(); pressUnknown.value = true; failed(cause, '翻页结果尚未确认。'); error.value += ' 请刷新消息核对，不会自动重复翻页。' }
  } finally { if (current(ticket, account)) { busy.value = ''; startObservation(); scheduleAutoPage() } }
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
  if (inputLocked.value || createdTask.value || !selectableKeys.value.has(key)) return
  if (selectedKeys.value.has(key)) selected.value = selected.value.filter(value => value !== key)
  else selected.value = [...selected.value, key]
}
function selectAll() {
  if (inputLocked.value || createdTask.value) return
  selected.value = allSelected.value ? [] : selectable.value.map(media => media.key)
}
const directoryKey = (value: string) => value.trim().replaceAll('/', '\\').replace(/\\+$/, '').toLocaleLowerCase('en-US')
function taskMatches(value: unknown, request: Create): value is DownloadTask {
  return validTask(value as DownloadTask) && (value as DownloadTask).total === request.mediaKeys.length
    && directoryKey((value as DownloadTask).targetDirectory) === directoryKey(request.targetDirectory)
}
function pauseCreationPolling() {
  clearTimeout(preparationTimer); preparationTimer = undefined; preparationEpoch++; pollingCreation.value = false
}
function stopCreationWaiting() {
  pauseCreationPolling()
  stopAutoPagination()
  notice.value = '已停止页面等待，后台准备仍在进行；可点击“核对创建结果”查看原结果。'
}
function acceptPreparation(value: unknown, request: Create) {
  if (!validBotPreparation(value) || value.operationId !== request.operationId
    || value.state === 'READY' && !taskMatches(value.task, request)) throw new Error('下载准备回执与本次选择不符，请核对原创建结果。')
  preparation.value = value; creationFeedback.value = ''
  if (value.state === 'READY') {
    pauseCreationPolling(); createdTask.value = value.task; createRequest.value = null
    notice.value = '下载任务已创建，可到任务中心查看进度。'
  } else if (value.state === 'FAILED') {
    pauseCreationPolling(); stopAutoPagination(); createRequest.value = null; creationFailed(value.error!)
  }
}
function startCreationPolling() {
  pauseCreationPolling()
  if (!ready.value || props.active === false || !createRequest.value || preparation.value?.state !== 'PREPARING') return
  pollingCreation.value = true; scheduleCreationPoll(preparationEpoch)
}
function scheduleCreationPoll(epoch: number) {
  if (epoch !== preparationEpoch || !pollingCreation.value || !ready.value || props.active === false || !createRequest.value) return
  preparationTimer = setTimeout(() => { preparationTimer = undefined; void pollCreation(epoch) }, 2000)
}
async function pollCreation(epoch: number) {
  const request = createRequest.value
  if (!request || epoch !== preparationEpoch || !pollingCreation.value || !ready.value || props.active === false) return
  if (busy.value || preparingCreate.value || previewReadPending.value) { pauseCreationPolling(); return }
  const ticket = generation, account = props.accountId; busy.value = 'lookup'
  let accepted = false
  try {
    const result = await api<unknown>(base + '/preparations/' + request.operationId)
    if (!current(ticket, account) || epoch !== preparationEpoch || !ready.value) return
    acceptPreparation(result, request); accepted = true
  } catch (cause) {
    if (current(ticket, account) && epoch === preparationEpoch) {
      pauseCreationPolling(); stopAutoPagination(); failed(cause, '读取准备进度未完成，请核对原创建结果。'); creationFeedback.value = error.value
    }
  } finally {
    if (current(ticket, account)) busy.value = ''
    if (accepted) { scheduleCreationPoll(epoch); scheduleAutoPage() }
  }
}
async function create() {
  const saved = preview.value
  if (!saved || !canCreate.value) { if (!disposed && !createRequest.value && !createdTask.value) creationFeedback.value = creationHint.value || '当前媒体尚未就绪，请核对选择后再下载。'; return }
  const ticket = generation, account = props.accountId
  pauseCreationPolling(); preparation.value = null
  const epoch = preparationEpoch
  preparingCreate.value = true; pauseObservationQueue(); manualButton.value = null; error.value = ''; notice.value = ''; creationFeedback.value = ''
  try {
    const keys = [...selected.value], directory = targetDirectory.value.trim()
    const version = (media: BotMedia) => JSON.stringify([media.key, media.type, media.size, media.fileName, media.message.chatId, media.message.messageId])
    const savedMedia = new Map(saved.media.map(media => [media.key, media]))
    const versions = new Map(keys.map(key => [key, version(savedMedia.get(key)!)]))
    const flight = previewFlight
    if (flight && !await flight.done) {
      if (current(ticket, account)) { creationRecheckRequired.value = true; creationFailed(error.value || '消息核对未完成，请刷新消息核对后再下载。') }
      return
    }
    if (!current(ticket, account)) return
    if (!ready.value) { creationFailed('连接暂不可用，请恢复连接后核对已选媒体。'); return }
    const latest = preview.value
    if (!latest || latest.id !== saved.id) { creationFailed('本次资源已变化，请重新读取资源后选择媒体。'); return }
    const latestMedia = new Map(latest.media.map(media => [media.key, media]))
    if (keys.some(key => { const media = latestMedia.get(key); return !media || version(media) !== versions.get(key) })) {
      creationFailed('所选媒体已变化或不再可见，请核对当前媒体后再次点击下载。'); return
    }
    const request: Create = { operationId: botOperationId(), sourceId: latest.id, revision: latest.revision, mediaKeys: keys, targetDirectory: directory }
    createRequest.value = request; busy.value = 'create'
    try {
      const result = await api<unknown>(base + '/preparations', { method: 'POST', body: request, timeout: 60000 })
      if (!current(ticket, account)) return
      acceptPreparation(result, request)
    } catch (cause) {
      if (current(ticket, account)) {
        stopAutoPagination()
        failed(cause, '创建任务结果尚未确认。')
        if (cause instanceof ApiError && cause.status >= 400 && cause.status < 500) createRequest.value = null
        else error.value += ' 参数已冻结，请核对创建结果，不会自动重新提交。'
        creationFeedback.value = error.value
      }
    } finally { if (current(ticket, account)) busy.value = '' }
  } catch (cause) {
    if (current(ticket, account)) {
      stopAutoPagination()
      failed(cause, '准备下载任务未完成，请核对后重试。')
      if (createRequest.value) error.value += ' 参数已冻结，请核对创建结果，不会自动重新提交。'
      creationFeedback.value = error.value
    }
  } finally { if (current(ticket, account)) {
    preparingCreate.value = false; if (epoch === preparationEpoch) startCreationPolling()
    scheduleAutoPage()
  } }
}
async function lookup() {
  const request = createRequest.value
  if (!request || !ready.value || busy.value || props.active === false) return
  pauseCreationPolling()
  const ticket = generation, account = props.accountId, epoch = preparationEpoch; busy.value = 'lookup'; error.value = ''
  let accepted = false
  try {
    const result = await api<unknown>(base + '/preparations/' + request.operationId)
    if (!current(ticket, account) || epoch !== preparationEpoch || !ready.value) return
    acceptPreparation(result, request); accepted = true
  } catch (cause) { if (current(ticket, account) && epoch === preparationEpoch) { failed(cause, '核对任务未完成。'); creationFeedback.value = error.value } }
  finally { if (current(ticket, account)) { busy.value = ''; if (accepted) { startCreationPolling(); scheduleAutoPage() } } }
}
function continueBrowsing() {
  if (!ready.value || inputLocked.value || autoPaging.value || !createdTask.value) return
  preparation.value = null; createdTask.value = null; selected.value = []; notice.value = '可继续翻页或选择另一批媒体。'; creationFeedback.value = ''
}
function viewTasks() {
  if (autoPaging.value) stopAutoPagination('已停止自动采集。')
  emit('tasks', 'download')
}
onBeforeUnmount(() => { pauseCreationPolling(); stopAutoPagination(); stopObservation(); disposed = true; generation++ })
</script>

<template>
  <section class="bot-download" aria-label="机器人下载">
    <p class="bot-help">在 Telegram 打开要下载的页／组，再点击“读取当前页／组”。可从任意页开始，也可手动翻页后重新读取；后续分页只追加本次媒体。默认自动翻页采集至末页；采集不等待文件下载；采集时保留可用的原文件引用，选中后在后台下载。读取和下载不设媒体数量上限。</p>
    <div class="bot-source"><label>机器人用户名或会话 ID<input v-model="source" class="input" :disabled="browsingLocked" placeholder="@example_bot 或 https://t.me/example_bot" maxlength="2048" autocomplete="off" @keydown.enter.prevent="open" /></label><button type="button" class="btn primary" :disabled="!ready || browsingLocked || previewReadPending || !source.trim()" @click="open">{{ busy === 'open' ? '读取中…' : '读取当前页／组' }}</button></div>
    <label class="bot-auto-option"><input v-model="autoPagination" type="checkbox" :disabled="browsingLocked" />自动翻页采集至末页</label>
    <details class="bot-advanced"><summary>高级等待设置</summary><div class="bot-wait-settings"><label>回复稳定等待（秒）<input v-model.number="waitSeconds" class="input" type="number" min="2" max="60" step="1" :disabled="!ready || browsingLocked || observing || previewReadPending" /></label><label>最长等待（秒）<input v-model.number="timeoutSeconds" class="input" type="number" min="15" max="300" step="1" :disabled="!ready || browsingLocked || observing || previewReadPending" /></label></div></details>
    <p v-if="!waitSettingsValid" class="notice warning" role="status">稳定等待为 2～60 秒；最长等待为 15～300 秒，且不少于稳定等待的两倍。</p>
    <p v-if="!authorized" class="notice warning">请先在设置中连接 Telegram。</p>
    <div v-if="error" class="notice warning" role="alert">{{ error }}</div><p v-if="notice" class="bot-notice" role="status">{{ notice }}</p>
    <template v-if="preview">
      <header class="bot-preview-heading"><div><strong>{{ preview.bot.title }}</strong><span>{{ pageLabel }}</span></div><button type="button" class="btn" :disabled="!ready || browsingLocked || previewReadPending || !!createdTask" @click="read('refresh')"><AppIcon name="refresh" />{{ busy === 'refresh' ? '读取中…' : '刷新消息' }}</button></header>
      <div class="bot-auto-status" role="status"><template v-if="autoPaging"><span>正在自动采集 · 已读取 {{ visibleMedia.length }} 个媒体</span><button type="button" class="btn" @click="stopAutoPagination('已停止自动采集，当前页回复仍会读取完成，可下载已读取的媒体。')">停止自动采集</button></template><button v-else-if="preview.paginationStatus !== 'END' && !createdTask" type="button" class="btn primary" :disabled="!canPress" @click="startAutoPagination">自动采集后续页／组</button></div>
      <p v-if="paginationNotice" :class="['notice', { warning: !reviewingAutoPage }]" role="status">{{ paginationNotice }}</p>
      <div v-if="preview.state !== 'READY' || pressUnknown || waitUnknown || waitingExpired" class="bot-wait-status" role="status"><div><strong v-if="waitingExpired">已达到最长等待时间</strong><strong v-else-if="waitUnknown">继续等待的结果待核对，请刷新消息</strong><strong v-else-if="observing">等待回复稳定 · 最多剩余 {{ remainingSeconds }} 秒</strong><strong v-else>自动观察已停止</strong><small>{{ preview.wait?.progressObserved ? '已观察到回复变化，等待本批消息稳定。' : '尚未确认本批回复已稳定。' }} 不会再次点击分页按钮。</small></div><button v-if="observing" type="button" class="btn" @click="stopWaiting">停止等待</button><button v-else type="button" class="btn" :disabled="!canContinueWait" @click="continueWait">{{ busy === 'wait' ? '提交中…' : '继续等待' }}</button></div>
      <p v-if="preview.controlMessageId" class="bot-control-text">{{ preview.messages.find(message => message.id === preview!.controlMessageId)?.text }}</p>
      <div class="bot-buttons"><button v-if="recommendation" type="button" class="btn primary bot-next" :disabled="!canPress || autoPaging" @click="press(recommendation)">{{ busy === 'press' ? '翻页中…' : '下一页／组：' + recommendation.text }}</button><span v-if="preview.paginationStatus === 'END'" class="bot-end">机器人已显示最后一页／组</span><span v-else-if="preview.paginationStatus === 'UNKNOWN'" class="bot-help">未识别到可靠的下一页，可在下方手动选择分页按钮。</span></div>
      <details v-if="controls.length" class="bot-manual"><summary>手动选择其他分页按钮</summary><div class="bot-buttons"><button v-for="button in controls" :key="button.id" type="button" class="btn" :disabled="!canPress || autoPaging || !button.supported" @click="chooseButton(button)">{{ button.text }}</button></div><p class="bot-help">仅选择翻页按钮。链接、广告、付款及不支持的按钮不会执行。</p></details>
      <div v-if="manualButton" class="bot-confirm" role="group" aria-label="确认分页按钮"><p>将点击“{{ manualButton.text }}”。请确认这是你要使用的分页按钮。</p><button type="button" class="btn primary" :disabled="!canPress || autoPaging" @click="press(manualButton)">确认点击</button><button type="button" class="btn" @click="manualButton=null">取消</button></div>
      <details class="bot-messages"><summary>本次资源消息（{{ preview.messages.length }}）</summary><article v-for="message in preview.messages" :key="message.id"><p>{{ message.text || '媒体消息' }}</p><small>{{ message.media.length }} 个媒体</small></article></details>
      <div class="bot-media-toolbar"><div><strong>已读取 {{ visibleMedia.length }} 个媒体</strong><span class="bot-selection-count" aria-live="polite">已选 {{ selected.length }} 个</span></div><div class="bot-view-switch" role="group" aria-label="机器人媒体显示方式"><button type="button" :class="{selected:display==='list'}" :aria-pressed="display==='list'" @click="display='list'">列表</button><button type="button" :class="{selected:display==='cards'}" :aria-pressed="display==='cards'" @click="display='cards'">卡片</button></div></div>
      <button v-if="selectable.length" type="button" class="btn bot-select-all" :disabled="inputLocked || !!createdTask" @click="selectAll">{{ allSelected ? '取消全选' : '全选媒体' }}</button>
      <div :class="['bot-media',display]"><label v-for="media in visibleMedia" :key="media.key" class="bot-media-item"><input type="checkbox" :checked="selectedKeys.has(media.key)" :disabled="inputLocked || !!createdTask || media.size<=0" @change="select(media.key)" /><span class="bot-media-icon"><AppIcon :name="media.type==='PHOTO'?'photo':media.type==='VIDEO'?'video':media.type==='AUDIO'?'audio':'file'" /></span><span class="bot-media-info"><strong>{{ media.fileName }}</strong><small>{{ botMediaNames[media.type] }} · {{ formatMediaBytes(media.size) }}</small><span v-if="media.caption">{{ media.caption }}</span></span></label></div>
      <p v-if="!visibleMedia.length" class="bot-help">当前页／组还没有可下载媒体，请在 Telegram 打开目标页／组后重新读取。</p>
      <label class="bot-directory">保存目录<input v-model="targetDirectory" class="input" :disabled="inputLocked || !!createdTask" placeholder="例如 D:\TelegramDownloads" autocomplete="off" /></label>
    </template>
    <Teleport :disabled="!actionTarget" :to="actionTarget || 'body'" defer><div v-if="preview || downloadStatus" class="bot-download-footer">
      <p v-if="creationHint && !downloadStatus" class="bot-help" role="status">{{ creationHint }}</p>
      <p v-if="downloadStatus" :class="['notice','bot-download-feedback',createdTask?'success':preparingCreate || busy==='lookup' || pollingCreation && preparation?.state==='PREPARING'?'':'warning']" role="status" aria-live="polite">{{ downloadStatus }}</p>
      <div v-if="preview || createRequest || createdTask" class="bot-download-actions"><template v-if="createRequest"><button type="button" class="btn primary" :disabled="!ready || !!busy" @click="lookup">核对创建结果</button><button v-if="pollingCreation" type="button" class="btn" @click="stopCreationWaiting">停止等待</button></template><button v-else-if="!createdTask" type="button" class="btn primary" :disabled="!canCreate" @click="create()">{{ preparingCreate && busy !== 'create' ? '正在核对已选媒体…' : busy === 'create' ? '创建中…' : '下载选中媒体' }}</button><template v-if="createdTask"><button type="button" class="btn primary" @click="viewTasks">查看下载任务</button><button type="button" class="btn" :disabled="!ready || browsingLocked" @click="continueBrowsing">继续浏览机器人</button></template></div>
    </div></Teleport>
  </section>
</template>

<style scoped>
.bot-auto-option input{accent-color:#2782c7}.bot-auto-status{display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;margin:12px 0;color:#325f83}.bot-auto-status:empty{display:none}.bot-auto-status .btn{font-size:13px}
.bot-wait-settings{display:grid;grid-template-columns:repeat(2,minmax(0,170px));gap:16px}.bot-wait-settings label{margin:8px 0!important}.bot-wait-status{display:flex;align-items:center;justify-content:space-between;gap:12px;background:#f2f7fb;border:1px solid #deebf5;border-radius:6px;padding:12px;margin:14px 0}.bot-wait-status strong,.bot-wait-status small{display:block;font-size:13px;line-height:1.9;overflow-wrap:anywhere}.bot-wait-status strong{font-weight:500;color:#547b97}.bot-wait-status small{font-size:12px;color:#627d90}.bot-wait-status .btn{flex-shrink:0;font-size:12px}@media(max-width:420px){.bot-wait-settings{gap:10px}.bot-wait-status{align-items:stretch;flex-direction:column}.bot-wait-status .btn{align-self:flex-end}}
.bot-download{min-width:0;font-size:13px;color:#66859c}.bot-help,.bot-notice{font-size:13px;line-height:1.9;margin:10px 0;color:#627d90}.bot-notice{color:#548671}.bot-source{display:flex;align-items:end;gap:12px}.bot-source>label{flex:1;min-width:0}.bot-download label:not(.bot-media-item){display:flex;flex-direction:column;gap:8px;font-size:13px;margin:15px 0}.bot-download .input{width:100%;min-width:0}.bot-source>.btn{flex-shrink:0;margin-bottom:15px}.bot-preview-heading,.bot-media-toolbar{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:22px 0 14px}.bot-preview-heading>div,.bot-media-toolbar>div:first-child{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.bot-preview-heading span,.bot-media-toolbar span{font-size:13px;color:#627d90}.bot-control-text{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;padding:14px;background:#f4f8fb;border-radius:6px}.bot-buttons,.bot-download-actions{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:12px 0}.bot-buttons .btn{font-size:13px}.bot-manual,.bot-messages{margin:16px 0;font-size:13px;color:#819aab}.bot-manual>summary,.bot-messages>summary{cursor:pointer}.bot-confirm{padding:14px;border:1px solid #dce8f2;border-radius:6px;background:#f6faff}.bot-confirm p{margin-top:0;overflow-wrap:anywhere}.bot-confirm .btn{margin-right:8px}.bot-end{font-size:13px;color:#588a70}.bot-messages article{border-bottom:1px solid #e5edf4;padding:10px 0}.bot-messages article p{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.9;margin:0}.bot-messages small{display:block;color:#99adba;margin-top:5px}.bot-view-switch{display:flex;gap:4px}.bot-view-switch button{padding:6px 10px;background:white;border:1px solid #d8e4ee;border-radius:5px;color:#7b97ab;font-size:13px}.bot-view-switch button.selected{color:#2079ba;background:#edf6fd}.bot-select-all{font-size:13px;margin-bottom:12px}.bot-media{border:1px solid #e0e9f0;border-radius:7px;overflow:hidden;background:#fff}.bot-media-item{display:flex;align-items:center;gap:12px;padding:14px;border-bottom:1px solid #e7eef4;min-width:0;cursor:pointer}.bot-media-item:last-child{border-bottom:0}.bot-media-item>input{accent-color:#2782c7;flex-shrink:0}.bot-media-icon{display:flex;align-items:center;justify-content:center;background:#eff6fa;color:#88a7ba;width:48px;height:48px;border-radius:5px;flex-shrink:0}.bot-media-icon .icon{width:22px;height:22px}.bot-media-info{min-width:0;display:flex;flex-direction:column;gap:5px;overflow-wrap:anywhere}.bot-media-info strong{font-weight:500;font-size:14px;color:#486b84}.bot-media-info small,.bot-media-info>span{font-size:12px;line-height:1.8;color:#627d90;white-space:pre-wrap}.bot-media.cards{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));border:0;gap:12px;background:transparent;overflow:visible}.bot-media.cards .bot-media-item{border:1px solid #dfe8f0;border-radius:7px;background:white;align-items:flex-start;flex-wrap:wrap}.bot-media.cards .bot-media-info{flex-basis:100%}.bot-download .notice{overflow-wrap:anywhere;line-height:1.9}@media(max-width:600px){.bot-source{align-items:stretch;flex-direction:column;gap:0}.bot-source>.btn{align-self:flex-end}.bot-preview-heading,.bot-media-toolbar{align-items:flex-start;flex-wrap:wrap}.bot-media.cards{grid-template-columns:repeat(auto-fill,minmax(150px,1fr))}.bot-media-item{gap:8px;padding:11px}.bot-media-icon{width:36px;height:36px}}
.bot-advanced{margin:12px 0;color:#627d90;font-size:13px}.bot-advanced>summary{cursor:pointer;line-height:1.8}.bot-wait-status strong{font-size:14px;font-weight:600;color:#325f83}.bot-preview-heading strong,.bot-media-toolbar strong{font-size:14px;color:#325f83}.bot-media-toolbar .bot-selection-count{font-size:14px;font-weight:600;color:#176da8;background:#edf6fd;padding:5px 10px;border-radius:5px}.bot-buttons .bot-next{font-size:14px;font-weight:600;min-height:40px}.bot-download-footer{font-size:13px}.bot-download-footer .notice{overflow-wrap:anywhere;line-height:1.9}.bot-download-actions .btn{font-size:13px}
.bot-download label.bot-auto-option{display:flex;flex-direction:row;align-items:center;gap:8px}
</style>
