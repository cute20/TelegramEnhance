<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { formatMediaBytes } from '../library'
import AppIcon from './AppIcon.vue'

interface CacheView { directory: string; defaultDirectory: string; files: number; bytes: number; maximumBytes: number; warning: string | null }
const props = defineProps<{ available: boolean }>()
const emit = defineEmits<{ accessError: [cause: ApiError] }>()
const saved = ref<CacheView | null>(null), directory = ref(''), busy = ref(''), error = ref(''), notice = ref(''), uncertain = ref(false)
let generation = 0, disposed = false
function valid(value: CacheView) {
  return value && typeof value.directory === 'string' && !!value.directory && typeof value.defaultDirectory === 'string'
    && Number.isSafeInteger(value.files) && value.files >= 0 && Number.isSafeInteger(value.bytes) && value.bytes >= 0
    && Number.isSafeInteger(value.maximumBytes) && value.maximumBytes > 0 && (value.warning == null || typeof value.warning === 'string')
}
async function perform(action: 'load' | 'save' | 'clear') {
  if (!props.available || disposed || busy.value || action !== 'load' && uncertain.value || action === 'save' && !directory.value.trim()) return
  const request = ++generation
  const current = () => !disposed && request === generation && props.available
  busy.value = action; error.value = ''; notice.value = ''
  try {
    const result = action === 'clear'
      ? await api<{ cache: CacheView; removed: number }>('/settings/thumbnail-cache/clear', { method: 'POST' })
      : await api<CacheView>('/settings/thumbnail-cache', action === 'save' ? { method: 'PUT', body: { directory: directory.value.trim() } } : {})
    if (!current()) return
    const value = 'cache' in result ? result.cache : result
    if (!valid(value)) throw new Error('缓存设置回执无法确认。')
    saved.value = value
    if (action !== 'clear') directory.value = value.directory
    uncertain.value = false
    if (action === 'clear') notice.value = '缩略图缓存已清理，再次浏览时会按需生成。'
    if (action === 'save') notice.value = '缓存保存位置已更新。'
  } catch (cause) {
    if (!current()) return
    if (action !== 'load' && !(cause instanceof ApiError && cause.status === 400)) uncertain.value = true
    error.value = (cause instanceof Error ? cause.message : '缓存操作未完成。') + (uncertain.value ? ' 请读取当前设置核对结果。' : '')
    if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
  } finally { if (current()) busy.value = '' }
}
watch(() => props.available, value => { generation++; busy.value = ''; if (value) void perform('load') }, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++ })
</script>

<template>
  <section class="settings-section thumbnail-cache-settings" aria-labelledby="thumbnail-cache-heading">
    <div class="section-heading"><span class="section-icon"><AppIcon name="photo" /></span><div><h2 id="thumbnail-cache-heading">缩略图缓存</h2><p>主帖封面与评论缩略图保存在本机，再次浏览可直接复用。</p></div></div>
    <div class="field"><label for="thumbnail-cache-directory">保存目录</label><input id="thumbnail-cache-directory" v-model="directory" class="input mono" :disabled="!available || !!busy || uncertain" autocomplete="off" spellcheck="false" placeholder="填写本机文件夹的完整路径" @keydown.enter.prevent="perform('save')" /></div>
    <div v-if="saved" class="cache-statistics"><span>{{ saved.files }} 张 · {{ formatMediaBytes(saved.bytes) }}</span><span>最多 {{ formatMediaBytes(saved.maximumBytes) }}，自动清理较早使用的缓存</span></div>
    <div v-if="error" class="notice warning" role="alert">{{ error }}</div>
    <p v-if="saved?.warning" class="notice warning" role="status">{{ saved.warning }}</p>
    <p v-if="notice" class="cache-notice" role="status">{{ notice }}</p>
    <div class="cache-actions">
      <button type="button" class="btn" :disabled="!available || !!busy || !saved || uncertain || !directory.trim() || directory===saved.directory" @click="perform('save')">{{ busy==='save' ? '正在保存…' : '保存位置' }}</button>
      <button type="button" class="btn" :disabled="!available || !!busy || !saved || uncertain" @click="perform('clear')">{{ busy==='clear' ? '正在清理…' : '清理缩略图缓存' }}</button>
      <button type="button" class="btn" :disabled="!available || !!busy" @click="perform('load')">{{ uncertain ? '读取当前设置' : '刷新统计' }}</button>
      <button v-if="saved && directory!==saved.defaultDirectory" type="button" class="cache-default" :disabled="!available || !!busy || uncertain" @click="directory=saved.defaultDirectory">使用默认位置</button>
    </div>
    <p class="section-footnote">清理只移除缓存图片。更换目录后，原位置的缓存会保留。</p>
  </section>
</template>

<style scoped>
.cache-statistics{display:flex;flex-wrap:wrap;gap:6px 18px;font-size:10px;line-height:1.8;color:#8ca1b1;margin:12px 0 16px}.cache-statistics>span:first-child{color:#577b96}.cache-actions{display:flex;align-items:center;flex-wrap:wrap;gap:8px}.cache-actions>.btn{font-size:10px}.cache-default{border:0;background:none;color:#779ab5;font-size:10px;cursor:pointer;padding:8px}.cache-notice{font-size:11px;line-height:1.8;color:#6b9b86;margin:10px 0}.thumbnail-cache-settings .input{width:100%;min-width:0}.thumbnail-cache-settings .notice{overflow-wrap:anywhere}.section-footnote{margin-top:14px}
</style>
