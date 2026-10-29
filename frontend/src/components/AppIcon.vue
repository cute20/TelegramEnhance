<script setup lang="ts">
defineProps<{ name: string }>()
const paths: Record<string, string> = {
  telegram: 'm3 10 18-7-5 18-5-7-8-4Zm8 4 6-7M11 14v5l3-3',
  library: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z',
  tasks: 'M8 4h12v16H4V8m0-4 2 2 4-4M8 11h8M8 15h8',
  rules: 'M4 6h16M4 18h16M4 12h16M8 3v6M16 9v6M9 15v6',
  settings: 'm10 3-1 3-3 1-2-1-2 4 2 2v2l-2 2 2 4 3-1 3 1 1 3h4l1-3 3-1 2 1 2-4-2-2v-2l2-2-2-4-3 1-3-1-1-3h-4ZM15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0',
  shield: 'm12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6l8-3Zm-4 9 3 3 5-6',
  arrow: 'M5 12h14m-5-5 5 5-5 5', link: 'm10 13 4-4M8 16l-1 1a4 4 0 0 1-6-6l5-5a4 4 0 0 1 6 0M16 8l1-1a4 4 0 0 1 6 6l-5 5a4 4 0 0 1-6 0',
  check: 'm5 12 4 4L19 6', info: 'M12 11v6m0-10v.1M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  server: 'M4 4h16v6H4zM4 14h16v6H4zM7 7h.1M7 17h.1M11 7h6M11 17h6',
  refresh: 'M20 7v5h-5M4 17v-5h5M6 6a8 8 0 0 1 13 3M18 18A8 8 0 0 1 5 15',
  power: 'M12 3v9M6 5a9 9 0 1 0 12 0', lock: 'M5 10h14v11H5zM8 10V7a4 4 0 0 1 8 0v3',
  plus: 'M12 5v14M5 12h14', close: 'm6 6 12 12M6 18 18 6', chevron: 'm9 5 7 7-7 7',
  clock: 'M12 7v5l3 2M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',
  search: 'M21 21l-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',
  list: 'M9 5h12M9 12h12M9 19h12M3 5h.1M3 12h.1M3 19h.1',
  tag: 'M3 3h8l10 10-8 8L3 11V3Zm4 4h.1',
  video: 'M3 5h13v14H3zM16 10l5-3v10l-5-3',
  photo: 'M3 3h18v18H3zM3 16l6-6 5 5 3-3 4 4M16 7h.1',
  file: 'M5 2h9l5 5v15H5zM14 2v6h5M8 12h8M8 16h8',
  audio: 'M9 18V5l11-2v13M9 9l11-2M9 18c0 2-6 3-6 0s6-3 6 0Zm11-2c0 2-6 3-6 0s6-3 6 0Z',
}
</script>
<template><svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="paths[name] ?? paths.info" /></svg></template>
