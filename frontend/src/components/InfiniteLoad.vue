<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
const props = defineProps<{ hasMore: boolean; busy: boolean; blocked?: boolean; version: string | number; context: string; loadingLabel?: string }>()
const emit = defineEmits<{ load: [] }>()
const sentinel = ref<HTMLElement | null>(null)
const visible = ref(false)
let observer: IntersectionObserver | undefined
let disposed = false
let requested = ''
let observationGeneration = 0
let observedIdentity = ''
function maybeLoad() {
  const identity = props.context + ':' + props.version
  if (disposed || observedIdentity !== identity || !visible.value || !props.hasMore || props.busy || props.blocked || requested === identity) return
  requested = identity
  emit('load')
}
function observe() {
  const observation = ++observationGeneration
  observedIdentity = props.context + ':' + props.version
  observer?.disconnect(); observer = undefined; visible.value = false
  if (disposed || !sentinel.value || typeof IntersectionObserver === 'undefined') return
  const target = sentinel.value
  observer = new IntersectionObserver(entries => {
    if (disposed || observation !== observationGeneration || target !== sentinel.value) return
    const entry = entries.filter(item => item.target === target).at(-1)
    if (entry) { visible.value = entry.isIntersecting; maybeLoad() }
  }, { root: target.closest('dialog'), rootMargin: '180px 0px', threshold: 0 })
  observer.observe(target)
}
onMounted(observe)
watch([() => props.busy, () => props.hasMore, () => props.blocked], maybeLoad, { flush: 'post' })
watch([() => props.version, () => props.context], (_next, previous) => {
  if (props.context !== previous[1]) requested = ''
  observe()
}, { flush: 'post' })
onBeforeUnmount(() => { disposed = true; observationGeneration++; observer?.disconnect(); observer = undefined })
</script>
<template><div class="infinite-load"><div ref="sentinel" class="scroll-sentinel" aria-hidden="true" /><span v-if="busy" role="status">{{ loadingLabel ?? '正在加载更多媒体…' }}</span><span v-else-if="hasMore && !blocked">继续向下滚动加载</span></div></template>
<style scoped>.infinite-load{position:relative;text-align:center;color:#95a9b8;font-size:10px;line-height:1.9;padding:15px 0 4px}.scroll-sentinel{height:1px;width:100%;pointer-events:none}</style>
