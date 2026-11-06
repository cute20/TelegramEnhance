<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api'
import type { PostPreview } from '../library'
import AppIcon from './AppIcon.vue'
import { acquireThumbnail, readThumbnail, forgetThumbnail, subscribeThumbnailInvalidation, previewLimits, validThumbnailPreview } from '../thumbnailMemory'

const props = defineProps<{ postKey: string; accountId: string | null; version: number; authorized: boolean; available: boolean; icon: string; mediaKey?: string; cacheRevision?: string; lazy?: boolean; compact?: boolean; downloadPreviewId?: string; downloadPage?: number }>()
const previewRoot = ref<HTMLElement | null>(null)
const inView = ref(!props.lazy)
const imageSource = ref<string | null>(null)
const imageWidth = ref<number | undefined>()
const imageHeight = ref<number | undefined>()
const imageQuality = ref<PostPreview['quality']>(null)
const imageIdentity = ref(0)
const loading = ref(false)
const cacheEpoch = ref(0)
const cacheKey = computed(() => JSON.stringify(['thumbnail-v1', props.accountId,
  props.downloadPreviewId !== undefined ? 'download-media' : props.mediaKey === undefined ? 'library-cover' : 'library-media',
  props.postKey, props.mediaKey ?? null, props.downloadPreviewId ?? null, props.downloadPage ?? null,
  props.cacheRevision ?? props.version]))
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
  forgetThumbnail(cacheKey.value, { status: 'AVAILABLE', dataUrl: imageSource.value, quality: imageQuality.value, width: imageWidth.value ?? 0, height: imageHeight.value ?? 0 })
  releaseImage()
  loading.value = false
}
function imageLoaded(event: Event) {
  const image = currentImage(event)
  if (!image) return
  const limits = previewLimits(imageQuality.value)
  if (!limits || image.naturalWidth !== imageWidth.value || image.naturalHeight !== imageHeight.value || image.naturalWidth > limits.edge || image.naturalHeight > limits.edge) imageFailed(event)
}

function applyPreview(result: PostPreview, requestGeneration: number) {
  if (!validThumbnailPreview(result) || result.status !== 'AVAILABLE') { releaseImage(); return }
  imageWidth.value = result.width; imageHeight.value = result.height; imageQuality.value = result.quality
  if (imageSource.value !== result.dataUrl) { imageIdentity.value = requestGeneration; imageSource.value = result.dataUrl }
}
const unsubscribeCache = subscribeThumbnailInvalidation(() => {
  generation++; cancelQueued?.(); cancelQueued = undefined; releaseImage(); loading.value = false; cacheEpoch.value++
})
watch([cacheKey, () => props.version, () => props.authorized, () => props.available, inView, cacheEpoch], (values, before) => {
  const requestGeneration = ++generation
  cancelQueued?.(); cancelQueued = undefined
  // Offscreen rows release their image references; the bounded cache restores them without another request.
  if (!before || values[0] !== before[0] || !props.authorized || !props.available || !inView.value) releaseImage()
  loading.value = false
  if (!canRequest.value) return
  const { postKey, mediaKey, accountId, version, downloadPreviewId, downloadPage } = props
  const key = cacheKey.value
  const current = () => !disposed && requestGeneration === generation && canRequest.value && props.postKey === postKey && props.mediaKey === mediaKey && props.accountId === accountId && props.version === version
    && props.downloadPreviewId === downloadPreviewId && props.downloadPage === downloadPage && cacheKey.value === key
  const cached = readThumbnail(key)
  if (cached !== undefined) { applyPreview(cached, requestGeneration); return }
  loading.value = true
  const request = acquireThumbnail(key, async () => {
        const query = new URLSearchParams({ version: String(version) })
        if (mediaKey !== undefined) query.set('mediaKey', mediaKey)
        const route = mediaKey === undefined ? 'preview' : 'media-preview'
        if (downloadPreviewId !== undefined) query.set('pageNumber', String(downloadPage))
        const endpoint = downloadPreviewId === undefined ? `/library/posts/${encodeURIComponent(postKey)}/${route}`
          : `/transfers/download/preview/${encodeURIComponent(downloadPreviewId)}/thumbnail`
        return api<PostPreview>(`${endpoint}?${query}`)
  })
  cancelQueued = request.cancel
  void request.promise.then(result => { if (current()) applyPreview(result, requestGeneration) })
    .catch(() => { /* Keep the card usable without a preview; cancellation never cancels another subscriber. */ })
    .finally(() => { if (current()) loading.value = false })
}, { immediate: true })

onMounted(observeVisibility)
watch(() => props.lazy, observeVisibility, { flush: 'post' })
onBeforeUnmount(() => { disposed = true; generation++; visibilityGeneration++; visibilityObserver?.disconnect(); visibilityObserver = undefined; unsubscribeCache(); cancelQueued?.(); cancelQueued = undefined; loading.value = false; releaseImage() })
</script>

<template>
  <div ref="previewRoot" :class="['post-preview', icon, { 'has-preview': imageSource, 'preview-loading': loading, 'compact-preview': compact }]" :title="previewTitle" :aria-busy="loading">
    <img v-if="imageSource" :key="imageIdentity" :data-preview-generation="imageIdentity" :src="imageSource" :width="imageWidth" :height="imageHeight" :alt="`Telegram ${imageLabel}`" loading="lazy" decoding="async" referrerpolicy="no-referrer" @load="imageLoaded" @error="imageFailed" />
    <template v-else><span class="post-preview-decoration" aria-hidden="true" /><AppIcon :name="icon" /></template>
    <span class="post-preview-caption">{{ imageSource ? imageLabel : loading ? '读取预览…' : '暂无预览' }}</span>
  </div>
</template>

<style scoped>
.post-preview{position:absolute;inset:0;display:grid;place-items:center;overflow:hidden}.post-preview>img{position:absolute;inset:0;width:100%;height:100%;object-fit:cover}.post-preview>.icon{position:relative;width:44px;height:44px;stroke-width:1.3;color:#7a9bb9;z-index:1}.post-preview.photo>.icon{color:#779c8b}.post-preview.audio>.icon{color:#9586b4}.post-preview.file>.icon{color:#aa9670}.post-preview-caption{position:absolute;left:12px;top:11px;z-index:1;font-size:11px;line-height:1.6;color:#5f7d95;letter-spacing:.2px;white-space:nowrap}.post-preview.has-preview>.post-preview-caption{background:#17344b99;border:1px solid #ffffff30;border-radius:4px;color:#fff;padding:2px 6px;backdrop-filter:blur(4px)}.preview-loading>.icon{opacity:.45}.post-preview-decoration{position:absolute;width:88px;height:88px;border:1px solid #ffffffa8;border-radius:22px;transform:rotate(-13deg);background:#ffffff25}.post-preview-decoration:after{content:'';position:absolute;inset:10px;border:1px solid #ffffff70;border-radius:16px;transform:rotate(26deg)}
.post-preview.compact-preview{position:relative;inset:auto;width:80px;height:60px;border-radius:5px;background:#eff4f8}.compact-preview>.icon{width:25px;height:25px}.compact-preview>.post-preview-caption{left:4px;top:3px;font-size:9px;letter-spacing:0}.compact-preview.has-preview>.post-preview-caption{padding:1px 3px}.compact-preview .post-preview-decoration{width:42px;height:42px;border-radius:10px}.compact-preview .post-preview-decoration:after{inset:5px;border-radius:7px}
</style>
