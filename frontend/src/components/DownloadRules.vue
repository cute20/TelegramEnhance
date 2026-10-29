<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { validSourceChannels, type DownloadChannels } from '../downloadChannel'
import { taskLabels } from '../downloadTasks'
import { ruleTime } from '../rules'
import {
  downloadRuleTypes, downloadRuleStates, validDownloadRule, validDownloadRuleInput,
  sameDownloadRuleSelection, canCheckDownloadRule, canTakeoverDownloadRule,
  downloadRuleSourceMode, type DownloadRuleSourceMode,
  type DownloadRuleInput, type DownloadRuleView, type DownloadRuleType,
} from '../downloadRules'
import CompactRecord from './CompactRecord.vue'
import SourceChannelSelect from './SourceChannelSelect.vue'
import RuleFormDialog from './RuleFormDialog.vue'
import NewTransfer from './NewTransfer.vue'

const props = defineProps<{ authorized: boolean; available: boolean; accountId: string | null }>()
const emit = defineEmits<{ accessError: [cause: ApiError]; tasks: [] }>()
const items = ref<DownloadRuleView[]>([]), channels = ref<DownloadChannels | null>(null)
const pending = ref(false), busy = ref(''), error = ref(''), listError = ref(''), channelError = ref(''), notice = ref('')
const intervalSeconds = ref(2)
const formOpen = ref(false), editing = ref<DownloadRuleView | null>(null), manualSource = ref(false)
const browsing = ref<DownloadRuleView | null>(null)
const confirmation = ref<{ kind: 'enable' | 'takeover'; view: DownloadRuleView } | null>(null)
const confirmationDialog = ref<HTMLDialogElement | null>(null)
const empty = (): DownloadRuleInput => ({ name: '', source: '', tags: '', keywords: '', types: ['VIDEO'], includeMain: false,
  targetDirectory: '', window: { mode: 'ALL_DAY', start: null, end: null, timezone: 'Asia/Shanghai' } })
const form = reactive(empty())
const sourceMode = computed(() => downloadRuleSourceMode(form))
const allTypesSelected = computed(() => downloadRuleTypes.every(item => form.types.includes(item.type)))
const canAccess = computed(() => props.authorized && props.available && !!props.accountId)
const channelOptions = computed(() => {
  const options = channels.value?.items ?? [], previous = editing.value?.rule
  return previous && !options.some(channel => channel.id === previous.channelId)
    ? [{ id: previous.channelId, title: previous.channelTitle }, ...options] : options
})
type Mutation = { kind: 'create'; operationId: string; input: DownloadRuleInput }
  | { kind: 'update' | 'enabled' | 'check' | 'takeover'; view: DownloadRuleView; input?: DownloadRuleInput }
