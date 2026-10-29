<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { coverageLabel, originalMessageLink, type Coverage, type Media, type MediaCatalogPage, type PostDetail } from '../library'
import AppIcon from './AppIcon.vue'
import LibraryMediaTable from './LibraryMediaTable.vue'
import MediaCollection from './MediaCollection.vue'
import MediaViewSwitch from './MediaViewSwitch.vue'
import InfiniteLoad from './InfiniteLoad.vue'

const props = defineProps<{ postKey: string; authorized: boolean; available: boolean; accountId: string | null }>()
const emit = defineEmits<{ close: []; accessError: [cause: ApiError] }>()
type MediaType = Media['type']
const typeOptions: { value: MediaType; label: string }[] = [
  { value: 'VIDEO', label: '视频' }, { value: 'PHOTO', label: '图片' },
  { value: 'AUDIO', label: '音频' }, { value: 'DOCUMENT', label: '文档' }, { value: 'OTHER', label: '其他' },
]
const detail = ref<PostDetail | null>(null)
const mediaPage = ref<MediaCatalogPage | null>(null)
const loadedMedia = ref<Media[]>([])
const mediaView = ref<'list' | 'cards'>('list')
const types = ref<MediaType[]>([])
const includeMain = ref(true)
const detailBusy = ref(false)
const mediaBusy = ref(false)
const detailError = ref('')
const mediaError = ref('')
const mediaOffset = ref(0)
const mediaPageVersion = ref(0)
const mediaLimit = 50
const canAccess = computed(() => props.authorized && props.available && Boolean(props.postKey))
const mainCoverage = computed(() => detail.value?.mainCoverage ?? detail.value?.post.mainCoverage)
const commentCoverage = computed(() => detail.value?.commentCoverage)
const mainWarnings = computed(() => [...new Set(detail.value?.post.warnings ?? [])])
const commentWarnings = computed(() => [...new Set(detail.value?.commentWarnings ?? [])])
const overallWarnings = computed(() => [...new Set(detail.value?.warnings ?? [])])
const visibleWarnings = computed(() => [...new Set([...overallWarnings.value, ...mainWarnings.value, ...commentWarnings.value])])
const readingRisk = computed(() => {
  if (!detail.value) return ''
  if ([detail.value.coverage, mainCoverage.value, commentCoverage.value].includes('UNAVAILABLE')) return '部分内容暂不可读取，当前只显示已索引的媒体。'
  if (detail.value.coverage !== 'COMPLETE' || mainCoverage.value !== 'COMPLETE' || commentCoverage.value !== 'COMPLETE') return '读取范围尚未全部确认，当前只显示已索引的媒体。'
  return visibleWarnings.value.length ? '最近一次读取仍有以下提示。' : ''
})
const albumMethod = computed(() => {
  const method = detail.value?.post.albumEvidence?.method
  const labels: Record<string, string> = {
    SINGLE_MESSAGE: '非相册单条读取', ADJACENT_BOUNDARIES: '双侧连续邻接边界核验', PARTIAL_SCAN: '扫描边界尚未核全',
  }
  return method ? labels[method] ?? method : '后台未提供读取方法'
})
const selectedTypeLabel = computed(() => types.value.length ? typeOptions.filter(option => types.value.includes(option.value)).map(option => option.label).join('、') : '全部类型')
const mainPageItems = computed(() => loadedMedia.value.filter(item => item.source === 'MAIN'))
const commentPageItems = computed(() => loadedMedia.value.filter(item => item.source !== 'MAIN'))
let contextGeneration = 0
let detailGeneration = 0
let mediaGeneration = 0
let disposed = false

