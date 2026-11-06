<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { coverageLabel, originalMessageLink, type CommentPage, type Media, type MediaCatalogPage, type PostDetail } from '../library'
import { runCommentRead, type CommentReadProgress } from '../commentReadRunner'
import AppIcon from './AppIcon.vue'
import MediaCollection from './MediaCollection.vue'
import MediaViewSwitch from './MediaViewSwitch.vue'
import InfiniteLoad from './InfiniteLoad.vue'

const props = defineProps<{ postKey: string; authorized: boolean; available: boolean; accountId: string | null; active?: boolean }>()
// Notify the catalog after a saved change; the reader stays on this page.
const emit = defineEmits<{ close: []; update: []; accessError: [cause: ApiError] }>()
type MediaType = Media['type']
const typeOptions: { value: MediaType; label: string }[] = [
  { value: 'VIDEO', label: '视频' }, { value: 'PHOTO', label: '图片' }, { value: 'AUDIO', label: '音频' },
  { value: 'DOCUMENT', label: '文档' }, { value: 'OTHER', label: '其他' },
]
const detail = ref<PostDetail | null>(null)
const mediaPage = ref<MediaCatalogPage | null>(null)
const loadedMedia = ref<Media[]>([])
const mediaView = ref<'list' | 'cards'>('cards')
const types = ref<MediaType[]>([])
const detailBusy = ref(false), mediaBusy = ref(false)
const detailError = ref(''), mediaError = ref('')
const mediaOffset = ref(0), mediaPageVersion = ref(0)
const remoteBusy = ref(''), readError = ref(''), notice = ref('')
const needsCheck = ref(false), stopRequested = ref(false)
const readProgress = ref<CommentReadProgress | null>(null)
const lastReadPage = ref<CommentPage | null>(null)
const pendingMutation = ref<'main' | 'comments' | null>(null)
const mediaLimit = 50
const canAccess = computed(() => props.authorized && props.available && props.active !== false && !!props.accountId && !!props.postKey)
const canDisplay = computed(() => props.authorized && !!detail.value && detail.value.post.key === props.postKey && detail.value.post.accountId === props.accountId)
const controlsEnabled = computed(() => canAccess.value && !remoteBusy.value && !detailBusy.value && !mediaBusy.value)
const readingComments = computed(() => ['comments', 'comments-restart'].includes(remoteBusy.value))
const sourceLink = computed(() => detail.value?.post.mainMessages.map(message => originalMessageLink(message.link)).find(Boolean))
const mainPageItems = computed(() => detail.value?.post.mainMedia ?? [])
const commentPageItems = computed(() => loadedMedia.value.filter(item => item.source === 'COMMENT'))
const selectedTypeLabel = computed(() => types.value.length ? typeOptions.filter(option => types.value.includes(option.value)).map(option => option.label).join('、') : '全部类型')
const commentCoverage = computed(() => detail.value?.commentCoverage ?? lastReadPage.value?.commentCoverage)
const readingSummary = computed(() => commentCoverage.value === 'COMPLETE' && detail.value?.exhausted ? '本轮评论已读完'
  : commentCoverage.value === 'UNAVAILABLE' ? '评论暂不可读取，已收录内容仍可浏览' : '当前展示已收录评论，可更新或继续读取')
