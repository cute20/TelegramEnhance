<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { coverageLabel, libraryTagTerms, type CatalogItem, type CatalogPage, type CatalogSort, type LibraryChannel, type PostDetail } from '../library'
import { postCardContent } from '../postCardContent'
import AppIcon from './AppIcon.vue'
import LibraryPostDetail from './LibraryPostDetail.vue'
import LibraryPostPreview from './LibraryPostPreview.vue'
import LibraryChannelImport from './LibraryChannelImport.vue'

const props = defineProps<{ authorized: boolean; available: boolean; accountId: string | null }>()
const emit = defineEmits<{ settings: []; accessError: [cause: ApiError] }>()
const mode = ref<'catalog' | 'import'>('catalog')
const addingPost = ref(false), postLink = ref('')
const channels = ref<LibraryChannel[]>([])
const selectedChannels = ref<string[]>([])
const tagsInput = ref(''), keywordsInput = ref(''), appliedTags = ref(''), appliedKeywords = ref('')
const sort = ref<CatalogSort>('published-desc')
const view = ref<'cards' | 'list'>('cards')
const allTagsVisible = ref(false)
const channelPicker = ref<HTMLDetailsElement | null>(null)
const catalog = ref<CatalogPage | null>(null)
const catalogCards = computed(() => catalog.value?.items.map(item => ({ item, content: postCardContent(item.post.title, item.post.text, item.tags) })) ?? [])
const selectedPostKey = ref('')
const readerPage = ref<HTMLElement | null>(null)
const offset = ref(0), busy = ref(''), error = ref(''), catalogFailed = ref(false)
const canAccess = computed(() => props.authorized && props.available)
const controlsEnabled = computed(() => canAccess.value && !busy.value)
const hasSearch = computed(() => !!appliedTags.value || !!appliedKeywords.value)
const searchPending = computed(() => tagsInput.value.trim() !== appliedTags.value || keywordsInput.value.trim() !== appliedKeywords.value)
const selectedTagTerms = computed(() => libraryTagTerms(appliedTags.value))
const selectedChannelLabel = computed(() => selectedChannels.value.length === 1 ? channels.value.find(channel => channel.id === selectedChannels.value[0])?.title || '已选 1 个频道' : selectedChannels.value.length ? `已选 ${selectedChannels.value.length} 个频道` : '全部频道')
const matchedRange = computed(() => catalog.value?.items.length ? `${catalog.value.offset + 1}–${catalog.value.offset + catalog.value.items.length}` : '0')
const tagFacets = computed(() => {
  const facets = catalog.value?.facets?.tags ?? []
  const shown = allTagsVisible.value ? [...facets] : facets.slice(0, 12)
  for (const tag of selectedTagTerms.value) {
    if (!shown.some(facet => sameTag(facet.tag, tag))) shown.push(facets.find(facet => sameTag(facet.tag, tag)) ?? { tag, count: 0 })
  }
  return shown
})
let generation = 0, readerRevision = 0, disposed = false, catalogDirty = false, returnScroll = 0
let returnFocus: HTMLElement | null = null

