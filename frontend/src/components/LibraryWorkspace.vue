<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { coverageLabel, type CatalogItem, type CatalogPage, type LibraryChannel } from '../library'
import { postCardContent } from '../postCardContent'
import AppIcon from './AppIcon.vue'
import LibraryProbe from './LibraryProbe.vue'
import LibraryPostDetail from './LibraryPostDetail.vue'
import LibraryPostPreview from './LibraryPostPreview.vue'
import LibraryChannelImport from './LibraryChannelImport.vue'

const props = defineProps<{ authorized: boolean; available: boolean; accountId: string | null }>()
const emit = defineEmits<{ settings: []; accessError: [cause: ApiError] }>()
const mode = ref<'catalog' | 'probe' | 'import'>('catalog')
const probeKey = ref<string | null>(null)
const channels = ref<LibraryChannel[]>([])
const selectedChannels = ref<string[]>([])
const tagsInput = ref('')
const keywordsInput = ref('')
const appliedTags = ref('')
const appliedKeywords = ref('')
const view = ref<'cards' | 'list'>('cards')
const catalog = ref<CatalogPage | null>(null)
const catalogCards = computed(() => catalog.value?.items.map(item => ({ item, content: postCardContent(item.post.title, item.post.text, item.tags) })) ?? [])
const selectedPostKey = ref('')
const offset = ref(0)
const busy = ref('')
const error = ref('')
const catalogFailed = ref(false)
const canAccess = computed(() => props.authorized && props.available)
const controlsEnabled = computed(() => canAccess.value && !busy.value)
const hasSearch = computed(() => !!appliedTags.value || !!appliedKeywords.value)
const searchPending = computed(() => tagsInput.value.trim() !== appliedTags.value || keywordsInput.value.trim() !== appliedKeywords.value)
const selectedChannelLabel = computed(() => selectedChannels.value.length ? `已选 ${selectedChannels.value.length} 个频道` : '全部已索引频道')
const matchedRange = computed(() => catalog.value?.items.length ? `${catalog.value.offset + 1}–${catalog.value.offset + catalog.value.items.length}` : '0')
let generation = 0
let disposed = false

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
  const query = new URLSearchParams({ tags: appliedTags.value, keywords: appliedKeywords.value, limit: '20', offset: String(targetOffset) })
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
function filterChanged() {
  selectedPostKey.value = ''; catalog.value = null; offset.value = 0
  return loadCatalog(0)
}
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
function clearSearch() {
  if (!controlsEnabled.value) return
  tagsInput.value = ''; keywordsInput.value = ''; appliedTags.value = ''; appliedKeywords.value = ''
  void filterChanged()
}
function openPost(key: string) { if (controlsEnabled.value) selectedPostKey.value = key }
function showCatalogView(nextView: 'cards' | 'list') {
  view.value = nextView
  selectedPostKey.value = ''
}
function openProbe(key: string | null = null) { probeKey.value = key; selectedPostKey.value = ''; mode.value = 'probe' }
function openImport() { if (canAccess.value) { selectedPostKey.value = ''; mode.value = 'import' } }
function coverIcon(item: CatalogItem) {
  const type = item.post.mainMedia[0]?.type
  return type === 'PHOTO' ? 'photo' : type === 'VIDEO' ? 'video' : type === 'AUDIO' ? 'audio' : type === 'DOCUMENT' ? 'file' : 'library'
}
function updatedLabel(timestamp: number) {
  if (!Number.isFinite(timestamp) || timestamp <= 0) return '未提供更新时间'
  return new Date(timestamp).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false })
}

watch([() => props.authorized, () => props.available, () => props.accountId, mode], (values, previous) => {
  const accountChanged = !!previous && values[2] !== previous[2]
  generation++; busy.value = ''
  if (!props.authorized || accountChanged) {
    channels.value = []; catalog.value = null; selectedChannels.value = []; selectedPostKey.value = ''
    tagsInput.value = ''; keywordsInput.value = ''; appliedTags.value = ''; appliedKeywords.value = ''; offset.value = 0; error.value = ''; catalogFailed.value = false; probeKey.value = null
    if (accountChanged) mode.value = 'catalog'
  }
  if (canAccess.value && mode.value === 'catalog') void refreshCatalog()
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++ })
</script>

