<script setup lang="ts">
import { ruleArchiveDirectory } from '../rules'
import type { UploadCreationSelection } from '../transferCreation'
const props = defineProps<{ modelValue: UploadCreationSelection; disabled: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: UploadCreationSelection] }>()
function update(patch: Partial<UploadCreationSelection>) { if (!props.disabled) emit('update:modelValue', { ...props.modelValue, ...patch }) }
</script>
<template>
  <section class="creation-fields" aria-label="上传设置"><fieldset :disabled="disabled">
    <label>目标主帖链接<input class="input" :value="modelValue.link" placeholder="https://t.me/…" autocomplete="off" @input="update({link:($event.target as HTMLInputElement).value})" /></label>
    <div class="field-columns"><label>待上传目录<input class="input" :value="modelValue.sourceDirectory" placeholder="例如 D:\待上传" autocomplete="off" @input="update({sourceDirectory:($event.target as HTMLInputElement).value})" /></label><label>已上传归档目录<input class="input" :value="ruleArchiveDirectory(modelValue.sourceDirectory)" placeholder="自动生成：原目录名-已上传" readonly /></label></div>
    <div class="upload-options"><label class="inline-option"><input type="checkbox" :checked="modelValue.recursive" @change="update({recursive:($event.target as HTMLInputElement).checked})" />包含子目录</label><label>媒体说明<select class="input" :value="modelValue.captionMode" @change="update({captionMode:($event.target as HTMLSelectElement).value as 'FILENAME'|'EMPTY'})"><option value="FILENAME">使用文件名</option><option value="EMPTY">留空</option></select></label></div>
    <p>不同媒体类型分别分组，正式确认上传成功后归档。</p>
  </fieldset></section>
</template>
<style scoped>
.creation-fields{min-width:0;margin:18px 0}.creation-fields fieldset{border:0;padding:0;min-width:0}.creation-fields label{display:flex;flex-direction:column;gap:7px;margin:15px 0;font-size:11px;color:#69879d}.creation-fields .input{width:100%;min-width:0}.field-columns{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:15px}.upload-options{display:flex;gap:20px;align-items:center;flex-wrap:wrap}.creation-fields .inline-option{flex-direction:row;gap:7px;align-items:center}.inline-option input{accent-color:#263f54}.creation-fields p{font-size:10px;line-height:1.9;color:#839dae}@media(max-width:600px){.field-columns{grid-template-columns:minmax(0,1fr);gap:0}.upload-options{gap:12px}}
</style>