const warnings = computed(() => [...new Set([
  ...(detail.value?.post.warnings ?? []), ...(detail.value?.warnings ?? []), ...(detail.value?.commentWarnings ?? []),
  ...(lastReadPage.value?.warnings ?? []), ...(lastReadPage.value?.scanWarnings ?? []),
  ...(lastReadPage.value?.observations?.flatMap(observation => observation.warnings ?? []) ?? []),
])])
let contextGeneration = 0, detailGeneration = 0, mediaGeneration = 0
let disposed = false
function current(context: number) { return !disposed && context === contextGeneration && canAccess.value }
function errorMessage(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 401) return '本机访问已失效，请从启动入口重新打开页面。'
  return cause instanceof Error ? cause.message : '读取未完成，请重试。'
}
function reportAccessError(cause: unknown) {
  if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
}
function validateDetail(result: PostDetail) {
  if (!result?.post || result.post.key !== props.postKey || result.post.accountId !== props.accountId
    || !Array.isArray(result.post.mainMessages) || !Array.isArray(result.post.mainMedia)) throw new Error('主帖或账号已变化，请重新打开这条主帖。')
}
async function savedDetail(context: number) {
  const result = await api<PostDetail>(`/library/posts/${encodeURIComponent(props.postKey)}?limit=1&offset=0`)
  if (!current(context)) return null
  validateDetail(result)
  return result
}
async function loadDetail() {
  if (!canAccess.value) return false
  const request = ++detailGeneration, context = contextGeneration
  detailBusy.value = true; detailError.value = ''
  try {
    const result = await savedDetail(context)
    if (!result || request !== detailGeneration) return false
    detail.value = result
    return true
  } catch (cause) {
    if (current(context) && request === detailGeneration) { detailError.value = errorMessage(cause); reportAccessError(cause) }
    return false
  } finally { if (current(context) && request === detailGeneration) detailBusy.value = false }
}
async function loadMedia(offset = 0, append = false) {
  if (!canAccess.value || append && (mediaBusy.value || !mediaPage.value?.hasMore || !!mediaError.value || !!remoteBusy.value)) return false
  const request = ++mediaGeneration, context = contextGeneration
  mediaBusy.value = true; mediaError.value = ''
  const query = new URLSearchParams({ includeMain: 'false', limit: String(mediaLimit), offset: String(offset) })
  for (const type of types.value) query.append('types', type)
  try {
    const result = await api<MediaCatalogPage>(`/library/posts/${encodeURIComponent(props.postKey)}/media?${query}`)
    if (!current(context) || request !== mediaGeneration) return false
    if (result?.scope !== 'INDEXED_ONLY' || result.offset !== offset || !Array.isArray(result.items)
      || result.items.length > mediaLimit || typeof result.hasMore !== 'boolean' || (result.hasMore && !result.items.length)
      || result.items.some(item => item.source !== 'COMMENT')) throw new Error('媒体读取位置无法确认，请重新加载评论媒体。')
    if (append && mediaPage.value && (result.total !== mediaPage.value.total || result.mainMediaCount !== mediaPage.value.mainMediaCount
      || result.commentMediaCount !== mediaPage.value.commentMediaCount)) throw new Error('本机媒体索引已变化，请刷新列表后继续。')
    const merged = new Map((append ? loadedMedia.value : []).map(item => [item.key, item]))
    for (const item of result.items) {
      if (merged.has(item.key)) throw new Error('媒体读取位置发生重叠，请重新加载评论媒体。')
      merged.set(item.key, item)
    }
    loadedMedia.value = [...merged.values()]
    mediaPage.value = result; mediaOffset.value = result.offset
    if (!append) mediaPageVersion.value++
    return true
  } catch (cause) {
    if (current(context) && request === mediaGeneration) { mediaError.value = errorMessage(cause); reportAccessError(cause) }
    return false
  } finally { if (current(context) && request === mediaGeneration) mediaBusy.value = false }
}
function loadMore() { if (mediaPage.value?.hasMore) void loadMedia(mediaPage.value.offset + mediaPage.value.items.length, true) }
function toggleType(type: MediaType) {
  if (!controlsEnabled.value) return
  const selected = types.value.includes(type) ? types.value.filter(value => value !== type) : [...types.value, type]
  types.value = typeOptions.filter(option => selected.includes(option.value)).map(option => option.value)
}
async function refreshSaved(context: number) {
  const results = await Promise.all([loadDetail(), loadMedia(0)])
  return current(context) && results.every(Boolean)
}
async function refreshLocal() {
  if (!controlsEnabled.value) return
  const context = contextGeneration, checking = needsCheck.value
  if (await refreshSaved(context)) {
    needsCheck.value = false
    if (checking) {
      readError.value = ''; notice.value = '已核对保存的位置。需要继续时，请点击“继续读取评论”；本次没有重新提交读取。'
      emit('update')
    }
  }
}
async function refreshPost() {
  if (!controlsEnabled.value || needsCheck.value || !detail.value) return
  const context = contextGeneration
  remoteBusy.value = 'main'; readError.value = ''; notice.value = ''; pendingMutation.value = 'main'
  try {
    const result = await api<PostDetail>(`/library/posts/${encodeURIComponent(props.postKey)}/refresh`, { method: 'POST', body: {}, timeout: 60000 })
    if (!current(context)) return
    validateDetail(result)
    pendingMutation.value = null; detail.value = result
    emit('update'); notice.value = '主帖已更新。评论可在下方从头更新，或从已保存的位置继续读取。'
    if (!await loadMedia(0) && current(context)) readError.value = '主帖已更新，评论列表刷新未完成；已展示的内容仍保留。'
  } catch (cause) {
    if (!current(context)) return
    needsCheck.value = true; readError.value = `${errorMessage(cause)} 请先核对已保存内容。`
    reportAccessError(cause)
  } finally { if (current(context)) { pendingMutation.value = null; remoteBusy.value = '' } }
}
function validPage(page: CommentPage) {
  return page && page.postKey === props.postKey && Array.isArray(page.media) && Array.isArray(page.messages)
    && Number.isSafeInteger(page.messageCount) && page.messageCount >= 0 && typeof page.exhausted === 'boolean'
    && (page.nextCursor === null || typeof page.nextCursor === 'string')
    && ['COMPLETE', 'PARTIAL', 'UNAVAILABLE'].includes(page.coverage) && Array.isArray(page.warnings)
}
async function readComments(restart = false) {
  if (!controlsEnabled.value || needsCheck.value || !detail.value?.post.canReadComments) return
  const context = contextGeneration, key = props.postKey, account = props.accountId
  const isCurrent = () => current(context) && key === props.postKey && account === props.accountId
  remoteBusy.value = restart ? 'comments-restart' : 'comments'; readError.value = ''; notice.value = ''
  stopRequested.value = false; readProgress.value = { pages: 0, messages: 0, media: 0 }
  try {
    const latest = await savedDetail(context)
    if (!latest || !isCurrent()) return
    detail.value = latest
    if (!latest.post.canReadComments) throw new Error('这条主帖暂不可读取评论，请先更新主帖。')
    if (!restart && latest.exhausted) { notice.value = '本轮评论已读完。更新新增或编辑内容时，请选择“从头更新评论”。'; return }
    const result = await runCommentRead({
      postKey: key, cursor: restart ? null : latest.nextCursor, isCurrent,
      shouldStop: () => stopRequested.value,
      betweenPages: () => new Promise(resolve => window.setTimeout(resolve, 250)),
      async readPage(cursor) {
        pendingMutation.value = 'comments'
        try {
          const page = await api<CommentPage>(`/library/posts/${encodeURIComponent(key)}/comments`, { method: 'POST', body: { cursor }, timeout: 60000 })
          if (isCurrent() && !validPage(page)) throw new Error('评论读取回执无法确认。')
          return page
        } catch (cause) { if (isCurrent()) needsCheck.value = true; throw cause }
        finally { if (isCurrent()) pendingMutation.value = null }
      },
      async onPage(page, progress) {
        lastReadPage.value = page; readProgress.value = { ...progress }
        if (detail.value) detail.value = {
          ...detail.value, nextCursor: page.nextCursor, exhausted: page.exhausted, coverage: page.coverage,
          commentCoverage: page.commentCoverage ?? 'PARTIAL', commentWarnings: page.commentWarnings, warnings: page.warnings,
          indexedCommentMessages: undefined, indexedCommentMedia: undefined,
        }
        emit('update')
        if (!await refreshSaved(context) && isCurrent()) {
          needsCheck.value = true
          throw new Error('本页已保存，列表刷新未完成。请先核对已保存内容，再继续读取。')
        }
      },
    })
    if (!isCurrent() || result.reason === 'stale') return
    if (result.reason === 'exhausted') notice.value = `本轮评论已读到边界，共读取 ${result.pages} 页、${result.messages} 条消息。`
    else if (result.reason === 'stopped') notice.value = '已停止后续读取，已保存的内容仍可浏览。需要时可继续读取。'
    else if (result.reason === 'limit') notice.value = '本次读取已暂停，进度已保存，可继续读取。'
    else { needsCheck.value = true; readError.value = '读取位置没有继续前进，已暂停。请核对已保存内容后继续。' }
  } catch (cause) {
    if (!isCurrent()) return
    readError.value = errorMessage(cause) + (needsCheck.value ? ' 读取已暂停，请先核对已保存内容；不会自动重新提交。' : '')
    reportAccessError(cause)
  } finally { if (isCurrent()) { pendingMutation.value = null; remoteBusy.value = '' } }
}
function stopReading() { if (readingComments.value) stopRequested.value = true }
watch([() => props.postKey, () => props.accountId, () => props.authorized, () => props.available, () => props.active !== false], (values, previous) => {
  const identityChanged = !previous || values[0] !== previous[0] || values[1] !== previous[1]
  const interrupted = !!remoteBusy.value
  contextGeneration++; detailGeneration++; mediaGeneration++; stopRequested.value = true
  if (pendingMutation.value && !identityChanged) {
    needsCheck.value = true; notice.value = '读取已暂停，当前请求可能仍在保存。恢复后请先核对已保存内容。'
  } else if (interrupted && !identityChanged) notice.value = '已停止后续读取，已收录内容仍保留。需要时可继续读取。'
  pendingMutation.value = null; remoteBusy.value = ''; detailBusy.value = false; mediaBusy.value = false
  if (identityChanged || !props.authorized) {
    detail.value = null; mediaPage.value = null; loadedMedia.value = []; detailError.value = ''; mediaError.value = ''
    readError.value = ''; notice.value = ''; needsCheck.value = false
    readProgress.value = null; lastReadPage.value = null; mediaOffset.value = 0; mediaPageVersion.value++
  }
  if (canAccess.value) { void loadDetail(); void loadMedia(0) }
}, { immediate: true })
watch(() => types.value.join(','), () => { if (canAccess.value && !remoteBusy.value) void loadMedia(0) })
onBeforeUnmount(() => { disposed = true; stopRequested.value = true; contextGeneration++; detailGeneration++; mediaGeneration++ })
</script>