<template>
  <div class="library-workspace">
    <template v-if="mode === 'probe'">
      <div class="library-mode-toolbar"><button class="btn" type="button" @click="mode = 'catalog'"><AppIcon name="library" />返回我的资源库</button><span>主帖读取与远端更新</span></div>
      <LibraryProbe :authorized="authorized" :available="available" :account-id="accountId" :initial-post-key="probeKey" @settings="emit('settings')" @access-error="cause => emit('accessError', cause)" />
    </template>
    <template v-else-if="mode === 'import'">
      <div class="library-mode-toolbar"><button class="btn" type="button" @click="mode = 'catalog'"><AppIcon name="library" />返回我的资源库</button><span>近期主帖小批导入</span></div>
      <LibraryChannelImport :authorized="authorized" :available="available" :account-id="accountId" @settings="emit('settings')" @access-error="cause => emit('accessError', cause)" />
    </template>
    <template v-else>
      <div class="library-workspace-toolbar"><div class="library-scope-label"><AppIcon name="server" /><span>仅浏览本机已索引范围</span><small v-if="catalog">{{ catalog.indexedChannels }} 个频道 · {{ catalog.indexedPosts }} 条主帖</small></div><div class="library-workspace-actions"><button class="btn" type="button" :disabled="!controlsEnabled" title="重新显示本地已保存的频道与主帖，不扫描 Telegram 新内容" @click="refreshCatalog"><AppIcon name="refresh" />刷新资源库</button><button class="btn" type="button" :disabled="!canAccess" @click="openImport"><AppIcon name="library" />导入频道</button><button class="btn primary" type="button" :disabled="!canAccess" @click="openProbe()"><AppIcon name="plus" />添加 / 更新主帖</button></div></div>
      <div v-if="!authorized" class="notice warning" role="status"><AppIcon name="lock" /><div><strong>先连接你的 Telegram 账号</strong><p>完成授权后，浏览当前账号已保存的频道与主帖。</p></div><button class="btn" type="button" @click="emit('settings')">前往设置</button></div>
      <div v-else-if="!available" class="notice warning" role="alert"><AppIcon name="info" /><span>本机服务暂不可用，读取已暂停。请在设置中检查后台连接状态。</span></div>
      <div v-if="error" class="notice danger" role="alert"><AppIcon name="info" /><span>{{ error }}</span><button class="icon-btn dismiss" type="button" aria-label="关闭资源库错误提示" @click="error = ''"><AppIcon name="close" /></button></div>
      <div v-if="catalogFailed && catalog" class="notice warning" role="status"><AppIcon name="info" /><span>当前显示上次成功读取的索引结果，本次刷新未完成。</span></div>
      <div class="library-catalog-layout">
        <aside class="library-channels" aria-label="自己的频道筛选"><div class="library-panel-caption"><span>自己的频道</span><small>{{ channels.length }}</small></div><button type="button" :class="['library-channel', 'library-all-channel', { selected: !selectedChannels.length }]" :disabled="!controlsEnabled" :aria-pressed="!selectedChannels.length" @click="allChannels"><AppIcon name="library" /><span>全部频道</span><small>{{ catalog?.indexedPosts ?? '—' }}</small></button><div class="library-channel-divider" /><button v-for="channel in channels" :key="channel.id" type="button" :class="['library-channel', { selected: selectedChannels.includes(channel.id) }]" :disabled="!controlsEnabled" :aria-pressed="selectedChannels.includes(channel.id)" @click="toggleChannel(channel.id)"><span class="library-channel-avatar">{{ (channel.title || '频').slice(0, 1) }}</span><span :title="channel.title">{{ channel.title || '未命名频道' }}</span><small>{{ channel.indexedPosts }}</small></button><p v-if="!channels.length" class="library-channel-empty">{{ canAccess ? busy ? '正在读取频道索引…' : '尚无已索引频道。添加自己频道的主帖后会显示在这里。' : '完成授权后读取自己的频道索引。' }}</p><div class="library-index-note"><AppIcon name="info" /><strong>点击频道可直接并选</strong><p>再次点击取消，全部频道清除筛选。仅显示已添加主帖所在频道，不代表已经扫描整个频道。</p></div></aside>
        <div class="library-catalog-content">
          <form class="library-search-panel" @submit.prevent="search"><div class="library-search-fields"><label for="catalog-tags"><span><AppIcon name="tag" />主帖标签 · 全部满足</span><input id="catalog-tags" v-model="tagsInput" class="input" :disabled="!canAccess" placeholder="例如：北京 旅行" autocomplete="off" /></label><label for="catalog-keywords"><span><AppIcon name="search" />主帖关键词 · 全部包含</span><input id="catalog-keywords" v-model="keywordsInput" class="input" :disabled="!canAccess" placeholder="例如：周末 记录" autocomplete="off" /></label><button class="btn primary" type="submit" :disabled="!controlsEnabled"><AppIcon name="search" />搜索主帖</button></div><div class="library-search-explanation"><span>用空格或逗号分隔。标签与关键词同时填写时也全部满足；仅匹配主帖文字。</span><button v-if="hasSearch || tagsInput || keywordsInput" class="library-text-button" type="button" :disabled="!controlsEnabled" @click="clearSearch">清空检索</button></div></form>
          <div class="library-result-bar"><div><strong>{{ selectedChannelLabel }}</strong><span v-if="catalog">{{ catalog.total }} 条匹配主帖</span><small v-if="searchPending">输入已修改，点击搜索生效</small></div><div class="library-view-switch" aria-label="资源展示方式"><button type="button" :class="{ active: view === 'cards' }" :aria-pressed="view === 'cards'" aria-label="卡片视图" :title="selectedPostKey ? '返回目录并以卡片显示' : '以卡片显示资源目录'" @click="showCatalogView('cards')"><AppIcon name="library" /><span>卡片</span></button><button type="button" :class="{ active: view === 'list' }" :aria-pressed="view === 'list'" aria-label="目录视图" :title="selectedPostKey ? '返回目录并以列表显示' : '以列表显示资源目录'" @click="showCatalogView('list')"><AppIcon name="list" /><span>列表</span></button></div></div>

          <LibraryPostDetail v-if="selectedPostKey" :post-key="selectedPostKey" :authorized="authorized" :available="available" :account-id="accountId" @close="selectedPostKey = ''" @access-error="cause => emit('accessError', cause)" />
          <div v-else-if="busy" class="library-loading" role="status"><span class="spinner" /><span>正在读取本机主帖索引…</span></div>
          <div v-else-if="catalogFailed && !catalog && canAccess" class="library-catalog-empty"><span class="empty-state-icon"><AppIcon name="info" /></span><h2>本次筛选未完成</h2><p>尚未取得当前条件的匹配结果。可调整标签、关键词或频道后重新搜索，也可以重试读取当前条件。</p><button class="btn primary" type="button" @click="refreshCatalog"><AppIcon name="refresh" />重试读取当前条件</button></div>
          <div v-else-if="!catalog || !catalog.items.length" class="library-catalog-empty"><span class="empty-state-icon"><AppIcon :name="hasSearch || selectedChannels.length ? 'search' : 'library'" /></span><h2>{{ !canAccess ? '连接后浏览自己的资源' : hasSearch || selectedChannels.length ? '当前条件没有匹配的已索引主帖' : '从一个自己的频道主帖开始' }}</h2><p>{{ hasSearch || selectedChannels.length ? '可调整标签、关键词或左侧频道。尚未添加的主帖不在本次搜索范围内。' : '添加主帖后，在这里按频道浏览主帖文字及相关媒体。当前不会自动扫描整个频道。' }}</p><button v-if="canAccess && !hasSearch && !selectedChannels.length" class="btn primary" type="button" @click="openProbe()"><AppIcon name="plus" />添加第一个主帖</button></div>
          <template v-else>
            <div :class="['library-posts', view]">
              <article v-for="{ item, content } in catalogCards" :key="item.post.key" class="library-post-card"><button class="library-post-open" type="button" :disabled="!controlsEnabled" @click="openPost(item.post.key)"><div :class="['library-post-cover', coverIcon(item)]"><LibraryPostPreview :post-key="item.post.key" :account-id="accountId" :version="item.updatedAt" :authorized="authorized" :available="available" :icon="coverIcon(item)" /><span class="library-cover-count">{{ item.post.mainMedia.length + item.indexedCommentMedia }} 个媒体</span></div><div class="library-card-body"><small class="library-card-channel"><span class="connection-dot" />{{ item.post.channelTitle || '未命名频道' }}</small><h2>{{ content.title }}</h2><p v-if="content.excerpt || !item.post.text" class="library-card-excerpt">{{ content.excerpt || '此主帖没有文字说明' }}</p><div v-if="content.tags.length || !content.hasSourceTags" class="library-card-tags"><span v-for="tag in content.tags" :key="tag">{{ tag }}</span><span v-if="!content.hasSourceTags" class="no-tags">无主帖标签</span></div><div class="library-card-media-count"><span>主帖媒体 {{ item.post.mainMedia.length }}</span><span>评论媒体 {{ item.indexedCommentMedia }}</span><span>评论消息 {{ item.indexedCommentMessages }}</span></div><div class="library-card-footer"><span :class="['library-coverage-text', item.post.coverage.toLowerCase()]">{{ coverageLabel(item.post.coverage) }}</span><time>{{ updatedLabel(item.updatedAt) }}</time><AppIcon name="chevron" /></div></div></button></article>
            </div>
            <div class="library-catalog-pagination"><span>已索引匹配结果 {{ matchedRange }} / {{ catalog.total }}</span><div><button class="btn" type="button" :disabled="!controlsEnabled || catalog.offset === 0" @click="loadCatalog(Math.max(0, catalog.offset - catalog.limit))">上一页</button><span>第 {{ Math.floor(catalog.offset / catalog.limit) + 1 }} 页</span><button class="btn" type="button" :disabled="!controlsEnabled || !catalog.hasMore" @click="loadCatalog(catalog.offset + catalog.limit)">下一页</button></div></div>
          </template>
          <p class="library-scope-footnote"><AppIcon name="shield" />此处浏览当前账号的已索引记录。新增或编辑内容可通过“导入频道”或“添加 / 更新主帖”读取核对，结果范围以主帖覆盖状态与警告为准。</p>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.library-workspace-toolbar{display:flex;align-items:center;justify-content:space-between;gap:15px;margin-bottom:25px}.library-scope-label{display:flex;align-items:center;gap:7px;font-size:10px;color:#8399aa;flex-wrap:wrap}.library-scope-label>.icon{width:14px;height:14px;color:#88a3b8}.library-scope-label>small{font-size:9px;color:#a2b0bb;border-left:1px solid #dde5ed;padding-left:9px;margin-left:3px}.library-workspace-actions{display:flex;gap:8px}.library-workspace-actions>.btn{font-size:10px}.library-catalog-layout{display:grid;grid-template-columns:185px minmax(0,1fr);gap:25px;align-items:start}.library-channels{min-width:0}.library-panel-caption{display:flex;justify-content:space-between;align-items:center;color:#8e9faa;font-size:10px;padding:2px 9px 13px;letter-spacing:1px}.library-panel-caption>small{font-size:9px;color:#a7b4bd;letter-spacing:0}.library-channel{display:flex;align-items:center;gap:9px;background:none;border:0;border-radius:7px;width:100%;padding:10px 9px;text-align:left;margin-bottom:5px;color:#7f94a5;min-height:43px}.library-channel:not(.selected):hover:enabled{background:#edf2f6}.library-channel.selected{background:#2b6eaa;color:#fff;box-shadow:0 2px 4px #254b6e0b}.library-channel>span:not(.library-channel-avatar){flex:1;min-width:0;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;font-size:11px}.library-channel>small{font-size:9px;color:#a2b2bd}.library-channel.selected>small{color:#e0ebf4}.library-channel-avatar{display:grid;place-items:center;width:26px;height:27px;background:#e5eef5;border-radius:7px;color:#7596af;font-size:10px;flex-shrink:0}.library-channel.selected>.library-channel-avatar{background:#ffffff1c;color:#eef6fd}.library-all-channel>.icon{width:16px;height:16px;margin:0 5px;color:#7394af}.library-all-channel.selected>.icon{color:#fff}.library-channel-divider{height:1px;background:#e4ebf1;margin:13px 9px}.library-channel-empty{color:#a6b4bf;font-size:10px;line-height:1.9;padding:6px 9px 12px}.library-index-note{font-size:9px;color:#a2b2be;line-height:1.9;border-top:1px solid #e3eaf0;padding-top:17px;margin:25px 9px}.library-index-note>.icon{display:inline-block;vertical-align:middle;width:12px;height:12px;margin-right:5px}.library-index-note>strong{font-size:10px;color:#879ead;font-weight:500}.library-index-note>p{margin-top:5px}.library-catalog-content{min-width:0}.library-search-panel{border:1px solid #e0e8ef;background:#fff;border-radius:8px;padding:15px 16px 12px;margin-bottom:16px}.library-search-fields{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1.2fr) auto;gap:12px;align-items:end}.library-search-fields label{display:block;min-width:0}.library-search-fields label>span{display:flex;align-items:center;gap:5px;font-size:10px;color:#8197a9;margin-bottom:7px}.library-search-fields label .icon{width:12px;height:12px;color:#95afc2}.library-search-fields .input{font-size:11px;min-height:37px}.library-search-fields>.btn{min-height:37px;font-size:10px}.library-search-explanation{display:flex;justify-content:space-between;align-items:center;gap:10px;font-size:9px;color:#a0afbb;margin-top:9px;line-height:1.9}.library-text-button{border:0;background:none;color:#699cc4;font-size:9px;white-space:nowrap;padding:0}.library-result-bar{display:flex;justify-content:space-between;align-items:center;gap:12px;margin-bottom:17px;font-size:10px;color:#98a9b6}.library-result-bar>div:first-child{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.library-result-bar strong{font-weight:550;color:#788fa2;font-size:10px}.library-result-bar small{font-size:9px;color:#b19467}.library-view-switch{display:flex;padding:2px;background:#fff;border:1px solid #e0e8ef;border-radius:5px;gap:2px;flex-shrink:0}.library-view-switch>button{display:grid;place-items:center;background:transparent;border:0;width:28px;height:26px;border-radius:3px;color:#a0b0bd}.library-view-switch>button.active{background:#eaf3fb;color:#5c95c4}.library-view-switch .icon{width:14px;height:14px}.library-posts.cards{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:17px}.library-post-card{min-width:0;border:1px solid #e1e9f0;background:#fff;border-radius:9px;overflow:hidden;transition:box-shadow .17s,border-color .17s,transform .17s}.library-post-card:hover{border-color:#bfd2e2;box-shadow:0 8px 23px #264f7010;transform:translateY(-2px)}.library-post-open{display:block;text-align:left;width:100%;padding:0;border:0;background:none}.library-post-cover{height:122px;background:linear-gradient(130deg,#e3edf4,#d6e4ef);position:relative;display:grid;place-items:center;color:#8daec7;overflow:hidden}.library-post-cover:after{content:'';position:absolute;width:135px;height:135px;border:1px solid #ffffff4a;transform:rotate(30deg);right:-25px;top:-45px;border-radius:27px}.library-post-cover>.icon{width:38px;height:38px;stroke-width:1.15;color:#91b0c7}.library-post-cover.photo{background:linear-gradient(130deg,#e6efe9,#d9e7e0)}.library-post-cover.photo>.icon{color:#95b4a5}.library-post-cover.audio{background:linear-gradient(130deg,#ede9f3,#e1deeb)}.library-post-cover.audio>.icon{color:#afa3c2}.library-post-cover.file{background:linear-gradient(130deg,#f2ece0,#ebe1d0)}.library-post-cover.file>.icon{color:#bca984}.library-cover-caption{position:absolute;left:13px;top:11px;font-size:8px;color:#86a3b8;letter-spacing:1px}.library-cover-count{position:absolute;right:10px;bottom:10px;border:1px solid #ffffff99;border-radius:4px;padding:2px 6px;font-size:8px;color:#7294ae;background:#ffffff52;z-index:1}.library-card-body{padding:13px 14px 12px}.library-card-channel{display:flex;gap:5px;align-items:center;color:#a0b0bd;font-size:9px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.library-card-channel>.connection-dot{width:4px;height:4px;background:#9db6c9}.library-card-body h2{font-size:13px;font-weight:550;margin:7px 0 5px;color:#49677e;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;line-height:1.8}.library-card-excerpt{font-size:10px;color:#a0afbb;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;min-height:16px}.library-card-tags{display:flex;gap:4px;overflow:hidden;min-height:20px;margin-top:10px}.library-card-tags>span{display:inline-block;border:1px solid #e9eef3;border-radius:4px;background:#f7f9fc;padding:1px 5px;color:#8da2b3;font-size:8px;white-space:nowrap}.library-card-tags>.no-tags{border-color:transparent;background:transparent;color:#b6c2cb;padding-left:0}.library-card-media-count{display:flex;flex-wrap:wrap;gap:4px 8px;margin-top:8px;color:#a5b3be;font-size:8px}.library-card-footer{display:flex;align-items:center;gap:7px;justify-content:space-between;font-size:8px;color:#a9b6c0;border-top:1px solid #eff3f6;padding-top:10px;margin-top:11px}.library-card-footer>time{font-size:7px;margin-left:auto;color:#b4c0c9;white-space:nowrap}.library-card-footer>.icon{width:12px;height:12px;color:#acbdca}.library-coverage-text{font-size:8px;color:#b09a77;white-space:nowrap}.library-coverage-text.complete{color:#7f9e8c}.library-coverage-text.unavailable{color:#b3948e}.library-posts.list{display:flex;flex-direction:column;gap:10px}.library-posts.list .library-post-open{display:flex;align-items:center;padding:11px;gap:15px}.library-posts.list .library-post-cover{width:104px;height:96px;border-radius:6px;flex-shrink:0}.library-posts.list .library-post-cover>.icon{width:30px;height:30px}.library-posts.list .library-cover-caption{display:none}.library-posts.list .library-cover-count{font-size:7px;bottom:6px;right:6px;padding:1px 4px}.library-posts.list .library-card-body{padding:0;flex:1;min-width:0}.library-posts.list .library-card-body h2{margin:3px 0}.library-posts.list .library-card-tags{margin-top:7px}.library-posts.list .library-card-media-count{margin-top:5px}.library-posts.list .library-card-footer{margin-top:7px;padding-top:7px}.library-catalog-pagination{display:flex;align-items:center;justify-content:space-between;gap:15px;padding-top:22px;font-size:9px;color:#a0b1be}.library-catalog-pagination>div{display:flex;align-items:center;gap:12px}.library-catalog-pagination .btn{font-size:9px;min-height:31px;padding:5px 10px}.library-loading{background:#fff;border:1px solid #e3ebf2;border-radius:8px;padding:75px 20px;display:flex;align-items:center;justify-content:center;gap:12px;font-size:11px;color:#93a9ba}.library-catalog-empty{text-align:center;border:1px dashed #d8e4ee;background:#fff;border-radius:9px;padding:60px 25px}.library-catalog-empty h2{font-size:16px;font-weight:550;color:#718da2;margin:0 0 9px}.library-catalog-empty p{font-size:11px;color:#9aafbe;line-height:1.9;max-width:400px;margin:0 auto 20px}.library-scope-footnote{display:flex;align-items:flex-start;gap:6px;font-size:9px;color:#adbac5;line-height:1.9;margin-top:20px}.library-scope-footnote>.icon{width:13px;height:13px;margin-top:2px}.library-mode-toolbar{display:flex;align-items:center;gap:14px;margin-bottom:22px}.library-mode-toolbar>span{font-size:10px;color:#a0b1be}.library-mode-toolbar>.btn{font-size:10px}.library-workspace>.notice>.btn{margin-left:auto}.library-workspace>.notice p{font-size:10px;margin-top:3px}.library-workspace>.notice strong{font-weight:550}
@media(min-width:1600px){.library-posts.cards{gap:21px}.library-post-cover{height:145px}}
@media(max-width:1250px){.library-catalog-layout{grid-template-columns:165px minmax(0,1fr);gap:20px}.library-posts.cards{grid-template-columns:repeat(2,minmax(0,1fr))}.library-scope-label>small{display:none}.library-search-fields{grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:10px}.library-search-fields>.btn{grid-column:2;justify-self:end;font-size:10px;min-height:32px}.library-search-fields .input{min-height:37px}.library-search-explanation{font-size:8px;margin-top:8px}.library-workspace-toolbar{gap:12px}.library-workspace-actions>.btn{font-size:9px;padding:7px 9px}}
@media(max-width:900px){.library-catalog-layout{grid-template-columns:145px minmax(0,1fr);gap:17px}.library-workspace-toolbar{align-items:flex-start;flex-direction:column;gap:12px}.library-workspace-actions{align-self:flex-end}.library-posts.cards{grid-template-columns:minmax(0,1fr)}.library-post-cover{height:128px}.library-channel{gap:6px;padding:9px 7px}.library-channel>span:not(.library-channel-avatar){font-size:10px}.library-channel-avatar{width:23px;height:25px;font-size:9px}.library-search-fields{grid-template-columns:minmax(0,1fr)}.library-search-fields>.btn{grid-column:1}.library-result-bar>div:first-child{gap:5px 9px}.library-result-bar{align-items:flex-start}.library-catalog-pagination{align-items:flex-start;flex-direction:column;gap:12px}.library-catalog-pagination>div{align-self:flex-end}.library-posts.list .library-post-open{gap:10px}.library-posts.list .library-post-cover{width:75px;height:95px}.library-posts.list .library-card-media-count{font-size:7px}}
@media(max-width:680px){.library-workspace-toolbar{margin-bottom:18px}.library-workspace-actions{align-self:stretch;gap:7px;justify-content:flex-end}.library-workspace-actions>.btn{font-size:9px;padding:6px 9px;min-height:32px}.library-workspace-actions>.btn>.icon{width:12px;height:12px}.library-scope-label{font-size:9px}.library-catalog-layout{grid-template-columns:minmax(0,1fr);gap:13px}.library-panel-caption{font-size:9px;padding:0 2px 9px}.library-channels{display:flex;flex-wrap:wrap;gap:6px}.library-panel-caption{width:100%}.library-channel{width:auto;min-height:32px;max-width:100%;padding:6px 9px;border:1px solid #e1e9f0;background:#f9fbfd;border-radius:5px;margin:0;gap:6px}.library-channel.selected{border-color:#2b6eaa}.library-channel>span:not(.library-channel-avatar){max-width:125px;font-size:9px;flex:none}.library-channel-avatar{display:none}.library-channel>small{font-size:8px}.library-all-channel>.icon{width:12px;height:12px;margin:0}.library-channel-divider{display:none}.library-index-note{width:100%;margin:7px 2px 0;padding:0;border:0;font-size:8px}.library-index-note>strong{font-size:8px}.library-index-note>p{display:none}.library-channel-empty{font-size:9px;padding:0 2px}.library-search-panel{padding:12px;margin-bottom:13px}.library-search-fields label>span{font-size:9px}.library-search-fields{gap:11px}.library-search-fields .input{font-size:10px;min-height:35px}.library-search-explanation{align-items:flex-start;font-size:8px}.library-search-fields>.btn{min-height:30px;font-size:9px}.library-result-bar strong{font-size:9px}.library-result-bar{font-size:9px;margin-bottom:13px}.library-result-bar small{font-size:8px}.library-view-switch>button{width:24px;height:23px}.library-view-switch .icon{width:12px;height:12px}.library-posts.cards{gap:13px}.library-card-body h2{font-size:12px}.library-post-cover{height:121px}.library-card-excerpt{font-size:9px}.library-card-channel{font-size:8px}.library-card-tags>span{font-size:8px}.library-card-media-count{font-size:8px}.library-card-footer>time{font-size:7px}.library-card-body{padding:12px}.library-posts.list .library-post-cover{width:58px;height:87px}.library-posts.list .library-post-cover>.icon{width:24px;height:24px}.library-posts.list .library-cover-count{display:none}.library-posts.list .library-card-body h2{font-size:11px}.library-posts.list .library-card-tags{display:none}.library-posts.list .library-card-excerpt{font-size:8px}.library-posts.list .library-card-footer{font-size:7px}.library-catalog-empty{padding:43px 20px}.library-catalog-empty h2{font-size:14px}.library-catalog-empty p{font-size:10px}.library-scope-footnote{font-size:8px;margin-top:15px}.library-mode-toolbar{gap:10px;flex-wrap:wrap}.library-mode-toolbar>.btn{font-size:9px}.library-mode-toolbar>span{font-size:9px}}
.library-workspace-actions{flex-wrap:wrap;justify-content:flex-end}.library-view-switch>button{display:inline-flex;align-items:center;justify-content:center;width:auto;gap:5px;padding:0 9px;font-size:10px}.library-posts.list .library-cover-caption{display:block;left:6px;right:6px;top:7px;text-align:center;font-size:7px;letter-spacing:0;line-height:1.5}
@media(max-width:680px){.library-view-switch>button{width:auto;padding:0 7px;font-size:9px}.library-posts.list .library-cover-caption{left:2px;right:2px;font-size:6px}}
.library-post-cover:after{display:none}.library-posts.list :deep(.post-preview>.icon){width:30px;height:30px}.library-posts.list :deep(.post-preview-caption){left:5px;top:7px;font-size:6px;letter-spacing:0}.library-posts.list :deep(.post-preview.has-preview>.post-preview-caption){padding:1px 3px}
@media(max-width:680px){.library-posts.list :deep(.post-preview>.icon){width:24px;height:24px}.library-posts.list :deep(.post-preview-caption){left:3px;right:3px;text-align:center;font-size:6px}}
</style>
