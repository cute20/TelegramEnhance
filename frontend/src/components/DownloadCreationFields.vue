<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { validSourceChannels, type DownloadChannels } from '../downloadChannel'
import { mediaTypes } from '../downloadPreview'
import type { DownloadCreationSelection } from '../transferCreation'
import SourceChannelSelect from './SourceChannelSelect.vue'
const props = defineProps<{ modelValue: DownloadCreationSelection; authorized: boolean; available: boolean; accountId: string | null; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: DownloadCreationSelection]; accessError: [cause: ApiError] }>()
const channels = ref<DownloadChannels | null>(null), loading = ref(false), error = ref('')
const ready = computed(() => props.authorized && props.available && !!props.accountId)
const all = computed(() => mediaTypes.every(item => props.modelValue.types.includes(item.type)))
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
      <fieldset class="media-options"><legend>评论媒体类型</legend><div class="source-options"><button type="button" :aria-pressed="all" :class="{selected:all}" @click="update({types:all?[]:mediaTypes.map(item=>item.type)})">全部</button><button v-for="item in mediaTypes" :key="item.type" type="button" :aria-pressed="modelValue.types.includes(item.type)" :class="{selected:modelValue.types.includes(item.type)}" @click="type(item.type)">{{ item.label }}</button></div><label class="inline-option"><input type="checkbox" :checked="modelValue.includeMain" @change="update({includeMain:($event.target as HTMLInputElement).checked})" />包含主帖自身全部媒体</label></fieldset>
      <label>保存目录<input class="input" :value="modelValue.targetDirectory" placeholder="例如 D:\TelegramDownloads" autocomplete="off" @input="update({targetDirectory:($event.target as HTMLInputElement).value})" /></label>
    </fieldset>
  </section>
</template>
<style scoped>
.creation-fields{min-width:0;margin:18px 0}.creation-fields fieldset{border:0;padding:0;min-width:0}.creation-fields label{display:flex;flex-direction:column;gap:7px;margin:15px 0;font-size:11px;color:#69879d}.creation-fields .input{width:100%;min-width:0}.source-options{display:flex;gap:7px;flex-wrap:wrap;margin:10px 0}.source-options button{border:1px solid #dce6ed;border-radius:6px;padding:8px 12px;background:#fff;color:#708fa6;font-size:11px}.source-options button.selected{background:#263f54;border-color:#263f54;color:#fff}.source-options button:disabled{opacity:.6}.channel-field{display:flex;align-items:end;gap:10px}.channel-field>.btn{flex-shrink:0;font-size:10px;margin-bottom:0}.field-columns{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:15px}.media-options{margin-top:18px}.media-options legend{font-size:11px;color:#69879d}.creation-fields .inline-option{flex-direction:row;align-items:center;gap:7px}.inline-option input{accent-color:#263f54}.field-error{font-size:10px;line-height:1.8;color:#a4824d}@media(max-width:600px){.field-columns{grid-template-columns:minmax(0,1fr);gap:0}.channel-field{align-items:stretch;flex-direction:column}.channel-field>.btn{align-self:flex-end}}
</style>