function current(context: number) { return !disposed && context === contextGeneration && canAccess.value }
function errorMessage(cause: unknown) {
  if (cause instanceof ApiError && cause.status === 401) return `本机访问令牌无效或已过期，请从启动入口重新打开界面。${cause.message}`
  if (cause instanceof ApiError && cause.status === 403) return `当前请求被拒绝，请检查本机访问和 Telegram 授权状态。${cause.message}`
  return cause instanceof Error ? cause.message : '读取本机索引失败，请重试。'
}
function reportAccessError(cause: unknown) {
  if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
}
function rangeLabel(coverage: Coverage | undefined, range: 'main' | 'comment') {
  if (!coverage) return '后台未提供独立范围状态'
  if (range === 'main') return { COMPLETE: '主帖范围已核全', PARTIAL: '主帖范围尚未核全', UNAVAILABLE: '主帖范围不可读取' }[coverage]
  return { COMPLETE: '本轮评论历史已读完', PARTIAL: '评论范围尚未核全', UNAVAILABLE: '评论历史不可读取' }[coverage]
}
async function loadDetail() {
  const request = ++detailGeneration
  const context = contextGeneration
  if (!canAccess.value) return
  detailBusy.value = true; detailError.value = ''
  const key = props.postKey
  try {
    const result = await api<PostDetail>(`/library/posts/${encodeURIComponent(key)}?limit=1&offset=0`)
    if (!current(context) || request !== detailGeneration) return
    detail.value = result
  } catch (cause) {
    if (!current(context) || request !== detailGeneration) return
    detailError.value = errorMessage(cause)
    reportAccessError(cause)
  } finally {
    if (current(context) && request === detailGeneration) detailBusy.value = false
  }
}
async function loadMedia(offset = 0, append = false) {
  if (append && (mediaBusy.value || !mediaPage.value?.hasMore || mediaError.value)) return
  const request = ++mediaGeneration
  if (!append) mediaPageVersion.value++
  const context = contextGeneration
  if (!append) { mediaPage.value = null; loadedMedia.value = []; mediaOffset.value = 0 }
  mediaError.value = ''
  if (!canAccess.value) return
  mediaBusy.value = true
  const query = new URLSearchParams({ includeMain: String(includeMain.value), limit: String(mediaLimit), offset: String(offset) })
  for (const type of types.value) query.append('types', type)
  const key = props.postKey
  try {
    const result = await api<MediaCatalogPage>(`/library/posts/${encodeURIComponent(key)}/media?${query}`)
    if (!current(context) || request !== mediaGeneration) return
    if (result?.scope !== 'INDEXED_ONLY' || result.offset !== offset || !Array.isArray(result.items)
      || result.items.length > mediaLimit || typeof result.hasMore !== 'boolean'
      || (result.hasMore && !result.items.length)) throw new Error('媒体读取位置无法确认，请重新加载媒体。')
    if (append && mediaPage.value && (result.total !== mediaPage.value.total || result.mainMediaCount !== mediaPage.value.mainMediaCount
      || result.commentMediaCount !== mediaPage.value.commentMediaCount)) throw new Error('本机媒体索引已变化，请刷新列表后继续。')
    const merged = new Map(loadedMedia.value.map(item => [item.key, item]))
    for (const item of result.items) {
      if (append && merged.has(item.key)) throw new Error('媒体读取位置发生重叠，请重新加载媒体。')
      merged.set(item.key, item)
    }
    loadedMedia.value = [...merged.values()]
    mediaPage.value = result; mediaOffset.value = result.offset
  } catch (cause) {
    if (!current(context) || request !== mediaGeneration) return
    mediaError.value = errorMessage(cause)
    reportAccessError(cause)
  } finally {
    if (current(context) && request === mediaGeneration) mediaBusy.value = false
  }
}
function loadMore() {
  if (mediaPage.value?.hasMore) void loadMedia(mediaPage.value.offset + mediaPage.value.items.length, true)
}
function toggleType(type: MediaType) {
  if (!canAccess.value) return
  const selected = types.value.includes(type) ? types.value.filter(value => value !== type) : [...types.value, type]
  types.value = typeOptions.filter(option => selected.includes(option.value)).map(option => option.value)
}
function refreshLocal() {
  if (!canAccess.value || detailBusy.value || mediaBusy.value) return
  void loadDetail()
  void loadMedia(0)
}
watch([() => props.postKey, () => props.accountId, () => props.authorized, () => props.available], () => {
  contextGeneration++; detailGeneration++; mediaGeneration++
  mediaPageVersion.value++
  detail.value = null; mediaPage.value = null; loadedMedia.value = []; detailError.value = ''; mediaError.value = ''
  detailBusy.value = false; mediaBusy.value = false; mediaOffset.value = 0
  if (canAccess.value) { void loadDetail(); void loadMedia(0) }
}, { immediate: true })
watch([() => types.value.join(','), includeMain], () => { void loadMedia(0) })
onBeforeUnmount(() => { disposed = true; contextGeneration++; detailGeneration++; mediaGeneration++ })
</script>