<template>
  <article class="library-post-detail" aria-label="主帖资源详情">
    <header class="detail-toolbar">
      <button class="btn detail-back" type="button" aria-label="关闭主帖详情，返回资源目录" @click="emit('close')">← 返回资源库</button>
      <div v-if="canDisplay" class="detail-heading"><h1 :title="detail?.post.title">{{ detail?.post.title || '未命名主帖' }}</h1><span class="detail-channel">{{ detail?.post.channelTitle || '未命名频道' }}</span></div>
      <a v-if="sourceLink && canDisplay" :href="sourceLink" target="_blank" rel="noopener noreferrer" class="detail-source"><AppIcon name="telegram" />在 Telegram 查看原帖 ↗</a>
    </header>
    <div v-if="!authorized" class="notice warning" role="status">请先完成 Telegram 授权，再查看当前账号的资源。</div>
    <div v-else-if="!available" class="notice warning" role="status">连接暂不可用，后续读取已停止。已收录的内容仍保留。</div>
    <div v-if="detailError" class="notice danger" role="alert"><span>{{ detailError }}</span><button class="btn" :disabled="!controlsEnabled" @click="loadDetail">重试详情</button></div>
    <div v-if="detailBusy && !detail" class="detail-loading" role="status">正在打开主帖…</div>
    <template v-if="canDisplay && detail">
      <details class="detail-overview"><summary>主帖信息<span v-if="remoteBusy === 'main'" role="status">正在更新主帖…</span></summary><div class="detail-overview-body"><p class="detail-overview-text">{{ detail.post.text || '此主帖没有文字说明。' }}</p><button class="btn" :disabled="!controlsEnabled || needsCheck" @click="refreshPost"><AppIcon :name="remoteBusy === 'main' ? 'clock' : 'refresh'" />{{ remoteBusy === 'main' ? '正在更新主帖…' : '更新主帖' }}</button></div></details>
      <section class="detail-comments" aria-labelledby="catalog-media-title">
        <div class="comments-heading">
          <h2 id="catalog-media-title">评论媒体 <span v-if="detail.indexedCommentMedia !== undefined">{{ detail.indexedCommentMedia }}</span></h2>
          <div class="comment-reading-actions">
            <template v-if="readingComments"><span class="reading-indicator" role="status">{{ stopRequested ? '正在保存当前页…' : '正在读取评论…' }}</span><button class="btn" :disabled="stopRequested" @click="stopReading">{{ stopRequested ? '正在停止…' : '停止读取' }}</button></template>
            <template v-else><button v-if="needsCheck" class="btn primary" :disabled="!controlsEnabled" @click="refreshLocal"><AppIcon name="refresh" />核对已保存内容</button><template v-else><button class="btn primary" :disabled="!controlsEnabled || !detail.post.canReadComments" @click="readComments(true)"><AppIcon name="refresh" />从头更新评论</button><button v-if="!detail.exhausted" class="btn" :disabled="!controlsEnabled || !detail.post.canReadComments" @click="readComments(false)">继续读取评论</button></template></template>
          </div>
          <MediaViewSwitch v-model="mediaView" />
        </div>
        <div class="comments-filters"><fieldset aria-label="评论媒体类型"><button :class="['filter-chip', { selected: !types.length }]" :disabled="!controlsEnabled" :aria-pressed="!types.length" @click="types = []">全部</button><button v-for="option in typeOptions" :key="option.value" :class="['filter-chip', { selected: types.includes(option.value) }]" :disabled="!controlsEnabled" :aria-pressed="types.includes(option.value)" @click="toggleType(option.value)">{{ option.label }}</button></fieldset><button class="local-refresh" :disabled="!controlsEnabled" title="仅刷新已收录的评论列表" @click="refreshLocal"><AppIcon name="refresh" />刷新列表</button></div>
        <p v-if="readingComments && readProgress" class="reading-progress" role="status" aria-live="polite">本次读取 {{ readProgress.pages }} 页 · {{ readProgress.messages }} 条消息 · {{ readProgress.media }} 个媒体</p>
        <p v-if="!detail.post.canReadComments || commentCoverage === 'UNAVAILABLE'" class="detail-note" role="status">暂时无法读取这条主帖的评论，已收录内容仍可浏览。可展开“主帖信息”更新主帖，或在 Telegram 查看原消息。</p>
        <p v-if="needsCheck && !notice && !readError" class="detail-note" role="status">读取位置需要核对，请先核对已保存内容后继续。</p>
        <p v-if="notice && (!detail.exhausted || needsCheck)" class="reading-notice" role="status">{{ notice }}</p>
        <p v-if="readError" class="notice danger" role="alert">{{ readError }}</p>
        <div class="media-page-heading"><span>{{ selectedTypeLabel }}<template v-if="mediaPage"> · 已加载 {{ commentPageItems.length }} / {{ mediaPage.commentMediaCount }} 个媒体</template></span><span v-if="mediaBusy" role="status">正在刷新列表…</span></div>
        <div v-if="mediaError" class="notice danger" role="alert"><span>{{ mediaError }}</span><button class="btn" :disabled="!controlsEnabled" @click="loadMedia(0)">重新加载媒体</button></div>
        <MediaCollection class="reading-media" :items="commentPageItems" :layout="mediaView" :post-key="postKey" :account-id="accountId" :authorized="authorized" :available="available && active !== false" :version="mediaPageVersion" :empty-text="mediaBusy ? '正在读取已收录的评论媒体…' : types.length ? `暂无匹配「${selectedTypeLabel}」的评论媒体。` : '还没有收录评论媒体，可从头更新评论。'" />
        <InfiniteLoad v-if="mediaPage" :has-more="mediaPage.hasMore" :busy="mediaBusy" :blocked="!!mediaError || !canAccess || !!remoteBusy" :version="mediaOffset + ':' + loadedMedia.length" :context="postKey + ':' + mediaPageVersion + ':' + mediaView" @load="loadMore" />
        <p v-if="mediaPage && !mediaPage.hasMore && loadedMedia.length" class="media-end">已展示全部已收录的匹配评论媒体</p>
      </section>
      <details v-if="mainPageItems.length" class="main-source-group"><summary>主帖附件 <span>{{ mainPageItems.length }} 个</span></summary><MediaCollection :items="mainPageItems" layout="list" :post-key="postKey" :account-id="accountId" :authorized="authorized" :available="available && active !== false" :version="mediaPageVersion" empty-text="此主帖没有附件。" /></details>
      <details class="reading-details"><summary>读取说明与原消息</summary><div class="reading-details-body"><p>{{ readingSummary }}<span v-if="detail.indexedCommentMessages !== undefined"> · {{ detail.indexedCommentMessages }} 条评论消息</span></p><p v-if="readProgress && !readingComments">本次读取 {{ readProgress.pages }} 页 · {{ readProgress.messages }} 条消息 · {{ readProgress.media }} 个媒体</p><p v-if="notice && detail.exhausted && !needsCheck">{{ notice }}</p><p>这里展示已收录内容。更新评论会逐页保存，新内容及修改可通过“从头更新评论”核对；中途停止后可继续读取。</p><p>主帖：{{ coverageLabel(detail.mainCoverage ?? detail.post.mainCoverage ?? 'PARTIAL') }} · 评论：{{ coverageLabel(commentCoverage ?? 'PARTIAL') }}。{{ detail.exhausted ? '本轮已到评论读取边界。' : '评论历史尚未读到边界。' }}</p><ul v-if="warnings.length" class="reading-warnings"><li v-for="warning in warnings" :key="warning">{{ warning }}</li></ul><div class="message-links"><a v-for="message in [...detail.post.mainMessages, ...(detail.post.discussionRoots ?? [])].filter(message => originalMessageLink(message.link))" :key="`${message.chatId}:${message.messageId}`" :href="originalMessageLink(message.link)" target="_blank" rel="noopener noreferrer">{{ message.chatId === detail.post.channelId ? '主帖原消息' : '讨论原消息' }} ↗</a></div><details class="identity-details"><summary>消息标识</summary><dl><dt>频道</dt><dd>{{ detail.post.channelId }}</dd><dt>主帖</dt><dd>{{ detail.post.mainMessageId }}</dd><dt>相册</dt><dd>{{ detail.post.albumId === '0' ? '单条消息' : detail.post.albumId ?? '未提供' }}</dd></dl></details></div></details>
    </template>
  </article>