function sameTag(left: string, right: string) { return left.replace(/^#+/u, '').normalize('NFKC').toLocaleLowerCase('en-US') === right.replace(/^#+/u, '').normalize('NFKC').toLocaleLowerCase('en-US') }
function tagSelected(tag: string) { return selectedTagTerms.value.some(selected => sameTag(selected, tag)) }
function current(requestGeneration: number) { return !disposed && generation === requestGeneration && canAccess.value && mode.value === 'catalog' }
async function execute(name: string, action: (requestGeneration: number) => Promise<void>) {
  if (!canAccess.value || busy.value || mode.value !== 'catalog') return
  const requestGeneration = ++generation
  busy.value = name; error.value = ''; catalogFailed.value = false
  try { await action(requestGeneration) }
  catch (cause) {
    if (!current(requestGeneration)) return
    catalogFailed.value = true
    error.value = cause instanceof Error ? cause.message : '本机资源索引读取失败，请重试。'
    if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
  } finally { if (generation === requestGeneration) busy.value = '' }
}
function catalogPath(targetOffset: number) {
  const query = new URLSearchParams({ tags: appliedTags.value, keywords: appliedKeywords.value, limit: '20', offset: String(targetOffset), sort: sort.value })
  selectedChannels.value.forEach(id => query.append('channelIds', id))
  return `/library/catalog?${query}`
}
async function loadCatalogData(targetOffset: number, requestGeneration: number) {
  const result = await api<CatalogPage>(catalogPath(targetOffset))
  if (current(requestGeneration)) { catalog.value = result; offset.value = result.offset }
}
function loadCatalog(targetOffset = 0) { return execute('catalog', requestGeneration => loadCatalogData(targetOffset, requestGeneration)) }
function refreshCatalog() {
  return execute('refresh', async requestGeneration => {
    const [availableChannels, result] = await Promise.all([api<LibraryChannel[]>('/library/channels'), api<CatalogPage>(catalogPath(offset.value))])
    if (!current(requestGeneration)) return
    channels.value = availableChannels; catalog.value = result; offset.value = result.offset
  })
}
function filterChanged() { closePost(); catalog.value = null; offset.value = 0; return loadCatalog(0) }
function toggleChannel(id: string) {
  if (!controlsEnabled.value) return
  selectedChannels.value = selectedChannels.value.includes(id) ? selectedChannels.value.filter(selected => selected !== id) : [...selectedChannels.value, id]
  void filterChanged()
}
function allChannels() { if (controlsEnabled.value) { selectedChannels.value = []; void filterChanged() } }
function search() {
  if (!controlsEnabled.value) return
  appliedTags.value = tagsInput.value.trim(); appliedKeywords.value = keywordsInput.value.trim()
  void filterChanged()
}
function toggleTag(tag: string) {
  if (!controlsEnabled.value) return
  const selected = tagSelected(tag) ? selectedTagTerms.value.filter(selected => !sameTag(selected, tag)) : [...selectedTagTerms.value, tag.replace(/^#+/u, '')]
  tagsInput.value = appliedTags.value = selected.join(' ')
  void filterChanged()
}
function clearTags() { if (controlsEnabled.value) { tagsInput.value = ''; appliedTags.value = ''; void filterChanged() } }
function clearSearch() {
  if (!controlsEnabled.value) return
  tagsInput.value = ''; keywordsInput.value = ''; appliedTags.value = ''; appliedKeywords.value = ''
  void filterChanged()
}
function changeSort(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (!controlsEnabled.value || !['published-desc', 'title-asc', 'updated-desc'].includes(value) || value === sort.value) return
  sort.value = value as CatalogSort
  void filterChanged()
}
function openPost(key: string, event?: Event) {
  if (!controlsEnabled.value) return
  if (channelPicker.value) channelPicker.value.open = false
  returnFocus = event?.currentTarget && typeof HTMLElement !== 'undefined' && event.currentTarget instanceof HTMLElement ? event.currentTarget : typeof document !== 'undefined' ? document.activeElement as HTMLElement | null : null
  returnScroll = typeof window !== 'undefined' ? window.scrollY : 0
  selectedPostKey.value = key
  const revision = ++readerRevision
  void nextTick(() => {
    if (disposed || revision !== readerRevision || selectedPostKey.value !== key || mode.value !== 'catalog') return
    readerPage.value?.focus({ preventScroll: true })
    if (typeof window !== 'undefined') window.scrollTo({ top: 0, behavior: 'instant' })
  })
}
function closePost(event?: Event) {
  event?.preventDefault()
  const wasOpen = !!selectedPostKey.value
  selectedPostKey.value = ''
  const target = returnFocus; returnFocus = null
  const revision = ++readerRevision
  void nextTick(() => {
    if (disposed || revision !== readerRevision || selectedPostKey.value || mode.value !== 'catalog') return
    if (target?.isConnected) target.focus({ preventScroll: true })
    if (wasOpen && typeof window !== 'undefined') window.scrollTo({ top: returnScroll, behavior: 'instant' })
  })
  if (wasOpen && catalogDirty && canAccess.value) { catalogDirty = false; void refreshCatalog() }
}
function showCatalogView(nextView: 'cards' | 'list') { view.value = nextView }
function showAddPost() { if (controlsEnabled.value) { addingPost.value = !addingPost.value; error.value = '' } }
function detailUpdated() { catalogDirty = true }
async function addPost() {
  const link = postLink.value.trim(), account = props.accountId
  if (!link || !controlsEnabled.value) return
  let addedKey = ''
  await execute('add-post', async requestGeneration => {
    const result = await api<PostDetail>('/library/probe', { method: 'POST', body: { link }, timeout: 60000 })
    if (!current(requestGeneration)) return
    if (!result?.post?.key || result.post.accountId !== account) throw new Error('主帖读取结果与当前账号不符，请刷新列表核对。')
    const [availableChannels, resultPage] = await Promise.all([api<LibraryChannel[]>('/library/channels'), api<CatalogPage>(catalogPath(offset.value))])
    if (!current(requestGeneration)) return
    channels.value = availableChannels; catalog.value = resultPage; offset.value = resultPage.offset
    addingPost.value = false; postLink.value = ''; addedKey = result.post.key
  })
  if (addedKey && account === props.accountId && canAccess.value && mode.value === 'catalog') openPost(addedKey)
}
function openImport() { if (canAccess.value) { closePost(); mode.value = 'import' } }
function coverIcon(item: CatalogItem) {
  const type = item.post.mainMedia[0]?.type ?? (['VIDEO', 'PHOTO', 'AUDIO', 'DOCUMENT', 'OTHER'] as const).find(type => (item.mediaTypeCounts?.[type] ?? 0) > 0)
  return type === 'PHOTO' ? 'photo' : type === 'VIDEO' ? 'video' : type === 'AUDIO' ? 'audio' : type === 'DOCUMENT' ? 'file' : 'library'
}
const mediaTypes = [{ type: 'VIDEO', label: '视频', icon: 'video' }, { type: 'PHOTO', label: '图片', icon: 'photo' }, { type: 'AUDIO', label: '音频', icon: 'audio' }, { type: 'DOCUMENT', label: '文档', icon: 'file' }, { type: 'OTHER', label: '其他', icon: 'file' }] as const
function cardMediaCounts(item: CatalogItem) { return mediaTypes.filter(type => (item.mediaTypeCounts?.[type.type] ?? 0) > 0).map(type => ({ ...type, count: item.mediaTypeCounts![type.type] })) }
function publishedTime(item: CatalogItem) { return item.publishedAt ?? item.post.publishedAt ?? null }
function dateLabel(timestamp: number | null | undefined) {
  if (typeof timestamp !== 'number' || !Number.isFinite(timestamp) || timestamp <= 0) return '发布时间未知'
  return new Date(timestamp).toLocaleDateString('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' })
}
function dateIso(timestamp: number | null | undefined) { return typeof timestamp === 'number' && Number.isFinite(timestamp) && timestamp > 0 ? new Date(timestamp).toISOString() : undefined }
function updatedLabel(timestamp: number) {
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '未提供索引更新时间'
  return new Date(timestamp).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}

watch([() => props.authorized, () => props.available, () => props.accountId, mode], (values, previous) => {
  const accountChanged = !!previous && values[2] !== previous[2]
  generation++; busy.value = ''
  if (!props.authorized || accountChanged) {
    catalogDirty = false; channels.value = []; catalog.value = null; selectedChannels.value = []; closePost()
    tagsInput.value = ''; keywordsInput.value = ''; appliedTags.value = ''; appliedKeywords.value = ''; offset.value = 0; error.value = ''; catalogFailed.value = false; addingPost.value = false; postLink.value = ''; allTagsVisible.value = false; catalogDirty = false
    if (accountChanged) mode.value = 'catalog'
  }
  if (canAccess.value && mode.value === 'catalog') void refreshCatalog()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++; readerRevision++; returnFocus = null })
</script>

<template>
  <div class="library-workspace">
    <template v-if="mode === 'import'">
      <div class="library-mode-toolbar"><button class="btn" type="button" @click="mode = 'catalog'"><AppIcon name="library" />返回我的资源库</button><span>近期主帖小批导入</span></div>
      <LibraryChannelImport :authorized="authorized" :available="available" :account-id="accountId" @settings="emit('settings')" @access-error="cause => emit('accessError', cause)" />
    </template>
    <template v-else>
      <section v-show="!selectedPostKey" class="library-catalog-page" aria-label="资源库目录">
      <div class="library-workspace-toolbar">
        <div class="library-scope-label"><AppIcon name="server" /><span>本机索引</span><small v-if="catalog">{{ catalog.indexedChannels }} 个频道 · {{ catalog.indexedPosts }} 条主帖</small></div>
        <div class="library-workspace-actions"><button class="btn" type="button" :disabled="!controlsEnabled" title="重新读取本机索引，不扫描 Telegram 新内容" @click="refreshCatalog"><AppIcon name="refresh" />刷新列表</button><button class="btn" type="button" :disabled="!canAccess" @click="openImport"><AppIcon name="library" />导入频道</button><button class="btn primary" type="button" :disabled="!canAccess" @click="showAddPost"><AppIcon name="plus" />添加主帖</button></div>
      </div>
      <form v-if="addingPost" class="library-add-post" @submit.prevent="addPost">
        <label for="library-post-link">粘贴主帖链接</label>
        <div><input id="library-post-link" v-model="postLink" class="input" type="url" placeholder="https://t.me/..." :disabled="!!busy || !canAccess" autocomplete="off" required /><button class="btn primary" type="submit" :disabled="!controlsEnabled || !postLink.trim()">{{ busy === 'add-post' ? '正在读取…' : '添加并阅读评论' }}</button><button class="btn" type="button" :disabled="!!busy" @click="addingPost = false">取消</button></div>
        <p>添加自己频道的主帖后，直接进入评论阅读页。</p>
      </form>
      <form class="library-search-panel" role="search" @submit.prevent="search">
        <div class="library-search-main"><label for="catalog-keywords"><AppIcon name="search" /><span class="visually-hidden">主帖关键词 · 全部包含</span><input id="catalog-keywords" v-model="keywordsInput" :disabled="!canAccess" placeholder="搜索主帖标题或文字，多个关键词用空格分隔" autocomplete="off" /></label><button class="btn primary" type="submit" :disabled="!controlsEnabled">搜索主帖<AppIcon name="arrow" /></button></div>
        <div class="library-search-footer"><details class="library-advanced-search"><summary>手动输入标签</summary><label for="catalog-tags"><span>全部标签都需满足</span><input id="catalog-tags" v-model="tagsInput" class="input" :disabled="!canAccess" placeholder="例如：北京 旅行" autocomplete="off" /></label><p>用空格或逗号分隔。标签与关键词同时填写时全部满足，仅匹配主帖文字。</p></details><button v-if="hasSearch || tagsInput || keywordsInput" class="library-text-button" type="button" :disabled="!controlsEnabled" @click="clearSearch">清空检索</button><span v-if="searchPending" class="library-search-pending" role="status">输入已修改，点击搜索生效</span></div>
      </form>

      <div v-if="!authorized" class="library-state" role="status"><span class="library-state-icon"><AppIcon name="lock" /></span><h2>连接后浏览自己的资源</h2><p>先在设置中完成 Telegram 授权，再读取当前账号的本机索引。</p><button class="btn primary" type="button" @click="emit('settings')">前往设置</button></div>
      <div v-else-if="!available" class="notice warning" role="alert"><AppIcon name="info" /><span>本机服务暂不可用，读取已暂停；恢复后会重新核对当前筛选。</span></div>
      <div v-if="error" class="notice danger library-error" role="alert"><AppIcon name="info" /><div><strong>资源库读取未完成</strong><p>{{ error }}</p><small v-if="catalogFailed && catalog">当前保留上次成功读取的索引结果。</small></div><button class="btn" type="button" :disabled="!controlsEnabled" @click="refreshCatalog">重试</button></div>

      <div class="library-filter-bar">
        <details ref="channelPicker" class="library-channel-picker"><summary><AppIcon name="library" /><span>{{ selectedChannelLabel }}</span><small>{{ selectedChannels.length ? selectedChannels.length + ' 项' : channels.length + ' 个频道' }}</small><AppIcon name="chevron" /></summary>
          <div class="library-channels" aria-label="自己的频道筛选"><div class="library-panel-caption"><strong>筛选频道</strong><span>可多选</span></div><button type="button" :class="['library-channel', { selected: !selectedChannels.length }]" :disabled="!controlsEnabled" :aria-pressed="!selectedChannels.length" @click="allChannels"><AppIcon name="library" /><span>全部频道</span><small>{{ catalog?.indexedPosts ?? '—' }}</small><AppIcon v-if="!selectedChannels.length" name="check" /></button><button v-for="channel in channels" :key="channel.id" type="button" :class="['library-channel', { selected: selectedChannels.includes(channel.id) }]" :disabled="!controlsEnabled" :aria-pressed="selectedChannels.includes(channel.id)" @click="toggleChannel(channel.id)"><span class="library-channel-avatar">{{ (channel.title || '频').slice(0, 1) }}</span><span :title="channel.title">{{ channel.title || '未命名频道' }}</span><small>{{ channel.indexedPosts }}</small><AppIcon v-if="selectedChannels.includes(channel.id)" name="check" /></button><p v-if="!channels.length" class="library-filter-note">{{ canAccess ? busy ? '正在读取频道索引…' : '尚无已索引频道。添加主帖后会显示在这里。' : '连接 Telegram 后读取频道索引。' }}</p><p class="library-filter-note">仅列出已添加主帖所在的频道；再次点击可取消选择。</p></div>
        </details>
        <div class="library-filter-summary"><span v-if="catalog" aria-live="polite">找到 <strong>{{ catalog.total }}</strong> 条主帖</span><span v-else>{{ busy ? '正在读取索引…' : '仅搜索已索引内容' }}</span><small v-if="busy && catalog" role="status">正在刷新…</small></div>
        <label class="library-sort"><span class="visually-hidden">主帖排序</span><select :value="sort" :disabled="!controlsEnabled" aria-label="主帖排序" @change="changeSort"><option value="published-desc">最新发布</option><option value="title-asc">标题 A–Z</option><option value="updated-desc">最近索引更新</option></select></label>
        <div class="library-view-switch" aria-label="资源展示方式"><button type="button" :class="{ active: view === 'cards' }" :aria-pressed="view === 'cards'" aria-label="卡片视图" title="以卡片显示资源目录" @click="showCatalogView('cards')"><AppIcon name="library" /><span class="visually-hidden">卡片</span></button><button type="button" :class="{ active: view === 'list' }" :aria-pressed="view === 'list'" aria-label="目录视图" title="以列表显示资源目录" @click="showCatalogView('list')"><AppIcon name="list" /><span class="visually-hidden">列表</span></button></div>
      </div>
      <div v-if="tagFacets.length" class="library-tag-facets" role="group" aria-label="按主帖标签筛选"><span><AppIcon name="tag" />标签</span><button type="button" class="library-tag-chip" :class="{ selected: !selectedTagTerms.length }" :aria-pressed="!selectedTagTerms.length" :disabled="!controlsEnabled" @click="clearTags">全部</button><button v-for="facet in tagFacets" :key="facet.tag" type="button" class="library-tag-chip" :class="{ selected: tagSelected(facet.tag) }" :aria-pressed="tagSelected(facet.tag)" :disabled="!controlsEnabled" @click="toggleTag(facet.tag)">#{{ facet.tag.replace(/^#+/u, '') }}<small>{{ facet.count }}</small></button><button v-if="(catalog?.facets?.tags.length ?? 0) > 12" class="library-text-button" type="button" @click="allTagsVisible = !allTagsVisible">{{ allTagsVisible ? '收起标签' : '更多标签' }}</button><small v-if="selectedTagTerms.length > 1" class="library-tags-and">同时满足所选标签</small></div>

      <div class="library-catalog-content" :aria-busy="!!busy">
        <div v-if="busy && !catalog" class="library-loading" role="status"><span class="visually-hidden">正在读取本机主帖索引…</span><div class="library-skeleton-grid" aria-hidden="true"><div v-for="index in 6" :key="index" class="library-skeleton-card"><div /><span /><span /></div></div></div>
        <div v-else-if="catalogFailed && !catalog && canAccess" class="library-state" role="status"><span class="library-state-icon"><AppIcon name="info" /></span><h2>本次筛选未完成</h2><p>未能读取当前条件的索引结果，请重试或调整筛选。</p><button class="btn primary" type="button" :disabled="!controlsEnabled" @click="refreshCatalog">重试读取当前条件</button></div>
        <div v-else-if="canAccess && (!catalog || !catalog.items.length)" class="library-state" role="status"><span class="library-state-icon"><AppIcon :name="hasSearch || selectedChannels.length ? 'search' : 'library'" /></span><h2>{{ hasSearch || selectedChannels.length ? '没有找到匹配的主帖' : '从一个频道主帖开始' }}</h2><p>{{ hasSearch || selectedChannels.length ? '试试其他关键词、标签或频道。尚未添加的主帖不在本次搜索范围内。' : '导入自己的频道，或添加一个主帖链接，在这里集中浏览文字与媒体。' }}</p><button v-if="!hasSearch && !selectedChannels.length" class="btn primary" type="button" @click="showAddPost"><AppIcon name="plus" />添加第一个主帖</button><button v-else-if="hasSearch" class="btn" type="button" @click="clearSearch">清空检索</button></div>
        <template v-else-if="catalog && catalog.items.length">
          <div :class="['library-posts', view]">
            <article v-for="{ item, content } in catalogCards" :key="item.post.key" class="library-post-card" :class="{ 'is-selected': selectedPostKey === item.post.key }"><button class="library-post-open" type="button" :disabled="!controlsEnabled" :aria-label="'查看主帖：' + content.title" :aria-expanded="selectedPostKey === item.post.key" @click="openPost(item.post.key, $event)"><div :class="['library-post-cover', coverIcon(item)]"><LibraryPostPreview :post-key="item.post.key" :cache-revision="JSON.stringify([item.updatedAt, item.post])" :account-id="accountId" :version="item.updatedAt" :authorized="authorized" :available="available" :icon="coverIcon(item)" lazy /><span class="library-cover-count">{{ item.post.mainMedia.length + item.indexedCommentMedia }} 个媒体</span></div><div class="library-card-body"><small class="library-card-channel"><span class="connection-dot" />{{ item.post.channelTitle || '未命名频道' }}</small><h2>{{ content.title }}</h2><p v-if="content.excerpt || !item.post.text" class="library-card-excerpt">{{ content.excerpt || '此主帖没有文字说明' }}</p><div v-if="content.tags.length" class="library-card-tags"><span v-for="tag in content.tags.slice(0, 3)" :key="tag">{{ tag }}</span><small v-if="content.tags.length > 3">+{{ content.tags.length - 3 }}</small></div><div class="library-card-media-count"><span v-for="type in cardMediaCounts(item)" :key="type.type"><AppIcon :name="type.icon" />{{ type.label }} {{ type.count }}</span><span v-if="!item.mediaTypeCounts">主帖 {{ item.post.mainMedia.length }} · 评论媒体 {{ item.indexedCommentMedia }}</span></div><div class="library-card-footer"><span :class="['library-coverage-text', item.post.coverage.toLowerCase()]">{{ coverageLabel(item.post.coverage) }}</span><time :datetime="dateIso(publishedTime(item))" :title="'发布：' + dateLabel(publishedTime(item)) + '；索引更新：' + updatedLabel(item.updatedAt)">{{ dateLabel(publishedTime(item)) }}</time></div></div></button></article>
          </div>
          <nav class="library-catalog-pagination" aria-label="资源库分页"><span>已索引匹配结果 {{ matchedRange }} / {{ catalog.total }}</span><div><button class="btn" type="button" :disabled="!controlsEnabled || catalog.offset === 0" @click="loadCatalog(Math.max(0, catalog.offset - catalog.limit))">上一页</button><span>第 {{ Math.floor(catalog.offset / catalog.limit) + 1 }} 页</span><button class="btn" type="button" :disabled="!controlsEnabled || !catalog.hasMore" @click="loadCatalog(catalog.offset + catalog.limit)">下一页</button></div></nav>
        </template>
      </div>
      <p class="library-scope-footnote"><AppIcon name="shield" />展示当前账号已收录的主帖与评论媒体。点击主帖阅读评论，也可跳转 Telegram 原消息。</p>
      </section>
      <section v-if="selectedPostKey" ref="readerPage" class="library-reader-page" tabindex="-1" aria-label="主帖评论阅读">
        <LibraryPostDetail :post-key="selectedPostKey" :active="mode === 'catalog' && canAccess" :authorized="authorized" :available="available" :account-id="accountId" @close="closePost" @update="detailUpdated" @access-error="cause => emit('accessError', cause)" />
      </section>
    </template>
  </div>
</template>

<style scoped>
.library-workspace{--library-ink:var(--ink,#213447);--library-muted:#62778b;--library-border:var(--line,#e3eaf1);min-width:0;color:var(--library-ink)}
.library-workspace .btn{font-size:12px;min-height:36px}
.library-reader-page{width:100%;max-width:1380px;margin:0 auto;outline:0;min-width:0}.library-add-post{margin:0 0 16px;padding:18px;border:1px solid var(--library-border);border-radius:10px;background:#fff}.library-add-post>label{display:block;font-weight:600;font-size:13px;color:var(--library-ink);margin-bottom:10px}.library-add-post>div{display:flex;gap:8px;align-items:center}.library-add-post input{flex:1;min-width:0}.library-add-post p{margin:9px 0 0;color:var(--library-muted);font-size:12px;line-height:1.7}
.library-workspace-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-bottom:12px}
.library-scope-label{display:flex;align-items:center;gap:8px;flex-wrap:wrap;font-size:12px;color:var(--library-muted)}.library-scope-label>.icon{width:16px;height:16px;color:var(--blue,#277fce)}.library-scope-label>small{font-size:12px;padding-left:12px;border-left:1px solid var(--library-border)}
.library-workspace-actions{display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end}
.library-search-panel{display:grid;grid-template-columns:minmax(0,1fr) auto;align-items:center;gap:12px 18px;padding:12px 16px;margin:0 0 14px;border:1px solid #dce8f3;border-radius:11px;background:linear-gradient(115deg,#f0f7ff,#fff 75%);box-shadow:0 3px 14px #254b6e04}
.library-search-main{display:flex;gap:10px}.library-search-main>label{display:flex;align-items:center;gap:12px;min-width:0;flex:1;background:#fff;border:1px solid #ccdbe8;border-radius:8px;padding:0 15px}.library-search-main>label:focus-within{border-color:var(--blue,#277fce);box-shadow:0 0 0 3px #277fce12}.library-search-main>label>.icon{width:20px;height:20px;flex-shrink:0;color:#8298ab}.library-search-main input{font:inherit;font-size:14px;color:var(--library-ink);width:100%;min-width:0;min-height:50px;border:0;outline:0;background:transparent}.library-search-main input::placeholder{color:#8293a2}.library-search-main>.btn{font-size:13px;gap:12px;min-height:50px;padding:0 20px}
.library-search-footer{display:flex;align-items:center;gap:14px;flex-wrap:wrap;min-height:20px;margin-top:13px;font-size:12px;color:var(--library-muted)}.library-advanced-search summary{cursor:pointer;font-size:12px}.library-advanced-search[open]{flex-basis:100%}.library-advanced-search label{display:flex;align-items:center;gap:12px;margin:12px 0 8px;max-width:590px;font-size:12px}.library-advanced-search .input{flex:1;min-width:0;font-size:13px}.library-advanced-search p{font-size:12px;line-height:1.7;margin:0}.library-text-button{border:0;background:transparent;padding:3px 0;color:var(--blue,#277fce);font-size:12px;cursor:pointer;white-space:nowrap}.library-text-button:hover{text-decoration:underline}.library-search-pending{color:#986d2b;font-size:12px}
.library-filter-bar{display:flex;align-items:center;gap:12px;margin:0 0 16px;min-width:0}.library-channel-picker{position:relative;flex-shrink:0;z-index:3}.library-channel-picker>summary{display:flex;align-items:center;gap:8px;cursor:pointer;list-style:none;border:1px solid var(--library-border);border-radius:8px;background:#fff;padding:9px 12px;font-size:13px;min-height:40px;box-sizing:border-box}.library-channel-picker>summary::-webkit-details-marker{display:none}.library-channel-picker>summary>.icon{width:16px;height:16px;color:#7590a7}.library-channel-picker>summary>.icon:last-child{width:12px;height:12px;transform:rotate(90deg)}.library-channel-picker[open]>summary>.icon:last-child{transform:rotate(-90deg)}.library-channel-picker>summary>span{max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.library-channel-picker>summary>small{color:var(--library-muted);font-size:11px}
.library-channels{position:absolute;top:calc(100% + 8px);left:0;width:310px;max-width:calc(100vw - 50px);max-height:420px;overflow:auto;border:1px solid var(--library-border);border-radius:11px;padding:12px;background:#fff;box-shadow:0 12px 36px #223c551a}.library-panel-caption{display:flex;align-items:center;justify-content:space-between;padding:4px 8px 12px;font-size:12px;color:var(--library-muted)}.library-panel-caption strong{font-weight:600;color:var(--library-ink)}.library-channel{display:flex;align-items:center;gap:9px;width:100%;min-height:40px;box-sizing:border-box;text-align:left;border:0;border-radius:7px;background:transparent;padding:8px;margin:2px 0;font-size:12px;color:var(--library-muted);cursor:pointer}.library-channel:hover:enabled{background:#f4f7fa}.library-channel.selected{background:#edf5fe;color:#25669e}.library-channel>.icon{width:17px;height:17px;flex-shrink:0}.library-channel>span:not(.library-channel-avatar){flex:1;min-width:0;white-space:nowrap;text-overflow:ellipsis;overflow:hidden}.library-channel>small{font-size:12px;margin-left:auto}.library-channel-avatar{display:grid;place-items:center;flex-shrink:0;width:25px;height:25px;border-radius:7px;background:#e9eff5;color:#547592;font-size:11px}.library-filter-note{font-size:12px;line-height:1.7;color:var(--library-muted);padding:8px 8px 0;margin:4px 0 0}
.library-filter-summary{display:flex;align-items:center;gap:10px;flex:1;min-width:0;font-size:12px;color:var(--library-muted)}.library-filter-summary strong{font-weight:600;color:var(--library-ink)}.library-filter-summary small{font-size:12px;color:var(--blue,#277fce)}.library-sort select{max-width:160px;min-height:40px;border:1px solid var(--library-border);border-radius:8px;background:#fff;padding:0 10px;font:inherit;font-size:12px;color:var(--library-muted)}
.library-view-switch{display:flex;flex-shrink:0;padding:3px;border:1px solid var(--library-border);border-radius:8px;background:#fff;gap:3px}.library-view-switch>button{display:grid;place-items:center;width:32px;height:32px;border:0;border-radius:5px;background:transparent;color:#718ba1;cursor:pointer}.library-view-switch>button.active{background:#eaf3fc;color:var(--blue,#277fce)}.library-view-switch .icon{width:17px;height:17px}
.library-tag-facets{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin:0 0 22px}.library-tag-facets>span{display:flex;align-items:center;gap:6px;font-size:12px;color:var(--library-muted);margin-right:3px}.library-tag-facets>span>.icon{width:14px;height:14px}.library-tag-chip{display:inline-flex;align-items:center;gap:7px;min-height:30px;border:1px solid #e0e8f0;border-radius:6px;background:#fff;color:#627b91;padding:4px 10px;font-size:12px;cursor:pointer}.library-tag-chip:hover:enabled{background:#f0f6fd;border-color:#bad2e9}.library-tag-chip.selected{background:#eaf3ff;color:#226ab2;border-color:#b8d3ef}.library-tag-chip small{font-size:11px;color:#7c91a3}.library-tag-chip.selected small{color:#4d82b7}.library-tags-and{font-size:12px;color:var(--library-muted)}
.library-catalog-content{min-width:0}.library-posts.cards,.library-skeleton-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:22px}.library-post-card{min-width:0;overflow:hidden;border:1px solid var(--library-border);border-radius:12px;background:#fff;transition:box-shadow .18s,border-color .18s,transform .18s}.library-post-card:hover{border-color:#b8cfe3;box-shadow:0 8px 22px #254b6e0c;transform:translateY(-2px)}.library-post-card.is-selected{border-color:var(--blue,#277fce);box-shadow:0 0 0 2px #277fce17}.library-post-open{display:block;width:100%;height:100%;padding:0;text-align:left;border:0;background:none;cursor:pointer;color:inherit}.library-post-open:focus-visible{outline:3px solid var(--blue,#277fce);outline-offset:-3px;border-radius:11px}
.library-post-cover{position:relative;display:grid;place-items:center;aspect-ratio:16/9;background:linear-gradient(130deg,#e5edf7,#d8e6f2);overflow:hidden}.library-post-cover.photo{background:linear-gradient(130deg,#e5f1eb,#d6e7e0)}.library-post-cover.audio{background:linear-gradient(130deg,#eeebf7,#e1dff0)}.library-post-cover.file{background:linear-gradient(130deg,#f4efe5,#eae1d2)}.library-cover-count{position:absolute;right:10px;bottom:10px;border:1px solid #ffffff60;border-radius:5px;padding:3px 7px;font-size:11px;color:#fff;background:#213e59a3;backdrop-filter:blur(5px);z-index:1}
.library-card-body{padding:16px}.library-card-channel{display:flex;align-items:center;gap:6px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:12px;color:var(--library-muted)}.library-card-channel>.connection-dot{width:5px;height:5px;flex-shrink:0;background:#76a5c9}.library-card-body h2{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere;font-size:14px;font-weight:650;line-height:1.65;min-height:23px;margin:8px 0 6px;color:#243f55}.library-card-excerpt{display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere;font-size:12px;line-height:1.75;color:#6b8296;margin:0}.library-card-tags{display:flex;align-items:center;flex-wrap:wrap;gap:5px;margin-top:11px}.library-card-tags>span{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border-radius:4px;background:#f0f5fa;padding:2px 6px;color:#65829c;font-size:11px;line-height:1.6}.library-card-tags>small{color:#7990a3;font-size:11px}
.library-card-media-count{display:flex;flex-wrap:wrap;gap:7px 11px;font-size:12px;line-height:1.7;color:#657f94;min-height:20px;margin-top:12px}.library-card-media-count>span{display:inline-flex;align-items:center;gap:5px}.library-card-media-count .icon{width:14px;height:14px;color:#819aaf}.library-card-footer{display:flex;align-items:center;justify-content:space-between;gap:8px;flex-wrap:wrap;border-top:1px solid #edf1f5;padding-top:11px;margin-top:13px;font-size:12px;line-height:1.7;color:#70869b}.library-card-footer time{font-size:12px;margin-left:auto;color:#6d8296}.library-coverage-text{font-size:11px;color:#946f34}.library-coverage-text.complete{color:#34785b}.library-coverage-text.unavailable{color:#a45f51}
.library-posts.list{display:flex;flex-direction:column;gap:12px}.library-posts.list .library-post-open{display:flex;align-items:stretch;padding:13px;gap:18px}.library-posts.list .library-post-cover{width:180px;min-height:125px;aspect-ratio:auto;border-radius:8px;flex-shrink:0}.library-posts.list .library-card-body{padding:0;flex:1;min-width:0}.library-posts.list .library-card-body h2{margin-top:5px}.library-posts.list .library-card-excerpt{-webkit-line-clamp:1}.library-posts.list .library-card-tags{margin-top:7px}.library-posts.list .library-card-media-count{margin-top:8px}.library-posts.list .library-card-footer{margin-top:8px;padding-top:8px}
.library-catalog-pagination{display:flex;align-items:center;justify-content:space-between;gap:16px;padding-top:24px;font-size:12px;color:var(--library-muted)}.library-catalog-pagination>div{display:flex;align-items:center;gap:14px}.library-catalog-pagination .btn{min-height:34px}
.library-state{display:flex;align-items:center;flex-direction:column;text-align:center;padding:52px 22px;border:1px dashed #cfdde8;border-radius:12px;background:#fff}.library-state-icon{display:grid;place-items:center;width:50px;height:50px;border-radius:14px;background:#eff5fb;color:#7095b5;margin-bottom:16px}.library-state-icon>.icon{width:25px;height:25px}.library-state h2{font-size:17px;font-weight:600;color:#36566f;margin:0 0 8px}.library-state p{max-width:420px;margin:0 0 20px;font-size:13px;color:var(--library-muted);line-height:1.8}
.library-error{font-size:13px;align-items:flex-start}.library-error>div{min-width:0;flex:1}.library-error strong{font-weight:600}.library-error p{line-height:1.7;margin:4px 0;overflow-wrap:anywhere}.library-error small{font-size:12px}.library-error>.btn{flex-shrink:0}
.library-skeleton-card{border:1px solid var(--library-border);border-radius:12px;background:#fff;padding-bottom:20px;overflow:hidden}.library-skeleton-card>div{aspect-ratio:16/9;background:linear-gradient(100deg,#e7eff6 20%,#f3f7fa 50%,#e7eff6 80%);background-size:200% 100%;animation:library-shimmer 1.7s ease-in-out infinite}.library-skeleton-card>span{display:block;height:12px;margin:18px 16px 0;background:#edf2f7;border-radius:3px;width:72%}.library-skeleton-card>span:last-child{width:45%;margin-top:11px}
.library-scope-footnote{display:flex;align-items:flex-start;gap:7px;font-size:12px;color:#748a9c;line-height:1.8;margin-top:24px}.library-scope-footnote>.icon{width:14px;height:14px;flex-shrink:0;margin-top:4px}.library-mode-toolbar{display:flex;align-items:center;gap:14px;flex-wrap:wrap;margin-bottom:22px;font-size:12px;color:var(--library-muted)}
.visually-hidden{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}
@keyframes library-shimmer{0%{background-position:100% 0}100%{background-position:-100% 0}}
@media(min-width:1600px){.library-posts.cards,.library-skeleton-grid{grid-template-columns:repeat(4,minmax(0,1fr))}}
@media(max-width:1180px){.library-posts.cards,.library-skeleton-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.library-workspace-toolbar{align-items:flex-start}.library-scope-label>small{padding-left:0;border:0;flex-basis:100%}.library-filter-bar{flex-wrap:wrap}.library-filter-summary{min-width:100px}}
@media(max-width:760px){.library-search-panel{padding:20px;margin-bottom:20px}.library-workspace-toolbar{flex-direction:column;gap:12px}.library-workspace-actions{align-self:flex-end}.library-search-heading h2{font-size:18px}.library-posts.list .library-post-cover{width:130px}.library-filter-summary{margin-right:auto}.library-channel-picker>summary>small{display:none}}
@media(max-width:520px){.library-add-post>div{flex-wrap:wrap}.library-add-post input{flex-basis:100%}.library-add-post .btn{flex:1}.library-posts.cards,.library-skeleton-grid{grid-template-columns:minmax(0,1fr);gap:16px}.library-workspace .btn{font-size:12px;padding:7px 10px}.library-workspace-actions{align-self:stretch;gap:7px}.library-workspace-actions>.btn{flex:1;white-space:nowrap;justify-content:center}.library-workspace-actions>.btn>.icon{display:none}.library-search-panel{padding:18px 16px}.library-search-main{flex-direction:column}.library-search-main input{font-size:13px;min-height:46px}.library-search-main>.btn{min-height:40px;justify-content:center}.library-search-footer{gap:10px}.library-advanced-search label{align-items:stretch;flex-direction:column;gap:6px}.library-filter-bar{gap:8px}.library-channel-picker{flex:1;min-width:0}.library-channel-picker>summary>span{max-width:145px}.library-filter-summary{flex-basis:100%;order:3;margin-top:3px}.library-sort select{max-width:135px}.library-channel-picker>summary{font-size:12px;padding:9px}.library-view-switch>button{width:28px}.library-posts.list .library-post-open{padding:11px;gap:12px}.library-posts.list .library-post-cover{width:90px;min-height:120px}.library-posts.list .library-card-tags{display:none}.library-posts.list .library-cover-count{right:4px;bottom:6px;padding:2px 4px;font-size:10px}.library-card-footer time{margin-left:0}.library-catalog-pagination{flex-direction:column;align-items:flex-start;gap:12px}.library-catalog-pagination>div{align-self:flex-end}.library-state{padding:38px 18px}.library-error{flex-wrap:wrap}.library-error>.btn{margin-left:auto}.library-tag-facets{gap:6px}.library-tag-chip{padding:4px 8px}}
@media(prefers-reduced-motion:reduce){.library-skeleton-card>div{animation:none}.library-post-card{transition:none}.library-post-card:hover{transform:none}}
.library-search-footer{margin-top:0;max-width:220px;gap:8px 12px}.library-search-footer:has(.library-advanced-search[open]){grid-column:1/-1;max-width:none}.library-advanced-search summary{white-space:nowrap}.library-filter-bar{margin-bottom:10px}.library-tag-facets{margin-bottom:14px;gap:6px 8px}
@media(max-width:760px){.library-search-panel{display:block;padding:12px 14px;margin-bottom:14px}.library-search-footer{max-width:none;margin-top:8px}.library-workspace-toolbar{gap:10px;margin-bottom:12px}}
</style>
