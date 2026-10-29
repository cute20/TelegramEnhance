<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { coverageLabel, originalMessageLink, type CommentPage, type Coverage, type Post, type PostDetail } from '../library'
import { runCommentRead, type CommentReadProgress } from '../commentReadRunner'
import AppIcon from './AppIcon.vue'
import LibraryMediaTable from './LibraryMediaTable.vue'

const props = defineProps<{ authorized: boolean; available: boolean; accountId: string | null; initialPostKey?: string | null }>()
const emit = defineEmits<{ settings: []; accessError: [cause: ApiError] }>()
const link = ref('')
const posts = ref<Post[]>([])
const detail = ref<PostDetail | null>(null)
const indexOffset = ref(0)
const mediaOffset = ref(0)
const indexLoaded = ref(false)
const busy = ref('')
const error = ref('')
const notice = ref('')
const lastReadPage = ref<CommentPage | null>(null)
const readProgress = ref<CommentReadProgress | null>(null)
const stopRequested = ref(false)
const readingComments = computed(() => busy.value === 'comments' || busy.value === 'comments-restart')
const remoteState = ref<{ nextCursor: string | null; exhausted: boolean; coverage: Coverage; warnings: string[] } | null>(null)
const canAccess = computed(() => props.authorized && props.available)
const controlsEnabled = computed(() => canAccess.value && !busy.value)
const coverage = computed(() => detail.value?.coverage ?? remoteState.value?.coverage ?? 'PARTIAL')
const mainCoverage = computed(() => detail.value?.mainCoverage ?? detail.value?.post.mainCoverage ?? (detail.value?.post.albumEvidence?.complete ? 'COMPLETE' : undefined))
const commentCoverage = computed(() => detail.value?.commentCoverage ?? lastReadPage.value?.commentCoverage ?? lastReadPage.value?.coverage)
const albumSummary = computed(() => {
  const post = detail.value?.post
  if (!post) return '等待读取主帖'
  if (post.albumId === '0') return '非相册，单条主帖'
  if (post.albumEvidence?.complete) return `相册已核全 · ${post.mainMessages.length} 条成员`
  if (post.albumId) return `相册尚未读全 · 当前已返回 ${post.mainMessages.length} 条成员`
  return '未报告相册标识，是否属于相册待核对'
})
const albumMethod = computed(() => {
  const method = detail.value?.post.albumEvidence?.method
  if (!method) return '后台未提供读取方法'
  const labels: Record<string, string> = {
    SINGLE_MESSAGE: '非相册单条读取',
    ADJACENT_BOUNDARIES: '双侧连续邻接边界核验',
    PARTIAL_SCAN: '扫描边界尚未核全',
  }
  return labels[method] ?? method
})
const mainStatusLabel = computed(() => {
  const labels: Record<string, string> = { COMPLETE: '主帖范围已核全', PARTIAL: '主帖范围尚未核全', UNAVAILABLE: '主帖范围不可读取' }
  return mainCoverage.value ? labels[mainCoverage.value] ?? `未知状态：${mainCoverage.value}` : '主帖范围待确认'
})
const commentStatusLabel = computed(() => {
  const labels: Record<string, string> = { COMPLETE: '本轮评论历史已读完', PARTIAL: '评论范围尚未核全', UNAVAILABLE: '评论历史不可读取' }
  return commentCoverage.value ? labels[commentCoverage.value] ?? `未知状态：${commentCoverage.value}` : '评论历史状态待确认'
})
function indexedCount(value: number | undefined) { return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null }
const indexedMessages = computed(() => indexedCount(detail.value?.indexedCommentMessages))
const indexedMedia = computed(() => indexedCount(detail.value?.indexedCommentMedia))
const warnings = computed(() => [...new Set([...(detail.value?.post.warnings ?? []), ...(detail.value?.warnings ?? []), ...(remoteState.value?.warnings ?? []), ...(lastReadPage.value?.scanWarnings ?? []), ...(lastReadPage.value?.observations?.flatMap(observation => observation.warnings ?? []) ?? [])])])
const lastObservationCounts = computed(() => {
  const observations = lastReadPage.value?.observations
  if (!observations) return null
  const confirmed = observations.filter(observation => observation.mediaComplete).length
  return { confirmed, pending: observations.length - confirmed }
})
const selectedKey = computed(() => detail.value?.post.key ?? '')
const indexLimit = 20
const mediaLimit = 50
let generation = 0
let disposed = false