</template>

<style scoped>
.library-post-detail{width:100%;min-width:0;color:#38556c}
.detail-toolbar{display:flex;align-items:center;gap:16px;margin-bottom:8px;padding-bottom:12px;border-bottom:1px solid #e2eaf1;min-width:0}
.detail-back{flex-shrink:0;background:#fff;font-size:12px}
.detail-heading{display:flex;align-items:baseline;gap:12px;min-width:0;flex:1}
.detail-heading h1{min-width:0;margin:0;font-size:20px;font-weight:650;line-height:1.5;color:#24455f;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.detail-channel{flex-shrink:0;max-width:30%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px;color:#7890a3}
.detail-source{display:inline-flex;align-items:center;gap:7px;margin-left:auto;flex-shrink:0;color:#277dbd;text-decoration:none;font-size:12px;white-space:nowrap}
.detail-source .icon{width:15px;height:15px}
.detail-overview{margin:0 0 14px;font-size:12px;color:#7890a3}
.detail-overview>summary{cursor:pointer;line-height:1.8;padding:3px 0}
.detail-overview>summary>span{margin-left:14px;color:#527b98}
.detail-overview-body{padding:10px 0 2px}
.detail-overview-text{margin:0 0 10px;white-space:pre-wrap;overflow-wrap:anywhere;font-size:13px;line-height:1.8;color:#60798d;max-width:1000px}
.detail-overview-body>.btn{font-size:12px;min-height:33px;background:#fff}
.detail-comments{min-width:0}
.comments-heading{display:flex;align-items:center;gap:10px 16px;flex-wrap:wrap;margin-bottom:10px}
.comments-heading h2{margin:0;font-size:18px;color:#2c4e68;line-height:1.6;white-space:nowrap}
.comments-heading h2>span{font-size:13px;color:#8ca0af;font-weight:500;margin-left:7px}
.comment-reading-actions{display:flex;align-items:center;justify-content:flex-end;gap:8px;flex-wrap:wrap;margin-left:auto}
.comment-reading-actions>.btn{min-height:33px;font-size:12px}
.reading-indicator{font-size:12px;color:#567b95}
.reading-progress,.reading-notice,.detail-note{font-size:12px;line-height:1.8;color:#6c8699;margin:8px 0}
.reading-notice{color:#5f8b77}
.comments-filters{display:flex;align-items:center;justify-content:space-between;gap:14px;margin:0 0 9px}
.comments-filters fieldset{display:flex;flex-wrap:wrap;gap:7px;border:0;padding:0;margin:0}
.filter-chip{min-height:33px;font-size:12px;border:1px solid #dfe8f0;border-radius:6px;padding:6px 13px;background:#fff;color:#6c8497;cursor:pointer}
.filter-chip.selected{background:#eaf3fd;border-color:#bad4ec;color:#2878b8}
.filter-chip:disabled{cursor:default;opacity:.6}
.local-refresh{display:inline-flex;align-items:center;gap:6px;border:0;background:none;color:#7e97aa;font-size:11px;cursor:pointer;white-space:nowrap;padding:7px 0 7px 9px}
.local-refresh .icon{width:13px;height:13px}
.local-refresh:disabled{opacity:.5;cursor:default}
.media-page-heading{display:flex;justify-content:space-between;gap:12px;font-size:11px;color:#8ba1b2;line-height:1.8;margin-bottom:10px}
.media-end{font-size:11px;text-align:center;color:#9aabb8;padding:15px 0 10px}
.main-source-group{margin:20px 0 0;border:1px solid #e1eaf1;border-radius:9px;background:#fff;padding:0 16px}
.main-source-group>summary{font-size:13px;color:#5e7c94;cursor:pointer;padding:12px 0}
.main-source-group>summary>span{font-size:12px;color:#94a6b4;margin-left:8px}
.main-source-group[open]{padding-bottom:16px}
.reading-details{border-top:1px solid #e3eaf0;margin-top:18px;padding:13px 2px;color:#839aaa}
.reading-details>summary{font-size:12px;cursor:pointer}
.reading-details-body{padding-top:13px;font-size:12px;color:#8096a7;line-height:1.9}
.reading-details-body p{margin:0 0 9px}
.reading-warnings{padding-left:19px;color:#9b896c;overflow-wrap:anywhere}
.message-links{display:flex;flex-wrap:wrap;gap:9px 20px;margin:13px 0}
.message-links a{color:#4285b5;text-decoration:none;font-size:12px}
.identity-details{font-size:11px}
.identity-details>summary{cursor:pointer}
.identity-details dl{display:grid;grid-template-columns:70px minmax(0,1fr);gap:6px 12px}
.identity-details dd{margin:0;overflow-wrap:anywhere}
.notice{font-size:12px;line-height:1.8;margin:10px 0}
.notice>span{overflow-wrap:anywhere}
.notice>.btn{margin-left:auto;font-size:12px}
.detail-loading{font-size:13px;color:#7f98ab;padding:55px 20px;text-align:center}
:deep(.reading-media.cards){grid-template-columns:repeat(auto-fill,minmax(235px,1fr));gap:20px}:deep(.reading-media.cards .media-collection-item){border-radius:10px;contain-intrinsic-size:auto 345px}:deep(.reading-media.cards .collection-image){height:205px}:deep(.reading-media.cards .collection-description){padding:13px 15px 9px;min-height:85px}:deep(.reading-media.cards .collection-description p){-webkit-line-clamp:2}:deep(.reading-media.cards .collection-meta){padding:0 15px 14px}:deep(.collection-description strong){font-size:13px;color:#3e627d}:deep(.collection-description p){font-size:12px;color:#7891a4;line-height:1.85}:deep(.collection-description small),:deep(.collection-meta),:deep(.collection-meta small){font-size:11px}:deep(.reading-media.list .media-collection-item){grid-template-columns:128px minmax(0,1fr) 80px 120px;gap:18px;padding:17px}:deep(.reading-media.list .collection-image){width:128px;height:96px}:deep(.reading-media.list .collection-description p){-webkit-line-clamp:3}:deep(.collection-empty){font-size:13px;line-height:1.9;padding:60px 18px;background:#f5f9fc;border:1px dashed #dce7ef}:deep(.infinite-load){font-size:12px}:deep(a:focus-visible),button:focus-visible,summary:focus-visible{outline:2px solid #3889c8;outline-offset:3px}
@media(min-width:1600px){:deep(.reading-media.cards){grid-template-columns:repeat(auto-fill,minmax(260px,1fr))}:deep(.reading-media.cards .collection-image){height:225px}}
@media(max-width:950px){.detail-toolbar{flex-wrap:wrap;gap:8px 12px}.detail-heading{flex-wrap:wrap;gap:0 10px}.detail-heading h1{max-width:100%}.detail-channel{max-width:100%}:deep(.reading-media.cards){grid-template-columns:repeat(auto-fill,minmax(205px,1fr));gap:15px}:deep(.reading-media.cards .collection-image){height:185px}}
@media(max-width:600px){.detail-toolbar{padding-bottom:10px}.detail-heading{flex-basis:calc(100% - 145px)}.detail-source{font-size:11px}.detail-channel{font-size:11px}.comments-heading{gap:7px 10px}.comments-heading h2{margin-right:auto}.comment-reading-actions{order:1;flex-basis:100%;justify-content:flex-start;margin-left:0}.comments-filters{align-items:flex-start;gap:8px}.filter-chip{padding:5px 10px;font-size:11px}.local-refresh{font-size:10px}.media-page-heading{flex-wrap:wrap}:deep(.reading-media.cards){grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}:deep(.reading-media.cards .collection-image){height:150px}:deep(.reading-media.cards .collection-description){padding:10px 11px 7px;min-height:80px}:deep(.reading-media.cards .collection-meta){padding:0 11px 11px}:deep(.reading-media.list .media-collection-item){grid-template-columns:85px minmax(0,1fr);gap:7px 12px;padding:13px}:deep(.reading-media.list .collection-image){width:85px;height:75px;grid-row:span 3}:deep(.reading-media.list .collection-meta){display:flex;gap:9px}}
@media(max-width:380px){:deep(.reading-media.cards){grid-template-columns:minmax(0,1fr)}:deep(.reading-media.cards .collection-image){height:200px}}
</style>
