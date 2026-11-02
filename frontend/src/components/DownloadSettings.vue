<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import AppIcon from './AppIcon.vue'

type Policy = 'KEEP_DUPLICATES_V2' | 'HISTORY_SKIP_V1'
interface DownloadPreferences { duplicatePolicy: Policy }
const props = withDefaults(defineProps<{ available: boolean; active?: boolean }>(), { active: true })
const emit = defineEmits<{ accessError: [cause: ApiError] }>()
const saved = ref<DownloadPreferences | null>(null), policy = ref<Policy>('KEEP_DUPLICATES_V2')
const busy = ref(''), error = ref(''), notice = ref(''), uncertain = ref(false)
const dirty = computed(() => !!saved.value && policy.value !== saved.value.duplicatePolicy)
const ready = () => !disposed && props.available && props.active
let disposed = false, readEpoch = 0, readQueued = false
const valid = (value: unknown): value is DownloadPreferences => !!value && typeof value === 'object'
  && ['KEEP_DUPLICATES_V2', 'HISTORY_SKIP_V1'].includes((value as DownloadPreferences).duplicatePolicy)

async function perform(action: 'load' | 'save') {
  if (!ready() || busy.value || action === 'save' && (!saved.value || !dirty.value || uncertain.value || !valid({ duplicatePolicy: policy.value }))) return
  const epoch = readEpoch, wasDirty = dirty.value, draft = policy.value
  if (action === 'load') readQueued = false
  busy.value = action; error.value = ''; notice.value = ''
  // A response lost after PUT must be resolved through GET, including a brief local outage.
  if (action === 'save') uncertain.value = true
  try {
    const result = await api<unknown>('/settings/downloads', action === 'save' ? { method: 'PUT', body: { duplicatePolicy: draft } } : {})
    if (disposed || action === 'load' && (epoch !== readEpoch || !ready())) return
    if (action === 'save' && !props.available) throw new Error('保存结果尚未核对。')
    if (!valid(result) || action === 'save' && result.duplicatePolicy !== draft) throw new Error('下载设置回执无法确认。')
    saved.value = result
    if (action === 'save' || uncertain.value || !wasDirty && policy.value === draft) policy.value = result.duplicatePolicy
    uncertain.value = false
    if (action === 'save') notice.value = '下载设置已保存，仅影响新创建的任务。'
  } catch (cause) {
    if (disposed || action === 'load' && (epoch !== readEpoch || !ready())) return
    if (action === 'save' && cause instanceof ApiError && cause.status >= 400 && cause.status < 500) uncertain.value = false
    error.value = (cause instanceof Error ? cause.message : '下载设置操作未完成。') + (uncertain.value ? ' 请读取当前设置核对结果，不会自动重新保存。' : '')
    if (cause instanceof ApiError && [401, 403].includes(cause.status)) emit('accessError', cause)
  } finally {
    if (!disposed) {
      busy.value = ''
      if (readQueued && ready()) { readQueued = false; void perform('load') }
    }
  }
}
watch([() => props.available, () => props.active], () => {
  readEpoch++
  if (!ready()) return
  if (busy.value === 'load') readQueued = true
  else if (!busy.value) void perform('load')
}, { immediate: true })
onBeforeUnmount(() => { disposed = true; readEpoch++; readQueued = false })
</script>

<template>
  <section class="settings-section download-settings" aria-labelledby="download-settings-heading">
    <div class="section-heading"><span class="section-icon"><AppIcon name="file" /></span><div><h2 id="download-settings-heading">重复媒体</h2></div></div>
    <fieldset class="duplicate-options" :disabled="!available || !active || !saved || busy==='save' || uncertain" aria-label="下载重复媒体时">
      <label><input v-model="policy" type="radio" name="download-duplicate-policy" value="KEEP_DUPLICATES_V2" />继续下载，放入“重复媒体”文件夹</label>
      <label><input v-model="policy" type="radio" name="download-duplicate-policy" value="HISTORY_SKIP_V1" />不下载重复媒体</label>
    </fieldset>
    <p v-if="policy==='HISTORY_SKIP_V1'" class="section-footnote">已确认的重复媒体直接跳过；首次遇到的文件需校验后判断。</p>
    <p class="section-footnote">仅影响新任务；已有任务保持原设置。</p>
    <p v-if="error" class="notice warning" role="alert">{{ error }}</p>
    <p v-if="notice" class="download-settings-notice" role="status">{{ notice }}</p>
    <div class="download-settings-actions">
      <span v-if="busy==='load'" role="status">正在读取…</span><span v-else-if="busy==='save'" role="status">正在保存…</span><span v-else-if="dirty && !uncertain">有未保存更改</span>
      <button v-if="!uncertain" type="button" class="btn" :disabled="!available || !active || !!busy || !dirty" @click="perform('save')">{{ busy==='save'?'正在保存…':'保存下载设置' }}</button>
      <button type="button" class="btn" :disabled="!available || !active || !!busy" @click="perform('load')">{{ uncertain?'读取当前设置核对':'刷新' }}</button>
    </div>
  </section>
</template>

<style scoped>
.download-settings{min-width:0}.duplicate-options{display:grid;gap:9px;border:0;padding:0;margin:0}.duplicate-options label{display:flex;align-items:flex-start;gap:8px;font-size:11px;line-height:1.7;color:#5d7c92;cursor:pointer;overflow-wrap:anywhere}.duplicate-options input{accent-color:#287fc9;flex:none;margin:3px 0 0}.download-settings-actions{display:flex;align-items:center;flex-wrap:wrap;gap:7px;margin-top:10px}.download-settings-actions>span{font-size:10px;color:#8fa2b0;flex-basis:100%}.download-settings-notice{font-size:10px;line-height:1.7;color:#65917b;margin:8px 0}.download-settings .notice{overflow-wrap:anywhere;margin:8px 0}.download-settings .section-footnote{margin:9px 0}
</style>
