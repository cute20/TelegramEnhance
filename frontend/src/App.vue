<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AppIcon from './components/AppIcon.vue'
import LibraryWorkspace from './components/LibraryWorkspace.vue'
import NewTransfer from './components/NewTransfer.vue'
import TransferTasks from './components/TransferTasks.vue'
import RuleWorkspace from './components/RuleWorkspace.vue'
import ThumbnailCacheSettings from './components/ThumbnailCacheSettings.vue'
import { validTaskCategory, type TaskCategory } from './taskCategories'
import { api, ApiError, clearAppToken, hasAppToken, type Bootstrap, type Diagnostics, type ProxyType, type Settings, type TelegramStatus } from './api'

type Page = 'library' | 'tasks' | 'rules' | 'settings'
type AuthKind = 'phone' | 'code' | 'password' | 'email' | 'emailCode'
const navigation: { id: Page; label: string; icon: string }[] = [
  { id: 'library', label: '我的资源库', icon: 'library' },
  { id: 'tasks', label: '任务中心', icon: 'tasks' },
  { id: 'rules', label: '自动规则', icon: 'rules' },
  { id: 'settings', label: '设置', icon: 'settings' },
]
const page = ref<Page>('settings')
const currentPage = computed(() => navigation.find(item => item.id === page.value)!)
const loading = ref(hasAppToken)
const busy = ref('')
const error = ref('')
const notice = ref('')
const serviceReachable = ref(false)
const accessInvalid = ref(!hasAppToken)
const exited = ref(false)
const telegram = ref<TelegramStatus | null>(null)
const diagnostics = ref<Diagnostics | null>(null)
const savedSettings = ref<Settings | null>(null)
const form = reactive({ apiId: '', apiHash: '', proxyType: 'DIRECT' as ProxyType, host: '', port: '', username: '', password: '' })
const authValue = ref('')
const currentTime = ref(Date.now())
const proxyResult = ref<{ level: string; message: string; milliseconds: number } | null>(null)
const proxyTestCurrent = ref(false)
const confirmation = ref<'logout' | null>(null)
const dialog = ref<HTMLDialogElement | null>(null)
const newTransferOpen = ref(false)
const taskRefresh = ref(0)
const taskCategory = ref<TaskCategory>('download')
function openTasks(category: TaskCategory = 'download') {
  if (!validTaskCategory(category)) return
  taskCategory.value = category; taskRefresh.value++; newTransferOpen.value = false; page.value = 'tasks'
}
const canOperate = computed(() => !loading.value && !busy.value && !accessInvalid.value && !exited.value && serviceReachable.value)
let pollTimer: number | undefined
let clockTimer: number | undefined
let disposed = false
let statusRequestPending = false