const uncertain = ref<Mutation | null>(null)
let generation = 0, requestSequence = 0, disposed = false, refreshQueued = false
let timer: ReturnType<typeof setTimeout> | undefined
const base = '/rules/download'
const clone = <T,>(value: T): T => JSON.parse(JSON.stringify(value)) as T
type Ticket = { generation: number; sequence: number; account: string | null }
function alive(ticket: Ticket) { return !disposed && ticket.generation === generation && ticket.sequence === requestSequence && ticket.account === props.accountId && canAccess.value }
function stopTimer() { clearTimeout(timer); timer = undefined }
function schedule() { stopTimer(); if (!disposed && canAccess.value && !pending.value) timer = setTimeout(() => { void refresh() }, 5000) }
function begin(kind: string): Ticket | null {
  if (disposed || !canAccess.value || pending.value) return null
  stopTimer(); pending.value = true; busy.value = kind
  return { generation, sequence: ++requestSequence, account: props.accountId }
}
function finish(ticket: Ticket) {
  if (ticket.sequence !== requestSequence) return
  pending.value = false
  if (alive(ticket)) busy.value = ''
  if (disposed) return
  if (refreshQueued && canAccess.value) { refreshQueued = false; void refresh() } else schedule()
}
function report(cause: unknown) { if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause) }
const message = (cause: unknown) => cause instanceof Error ? cause.message : '操作未完成。'
function current(view: DownloadRuleView) { return items.value.find(item => item.rule.id === view.rule.id && item.rule.revision === view.rule.revision && item.rule.version === view.rule.version) }
function browse(view: DownloadRuleView) { const value = current(view); if (value && canAccess.value && !pending.value) browsing.value = clone(value) }
function put(value: DownloadRuleView) { items.value = [value, ...items.value.filter(item => item.rule.id !== value.rule.id)] }
async function refresh() {
  const ticket = begin('list'); if (!ticket) return
  refreshQueued = false
  try {
    const result = await api<{ items: DownloadRuleView[]; intervalSeconds: number }>(base)
    if (!alive(ticket)) return
    if (!result || !Number.isSafeInteger(result.intervalSeconds) || result.intervalSeconds < 1 || result.intervalSeconds > 60 || !Array.isArray(result.items) || result.items.length > 20
      || !result.items.every(item => validDownloadRule(item, ticket.account, items.value.find(old => old.rule.id === item.rule.id)))
      || new Set(result.items.map(item => item.rule.id)).size !== result.items.length) throw new Error('持续下载规则列表无法确认，请刷新核对。')
    items.value = result.items; intervalSeconds.value = result.intervalSeconds; listError.value = ''
    if (confirmation.value) {
      const selected = confirmation.value, latest = current(selected.view)
      if (!latest || selected.kind === 'takeover' && (!canTakeoverDownloadRule(latest)
        || latest.summary.job?.taskId !== selected.view.summary.job?.taskId)) closeConfirmation()
    }
  } catch (cause) { if (alive(ticket)) { listError.value = message(cause); report(cause) } }
  finally { finish(ticket) }
}
async function loadChannels() {
  const ticket = begin('channels'); if (!ticket) return
  channelError.value = ''
  try {
    const result = await api<DownloadChannels>('/transfers/download/channels', { timeout: 50000 })
    if (!alive(ticket)) return
    if (!validSourceChannels(result)) throw new Error('可读频道列表无法确认。')
    channels.value = result
  } catch (cause) { if (alive(ticket)) { channelError.value = message(cause); report(cause) } }
  finally { finish(ticket) }
}
function edit(view: DownloadRuleView | null) {
  if (pending.value || uncertain.value || !canAccess.value || view && !current(view)) return
  closeConfirmation(); editing.value = view ? clone(view) : null
  Object.assign(form, empty(), { sourceMode: 'CHANNEL', postKey: null, mainMessageId: null }, clone(view?.rule.input ?? empty()))
  manualSource.value = false; formOpen.value = true; error.value = ''; notice.value = ''; channelError.value = ''
}
function closeForm() {
  if (pending.value) return
  formOpen.value = false
  if (!uncertain.value) { editing.value = null; error.value = ''; channelError.value = '' }
}
function reopenForm() {
  if (pending.value || !canAccess.value) return
  if (uncertain.value?.kind === 'create' || uncertain.value?.kind === 'update') formOpen.value = true
}
function changeSourceMode(mode: DownloadRuleSourceMode) {
  if (pending.value || uncertain.value || sourceMode.value === mode) return
  Object.assign(form, { sourceMode: mode, source: '', tags: '', keywords: '', postKey: null, mainMessageId: null })
  manualSource.value = false; channelError.value = ''; error.value = ''
}
function chooseSource(source: string) {
  if (pending.value || uncertain.value) return
  form.source = source
  if (!form.name.trim()) form.name = (channelOptions.value.find(channel => channel.id === source)?.title ?? '').slice(0, 80)
}
function toggleType(type: DownloadRuleType) {
  if (pending.value || uncertain.value) return
  form.types = form.types.includes(type) ? form.types.filter(item => item !== type) : [...form.types, type]
}
function toggleAllTypes() {
  if (pending.value || uncertain.value) return
  form.types = allTypesSelected.value ? [] : downloadRuleTypes.map(item => item.type)
}
async function mutate(command: Mutation, path: string, body: unknown, method = 'POST') {
  const ticket = begin(command.kind); if (!ticket) return
  error.value = ''; notice.value = ''
  try {
    const result = await api<DownloadRuleView>(path, { method, body, timeout: 150000 })
    if (!alive(ticket)) return
    const previous = command.kind === 'create' ? undefined : command.view
    if (!validDownloadRule(result, ticket.account, previous)) throw new Error('持续下载规则操作回执无法确认。')
    if (command.kind === 'create' || command.kind === 'update') {
      const input = command.input!
      if (result.rule.enabled || result.rule.version !== (previous ? previous.rule.version + 1 : 1)
        || previous && result.rule.revision <= previous.rule.revision
        || !sameDownloadRuleSelection(result.rule.input, input)
        || downloadRuleSourceMode(input) === 'CHANNEL' && /^-?[1-9]\d*$/.test(input.source) && result.rule.channelId !== input.source) throw new Error('保存的规则版本或范围与本次提交不符，请先核对。')
      formOpen.value = false; editing.value = null
      notice.value = '规则已保存并暂停。请核对范围后启用。'
    } else if (previous && result.rule.version !== previous.rule.version) throw new Error('本次操作的规则版本已变化，请先核对。')
    else if (command.kind === 'enabled') {
      if (result.rule.enabled !== (body as { enabled: boolean }).enabled) throw new Error('启停状态与本次请求不符，请先核对。')
      notice.value = result.rule.enabled ? '持续下载已启用，将按北京时间窗口扫描并实际下载。' : '规则已暂停；已开始的当前文件继续完成，不会启动下一个文件。'
    }
    else if (command.kind === 'check') notice.value = '已执行一次扫描检查；页面不会连续请求下一步。请查看当前进度。'
    else notice.value = '任务已转为手动处理，未立即开始下载。规则仍可继续产生后续任务；原媒体声明保留。'
    put(result); uncertain.value = null
  } catch (cause) {
    if (!alive(ticket)) return
    error.value = message(cause); report(cause)
    if (!(cause instanceof ApiError) || cause.status >= 500 || cause.status === 409) {
      uncertain.value = clone(command); error.value += ' 本次结果未确认，请先核对；不会自动重复提交。'
    }
  } finally { finish(ticket) }
}
async function save() {
  if (pending.value || uncertain.value || !formOpen.value || !canAccess.value) return
  const input = clone(form)
  input.name = input.name.trim(); input.source = input.source.trim(); input.targetDirectory = input.targetDirectory.trim()
  if (input.window.mode === 'ALL_DAY') { input.window.start = null; input.window.end = null }
  if (!validDownloadRuleInput(input)) { error.value = '请填写规则名称、有效来源和保存根目录，选择评论媒体或包含主帖媒体，并检查北京时间窗口。'; return }
  const previous = editing.value
  if (previous) await mutate({ kind: 'update', view: clone(previous), input }, `${base}/${encodeURIComponent(previous.rule.id)}`, { revision: previous.rule.revision, input }, 'PUT')
  else { const operationId = crypto.randomUUID(); await mutate({ kind: 'create', operationId, input }, base, { operationId, input }) }
}
async function lookup() {
  const command = uncertain.value; if (!command) return
  const ticket = begin('lookup'); if (!ticket) return
  try {
    const result = await api<DownloadRuleView | null>(command.kind === 'create' ? `${base}/by-operation/${command.operationId}` : `${base}/${encodeURIComponent(command.view.rule.id)}`)
    if (!alive(ticket)) return
    if (result === null) { error.value = '尚未查到本次保存，请稍后再次核对；没有重新提交或生成新的操作号。'; return }
    if (!validDownloadRule(result, ticket.account, command.kind === 'create' ? undefined : command.view)) throw new Error('核对结果的账号、规则或版本无法确认。')
    if (command.kind === 'create' || command.kind === 'update') {
      const input = command.input!
      if (result.rule.version !== (command.kind === 'create' ? 1 : command.view.rule.version + 1)
        || !sameDownloadRuleSelection(result.rule.input, input)
        || downloadRuleSourceMode(input) === 'CHANNEL' && /^-?[1-9]\d*$/.test(input.source) && result.rule.channelId !== input.source)
        throw new Error('尚未确认本次保存的新版本与范围，请稍后再次核对。')
    }
    put(result); uncertain.value = null; error.value = ''; notice.value = '已读取当前持久状态，请核对版本、启停和任务进度；没有重发操作。'
    if (command.kind === 'create' || command.kind === 'update') { formOpen.value = false; editing.value = null }
  } catch (cause) { if (alive(ticket)) { error.value = message(cause) + ' 未重复提交，请再次核对。'; report(cause) } }
  finally { finish(ticket) }
}
function closeConfirmation() { confirmationDialog.value?.close(); confirmation.value = null }
async function prepareConfirmation(view: DownloadRuleView, kind: 'enable' | 'takeover') {
  const value = current(view)
  if (!value || pending.value || uncertain.value || !canAccess.value || kind === 'enable' && value.rule.enabled
    || kind === 'takeover' && !canTakeoverDownloadRule(value)) return
  confirmation.value = { kind, view: clone(value) }; await nextTick()
  if (!disposed && canAccess.value && confirmation.value && current(confirmation.value.view)) confirmationDialog.value?.showModal()
}
async function confirm() {
  const selected = confirmation.value
  if (!selected || pending.value || uncertain.value || !canAccess.value) return
  const value = current(selected.view)
  closeConfirmation()
  if (!value) { error.value = '规则已变化，请重新核对后操作。'; return }
  if (selected.kind === 'enable') await mutate({ kind: 'enabled', view: clone(value) }, `${base}/${value.rule.id}/enabled`, { revision: value.rule.revision, enabled: true })
  else if (canTakeoverDownloadRule(value) && value.summary.job!.taskId === selected.view.summary.job?.taskId)
    await mutate({ kind: 'takeover', view: clone(value) }, `${base}/${value.rule.id}/takeover`, { revision: value.rule.revision, taskId: value.summary.job!.taskId })
  else error.value = '任务状态或成员已变化，请重新核对后再转为手动。'
}
async function pause(view: DownloadRuleView) {
  const value = current(view)
  if (!value || !value.rule.enabled || pending.value || uncertain.value) return
  await mutate({ kind: 'enabled', view: clone(value) }, `${base}/${value.rule.id}/enabled`, { revision: value.rule.revision, enabled: false })
}
async function check(view: DownloadRuleView) {
  const value = current(view)
  if (!value || !canCheckDownloadRule(value) || pending.value || uncertain.value) return
  await mutate({ kind: 'check', view: clone(value) }, `${base}/${value.rule.id}/check`, { revision: value.rule.revision })
}
const windowLabel = (input: DownloadRuleInput) => input.window.mode === 'ALL_DAY' ? '全天 · 北京时间' : `${input.window.start}—${input.window.end} · 北京时间`
const typeLabel = (input: DownloadRuleInput) => !input.types.length ? '不下载评论媒体' : input.types.length === 5 ? '全部评论类型' : downloadRuleTypes.filter(item => input.types.includes(item.type)).map(item => item.label).join('、')
const filterLabel = (input: DownloadRuleInput) => downloadRuleSourceMode(input) === 'POST' ? `指定主帖：${input.source}` : `标签：${input.tags || '不限'}；关键词：${input.keywords || '不限'}（全部满足，仅匹配主帖文字）`
const scopeLabel = (input: DownloadRuleInput) => downloadRuleSourceMode(input) === 'POST' ? '指定主帖' : '频道筛选'
function alert(view: DownloadRuleView) { return [view.summary.scanError, view.summary.job?.error].filter((text, index, values): text is string => !!text && values.indexOf(text) === index).join('；') }
function progress(view: DownloadRuleView) { const summary = view.summary; return `已检查 ${summary.scanned} 条主帖 · 符合 ${summary.matched} · 待读取/核对 ${summary.unresolved}${summary.errors ? '（其中异常 ' + summary.errors + '）' : ''} · 评论 ${summary.commentPages} 页 · 已观察媒体 ${summary.media} · 已保存 ${summary.completed}` }
const rangeSummary = (view: DownloadRuleView) => downloadRuleSourceMode(view.rule.input) === 'POST'
  ? '范围固定为这条主帖；评论读取状态与新增媒体分别核对。'
  : view.summary.historyDone ? '本轮主帖目录已到边界；评论和待核实范围仍分别处理。' : '主帖目录尚未到边界。'