function current(requestGeneration: number) { return !disposed && requestGeneration === generation && canAccess.value }
async function execute(name: string, action: (requestGeneration: number) => Promise<void>) {
  if (!controlsEnabled.value) return
  const requestGeneration = generation
  busy.value = name; error.value = ''; notice.value = ''
  try { await action(requestGeneration) }
  catch (cause) {
    if (!current(requestGeneration)) return
    error.value = cause instanceof Error ? cause.message : '读取失败，请重试。'
    if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
  } finally { if (requestGeneration === generation) busy.value = '' }
}
async function loadIndexData(offset: number, requestGeneration: number) {
  const result = await api<Post[]>(`/library/posts?limit=${indexLimit}&offset=${offset}`)
  if (!current(requestGeneration)) return
  posts.value = result; indexOffset.value = offset; indexLoaded.value = true
}
function loadIndex(offset = indexOffset.value) { return execute('index', requestGeneration => loadIndexData(offset, requestGeneration)) }
function applyDetail(result: PostDetail, offset: number, updateRemote = true) {
  detail.value = result; mediaOffset.value = offset
  const indexedPost = posts.value.findIndex(post => post.key === result.post.key)
  if (indexedPost >= 0) posts.value[indexedPost] = result.post
  if (updateRemote) remoteState.value = { nextCursor: result.nextCursor, exhausted: result.exhausted, coverage: result.coverage, warnings: result.warnings }
}
function openPost(key: string, offset = 0) {
  return execute('detail', async requestGeneration => {
    const result = await api<PostDetail>(`/library/posts/${encodeURIComponent(key)}?limit=${mediaLimit}&offset=${offset}`)
    if (!current(requestGeneration)) return
    if (selectedKey.value !== key) { lastReadPage.value = null; readProgress.value = null }
    applyDetail(result, offset)
  })
}
function probe() {
  return execute('probe', async requestGeneration => {
    const input = link.value.trim()
    if (!input) throw new Error('请填写自己频道中已有的主帖链接。')
    const result = await api<PostDetail>('/library/probe', { method: 'POST', body: { link: input }, timeout: 60000 })
    if (!current(requestGeneration)) return
    lastReadPage.value = null; readProgress.value = null
    applyDetail(result, 0)
    notice.value = '主帖读取结果已保存到本机索引。评论范围请核对下方覆盖状态。'
    try { await loadIndexData(0, requestGeneration) }
    catch (cause) {
      if (!current(requestGeneration)) return
      error.value = '主帖已保存，但索引列表刷新失败，请点击“刷新索引”重试。'
      if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
    }
  })
}
function refreshPost() {
  const key = selectedKey.value
  if (!key) return
  const targetIndexOffset = indexOffset.value
  return execute('refresh-post', async requestGeneration => {
    const result = await api<PostDetail>(`/library/posts/${encodeURIComponent(key)}/refresh`, { method: 'POST', body: {}, timeout: 60000 })
    if (!current(requestGeneration)) return
    lastReadPage.value = null; readProgress.value = null
    applyDetail(result, 0)
    notice.value = '已重新读取这条主帖、相册和讨论映射，并保存本机索引。评论媒体没有重新读取；核对评论编辑请从首轮重读。'
    try { await loadIndexData(targetIndexOffset, requestGeneration) }
    catch (cause) {
      if (!current(requestGeneration)) return
      error.value = '主帖已重新读取并保存，但本机索引列表刷新失败，请点击“刷新索引”重试。'
      if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
    }
  })
}
function readComments(restart = false) {
  const post = detail.value?.post
  const state = remoteState.value
  if (!post?.canReadComments || !state || (!restart && state.exhausted)) return
  const targetOffset = restart ? 0 : mediaOffset.value
  const accountId = props.accountId
  return execute(restart ? 'comments-restart' : 'comments', async requestGeneration => {
    stopRequested.value = false
    readProgress.value = { pages: 0, messages: 0, media: 0 }
    const isCurrent = () => current(requestGeneration) && props.accountId === accountId && selectedKey.value === post.key
    const readLocal = () => api<PostDetail>(`/library/posts/${encodeURIComponent(post.key)}?limit=${mediaLimit}&offset=${targetOffset}`)
    const latest = await readLocal()
    if (!isCurrent()) return
    if (latest.post.key !== post.key || latest.post.accountId !== accountId) throw new Error('主帖或账号已变化，请重新打开这条主帖。')
    applyDetail(latest, targetOffset)
    if (!latest.post.canReadComments) throw new Error('这条主帖当前无法读取评论，请先重新读取主帖。')
    if (!restart && latest.exhausted) { notice.value = '本轮评论历史已到读取边界；核对新增或编辑时可从头重读。'; return }
    const result = await runCommentRead({
      postKey: post.key,
      cursor: restart ? null : latest.nextCursor,
      isCurrent,
      shouldStop: () => stopRequested.value,
      betweenPages: () => new Promise(resolve => window.setTimeout(resolve, 250)),
      async readPage(cursor) {
        try { return await api<CommentPage>(`/library/posts/${encodeURIComponent(post.key)}/comments`, { method: 'POST', body: { cursor }, timeout: 60000 }) }
        catch (cause) {
          if (cause instanceof ApiError) throw cause
          throw new Error('连续读取已暂停，当前页可能仍在后台保存。请先刷新本机列表核对已保存位置，再继续读取。')
        }
      },
      async onPage(page, progress) {
        lastReadPage.value = page
        readProgress.value = { ...progress }
        remoteState.value = { nextCursor: page.nextCursor, exhausted: page.exhausted, coverage: page.coverage, warnings: page.warnings }
        if (detail.value) applyDetail({
          ...detail.value,
          post: { ...detail.value.post, coverage: page.coverage },
          nextCursor: page.nextCursor, exhausted: page.exhausted, coverage: page.coverage,
          warnings: page.warnings, commentCoverage: page.commentCoverage ?? 'PARTIAL',
          commentWarnings: page.commentWarnings,
          indexedCommentMessages: undefined, indexedCommentMedia: undefined,
        }, targetOffset, false)
        let refreshed: PostDetail
        try { refreshed = await readLocal() }
        catch (cause) {
          if (cause instanceof ApiError && [401, 403].includes(cause.status)) throw cause
          throw new Error('本页已保存，但本机列表刷新失败，连续读取已暂停。请刷新本机列表后继续。')
        }
        if (!isCurrent()) return
        if (refreshed.post.key !== post.key || refreshed.post.accountId !== accountId) throw new Error('主帖或账号已变化，连续读取已暂停。')
        applyDetail(refreshed, targetOffset, false)
      },
    })
    if (!isCurrent() || result.reason === 'stale') return
    if (result.reason === 'exhausted') notice.value = `本轮评论历史已读到边界，处理 ${result.pages} 页、${result.messages} 条消息。${commentCoverage.value === 'COMPLETE' ? '' : '仍有未核全项，请查看读取提示。'}`
    else if (result.reason === 'stopped') notice.value = result.pages ? '已停止后续读取，当前页结果已保存。下次可从保存的位置继续。' : '已停止，未发起新的评论页读取。'
    else if (result.reason === 'limit') notice.value = '本次连续读取已达到运行上限并暂停，已保存读取位置，可继续读取。'
    else error.value = '读取位置没有继续前进，已暂停。请先刷新本机列表，核对提示后再继续。'
  })
}
function stopReading() { if (readingComments.value) stopRequested.value = true }