const authLabels: Record<string, string> = {
  DISCONNECTED: '尚未连接', STARTING: '正在启动', WAIT_PHONE: '等待手机号', WAIT_CODE: '等待验证码',
  WAIT_PASSWORD: '等待两步验证', WAIT_EMAIL: '等待邮箱', WAIT_EMAIL_CODE: '等待邮箱验证码',
  WAIT_REGISTRATION: '需要注册账号', WAIT_OTHER_DEVICE: '等待其他设备确认', READY: '已完成授权',
  CLOSING: '正在断开', CLOSED: '已断开', ERROR: '连接出错',
}
const connectionLabels: Record<string, string> = {
  DISCONNECTED: '未连接', UNKNOWN: '等待连接状态', CONNECTING: '正在连接 Telegram',
  CONNECTING_TO_PROXY: '正在连接代理', WAITING_FOR_NETWORK: '等待网络', UPDATING: '正在同步', READY: 'Telegram 已连接',
}
const authLabel = computed(() => telegram.value ? authLabels[telegram.value.authState] ?? `未知授权状态：${telegram.value.authState}` : '等待读取状态')
const connectionLabel = computed(() => {
  if (exited.value) return '已请求关闭项目'
  if (accessInvalid.value) return '请从启动脚本打开'
  if (loading.value) return '正在连接本机服务'
  if (!serviceReachable.value) return '本机服务连接中断'
  const state = telegram.value?.connectionState ?? 'UNKNOWN'
  if (state === 'READY' && telegram.value?.authState !== 'READY') return 'Telegram 网络已连通，账号尚未授权'
  return connectionLabels[state] ?? state
})
const connected = computed(() => serviceReachable.value && !exited.value && telegram.value?.authState === 'READY')
const nativeLabel = computed(() => telegram.value ? (telegram.value.nativeLoaded ? '原生库已加载' : '原生库未加载') : '等待检查')
const activeSession = computed(() => !!telegram.value && !['DISCONNECTED', 'CLOSED', 'ERROR'].includes(telegram.value.authState))
const authStep = computed<{ kind: AuthKind; label: string; placeholder: string; type: string; autocomplete: string; help: string } | null>(() => {
  switch (telegram.value?.authState) {
    case 'WAIT_PHONE': return { kind: 'phone', label: 'Telegram 手机号', placeholder: '+86 …', type: 'tel', autocomplete: 'tel', help: '填写已有 Telegram 账号的手机号，包含国际区号。' }
    case 'WAIT_CODE': return { kind: 'code', label: '登录验证码', placeholder: '输入收到的验证码', type: 'text', autocomplete: 'one-time-code', help: '请按上方提示查看实际收码方式，验证码投递由 Telegram 决定。' }
    case 'WAIT_PASSWORD': return { kind: 'password', label: '两步验证密码', placeholder: '输入 Telegram 两步验证密码', type: 'password', autocomplete: 'off', help: '这是你在 Telegram 中设置的两步验证密码。' }
    case 'WAIT_EMAIL': return { kind: 'email', label: '授权邮箱', placeholder: 'name@example.com', type: 'email', autocomplete: 'email', help: 'Telegram 要求此账号补充邮箱验证，请按提示填写。' }
    case 'WAIT_EMAIL_CODE': return { kind: 'emailCode', label: '邮箱验证码', placeholder: '输入邮箱收到的验证码', type: 'text', autocomplete: 'one-time-code', help: '请检查 Telegram 指定的验证邮箱。' }
    default: return null
  }
})
const authorizationHint = computed(() => {
  const status = telegram.value
  if (status?.authHint) return status.authHint
  if (status?.authState !== 'WAIT_CODE') return ''
  if (!status.codeDelivery || status.codeDelivery.type === 'TelegramMessage') return '验证码已请求发送到已登录的 Telegram 客户端，请查看官方 Telegram 服务消息，不是短信。'
  return 'Telegram 已要求输入登录验证码，请查看账号对应的实际收码方式。'
})
const resendCountdown = computed(() => {
  const deadline = telegram.value?.codeDelivery?.resendAvailableAt
  return deadline == null ? 0 : Math.max(0, Math.ceil((deadline - currentTime.value) / 1000))
})
const canResendCode = computed(() => telegram.value?.authState === 'WAIT_CODE'
  && !!telegram.value.codeDelivery?.nextType
  && telegram.value.codeDelivery.canResend
  && resendCountdown.value === 0)
const dirty = computed(() => {
  const saved = savedSettings.value
  return !!saved && (form.apiId !== (saved.apiId ? String(saved.apiId) : '') || !!form.apiHash || form.proxyType !== saved.proxy.type || form.host !== (saved.proxy.host ?? '') || form.port !== (saved.proxy.port ? String(saved.proxy.port) : '') || form.username !== (saved.proxy.username ?? '') || !!form.password)
})

