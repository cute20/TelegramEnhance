<script setup lang="ts">
import AppIcon from './AppIcon.vue'
defineProps<{ modelValue: 'list' | 'cards' }>()
const emit = defineEmits<{ 'update:modelValue': [value: 'list' | 'cards'] }>()
</script>
<template><div class="media-view-switch" role="group" aria-label="媒体展示方式"><button type="button" :aria-pressed="modelValue === 'list'" :class="{ selected: modelValue === 'list' }" @click="emit('update:modelValue', 'list')"><AppIcon name="list" />列表</button><button type="button" :aria-pressed="modelValue === 'cards'" :class="{ selected: modelValue === 'cards' }" @click="emit('update:modelValue', 'cards')"><AppIcon name="library" />卡片</button></div></template>
<style scoped>.media-view-switch{display:flex;flex-shrink:0;gap:3px;border:1px solid #dfe8ef;border-radius:5px;padding:3px;background:#fff}.media-view-switch button{display:flex;align-items:center;gap:5px;border:0;background:transparent;color:#8ca3b4;border-radius:3px;padding:5px 8px;font-size:10px}.media-view-switch button.selected{background:#eaf3fb;color:#407eaf}.media-view-switch .icon{width:13px;height:13px}.media-view-switch button:focus-visible{outline:2px solid #2986cc;outline-offset:2px}</style>
