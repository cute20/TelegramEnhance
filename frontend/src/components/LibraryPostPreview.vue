<script lang="ts">
// Shared by every card and list item; keep native preview reads bounded across pages.
interface PreviewJob { current: () => boolean; run: () => Promise<void> }
const previewJobs: PreviewJob[] = []
let activePreviewReads = 0
function drainPreviews() {
  while (activePreviewReads < 2 && previewJobs.length) {
    const job = previewJobs.shift()!
    if (!job.current()) continue
    activePreviewReads++
    void Promise.resolve().then(job.run).catch(() => { /* A preview failure must not block the queue. */ }).finally(() => {
      activePreviewReads--
      drainPreviews()
    })
  }
}
function enqueuePreview(job: PreviewJob) {
  previewJobs.push(job)
  drainPreviews()
  return () => {
    const index = previewJobs.indexOf(job)
    if (index >= 0) previewJobs.splice(index, 1)
  }
}
const JPEG_PREFIX = 'data:image/jpeg;base64,'
function previewLimits(quality: unknown) {
  if (quality === 'THUMBNAIL') return { bytes: 128 * 1024, edge: 480 }
  if (quality === 'MINI') return { bytes: 32 * 1024, edge: 256 }
  return null
}
function validPreviewDataUrl(value: unknown, maxBytes: number): value is string {
  const maxBase64Length = 4 * Math.ceil(maxBytes / 3)
  if (typeof value !== 'string' || !value.startsWith(JPEG_PREFIX) || value.length > JPEG_PREFIX.length + maxBase64Length) return false
  const encoded = value.slice(JPEG_PREFIX.length)
  if (encoded.length < 4 || encoded.length % 4 !== 0 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded)) return false
  const padding = encoded.endsWith('==') ? 2 : encoded.endsWith('=') ? 1 : 0
  if (encoded.length / 4 * 3 - padding > maxBytes) return false
  try {
    const decoded = atob(encoded)
    return decoded.length >= 4 && decoded.length <= maxBytes && decoded.startsWith('\u00ff\u00d8\u00ff') && decoded.endsWith('\u00ff\u00d9')
  } catch { return false }
}
</script>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import type { PostPreview } from '../library'
import AppIcon from './AppIcon.vue'

const props = defineProps<{ postKey: string; accountId: string | null; version: number; authorized: boolean; available: boolean; icon: string; mediaKey?: string; lazy?: boolean; compact?: boolean; downloadPreviewId?: string; downloadPage?: number }>()
const previewRoot = ref<HTMLElement | null>(null)
const inView = ref(!props.lazy)
const imageSource = ref<string | null>(null)
const imageWidth = ref<number | undefined>()
const imageHeight = ref<number | undefined>()
const imageQuality = ref<PostPreview['quality']>(null)
const imageIdentity = ref(0)
const loading = ref(false)
const canRequest = computed(() => props.authorized && props.available && !!props.accountId && !!props.postKey && inView.value && (props.mediaKey === undefined || !!props.mediaKey)
  && (props.downloadPreviewId === undefined || (!!props.downloadPreviewId && !!props.mediaKey && Number.isSafeInteger(props.downloadPage) && props.downloadPage! >= 0)))
const imageLabel = computed(() => imageQuality.value === 'THUMBNAIL' ? '缩略图' : '低清预览')
const previewTitle = computed(() => imageSource.value ? `Telegram ${imageLabel.value}，仅缩略图预览` : loading.value ? '正在读取 Telegram 缩略图预览' : '暂无预览，当前显示媒体类型图标')
let generation = 0
let disposed = false
let cancelQueued: (() => void) | undefined
let visibilityObserver: IntersectionObserver | undefined
let visibilityGeneration = 0

function observeVisibility() {
  const observation = ++visibilityGeneration
  visibilityObserver?.disconnect(); visibilityObserver = undefined
  if (disposed) return
  if (!props.lazy || typeof IntersectionObserver === 'undefined') { inView.value = true; return }
  inView.value = false
  if (!previewRoot.value) return
  visibilityObserver = new IntersectionObserver(entries => {
    if (disposed || observation !== visibilityGeneration) return
    const entry = entries.filter(item => item.target === previewRoot.value).at(-1)
    if (entry) inView.value = entry.isIntersecting
  }, { rootMargin: '100px 0px', threshold: 0 })
  visibilityObserver.observe(previewRoot.value)
}
function releaseImage() { imageSource.value = null; imageWidth.value = undefined; imageHeight.value = undefined; imageQuality.value = null; imageIdentity.value = 0 }
function currentImage(event: Event) {
  const image = event.currentTarget as HTMLImageElement | null
  return image && image.getAttribute('src') === imageSource.value && image.dataset.previewGeneration === String(imageIdentity.value) ? image : null
}
function imageFailed(event: Event) {
  if (!currentImage(event)) return
  releaseImage()
  loading.value = false
}
function imageLoaded(event: Event) {
  const image = currentImage(event)
  if (!image) return
  const limits = previewLimits(imageQuality.value)
  if (!limits || image.naturalWidth !== imageWidth.value || image.naturalHeight !== imageHeight.value || image.naturalWidth > limits.edge || image.naturalHeight > limits.edge) imageFailed(event)
}