function reportError(cause: unknown) {
  error.value = cause instanceof Error ? cause.message : '操作失败，请重试。'
  if (cause instanceof ApiError && (cause.status === 401 || cause.status === 403)) {
    accessInvalid.value = true
    stopPolling()
  }
}
function applyTelegram(status: TelegramStatus) {
  if (telegram.value?.authState !== status.authState) authValue.value = ''
  currentTime.value = Date.now()
  telegram.value = status
}
function applySettings(settings: Settings) {
  savedSettings.value = settings
  Object.assign(form, {
    apiId: settings.apiId ? String(settings.apiId) : '', apiHash: '', proxyType: settings.proxy.type,
    host: settings.proxy.host ?? '', port: settings.proxy.port ? String(settings.proxy.port) : '', username: settings.proxy.username ?? '', password: '',
  })
}
function stopPolling() { window.clearTimeout(pollTimer); pollTimer = undefined }
function schedulePoll() {
  stopPolling()
  if (!disposed && !exited.value && !accessInvalid.value) pollTimer = window.setTimeout(poll, 2000)
}
async function readStatus() {
  if (statusRequestPending) return
  statusRequestPending = true
  try {
    const status = await api<TelegramStatus>('/telegram')
    if (!exited.value) { applyTelegram(status); serviceReachable.value = true }
  } catch (cause) {
    serviceReachable.value = false
    if (cause instanceof ApiError && [401, 403].includes(cause.status)) reportError(cause)
    throw cause
  } finally { statusRequestPending = false }
}
async function poll() {
  try {
    if (!savedSettings.value) { await bootstrap(); return }
    await readStatus()
  } catch { /* Persistent connection banner presents service interruption. */ }
  schedulePoll()
}
async function bootstrap() {
  if (exited.value || !hasAppToken) return
  stopPolling()
  loading.value = true
  error.value = ''
  try {
    const initial = await api<Bootstrap>('/bootstrap')
    applySettings(initial.settings)
    applyTelegram(initial.telegram)
    diagnostics.value = initial.diagnostics
    serviceReachable.value = true
    accessInvalid.value = false
  } catch (cause) { serviceReachable.value = false; reportError(cause) }
  finally { loading.value = false; schedulePoll() }
}
function settingsPayload() {
  const apiId = Number(form.apiId)
  if (!Number.isInteger(apiId) || apiId <= 0 || apiId > 2147483647) throw new Error('请填写有效的 Telegram API ID（正整数）。')
  if (!savedSettings.value?.apiHashStored && !form.apiHash.trim()) throw new Error('请填写 Telegram API Hash。')
  if (form.apiHash.trim() && !/^[a-fA-F0-9]{32}$/.test(form.apiHash.trim())) throw new Error('API Hash 应为 32 位十六进制字符，请检查应用凭证。')
  const port = Number(form.port)
  if (form.proxyType !== 'DIRECT') {
    if (!form.host.trim()) throw new Error('请填写代理主机地址。')
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('代理端口应为 1–65535 之间的整数。')
  }
  return { apiId, apiHash: form.apiHash.trim(), proxy: { type: form.proxyType, host: form.host.trim(), port: Number.isInteger(port) && port > 0 ? port : 0, username: form.username.trim(), password: form.password } }
}
async function saveSettings() {
  const saved = await api<Settings>('/settings', { method: 'PUT', body: settingsPayload() })
  applySettings(saved)
  proxyTestCurrent.value = false
}
async function run(label: string, action: () => Promise<void>) {
  if (!canOperate.value) return
  busy.value = label; error.value = ''; notice.value = ''
  try { await action() } catch (cause) { reportError(cause) } finally { busy.value = '' }
}
function save() {
  return run('save', async () => {
    await saveSettings()
    notice.value = activeSession.value ? '设置已保存。当前 Telegram 会话保持原连接配置；请断开后重新连接以应用更改。' : '设置已保存到本机。'
  })
}
function connect() {
  return run('connect', async () => {
    await saveSettings()
    await api('/telegram/connect', { method: 'POST' })
    await readStatus()
    notice.value = '已提交连接请求，请继续按授权状态完成验证。'
  })
}
function authenticate() {
  const step = authStep.value
  if (!step) return
  return run('auth', async () => {
    const value = step.kind === 'password' ? authValue.value : authValue.value.trim()
    if (!value) throw new Error(`请填写${step.label}。`)
    try { await api('/telegram/auth', { method: 'POST', body: { kind: step.kind, value } }); await readStatus() }
    finally { authValue.value = '' }
  })
}
function resendCode() {
  // Recheck the absolute server deadline at click time as well as in the UI.
  currentTime.value = Date.now()
  if (!canResendCode.value) return
  return run('resend', async () => {
    const status = await api<TelegramStatus>('/telegram/auth/resend', { method: 'POST' })
    applyTelegram(status)
    authValue.value = ''
    notice.value = '已请求 Telegram 重新发送验证码，请按更新后的提示查看收码方式。'
  })
}
function disconnect() {
  return run('disconnect', async () => {
    await api('/telegram/disconnect', { method: 'POST' }); authValue.value = ''; await readStatus()
    notice.value = '已请求断开 Telegram，保留本机会话。下次连接将尝试复用有效授权。'
  })
}
function testProxy() {
  return run('proxy', async () => {
    if (dirty.value) throw new Error('请先保存当前设置，再检查已保存的代理配置。')
    proxyResult.value = await api('/proxy/test', { method: 'POST', timeout: 30000 })
    proxyTestCurrent.value = true
  })
}
function refreshDiagnostics() {
  return run('diagnostics', async () => { diagnostics.value = await api<Diagnostics>('/diagnostics'); notice.value = '本机诊断已更新。' })
}
async function openConfirmation(kind: 'logout') { confirmation.value = kind; await nextTick(); dialog.value?.showModal() }
function closeConfirmation() { dialog.value?.close(); confirmation.value = null }
async function confirmAction() {
  const kind = confirmation.value
  closeConfirmation()
  if (kind === 'logout') {
    await run('logout', async () => {
      await api('/telegram/logout', { method: 'POST' }); authValue.value = ''; await readStatus()
      notice.value = '已请求注销此设备的 Telegram 会话，下次连接需要重新授权。'
    })
  }
}
function exitProject() {
  return run('exit', async () => {
    stopPolling()
    try {
      await api('/app/exit', { method: 'POST' }); exited.value = true; stopPolling(); window.clearInterval(clockTimer); serviceReachable.value = false
      authValue.value = ''; form.apiHash = ''; form.password = ''; clearAppToken(); notice.value = ''
    } catch (cause) { schedulePoll(); throw cause }
  })
}
onMounted(() => { clockTimer = window.setInterval(() => { currentTime.value = Date.now() }, 1000); return bootstrap() })
onBeforeUnmount(() => { disposed = true; stopPolling(); window.clearInterval(clockTimer) })
</script>