watch([() => props.authorized, () => props.available, () => props.accountId], () => {
  generation++; stopTimer(); closeConfirmation(); items.value = []; channels.value = null; browsing.value = null
  editing.value = null; formOpen.value = false; uncertain.value = null; error.value = ''; listError.value = ''; notice.value = ''; channelError.value = ''; busy.value = ''; Object.assign(form, empty())
  refreshQueued = canAccess.value
  if (!pending.value && canAccess.value) void refresh()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++; stopTimer(); closeConfirmation(); channels.value = null; items.value = [] })
</script>

<template>
  <section class="download-rules" aria-label="持续下载规则">
    <header class="download-rule-heading"><span class="rule-count">{{ items.length }} 条下载规则</span><div class="download-rule-actions"><button type="button" class="btn" :disabled="!canAccess || pending" @click="refresh">刷新</button><button type="button" class="btn primary" :disabled="!canAccess || pending || !!uncertain" @click="edit(null)">新建下载规则</button></div></header>
    <p v-if="!canAccess" class="notice warning">请先连接 Telegram 并恢复本机服务，查看当前账号的持续下载规则。</p>
    <p v-if="listError" class="notice warning" role="status">{{ listError }}<span v-if="items.length">当前保留上次成功读取的规则。</span></p>
    <p v-if="error && !formOpen" class="notice danger" role="alert">{{ error }}</p><p v-if="notice" class="notice" role="status">{{ notice }}</p>
    <div v-if="uncertain && !formOpen" class="download-rule-uncertain"><span>本次操作需要核对，其他修改已暂停。</span><button type="button" class="btn" :disabled="pending || !canAccess" @click="lookup">核对本次操作结果</button><button v-if="uncertain.kind === 'create' || uncertain.kind === 'update'" type="button" class="btn" :disabled="pending || !canAccess" @click="reopenForm">返回表单</button></div>
    <RuleFormDialog :open="formOpen" :title="editing ? '修改下载规则' : '新建下载规则'" :busy="pending" @close="closeForm">
    <form v-if="formOpen" class="download-rule-form" @submit.prevent="save">
      <p class="download-rule-muted">选择下载范围，设置媒体与保存位置。保存后先暂停，启用后才开始自动下载。</p>
      <p v-if="error" class="notice danger" role="alert">{{ error }}</p>
      <div v-if="uncertain" class="download-rule-uncertain"><span>保存结果尚未确认，请先核对。</span><button type="button" class="btn" :disabled="pending || !canAccess" @click="lookup">核对本次操作结果</button></div>
      <fieldset :disabled="pending || !!uncertain || !canAccess">
        <div class="download-rule-source-modes" role="group" aria-label="下载范围"><button type="button" :class="{ selected: sourceMode === 'CHANNEL' }" :aria-pressed="sourceMode === 'CHANNEL'" @click="changeSourceMode('CHANNEL')">频道筛选</button><button type="button" :class="{ selected: sourceMode === 'POST' }" :aria-pressed="sourceMode === 'POST'" @click="changeSourceMode('POST')">指定主帖</button></div>
        <template v-if="sourceMode === 'CHANNEL'">
        <div class="download-rule-channel"><SourceChannelSelect :model-value="form.source" :channels="channelOptions" :disabled="pending || !!uncertain || !canAccess" @update:model-value="chooseSource" /><button type="button" class="btn" @click="loadChannels">{{ busy === 'channels' ? '读取中…' : '读取可选频道' }}</button></div>
        <p v-if="channelError" class="download-rule-warning">{{ channelError }}</p><p v-if="channels?.partial" class="download-rule-muted">频道发现有界，本次列表可能未列全。其他可访问频道可在下方填写来源。</p><p v-for="warning in channels?.warnings ?? []" :key="warning" class="download-rule-muted">{{ warning }}</p>
        <button type="button" class="btn" :aria-expanded="manualSource" @click="manualSource = !manualSource">{{ manualSource ? '收起来源输入' : '填写其他频道来源' }}</button>
        <label v-if="manualSource">频道来源<input v-model="form.source" class="input" placeholder="频道链接、@用户名、频道ID或私有频道主帖链接" maxlength="2048" autocomplete="off" /></label>
        </template>
        <template v-else><label>主帖链接<input v-model="form.source" class="input" required placeholder="https://t.me/频道名/消息编号" maxlength="2048" autocomplete="off" /></label><small>仅下载这条主帖及其评论中的所选媒体，持续检查后续新增内容。</small></template>
        <label>规则名称<input v-model="form.name" class="input" required maxlength="80" :placeholder="sourceMode === 'POST' ? '例如：收藏主帖的持续下载' : '选择频道后可自动填写，也可以修改'" /></label>
        <template v-if="sourceMode === 'CHANNEL'">
        <div class="download-rule-fields"><label>主帖标签 · 全部满足<input v-model="form.tags" class="input" maxlength="2048" placeholder="留空不限标签" /></label><label>主帖关键词 · 全部包含<input v-model="form.keywords" class="input" maxlength="2048" placeholder="留空不限关键词" /></label></div>
        <small>多项用空格或逗号分隔，标签和关键词同时填写时也须全部满足；只匹配主帖文字。</small>
        </template>
        <fieldset class="download-rule-media"><legend>评论媒体类型 · 可多选</legend><div class="download-rule-types"><button type="button" :class="{ selected: allTypesSelected }" :aria-pressed="allTypesSelected" @click="toggleAllTypes">全部</button><button v-for="item in downloadRuleTypes" :key="item.type" type="button" :class="{ selected: form.types.includes(item.type) }" :aria-pressed="form.types.includes(item.type)" @click="toggleType(item.type)">{{ item.label }}</button></div><label class="download-rule-main"><input v-model="form.includeMain" type="checkbox" /> 包含主帖自身全部媒体（不受评论类型筛选影响）</label><p v-if="!form.types.length" class="download-rule-warning" role="status">{{ form.includeMain ? '未选择评论媒体，仅下载主帖自身媒体。' : '未选择下载内容，请选择评论媒体类型，或勾选包含主帖媒体。' }}</p></fieldset>
        <label>保存根目录<input v-model="form.targetDirectory" class="input" required placeholder="例如 D:\TelegramDownloads" autocomplete="off" /></label>
        <div class="download-rule-window"><label>执行时段<select v-model="form.window.mode" class="input"><option value="ALL_DAY">全天</option><option value="WINDOW">指定北京时间时段</option></select></label><template v-if="form.window.mode === 'WINDOW'"><label>开始<input v-model="form.window.start" class="input" type="time" required /></label><label>结束<input v-model="form.window.end" class="input" type="time" required /></label></template></div>
        <small>北京时间，支持跨午夜。窗口结束或暂停后不启动下一个文件，已开始的当前文件继续完成。</small>
        <p v-if="editing" class="download-rule-warning">修改后规则会暂停，再次启用会重新检查来源，可能再次下载已有内容；已创建的任务保持原参数。</p>
        <div class="download-rule-actions"><button class="btn primary" type="submit">{{ busy === 'create' || busy === 'update' ? '保存中…' : '保存并暂停' }}</button><button class="btn" type="button" @click="closeForm">取消</button></div>
      </fieldset>
    </form>
    </RuleFormDialog>
    <p v-if="canAccess && !items.length && !listError" class="download-rule-empty">{{ busy === 'list' ? '正在读取持续下载规则…' : '暂无持续下载规则。新规则保存后先暂停，不会自动开始下载。' }}</p>
    <CompactRecord v-for="view in items" :key="view.rule.id" :title="view.rule.input.name" :description="`${scopeLabel(view.rule.input)} · ${view.rule.channelTitle} · ${windowLabel(view.rule.input)}`" :status="downloadRuleStates[view.state]" :tone="alert(view) || view.state === 'ATTENTION' ? 'warning' : view.rule.enabled ? 'active' : 'neutral'" :timestamp="view.rule.updatedAt" :default-expanded="false" :alert="alert(view)">
      <dl class="rule-facts">
        <dt>来源</dt><dd>{{ view.rule.channelTitle }} · {{ scopeLabel(view.rule.input) }}<a v-if="downloadRuleSourceMode(view.rule.input) === 'POST'" :href="view.rule.input.source" target="_blank" rel="noreferrer">查看主帖 ↗</a></dd>
        <dt>保存目录</dt><dd><code>{{ view.rule.input.targetDirectory }}</code></dd>
        <dt v-if="downloadRuleSourceMode(view.rule.input) === 'CHANNEL'">主帖筛选</dt><dd v-if="downloadRuleSourceMode(view.rule.input) === 'CHANNEL'">{{ filterLabel(view.rule.input) }}</dd>
        <dt>下载内容</dt><dd>{{ typeLabel(view.rule.input) }} · {{ view.rule.input.includeMain ? '包含主帖附件' : '不含主帖附件' }}</dd>
      </dl>
      <p v-if="view.state === 'WAITING_WINDOW'" class="download-rule-muted">下次运行：{{ ruleTime(view.nextAllowedAt) }}</p>
      <p v-if="view.summary.job" class="rule-latest">最近任务：{{ taskLabels[view.summary.job.state] ?? view.summary.job.state }} · 已保存 {{ view.summary.job.completed }}/{{ view.summary.job.total }}<span v-if="view.summary.job.manual"> · 已转为手动</span><span v-else-if="view.summary.job.ruleVersion !== view.rule.version"> · 旧版本任务</span></p>
      <p v-if="view.summary.job?.held" class="download-rule-warning">任务已手动暂停，请到任务中心处理。</p>
      <details class="rule-observation"><summary>读取进度</summary><p>{{ progress(view) }}</p><p>{{ rangeSummary(view) }}</p></details>
      <div class="download-rule-actions"><button class="btn" type="button" :disabled="pending || !!uncertain || !canAccess || !canCheckDownloadRule(view)" @click="check(view)">立即检查</button><button v-if="view.summary.job" class="btn" type="button" @click="emit('tasks')">查看任务</button><button v-if="canTakeoverDownloadRule(view)" class="btn" type="button" :disabled="pending || !!uncertain || !canAccess" @click="prepareConfirmation(view, 'takeover')">将此任务转为手动</button></div>
      <template #actions><button type="button" class="btn" :disabled="pending || !canAccess" @click="browse(view)">浏览帖子</button><button type="button" class="btn" :disabled="pending || !!uncertain || !canAccess" @click="view.rule.enabled ? pause(view) : prepareConfirmation(view, 'enable')">{{ view.rule.enabled ? '暂停' : '启用' }}</button><button type="button" class="btn" :disabled="pending || !!uncertain || !canAccess" @click="edit(view)">修改规则</button></template>
    </CompactRecord>
  </section>
  <NewTransfer v-if="browsing" :key="browsing.rule.id + ':' + browsing.rule.revision" preview-only :browse-source="browsing.rule.input" :authorized="authorized" :available="available" :account-id="accountId" @close="browsing = null" @settings="browsing = null" @access-error="emit('accessError', $event)" />
  <dialog ref="confirmationDialog" class="download-rule-dialog" @cancel="closeConfirmation"><template v-if="confirmation"><h2>{{ confirmation.kind === 'enable' ? '确认启用持续下载' : '确认转为手动处理' }}</h2><strong>{{ confirmation.view.rule.input.name }} · {{ confirmation.view.rule.channelTitle }}</strong><template v-if="confirmation.kind === 'enable'"><p v-if="downloadRuleSourceMode(confirmation.view.rule.input) === 'POST'">只持续检查这条指定主帖及其评论，所选媒体会实际下载，重复媒体单独保存。</p><p v-else>会扫描全部可读历史并持续检查新帖、旧帖评论，符合条件的媒体会实际下载，重复媒体单独保存。</p><p>{{ filterLabel(confirmation.view.rule.input) }}<br />{{ typeLabel(confirmation.view.rule.input) }}<br />{{ confirmation.view.rule.input.includeMain ? '包含主帖自身全部媒体' : '不含主帖自身媒体' }}</p><p>保存根目录：<code>{{ confirmation.view.rule.input.targetDirectory }}</code><br />{{ windowLabel(confirmation.view.rule.input) }}</p><p>当前文件继续完成后才受暂停和窗口结束限制。修改保存会新建版本从头扫描，可能再次下载已有内容；旧任务不改。</p></template><template v-else><p>仅将当前停稳的任务解除规则控制，参数、文件和状态保留，不会立即开始下载。之后请到任务中心继续或处理。</p><p>规则若仍启用，可以继续产生后续任务；原媒体声明保留，不会重复当前页。</p></template><div class="download-rule-actions"><button class="btn" type="button" :disabled="pending" @click="closeConfirmation">取消</button><button class="btn" type="button" :disabled="pending" @click="confirm">{{ confirmation.kind === 'enable' ? '确认启用并允许下载' : '确认转为手动' }}</button></div></template></dialog>
