<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { validSourceChannels, downloadDatesError, type DownloadChannels } from '../downloadChannel'
import { mediaTypes } from '../downloadPreview'
import type { DownloadCreationSelection } from '../transferCreation'
import SourceChannelSelect from './SourceChannelSelect.vue'
import AppIcon from './AppIcon.vue'
const props = defineProps<{ modelValue: DownloadCreationSelection; authorized: boolean; available: boolean; accountId: string | null; disabled: boolean; dateFiltering?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: DownloadCreationSelection]; accessError: [cause: ApiError] }>()
const channels = ref<DownloadChannels | null>(null), loading = ref(false), error = ref('')
const ready = computed(() => props.authorized && props.available && !!props.accountId)
const all = computed(() => mediaTypes.every(item => props.modelValue.types.includes(item.type)))
const datesError = computed(() => props.dateFiltering!==false && props.modelValue.entry!=='POST' ? downloadDatesError(props.modelValue.startDate,props.modelValue.endDate) : '')
let generation = 0, disposed = false, attempted = false
function update(patch: Partial<DownloadCreationSelection>) {
  if (props.disabled || !ready.value) return
  emit('update:modelValue', { ...props.modelValue, ...patch })
}
function entry(value: DownloadCreationSelection['entry']) {
  if (value === props.modelValue.entry) return
  update({ entry: value, source: '', tags: '', keywords: '', suggestedName: '' })
}
function source(value: string) {
  update({ source: value, suggestedName: channels.value?.items.find(item => item.id === value)?.title ?? '' })
}
function type(value: DownloadCreationSelection['types'][number]) {
  const types = props.modelValue.types
  update({ types: types.includes(value) ? types.filter(item => item !== value) : [...types, value] })
}
async function load() {
  if (!ready.value || loading.value || props.disabled) return
  attempted = true
  const current = ++generation, account = props.accountId
  loading.value = true; error.value = ''
  try {
    const result = await api<DownloadChannels>('/transfers/download/channels', { timeout: 50000 })
    if (disposed || current !== generation || !ready.value || account !== props.accountId) return
    if (!validSourceChannels(result)) throw new Error('频道列表无法确认，请重试。')
    channels.value = result
  } catch (cause) {
    if (disposed || current !== generation) return
    error.value = cause instanceof Error ? cause.message : '频道读取未完成。'
    if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
  } finally { if (!disposed && current === generation) loading.value = false }
}
function ensure() { if (ready.value && props.modelValue.entry === 'CHANNEL' && !channels.value && !attempted && !props.disabled) void load() }
watch([() => props.authorized, () => props.accountId], () => { generation++; channels.value = null; loading.value = false; error.value = ''; attempted = false; ensure() })
watch(() => props.available, value => { if (!value) { generation++; loading.value = false; if (!channels.value) attempted = false } else ensure() })
watch([() => props.modelValue.entry, () => props.disabled], ensure, { immediate: true })
onBeforeUnmount(() => { disposed = true; generation++ })
</script>
<template>
  <section class="creation-fields" aria-label="下载设置">
    <fieldset :disabled="disabled || !ready">
      <div class="source-options" role="group" aria-label="下载范围">
        <button type="button" :aria-pressed="modelValue.entry === 'CHANNEL'" :class="{selected:modelValue.entry === 'CHANNEL'}" @click="entry('CHANNEL')">频道筛选</button>
        <button type="button" :aria-pressed="modelValue.entry === 'POST'" :class="{selected:modelValue.entry === 'POST'}" @click="entry('POST')">指定主帖</button>
        <button type="button" :aria-pressed="modelValue.entry === 'MANUAL'" :class="{selected:modelValue.entry === 'MANUAL'}" @click="entry('MANUAL')">手动输入频道</button>
      </div>
      <div v-if="modelValue.entry === 'CHANNEL'" class="channel-field"><SourceChannelSelect :model-value="modelValue.source" :channels="channels?.items ?? []" :loading="loading" :disabled="disabled || !ready" @update:model-value="source" /><button type="button" class="btn" :disabled="loading" @click="load">{{ loading ? '读取中…' : '刷新频道' }}</button></div>
      <label v-else-if="modelValue.entry === 'POST'">主帖链接<input class="input" :value="modelValue.source" placeholder="https://t.me/频道名/消息编号" maxlength="2048" autocomplete="off" @input="update({source:($event.target as HTMLInputElement).value})" /></label>
      <label v-else>频道来源<input class="input" :value="modelValue.source" placeholder="频道链接、@用户名、频道ID或私有频道主帖链接" maxlength="2048" autocomplete="off" @input="source(($event.target as HTMLInputElement).value)" /></label>
      <p v-if="error" class="field-error" role="status">{{ error }}</p>
      <div v-if="modelValue.entry !== 'POST'" class="field-columns"><label>主帖标签 · 全部满足<input class="input" :value="modelValue.tags" placeholder="留空不限标签" maxlength="2048" @input="update({tags:($event.target as HTMLInputElement).value})" /></label><label>主帖关键词 · 全部包含<input class="input" :value="modelValue.keywords" placeholder="留空不限关键词" maxlength="2048" @input="update({keywords:($event.target as HTMLInputElement).value})" /></label></div>
      <details v-if="dateFiltering!==false && modelValue.entry!=='POST'" class="download-date-filters"><summary>高级筛选 · 发布时间</summary><div class="field-columns"><label>开始日期<input type="date" class="input" :value="modelValue.startDate??''" :max="modelValue.endDate||undefined" @input="update({startDate:($event.target as HTMLInputElement).value})" /></label><label>结束日期<input type="date" class="input" :value="modelValue.endDate??''" :min="modelValue.startDate||undefined" @input="update({endDate:($event.target as HTMLInputElement).value})" /></label></div><small>日期范围用于频道历史扫描，按主帖发布时间和北京时间筛选，包含起止当天；留空则不限。</small><p v-if="datesError" class="field-error" role="alert">{{datesError}}</p></details>
      <small v-else-if="modelValue.startDate||modelValue.endDate">日期范围用于频道历史扫描，当前指定主帖或自动规则不应用该条件；切回频道扫描时保留原日期。</small>
      <fieldset class="media-options">
        <legend>评论媒体类型 <span>可多选</span></legend>
        <div class="source-options media-type-options" role="group" aria-label="评论媒体类型">
          <button type="button" :aria-pressed="all" :class="{selected:all}" @click="update({types:all?[]:mediaTypes.map(item=>item.type)})"><AppIcon name="check" />全部</button>
          <button v-for="item in mediaTypes" :key="item.type" type="button" :aria-pressed="modelValue.types.includes(item.type)" :class="{selected:modelValue.types.includes(item.type)}" @click="type(item.type)"><AppIcon name="check" />{{ item.label }}</button>
        </div>
        <label class="main-media-option" :class="{selected:modelValue.includeMain}">
          <span class="main-media-copy"><strong>包含主帖自身全部媒体</strong><small>同时包含主帖中的图片、视频等附件，不受上方评论类型筛选限制。</small></span>
          <input type="checkbox" role="switch" aria-label="包含主帖自身全部媒体" :checked="modelValue.includeMain" @change="update({includeMain:($event.target as HTMLInputElement).checked})" />
        </label>
      </fieldset>
      <label>保存目录<input class="input" :value="modelValue.targetDirectory" placeholder="例如 D:\TelegramDownloads" autocomplete="off" @input="update({targetDirectory:($event.target as HTMLInputElement).value})" /></label>
    </fieldset>
  </section>