<template>
  <article class="library-post-detail" aria-label="主帖资源详情">
    <header class="detail-toolbar">
      <div class="detail-context" :title="detail ? `${detail.post.channelTitle || '未命名频道'} / ${detail.post.title || '未命名主帖'}` : '主帖详情'"><span v-if="detail" class="detail-context-channel">{{ detail.post.channelTitle || '未命名频道' }}</span><span v-if="detail" class="detail-context-divider" aria-hidden="true">/</span><h2>{{ detail?.post.title || (detailBusy ? '正在读取主帖…' : '主帖详情') }}</h2></div>
      <div class="detail-actions">
        <button class="icon-btn close-detail" type="button" aria-label="关闭主帖详情，返回资源目录" title="返回资源目录" @click="emit('close')"><AppIcon name="close" /></button>
      </div>
    </header>

    <div v-if="!authorized" class="notice warning" role="status"><AppIcon name="lock" /><span>请先完成 Telegram 授权，再查看当前账号的本机资源。</span></div>
    <div v-else-if="!available" class="notice warning" role="status"><AppIcon name="info" /><span>本机服务当前不可用，资源详情读取已暂停。</span></div>
    <div v-if="detailError" class="notice danger" role="alert"><AppIcon name="info" /><span>{{ detailError }}</span><button class="btn" type="button" :disabled="!canAccess || detailBusy" @click="loadDetail">重试详情</button></div>
    <div v-if="detailBusy && !detail" class="detail-loading" role="status"><AppIcon name="clock" />正在读取本机主帖详情…</div>

    <template v-if="detail && canAccess">
      <div v-if="readingRisk" class="visible-reading-risk" role="status"><p><AppIcon name="info" />{{ readingRisk }}</p><ul v-if="visibleWarnings.length"><li v-for="warning in visibleWarnings" :key="warning">{{ warning }}</li></ul></div>
    </template>

    <section v-if="canAccess" class="settings-section detail-media" aria-labelledby="catalog-media-title">
      <div class="section-title"><h3 id="catalog-media-title">媒体清单</h3><button class="btn" type="button" :disabled="!canAccess || detailBusy || mediaBusy" title="刷新已保存的评论媒体清单" @click="refreshLocal"><AppIcon name="refresh" />刷新评论区</button></div>
      <fieldset class="media-filters" aria-label="评论媒体类型"><div class="filter-chips"><button type="button" :class="['filter-chip', { selected: !types.length }]" :aria-pressed="!types.length" @click="types = []">全部</button><button v-for="option in typeOptions" :key="option.value" type="button" :class="['filter-chip', { selected: types.includes(option.value) }]" :aria-pressed="types.includes(option.value)" @click="toggleType(option.value)">{{ option.label }}</button></div></fieldset>
      <p class="filter-selection" role="status" aria-live="polite">评论筛选：<strong>{{ selectedTypeLabel }}</strong><span v-if="types.length > 1"> · 满足任一所选类型</span><span v-if="!mediaBusy && mediaPage"> · 命中 {{ mediaPage.commentMediaCount }} 个评论 / 回复媒体</span></p>
      <label class="main-media-toggle"><input v-model="includeMain" type="checkbox" /><span>包含主帖自身全部媒体</span></label>
      <div v-if="mediaError" class="notice danger" role="alert"><AppIcon name="info" /><span>{{ mediaError }}</span><button class="btn" type="button" :disabled="mediaBusy" @click="loadMedia(0)">重新加载媒体</button></div>
      <div v-if="mediaBusy && !mediaPage" class="detail-loading" role="status"><AppIcon name="clock" />正在读取本机媒体…</div>
      <template v-if="mediaPage">
        <div class="media-counts"><span>当前条件命中 <strong>{{ mediaPage.total }}</strong> 个本机媒体</span><span>主帖 {{ mediaPage.mainMediaCount }} · 评论 / 回复 {{ mediaPage.commentMediaCount }}</span></div>
        <div class="media-page-heading"><span>已加载 {{ loadedMedia.length }} / {{ mediaPage.total }} 个本机媒体</span><MediaViewSwitch v-model="mediaView" /></div>
        <details v-if="includeMain && mediaPage.mainMediaCount > 0" :key="mediaPageVersion" class="media-source-group main-source-group">
          <summary><strong>主帖附件（{{ mediaPage.mainMediaCount }}）</strong><span>已加载 {{ mainPageItems.length }} 个</span></summary>
          <LibraryMediaTable :items="mainPageItems" empty-text="主帖附件尚未加载，请继续向下滚动。" />
        </details>
        <div class="media-source-group comment-source-group">
          <h4>评论 / 回复媒体 <span>{{ selectedTypeLabel }} · 已加载 {{ commentPageItems.length }} / 筛选命中 {{ mediaPage.commentMediaCount }} 个</span></h4>
          <MediaCollection :items="commentPageItems" :layout="mediaView" :post-key="postKey" :account-id="accountId" :authorized="authorized" :available="available" :version="mediaPageVersion" :empty-text="mediaPage.commentMediaCount ? '评论媒体尚未加载，请继续向下滚动。' : `评论 / 回复中没有匹配「${selectedTypeLabel}」的已保存媒体。`" />
        </div>
        <InfiniteLoad :has-more="mediaPage.hasMore" :busy="mediaBusy" :blocked="!!mediaError || !canAccess" :version="mediaOffset + ':' + loadedMedia.length" :context="postKey + ':' + mediaPageVersion + ':' + mediaView" @load="loadMore" />
        <p v-if="!mediaPage.hasMore" class="detail-note">已展示全部 {{ loadedMedia.length }} 个本机匹配媒体。</p>
      </template>
    </section>
    <details v-if="detail && canAccess" class="settings-section reading-details">
      <summary>读取详情与原消息</summary>
      <div class="reading-details-body">
        <p class="detail-note">媒体数量仅统计本地已保存内容；需要读取 Telegram 新内容时，使用页面上方“添加 / 更新主帖”。</p>
        <section aria-labelledby="catalog-detail-title">
          <div class="section-title"><h3 id="catalog-detail-title">{{ detail.post.title || '未命名主帖' }}</h3></div>
          <p class="folded-channel-title">{{ detail.post.channelTitle || '未命名频道' }}</p>
          <div class="detail-post-text"><span>主帖完整文字</span><p>{{ detail.post.text || '此主帖未返回文字。' }}</p></div>
          <h4 class="folded-subheading">主帖身份与原消息 · {{ detail.post.mainMessages.length }} 条成员</h4>
          <dl class="detail-identities"><dt>Telegram 账号 ID</dt><dd><code>{{ detail.post.accountId }}</code></dd><dt>频道 ID</dt><dd><code>{{ detail.post.channelId }}</code></dd><dt>规范主帖消息 ID</dt><dd><code>{{ detail.post.mainMessageId }}</code></dd><dt>主帖稳定标识</dt><dd><code>{{ detail.post.key }}</code></dd><dt>相册 ID</dt><dd>{{ detail.post.albumId === '0' ? '非相册，单条主帖' : detail.post.albumId ?? '未报告相册标识' }}</dd></dl>
          <ul class="message-links"><li v-for="message in detail.post.mainMessages" :key="`${message.chatId}:${message.messageId}`"><span>聊天 <code>{{ message.chatId }}</code> / 消息 <code>{{ message.messageId }}</code></span><a v-if="originalMessageLink(message.link)" :href="originalMessageLink(message.link)" target="_blank" rel="noopener noreferrer">原消息 ↗</a><span v-else class="link-unavailable">无可用链接</span></li></ul>
        </section>
        <section class="discussion-details" aria-labelledby="catalog-range-title">
          <div class="section-title"><h3 id="catalog-range-title">读取范围</h3><span class="small-tag">最近保存：{{ coverageLabel(detail.coverage) }}</span></div>
          <div class="range-grid">
            <div :class="['range-card', mainCoverage?.toLowerCase()]"><span>主帖 / 相册</span><strong>{{ rangeLabel(mainCoverage, 'main') }}</strong><p>{{ detail.post.albumId === '0' ? '非相册，单条主帖' : detail.post.albumEvidence?.complete ? `相册已核全 · ${detail.post.mainMessages.length} 条成员` : `当前索引 ${detail.post.mainMessages.length} 条成员；是否核全请查看边界证据。` }}</p><small>读取方法：{{ albumMethod }}</small><details v-if="detail.post.albumEvidence" class="evidence-details"><summary>相册边界证据</summary><dl class="detail-identities"><dt>核全结果</dt><dd>{{ detail.post.albumEvidence.complete ? '后台已确认核全' : '后台尚未确认核全' }}</dd><dt>较新侧边界 ID</dt><dd><code>{{ detail.post.albumEvidence.newerBoundaryId ?? '未报告' }}</code></dd><dt>较旧侧边界 ID</dt><dd><code>{{ detail.post.albumEvidence.olderBoundaryId ?? '未报告' }}</code></dd><dt>后台方法值</dt><dd><code>{{ detail.post.albumEvidence.method }}</code></dd></dl></details><ul v-if="mainWarnings.length" class="range-warnings"><li v-for="warning in mainWarnings" :key="warning">{{ warning }}</li></ul></div>
            <div :class="['range-card', commentCoverage?.toLowerCase()]"><span>评论历史</span><strong>{{ rangeLabel(commentCoverage, 'comment') }}</strong><p>{{ detail.exhausted ? '后台报告本轮已到读取边界。' : '尚未确认到达本轮读取边界。' }}到达边界与范围完整分别判断。</p><small>本机累计评论消息：{{ detail.indexedCommentMessages ?? '未提供' }} · 媒体：{{ detail.indexedCommentMedia ?? '未提供' }}</small><ul v-if="commentWarnings.length" class="range-warnings"><li v-for="warning in commentWarnings" :key="warning">{{ warning }}</li></ul></div>
          </div>
          <p class="detail-note">这些状态只描述最近保存时的读取范围；评论新增、编辑或删除后，需要手动读取核对。媒体为空、本页不足 50 个均不代表远端已读完。</p>
        </section>
        <section class="discussion-details" aria-labelledby="catalog-discussion-title">
          <div class="section-title"><h3 id="catalog-discussion-title">关联讨论与原消息</h3><span class="small-tag">{{ detail.post.canReadComments ? '评论可读' : '评论不可读' }}</span></div>
          <dl class="detail-identities"><dt>讨论组 ID</dt><dd><code>{{ detail.post.discussionChatId ?? '未返回讨论组' }}</code></dd><dt>讨论根消息 ID</dt><dd><code>{{ detail.post.discussionRootMessageId ?? '未返回讨论根消息' }}</code></dd></dl>
          <ul v-if="detail.post.discussionRoots.length" class="message-links"><li v-for="message in detail.post.discussionRoots" :key="`${message.chatId}:${message.messageId}`"><span>聊天 <code>{{ message.chatId }}</code> / 消息 <code>{{ message.messageId }}</code></span><a v-if="originalMessageLink(message.link)" :href="originalMessageLink(message.link)" target="_blank" rel="noopener noreferrer">讨论原消息 ↗</a><span v-else class="link-unavailable">无可用链接</span></li></ul>
          <p class="detail-note">频道主帖与讨论组保留各自的聊天和消息身份，链接跳转 Telegram 原消息。</p>
        </section>
      </div>
    </details>
  </article>