</template>

<style scoped>
.download-rules{margin-bottom:34px;padding-bottom:28px;border-bottom:1px solid #dce7f0;min-width:0}.download-rule-heading{display:flex;justify-content:space-between;gap:16px;align-items:center;margin-bottom:15px}.download-rule-heading>div:first-child{min-width:0;flex:1}.download-rule-heading h2{font-size:16px;color:#486a82;font-weight:600;margin:0 0 7px}.download-rule-heading p,.download-rule-empty{font-size:11px;line-height:1.9;color:#839dae}.download-rule-actions{display:flex;align-items:center;gap:8px;flex-wrap:wrap}.download-rule-actions>.btn{font-size:10px}.download-rule-heading>.download-rule-actions{justify-content:flex-end}.download-rule-empty{padding:25px 15px;text-align:center;background:#fff;border:1px solid #e0e9f1;border-radius:7px}.download-rule-form{margin:10px 0 0;min-width:0}.download-rule-form>fieldset{min-width:0}.download-rule-source-modes{display:flex;gap:8px;margin:15px 0;flex-wrap:wrap}.download-rule-source-modes button{border:1px solid #dce6ed;border-radius:6px;padding:9px 15px;background:#fff;color:#708fa6;font-size:12px;cursor:pointer}.download-rule-source-modes button.selected{background:#263f54;border-color:#263f54;color:#fff}.download-rule-form h3{font-size:14px;font-weight:550;color:#52748d;margin:0 0 15px}.download-rule-form label{display:flex;flex-direction:column;gap:7px;font-size:11px;color:#69879d;margin:15px 0}.download-rule-form small,.download-rule-muted{font-size:10px;line-height:1.9;color:#8aa2b4}.download-rule-form>fieldset>.download-rule-actions{margin-top:17px}.download-rule-fields{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:15px}.download-rule-channel{display:flex;align-items:flex-end;gap:10px;margin-bottom:12px}.download-rule-channel>.btn{font-size:10px;flex-shrink:0}.download-rule-media{margin-top:18px}.download-rule-media legend{font-size:11px;color:#67869e;margin-bottom:9px}.download-rule-types{display:flex;gap:7px;flex-wrap:wrap}.download-rule-types button{border:1px solid #dce6ed;background:#fff;color:#819aac;font-size:10px;border-radius:5px;padding:6px 11px}.download-rule-types button.selected{color:#fff;background:#263f54;border-color:#263f54}.download-rule-form .download-rule-main{flex-direction:row;align-items:flex-start;gap:7px;line-height:1.8}.download-rule-main input{accent-color:#263f54;margin-top:3px}.download-rule-window{display:flex;gap:15px;flex-wrap:wrap}.download-rule-window label{min-width:0}.download-rule-progress{flex-basis:100%;display:flex;flex-direction:column;gap:4px;font-size:10px;line-height:1.9;color:#7895a9;overflow-wrap:anywhere;margin:2px 0 5px;min-width:0}.download-rule-warning{font-size:10px;line-height:1.9;color:#ab824e;overflow-wrap:anywhere}.download-rule-uncertain{display:flex;gap:10px;flex-wrap:wrap;align-items:center;font-size:11px;color:#a4824d;margin:10px 0 18px}.download-rules :deep(.record-body){font-size:11px;line-height:1.9;color:#7a95a9}.download-rules :deep(.record-body)>p{margin:8px 0;overflow-wrap:anywhere}.download-rule-path code{font-size:10px;overflow-wrap:anywhere}.download-rule-job{padding:10px;background:#f4f8fb;border-radius:5px}.download-rules details{font-size:10px;line-height:1.9;overflow-wrap:anywhere}.download-rules details p{margin-top:10px}.download-rules summary{cursor:pointer}.download-rule-dialog{width:min(600px,calc(100vw - 30px));max-height:85vh;overflow:auto;padding:23px;border:1px solid #d9e5ef;border-radius:9px;color:#587a92;font-size:12px;line-height:1.9}.download-rule-dialog::backdrop{background:#12283b88}.download-rule-dialog h2{font-size:16px;margin:0 0 12px}.download-rule-dialog p{margin:13px 0;overflow-wrap:anywhere}.download-rule-dialog strong{overflow-wrap:anywhere}.download-rule-dialog code{font-size:11px;overflow-wrap:anywhere}@media(max-width:700px){.download-rule-heading{flex-direction:column}.download-rule-fields{grid-template-columns:minmax(0,1fr);gap:0}.download-rule-channel{align-items:stretch;flex-direction:column}.download-rule-channel>.btn{align-self:flex-end}.download-rule-form{padding:17px 13px}.download-rule-window{gap:0 10px}.download-rule-window label:first-child{flex-basis:100%}.download-rule-dialog{padding:17px}}
</style>