</template>
<style scoped>
.creation-fields{min-width:0;margin:18px 0}
.creation-fields fieldset{border:0;padding:0;min-width:0}
.creation-fields label{display:flex;flex-direction:column;gap:7px;margin:15px 0;font-size:13px;color:#69879d}
.creation-fields .input{width:100%;min-width:0}
.source-options{display:flex;gap:8px;flex-wrap:wrap;margin:10px 0}
.source-options button{display:inline-flex;align-items:center;justify-content:center;gap:6px;min-height:38px;border:1px solid #d9e4ee;border-radius:7px;padding:8px 13px;background:#fff;color:#607f97;font-size:13px;line-height:1.5;transition:background .15s,border-color .15s,color .15s}
.source-options button:hover:enabled{border-color:#a9cbe7;background:#f4f8fd}
.source-options button.selected{background:#eaf3ff;border-color:#a8ccee;color:#246ca9;font-weight:600}
.source-options button:focus-visible{outline:2px solid #277fce;outline-offset:3px}
.source-options button:disabled{opacity:.55;cursor:not-allowed}
.channel-field{display:flex;align-items:end;gap:10px}
.channel-field>.btn{flex-shrink:0;font-size:12px;margin-bottom:0}
.field-columns{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:15px}
.media-options{margin-top:22px}
.media-options legend{font-size:13px;color:#69879d}
.media-options legend>span{margin-left:6px;font-size:12px;color:#8b9eae}
.media-type-options button>.icon{width:14px;height:14px;opacity:0}
.media-type-options button.selected>.icon{opacity:1}
.creation-fields .main-media-option{flex-direction:row;align-items:center;justify-content:space-between;gap:20px;padding:13px 15px;margin:16px 0 20px;border:1px solid #e0e8f0;border-radius:9px;background:#f8fafc;cursor:pointer;transition:background .15s,border-color .15s}
.creation-fields .main-media-option.selected{background:#f1f7fe;border-color:#bed8ee}
.main-media-option:hover{border-color:#a9cbe7}
.main-media-copy{display:flex;flex-direction:column;gap:3px;min-width:0}
.main-media-copy strong{font-size:13px;font-weight:600;line-height:1.7;color:#365b78}
.main-media-copy small{font-size:12px;line-height:1.7;color:#748ca0}
.main-media-option input{appearance:none;position:relative;flex:0 0 38px;width:38px;height:22px;border:1px solid #c3cfdb;border-radius:11px;margin:0;background:#c3cfdb;cursor:pointer;transition:background .15s,border-color .15s}
.main-media-option input::before{content:'';position:absolute;top:2px;left:2px;width:16px;height:16px;border-radius:50%;background:#fff;box-shadow:0 1px 3px #24425a26;transition:transform .15s}
.main-media-option input:checked{background:#277fce;border-color:#277fce}
.main-media-option input:checked::before{transform:translateX(16px)}
.main-media-option input:focus-visible{outline:2px solid #277fce;outline-offset:3px}
.main-media-option:has(input:disabled){opacity:.55;cursor:not-allowed}
.main-media-option input:disabled{cursor:not-allowed}
.field-error{font-size:12px;line-height:1.8;color:#a4824d}
@media(max-width:600px){.field-columns{grid-template-columns:minmax(0,1fr);gap:0}.channel-field{align-items:stretch;flex-direction:column}.channel-field>.btn{align-self:flex-end}.source-options{gap:6px}.source-options button{padding:8px 11px}.creation-fields .main-media-option{gap:12px;padding:12px}}
@media(forced-colors:active){.main-media-option input{appearance:auto}.main-media-option input::before{display:none}}
@media(prefers-reduced-motion:reduce){.main-media-option input::before{transition:none}}
</style>
