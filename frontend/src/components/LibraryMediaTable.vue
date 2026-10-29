<script setup lang="ts">
import { computed } from 'vue'
import { formatMediaBytes, originalMessageLink, type Media } from '../library'
import AppIcon from './AppIcon.vue'
import LibraryPostPreview from './LibraryPostPreview.vue'
const props = defineProps<{ items: Media[]; emptyText: string; postKey?: string; accountId?: string | null; authorized?: boolean; available?: boolean; version?: number }>()
const hasPreviewContext = computed(() => !!props.postKey)
const mediaLabels: Record<string, string> = { VIDEO: '视频', PHOTO: '图片', AUDIO: '音频', DOCUMENT: '文档', OTHER: '其他' }
function previewable(media: Media) { return media.source === 'COMMENT' && ['PHOTO', 'VIDEO'].includes(media.type) }
function mediaIcon(media: Media) { return ({ PHOTO: 'photo', VIDEO: 'video', AUDIO: 'audio' } as Record<string, string>)[media.type] ?? 'file' }
</script>

<template>
  <div v-if="!items.length" class="probe-inline-empty">{{ emptyText }}</div>
  <div v-else class="probe-table-scroll">
    <table :class="['probe-media-table', { 'with-comment-previews': hasPreviewContext }]">
      <thead><tr><th scope="col">媒体与说明</th><th scope="col">类型 / 大小</th><th scope="col">消息身份</th><th scope="col">定位</th></tr></thead>
      <tbody>
        <tr v-for="media in items" :key="media.key">
          <td><div class="media-cell"><div v-if="hasPreviewContext && media.source === 'COMMENT'" class="media-row-preview"><LibraryPostPreview v-if="previewable(media)" :post-key="postKey ?? ''" :media-key="media.key" :account-id="accountId ?? null" :authorized="authorized ?? false" :available="available ?? false" :version="version ?? 0" :icon="mediaIcon(media)" lazy compact /><AppIcon v-else :name="mediaIcon(media)" /><span v-if="media.type === 'VIDEO'" class="media-video-indicator" aria-hidden="true" /></div><div class="media-row-text"><strong class="probe-file-name">{{ media.fileName || '未提供文件名' }}</strong><p v-if="media.caption" class="probe-media-caption">{{ media.caption }}</p><small class="probe-source">{{ media.source === 'MAIN' ? '主帖媒体' : media.source === 'COMMENT' ? '评论媒体' : media.source }}</small></div></div></td>
          <td><span class="probe-media-type">{{ mediaLabels[media.type] ?? media.type }}</span><small>{{ formatMediaBytes(media.size) }}</small></td>
          <td class="probe-message-ids"><span>聊天 <code>{{ media.message.chatId }}</code></span><span>消息 <code>{{ media.message.messageId }}</code></span></td>
          <td><a v-if="originalMessageLink(media.message.link)" :href="originalMessageLink(media.message.link)" target="_blank" rel="noopener noreferrer" class="probe-message-link">原消息 ↗</a><span v-else class="probe-link-unavailable">无可用链接</span></td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.media-cell{display:flex;align-items:flex-start;gap:10px;min-width:0}.media-row-text{flex:1;min-width:0}.media-row-preview{position:relative;display:grid;place-items:center;flex:0 0 80px;width:80px;height:60px;border-radius:4px;background:#eff4f8;overflow:hidden}.media-row-preview>.icon{width:25px;height:25px;color:#9aadbb;stroke-width:1.2}.media-video-indicator{position:absolute;right:4px;bottom:4px;display:grid;place-items:center;width:17px;height:17px;background:#20394db3;border:1px solid #ffffff70;border-radius:50%;pointer-events:none}.media-video-indicator:before{content:'';width:0;height:0;border-top:4px solid transparent;border-bottom:4px solid transparent;border-left:6px solid #fff;margin-left:2px}.probe-media-table.with-comment-previews{min-width:650px}
</style>