<template>
  <a class="skip-link" href="#main">跳到主内容</a>
  <div class="shell">
    <aside class="rail" aria-label="主导航">
      <div class="brand"><span class="brand-mark"><AppIcon name="telegram" /></span><div><span class="brand-name">Telegram 资源台</span><small>RESOURCE WORKBENCH</small></div></div>
      <p class="nav-caption">工作空间</p>
      <nav>
        <button v-for="item in navigation" :key="item.id" :class="['nav-item', { active: page === item.id }]" :aria-current="page === item.id ? 'page' : undefined" :aria-label="item.label" @click="page = item.id">
          <AppIcon :name="item.icon" /><span>{{ item.label }}</span>
        </button>
      </nav>
      <div class="rail-bottom">
        <div class="rail-note"><span class="rail-note-head"><AppIcon name="shield" />本机工作空间</span><p>应用凭证、代理和 Telegram 会话由当前 Windows 用户管理。</p></div>
        <div class="rail-account"><span class="avatar"><AppIcon name="telegram" /></span><div><strong>{{ connected ? telegram?.displayName || 'Telegram 已授权' : '连接你的 Telegram' }}</strong><small>{{ connected ? '单账号 · 本机会话' : '在设置中完成首次连接' }}</small></div><span :class="['connection-dot', { online: connected }]" /></div>
      </div>
    </aside>
    <div class="workspace">
      <header class="topbar"><div class="breadcrumb"><span>工作空间</span><AppIcon name="chevron" /><strong>{{ currentPage.label }}</strong></div><div class="top-actions"><span class="local-label"><AppIcon name="lock" />仅本机访问</span><button class="btn new-transfer" :disabled="!canOperate" @click="newTransferOpen = true"><AppIcon name="plus" />新建传输</button></div></header>
      <main id="main" tabindex="-1">
        <div class="page-heading"><div><div class="eyebrow">{{ page === 'settings' ? 'PREFERENCES' : 'WORKSPACE' }}</div><h1>{{ currentPage.label }}</h1><p v-if="page === 'settings' || page === 'library'">{{ page === 'settings' ? '配置本机连接，安全地接入你的 Telegram 账号。' : '按自己的频道整理主帖，浏览已索引的文字、标签与评论媒体。' }}</p></div><button v-if="page === 'settings'" type="button" class="btn close-project" :disabled="!canOperate" @click="exitProject"><AppIcon name="power" />{{ busy === 'exit' ? '正在关闭…' : '关闭项目' }}</button></div>
        <div v-if="exited" class="notice success service-notice" role="status"><AppIcon name="check" /><div><strong>已请求关闭项目</strong><p>后台正在保存并退出，Telegram 会话保留。再次使用时，请重新运行项目启动脚本。</p></div></div>
        <div v-else-if="accessInvalid" class="notice warning service-notice" role="alert"><AppIcon name="lock" /><div><strong>请从项目启动脚本打开界面</strong><p>当前页面缺少有效的本机访问令牌。请使用启动脚本打开的新链接进入。</p></div></div>
        <div v-else-if="!loading && !serviceReachable" class="notice warning service-notice" role="alert"><AppIcon name="info" /><div><strong>本机服务连接中断</strong><p>请确认 Java 后台仍在运行。恢复后会自动重查 Telegram 状态。</p></div><button class="btn" :disabled="!!busy" @click="bootstrap">重新读取</button></div>
        <div v-if="error" class="notice danger" role="alert"><AppIcon name="info" /><span>{{ error }}</span><button class="icon-btn dismiss" aria-label="关闭错误提示" @click="error = ''"><AppIcon name="close" /></button></div>
        <div v-if="notice" class="notice success" role="status"><AppIcon name="check" /><span>{{ notice }}</span><button class="icon-btn dismiss" aria-label="关闭提示" @click="notice = ''"><AppIcon name="close" /></button></div>
        <template v-if="page === 'settings'">
          <div class="stage-banner"><span class="stage-icon"><AppIcon name="link" /></span><div><strong>先连接，再整理你的资源</strong><p>配置应用凭证和独立代理，连接并保留你的 Telegram 本机会话。</p></div></div>
          <div v-if="loading" class="loading-panel" role="status"><span class="spinner" />正在读取本机配置与 Telegram 状态…</div>
          <div v-else class="settings-grid">
            <div class="settings-primary">
              <form id="settings-form" autocomplete="off" @submit.prevent="save" @input="proxyTestCurrent = false">
                <fieldset :disabled="!canOperate">
                  <section class="settings-section" aria-labelledby="credentials-heading">
                    <div class="section-heading"><span class="section-icon"><AppIcon name="telegram" /></span><div><h2 id="credentials-heading">Telegram 应用凭证</h2><p>使用你自己的 Telegram API ID 和 API Hash。</p></div><span v-if="savedSettings?.apiHashStored" class="status-pill green"><AppIcon name="lock" />已安全保存</span></div>
                    <div class="form-grid"><div class="field"><label for="api-id">API ID</label><input id="api-id" v-model="form.apiId" class="input mono" inputmode="numeric" pattern="[0-9]+" placeholder="填写 API ID" spellcheck="false" required /><small>在 Telegram 的 API development tools 中获取。</small></div><div class="field"><label for="api-hash">API Hash</label><input id="api-hash" v-model="form.apiHash" class="input mono" type="password" autocomplete="new-password" :placeholder="savedSettings?.apiHashStored ? '已保存，留空保持不变' : '填写 32 位 API Hash'" :required="!savedSettings?.apiHashStored" spellcheck="false" /><small>{{ savedSettings?.apiHashStored ? '修改时填写新的 Hash，已有值不会回传到页面。' : '仅在本机使用，不要发送到聊天或分享给他人。' }}</small></div></div>
                    <div class="inline-note"><AppIcon name="shield" /><span>敏感配置由 Windows 当前用户保护。手机号和验证码在下方授权区按需填写。</span></div>
                  </section>
                  <section class="settings-section" aria-labelledby="proxy-heading">
                    <div class="section-heading"><span class="section-icon"><AppIcon name="rules" /></span><div><h2 id="proxy-heading">软件独立代理</h2><p>只用于此应用的 Telegram 连接，不修改系统代理。</p></div></div>
                    <div class="field"><label for="proxy-type">连接方式</label><select id="proxy-type" v-model="form.proxyType" class="input"><option value="DIRECT">直连 · 不使用代理</option><option value="SOCKS5">SOCKS5 代理</option><option value="HTTP">HTTP 代理</option></select></div>
                    <template v-if="form.proxyType !== 'DIRECT'">
                      <div class="form-grid proxy-address"><div class="field"><label for="proxy-host">代理主机</label><input id="proxy-host" v-model="form.host" class="input mono" placeholder="127.0.0.1" spellcheck="false" required /><small>填写主机名或 IP 地址，不包含协议前缀。</small></div><div class="field"><label for="proxy-port">端口</label><input id="proxy-port" v-model="form.port" class="input mono" inputmode="numeric" pattern="[0-9]+" placeholder="例如 7890" required /></div></div>
                      <div class="form-grid"><div class="field"><label for="proxy-username">代理用户名 <span class="optional">可选</span></label><input id="proxy-username" v-model="form.username" class="input" autocomplete="off" spellcheck="false" /></div><div class="field"><label for="proxy-password">代理密码 <span class="optional">可选</span></label><input id="proxy-password" v-model="form.password" class="input" type="password" autocomplete="new-password" :placeholder="savedSettings?.proxy.passwordStored ? '已保存，留空保持不变' : '代理无认证时留空'" /></div></div>
                    </template>
                    <div v-else class="direct-note"><AppIcon name="info" /><p>应用将直接连接 Telegram。请确认当前网络支持访问 Telegram。</p></div>
                    <div class="proxy-test"><button type="button" class="btn" :disabled="!canOperate || dirty || !savedSettings" @click="testProxy"><AppIcon :name="busy === 'proxy' ? 'clock' : 'link'" />{{ busy === 'proxy' ? '正在检查…' : '检查已保存配置' }}</button><span>{{ dirty ? '先保存更改，再检查连接' : '检查端口或握手，不代表账号已连接' }}</span></div>
                    <div v-if="proxyResult" :class="['proxy-result', { stale: !proxyTestCurrent }]" role="status"><strong>{{ proxyTestCurrent ? '最近检查' : '上次检查 · 配置已更改' }}</strong><span>{{ proxyResult.message }}</span><small>{{ proxyResult.level }} · {{ proxyResult.milliseconds }} ms</small></div>
                    <p class="section-footnote">Telegram 是否真正可用，以授权区的连接状态和实际授权结果为准。</p>
                  </section>
                  <div class="save-bar"><span><span :class="['unsaved-dot', { visible: dirty }]" />{{ dirty ? '有尚未保存的更改' : savedSettings ? '当前设置已保存' : '等待读取设置' }}</span><button type="submit" class="btn primary" :disabled="!canOperate">{{ busy === 'save' ? '正在保存…' : '保存设置' }}</button></div>
                </fieldset>
              </form>
              <ThumbnailCacheSettings :available="serviceReachable && !loading && !accessInvalid && !exited" @access-error="reportError" />
              <section class="settings-section authorization" aria-labelledby="auth-heading">
                <div class="section-heading"><span class="section-icon"><AppIcon name="shield" /></span><div><h2 id="auth-heading">Telegram 授权与会话</h2><p>连接一个已有账号；有效本机会话会在下次连接时复用。</p></div></div>
                <div class="account-box"><span :class="['account-avatar', { online: connected }]"><AppIcon :name="connected ? 'check' : 'telegram'" /></span><div class="account-identity"><strong>{{ connected ? telegram?.displayName || 'Telegram 账号' : authLabel }}</strong><small>{{ connected ? `Telegram ID · ${telegram?.userId ?? '等待读取'}` : '应用凭证只用于发起连接，账号仍需 Telegram 授权。' }}</small></div><span :class="['status-pill', connected ? 'green' : 'neutral']">{{ authLabel }}</span></div>
                <div class="session-status"><span class="connection-dot" :class="{ online: connected && telegram?.connectionState === 'READY' }" /><span>{{ connectionLabel }}</span><code v-if="telegram">{{ telegram.connectionState }}</code></div>
                <div v-if="telegram?.error" class="notice danger telegram-error" role="alert"><AppIcon name="info" /><span>{{ telegram.error }}</span></div>
                <p v-if="authorizationHint" class="auth-hint">{{ authorizationHint }}</p>
                <form v-if="authStep && !exited" class="auth-form" autocomplete="off" @submit.prevent="authenticate">
                  <div class="field"><label for="auth-value">{{ authStep.label }}</label><div class="input-action"><input id="auth-value" :key="authStep.kind" v-model="authValue" class="input" :type="authStep.type" :autocomplete="authStep.autocomplete" :placeholder="authStep.placeholder" :disabled="!canOperate" spellcheck="false" required /><button type="submit" class="btn primary" :disabled="!canOperate || !authValue">{{ busy === 'auth' ? '正在提交…' : '提交验证' }}<AppIcon name="arrow" /></button></div><small>{{ authStep.help }}</small></div>
                  <div v-if="telegram?.authState === 'WAIT_CODE'" class="code-delivery-options">
                    <template v-if="telegram.codeDelivery?.nextType">
                      <p v-if="resendCountdown > 0" class="section-footnote">Telegram 允许后可重新请求验证码，还需等待 {{ resendCountdown }} 秒。</p>
                      <template v-else><button type="button" class="btn" :disabled="!canOperate || !canResendCode" @click="resendCode"><AppIcon name="refresh" />{{ busy === 'resend' ? '正在重新请求…' : '重新请求验证码' }}</button><p v-if="!telegram.codeDelivery.canResend" class="section-footnote">正在等待 Telegram 确认可以重新请求，请稍后。</p></template>
                    </template>
                    <p v-else class="section-footnote">本次登录未提供其他收码方式，请查看已登录客户端；无法强制短信。</p>
                  </div>
                </form>
                <div v-else-if="telegram?.authState === 'WAIT_REGISTRATION'" class="notice warning"><AppIcon name="info" /><span>当前版本连接已有 Telegram 账号。请先在 Telegram 官方客户端完成注册，再断开并重新连接。</span></div>
                <div v-else-if="telegram?.authState === 'WAIT_OTHER_DEVICE'" class="notice info"><AppIcon name="info" /><span>请按 Telegram 提示在另一台已登录设备上确认授权，状态会自动更新。</span></div>
                <div v-else-if="telegram && !authLabels[telegram.authState]" class="notice warning"><AppIcon name="info" /><span>出现尚未支持的授权状态：{{ telegram.authState }}。请保留此信息并检查后台。</span></div>
                <div class="session-actions"><button v-if="!activeSession" type="button" class="btn primary" :disabled="!canOperate" @click="connect"><AppIcon name="telegram" />{{ busy === 'connect' ? '正在发起连接…' : '保存并连接 Telegram' }}</button><button v-else type="button" class="btn" :disabled="!canOperate || telegram?.authState === 'CLOSING'" @click="disconnect">{{ busy === 'disconnect' ? '正在断开…' : '断开 · 保留会话' }}</button><button type="button" class="btn text-danger" :disabled="!canOperate || !activeSession || telegram?.authState === 'CLOSING'" @click="openConfirmation('logout')">注销 Telegram 会话</button></div>
                <p class="section-footnote">断开和退出应用会保留授权。注销会让此设备的 Telegram 会话失效，再次使用需要重新验证。</p>
              </section>
            </div>
            <aside class="settings-secondary" aria-label="本机运行信息">
              <section class="settings-section runtime-section"><div class="section-heading compact"><h2><AppIcon name="server" />本机运行</h2></div><div :class="['runtime-status', { ready: telegram?.nativeLoaded }]"><span class="connection-dot" :class="{ online: telegram?.nativeLoaded }" />{{ nativeLabel }}</div><dl class="runtime-list"><dt>原生版本</dt><dd>{{ telegram?.nativeVersion || '等待读取' }}</dd><dt>Java</dt><dd>{{ diagnostics?.javaVersion || '等待检查' }}</dd><dt>平台</dt><dd>{{ diagnostics ? `${diagnostics.os} · ${diagnostics.architecture}` : '等待检查' }}</dd><dt>SQLite 模式</dt><dd>{{ diagnostics?.database.journalMode || '等待检查' }}</dd><dt>同步策略</dt><dd>{{ diagnostics?.database.synchronous || '等待检查' }}</dd><dt>数据库完整性</dt><dd>{{ diagnostics?.database.integrity || '等待检查' }}</dd><dt>数据库版本</dt><dd>{{ diagnostics?.database.schemaVersion ?? '等待检查' }}</dd><dt>Windows 保护</dt><dd>{{ diagnostics?.dpapi || '等待检查' }}</dd></dl><details class="data-directory"><summary>应用数据位置</summary><p>默认保存在项目的 data 文件夹中，包含设置、资源库、任务记录和 Telegram 会话；用户媒体另存。</p><code>{{ diagnostics?.dataDirectory || '等待检查' }}</code></details><button type="button" class="btn full-width" :disabled="!canOperate" @click="refreshDiagnostics"><AppIcon name="refresh" />{{ busy === 'diagnostics' ? '正在检查…' : '刷新本机诊断' }}</button></section>
              <section class="helper-card"><h3>首次连接，三步完成</h3><ol class="setup-steps"><li><span>1</span><div><strong>填写应用凭证</strong><p>配置你的 API ID 和 API Hash。</p></div></li><li><span>2</span><div><strong>确认网络方式</strong><p>选择直连，或软件独立代理。</p></div></li><li><span>3</span><div><strong>完成 Telegram 验证</strong><p>手机号、验证码与必要的两步验证。</p></div></li></ol></section>
              <section class="privacy-note"><AppIcon name="shield" /><p>普通资源元数据与 Telegram 会话分开保存。Windows 保护覆盖敏感配置，不代表媒体或整个数据库已加密。</p></section>
            </aside>
          </div>
        </template>
        <LibraryWorkspace v-else-if="page === 'library'" :authorized="telegram?.authState === 'READY'" :available="serviceReachable && !loading && !accessInvalid && !exited" :account-id="telegram?.userId ?? null" @settings="page = 'settings'" @access-error="reportError" />
        <RuleWorkspace @tasks="openTasks" v-else-if="page === 'rules'" :authorized="telegram?.authState === 'READY'" :available="serviceReachable && !loading && !accessInvalid && !exited" :account-id="telegram?.userId ?? null" @access-error="reportError" />
        <section v-else-if="page !== 'tasks'" class="empty-state"><span class="empty-state-icon"><AppIcon :name="currentPage.icon" /></span><h2>{{ currentPage.label }}暂不可用</h2><p>请在设置中检查本机服务与 Telegram 连接状态。</p><button class="btn primary" @click="page = 'settings'">返回设置<AppIcon name="arrow" /></button></section>
        <TransferTasks v-show="page === 'tasks'" :active="page === 'tasks'" v-model:category="taskCategory" :refresh-version="taskRefresh" :authorized="telegram?.authState === 'READY'" :available="serviceReachable && !loading && !accessInvalid && !exited" :account-id="telegram?.userId ?? null" @access-error="reportError" />
      </main>
      <footer class="statusbar"><span><span :class="['connection-dot', { online: connected && telegram?.connectionState === 'READY' }]" />{{ connectionLabel }}</span><span>本机单账号<span class="footer-divider">|</span>会话与媒体目录分开管理</span></footer>
    </div>
  </div>
  <NewTransfer v-if="newTransferOpen" :authorized="telegram?.authState === 'READY'" :available="serviceReachable && !loading && !accessInvalid && !exited" :account-id="telegram?.userId ?? null" @close="newTransferOpen = false" @settings="newTransferOpen = false; page = 'settings'" @tasks="openTasks" @access-error="reportError" />
  <dialog ref="dialog" class="confirmation-dialog" aria-labelledby="confirmation-heading" @cancel="confirmation = null">
    <div class="dialog-heading"><span class="dialog-icon"><AppIcon name="shield" /></span><h2 id="confirmation-heading">注销本机 Telegram 会话？</h2></div>
    <p>这会让当前设备的 Telegram 会话失效。下次连接需要重新输入手机号、验证码及必要的两步验证。</p>
    <div class="dialog-actions"><button class="btn" autofocus @click="closeConfirmation">取消</button><button class="btn danger-button" @click="confirmAction">确认注销会话</button></div>
  </dialog>
</template>

<style scoped>
.page-heading>div{min-width:0}.close-project{flex-shrink:0;align-self:flex-start;margin-top:20px;color:#ad6b68;border-color:#e5d3d1;background:#fff;font-size:11px}.close-project>.icon{width:15px;height:15px}
@media(max-width:480px){.page-heading{flex-wrap:wrap;gap:12px}.close-project{margin-top:0}}
</style>