watch([() => props.postKey, () => props.mediaKey, () => props.accountId, () => props.version, () => props.authorized, () => props.available, () => props.downloadPreviewId, () => props.downloadPage, inView], () => {
  const requestGeneration = ++generation
  cancelQueued?.(); cancelQueued = undefined
  releaseImage(); loading.value = false
  if (!canRequest.value) return
  const { postKey, mediaKey, accountId, version, downloadPreviewId, downloadPage } = props
  const current = () => !disposed && requestGeneration === generation && canRequest.value && props.postKey === postKey && props.mediaKey === mediaKey && props.accountId === accountId && props.version === version
    && props.downloadPreviewId === downloadPreviewId && props.downloadPage === downloadPage
  loading.value = true
  cancelQueued = enqueuePreview({
    current,
    async run() {
      if (!current()) return
      try {
        const query = new URLSearchParams({ version: String(version) })
        if (mediaKey !== undefined) query.set('mediaKey', mediaKey)
        const route = mediaKey === undefined ? 'preview' : 'media-preview'
        if (downloadPreviewId !== undefined) query.set('pageNumber', String(downloadPage))
        const endpoint = downloadPreviewId === undefined ? `/library/posts/${encodeURIComponent(postKey)}/${route}`
          : `/transfers/download/preview/${encodeURIComponent(downloadPreviewId)}/thumbnail`
        const result = await api<PostPreview>(`${endpoint}?${query}`)
        if (!current()) return
        const limits = previewLimits(result?.quality)
        if (result?.status !== 'AVAILABLE' || !limits || !validPreviewDataUrl(result.dataUrl, limits.bytes)) return
        if (!Number.isInteger(result.width) || result.width <= 0 || result.width > limits.edge || !Number.isInteger(result.height) || result.height <= 0 || result.height > limits.edge) return
        imageWidth.value = result.width
        imageHeight.value = result.height
        imageQuality.value = result.quality
        imageIdentity.value = requestGeneration
        imageSource.value = result.dataUrl
      } catch { /* Keep the card usable without a preview; never log image or token data. */ }
      finally { if (current()) loading.value = false }
    },
  })
}, { immediate: true })

onMounted(observeVisibility)
watch(() => props.lazy, observeVisibility, { flush: 'post' })
onBeforeUnmount(() => { disposed = true; generation++; visibilityGeneration++; visibilityObserver?.disconnect(); visibilityObserver = undefined; cancelQueued?.(); cancelQueued = undefined; loading.value = false; releaseImage() })
</script>

<template>
  <div ref="previewRoot" :class="['post-preview', icon, { 'has-preview': imageSource, 'preview-loading': loading, 'compact-preview': compact }]" :title="previewTitle" :aria-busy="loading">
    <img v-if="imageSource" :key="imageIdentity" :data-preview-generation="imageIdentity" :src="imageSource" :width="imageWidth" :height="imageHeight" :alt="`Telegram ${imageLabel}`" loading="lazy" decoding="async" referrerpolicy="no-referrer" @load="imageLoaded" @error="imageFailed" />
    <AppIcon v-else :name="icon" />
    <span class="post-preview-caption">{{ imageSource ? imageLabel : loading ? '读取预览…' : '暂无预览' }}</span>
  </div>
</template>

<style scoped>
.post-preview{position:absolute;inset:0;display:grid;place-items:center;overflow:hidden}.post-preview>img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}.post-preview>.icon{width:38px;height:38px;stroke-width:1.15;color:#91b0c7}.post-preview.photo>.icon{color:#95b4a5}.post-preview.audio>.icon{color:#afa3c2}.post-preview.file>.icon{color:#bca984}.post-preview-caption{position:absolute;left:10px;top:9px;z-index:1;font-size:7px;line-height:1.6;color:#86a3b8;letter-spacing:.4px;white-space:nowrap}.post-preview.has-preview>.post-preview-caption{background:#1d344a6b;border-radius:3px;color:#f4f8fc;padding:2px 5px;letter-spacing:.2px}.preview-loading>.icon{opacity:.5}
.post-preview.compact-preview{position:relative;inset:auto;width:80px;height:60px;border-radius:4px;background:#eff4f8}.compact-preview>.icon{width:25px;height:25px}.compact-preview>.post-preview-caption{left:4px;top:3px;font-size:6px;letter-spacing:0}.compact-preview.has-preview>.post-preview-caption{padding:1px 3px}
</style>