watch([() => props.authorized, () => props.available, () => props.accountId, () => props.initialPostKey], (values, previous) => {
  const accountChanged = !!previous && values[2] !== previous[2]
  const initialPostChanged = !!previous && values[3] !== previous[3]
  if (!canAccess.value || accountChanged || initialPostChanged) { generation++; busy.value = ''; stopRequested.value = false; readProgress.value = null }
  if (!props.authorized || accountChanged) {
    posts.value = []; detail.value = null; remoteState.value = null; lastReadPage.value = null
    indexLoaded.value = false; indexOffset.value = 0; mediaOffset.value = 0; error.value = ''; notice.value = ''
  }
  if (canAccess.value && !indexLoaded.value) {
    void execute('index', async requestGeneration => {
      await loadIndexData(0, requestGeneration)
      const key = props.initialPostKey
      if (!key || !current(requestGeneration)) return
      const result = await api<PostDetail>(`/library/posts/${encodeURIComponent(key)}?limit=${mediaLimit}&offset=0`)
      if (current(requestGeneration)) applyDetail(result, 0)
    })
  } else if (canAccess.value && props.initialPostKey && selectedKey.value !== props.initialPostKey) void openPost(props.initialPostKey)
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++ })
</script>

<template>
  <div class="library-probe">
    <div v-if="!authorized" class="notice warning" role="status"><AppIcon name="lock" /><div><strong>请先完成 Telegram 授权</strong><p>账号授权成功后，才能读取主帖和本机索引。</p></div><button type="button" class="btn" @click="emit('settings')">前往设置</button></div>
    <div v-else-if="!available" class="notice warning" role="alert"><AppIcon name="info" /><span>本机服务当前不可用，读取操作已暂停。请检查后台和设置页连接状态。</span></div>
    <div v-if="error" class="notice danger" role="alert"><AppIcon name="info" /><span>{{ error }}</span><button class="icon-btn dismiss" aria-label="关闭读取错误" @click="error = ''"><AppIcon name="close" /></button></div>
    <div v-if="notice" class="notice success" role="status"><AppIcon name="check" /><span>{{ notice }}</span><button class="icon-btn dismiss" aria-label="关闭读取提示" @click="notice = ''"><AppIcon name="close" /></button></div>

    <section class="settings-section probe-entry" aria-labelledby="probe-heading">
      <div class="section-heading"><span class="section-icon"><AppIcon name="link" /></span><div><h2 id="probe-heading">添加或更新主帖</h2><p>填写自己频道的主帖链接，将主帖与评论资源保存在本机。</p></div></div>
      <form autocomplete="off" @submit.prevent="probe"><div class="field"><label for="probe-link">主帖链接</label><div class="input-action"><input id="probe-link" v-model="link" class="input" type="url" placeholder="https://t.me/…" spellcheck="false" :disabled="!controlsEnabled" required /><button class="btn primary" type="submit" :disabled="!controlsEnabled || !link.trim()"><AppIcon :name="busy === 'probe' ? 'clock' : 'arrow'" />{{ busy === 'probe' ? '正在读取…' : '读取并保存索引' }}</button></div><small>提交后读取主帖、相册及评论关联信息并保存到本机；评论媒体可在下方更新。</small></div></form>
    </section>

    <div class="probe-layout">
      <aside class="probe-index settings-section" aria-labelledby="probe-index-heading">
        <div class="probe-section-title"><div><h2 id="probe-index-heading">已保存主帖</h2><p>当前账号的本机索引</p></div><button class="icon-btn" type="button" :disabled="!controlsEnabled" aria-label="刷新索引" title="刷新索引" @click="loadIndex()"><AppIcon name="refresh" /></button></div>
        <p v-if="busy === 'index'" class="probe-inline-empty" role="status">正在读取本机索引…</p>
        <p v-else-if="!indexLoaded" class="probe-inline-empty">{{ authorized ? '等待读取本机索引' : '完成授权后读取索引' }}</p>
        <p v-else-if="!posts.length" class="probe-inline-empty">{{ indexOffset ? '本页没有已保存的主帖，请返回上一页。' : '尚无已保存主帖，请先填写上方链接。' }}</p>
        <div v-else class="probe-post-list"><button v-for="post in posts" :key="post.key" type="button" :class="['probe-post-item', { selected: selectedKey === post.key }]" :disabled="!controlsEnabled" :aria-pressed="selectedKey === post.key" @click="openPost(post.key)"><small>{{ post.channelTitle || '未命名频道' }}</small><strong>{{ post.title || '未命名主帖' }}</strong><span>主帖 <code>{{ post.mainMessageId }}</code></span><span :class="['probe-coverage', post.coverage.toLowerCase()]">{{ coverageLabel(post.coverage) }}</span></button></div>
        <div class="probe-pagination index-pagination"><button class="btn" type="button" :disabled="!controlsEnabled || indexOffset === 0" @click="loadIndex(Math.max(0, indexOffset - indexLimit))">上一页</button><span>第 {{ Math.floor(indexOffset / indexLimit) + 1 }} 页</span><button class="btn" type="button" :disabled="!controlsEnabled || posts.length < indexLimit" @click="loadIndex(indexOffset + indexLimit)">下一页</button></div>
        <p class="probe-tiny-note">每页最多 20 条，不预估主帖总数。</p>
      </aside>

      <div class="probe-detail">
        <div v-if="!detail" class="probe-detail-empty"><span class="empty-state-icon"><AppIcon name="library" /></span><h2>{{ busy === 'probe' || busy === 'detail' ? '正在读取主帖…' : '选择要更新的主帖' }}</h2><p>粘贴主帖链接，或从左侧选择已保存主帖，读取评论并更新本机媒体。</p></div>
        <template v-else>
          <header class="probe-detail-toolbar">
            <div class="probe-compact-context" :title="`${detail.post.channelTitle || '未命名频道'} / ${detail.post.title || '未命名主帖'}`"><span>{{ detail.post.channelTitle || '未命名频道' }}</span><span class="probe-context-divider" aria-hidden="true">/</span><h2>{{ detail.post.title || '未命名主帖' }}</h2></div>
            <button class="btn" type="button" :disabled="!controlsEnabled" title="更新主帖文字、相册媒体及讨论关联；评论通过下方按钮读取" @click="refreshPost"><AppIcon :name="busy === 'refresh-post' ? 'clock' : 'refresh'" />{{ busy === 'refresh-post' ? '正在重新读取…' : '重新读取主帖' }}</button>
          </header>
          <div class="probe-compact-status" aria-label="最近读取状态"><span>最近读取</span><strong :class="mainCoverage?.toLowerCase()">{{ mainStatusLabel }}</strong><strong :class="commentCoverage?.toLowerCase()">{{ commentCoverage === 'PARTIAL' ? '评论范围尚未核全' : commentStatusLabel }}</strong><small>{{ remoteState?.exhausted ? '本轮已到读取边界' : '尚未确认读取边界' }}</small></div>
          <div v-if="coverage !== 'COMPLETE' || mainCoverage !== 'COMPLETE' || commentCoverage !== 'COMPLETE' || !detail.post.canReadComments || warnings.length" class="probe-warnings probe-visible-risk" role="status">
            <p v-if="coverage === 'UNAVAILABLE' || mainCoverage === 'UNAVAILABLE' || commentCoverage === 'UNAVAILABLE' || !detail.post.canReadComments"><AppIcon name="info" />部分内容暂不可读取，当前显示本机已保存的媒体。</p>
            <p v-else-if="coverage !== 'COMPLETE' || mainCoverage !== 'COMPLETE' || commentCoverage !== 'COMPLETE'"><AppIcon name="info" />读取范围尚未全部确认，当前显示本机已保存的媒体。</p>
            <p v-else><AppIcon name="info" />最近读取有以下提示。</p>
            <ul v-if="warnings.length"><li v-for="warning in warnings" :key="warning">{{ warning }}</li></ul>
          </div>

          <section class="settings-section probe-comments" aria-labelledby="comments-heading">
            <div class="probe-section-title"><div><h2 id="comments-heading">评论及回复媒体</h2><p>查看和更新这条主帖的评论与回复媒体。</p></div><button class="btn" type="button" :disabled="!controlsEnabled" @click="openPost(detail.post.key, mediaOffset)"><AppIcon name="refresh" />刷新本机列表</button></div>
            <div class="probe-remote-bar">
              <div><strong>{{ readingComments ? stopRequested ? '当前页保存后停止' : '正在连续读取评论' : remoteState?.exhausted ? '本轮评论历史已到边界' : remoteState?.nextCursor ? '可从已保存位置继续' : '可读取这条主帖的全部评论' }}</strong><p v-if="!detail.post.canReadComments">该主帖当前不可读取评论，请重新读取主帖或查看下方详情。</p><p v-else-if="remoteState?.exhausted && commentCoverage !== 'COMPLETE'">已到读取边界，仍有未核全项，请查看上方提示。</p><p v-else>自动逐页读取并保存，可随时停止；离开此页后不再发起下一页。</p></div>
              <div class="probe-remote-actions">
                <button class="btn primary" type="button" :disabled="!controlsEnabled || !detail.post.canReadComments || !remoteState || remoteState.exhausted" @click="readComments()"><AppIcon :name="busy === 'comments' ? 'clock' : 'arrow'" />{{ busy === 'comments' ? '正在连续读取…' : remoteState?.nextCursor ? '继续读取全部评论' : '读取全部评论' }}</button>
                <button class="btn" type="button" :disabled="!controlsEnabled || !detail.post.canReadComments || !remoteState" @click="readComments(true)"><AppIcon :name="busy === 'comments-restart' ? 'clock' : 'refresh'" />{{ busy === 'comments-restart' ? '正在从头重读…' : '从头重读全部评论' }}</button>
                <button v-if="readingComments" class="btn" type="button" :disabled="stopRequested" @click="stopReading()">{{ stopRequested ? '正在停止…' : '停止读取' }}</button>
              </div>
            </div>
            <p v-if="readProgress" class="probe-read-progress" role="status" aria-live="polite">本次处理 {{ readProgress.pages }} 页 · {{ readProgress.messages }} 条消息 · {{ readProgress.media }} 个媒体<span v-if="readingComments && stopRequested"> · 当前页保存后停止</span></p>
            <p class="probe-tiny-note probe-restart-note">从头重读会去重并更新已核验的媒体与说明，未读到的消息保留旧索引；停止后可继续读取。</p>
            <div class="probe-index-totals"><div><span>本机累计评论消息</span><strong>{{ indexedMessages === null ? '未提供' : `${indexedMessages} 条` }}</strong></div><div><span>本机累计评论媒体</span><strong>{{ indexedMedia === null ? '未提供' : `${indexedMedia} 个` }}</strong></div></div>
            <p class="probe-tiny-note">累计量来自后台保存的去重索引，不代表远端当前总量。{{ indexedMessages === null || indexedMedia === null ? '当前后台未提供全部累计统计，下面的本页数量不会用作总数。' : '累计统计与下面的当前页数量分别展示。' }}</p>
            <div class="probe-local-heading"><span>本机评论媒体 · 第 {{ Math.floor(mediaOffset / mediaLimit) + 1 }} 页 · 当前页 {{ detail.commentMedia.length }} 个</span><span>每页最多 50 个</span></div>
            <LibraryMediaTable :items="detail.commentMedia" empty-text="本机索引本页尚无评论媒体。可能尚未读取、该页无所列媒体或范围不可访问；请核对覆盖状态与警告。" />
            <div class="probe-pagination"><button class="btn" type="button" :disabled="!controlsEnabled || mediaOffset === 0" @click="openPost(detail.post.key, Math.max(0, mediaOffset - mediaLimit))">上一页媒体</button><span>本机分页不代表远端读取进度</span><button class="btn" type="button" :disabled="!controlsEnabled || detail.commentMedia.length < mediaLimit" @click="openPost(detail.post.key, mediaOffset + mediaLimit)">下一页媒体</button></div>
            <p class="probe-tiny-note">本页满 50 个时允许尝试下一页，不推断总数。下一页为空时可返回上一页。</p>
          </section>
          <details :key="detail.post.key" class="settings-section probe-reading-details">
            <summary>读取详情、主帖与原消息</summary>
            <div class="probe-reading-details-body">
              <div v-if="lastReadPage" class="probe-last-page"><p>最近一页远端结果：{{ lastReadPage.messageCount }} 条消息 · {{ lastReadPage.media.length }} 个媒体 · {{ coverageLabel(lastReadPage.commentCoverage ?? lastReadPage.coverage) }}。</p><p v-if="lastObservationCounts" class="probe-observation-counts"><span>媒体已核验 {{ lastObservationCounts.confirmed }} 条消息</span><span>仍待核验 {{ lastObservationCounts.pending }} 条消息</span></p><details v-if="lastReadPage.messages.length" class="probe-message-details"><summary>查看本页消息身份与原消息链接</summary><ul class="probe-message-list"><li v-for="message in lastReadPage.messages" :key="`${message.chatId}:${message.messageId}`"><span>聊天 <code>{{ message.chatId }}</code> / 消息 <code>{{ message.messageId }}</code></span><a v-if="originalMessageLink(message.link)" :href="originalMessageLink(message.link)" target="_blank" rel="noopener noreferrer">原消息 ↗</a><span v-else class="probe-link-unavailable">无可用链接</span></li></ul></details></div>
              <section class="probe-reading-status" aria-labelledby="reading-status-heading">
                <div class="probe-section-title"><div><h2 id="reading-status-heading">这条主帖的读取状态</h2><p>主帖与评论历史分别核对，当前状态只针对选中的这条主帖。</p></div><span :class="['probe-coverage', coverage.toLowerCase()]">整体：{{ coverageLabel(coverage) }}</span></div>
                <p class="probe-tiny-note probe-refresh-note">重新读取主帖会更新主帖文字、相册媒体及讨论映射；评论媒体与说明有编辑时，请从首轮重读评论。未展开或读取失败时保留旧数据与警告，尚未读到的消息不能视为已删除。</p>
                <div class="probe-range-grid">
                  <div :class="['probe-range-card', mainCoverage?.toLowerCase()]"><span>主帖 / 相册</span><strong>{{ mainStatusLabel }}</strong><p>{{ albumSummary }}</p><small>读取方法：{{ albumMethod }}</small><code v-if="mainCoverage" class="probe-range-code">{{ mainCoverage }}</code><p v-else class="probe-range-fallback">后台未单独提供主帖范围状态，请结合成员与边界证据核对。</p>
                    <details v-if="detail.post.albumEvidence" class="probe-message-details probe-album-evidence"><summary>查看相册边界证据</summary><dl class="probe-identities"><dt>核全结果</dt><dd>{{ detail.post.albumEvidence.complete ? '后台已确认核全' : '后台尚未确认核全' }}</dd><dt>较新侧边界 ID</dt><dd><code>{{ detail.post.albumEvidence.newerBoundaryId ?? '未报告' }}</code></dd><dt>较旧侧边界 ID</dt><dd><code>{{ detail.post.albumEvidence.olderBoundaryId ?? '未报告' }}</code></dd><dt>后台方法值</dt><dd><code>{{ detail.post.albumEvidence.method }}</code></dd></dl></details>
                  </div>
                  <div :class="['probe-range-card', commentCoverage?.toLowerCase()]"><span>评论历史</span><strong>{{ commentStatusLabel }}</strong><p v-if="commentCoverage === 'COMPLETE'">后台确认本轮评论历史已读取完成。核对新增评论时，可以从首轮重读。</p><p v-else-if="commentCoverage === 'PARTIAL'">评论范围尚未核全，请继续读取并查看范围提示。</p><p v-else-if="commentCoverage === 'UNAVAILABLE'">当前评论历史不可读取，请检查讨论映射、权限与后台警告。</p><p v-else>后台未单独提供评论历史状态。整体范围与远端边界仍在下方保留，不据此推断评论完整。</p><small>{{ remoteState?.exhausted ? '后台报告本轮已到读取边界' : '尚未确认到达本轮读取边界' }}</small><code v-if="commentCoverage" class="probe-range-code">{{ commentCoverage }}</code></div>
                </div>
              </section>
              <section class="probe-post-detail" aria-labelledby="post-detail-heading">
                <div class="probe-section-title"><div><p class="probe-channel-title">{{ detail.post.channelTitle || '未命名频道' }}</p><h2 id="post-detail-heading">{{ detail.post.title || '未命名主帖' }}</h2></div><span :class="['probe-coverage', coverage.toLowerCase()]">{{ coverageLabel(coverage) }}</span></div>
                <dl class="probe-identities"><dt>账号 ID</dt><dd><code>{{ detail.post.accountId }}</code></dd><dt>频道 ID</dt><dd><code>{{ detail.post.channelId }}</code></dd><dt>规范主帖消息 ID</dt><dd><code>{{ detail.post.mainMessageId }}</code></dd><dt>主帖稳定标识</dt><dd><code>{{ detail.post.key }}</code></dd><dt>相册 ID</dt><dd><span v-if="detail.post.albumId === '0'">非相册，单条主帖</span><code v-else-if="detail.post.albumId">{{ detail.post.albumId }}</code><span v-else>未报告相册标识</span></dd></dl>
                <div class="probe-post-text"><span>主帖文字</span><p>{{ detail.post.text || '此主帖未返回文字。' }}</p></div>
                <details class="probe-message-details" open><summary>主帖消息与相册成员 · {{ detail.post.mainMessages.length }} 条</summary><ul class="probe-message-list"><li v-for="message in detail.post.mainMessages" :key="`${message.chatId}:${message.messageId}`"><span>聊天 <code>{{ message.chatId }}</code> / 消息 <code>{{ message.messageId }}</code></span><a v-if="originalMessageLink(message.link)" :href="originalMessageLink(message.link)" target="_blank" rel="noopener noreferrer">原消息 ↗</a><span v-else class="probe-link-unavailable">无可用链接</span></li></ul></details>
                <h3 class="probe-subheading">主帖自身媒体 · {{ detail.post.mainMedia.length }} 个</h3><LibraryMediaTable :items="detail.post.mainMedia" empty-text="本次主帖结果未包含媒体。相册展开范围以后台警告为准。" />
              </section>

              <section class="probe-discussion-details" aria-labelledby="discussion-heading">
                <div class="probe-section-title"><div><h2 id="discussion-heading">讨论映射与读取范围</h2><p>频道主帖与讨论组是不同的聊天身份，请对照真实消息核验。</p></div></div>
                <dl class="probe-identities"><dt>讨论组 ID</dt><dd><code v-if="detail.post.discussionChatId">{{ detail.post.discussionChatId }}</code><span v-else>未返回讨论组</span></dd><dt>讨论根消息 ID</dt><dd><code v-if="detail.post.discussionRootMessageId">{{ detail.post.discussionRootMessageId }}</code><span v-else>未返回讨论根消息</span></dd><dt>评论读取权限</dt><dd>{{ detail.post.canReadComments ? '后台报告可读取' : '后台报告不可读取' }}</dd><dt>覆盖状态</dt><dd><span :class="['probe-coverage', coverage.toLowerCase()]">{{ coverageLabel(coverage) }}</span><code class="probe-raw-coverage">{{ coverage }}</code></dd><dt>本轮远端边界</dt><dd>{{ remoteState?.exhausted ? '后台报告本轮已到读取边界' : '尚未确认到达读取边界' }}</dd></dl>
                <details v-if="detail.post.discussionRoots.length" class="probe-message-details"><summary>讨论根消息定位 · {{ detail.post.discussionRoots.length }} 条</summary><ul class="probe-message-list"><li v-for="message in detail.post.discussionRoots" :key="`${message.chatId}:${message.messageId}`"><span>聊天 <code>{{ message.chatId }}</code> / 消息 <code>{{ message.messageId }}</code></span><a v-if="originalMessageLink(message.link)" :href="originalMessageLink(message.link)" target="_blank" rel="noopener noreferrer">原消息 ↗</a><span v-else class="probe-link-unavailable">无可用链接</span></li></ul></details>
                <p class="probe-tiny-note">“本轮范围完整”仅表示后台确认当前读取范围；媒体为空或本页不足 50 条不表示评论已读完。</p>
              </section>

            </div>
          </details>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.probe-read-progress{font-size:10px;line-height:1.9;color:#6788a1;margin:-5px 0 15px}.probe-read-progress+.probe-restart-note{margin-top:0}.probe-remote-actions{max-width:100%}.probe-remote-bar{flex-wrap:wrap}
.probe-stage-banner>.small-tag{margin-left:auto;white-space:nowrap}.probe-entry .field{margin-bottom:0}.probe-entry .input-action>.input{font-size:12px}.probe-layout{display:grid;grid-template-columns:245px minmax(0,1fr);gap:20px;align-items:start}.probe-index{padding:18px 14px}.probe-section-title{display:flex;align-items:flex-start;justify-content:space-between;gap:13px;margin-bottom:17px}.probe-section-title h2{font-size:13px;font-weight:600;color:#3c5b73;margin:0}.probe-section-title p{font-size:10px;color:#9aa9b6;margin-top:4px}.probe-section-title .probe-channel-title{font-size:10px;color:#88a0b3;margin:0 0 5px}.probe-post-detail .probe-section-title h2{font-size:16px;overflow-wrap:anywhere}.probe-post-list{display:flex;flex-direction:column;gap:7px}.probe-post-item{display:flex;align-items:flex-start;flex-direction:column;gap:5px;text-align:left;border:1px solid #e9eef3;border-radius:6px;padding:11px 12px;background:#fff;width:100%;color:#607a90}.probe-post-item:hover:enabled{border-color:#c3d8e9;background:#f8fbfe}.probe-post-item.selected{background:#eff6fc;border-color:#bcd5ea}.probe-post-item>small{font-size:9px;color:#90a5b7;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.probe-post-item>strong{font-size:11px;line-height:1.8;font-weight:550;display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}.probe-post-item>span{font-size:9px;color:#9aabb8;overflow-wrap:anywhere}.probe-post-item .probe-coverage{font-size:8px;padding:1px 5px}.probe-detail{min-width:0}.probe-detail-empty{border:1px dashed #d8e4ee;border-radius:9px;background:#fff;text-align:center;padding:64px 30px}.probe-detail-empty h2{font-size:15px;font-weight:550;color:#6d889e;margin:0 0 11px}.probe-detail-empty p{color:#9cafbd;font-size:11px;max-width:370px;line-height:1.9;margin:0 auto}.probe-coverage{display:inline-flex;align-items:center;padding:3px 7px;border:1px solid #eee1c7;border-radius:4px;font-size:9px;color:#a18451;background:#fffaf0;white-space:nowrap}.probe-coverage.complete{background:#f0f8f3;border-color:#dcece2;color:#649076}.probe-coverage.unavailable{background:#fcf1ef;border-color:#eeddd7;color:#ac7b70}.probe-identities{display:grid;grid-template-columns:120px minmax(0,1fr);gap:11px 10px;margin:0 0 19px;font-size:10px}.probe-identities dt{color:#98aab7}.probe-identities dd{margin:0;color:#688198;overflow-wrap:anywhere}.probe-identities code{font-size:10px;letter-spacing:.1px}.probe-raw-coverage{margin-left:9px;color:#a2b0bb;font-size:8px!important}.probe-post-text{border:1px solid #edf2f6;border-radius:6px;padding:12px 14px;background:#fafcfd;margin-bottom:18px}.probe-post-text>span{font-size:9px;color:#a1b0bc}.probe-post-text>p{font-size:11px;color:#72899b;line-height:1.9;white-space:pre-wrap;overflow-wrap:anywhere;max-height:320px;overflow-y:auto;margin-top:6px}.probe-message-details{margin:14px 0;font-size:10px;color:#859db0}.probe-message-details>summary{cursor:pointer;line-height:1.8}.probe-message-list{list-style:none;margin:10px 0 0;padding:0;border-top:1px solid #edf2f6}.probe-message-list li{display:flex;justify-content:space-between;align-items:flex-start;gap:12px;padding:9px 0;border-bottom:1px solid #f0f3f6;font-size:9px}.probe-message-list li>span{overflow-wrap:anywhere}.probe-message-list a{color:#5d96c1;text-decoration:none;white-space:nowrap;font-size:9px}.probe-subheading{font-size:11px;color:#748da1;font-weight:550;margin:20px 0 10px}.probe-pagination{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:16px}.probe-pagination>span{font-size:9px;color:#9aabb9;text-align:center}.probe-pagination>.btn{font-size:10px;min-height:31px;padding:5px 10px}.index-pagination{gap:5px}.index-pagination>.btn{font-size:9px;min-height:28px;padding:3px 8px}.index-pagination>span{font-size:8px;white-space:nowrap}.probe-tiny-note{font-size:9px;color:#a1afbb;line-height:1.9;margin-top:12px}.probe-warnings{background:#fff9ed;border:1px solid #eee1c8;border-radius:6px;padding:12px 14px;color:#a28a5e;margin-top:16px;font-size:10px}.probe-warnings>strong{display:flex;gap:6px;align-items:center;font-size:10px;font-weight:550}.probe-warnings .icon{width:13px;height:13px}.probe-warnings>ul{margin:9px 0 0;padding-left:18px;line-height:1.9}.probe-warnings li{overflow-wrap:anywhere}.probe-remote-bar{display:flex;align-items:center;justify-content:space-between;gap:15px;background:#f0f6fb;border:1px solid #dfeaf4;border-radius:6px;padding:14px;margin-bottom:18px}.probe-remote-bar strong{font-size:11px;font-weight:550;color:#6385a0}.probe-remote-bar p{font-size:9px;color:#92a7b8;margin-top:4px;line-height:1.9}.probe-remote-bar>.btn{font-size:10px}.probe-local-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:19px 0 12px;font-size:10px;color:#8098ab}.probe-local-heading>span:last-child{font-size:9px;color:#a2b0bc;white-space:nowrap}.probe-last-page{font-size:10px;color:#8ba0b0;background:#f9fbfd;padding:12px 14px;border:1px solid #eaf0f5;border-radius:6px;margin-bottom:17px}.probe-last-page .probe-message-details{margin-bottom:0}.library-probe>.notice>button.btn{margin-left:auto}.library-probe>.notice strong{font-weight:550}.library-probe>.notice p{font-size:10px;margin-top:3px}
:deep(.probe-inline-empty){font-size:10px;color:#9badbb;text-align:center;line-height:1.9;padding:28px 14px;border:1px dashed #e2eaf1;border-radius:6px;background:#fcfdfe;margin:0}:deep(.probe-table-scroll){width:100%;overflow-x:auto;border:1px solid #e7eef4;border-radius:6px}:deep(.probe-media-table){border-collapse:collapse;width:100%;text-align:left;table-layout:fixed;font-size:10px}:deep(.probe-media-table th){font-size:9px;font-weight:500;color:#95a8b7;background:#f7fafc;border-bottom:1px solid #e7eef4;padding:10px 12px;white-space:nowrap}:deep(.probe-media-table th:first-child){width:37%}:deep(.probe-media-table th:nth-child(2)){width:17%}:deep(.probe-media-table th:nth-child(3)){width:30%}:deep(.probe-media-table th:nth-child(4)){width:16%}:deep(.probe-media-table td){padding:12px;border-bottom:1px solid #edf2f6;vertical-align:top;overflow-wrap:anywhere;color:#839aab}:deep(.probe-media-table tr:last-child td){border-bottom:0}:deep(.probe-file-name){font-size:10px;color:#668198;font-weight:550}:deep(.probe-media-caption){white-space:pre-wrap;line-height:1.8;max-height:110px;overflow-y:auto;margin-top:5px;font-size:9px;color:#99abb8}:deep(.probe-source){display:block;font-size:8px;color:#a5b4bf;margin-top:5px}:deep(.probe-media-type){display:block;font-size:10px;color:#7c96aa;margin-bottom:4px}:deep(.probe-media-table td>small){font-size:9px;white-space:nowrap;color:#9aacb9}:deep(.probe-message-ids>span){display:block;font-size:8px;line-height:1.9;color:#9aafbe}:deep(.probe-message-ids code){font-size:9px;color:#819bb0}:deep(.probe-message-link){font-size:9px;color:#699cc2;white-space:nowrap;text-decoration:none}:deep(.probe-link-unavailable){font-size:8px;color:#a6b5c0}
.probe-range-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.probe-range-card{min-width:0;background:#f8fafc;border:1px solid #e6edf3;border-radius:7px;padding:14px}.probe-range-card>span{display:block;font-size:9px;letter-spacing:.4px;color:#91a6b7}.probe-range-card>strong{display:block;margin:7px 0 8px;font-size:13px;font-weight:550;color:#7890a3}.probe-range-card>p{font-size:10px;color:#91a5b5;line-height:1.9;overflow-wrap:anywhere}.probe-range-card>small{display:block;font-size:9px;color:#9bafbe;margin-top:9px;line-height:1.8;overflow-wrap:anywhere}.probe-range-code{display:block;font-size:8px;color:#acbbc7;margin-top:6px}.probe-range-card.complete{background:#f4faf6;border-color:#e0ede4}.probe-range-card.complete>strong{color:#659176}.probe-range-card.partial{background:#fffcf6;border-color:#eee5d4}.probe-range-card.partial>strong{color:#ac905e}.probe-range-card.unavailable{background:#fcf5f3;border-color:#f0e2dd}.probe-range-card.unavailable>strong{color:#b08377}.probe-range-card>.probe-range-fallback{font-size:9px;margin-top:8px;color:#a7b6c2}.probe-album-evidence{font-size:9px;margin:10px 0 0}.probe-album-evidence .probe-identities{grid-template-columns:95px minmax(0,1fr);font-size:9px;gap:8px;margin:10px 0 0}.probe-album-evidence .probe-identities code{font-size:8px}.probe-index-totals{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px;padding-top:5px}.probe-index-totals>div{background:#f8fafc;border:1px solid #e8eff5;border-radius:6px;padding:12px 14px}.probe-index-totals span{display:block;font-size:9px;color:#9aafbd}.probe-index-totals strong{display:block;font-size:18px;font-weight:550;letter-spacing:.2px;color:#718da3;margin-top:5px}.probe-remote-actions{display:flex;align-items:center;justify-content:flex-end;gap:8px;flex-wrap:wrap;flex-shrink:0}.probe-remote-actions>.btn{font-size:10px}.probe-restart-note{margin:-8px 0 18px}
@media(min-width:1500px){.probe-layout{grid-template-columns:260px minmax(0,1fr);gap:24px}}
.probe-reading-actions{display:flex;flex-direction:column;align-items:flex-end;gap:9px;flex-shrink:0}.probe-reading-actions>.btn{font-size:10px;min-height:31px}.probe-refresh-note{margin:-2px 0 16px;padding:10px 12px;background:#f8fafc;border:1px solid #e9eff5;border-radius:5px}.probe-observation-counts{display:flex;flex-wrap:wrap;gap:7px 15px;font-size:9px;color:#8fa6b8;margin-top:8px}.probe-observation-counts>span:first-child{color:#71957f}
@media(max-width:1200px){.probe-layout{grid-template-columns:210px minmax(0,1fr);gap:16px}.probe-detail>.settings-section{padding:20px 17px}.probe-remote-bar{align-items:flex-start;flex-direction:column;gap:10px}.probe-identities{grid-template-columns:105px minmax(0,1fr);gap:10px}.probe-media-table{min-width:450px}.probe-comments>.probe-section-title{flex-direction:column;gap:10px}.probe-comments>.probe-section-title>.btn{align-self:flex-end;font-size:9px;min-height:31px}}
@media(max-width:1000px){.probe-layout{grid-template-columns:minmax(0,1fr)}.probe-index{padding:18px 20px}.probe-post-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px}.index-pagination{justify-content:flex-end;gap:12px}.probe-stage-banner>.small-tag{display:none}.probe-remote-bar{flex-direction:row;align-items:center}.probe-comments>.probe-section-title{flex-direction:row}.probe-detail>.settings-section{padding:23px}.probe-detail-empty{padding:45px 25px}}
@media(max-width:680px){.probe-entry .input-action>.input{font-size:11px}.probe-entry .input-action>.btn{font-size:10px}.probe-post-list{grid-template-columns:minmax(0,1fr)}.probe-index{padding:17px}.probe-detail>.settings-section{padding:19px 17px}.probe-identities{grid-template-columns:91px minmax(0,1fr);font-size:9px;gap:9px}.probe-identities code{font-size:9px}.probe-post-detail .probe-section-title{flex-direction:column;gap:10px}.probe-post-detail .probe-section-title h2{font-size:14px}.probe-remote-bar{flex-direction:column;align-items:flex-start}.probe-comments>.probe-section-title{flex-direction:column}.probe-local-heading{align-items:flex-start;flex-direction:column;gap:4px}.probe-pagination{gap:8px;flex-wrap:wrap}.probe-pagination>span{order:3;width:100%;font-size:8px}.index-pagination>span{order:0;width:auto}.probe-message-list li{flex-direction:column;gap:5px}:deep(.probe-media-table){min-width:480px}.probe-raw-coverage{display:block;margin:5px 0 0}.library-probe>.notice{flex-wrap:wrap}}
@media(max-width:1200px) and (min-width:1001px){.probe-range-grid{grid-template-columns:minmax(0,1fr)}.probe-reading-status>.probe-section-title{flex-direction:column;gap:9px}}
@media(max-width:680px){.probe-range-grid{grid-template-columns:minmax(0,1fr)}.probe-reading-status>.probe-section-title{flex-direction:column;gap:9px}.probe-index-totals{gap:8px}.probe-index-totals>div{padding:10px}.probe-index-totals span{font-size:8px}.probe-index-totals strong{font-size:16px}}

.probe-detail-toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px;margin:0 2px 12px;padding:2px 0 13px;border-bottom:1px solid #e3eaf0}.probe-compact-context{display:flex;align-items:center;gap:8px;min-width:0;flex:1;line-height:1.8}.probe-compact-context>span:first-child{font-size:10px;color:#8fa5b5;max-width:145px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex-shrink:0}.probe-context-divider{font-size:10px;color:#bac9d3}.probe-compact-context>h2{margin:0;min-width:0;font-size:12px;font-weight:550;line-height:1.8;color:#597990;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.probe-detail-toolbar>.btn{font-size:10px;min-height:31px;flex-shrink:0}.probe-compact-status{display:flex;align-items:center;flex-wrap:wrap;gap:6px 15px;margin:0 2px 15px;font-size:10px;color:#9c8b6e}.probe-compact-status>span,.probe-compact-status>small{font-size:9px;color:#9eafbb}.probe-compact-status>strong{font-weight:500}.probe-compact-status>strong.complete{color:#7b9d88}.probe-compact-status>strong.unavailable{color:#ae8278}.probe-visible-risk{margin:0 0 16px;padding:10px 13px;border:0;border-left:2px solid #d7b47d;border-radius:0 5px 5px 0}.probe-visible-risk>p{display:flex;align-items:flex-start;gap:7px;line-height:1.9}.probe-visible-risk>p>.icon{flex-shrink:0;margin-top:3px}.probe-visible-risk>ul{margin-top:7px}.probe-detail>.probe-reading-details{padding:16px 20px;background:#fbfcfd}.probe-reading-details>summary{cursor:pointer;font-size:11px;font-weight:500;color:#829aab;line-height:1.9}.probe-reading-details-body{padding-top:22px}.probe-reading-details-body>section+section{border-top:1px solid #e7eef4;margin-top:22px;padding-top:22px}.probe-reading-details .probe-post-detail .probe-section-title h2{font-size:13px}.probe-reading-details .probe-post-text>p{max-height:none;overflow:visible}.probe-comments .probe-index-totals{display:flex;align-items:center;flex-wrap:wrap;gap:8px 22px;padding-top:0}.probe-comments .probe-index-totals>div{display:flex;align-items:baseline;gap:8px;padding:0;border:0;background:none}.probe-comments .probe-index-totals strong{font-size:12px;margin:0}.probe-comments .probe-index-totals span{font-size:9px}.probe-comments .probe-last-page{padding:9px 11px;margin-bottom:13px}.probe-comments .probe-tiny-note{font-size:9px}
@media(max-width:680px){.probe-detail-toolbar{flex-wrap:wrap;gap:9px}.probe-compact-context{flex-basis:100%;flex-wrap:wrap;gap:2px 7px}.probe-compact-context>span:first-child{max-width:100%;font-size:9px}.probe-context-divider{display:none}.probe-compact-context>h2{flex-basis:100%;white-space:normal;overflow-wrap:anywhere;font-size:11px}.probe-detail-toolbar>.btn{margin-left:auto}.probe-compact-status{gap:5px 10px;font-size:9px}.probe-compact-status>span{flex-basis:100%;font-size:8px}.probe-detail>.probe-reading-details{padding:14px 17px}.probe-visible-risk{font-size:9px}.probe-comments .probe-index-totals{gap:7px 15px}}
</style>