</template>

<style scoped>
.filter-chip{gap:6px;cursor:pointer}.filter-selection{font-size:10px;line-height:1.8;color:#728da2;margin:10px 0 0}.filter-selection strong{color:#436b8b;font-weight:600}.media-source-group{margin-top:17px}.media-source-group>h4{display:flex;align-items:baseline;flex-wrap:wrap;gap:5px 12px;font-size:11px;color:#4e6d85;font-weight:600;margin:0 0 10px}.media-source-group>h4>span{font-size:9px;color:#8ca1b1;font-weight:400}
.main-source-group>summary{cursor:pointer;list-style-position:inside;padding:8px 11px;border:1px solid #e7eef4;border-radius:5px;background:#f8fafc;color:#839bad;font-size:10px;line-height:1.8}.main-source-group>summary>strong{font-weight:550;color:#5e7d94;margin-right:12px}.main-source-group>summary>span{font-size:9px;color:#96aaba}.main-source-group>summary:focus-visible{outline:2px solid #2d85cc;outline-offset:3px}.main-source-group[open]>summary{margin-bottom:10px}
.library-post-detail{min-width:0}.detail-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:0 0 17px}.detail-eyebrow{font-size:10px;letter-spacing:1px;color:#5f8099;font-weight:600}.detail-toolbar p{font-size:10px;color:#98a9b7;margin-top:4px}.detail-actions{display:flex;align-items:center;gap:9px;flex-shrink:0}.close-detail{width:32px;height:32px;border:1px solid #e1e8ee;background:#fff;color:#8297a8}.detail-heading,.section-title{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;margin-bottom:17px}.detail-channel{font-size:10px;color:#8da4b6;margin:0 0 5px}.detail-heading h2{font-size:17px;line-height:1.6;font-weight:600;color:#3a566c;margin:0;overflow-wrap:anywhere}.section-title h3{font-size:12px;font-weight:600;color:#4e6d85;margin:0}.section-title p{font-size:10px;color:#97aab9;margin-top:5px}.section-title>.btn{min-height:31px;font-size:10px}.detail-post-text{background:#f8fafc;border:1px solid #e8eef4;border-radius:6px;padding:13px 15px;margin:0 0 15px}.detail-post-text>span{font-size:9px;color:#99adbc}.detail-post-text>p{font-size:11px;line-height:1.9;color:#728a9d;white-space:pre-wrap;overflow-wrap:anywhere;max-height:300px;overflow-y:auto;margin-top:6px}.detail-identities{display:grid;grid-template-columns:125px minmax(0,1fr);gap:10px 12px;font-size:10px;margin:15px 0}.detail-identities dt{color:#97aab9}.detail-identities dd{margin:0;color:#6e889e;overflow-wrap:anywhere}.detail-identities code{font-size:10px}.identity-details{font-size:10px;color:#829aaf}.identity-details>summary,.evidence-details>summary{cursor:pointer;line-height:1.9}.message-links{list-style:none;margin:12px 0 0;padding:0;border-top:1px solid #edf2f6}.message-links li{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;font-size:10px;color:#8b9fae;padding:10px 0;border-bottom:1px solid #edf2f6}.message-links li>span{overflow-wrap:anywhere}.message-links a{font-size:10px;white-space:nowrap;text-decoration:none;color:#6294bb}.link-unavailable{color:#acb9c4;font-size:9px}.coverage-badge{display:inline-flex;align-items:center;padding:3px 7px;border:1px solid #eee1c7;border-radius:4px;font-size:9px;color:#a18451;background:#fffaf0;white-space:nowrap}.coverage-badge.complete{background:#f0f8f3;border-color:#dcece2;color:#649076}.coverage-badge.unavailable{background:#fcf1ef;border-color:#eeddd7;color:#ac7b70}.range-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.range-card{min-width:0;background:#f8fafc;border:1px solid #e6edf3;border-radius:7px;padding:14px}.range-card>span{display:block;font-size:9px;letter-spacing:.4px;color:#91a6b7}.range-card>strong{display:block;font-size:12px;font-weight:550;color:#7890a3;margin:7px 0 8px}.range-card>p{font-size:10px;color:#91a5b5;line-height:1.9;overflow-wrap:anywhere}.range-card>small{display:block;font-size:9px;color:#91a8b9;line-height:1.9;margin-top:9px}.range-card.complete{background:#f4faf6;border-color:#e0ede4}.range-card.complete>strong{color:#659176}.range-card.partial{background:#fffcf6;border-color:#eee5d4}.range-card.partial>strong{color:#ac905e}.range-card.unavailable{background:#fcf5f3;border-color:#f0e2dd}.range-card.unavailable>strong{color:#b08377}.evidence-details{font-size:9px;color:#8c9eab;margin-top:10px}.evidence-details .detail-identities{grid-template-columns:95px minmax(0,1fr);gap:8px;font-size:9px;margin-bottom:0}.evidence-details code{font-size:9px}.range-warnings{padding-left:16px;margin:10px 0 0;font-size:9px;line-height:1.9;color:#a58b5f;overflow-wrap:anywhere}.detail-warnings{background:#fff9ed;border:1px solid #eee1c8;border-radius:6px;padding:12px 14px;color:#a28a5e;margin-top:15px;font-size:10px}.detail-warnings>strong{display:flex;align-items:center;gap:6px;font-size:10px;font-weight:550}.detail-warnings .icon{width:13px;height:13px}.detail-warnings ul{padding-left:18px;margin:9px 0 0;line-height:1.9;overflow-wrap:anywhere}.detail-note{font-size:9px;line-height:1.9;color:#97aab9;margin-top:12px}.detail-loading{display:flex;align-items:center;justify-content:center;gap:8px;font-size:11px;color:#87a0b4;border:1px dashed #dce6ef;background:#fbfdff;border-radius:7px;padding:35px 18px;margin:0 0 17px}.detail-loading .icon{width:16px;height:16px}.filter-chips{display:flex;flex-wrap:wrap;gap:7px}.filter-chip{display:inline-flex;align-items:center;justify-content:center;border:1px solid #e3eaf0;border-radius:5px;padding:5px 11px;background:#fff;color:#91a4b4;font-size:10px;min-height:30px}.filter-chip:hover{border-color:#bfd1df;background:#f6fafd}.filter-chip.selected{background:#233e55;border-color:#233e55;color:#fff}.main-media-toggle{display:flex;align-items:center;gap:8px;margin:12px 0 16px;font-size:10px;color:#728da2;cursor:pointer}.main-media-toggle input{accent-color:#277fce;margin:0;width:14px;height:14px}.media-counts{display:flex;align-items:center;justify-content:space-between;gap:12px;background:#f6f9fc;border:1px solid #e7eef4;border-radius:6px;padding:11px 13px;margin:0 0 16px;font-size:10px;color:#8ca1b1}.media-counts strong{font-size:13px;font-weight:550;color:#6086a2;margin:0 3px}.media-counts>span:last-child{font-size:9px;color:#98aaba}.media-page-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;color:#88a0b2;font-size:10px;margin-bottom:11px}.media-page-heading>span:last-child{font-size:9px;color:#a1b1bd}.detail-pagination{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-top:16px}.detail-pagination>.btn{font-size:10px;min-height:31px;padding:5px 10px}.detail-pagination>span{font-size:9px;color:#9aafbe;text-align:center}.notice>.btn{margin-left:auto;flex-shrink:0;font-size:10px;min-height:28px}.notice>span{overflow-wrap:anywhere}
:deep(.probe-inline-empty){font-size:10px;color:#9badbb;text-align:center;line-height:1.9;padding:28px 14px;border:1px dashed #e2eaf1;border-radius:6px;background:#fcfdfe;margin:0}:deep(.probe-table-scroll){width:100%;overflow-x:auto;border:1px solid #e7eef4;border-radius:6px}:deep(.probe-media-table){border-collapse:collapse;width:100%;min-width:510px;text-align:left;table-layout:fixed;font-size:10px}:deep(.probe-media-table th){font-size:9px;font-weight:500;color:#95a8b7;background:#f7fafc;border-bottom:1px solid #e7eef4;padding:10px 12px;white-space:nowrap}:deep(.probe-media-table th:first-child){width:37%}:deep(.probe-media-table th:nth-child(2)){width:17%}:deep(.probe-media-table th:nth-child(3)){width:30%}:deep(.probe-media-table th:nth-child(4)){width:16%}:deep(.probe-media-table td){padding:12px;border-bottom:1px solid #edf2f6;vertical-align:top;overflow-wrap:anywhere;color:#839aab}:deep(.probe-media-table tr:last-child td){border-bottom:0}:deep(.probe-file-name){font-size:10px;color:#668198;font-weight:550}:deep(.probe-media-caption){white-space:pre-wrap;line-height:1.8;max-height:110px;overflow-y:auto;margin-top:5px;font-size:9px;color:#99abb8}:deep(.probe-source){display:block;font-size:8px;color:#a5b4bf;margin-top:5px}:deep(.probe-media-type){display:block;font-size:10px;color:#7c96aa;margin-bottom:4px}:deep(.probe-media-table td>small){font-size:9px;white-space:nowrap;color:#9aacb9}:deep(.probe-message-ids>span){display:block;font-size:8px;line-height:1.9;color:#9aafbe}:deep(.probe-message-ids code){font-size:9px;color:#819bb0}:deep(.probe-message-link){font-size:9px;color:#699cc2;white-space:nowrap;text-decoration:none}:deep(.probe-link-unavailable){font-size:8px;color:#a6b5c0}
@media(max-width:1050px){.range-grid{grid-template-columns:minmax(0,1fr)}.detail-toolbar{align-items:flex-start;flex-direction:column}.detail-actions{align-self:flex-end}.detail-heading{flex-wrap:wrap}.media-counts{align-items:flex-start;flex-direction:column;gap:5px}}
@media(max-width:680px){.detail-heading h2{font-size:15px}.detail-toolbar p{font-size:9px}.detail-actions>.btn{font-size:10px}.detail-identities{grid-template-columns:105px minmax(0,1fr);gap:9px;font-size:9px}.detail-identities code{font-size:9px}.message-links li{flex-direction:column;gap:5px;font-size:9px}.section-title{flex-wrap:wrap}.media-page-heading{flex-direction:column;align-items:flex-start;gap:4px}.detail-pagination{flex-wrap:wrap;gap:8px}.detail-pagination>span{order:3;flex-basis:100%;font-size:8px}.filter-chip{padding:5px 9px}.notice{flex-wrap:wrap}.notice>.btn{margin-left:25px}.detail-toolbar{gap:11px}}
.visible-reading-risk{border-left:2px solid #d9b477;background:#fffaf1;color:#a68a5a;padding:11px 14px;margin:0 0 17px;border-radius:0 5px 5px 0;font-size:10px;line-height:1.9}.visible-reading-risk>p{display:flex;align-items:flex-start;gap:7px}.visible-reading-risk .icon{width:14px;height:14px;margin-top:2px}.visible-reading-risk ul{margin:7px 0 0;padding-left:21px;overflow-wrap:anywhere}.reading-details{padding:16px 22px;background:#fbfcfd}.reading-details>summary{cursor:pointer;font-size:11px;color:#829aab;font-weight:500;line-height:1.9}.reading-details-body{padding-top:22px}.discussion-details{border-top:1px solid #e7eef4;margin-top:22px;padding-top:20px}
@media(max-width:680px){.reading-details{padding:14px 17px}.visible-reading-risk{font-size:9px;padding:10px 12px}}
.detail-context{display:flex;align-items:center;gap:8px;flex:1;min-width:0;line-height:1.8}.detail-context-channel{font-size:10px;color:#8ea4b5;max-width:140px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex-shrink:0}.detail-context-divider{font-size:10px;color:#c0ccd5}.detail-context>h2{font-size:12px;font-weight:550;color:#5c7b93;line-height:1.8;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;margin:0;min-width:0}.reading-details-body .detail-post-text>p{max-height:none;overflow:visible}.folded-channel-title{font-size:10px;color:#9aafbd;margin:-8px 0 13px}.folded-subheading{font-size:10px;color:#839daf;font-weight:500;margin:19px 0 10px}.reading-details-body .section-title h3{overflow-wrap:anywhere}
@media(min-width:681px) and (max-width:1050px){.detail-toolbar{flex-direction:row;align-items:center}.detail-actions{align-self:auto}.detail-context-channel{max-width:105px}.detail-context>h2{font-size:11px}.detail-actions>.btn{font-size:9px}}
@media(max-width:680px){.detail-context{width:100%;flex-wrap:wrap;gap:1px 7px}.detail-context-channel{max-width:100%;font-size:9px}.detail-context-divider{display:none}.detail-context>h2{flex-basis:100%;white-space:normal;overflow-wrap:anywhere;font-size:11px}}
</style>
